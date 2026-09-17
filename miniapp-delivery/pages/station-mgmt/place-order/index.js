// ⚠️ 直接用 utils/request，不引 api/station-mgmt.js 与 config/api.js：
// 那两个文件正被另一个工作流（商品图片库）改动，共用会让两边未提交的改动纠缠在一起。
// 路径常量写在本文件里，理由同上。
const { get, post } = require('../../../utils/request')

const ASSIST = '/api/manager/order-assist'
const PRODUCTS_PATH = '/api/products/sale-by-station'
// 建单复用顾客端同一个端点：它本来就支持员工代客下单，且已带
// 「客户确属本水站」「地址必须属于该客户」两道护栏。**不要再另写建单端点。**
const ORDER_CREATE_PATH = '/api/orders/create'
// 来源：1电话 / 2微信 / 3小程序。站长代录属于"电话叫水"这一类，固定 1。
const SOURCE_PHONE = 1

/**
 * 代客下单（站长）：电话/口头订单当场录进系统。规格见 docs/design/20 §5。
 *
 * ⚠️ 为什么必须有这个页面（不是"锦上添花"）：
 *   本项目的核心资产是**桶账真实** —— 所有桶的流动都过 BarrelLedgerService。
 *   一旦站长在电话里接了单却不录进系统，这批桶的流动就不入库：顾客端看不到、
 *   桶账不更新、对账不平。**这个页面是那个前提的唯一支柱。**
 *
 * ⚠️ 三条口径，改这个页面时必须守住：
 *   1. **金额一律由服务端算**（/api/manager/order-assist/quote 与建单共用同一个实现），
 *      前端只展示。本文件里没有任何金额算术。
 *   2. **支付方式的选项与文案由服务端下发**（quote 返回的 methods / defaultMethod），
 *      前端禁止自带 1/2/3 映射表 —— 历史上两端各写一套，导致新客下单 100% 失败。
 *   3. 缺货（needConfirm）必须让站长明确确认后才带 confirmShortage 重提；
 *      重提时**复用同一个幂等键**，避免"第一次其实建成功了、重试又建一单"。
 */
