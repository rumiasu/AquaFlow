const { getProductDetail } = require('../../api/product')
const { getOrderDetail } = require('../../api/order')
const { getAddresses } = require('../../api/address')
const { getBarrelSummary, getBarrelSummaryByType } = require('../../api/barrel')
const { getTicketAccounts } = require('../../api/ticket')
const { createOrder, createPayment } = require('../../api/order')
const { getQuote } = require('../../api/payment')
const { getStationPublicPhone, getStationStatus } = require('../../api/station')
const { submitEnterpriseApply, getMyEnterpriseApplies } = require('../../api/enterprise')
const { storage, stationStorage, payMethodStorage } = require('../../utils/storage')
const { resolveStationId } = require('../../utils/station')
const { getCustomerId } = require('../../utils/token')
const { formatAddress } = require('../../utils/address')
const app = getApp()

Page({
  data: {
    loading: true,
    items: [],
    products: [],
    address: null,
    addressText: '',
    note: '',
    barrelByType: [],
    barrelSummary: [],
    stationId: null,
    stationName: '',
    // 金额校准相关
    totalWaterCost: 0,
    // 非桶装押金合计：后端 quote 的 barrelDeposit 键（历史误称）装着它，2026-09-19 起恒为 0，
    // 只用于支付请求的兼容字段（后端会用订单金额重算、丢弃客户端值），页面上不再展示
    totalDeposit: 0,
    extraDepositBuckets: 0,
    extraDepositAmount: 0,
    totalAmount: 0,
    // 水站营业状态提示（软状态）：有值时页面顶部显示横幅，**不阻断下单**
    stationStatusHint: '',
    totalWaterCostText: '0.00',
    extraDepositAmountText: '',
    totalAmountText: '0.00',
    // 支付方式：枚举以后端 PayMethod 为准 —— 1=微信 2=现金(货到付款) 3=水票。
    // 历史 bug：这里曾按「1微信 2水票 3货到付款」自造映射，与后端 2/3 恰好相反，
    // 导致默认项（2）被后端判为现金而撞上货到付款授权校验 → 新客户 100% 下单失败；
    // 选"货到付款"(3) 反被当成水票 → 下单即视同已付、无人收款。
    // 现在选项与文案一律由服务端 /api/payments/quote 的 methods 下发，前端不再自带映射。
    // [2026-09-19] 默认值不是写死的 3，而是**上次用过的支付方式**（本地偏好，见 utils/storage.js）；
    // 首次下单没有记录时回到 3（水票），再由 refreshQuote 按后端下发的 enabled 校正。
    selectedMethod: payMethodStorage.get() || 3,
    payMethods: [],
    // [2026-09-19] 水票抵扣预览（后端 quote 下发，仅当选中水票时有值）：
    // 产品口径「水票支付时不显示计费，只计费除去水票的部分」靠它实现 ——
    // 金额一律后端算，前端只渲染，绝不在前端做抵扣算术。
    ticketPay: null,
    ticketCoverText: '',      // 「水票抵扣 N 张 · ¥X」（仅整单可被票结清时才有值）
    ticketShortfallHint: '',  // 票不足时的一句结论（后端下发）
    payableAmountText: '0.00',// 计费区展示的"这次要付"的金额；真实订单金额看 totalAmountText
    isTicketPay: false,       // 当前是否选中水票（决定计费区怎么渲染）
    submitting: false,
    // 幂等键：onLoad 生成一次，下单成功后才刷新（保证同一意图只产生一单）
    idempotencyKey: '',
    // 支付方式确认弹窗
    showOfflineConfirm: false,
    // 首次资产业务确认弹窗
    showAssetConfirm: false,
    assetConfirmed: false,
    // 资产使用说明详情弹窗
    showAssetDetail: false,
    stationPhone: '',
    pendingOrderRes: null,
    // 费用明细弹窗
    showDetailPopup: false,
    // 配送中桶提醒弹窗
    showInTransitReminder: false,
    inTransitReminderAck: false,   // 同一次进入页面只提示一次
    hasInTransitBarrels: false,
    // 企业身份提示（v50）：由服务端在报价里下发（金额达阈值且客户还不是企业身份时才有值），
    // 前端不自算阈值、不自造文案。**不做独立入口** —— 只在拿到它的那一刻弹一次。
    enterpriseHint: ''
  },

  onLoad(options) {
    if (!app.globalData.isLogin) {
      wx.redirectTo({ url: '/pages/login/index' })
      return
    }

    // 一次下单意图 = 一个幂等键（提交失败重试也复用），避免重复下单
    this.setData({ idempotencyKey: this.genIdempotencyKey() })

    // 优先级：URL参数 > 全局临时站点 > 本地存储
    let stationId = null
    if (options.stationId) {
      const parsed = parseInt(options.stationId)
      if (!isNaN(parsed) && parsed > 0) {
        stationId = parsed
      }
    }
    if (!stationId && app.globalData.tempStationId) {
      stationId = app.globalData.tempStationId
    }
    if (!stationId) {
      stationId = stationStorage.getId()
    }
    if (stationId) {
      app.globalData.tempStationId = stationId
    }
    this.setData({ stationId })

    if (!stationId || stationId <= 0) {
      this.setData({ loading: false })
      wx.showModal({
        title: '请选择服务水站',
        content: '下单前需先选择一个水站。资产（桶、水票、押金）按水站隔离，互不干扰。',
        confirmText: '去选站',
        cancelText: '取消',
        success: (res) => {
          if (res.confirm) {
            // [2026-09-18 修] `/pages/home/index` 是 tabBar 页面，**只能用 switchTab**。
            // 原来写 navigateTo，微信会直接 fail（控制台报 "can not navigateTo a tabbar page"）——
            // 用户点了「去选站」什么都不会发生，属"点击静默无反应"那一类（AGENTS §6）。
            wx.switchTab({ url: '/pages/home/index' })
          } else {
            wx.navigateBack()
          }
        }
      })
      return
    }

    if (options.reorderId) {
      this.loadReorder(parseInt(options.reorderId))
      return
    }

    let items = []
    if (options.source === 'cart') {
      const cart = app.getCart(stationId) || {}
      Object.keys(cart).forEach(pid => {
        const qty = parseInt(cart[pid]) || 0
        if (qty > 0) items.push({ productId: parseInt(pid), quantity: qty })
      })
    } else if (options.items) {
      try {
        items = JSON.parse(decodeURIComponent(options.items)) || []
      } catch (e) { items = [] }
    }
    if (options.productId && items.length === 0) {
      items = [{ productId: parseInt(options.productId), quantity: parseInt(options.quantity) || 1 }]
    }

    const addressId = options.addressId ? parseInt(options.addressId) : null
    const specialNote = options.specialNote ? decodeURIComponent(options.specialNote) : ''
    this.setData({ items, note: specialNote, _preferAddressId: addressId })

    this.loadItemsProducts()
    this.loadAddress(addressId)
    this.loadBarrel()
  },

  onShow() {
    const selectedAddress = storage.get('selectedAddress')
    if (selectedAddress) {
      this.setData({
        address: selectedAddress,
        addressText: formatAddress(selectedAddress)
      })
      storage.remove('selectedAddress')
    }
  },

async loadItemsProducts() {
    const { items, stationId } = this.data
    const products = []

    // 1. 必须有选中的服务水站
    let effectiveStationId = stationId
    let effectiveStationName = ''

    if (!effectiveStationId || effectiveStationId <= 0) {
      // 无站点：提示去选择水站
      this.setData({ loading: false })
      const confirm = await new Promise(resolve => {
        wx.showModal({
          title: '请选择服务水站',
          content: '下单前需先选择一个水站。资产（桶、水票、押金）按水站隔离，互不干扰。',
          confirmText: '去选站',
          cancelText: '取消',
          success: (res) => resolve(res.confirm)
        })
      })
      if (!confirm) {
        wx.navigateBack()
      } else {
        // [2026-09-18 修] 同 :103 —— tabBar 页面必须用 switchTab，navigateTo 必然 fail。
        wx.switchTab({ url: '/pages/home/index' })
      }
      return
}

// 获取站点名称
    if (app.globalData.tempStationId === effectiveStationId && app.globalData.tempStation) {
      effectiveStationName = app.globalData.tempStation.name || ''
    } else {
      const station = stationStorage.get()
      if (station && station.id === effectiveStationId) {
        effectiveStationName = station.name || ''
      }
    }

    // 加载商品详情
    for (const it of items) {
      try {
        // 必须带 stationId：本站自定义商品只有该站能读（后端按 owner_station_id 过滤）
        const res = await getProductDetail(it.productId, effectiveStationId)
        if (res && res.data) {
          const d = res.data
          // 后端下发的是**本站有效价**（站级覆盖 → 通用库参考价）。
          // 这里统一映射回 price/deposit：本页下方的小计、桶押金估算、提交快照都沿用旧字段名，
          // 避免"有的地方改了、有的地方没改"又变成双口径（本仓计价双轨的历史事故）。
          const p = {
            ...d,
            price: d.effectivePrice != null ? d.effectivePrice : d.price,
            deposit: d.effectiveDeposit != null ? d.effectiveDeposit : d.deposit,
            quantity: it.quantity || 1,
            waterTypeId: d.water_type_id
          }
          p.subtotal = (parseFloat(p.price) || 0) * (p.quantity || 1)
          p.subtotalText = p.subtotal.toFixed(2)
          products.push(p)
        }
      } catch (e) {
        console.warn('Load product error:', e.message)
      }
    }

this.setData({ products, stationName: effectiveStationName })
    this.loadStationStatus(effectiveStationId)
    this.syncBarrelSummary()
    this.refreshQuote()
    this.setData({ loading: false })
  },

  onSelectMethod(e) {
    const { id, enabled } = e.currentTarget.dataset
    // 不可用的支付方式（如未接入的微信支付、未授权的货到付款）不响应点击
    if (enabled === false || String(enabled) === 'false') {
      const tip = (this.data.payMethods.find(m => m.id === parseInt(id)) || {}).desc
      wx.showToast({ title: tip || '该支付方式暂不可用', icon: 'none' })
      return
    }
    this.setData({ selectedMethod: parseInt(id) })
    this.refreshQuote()
  },

  onOfflineConfirmOk() {
    this.setData({ showOfflineConfirm: false })
  },

  onOfflineConfirmCancel() {
    this.setData({ showOfflineConfirm: false })
  },

  onInTransitReminderCancel() {
    this.setData({ showInTransitReminder: false })
  },

  onInTransitReminderOk() {
    // 用户确认继续：关闭提醒后重跑提交（此时 inTransitReminderAck 已为 true，会跳过提醒并真正下单）
    this.setData({ showInTransitReminder: false })
    this.onSubmit()
  },

  async loadReorder(orderId) {
    try {
      const res = await getOrderDetail(orderId)
      const order = res.data
      if (!order) {
        wx.showToast({ title: '订单不存在', icon: 'none' })
        wx.navigateBack()
        return
      }

      // [2026-09-14 修正] 原实现调 /api/stations/mine —— 员工专属接口（@RequireRole
      // STATION_MANAGER/DELIVERY），顾客 token 恒 403，currentStationId 永远为 null，
      // 于是下面那段「水站不一致」提醒成了永不触发的死分支：从 A 站订单「再来一单」时，
      // 即便人已在 B 站也不会得到任何提示，可能按 B 站的价格/库存下单。
      // 改用与首页同一套来源：stationStorage 优先，回退 my-station。
      const currentStationId = await resolveStationId()
      const orderStationId = order.deliveryStationId || order.stationId

      if (currentStationId && orderStationId && currentStationId !== orderStationId) {
        wx.showModal({
          title: '水站不一致',
          content: '该订单属于其他水站，当前水站可能无法供应此商品。是否继续？',
          confirmText: '继续',
          cancelText: '取消',
          success: (modalRes) => {
            if (modalRes.confirm) {
              this._doLoadFromOrder(order)
            } else {
              wx.navigateBack()
            }
          }
        })
        return
      }

      await this._doLoadFromOrder(order)
    } catch (error) {
      console.error('Load reorder error:', error)
      this.setData({ loading: false })
    }
  },

  async _doLoadFromOrder(order) {
    let items = []
    if (order.items && order.items.length > 0) {
      items = order.items.map(it => ({
        productId: it.productId || it.waterTypeId,
        quantity: it.quantity || 1
      }))
    } else if (order.productId || order.waterTypeId) {
      items = [{
        productId: order.productId || order.waterTypeId,
        quantity: order.quantity || 1
      }]
    }

    this.setData({
      items,
      note: order.specialNote || '',
      stationId: order.deliveryStationId || order.stationId || this.data.stationId
    })
    this.loadAddress(order.addressId)
    await this.loadItemsProducts()
    this.loadBarrel()
  },

  async loadAddress(preferId) {
    try {
      const res = await getAddresses()
      if (res.data && res.data.length > 0) {
        const list = res.data
        const picked = list.find(a => a.id === preferId)
        const fallback = list.find(a => a.isDefault) || list[0]
        const addr = picked || fallback
        this.setData({ address: addr, addressText: addr ? formatAddress(addr) : '' })
      }
    } catch (error) {
      console.error('Load address error:', error)
    }
  },

  async loadBarrel() {
    try {
      const { stationId } = this.data
      const res = await getBarrelSummaryByType(stationId)
      if (res.data) {
        this.setData({ barrelByType: res.data })
        this.syncBarrelSummary()
      }
      // 统计「配送中」的桶：只有 PENDING（已购待送、尚未送达确认）才算配送中，
      // 已送达的 DELIVERED 记录不应再触发提醒；首单仍在配送的也已被包含。
      const sumRes = await getBarrelSummary(stationId)
      if (sumRes.data) {
        const pending = sumRes.data.pendingDeliveryBuckets || 0
        this.setData({ hasInTransitBarrels: pending > 0 })
      }
    } catch (error) {
      console.error('Load barrel error:', error)
    }
  },

  syncBarrelSummary() {
    const { products, barrelByType } = this.data
    const summary = products.map(p => {
      const pid = p.id
      const held = (barrelByType || []).find(b => (b.productId || b.waterTypeId) === pid)
      const actualBuckets = held
        ? Math.max(0, held.assetQty != null ? held.assetQty : (held.holdingQty || 0) - (held.confirmedQty || 0))
        : 0
      return { productId: pid, productName: p.name, actualBuckets, quantity: p.quantity || 1 }
    })
    this.setData({ barrelSummary: summary })
  },

  onQtyChange(e) {
    const { index, type } = e.currentTarget.dataset
    const products = [...this.data.products]
    let qty = products[index].quantity || 1
    if (type === 'add') qty++
    else if (type === 'minus' && qty > 1) qty--
    products[index].quantity = qty
    products[index].subtotal = (parseFloat(products[index].price) || 0) * qty
    products[index].subtotalText = products[index].subtotal.toFixed(2)

    const items = products.map(p => ({ productId: p.id, quantity: p.quantity || 1 }))
    this.setData({ products, items })
    this.syncBarrelSummary()
    this.refreshQuote()
  },

  onQtyInput(e) {
    const { index } = e.currentTarget.dataset
    const qty = Math.max(1, parseInt(e.detail.value) || 1)
    const products = [...this.data.products]
    products[index].quantity = qty
    products[index].subtotal = (parseFloat(products[index].price) || 0) * qty
    products[index].subtotalText = products[index].subtotal.toFixed(2)
    const items = products.map(p => ({ productId: p.id, quantity: p.quantity || 1 }))
    this.setData({ products, items })
    this.syncBarrelSummary()
    this.refreshQuote()
  },

  onNoteInput(e) {
    this.setData({ note: e.detail.value })
  },

  onAddressTap() {
    wx.navigateTo({ url: '/pages/address/list?from=order' })
  },

  getTotalEstimate() {
    const { products } = this.data
    let total = 0
    products.forEach(p => {
      const price = parseFloat(p.price) || 0
      total += price * (p.quantity || 1)
    })
    return total.toFixed(2)
  },

  async refreshQuote() {
    const { products, selectedMethod, stationId, barrelSummary, address } = this.data
    if (!products || products.length === 0 || !stationId) return

    try {
      const quoteItems = products.map(p => ({
        productId: p.id,
        quantity: p.quantity || 1
      }))
      const res = await getQuote({
        items: quoteItems,
        paymentMethod: selectedMethod,
        stationId: stationId,
        // [v35] 必须带上收货地址：配送范围要靠它取坐标、楼层费要靠它取楼层。
        // 不传的话后端算不出距离与楼层（按"拿不准就不收"处理），
        // 而**下单时是带地址的** → 报价与订单金额就会不一致（本仓记过的"计价双轨"事故）。
        addressId: address && address.id ? address.id : undefined
      })
      if (res.data) {
        const d = res.data
        const totalWaterCost = d.waterAmount || 0
        const totalDeposit = d.barrelDeposit || 0
        const extraDepositBuckets = d.extraDepositBuckets || 0
        const extraDepositAmount = d.extraDeposit || 0
        const totalAmount = d.totalAmount || (totalWaterCost + totalDeposit + extraDepositAmount)
        const allowOfflinePayment = d.allowOfflinePayment === true
        // [v35] 配送费与楼层费：金额与文案都由后端下发，前端只负责展示，不自算、不自造文案
        const deliveryFee = d.deliveryFee || 0
        const floorFee = d.floorFee || 0
        const feeWarnings = Array.isArray(d.warnings) ? d.warnings : []
        // blocked 是"起送量/配送范围配成了不接单"的硬拦结论 —— 与下单侧的拒绝判据同源
        const blocked = d.blocked === true
        const blockReason = d.blockReason || ''
        // 企业身份提示（v50）：服务端开关关着 / 没到阈值 / 已是企业身份时都是空串
        const enterpriseHint = d.enterpriseHint || ''

        // 支付方式列表由服务端下发（含文案、可用性、默认项），前端不再硬编码 1/2/3 的含义
        // ⚠️ 这个兜底本身有隐患，已登记待处理（审计报告 §6.5）：methods 缺失时它只造出"水票支付"一项 ——
        // 客户会看到一个唯一选项而票可能是 0 张，且现金/微信凭空消失，比"明确提示报价失败并重试"更误导。
        // 本轮只同步文案（不扩大改动范围）。
        const payMethods = Array.isArray(d.methods) && d.methods.length
          ? d.methods
          : [{ id: 3, name: '水票支付', desc: '使用账户水票抵扣 · 票不足可先购买水票', enabled: true }]
        // 当前选中项若已不可用（权限被收回），回退到服务端给的默认值。
        // [2026-09-19] selectedMethod 的初值来自"上次用过的支付方式"（本地偏好），
        // 所以这一句同时也是**记住的方式在本站不可用时的回退点**。
        const selectedStillOk = payMethods.some(m => m.id === selectedMethod && m.enabled)
        const nextMethod = selectedStillOk ? selectedMethod : (d.defaultMethod || payMethods[0].id)

        // ===== 水票抵扣预览（产品口径：水票支付时不显示计费，只计费除去水票的部分）=====
        // 金额一律后端算好下发（quote.ticketPay），前端只渲染，绝不在前端做抵扣算术 ——
        // 前端算一遍就会出现"结算页一个价、扣票另一个价"（本仓记过的计价双轨）。
        // 只在选中水票时用：切到现金/微信后这个键不再相关，留着会让计费区显示错。
        const ticketPay = (nextMethod === 3 && d.ticketPay) ? d.ticketPay : null
        const ticketFullyCovered = !!(ticketPay && ticketPay.fullyCovered)
        // 水票抵扣那行只在**整单能被票结清**时显示：抵不掉时本单根本用不了票，
        // 这时显示"水票抵扣 ¥X"却又要付全额，客户只会以为系统算错了。
        const ticketCoverText = ticketFullyCovered
          ? '水票抵扣 ' + ticketPay.coverQty + ' 张 · ¥' + (Number(ticketPay.coverAmount) || 0).toFixed(2)
          : ''
        const ticketShortfallHint = (ticketPay && !ticketPay.fullyCovered) ? (ticketPay.hint || '') : ''
        // 客户这次**实际要付**的钱：票能全抵 → 0（水费/押金/配送费随票一并结清）；
        // 抵不掉 → 订单全额（本单用不了票，改选方式后就是全额付）。
        // ⚠️ 只用于展示，不参与任何提交参数 —— 提交金额一律由后端按订单重算。
        const payableAmount = ticketPay ? (Number(ticketPay.payableAmount) || 0) : totalAmount

        // 计算每个商品的**缺桶押金**明细（非桶装没有押金，见下）
        const updatedProducts = products.map(p => {
          const deposit = parseFloat(p.deposit) || 0
          const isBarrel = p.category === 1
          let shortage = 0
          let shortageDeposit = 0

          if (isBarrel) {
            // 桶装水：只对缺少的空桶收押金
            const held = (barrelSummary || []).find(s => s.productId === p.id)
            const actualBuckets = held ? held.actualBuckets : 0
            shortage = Math.max(0, (p.quantity || 1) - actualBuckets)
            shortageDeposit = deposit * shortage
          }
          // 非桶装（瓶装水 / 一次性桶 / 饮水器）：**押金恒为 0**（2026-09-19）。
          // 押金是循环桶的押金（正本：product.deposit 列注释"只有桶装水使用"），
          // 这里原来写的是"每个都收押金"，与后端同口径一起收口 —— 否则结算页会显示一笔
          // 后端根本不收的押金（计价双轨的老坑）。后端 quote 的 barrelDeposit 同样恒为 0。

          return {
            ...p,
            shortage,
            shortageDepositText: shortageDeposit > 0 ? shortageDeposit.toFixed(2) : '0.00'
          }
        })

        const updates = {
          products: updatedProducts,
          totalWaterCost,
          totalDeposit,
          extraDepositBuckets,
          extraDepositAmount,
          totalAmount,
          totalWaterCostText: totalWaterCost.toFixed(2),
          extraDepositAmountText: extraDepositBuckets > 0 ? extraDepositAmount.toFixed(2) : '',
          totalAmountText: totalAmount.toFixed(2),
          // 计费区展示用（水票支付时"只计费除去水票的部分"）：
          // 票能全抵 → 0.00；否则 = 订单全额。totalAmount/totalAmountText 仍是**真实订单金额**，
          // 两者刻意的分开：展示值绝不能混进任何提交参数。
          payableAmountText: payableAmount.toFixed(2),
          ticketPay,
          ticketCoverText,
          ticketShortfallHint,
          isTicketPay: nextMethod === 3,
          allowOfflinePayment,
          payMethods,
          selectedMethod: nextMethod,
          deliveryFee,
          floorFee,
          deliveryFeeText: deliveryFee > 0 ? deliveryFee.toFixed(2) : '',
          floorFeeText: floorFee > 0 ? floorFee.toFixed(2) : '',
          // 费用合计文案（两个都为 0 时不显示这一行）
          feeTotalText: (deliveryFee + floorFee) > 0 ? (deliveryFee + floorFee).toFixed(2) : '',
          feeWarnings,
          blocked,
          blockReason,
          enterpriseHint
        }

        this.setData(updates)
        // 弹窗放在 setData 之后、不 await：提示而已，绝不能拖住报价渲染或下单按钮
        this.maybePromptEnterprise(enterpriseHint)
      }
    } catch (e) {
      // [2026-09-20 真机联调] 原来只 console.warn 就完了：报价失败时页面停在
      // 「合计 ¥0.00」、支付方式还是上一轮的，而**下单按钮照样可点** ——
      // 真机弱网下会提交一张金额陈旧的订单（钱的事，宁可挡住）。
      // 这里复用页面**既有的**硬拦闸门：onSubmit 开头就查 this.data.blocked 并 return，
      // 所以不用新增一套判断。下次报价成功时，成功分支会用后端下发的 blocked/blockReason
      // 覆盖掉这里的值（见上面 setData 的 updates），不会把页面永久锁死。
      console.error('[OrderCreate] refreshQuote 失败:', e)
      this.setData({
        blocked: true,
        blockReason: '没能取到最新报价（' + ((e && e.message) || '网络异常')
          + '），为避免金额出错已暂停下单，请下拉刷新后重试'
      })
      wx.showToast({ title: '报价加载失败，已暂停下单', icon: 'none' })
    }
  },

  // 生成一个下单幂等键（仅在"新的一次下单意图"时调用：进入页面 / 下单成功后）
  genIdempotencyKey() {
    return 'order_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9)
  },

  async onSubmit() {
    if (this.data.submitting) return

    // [v35] 硬拦（起送量/配送范围被站长配成不接单）在前端就地挡住：
    // 让客户填完地址、点了提交才被后端拒，体验上像是"系统坏了"。
    // reason 由后端下发（与 createOrder 的拒绝判据同源），前端不自己判断该不该拦。
    if (this.data.blocked) {
      wx.showModal({
        title: '暂不可下单',
        content: this.data.blockReason || '当前订单暂不满足下单条件，请调整后重试',
        showCancel: false,
        confirmText: '知道了'
      })
      return
    }

    const { products, address, note, stationId, selectedMethod } = this.data

    if (!address) {
      wx.showToast({ title: '请选择配送地址', icon: 'none' })
      return
    }

    if (!products || products.length === 0) {
      wx.showToast({ title: '请选择商品', icon: 'none' })
      return
    }

    // 2 = PayMethod.CASH（货到付款）：需先弹窗确认"送达后付款"
    if (selectedMethod === 2 && !this.data.showOfflineConfirm) {
      this.setData({ showOfflineConfirm: true })
      return
    }

    // [2026-09-19] 水票不足就地拦住（产品口径：「点击水票后优先水票支付，但经校验后
    // 发现水票不足以覆盖该订单时…」——校验点就在这里，而不是等支付时报"余额不足"）。
    //
    // ⚠️ 为什么是"拦"而不是"放过去让它失败"：水票支付是**整单**结清，票不够时
    // `deductTickets` 会抛错 → 订单已创建但 `payment_status` 停在 0 → 按派单判据
    // **这单进不了站长视野**（客户以为下单成功了，实际没人看得见）。宁可当场拦住。
    //
    // 提交前**重跑一次报价**再判定：客户可能刚在别处补了票，用页面上的旧数据会误伤。
    // 重跑不会重复弹企业身份窗（maybePromptEnterprise 有 this.enterprisePrompted 闸门）。
    if (selectedMethod === 3) {
      await this.refreshQuote()
      const tp = this.data.ticketPay
      if (tp && !tp.fullyCovered) {
        // 标题与结论都由后端下发：它要区分"某商品根本不能用票（补票也没用）"
        // 与"票不够（补票或者改选）"，前端分不出来也不该分。
        // [2026-09-20] 按钮从只有一个"知道了"改成**可执行的两个动作**：
        //   · 「去买水票」→ 跳购票页（这就是"先补齐水票再下单"那条路，也是购票页的**唯一入口**）；
        //   · 「留在本页」→ 关掉弹窗回结算页改选支付方式（货到付款/微信在那里切）。
        // 原实现客户看完提示只能自己去找购票入口 —— 而全仓根本没有跳转购票页的地方，等于死路。
        // 判定用后端下发的结构化 reason，**不比对 title 文案**（文案是给人看的，不是判据）。
        const canBuyTicket = tp.reason === 'INSUFFICIENT'
        wx.showModal({
          title: tp.title || '暂不能用票支付',
          content: tp.hint || '水票不足以覆盖本单，请改选支付方式或先补齐水票',
          showCancel: canBuyTicket,
          confirmText: canBuyTicket ? '去买水票' : '改选支付方式',
          cancelText: '留在本页',
          success: (r) => {
            // 只有"余额不够"才把人引去买票；"商品根本不能用票"时补票没用（后端 hint 已写明）
            if (canBuyTicket && r.confirm) {
              wx.navigateTo({ url: '/pages/ticket/index' })
            }
          }
        })
        return
      }
    }

    // 配送中桶提醒：有水桶正在配送中，且本次下单会产生额外桶押金时，先友好提示
    if (this.data.hasInTransitBarrels && this.data.extraDepositBuckets > 0 && !this.data.inTransitReminderAck) {
      this.setData({ showInTransitReminder: true, inTransitReminderAck: true })
      return
    }

    // 复用本次下单意图的幂等键：失败重试不会产生第二单
    const idempotencyKey = this.data.idempotencyKey || this.genIdempotencyKey()

    this.setData({ submitting: true, showOfflineConfirm: false, idempotencyKey })

    // 资产确认弹窗未处理完前保持 submitting=true，防止用户重复点击造成重复下单
    let keepSubmitting = false
    try {
      const items = this.data.products.map(p => ({
        productId: p.id,
        waterTypeId: p.waterTypeId || null,
        productName: p.name,
        productSpec: p.spec || '',
        productPrice: parseFloat(p.price) || 0,
        productDeposit: parseFloat(p.deposit) || 0,
        quantity: p.quantity || 1
      }))

      const orderRes = await createOrder({
        items: items,
        addressId: this.data.address.id,
        specialNote: this.data.note,
        source: 3,
        paymentMethod: this.data.selectedMethod,
        stationId: this.data.stationId,
        extraDeposit: this.data.extraDepositAmount || 0,
        idempotencyKey: idempotencyKey
      })

      // 订单已创建成功 -> 刷新幂等键，之后再主动下单才是新的一单
      this.setData({ idempotencyKey: this.genIdempotencyKey() })

      // [2026-09-19] 记下这次用的支付方式（**只存本地**，产品口径「前端记一下就好，不用写进后端」）。
      // 放在这里而不是提交前：提交失败/被拒时不该污染"上次成功用过的"那一项。
      payMethodStorage.set(this.data.selectedMethod)

      if (orderRes.data && orderRes.data.firstStationAsset) {
        keepSubmitting = true
        this.setData({ showAssetConfirm: true, assetConfirmed: false, pendingOrderRes: orderRes })
        return
      }

      this.proceedToPayment(orderRes)
    } catch (error) {
      console.error('[OrderCreate] 下单失败:', error)
      wx.showToast({ title: '下单失败: ' + (error.message || ''), icon: 'none', duration: 3000 })
    } finally {
      if (!keepSubmitting) this.setData({ submitting: false })
    }
  },

  async proceedToPayment(orderRes) {
    const orderId = orderRes.data?.orderId || orderRes.data || null
    if (!orderId) return

    // 下单响应里的 warnings（水站营业状态提示 / 欠桶提醒 / 缺货提示）**必须让客户看到**：
    // 后端一直在下发，前端从来没读过，等于白提醒。营业状态是"不阻断但要说清楚"的软状态，
    // 所以这里只弹提示，订单已经在库里了，点"知道了"继续走支付/成功页。
    await this.showOrderWarnings(orderRes)

    // 3 = PayMethod.TICKET（水票支付）：下单即视同已付，补一条支付流水用于对账
    if (this.data.selectedMethod === 3) {
      try {
        await createPayment({
          orderId,
          customerId: getCustomerId(),
          amount: this.data.totalAmount,
          waterAmount: this.data.totalWaterCost,
          barrelDeposit: this.data.totalDeposit,
          extraDepositBuckets: this.data.extraDepositBuckets,
          extraDepositAmount: this.data.extraDepositAmount,
          paymentMethod: 3,
          ticketProductId: null,
          ticketQty: null
        })
      } catch (e) {
        console.warn('水票支付记录创建失败:', e.message)
      }
    }

    wx.setStorageSync('lastOrderId', orderId)
    wx.redirectTo({ url: `/pages/order/success?id=${orderId}&stationId=${this.data.stationId}` })
  },

  /**
   * 展示下单响应里的 warnings（非阻断）。
   * wx.showModal 是**回调式** API（本仓没有 promisify），所以这里包一层 Promise
   * 以便在跳转前把提示显示完 —— 直接 await wx.showModal(...) 会恒得 undefined。
   */
  showOrderWarnings(orderRes) {
    const warnings = (orderRes && orderRes.data && orderRes.data.warnings) || []
    if (!warnings.length) return Promise.resolve()
    return new Promise((resolve) => {
      wx.showModal({
        title: '下单成功，请注意',
        content: warnings.join('\n'),
        showCancel: false,
        confirmText: '知道了',
        complete: () => resolve()
      })
    })
  },

  // ===== 首次资产业务确认弹窗 =====
  // 注意：订单在弹窗出现之前就已创建成功。这里的"取消"只是不继续支付，
  // 必须明确提示订单已存在，否则用户会以为没下单而再次提交，造成重复下单。
  onAssetConfirmCancel() {
    this.setData({
      showAssetConfirm: false,
      assetConfirmed: false,
      pendingOrderRes: null,
      submitting: false
    })
    wx.showModal({
      title: '订单已创建',
      content: '订单已提交成功，可在「我的订单」中查看或取消。',
      showCancel: false,
      confirmText: '查看订单',
      success: () => wx.switchTab({ url: '/pages/order/list' })
    })
  },

  onAssetCheckboxChange(e) {
    this.setData({ assetConfirmed: e.detail.value.length > 0 })
  },

  onAssetConfirmOk() {
    if (!this.data.assetConfirmed) {
      wx.showToast({ title: '请勾选确认后继续', icon: 'none' })
      return
    }
    const orderRes = this.data.pendingOrderRes
    this.setData({ showAssetConfirm: false, assetConfirmed: false, pendingOrderRes: null })
    this.proceedToPayment(orderRes)
  },

  // ===== 企业身份申请（v50）=====
  //
  // 产品口径：「订水时检测到大额订单，会弹出确认是否是企业，可申请企业身份」——
  // **客户侧没有独立入口**，这一步就是全部入口。
  //
  // 三个设计取舍（改之前先读）：
  //   ① 每次进入页面**只弹一次**（this.enterprisePrompted 是挂在页面实例上的，不是 data）：
  //      报价在改数量/改支付方式时都会重算，若不加这个闸门，用户每点一下加号就被弹一次。
  //   ② 弹窗前先查一次"我在本站有没有待审的申请"：已经申请过的人不再骚扰
  //      （重复提交后端虽然幂等，但天天弹同一个窗很烦）。查询失败**不阻断** ——
  //      宁可多弹一次，也不能因为一次网络抖动就把"能申请"这件事静默丢掉。
  //   ③ 全程不阻断下单：无论用户点"暂不"、还是提交失败，都不影响本次下单流程。
  maybePromptEnterprise(hint) {
    if (!hint || this.enterprisePrompted) return
    // 先置位再弹：showModal 是异步回调，这里若不先置位，同一轮里的第二次报价会再弹一个
    this.enterprisePrompted = true
    this.checkPendingEnterpriseApply().then((hasPending) => {
      if (hasPending) return
      wx.showModal({
        title: '企业订水',
        content: hint,
        confirmText: '申请企业身份',
        cancelText: '暂不',
        success: (r) => {
          if (r.confirm) this.askEnterpriseName()
        }
      })
    })
  },

  /** 本站是否已有待审申请（拿不到就说"没有"，即允许弹窗）。 */
  async checkPendingEnterpriseApply() {
    try {
      const res = await getMyEnterpriseApplies(this.data.stationId)
      const list = (res && res.data) || []
      return list.some((a) => a.status === 'PENDING')
    } catch (e) {
      console.warn('[OrderCreate] 查询企业身份申请失败（按未申请处理）:', e.message)
      return false
    }
  },

  /**
   * 只问企业名称（后端必填项就这一个），用 wx.showModal 的 editable 形态 ——
   * 需求是"最小闭环、不新增页面"，为这一句话单开一个表单页不值当。
   * 联系人/电话/税号都可留空：站长审核时看得到是谁在下单（订单里有收货人）。
   */
  askEnterpriseName() {
    wx.showModal({
      title: '企业名称',
      editable: true,
      placeholderText: '请填写营业执照上的企业全称',
      success: (r) => {
        if (!r.confirm) return
        const companyName = (r.content || '').trim()
        if (!companyName) {
          wx.showToast({ title: '企业名称不能为空', icon: 'none' })
          return
        }
        this.submitEnterpriseApply(companyName)
      }
    })
  },

  async submitEnterpriseApply(companyName) {
    const address = this.data.address || {}
    try {
      await submitEnterpriseApply({
        stationId: this.data.stationId,
        companyName,
        // 电话可用就用收货人电话兜底：站长要打电话核实时不至于拿到一条没有任何联系方式的申请
        contactPhone: address.phone || undefined
      })
      wx.showModal({
        title: '申请已提交',
        content: '水站站长审核通过后，你会成为企业客户。本次下单不受影响，可以继续。',
        showCancel: false,
        confirmText: '继续下单'
      })
    } catch (e) {
      // 服务端开关关掉时这里收到的就是「企业身份功能当前未开启」，原样展示服务端文案
      wx.showToast({ title: e.message || '提交失败，请稍后重试', icon: 'none' })
    }
  },

  // ===== 资产使用说明详情弹窗 =====
  onAssetDetailTap() {
    // 获取水站电话
    this.fetchStationPhone()
    this.setData({ showAssetDetail: true })
  },

  onAssetDetailClose() {
    this.setData({ showAssetDetail: false })
  },

  async fetchStationPhone() {
    if (this.data.stationPhone) return
    try {
      const res = await getStationPublicPhone(this.data.stationId)
      if (res.data && res.data.phone) {
        this.setData({ stationPhone: res.data.phone })
      }
    } catch (e) {
      console.warn('获取水站电话失败:', e)
    }
  },

  // ===== 费用明细弹窗 =====
  onShowDetail() {
    this.setData({ showDetailPopup: true })
  },

  onDetailPopupClose() {
    this.setData({ showDetailPopup: false })
  }
})