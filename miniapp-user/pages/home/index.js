// 首页 · Step4 锁稿落地（2026-09-11）
// 六区块：头部卡 / 再来一单 / 配送状态条 / 订水区 / 桶账一行 / 合计下单栏
// 原则：桶账与金额口径只读后端派生字段，前端不做业务加减（合计栏除外——那是基于后端下发单价的选购预估）。
const { getStationProducts } = require('../../api/product')
const { getOrders, getOrderDetail, getMyLatestStation } = require('../../api/order')
const { getAddresses } = require('../../api/address')
const { getBarrelSummary, getBarrelSummaryByType } = require('../../api/barrel')
const { getUnreadNotifications, markAllRead } = require('../../api/notification')
const { getPublicStations, getStationStatus } = require('../../api/station')
const { storage, stationStorage } = require('../../utils/storage')
const { formatAddress } = require('../../utils/address')

// 金额展示：整数不带小数点，非整数保留两位（纯展示，不涉及计算口径）
const fmtMoney = (n) => {
  const v = Number(n) || 0
  return (Math.round(v * 100) / 100).toFixed(v % 1 === 0 ? 0 : 2)
}

Page({
  data: {
    loading: true,
    statusBarHeight: 44,
    isLogin: false,
    state: 'guest', // guest / noStation / claimPending / ready
    greeting: '你好',
    address: null,
    addressHint: '点击设置配送地址',
    heroImage: '',
    products: [],       // 原始商品列表（后端下发单价/押金）
    productsView: [],   // 渲染用：合并了 qty / priceText / depositText
    cart: {},
    total: { count: 0, waterText: '0', depositText: '0', grandText: '0' },
    againOrder: null,   // { img, summary, items:[{productId, quantity}] }
    activeShip: null,   // { id, title, sub }
    barrelVisible: false,
    barrelLine1: '',
    barrelLine2: '',
    currentStation: null,
    currentStationId: null,
    // 水站营业状态（软状态，v32）：文案与"要不要提醒"全部由后端下发，前端不做 1..4 映射。
    // stationStatusHint 非空 = 需要提醒（休息中/配送延迟/暂停配送），只影响配色，不阻断下单。
    stationStatusText: '',
    stationStatusNote: '',
    stationStatusHint: '',
    showStationList: false,
    stationList: [],
    // 桶权益按商品：{ productId: 权益数量 }，来自后端 /api/barrels/summary-by-type
    // 用途：合计栏计算「缺桶押金」时抵扣已有权益，口径与后端 payments/quote 一致
    barrelRights: {}
  },

  onLoad() {
    try {
      const info = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync()
      this.setData({ statusBarHeight: info.statusBarHeight || 44 })
    } catch (e) {}
    this.setData({ greeting: this.buildGreeting() })
  },

  onShow() {
    const app = getApp()
    const isLogin = app.globalData.isLogin
    this.setData({ isLogin })

    if (!isLogin) {
      this.setData({ state: 'guest', products: [], productsView: [], loading: false })
      return
    }

    this.checkStation()
    this.checkNotifications()

    const selectedAddress = storage.get('selectedAddress')
    if (selectedAddress) {
      this.setData({ address: selectedAddress })
      storage.remove('selectedAddress')
    }

    const selectedProductId = storage.get('selectedProductId')
    if (selectedProductId) {
      this._pendingProductId = selectedProductId
      storage.remove('selectedProductId')
    }
  },

  onPullDownRefresh() {
    this.checkStation().then(() => wx.stopPullDownRefresh())
  },

  /**
   * 水站营业状态（软状态，v32）：只提示、不阻断下单。
   *
   * ⚠️ 文案与"要不要提醒"全部取后端下发的 statusText / note / customerHint ——
   * 前端不做 operatingStatus 1..4 的映射（本仓明文禁止自带映射表）。
   * 拉不到就**不显示**：宁可不显示，也不要编造一个"营业中"。
   */
  async loadStationStatus(stationId) {
    if (!stationId) {
      this.setData({ stationStatusText: '', stationStatusNote: '', stationStatusHint: '' })
      return
    }
    try {
      const res = await getStationStatus(stationId)
      const d = (res && res.data) || {}
      this.setData({
        stationStatusText: d.statusText || '',
        stationStatusNote: d.note || '',
        stationStatusHint: d.customerHint || ''
      })
    } catch (e) {
      this.setData({ stationStatusText: '', stationStatusNote: '', stationStatusHint: '' })
    }
  },

  /** 点状态胶囊：把完整说明（状态 + 站长留言）摊开给客户看 */
  onStationStatusTap() {
    const { stationStatusText, stationStatusNote } = this.data
    if (!stationStatusText) return
    wx.showModal({
      title: '水站状态',
      content: stationStatusNote ? stationStatusText + '\n' + stationStatusNote : stationStatusText,
      showCancel: false,
      confirmText: '知道了'
    })
  },

  buildGreeting() {
    const h = new Date().getHours()
    if (h < 6) return '夜深了'
    if (h < 11) return '早上好，该喝水了'
    if (h < 14) return '中午好，该喝水了'
    if (h < 18) return '下午好，该喝水了'
    return '晚上好'
  },

  async checkStation() {
    this.setData({ loading: true })
    let completed = false
    try {
      // 1. 优先使用本地存储的水站
      if (stationStorage.getId()) {
        this.setData({
          currentStationId: stationStorage.getId(),
          currentStation: stationStorage.get()
        })
        await this.loadData()
        completed = true
        return
      }

      // 2. 本地没有水站，尝试从后端获取最近下单的水站（自动恢复）
      try {
        const stationRes = await getMyLatestStation()
        if (stationRes && stationRes.data && stationRes.data.stationId) {
          const s = stationRes.data
          const station = { id: s.stationId, name: s.stationName || '水站' }
          stationStorage.set(station)
          this.setData({ currentStationId: station.id, currentStation: station })
          await this.loadData()
          completed = true
          return
        }
      } catch (e) {
        // [2026-09-20 真机联调] 原来这里是**空 catch** + 注释「无历史订单，继续走流程」——
        // 等于把"接口失败/断网"与"确实没有历史订单"当成同一件事，真机上根本分不出来。
        // 行为保持不变（继续走选站流程，用户体验不受影响），但必须留下可查的痕迹。
        console.warn('[home] 取上次下单水站失败，按"无历史订单"继续:', e && (e.errMsg || e.message))
      }

      // 3. 无历史订单，显示选择列表
      this.setData({ state: 'noStation' })
      await this.loadStationList()
    } catch (e) {
      console.error('检查水站失败:', e)
      this.setData({ state: 'noStation' })
      await this.loadStationList()
    } finally {
      if (!completed) this.setData({ loading: false })
    }
  },

  /** 检查未读通知，逐条弹窗提醒（拒单/临时外派） */
  async checkNotifications() {
    try {
      const res = await getUnreadNotifications().catch(() => null)
      if (res && res.code === 0 && res.data && res.data.length > 0) {
        const notifications = res.data
        for (let i = 0; i < notifications.length; i++) {
          const n = notifications[i]
          await new Promise((resolve) => {
            wx.showModal({
              title: n.title || '消息提醒',
              content: n.content || '',
              showCancel: false,
              confirmText: '我知道了',
              success: () => resolve()
            })
          })
        }
        await markAllRead().catch(() => {})
      }
    } catch (e) {
      console.error('检查通知失败:', e)
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

  onSelectStation(e) {
    const { id } = e.currentTarget.dataset
    this.confirmStation(id)
  },

  async confirmStation(stationId) {
    try {
      const station = this.data.stationList.find(s => s.id === stationId)
      if (!station) {
        wx.showToast({ title: '水站不存在', icon: 'none' })
        return
      }

      // 本地提示：不同水站资产不互通
      const noticeDisabled = stationStorage.getSwitchNoticeDisabled()
      if (!noticeDisabled && this.data.currentStationId && this.data.currentStationId !== stationId) {
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
        if (!confirm) return
        const dontShow = await new Promise(resolve => {
          wx.showModal({
            title: '提示',
            content: '下次不再提示？',
            confirmText: '不再提示',
            cancelText: '每次都提示',
            success: (r) => resolve(r.confirm)
          })
        })
        if (dontShow) stationStorage.setSwitchNoticeDisabled(true)
      }

      wx.showToast({ title: '已选择水站', icon: 'success' })
      this.setData({ showStationList: false })
      stationStorage.set(station)
      await this.checkStation()
    } catch (e) {
      console.error('选择水站失败:', e)
      wx.showToast({ title: '选择失败', icon: 'none' })
    }
  },

  onOpenStationList() {
    this.setData({ showStationList: true })
    this.loadStationList()
  },

  onCloseStationList() {
    this.setData({ showStationList: false })
  },

  async loadData() {
    this.setData({ loading: true })
    try {
      const app = getApp()
      const stationId = this.data.currentStationId

      // 营业状态跟着首页一起刷新（站长刚改成"休息中"，客户回到首页就该看到）
      this.loadStationStatus(stationId)

      const [ordersRes, addressRes, summaryRes, rightsRes, productsRes] = await Promise.all([
        getOrders({}).catch(() => null),
        getAddresses().catch(() => null),
        getBarrelSummary(stationId).catch(() => null),
        stationId ? getBarrelSummaryByType(stationId).catch(() => null) : Promise.resolve(null),
        stationId ? getStationProducts(stationId).catch(() => null) : Promise.resolve(null)
      ])

      // 地址：只决定头部卡提示与下单参数，不再单独占一张卡
      let address = this.data.address
      if (addressRes && addressRes.data && addressRes.data.length > 0) {
        const list = addressRes.data
        address = list.find(a => a.isDefault) || list[0]
      }
      const addressHint = address ? `配送至：${formatAddress(address)}` : '点击设置配送地址'

      // 后端下发的是**本站有效价**（站级覆盖 → 通用库参考价）；统一映射回 price/deposit，
      // 让本页下方那段"价格/押金文案 + 押金合计"的既有算法用上站级价（否则首页显示的价与结算价不一致）。
      const products = ((stationId && productsRes && productsRes.data) ? productsRes.data : []).map(p => ({
        ...p,
        price: p.effectivePrice != null ? p.effectivePrice : p.price,
        deposit: p.effectiveDeposit != null ? p.effectiveDeposit : p.deposit
      }))
      // 头部卡美术图 = 本站主力商品（第一个）档案照；没有图则占位
      const heroImage = products.length > 0 && products[0].imageUrl ? products[0].imageUrl : ''

      // 购物车（按水站独立保留）
      const cart = app.getCart(stationId)
      products.forEach(p => { if (cart[p.id] === undefined) cart[p.id] = 0 })
      if (this._pendingProductId) {
        const target = products.find(p => p.id === this._pendingProductId)
        if (target) cart[this._pendingProductId] = (cart[this._pendingProductId] || 0) + 1
        this._pendingProductId = null
      }

      // 桶权益按商品（合计栏抵扣缺桶押金用，口径与后端 payments/quote 一致）
      const barrelRights = this.parseBarrelRights(rightsRes)

      this.setData({ address, addressHint, products, cart, heroImage, barrelRights })
      this.refreshDerived(barrelRights)

      // 桶账一行：口径全部来自后端 summary（权益/占用/配送中/水站暂存/欠桶/可退押金）
      const summary = (summaryRes && summaryRes.data) ? summaryRes.data : {}
      this.renderBarrelLine(summary)

      // 进行中订单 → 配送状态条（最多展示 1 条）
      const orders = (ordersRes && ordersRes.data) ? ordersRes.data : []
      const active = orders.find(o => o.status === 1 || o.status === 2)
      await this.renderActiveShip(active)

      // 最近一笔已送达/已完成订单 → 再来一单
      const lastDone = orders.find(o => o.status === 3 || o.status === 4)
      await this.renderAgainOrder(lastDone, products)

      this.setData({ state: 'ready' })
    } finally {
      this.setData({ loading: false })
    }
  },

  /** 桶账一行：只读后端派生口径，前端不加减 */
  renderBarrelLine(s) {
    // [2026-09-16 修复] 三个数各有其源，不能混用：
    //   rightBuckets    = 权益（已到手）—— 下单抵扣 / 退押金认的是这个
    //   heldBuckets     = 持有 = 权益 + 配送中（"买了就是你的"，barrel 页与员工端都用它）
    //   occupiedBuckets = 占用 = 权益 + over（物理在手，不含配送中）
    // 此前第一项取的是 heldBuckets 却标成"权益"，把在途算进了权益；
    // 配送中取的是 deliveryBuckets（含已送达的 DELIVERED 行），送达后仍会显示"配送中 N"。
    const right = s.rightBuckets != null ? s.rightBuckets : (s.heldBuckets || 0)
    const occupied = s.occupiedBuckets != null ? s.occupiedBuckets : right
    const delivery = s.pendingDeliveryBuckets || 0  // 配送中：只含 PENDING
    const storageN = s.storageBuckets || 0   // 水站暂存（over<0，合法状态）
    const owed = s.owedBuckets || 0          // 欠桶
    const balance = Number(s.depositBalance) || 0

    const visible = right > 0 || occupied > 0 || delivery > 0 || storageN > 0 || owed > 0 || balance > 0
    if (!visible) {
      this.setData({ barrelVisible: false, barrelLine1: '', barrelLine2: '' })
      return
    }

    let line1 = `权益 ${right} · 占用 ${occupied}`
    if (delivery > 0) line1 += ` · 配送中 ${delivery}`
    if (storageN > 0) line1 += ` · 水站暂存 ${storageN} 个`
    if (owed > 0) line1 += ` · 欠 ${owed} 个`

    const parts = []
    if (storageN > 0) parts.push('暂存桶下次订水自动抵扣')
    if (balance > 0) parts.push(`可退押金 ¥${fmtMoney(balance)}（退桶按买入价退）`)

    this.setData({ barrelVisible: true, barrelLine1: line1, barrelLine2: parts.join(' · ') })
  },

  /** 配送状态条：进行中订单（待配送1/配送中2），附商品摘要 */
  async renderActiveShip(order) {
    if (!order) {
      this.setData({ activeShip: null })
      return
    }
    const title = order.status === 2 ? '配送中 · 师傅正在送来' : '待配送 · 水站备货中'
    let sub = ''
    const detail = await getOrderDetail(order.id).catch(() => null)
    if (detail && detail.data && detail.data.items && detail.data.items.length > 0) {
      sub = detail.data.items
        .map(it => `${it.productNameSnapshot || '桶装水'} ×${it.quantity || 0}`)
        .join('、')
    }
    this.setData({ activeShip: { id: order.id, title, sub } })
  },

  /** 再来一单：取最近一笔已送达/已完成订单的商品组合；「加入」直接写进步进器，不跳页 */
  async renderAgainOrder(order, products) {
    if (!order) {
      this.setData({ againOrder: null })
      return
    }
    const detail = await getOrderDetail(order.id).catch(() => null)
    if (!detail || !detail.data || !detail.data.items || detail.data.items.length === 0) {
      this.setData({ againOrder: null })
      return
    }
    const items = detail.data.items
      .filter(it => (it.productId != null) && (it.quantity || 0) > 0)
      .map(it => ({ productId: it.productId, quantity: it.quantity, name: it.productNameSnapshot || '桶装水' }))
    if (items.length === 0) {
      this.setData({ againOrder: null })
      return
    }
    const names = items.slice(0, 2).map(it => `${it.name} ×${it.quantity}`)
    const summary = items.length > 2 ? `${names.join('、')} 等${items.length}件` : names.join('、')
    // 缩略图取第一件商品的本站档案照（商品可能已下架，下架则占位）
    const first = products.find(p => String(p.id) === String(items[0].productId))
    const img = first && first.imageUrl ? first.imageUrl : ''
    this.setData({ againOrder: { img, summary, items } })
  },

  /**
   * 桶权益按商品归集：{ productId: 权益数量 }
   * 数据源 /api/barrels/summary-by-type（后端 BarrelServiceImpl，驼峰键 assetQty）。
   * 拿不到就返回空对象 —— 退化成"全额收押金"，与后端 quote 在拿不到资产时的行为一致（不会算少）。
   */
  parseBarrelRights(res) {
    const list = (res && res.data) ? res.data : []
    const map = {}
    list.forEach(it => {
      if (it && it.productId != null) {
        map[String(it.productId)] = Number(it.assetQty) || 0
      }
    })
    return map
  },

  /**
   * 合并 cart → productsView + 合计栏。
   *
   * 【押金口径必须与后端 PaymentServiceImpl.quote() 保持一致】
   *  - 桶装水（category === 1）：只为「缺的桶」付押金 → shortage = max(0, 需要 − 该商品已持有权益)。
   *    顾客已拥有的桶权益【不重复收押金】，这是"押金 = 买桶权益"的定义决定的。
   *  - 非桶商品：无权益概念，按数量全额收押金。
   *
   * 旧实现无脑 deposit += qty × p.deposit，导致有桶权益的顾客在首页看到虚高押金，
   * 跳到下单页（走后端 quote）数字又变正确 —— 同一个购物车两个价。已在 2026-09-12 对齐。
   */
  refreshDerived(rights) {
    const { products, cart } = this.data
    const heldMap = rights || this.data.barrelRights || {}
    let count = 0, water = 0, deposit = 0
    const productsView = products.map(p => {
      const qty = parseInt(cart[p.id]) || 0
      const unitDeposit = Number(p.deposit) || 0
      let depositNote = ''

      if (qty > 0) {
        count += qty
        water += qty * (Number(p.price) || 0)

        if (Number(p.category) === 1) {
          // 桶装水：已有权益的桶不再收押金
          const held = Number(heldMap[String(p.id)]) || 0
          const shortage = Math.max(0, qty - held)
          deposit += shortage * unitDeposit
          if (held > 0 && shortage === 0) {
            depositNote = `已享 ${held} 个桶权益，无需再付押金`
          } else if (held > 0) {
            depositNote = `已享 ${held} 个桶权益，另需 ${shortage} 个桶押金 ¥${fmtMoney(shortage * unitDeposit)}`
          } else if (unitDeposit > 0) {
            depositNote = `桶押金 ¥${fmtMoney(unitDeposit)}/个`
          }
        } else {
          deposit += qty * unitDeposit
          if (unitDeposit > 0) depositNote = `押金 ¥${fmtMoney(unitDeposit)}/个`
        }
      }

      return {
        ...p,
        qty,
        depositNote,
        priceText: fmtMoney(p.price),
        depositText: fmtMoney(p.deposit)
      }
    })

    const waterText = fmtMoney(water)
    const depositText = fmtMoney(deposit)
    this.setData({
      productsView,
      total: {
        count,
        waterText,
        depositText,
        grandText: fmtMoney(water + deposit),
        // wxml 不能做三元拼接，文案在 JS 里算好
        detailText: count === 0
          ? '选中数量后自动合计'
          : (deposit > 0 ? `（水款 ¥${waterText} + 押金 ¥${depositText}）` : `（水款 ¥${waterText}）`)
      }
    })
  },

  onAddressTap() {
    wx.navigateTo({ url: '/pages/address/list?from=home' })
  },

  onAgainTap() {
    const { againOrder, products } = this.data
    if (!againOrder) return
    const app = getApp()
    const stationId = this.data.currentStationId
    const cart = app.getCart(stationId)
    let added = 0
    againOrder.items.forEach(it => {
      const target = products.find(p => String(p.id) === String(it.productId))
      if (target) { // 已下架/非本站商品不写入，避免下出幽灵商品
        cart[it.productId] = (parseInt(cart[it.productId]) || 0) + it.quantity
        added += it.quantity
      }
    })
    if (added === 0) {
      wx.showToast({ title: '原商品已下架', icon: 'none' })
      return
    }
    this.setData({ cart })
    this.refreshDerived()
    wx.showToast({ title: '已加入订水清单', icon: 'none' })
  },

  onViewActiveOrder() {
    const { activeShip } = this.data
    if (activeShip) {
      wx.navigateTo({ url: `/pages/order/detail?id=${activeShip.id}` })
    }
  },

  onGoBarrel() {
    wx.navigateTo({ url: '/pages/barrel/index' })
  },

  onGoShop() {
    wx.navigateTo({ url: '/pages/shop/index' })
  },

  onGoSearch() {
    // 搜索统一到商城页（shop 的搜索范围 = 本站商品，与"只能买本站商品"的业务一致）。
    // 原先指向 pages/home/search（服务端全量搜索，会搜出别站商品、点进去无法下单），
    // 该页已于 2026-09-12 删除合并。focus=1 让商城页自动聚焦输入框，少点一步。
    wx.navigateTo({ url: '/pages/shop/index?focus=1' })
  },

  onLogin() {
    wx.navigateTo({ url: '/pages/login/index' })
  },

  collectCartItems() {
    const app = getApp()
    const stationId = this.data.currentStationId
    const cart = app.getCart(stationId)
    const items = []
    this.data.products.forEach(p => {
      const qty = parseInt(cart[p.id]) || 0
      if (qty > 0) items.push({ productId: p.id, quantity: qty })
    })
    return items
  },

  onCartQtyChange(e) {
    const { id, type } = e.currentTarget.dataset
    const app = getApp()
    const stationId = this.data.currentStationId
    const cart = app.getCart(stationId)
    let qty = parseInt(cart[id]) || 0
    if (type === 'add') qty++
    else if (type === 'minus' && qty > 0) qty--
    cart[id] = qty
    this.setData({ cart })
    this.refreshDerived()
  },

  onSubmit() {
    const { address, total } = this.data
    if (total.count === 0) {
      wx.showToast({ title: '请选择商品', icon: 'none' })
      return
    }
    if (!address) {
      wx.showModal({
        title: '还没有配送地址',
        content: '先设置一个收货地址，水才能送到家',
        confirmText: '去设置',
        success: (r) => {
          if (r.confirm) wx.navigateTo({ url: '/pages/address/list?from=home' })
        }
      })
      return
    }
    const items = this.collectCartItems()
    const itemsParam = items.map(it => ({ productId: it.productId, quantity: it.quantity }))
    wx.navigateTo({
      url: `/pages/order/create?items=${encodeURIComponent(JSON.stringify(itemsParam))}&addressId=${address.id}&addressDetail=${encodeURIComponent(address.detail || '')}&source=3&stationId=${this.data.currentStationId || ''}`
    })
  }
})