Page({
  data: {
    stationId: null,
    // 1 选客户 → 2 选地址 → 3 选商品并试算
    step: 1,
    keyword: '',
    customers: [],
    customer: null,
    addresses: [],
    addressId: null,
    addressText: '',
    products: [],
    cartLines: [],
    totalQty: 0,
    methods: [],
    selectedMethod: null,
    quote: null,
    loading: true,
    quoting: false,
    submitting: false
  },

  onShow() {
    const app = getApp()
    if (!app.canAccessStationBusiness()) {
      app.routeByRole(true)
      return
    }
    const stationId = (app.globalData.userInfo || {}).stationId
    if (!stationId) {
      wx.showToast({ title: '未识别到所属水站', icon: 'none' })
      return
    }
    this.setData({ stationId })
    this.loadCustomers()
    this.loadProducts()
  },

  async loadCustomers() {
    this.setData({ loading: true })
    try {
      const kw = this.data.keyword ? '?keyword=' + encodeURIComponent(this.data.keyword) : ''
      const res = await get(ASSIST + '/customers' + kw)
      this.setData({ customers: res.data || [] })
    } catch (err) {
      wx.showToast({ title: err.message || '客户加载失败', icon: 'none' })
    } finally {
      this.setData({ loading: false })
    }
  },

  async loadProducts() {
    try {
      const res = await get(PRODUCTS_PATH + '?stationId=' + this.data.stationId)
      this.setData({ products: res.data || [] })
    } catch (err) {
      wx.showToast({ title: err.message || '商品加载失败', icon: 'none' })
    }
  },

  onKeyword(e) {
    this.setData({ keyword: e.detail.value })
  },

  onSearch() {
    this.loadCustomers()
  },

  async onPickCustomer(e) {
    const id = Number(e.currentTarget.dataset.id)
    const customer = this.data.customers.find(c => c.id === id)
    if (!customer) return
    // 换客户要清空下游选择：地址与报价都是"跟着客户走"的
    this.setData({
      customer,
      step: 2,
      addresses: [],
      addressId: null,
      addressText: '',
      quote: null,
      methods: [],
      selectedMethod: null
    })
    try {
      const res = await get(ASSIST + '/customers/' + id + '/addresses')
      const addresses = res.data || []
      this.setData({ addresses })
      if (!addresses.length) {
        wx.showToast({ title: '该客户还没有收货地址', icon: 'none' })
      }
    } catch (err) {
      wx.showToast({ title: err.message || '地址加载失败', icon: 'none' })
    }
  },

  onPickAddress(e) {
    const id = Number(e.currentTarget.dataset.id)
    const addr = this.data.addresses.find(a => a.id === id)
    // 摘要里要显示"送到哪"：wxml 里不做字符串拼接，统一在这里拼好
    const addressText = addr
      ? ((addr.name || '') + ' ' + (addr.phone || '') + ' ' + (addr.detail || '')).trim()
      : ''
    this.setData({ addressId: id, addressText, step: 3, quote: null })
  },

  onBackStep() {
    const step = Math.max(1, this.data.step - 1)
    this.setData({ step, quote: null })
  },

  /** 购物车加减。数量 0 即从车里移除（不留 0 行，避免提交空明细）。 */
  onQty(e) {
    const id = Number(e.currentTarget.dataset.id)
    const delta = Number(e.currentTarget.dataset.delta)
    this.mutateCart(id, delta)
  },

  mutateCart(productId, delta) {
    const products = this.data.products
    const p = products.find(x => x.id === productId)
    if (!p) return
    const line = this.data.cartLines.find(l => l.productId === productId)
    const current = line ? line.quantity : 0
    const next = current + delta

    let cartLines = this.data.cartLines.filter(l => l.productId !== productId)
    if (next > 0) {
      cartLines.push({
        productId,
        name: p.name,
        // 价格只用于**展示**参考，最终金额一律以服务端试算/建单为准
        price: p.salePrice != null ? p.salePrice : p.price,
        quantity: next
      })
    }
    const totalQty = cartLines.reduce((s, l) => s + l.quantity, 0)
    // 数量一变，之前的报价就失效了 —— 宁可让站长重新试算，也不要展示过期金额
    this.setData({ cartLines, totalQty, quote: null })
  },

  /** 服务端试算：金额、配送费/楼层费、门槛提示、以及**由服务端下发的支付方式选项**。 */
  async onQuote() {
    const { customer, addressId, cartLines, selectedMethod } = this.data
    if (!customer) {
      wx.showToast({ title: '请先选择客户', icon: 'none' })
      return
    }
    if (!addressId) {
      wx.showToast({ title: '请先选择收货地址', icon: 'none' })
      return
    }
    if (!cartLines.length) {
      wx.showToast({ title: '请先选择商品', icon: 'none' })
      return
    }

    this.setData({ quoting: true })
    try {
      const body = {
        stationId: this.data.stationId,
        // 首次试算没有已选方式时先探一个值：真正的选项与默认值由响应里的
        // methods / defaultMethod 决定（前端不自带映射表）
        paymentMethod: selectedMethod || 2,
        addressId,
        items: cartLines.map(l => ({ productId: l.productId, quantity: l.quantity }))
      }
      const res = await post(ASSIST + '/quote?customerId=' + customer.id, body)
      const d = res.data || {}
      const methods = Array.isArray(d.methods) ? d.methods : []
      const stillOk = methods.some(m => m.id === this.data.selectedMethod && m.enabled !== false)
      const nextMethod = stillOk ? this.data.selectedMethod : (d.defaultMethod || (methods[0] && methods[0].id))
      this.setData({ quote: d, methods, selectedMethod: nextMethod })
    } catch (err) {
      wx.showToast({ title: err.message || '试算失败', icon: 'none' })
    } finally {
      this.setData({ quoting: false })
    }
  },

  /** 换支付方式要重新试算：水票/现金的押金与可用性口径不同，金额可能变。 */
  onPickMethod(e) {
    const id = Number(e.currentTarget.dataset.id)
    const m = this.data.methods.find(x => x.id === id)
    if (!m || m.enabled === false) return
    this.setData({ selectedMethod: id }, () => this.onQuote())
  },

  async onSubmit() {
    if (this.data.submitting) return
    const { customer, addressId, cartLines, selectedMethod, quote, stationId } = this.data
    if (!quote) {
      wx.showToast({ title: '请先试算', icon: 'none' })
      return
    }
    if (quote.blocked) {
      // 后端把"起送量/配送范围"配成不接单时会下发 blocked + 原因，这里直接展示，
      // 不要让站长提交后才看到一个失败
      wx.showModal({ title: '本单不能下', content: quote.blockReason || '不满足本站配送条件', showCancel: false })
      return
    }
    await this.doCreate(false)
  },

  async doCreate(confirmShortage) {
    const { customer, addressId, cartLines, selectedMethod, stationId } = this.data
    // 幂等键在"一次提交动作"内保持稳定：缺货确认后重提必须复用同一个键，
    // 否则"第一次其实建成功了、只是响应丢了"会变成两单。
    if (!this.idempotencyKey) {
      this.idempotencyKey = 'staff-' + customer.id + '-' + Date.now() + '-' +
        Math.floor(Math.random() * 100000)
    }

    this.setData({ submitting: true })
    try {
      const body = {
        customerId: customer.id,
        addressId,
        stationId,
        paymentMethod: selectedMethod,
        source: SOURCE_PHONE,
        idempotencyKey: this.idempotencyKey,
        items: cartLines.map(l => ({ productId: l.productId, quantity: l.quantity }))
      }
      if (confirmShortage) body.confirmShortage = true

      const res = await post(ORDER_CREATE_PATH, body)
      const d = res.data || {}

      if (d.needConfirm) {
        const lines = (d.shortages || [])
          .map(s => (s.productName || ('商品' + s.productId)) + ' 需 ' + s.requested + '，库存 ' + s.stock)
          .join('\n')
        const that = this
        wx.showModal({
          title: '库存不足',
          content: (lines || '存在缺货商品') + '\n\n仍要下单吗？缺货部分后续补送。',
          confirmText: '仍然下单',
          success(r) {
            if (r.confirm) that.doCreate(true)
          }
        })
        return
      }

      const warnings = Array.isArray(d.warnings) ? d.warnings : []
      wx.showModal({
        title: '下单成功',
        content: '订单号 ' + d.orderId + (warnings.length ? '\n\n' + warnings.join('\n') : ''),
        showCancel: false,
        success() {
          wx.redirectTo({ url: '/pages/order/detail?id=' + d.orderId })
        }
      })
    } catch (err) {
      wx.showToast({ title: err.message || '下单失败', icon: 'none' })
    } finally {
      this.setData({ submitting: false })
    }
  }
})
