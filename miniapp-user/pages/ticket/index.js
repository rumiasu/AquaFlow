const { getTicketAccounts, getTicketRecords, getTicketPackages, purchaseTicket } = require('../../api/ticket')
const { getStationProducts } = require('../../api/product')
const { getPublicStations } = require('../../api/station')
const { getBaseUrl, API } = require('../../config/api')
const { getAccessToken } = require('../../utils/token')
const { stationStorage } = require('../../utils/storage')

Page({
  data: {
    loading: true,
    accounts: [],
    records: [],
    currentTab: 0,
    totalTickets: 0,
    totalValue: 0,
    tabs: [
      { id: 0, name: '水票明细' },
      { id: 1, name: '消费记录' }
    ],
    showPurchase: false,
    buyProducts: [],
    buyForm: {
      productId: null,
      productName: '',
      faceValue: 0,
      quantity: 1,
      totalPrice: 0,
      paymentMethod: 1,
      // 选中的档位 id（null = 散买，按单张水票价）。**只用于定制档位**。
      packageId: null,
      // 选中的**统一折扣档张数**（null = 没选统一档）。与 packageId 互斥。
      // ⚠️ 只传张数、不传价：价格由后端按"这款水自己的价 × 该档折扣"现算
      // （产品口径「对应水怎么统一打折」），客户端算价就等于自己定价。
      unifiedQty: null,
      // 散买单张价。选了档位后 faceValue 会变成档位均价，用它才能退回散买口径
      looseFaceValue: 0
    },
    // 当前商品在本站能买的档位（定制档位 or 站级统一折扣折算出来的档位，见后端 TicketTierService）
    buyPackages: [],
    // 购票是「提交申请 → 水站确认收款 → 水票到账」，**本身没有在线支付渠道**。
    // 原来这里写死 name:'微信支付'，会让客户以为能在线付款（微信渠道其实未接入），
    // 而本仓明令「支付方式文案由服务端下发、前端不自带映射表」—— 购票页曾是唯一例外。
    // 收款方式目前没有可选项（都是线下确认收款），所以如实描述这件事，不冒充任何渠道。
    buyMethods: [
      { id: 1, name: '水站确认收款', desc: '提交购票申请后由水站确认收款，到账后水票可用' }
    ],
    submitting: false,
    // 在线购票幂等键：同一笔购买意图（含失败重试）复用同一个值，购买成功后才重新生成。
    // 后端 v33 起必传 —— 无订单支付在数据库层没有任何防重，缺了它连点两次「买票」
    // 会落两条待收款流水，站长两条都确认就会入账两次。
    purchaseIdempotencyKey: '',
    currentStationId: null,
    currentStation: null,
    showStationPicker: false,
    stationList: []
  },

  onShow() {
    this.loadData()
  },

  // 生成一个购票幂等键（仅在"新的一次购买意图"时调用：进入页面 / 购买成功后）
  genPurchaseIdempotencyKey() {
    return 'ticket_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9)
  },

  async loadData() {
    // 优先读取本地存储的水站
    let stationId = stationStorage.getId()
    let station = stationStorage.get()

    this.setData({ currentStationId: stationId, currentStation: station })

    this.setData({ loading: true })
    try {
      let productsRes = null
      if (stationId) {
        // 2026-09-16：不再回退到 /api/products/on-sale —— 那是**全平台**在售列表，
        // 会把别站可售商品塞进"买水票"弹窗（跨站可见性漏洞）。没有选水站就只展示已持有水票。
        productsRes = await getStationProducts(stationId).catch(() => null)
      }

      const [accountsRes, recordsRes] = await Promise.all([
        getTicketAccounts(stationId),
        getTicketRecords(stationId)
      ])
      if (accountsRes.data) {
        const accounts = accountsRes.data
        const totalTickets = accounts.reduce((sum, a) => sum + (a.remainQuantity || 0), 0)
        const totalValue = accounts.reduce((sum, a) => sum + (a.remainQuantity || 0) * (a.effectiveTicketPrice || a.faceValue || a.price || 0), 0)
        this.setData({ accounts, totalTickets, totalValue })
      }
      if (recordsRes.data) {
        this.setData({ records: recordsRes.data })
      }
      if (productsRes && productsRes.data) {
        // 列表里放两类商品，**都是真实商品**（2026-09-20 产品口径：「在用户端看起来没区别…
        // 不是专门卖统一水票」—— 所以这里**不会**出现"统一水票"这种商品）：
        //   ① 本站开了定制票的（ticketEnabled === 1）；
        //   ② 桶装水（category === 1）—— 站长配了站级统一折扣时，它也能按折扣买票。
        //      "到底能不能买"由点进去拉到的档位决定（后端 TicketTierService 一处判据），
        //      拉不到档位就提示一句，不在这儿猜。
        const buyProducts = productsRes.data.filter(p => p.ticketEnabled === 1 || p.category === 1)
        this.setData({ buyProducts, currentStationId: stationId })
      }
    } catch (error) {
      console.error('Load ticket data error:', error)
    } finally {
      this.setData({ loading: false })
    }
  },

  async loadStationList() {
    try {
      const res = await getPublicStations().catch(() => null)
      if (res && res.code === 0 && res.data) {
        const activeStations = res.data.filter(s => s.status === 1)
        this.setData({ stationList: activeStations })
      }
    } catch (e) {
      console.error('加载水站列表失败:', e)
    }
  },

  onOpenStationPicker() {
    this.setData({ showStationPicker: true })
    this.loadStationList()
  },

  onCloseStationPicker() {
    this.setData({ showStationPicker: false })
  },

  async onSelectStation(e) {
    const { id } = e.currentTarget.dataset
    if (id === this.data.currentStationId) {
      this.setData({ showStationPicker: false })
      return
    }
    const station = this.data.stationList.find(s => s.id === id)

    // 本地提示：不同水站资产不互通
    const noticeDisabled = stationStorage.getSwitchNoticeDisabled()
    if (!noticeDisabled && this.data.currentStationId && this.data.currentStationId !== id) {
      const confirm = await new Promise(resolve => {
        wx.showModal({
          title: '切换水站提醒',
          content: '不同水站的水票、桶及押金等资产不互通，请确认后再切换。',
          confirmText: '知道了，继续',
          cancelText: '取消',
          showCancel: true,
          success: (r) => resolve(r.confirm)
        })
      })
      if (!confirm) {
        return
      }
      const dontShow = await new Promise(resolve => {
        wx.showModal({
          title: '提示',
          content: '下次不再提示？',
          confirmText: '不再提示',
          cancelText: '每次都提示',
          success: (r) => resolve(r.confirm)
        })
      })
      if (dontShow) {
        stationStorage.setSwitchNoticeDisabled(true)
      }
    }

    stationStorage.set(station)
    this.setData({ showStationPicker: false })
    await this.loadData()
  },

  onTabChange(e) {
    const { id } = e.currentTarget.dataset
    this.setData({ currentTab: id })
  },

  onShowPurchase() {
    if (!this.data.currentStationId) {
      wx.showToast({ title: '请先选择水站', icon: 'none' })
      return
    }
    this.setData({ showPurchase: true })
  },

  onClosePurchase() {
    this.setData({ showPurchase: false, buyPackages: [], buyForm: { productId: null, productName: '', faceValue: 0, quantity: 1, totalPrice: 0, paymentMethod: 1, packageId: null, unifiedQty: null, looseFaceValue: 0 } })
  },

  /** 弹窗内容区吞掉点击，避免冒泡到遮罩触发关闭（wxml 用 catchtap 绑定） */
  stopPropagation() {},

  onBuyProductSelect(e) {
    const { id } = e.currentTarget.dataset
    // dataset 类型可能是 string/number，统一按字符串比较，避免 === 恒 false
    const product = this.data.buyProducts.find(p => String(p.id) === String(id))
    if (!product) return
    // 面值 = 后端下发的本站水票价（与 /api/tickets/purchase 的计费完全同源），不再用零售价。
    // ⚠️ 走统一折扣的商品没配水票价 → 这个值可能是 0，此时**只能按档位买**（页面不会显示散买）。
    const price = parseFloat(product.effectiveTicketPrice || product.price) || 0
    // 换商品必须清掉已选档位：档位是「本站 + 本商品」的，留着上一个商品的档位
    // 会被后端以"档位与本水站/本商品不匹配"拒绝
    this.setData({
      'buyForm.productId': product.id,
      'buyForm.productName': product.name,
      'buyForm.faceValue': price,
      'buyForm.looseFaceValue': price,
      'buyForm.packageId': null,
      'buyForm.unifiedQty': null,
      'buyForm.quantity': 1,
      'buyForm.totalPrice': price
    })
    this.loadBuyPackages(product.id)
  },

  /**
   * 拉该商品在本站能买的档位。
   *
   * <p>后端返回的每一项带 {@code source}：{@code CUSTOM} = 站长给这款水挂的定制档位；
   * {@code UNIFIED} = 站级统一折扣按**这款水自己的价**折算出来的档位。两者在界面上
   * **长得一样**（产品口径：「在用户端看起来没区别」），前端只需记住选了哪一个。</p>
   *
   * <p>没档位就返回空数组 —— 此时页面保持"散买"（按单张水票价）。但对于**走统一折扣的商品**，
   * 散买没有价可依（它没配水票价），所以后端会拒；页面对这种商品不显示散买入口。</p>
   */
  async loadBuyPackages(productId) {
    this.setData({ buyPackages: [] })
    try {
      const res = await getTicketPackages(this.data.currentStationId, productId)
      this.setData({ buyPackages: res.data || [] })
    } catch (err) {
      // 档位拉不到不该挡住买票：静默降级为散买（不弹错误提示，避免"其实能买却提示失败"）
      console.warn('load ticket packages failed:', err && err.message)
    }
  },

  /**
   * 选档位。金额一律用**服务端下发的档位价**，前端不做 price/qty 的算术 ——
   * 均价是快照进水票批次的值，前端算一遍就会出现"界面一个价、批次另一个价"。
   * 张数必须等于档位张数（后端会校验）。
   *
   * <p>定制档回传 {@code packageId}；统一折扣档**只回传张数** {@code unifiedQty}
   * （价格由服务端按该款水的价 × 折扣现算，客户端传不了价也不该传）。</p>
   */
  onBuyPackageSelect(e) {
    const { id, source } = e.currentTarget.dataset
    // 统一档没有 packageId，只能按 qty 找；定制档按 id 找
    const pkg = this.data.buyPackages.find(p => source === 'UNIFIED'
      ? (p.source === 'UNIFIED' && String(p.qty) === String(id))
      : (p.source !== 'UNIFIED' && String(p.packageId || p.id) === String(id)))
    if (!pkg) return
    const custom = pkg.source !== 'UNIFIED'
    this.setData({
      'buyForm.packageId': custom ? (pkg.packageId || pkg.id) : null,
      'buyForm.unifiedQty': custom ? null : pkg.qty,
      'buyForm.quantity': pkg.qty,
      'buyForm.faceValue': pkg.unitPrice,
      'buyForm.totalPrice': pkg.price
    })
  },

  /** 退回散买：张数与单价都回到按单张水票价的口径。 */
  onBuyPackageClear() {
    const loose = this.data.buyForm.looseFaceValue || 0
    this.setData({
      'buyForm.packageId': null,
      'buyForm.unifiedQty': null,
      'buyForm.quantity': 1,
      'buyForm.faceValue': loose,
      'buyForm.totalPrice': loose
    })
  },

  /** 步进器 +/-（tap 事件，data-type=minus/add），数量下限 1 */
  onBuyQtyStep(e) {
    const { type } = e.currentTarget.dataset
    const cur = this.data.buyForm.quantity || 1
    const next = type === 'add' ? cur + 1 : Math.max(1, cur - 1)
    if (next === cur) return
    this.applyLooseQty(next)
  },

  /** 手动输入数量（input 事件） */
  onBuyQuantityInput(e) {
    this.applyLooseQty(Math.max(1, parseInt(e.detail.value) || 1))
  },

  /**
   * 改张数即放弃已选档位，回到散买口径。
   *
   * 档位价对应的是**固定张数**（后端会校验 quantity === pkg.qty）。张数一变，档位价就不再适用；
   * 若留着 packageId，提交会被后端以"购买张数与档位不一致，请重新选择"拒回 ——
   * 与其让客户撞一次错误，不如在这里直接退回散买并把单价换回单张水票价。
   */
  applyLooseQty(qty) {
    const loose = this.data.buyForm.looseFaceValue || 0
    this.setData({
      'buyForm.packageId': null,
      'buyForm.unifiedQty': null,
      'buyForm.quantity': qty,
      'buyForm.faceValue': loose,
      'buyForm.totalPrice': loose * qty
    })
  },

  /** 选择支付方式（tap 事件，data-id） */
  onBuyPaymentMethodSelect(e) {
    const { id } = e.currentTarget.dataset
    this.setData({ 'buyForm.paymentMethod': parseInt(id) || 1 })
  },

  async onBuySubmit() {
    const { productId, quantity, paymentMethod, packageId, unifiedQty } = this.data.buyForm
    if (!productId && productId !== 0) {
      wx.showToast({ title: '请选择商品', icon: 'none' })
      return
    }
    if (!quantity || quantity <= 0) {
      wx.showToast({ title: '请输入正确数量', icon: 'none' })
      return
    }
    if (!this.data.currentStationId) {
      wx.showToast({ title: '请先选择水站', icon: 'none' })
      return
    }

    // 幂等键：同一笔购买意图（含失败重试）必须复用同一个值，否则「连点两次」或
    // 「超时后重试」都会各落一条待收款流水，站长两条都确认就会入账两次。
    // 与 pages/order/create.js 的差别：那里失败后重新生成键（下一单是新意图），
    // 这里失败时**保留**原键 —— 购票只有「买成」与「没买成」两种结果，重试就是在重试同一件事。
    const idempotencyKey = this.data.purchaseIdempotencyKey || this.genPurchaseIdempotencyKey()
    this.setData({ submitting: true, purchaseIdempotencyKey: idempotencyKey })
    try {
      const res = await purchaseTicket({
        productId: productId,
        waterTypeId: productId, // 兼容旧字段
        quantity: quantity,
        paymentMethod: paymentMethod,
        stationId: this.data.currentStationId,
        idempotencyKey: idempotencyKey,
        // 定制档：张数与总价一律以服务端档位配置为准（客户端传的价格会被忽略）
        packageId: packageId || null,
        // 统一折扣档：**只传张数**，价格由服务端按"这款水自己的价 × 该档折扣"现算
        unifiedQty: unifiedQty || null
      })
      // 后端此时只创建了待支付流水，水票要等支付确认后才入账。
      // 旧实现无条件提示"购买成功"，客户看到余额为空会以为系统吞了钱。
      // 这里按真实 status 区分：2=已支付(票已到账)，1=待支付(等水站确认)。
      const status = (res && res.data && res.data.status) != null ? res.data.status : 1
      this.onClosePurchase()
      // 本次购买意图已落库，换一个新键，避免「下一次购买」被当成重放而返回上一笔
      this.setData({ purchaseIdempotencyKey: this.genPurchaseIdempotencyKey() })
      await this.loadData()
      if (status === 2) {
        wx.showToast({ title: '购买成功，水票已到账', icon: 'success' })
      } else {
        wx.showModal({
          title: '已提交，等待到账',
          content: '购买申请已提交给水站，水站确认收款后水票才会到账。如长时间未到账请联系水站。',
          showCancel: false,
          confirmText: '知道了'
        })
      }
    } catch (error) {
      console.error('Purchase ticket error:', error)
      wx.showToast({ title: error.message || '购买失败', icon: 'none' })
    } finally {
      this.setData({ submitting: false })
    }
  }
})