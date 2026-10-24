// 首页 · Step4 锁稿落地（2026-09-11）
// [2026-09-26 Wave1 轨道 B] 信息顺序重排为：水站与营业状态 / 收货地址 / 当前订单（有才显示）/
// 再买上次 / 商品列表 / 水桶与押金入口。顺序与文案见 docs/design/29 §3「订水首页」。
// 原则：桶账与金额口径只读后端派生字段，前端不做业务加减（合计栏除外——那是基于后端下发单价的选购预估）。
const { getStationProducts } = require('../../api/product')
const { getOrders, getOrderDetail, getMyLatestStation } = require('../../api/order')
const { getAddresses } = require('../../api/address')
const { getBarrelSummary, getBarrelSummaryByType } = require('../../api/barrel')
const { getUnreadNotifications, markAllRead } = require('../../api/notification')
const { getPublicStations, getStationStatus } = require('../../api/station')
const { getQuote } = require('../../api/payment')
const { storage, stationStorage, payMethodStorage } = require('../../utils/storage')
const { captureSession, isCurrentSession } = require('../../utils/token')
const { formatAddress } = require('../../utils/address')
const { itemUnit } = require('../../utils/order-item-view')
const { displayMessage } = require('../../utils/display-message')
const { homeNavLayout, catalogState } = require('./home-view-state')

// 金额展示：整数不带小数点，非整数保留两位（纯展示，不涉及计算口径）
const fmtMoney = (n) => {
  const v = Number(n) || 0
  return (Math.round(v * 100) / 100).toFixed(v % 1 === 0 ? 0 : 2)
}

Page({
  data: {
    loading: true,
    // 数据没加载出来时的提示（空串 = 全部正常）。见 _noteLoadError 的说明。
    loadError: '',
    loadIssueDetails: [],
    loadImpactSummary: '',
    notificationsLoading: false,
    homeRetrying: false,
    showLoadDetails: false,
    catalogState: 'idle', // idle / loading / error / empty / ready
    homeQuoteState: 'idle',
    purchaseHint: '',
    // 选站弹窗里"水站列表没拉出来"的原因（空串 = 拉到了或还没拉）。
    // 与 loadError 分开：选站列表在 noStation 状态下才是主角，混在一处会互相覆盖。
    stationListError: '',
    stationListLoading: false,
    statusBarHeight: 44,
    navBarHeight: 44,
    navContentWidth: 235,
    isLogin: false,
    state: 'guest', // guest / noStation / claimPending / ready
    greeting: '你好',
    address: null,
    addressHint: '点击设置配送地址',
    heroImage: '',
    homeWaterBackdropFailed: false,
    posterArtFailed: false,
    posterProductImageFailed: false,
    products: [],       // 原始商品列表（后端下发单价/押金）
    productsView: [],   // 渲染用：合并了 qty / priceText / depositText
    cart: {},
    unavailableCartCount: 0,
    total: { count: 0, waterText: '0', depositText: '0', grandText: '0' },
    againOrder: null,   // { img, summary, items:[{productId, quantity}] }
    activeShip: null,   // { id, title, sub }
    activeOrderCount: 0, // 进行中订单张数（>1 时当前订单条给「查看全部」入口，不在首页堆多张大卡）
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
    // 桶权益按商品：仅供服务端确认历史模式后的旧押金预估使用。
    barrelRights: {}
  },

  onLoad() {
    this._homeDestroyed = false
    this._homeHidden = false
    this.setData(homeNavLayout(wx))
    this.setData({ greeting: this.buildGreeting() })
  },

  onResize() {
    this.setData(homeNavLayout(wx))
  },

  onShow() {
    this._homeHidden = false
    this.invalidateHomeQuote()
    this.refreshDerived(undefined, false)
    const app = getApp()
    const isLogin = app.globalData.isLogin
    this._syncHomeIssueSession()
    this.setData({ isLogin })
    this._updateHomeFeedback()

    if (!isLogin) {
      this.setData({ state: 'guest', products: [], productsView: [], catalogState: 'idle', loading: false })
      return
    }

    if (!this._homeAddressSession || !isCurrentSession(this._homeAddressSession)) {
      this._homeChosenAddressId = null
      this.setData({ address: null, addressHint: '点击设置配送地址' })
    }
    this._homeAddressSession = captureSession()
    const selectedAddress = storage.get('selectedAddress')
    if (selectedAddress) {
      this._homeChosenAddressId = selectedAddress.id
      this.setData({ address: selectedAddress })
      storage.remove('selectedAddress')
    }
    this.checkStation()
    this.checkNotifications()

    const selectedProductId = storage.get('selectedProductId')
    if (selectedProductId) {
      this._pendingProductId = selectedProductId
      storage.remove('selectedProductId')
    }
  },

  onHide() {
    this._homeHidden = true
    this._homeLoadSeq = (this._homeLoadSeq || 0) + 1
    this._homeRetry = null
    this._notificationSeq = (this._notificationSeq || 0) + 1
    this._stationCheckSeq = (this._stationCheckSeq || 0) + 1
    this._stationListSeq = (this._stationListSeq || 0) + 1
    this._stationStatusSeq = (this._stationStatusSeq || 0) + 1
    this.setData({ showStationList: false, stationListLoading: false, notificationsLoading: false, homeRetrying: false })
    this.invalidateHomeQuote()
    this.refreshDerived(undefined, false)
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
    if (this._homeDestroyed || this._homeHidden) return
    const session = captureSession()
    const sequence = this._stationStatusSeq = (this._stationStatusSeq || 0) + 1
    const current = () => !this._homeDestroyed && !this._homeHidden
      && sequence === this._stationStatusSeq && isCurrentSession(session)
      && stationId === this.data.currentStationId
    // 2026-10-20：旧站的慢响应曾覆盖新站状态；换站先清空，回执只更新原站的当前读取。
    this.setData({ stationStatusText: '', stationStatusNote: '', stationStatusHint: '' })
    if (!stationId) {
      return
    }
    try {
      const res = await getStationStatus(stationId)
      if (!current()) return
      const d = (res && res.data) || {}
      this.setData({
        stationStatusText: d.statusText || '',
        stationStatusNote: d.note || '',
        stationStatusHint: d.customerHint || ''
      })
    } catch (e) {
      if (current()) this.setData({ stationStatusText: '', stationStatusNote: '', stationStatusHint: '' })
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

  // 同一登录周期的失败只在对应读取确认成功后清除；换号/重新登录不带入旧账号提醒。
  _syncHomeIssueSession() {
    if (!this._homeIssueSession || !isCurrentSession(this._homeIssueSession)) {
      this._homeIssueSession = captureSession()
      this._homeIssues = {}
      this.setData({ showLoadDetails: false, loadError: '', loadIssueDetails: [], loadImpactSummary: '' })
    }
  },

  _updateHomeFeedback() {
    const issues = this._homeIssues || {}
    const details = Object.keys(issues).map(key => ({ key, text: issues[key] }))
    const overview = issues.overview || ''
    const labels = []
    if (overview.includes('商品')) labels.push('商品')
    if (overview.includes('订单信息')) labels.push('订单状态')
    else if (issues.activeOrder || issues.againOrder) labels.push('订单详情')
    if (overview.includes('桶资产')) labels.push('桶资产')
    if (overview.includes('配送地址')) labels.push('配送地址')
    if (issues.notifications) labels.push('提醒')
    const impact = labels.length === 1 && labels[0] === '商品' ? '商品暂未加载，请重试后选水'
      : labels.length > 1 ? labels.slice(0, -1).join('、') + '与' + labels[labels.length - 1] + '暂未核实'
        : labels.length ? labels[0] + '暂未核实' : '相关信息暂未核实'
    this.setData({ loadIssueDetails: details, loadError: details.map(item => item.text).join('；'),
      loadImpactSummary: details.length ? impact : '',
      showLoadDetails: details.length ? this.data.showLoadDetails : false })
  },

  _noteLoadError(text, key = 'overview') {
    this._syncHomeIssueSession()
    if (text) this._homeIssues[key] = text
    else delete this._homeIssues[key]
    this._updateHomeFeedback()
  },

  onToggleLoadDetails() {
    this.setData({ showLoadDetails: !this.data.showLoadDetails })
  },

  async onRetryHome() {
    if (this._homeDestroyed || this._homeHidden || this.data.loading || this._homeRetry) return
    const retry = this._homeRetry = {}
    this.setData({ homeRetrying: true })
    try { await Promise.all([this.checkStation(), this.checkNotifications()]) }
    finally {
      if (this._homeRetry === retry) { this._homeRetry = null; this.setData({ homeRetrying: false }) }
    }
  },

  onRetryStationList() {
    if (!this.data.stationListLoading) return this.loadStationList()
  },

  showNoStation() {
    this.setData({ state: 'noStation', loading: false, currentStation: null, currentStationId: null,
      products: [], productsView: [], catalogState: 'idle', activeShip: null, againOrder: null,
      barrelVisible: false, purchaseHint: '' })
  },

  async checkStation() {
    if (this._homeDestroyed || this._homeHidden) return
    const session = captureSession()
    const sequence = this._stationCheckSeq = (this._stationCheckSeq || 0) + 1
    const selectedId = stationStorage.getId()
    // 2026-10-20：自动恢复的迟到结果曾改回客户刚选过的下单站；只接受同周期、同轮且未改站的结果。
    const current = () => !this._homeDestroyed && !this._homeHidden
      && sequence === this._stationCheckSeq && isCurrentSession(session)
      && stationStorage.getId() === selectedId
    this.invalidateHomeQuote()
    this.refreshDerived(undefined, false)
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
        if (!current()) return
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
        if (!current()) return
        // [2026-09-20 真机联调] 原来这里是**空 catch** + 注释「无历史订单，继续走流程」——
        // 等于把"接口失败/断网"与"确实没有历史订单"当成同一件事，真机上根本分不出来。
        // 行为保持不变（继续走选站流程，用户体验不受影响），但必须留下可查的痕迹。
        console.warn('[home] 取上次下单水站失败，按"无历史订单"继续:', e && (e.errMsg || e.message))
      }

      // 3. 无历史订单，显示选择列表
      if (!current()) return
      this.showNoStation()
      await this.loadStationList()
    } catch (e) {
      if (!current()) return
      console.error('检查水站失败:', e)
      this.showNoStation()
      await this.loadStationList()
    } finally {
      if (!completed && current()) this.setData({ loading: false })
    }
  },

  /** 检查未读通知，逐条弹窗提醒（拒单/临时外派） */
  async checkNotifications() {
    if (this._homeDestroyed || this._homeHidden) return
    const session = captureSession()
    if (!session.loggedIn || !session.customerId) return
    const sequence = this._notificationSeq = (this._notificationSeq || 0) + 1
    // 2026-10-22：原弹窗回调曾跨离页/换号继续提醒并用新会话标已读；每次等待后核对原上下文。
    const current = () => !this._homeDestroyed && !this._homeHidden
      && sequence === this._notificationSeq && isCurrentSession(session)
    this._syncHomeIssueSession()
    this.setData({ notificationsLoading: true })
    try {
      // 未读提醒失败单独记录，不阻断选站或订水内容。
      // 未读里可能有「订单被拒」「被临时外派」这类客户必须当场知道的事，静默 = 他永远不会知道。
      // 仍然不阻断首页（这是附加提醒），但必须在页面顶部留下痕迹。
      const res = await getUnreadNotifications().catch((e) => {
        if (!current()) return null
        console.warn('[home] 未读通知拉取失败:', e && (e.message || e.errMsg))
        this._noteLoadError('订单提醒暂未加载，可能暂时收不到最新通知，可重试。', 'notifications')
        return null
      })
      if (!current()) return
      if (!res || res.code !== 0 || !Array.isArray(res.data)) {
        if (res) this._noteLoadError('订单提醒暂未加载，可能暂时收不到最新通知，可重试。', 'notifications')
        return
      }
      this._noteLoadError('', 'notifications')
      if (res.data.length > 0) {
        const notifications = res.data
        for (let i = 0; i < notifications.length; i++) {
          const n = notifications[i]
          if (!current()) return
          const shown = await new Promise((resolve) => {
            wx.showModal({
              title: n.title || '消息提醒',
              content: n.content || '',
              showCancel: false,
              confirmText: '我知道了',
              success: () => resolve(true),
              fail: () => resolve(false)
            })
          })
          if (!shown || !current()) return
        }
        // 标记已读是**写操作**、不是"我们向用户断言了某个事实"，所以只留日志不弹提示：
        // 它失败的后果是"同一批提醒下次进首页会再弹一遍"，用户当场无从处置，弹 toast 只会添乱。
        // 但不能继续空 catch（原来是 `.catch(() => {})`）—— 否则排查时连"写失败"都看不见。
        await markAllRead().catch((e) => {
          console.warn('[home] 标记通知已读失败（下次进页面会重复提醒）:', e && (e.message || e.errMsg))
        })
      }
    } catch (e) {
      console.error('检查通知失败:', e)
      if (current()) this._noteLoadError('订单提醒暂未加载，可能暂时收不到最新通知，可重试。', 'notifications')
    } finally {
      if (current()) this.setData({ notificationsLoading: false })
    }
  },

  async loadStationList() {
    if (this._homeDestroyed || this._homeHidden) return
    const session = captureSession()
    const sequence = this._stationListSeq = (this._stationListSeq || 0) + 1
    const current = () => !this._homeDestroyed && !this._homeHidden
      && sequence === this._stationListSeq && isCurrentSession(session)
    // 2026-10-22：首次请求未完成曾显示“暂无水站”，重试还可点旧行；加载、失败和真实空结果分开。
    this.setData({ stationListLoading: true, stationList: [], stationListError: '' })
    // [2026-09-20 真机联调] 原来是 `.catch(() => null)` + 外层只 console.error：
    // 接口失败时列表为空，弹窗渲染成「暂无可用水站」—— 把"没查到"说成了"平台真的没有水站"，
    // 客户会以为整个平台都没水站可选。现在把失败原因记进 stationListError，由 wxml 分开说。
    try {
      const res = await getPublicStations()
      if (!current()) return
      // 业务失败仍是 HTTP 200，一律判 body.code（AGENTS §8.1）；code!=0 也要出声，不能沿用旧列表
      if (res && res.code === 0 && Array.isArray(res.data)) {
        const activeStations = res.data.filter(s => s.status === 1)
        this.setData({ stationList: activeStations, stationListError: '' })
      } else {
        console.warn('[home] 水站列表返回非成功响应:', res)
        this.setData({ stationList: [], stationListError: displayMessage(res && res.message, '水站列表暂时无法加载，请稍后重试') })
      }
    } catch (e) {
      if (!current()) return
      console.error('[home] 加载水站列表失败:', e)
      // 2026-10-20：旧列表仍可点击且遮住失败提示；失败须清行，不能把过期候选冒充本次读取成功。
      this.setData({ stationList: [], stationListError: displayMessage(e && e.message, '水站列表暂时无法加载，请稍后重试') })
      wx.showToast({ title: '水站列表没加载出来，请重试', icon: 'none' })
    } finally {
      if (current()) this.setData({ stationListLoading: false })
    }
  },

  onSelectStation(e) {
    const { id } = e.currentTarget.dataset
    this.confirmStation(id)
  },

  async confirmStation(stationId) {
    if (this._homeDestroyed || this._homeHidden) return
    const session = captureSession()
    const sequence = this._stationSelectSeq = (this._stationSelectSeq || 0) + 1
    const checkSequence = this._stationCheckSeq
    const selectedId = stationStorage.getId()
    // 2026-10-20：切站弹窗的确认也可能迟到；旧会话/旧页面/已刷新范围不能再保存下单站。
    const current = () => !this._homeDestroyed && !this._homeHidden && isCurrentSession(session)
      && sequence === this._stationSelectSeq && checkSequence === this._stationCheckSeq
      && selectedId === stationStorage.getId()
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
            confirmText: '继续',
            cancelText: '取消',
            showCancel: true,
            success: (r) => resolve(r.confirm)
          })
        })
        if (!current() || !confirm) return
        const dontShow = await new Promise(resolve => {
          wx.showModal({
            title: '提示',
            content: '下次不再提示？',
            confirmText: '不再提示',
            cancelText: '继续提示',
            success: (r) => resolve(r.confirm)
          })
        })
        if (!current()) return
        if (dontShow) stationStorage.setSwitchNoticeDisabled(true)
      }

      if (!current()) return
      this.invalidateHomeQuote()
      this.refreshDerived(undefined, false)
      wx.showToast({ title: '已选择水站', icon: 'success' })
      this.setData({ showStationList: false })
      stationStorage.set(station)
      await this.checkStation()
    } catch (e) {
      if (!current()) return
      console.error('选择水站失败:', e)
      wx.showToast({ title: '选择失败', icon: 'none' })
    }
  },

  onOpenStationList() {
    this.setData({ showStationList: true })
    if (!this.data.stationListLoading) return this.loadStationList()
  },

  onCloseStationList() {
    this.setData({ showStationList: false })
  },

  onUnload() {
    this._homeDestroyed = true
    this._homeLoadSeq = (this._homeLoadSeq || 0) + 1
    this.invalidateHomeQuote()
  },

  async loadHomeOrders(session) {
    if (!session.loggedIn || !session.customerId) return { data: [] }
    const fetchPage = async (status, page, pageSize) => {
      const res = await getOrders({ status, page, pageSize })
      if (!isCurrentSession(session)) throw new Error('登录状态已变化，请重新加载订单')
      const rows = Array.isArray(res) ? res : res && res.data
      if (!Array.isArray(rows) || rows.some(order => !order || order.id == null)) {
        throw new Error('订单数据没有完整加载，请重试')
      }
      return rows
    }
    const activeOrders = async status => {
      const orders = [], ids = new Set(), pageSize = 200
      for (let page = 1; ; page++) {
        const rows = await fetchPage(status, page, pageSize)
        let added = 0
        rows.forEach(order => {
          if (!ids.has(order.id)) { ids.add(order.id); orders.push(order); added++ }
        })
        if (rows.length < pageSize) return orders
        // 页码被忽略或内容重复时不能无限循环，也不能报一个被截断的总数。
        if (!added) throw new Error('订单数据没有完整加载，请重试')
      }
    }
    // 进行中单单独完整分页；再买上次仍取创建时间最新的已送达/已完成单。
    const groups = await Promise.all([
      activeOrders(1), activeOrders(2), fetchPage(3, 1, 1), fetchPage(4, 1, 1)
    ])
    const unique = new Map()
    groups.forEach(rows => rows.forEach(order => { unique.set(order.id, order) }))
    const orders = [...unique.values()].sort((a, b) => {
      const first = String(a.createTime || ''), second = String(b.createTime || '')
      return first === second ? 0 : first < second ? 1 : -1
    })
    return { data: orders }
  },

  async loadData() {
    if (this._homeDestroyed || this._homeHidden) return
    this.invalidateHomeQuote()
    this.refreshDerived(undefined, false)
    const session = captureSession()
    const version = this._homeLoadSeq = (this._homeLoadSeq || 0) + 1
    const stationId = this.data.currentStationId
    const current = () => !this._homeDestroyed && !this._homeHidden && version === this._homeLoadSeq
      && isCurrentSession(session) && stationId === this.data.currentStationId
    this.setData({ loading: true, catalogState: 'loading', purchaseHint: '' })
    try {
      const app = getApp()

      // 营业状态跟着首页一起刷新（站长刚改成"休息中"，客户回到首页就该看到）
      this.loadStationStatus(stationId)

      const failed = []
      const softRead = (tag, request, valid) => Promise.resolve(request).then(res => {
        if (!res || (res.code !== undefined && res.code !== 0) || (valid && !valid(res.data))) {
          throw new Error(tag + '返回内容不完整')
        }
        return res
      }).catch(error => {
        if (current()) console.warn('[home] ' + tag + '加载失败:', error && (error.message || error.errMsg))
        if (!failed.includes(tag)) failed.push(tag)
        return null
      })
      const [ordersRes, addressRes, summaryRes, rightsRes, productsRes] = await Promise.all([
        softRead('订单信息', this.loadHomeOrders(session)),
        softRead('配送地址', getAddresses(), Array.isArray),
        softRead('桶资产', getBarrelSummary(stationId), value => !!value && typeof value === 'object' && !Array.isArray(value)),
        stationId ? softRead('桶资产', getBarrelSummaryByType(stationId), Array.isArray) : Promise.resolve(null),
        stationId ? softRead('商品', getStationProducts(stationId), Array.isArray) : Promise.resolve(null)
      ])
      if (!current()) return
      const productState = catalogState(productsRes)
      this._noteLoadError(failed.length ? '暂未加载：' + failed.join('、') + '。可重新加载后查看完整信息。' : '', 'overview')
      const assetsLoaded = !!summaryRes && !!rightsRes
      const purchaseHint = assetsLoaded ? '' : '桶资产暂未核实，确认页核对后才能提交订单。'

      // 2026-10-23：地址列表返回后刷新曾改回默认地址；本周期明确选择的ID必须重读同一条。
      let address = this.data.address
      const chosenId = isCurrentSession(this._homeAddressSession) ? this._homeChosenAddressId : null
      if (addressRes) {
        const list = addressRes.data
        address = chosenId != null ? list.find(a => String(a.id) === String(chosenId)) || null
          : list.find(a => a.isDefault) || list[0] || null
      }
      const addressHint = address ? `配送至：${formatAddress(address)}`
        : chosenId != null ? '先前选择的地址已不可用，请重新选择' : '点击设置配送地址'

      // 后端下发本站有效价；商品小计不包含独立押金、配送费和楼层费。
      const products = ((productState !== 'error' && stationId && productsRes && productsRes.data) ? productsRes.data : []).map(p => ({
        ...p,
        price: p.effectivePrice != null ? p.effectivePrice : p.price,
        deposit: p.effectiveDeposit != null ? p.effectiveDeposit : p.deposit
      }))
      // 头部卡美术图 = 本站主力商品（第一个）档案照；没有图则占位
      const heroImage = products.length > 0 && products[0].imageUrl ? products[0].imageUrl : ''

      // 购物车（按水站独立保留）
      const cart = { ...app.getCart(stationId) }
      products.forEach(p => { if (cart[p.id] === undefined) cart[p.id] = 0 })
      if (this._pendingProductId) {
        const target = products.find(p => p.id === this._pendingProductId)
        if (target) {
          app.addToCart(stationId, this._pendingProductId, 1)
          cart[this._pendingProductId] = app.getCart(stationId)[this._pendingProductId]
        }
        this._pendingProductId = null
      }

      // 桶权益按商品；独立模式的可用容量另取服务端本次报价，不由权益总数推断。
      const barrelRights = this.parseBarrelRights(rightsRes)

      this.setData({ address, addressHint, products, cart, heroImage, barrelRights, posterProductImageFailed: false,
        catalogState: productState, purchaseHint })
      this.refreshDerived(barrelRights)

      // 桶账一行：口径全部来自后端 summary（权益/占用/配送中/水站暂存/欠桶/可退押金）
      const summary = summaryRes && summaryRes.data
      this.renderBarrelLine(summary, !!summary && typeof summary === 'object')

      // 进行中订单 → 当前订单条（首页最多展示 1 条）
      const orders = (ordersRes && ordersRes.data) ? ordersRes.data : []
      const actives = orders.filter(o => o.status === 1 || o.status === 2)
      if (ordersRes) await this.renderActiveShip(actives[0], actives.length, current)
      else this.setData({ activeShip: null, activeOrderCount: 0, againOrder: null })
      if (!current()) return

      // 最近一笔已送达/已完成订单 → 再来一单
      const lastDone = orders.find(o => o.status === 3 || o.status === 4)
      if (ordersRes) await this.renderAgainOrder(lastDone, products, current)
      if (!current()) return

      this.setData({ state: 'ready' })
    } catch (e) {
      if (!current()) return
      // [2026-09-26 Wave1 轨道 B] 白屏守卫：这里抛异常时 state 永远不会落到 'ready'
      // （上面那行是唯一赋值点），而结算栏与整块内容都挂在 state === 'ready' 上 ——
      // 页面只剩一张 hero 卡，看起来跟"这个站什么都没有"一模一样（AGENTS §8.22）。
      // 复用现有 loadError 机制给出可读失败态，不重构 loadData。
      console.error('[home] 首页数据加载失败:', e)
      this._noteLoadError('订水信息暂未完整加载，请重新加载。', 'overview')
      this.setData({ state: 'ready', products: [], productsView: [], catalogState: 'error', purchaseHint: '订水信息尚未核实，请重新加载。' })
    } finally {
      if (current()) this.setData({ loading: false })
    }
  },

  /** 水桶与押金入口：只展示本站已读取的信息，不由零资产推断是否需要交押金。 */
  renderBarrelLine(s, loaded = true) {
    // 2026-10-07：原来全零会隐藏办理入口；读取失败也不能被空对象伪装成真实零余额。
    if (!loaded || !s || !this.data.currentStationId) {
      this.setData({ barrelVisible: false, barrelLine1: '', barrelLine2: '' })
      return
    }
    const owed = Number(s.owedBuckets) || 0
    const balance = Number(s.depositBalance) || 0
    const parts = []
    if (owed > 0) parts.push(`欠 ${owed} 个空桶，还清前不能退桶`)
    if (balance > 0) parts.push(`押金余额 ¥${fmtMoney(balance)}`)
    this.setData({ barrelVisible: true, barrelLine1: '水桶与押金',
      barrelLine2: parts.join(' · ') || '查看说明与办理入口' })
  },

  /** 当前订单条：进行中订单（待配送1/配送中2），附商品摘要；activeCount = 进行中总张数 */
  async renderActiveShip(order, activeCount, current = () => true) {
    if (!current()) return
    if (!order) {
      this._noteLoadError('', 'activeOrder')
      this.setData({ activeShip: null, activeOrderCount: 0 })
      return
    }
    // ⚠️ 只陈述后端已下发的事实：不写"备货中"（待配送≠已备货）、不写"正在赶来/马上到"
    //（配送中≠承诺到达时间）。两条都曾是本页的过度承诺（docs/design/29 §3）。
    // 2026-10-24：后端状态文案优先；缺失时不凭前端二选一伪造配送进度。
    const title = typeof order.statusText === 'string' && order.statusText.trim() ? order.statusText : '订单状态待核实'
    let sub = ''
    // [2026-09-20] 原来 `.catch(() => null)`：明细拉不到时 sub 是空串 —— 与"这单确实没有商品明细"
    // 无法区分（状态条本身还在，只是没有商品那一行，客户看不出来是没网）。
    const detail = await getOrderDetail(order.id).catch((e) => {
      if (!current()) return null
      console.warn('[home] 进行中订单明细加载失败:', e && (e.message || e.errMsg))
      this._noteLoadError('进行中订单的商品明细暂未加载，可进入订单查看或重试。', 'activeOrder')
      return null
    })
    if (!current()) return
    if (detail && detail.data) this._noteLoadError('', 'activeOrder')
    if (detail && detail.data && detail.data.items && detail.data.items.length > 0) {
      sub = detail.data.items
        .map(it => `${it.productNameSnapshot || '桶装水'} ×${it.quantity || 0}`)
        .join('、')
    }
    this.setData({ activeShip: { id: order.id, title, sub }, activeOrderCount: Number(activeCount) || 1 })
  },

  /** 再来一单：只传原订单标识；确认页重新读取商品、地址与服务端报价，不直接下单。 */
  async renderAgainOrder(order, products, current = () => true) {
    if (!current()) return
    if (!order) {
      this._noteLoadError('', 'againOrder')
      this.setData({ againOrder: null })
      return
    }
    // [2026-09-20] 原来 `.catch(() => null)`：失败与"上次那单没有商品明细"都让整张卡消失，
    // 客户会以为"再来一单"这个功能没了。失败必须说清是没加载出来。
    const detail = await getOrderDetail(order.id).catch((e) => {
      if (!current()) return null
      console.warn('[home] 上次订单明细加载失败:', e && (e.message || e.errMsg))
      this._noteLoadError('上次订单明细暂未加载，暂时无法再订一单，可重试。', 'againOrder')
      return null
    })
    if (!current()) return
    if (detail && detail.data) this._noteLoadError('', 'againOrder')
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
    // [2026-09-27 走查 C02 修] 卡片标题下面那句摘要要说清**「加入清单」会加什么**。
    // 原文在超过两件时写「等N件」—— N 是**明细行数（种数）**，而客户数的是"几瓶几桶"
    //（截图 09/10：两种商品共 3 件，卡片上只说得出一个商品名 + 一个含糊的"件"）。
    // 现在拆成两句：第一句按种数与件数把规模说清，第二句列前两款的名称与数量。
    // 判据：数字全部由 items 现算，不猜、不写死；"件"与"种"分别用各自的单位。
    const kinds = items.length
    const pieces = items.reduce((s, it) => s + (Number(it.quantity) || 0), 0)
    const scaleText = `上次共 ${kinds} 种 / ${pieces} 件`
    const previewText = items.length > 2
      ? `${names.join('、')} 等${kinds}种`
      : names.join('、')
    // 缩略图取第一件商品的本站档案照（商品可能已下架，下架则占位）
    const first = products.find(p => String(p.id) === String(items[0].productId))
    const img = first && first.imageUrl ? first.imageUrl : ''
    this.setData({ againOrder: { id: order.id, img, summary: scaleText, preview: previewText, items } })
  },

  /**
   * 桶权益按商品归集：{ productId: 权益数量 }
   * 数据源 /api/barrels/summary-by-type（后端 BarrelServiceImpl，驼峰键 assetQty）。
   * 独立模式不用这个汇总推算押金或可用容量；历史预估仍由服务端模式确认后启用。
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
   * 新模式：水款与本次新增押金分项展示，合计取服务端逐商品报价；结算时明确确认。
   * 未核实模式：只报商品款预估并明确提示，不把押金混入或假称无需押金。
   * 以下旧计算只在服务端明确 independentRights=false 时启用。
   *  - 桶装水（category === 1）：只为「缺的桶」付押金 → shortage = max(0, 需要 − 该商品已持有权益)。
   *    顾客已拥有的桶权益【不重复收押金】，这是"押金 = 买桶权益"的定义决定的。
   *  - 非桶商品：无权益概念，按数量全额收押金。
   *
   * 旧实现无脑 deposit += qty × p.deposit，导致有桶权益的顾客在首页看到虚高押金，
   * 跳到下单页（走后端 quote）数字又变正确 —— 同一个购物车两个价。已在 2026-09-12 对齐。
   */
  cancelScheduledHomeQuote() {
    if (this._homeQuoteTimer != null) clearTimeout(this._homeQuoteTimer)
    this._homeQuoteTimer = null
  },

  invalidateHomeQuote() {
    this.cancelScheduledHomeQuote()
    this._homeQuoteSeq = (this._homeQuoteSeq || 0) + 1
    this._homeQuote = null
    this._homeQuoteContext = null
    if (!this._homeDestroyed) this.setData({ homeQuoteState: 'idle' })
  },

  homeQuotePayload() {
    const { currentStationId: stationId, address, products, cart } = this.data
    const items = products.filter(p => (parseInt(cart[p.id]) || 0) > 0)
      .map(p => ({ productId: p.id, quantity: parseInt(cart[p.id]) }))
    return { stationId, items, addressId: address && address.id, paymentMethod: 1 }
  },

  isHomeQuoteCurrent(context) {
    return !!context && !this._homeDestroyed && !this._homeHidden
      && context.seq === this._homeQuoteSeq && isCurrentSession(context.session)
      && context.key === JSON.stringify(this.homeQuotePayload())
  },

  scheduleHomeQuote(seq) {
    const payload = this.homeQuotePayload()
    const context = { seq, session: captureSession(), key: JSON.stringify(payload) }
    if (!payload.stationId || !payload.items.length || !context.session.loggedIn
      || !context.session.customerId || !this.isHomeQuoteCurrent(context)) return
    this._homeQuoteTimer = setTimeout(() => {
      this._homeQuoteTimer = null
      if (this.isHomeQuoteCurrent(context)) this.refreshRightsQuote(seq)
    }, 300)
  },

  refreshDerived(rights, refreshQuote = true, debounceQuote = false) {
    if (rights) this.setData({ barrelRights: rights })
    if (refreshQuote) {
      this.invalidateHomeQuote()
    } else if (this._homeQuote && !this.isHomeQuoteCurrent(this._homeQuoteContext)) {
      this._homeQuote = null
      this._homeQuoteContext = null
    }
    const { products, cart } = this.data
    const heldMap = rights || this.data.barrelRights || {}
    const quote = this._homeQuote
    const independent = quote && quote.independentRights === true
    const legacy = quote && quote.independentRights === false
    const hasBarrel = products.some(p => Number(p.category) === 1 && (parseInt(cart[p.id]) || 0) > 0)
    let count = 0, water = 0, deposit = 0
    const productsView = products.map(p => {
      const qty = parseInt(cart[p.id]) || 0
      const unitDeposit = Number(p.deposit) || 0
      let depositNote = ''

      if (qty > 0) {
        count += qty
        water += qty * (Number(p.price) || 0)

        if (Number(p.category) === 1 && independent) {
          const missing = quote.barrelPurchases.find(it => String(it.productId) === String(p.id))
          if (missing) deposit += Number(missing.amount)
          depositNote = missing
            ? `容量不足 ${missing.quantity} 份，本次新增押金 ¥${fmtMoney(missing.amount)}；结算时确认` + (missing.busyRights > 0 ? '，也可等待已有容量释放' : '')
            : '本次无需加收桶押金'
        } else if (Number(p.category) === 1 && !legacy) {
          depositNote = '桶押金办理方式未核实，请到结算页确认'
        } else if (Number(p.category) === 1) {
          // 桶装水：已有权益的桶不再收押金
          const held = Number(heldMap[String(p.id)]) || 0
          const shortage = Math.max(0, qty - held)
          deposit += shortage * unitDeposit
          // [2026-09-26 Wave1 轨道 B] 商品行只留"与本次购买有关的一行押金说明"，
          // 措辞按 docs/design/29 §7 的对照表：不暗示"本次回收就能退押金"，
          // 也不把桶权益口径搬到商品行铺开（明细在桶账页）。
          if (held > 0 && shortage === 0) {
            depositNote = '本次无需加收桶押金'
          } else if (held > 0) {
            depositNote = `本次另需 ${shortage} 个桶押金 ¥${fmtMoney(shortage * unitDeposit)}`
          } else if (unitDeposit > 0) {
            depositNote = `桶押金 ¥${fmtMoney(unitDeposit)}/个`
          }
        } else if (legacy) {
          deposit += qty * unitDeposit
          if (unitDeposit > 0) depositNote = `押金 ¥${fmtMoney(unitDeposit)}/个`
        }
      }

      return {
        ...p,
        qty,
        quantityUnit: itemUnit(p),
        depositNote,
        priceText: fmtMoney(p.price),
        depositText: fmtMoney(p.deposit)
      }
    })

    const waterText = fmtMoney(water)
    const depositText = fmtMoney(deposit)
    // 2026-10-23：部分已选商品不在当前目录时只隐藏曾被误认完整恢复；不删除原草稿。
    const productIds = new Set(products.map(p => String(p.id)))
    const unavailableCartCount = ['ready', 'empty'].includes(this.data.catalogState)
      ? Object.keys(cart).filter(id => Number(cart[id]) > 0 && !productIds.has(String(id))).length : 0
    this.setData({
      productsView,
      unavailableCartCount,
      total: {
        count,
        waterText,
        depositText,
        grandText: fmtMoney(water + deposit),
        // wxml 不能做三元拼接，文案在 JS 里算好
        detailText: count === 0
          ? '选中数量后自动合计'
          : (independent && hasBarrel ? (deposit > 0
              ? `（水款 ¥${waterText} + 本次新增押金 ¥${depositText}，结算时确认）` : `（水款 ¥${waterText}，无需加收桶押金）`)
            : (!legacy && hasBarrel ? `（水款 ¥${waterText}，桶押金未核实）`
              : (deposit > 0 ? `（水款 ¥${waterText} + 押金 ¥${depositText}）` : `（水款 ¥${waterText}）`)))
      }
    })
    if (refreshQuote) {
      if (debounceQuote) return this.scheduleHomeQuote(this._homeQuoteSeq)
      return this.refreshRightsQuote(this._homeQuoteSeq)
    }
  },

  /** 首页展示商品与新增押金预估；实际支付方式和配送费用在结算页核实。 */
  async refreshRightsQuote(seq) {
    this.cancelScheduledHomeQuote()
    const payload = this.homeQuotePayload()
    const { stationId, items } = payload
    if (!stationId || !items.length) return
    const session = captureSession()
    if (!session.loggedIn || !session.customerId) return
    const context = { seq, session, key: JSON.stringify(payload) }
    const current = () => this.isHomeQuoteCurrent(context)
    if (!current()) return
    this.setData({ homeQuoteState: 'loading' })
    try {
      const res = await getQuote(payload)
      if (!current()) return
      const quote = res && res.data
      if (!res || (res.code !== undefined && ![0, 200].includes(res.code)) || !quote
        || typeof quote.independentRights !== 'boolean'
        || (quote.independentRights && (!Array.isArray(quote.barrelPurchases)
          || quote.barrelPurchases.some(it => !it || !Number.isInteger(it.productId)
            || !Number.isInteger(it.quantity) || it.quantity < 1
            || !Number.isFinite(Number(it.unitPrice)) || Number(it.unitPrice) <= 0
            || !Number.isFinite(Number(it.amount)) || Number(it.amount) <= 0
            || Math.abs(Number(it.amount)-Number(it.unitPrice)*it.quantity) > 0.009
            || !items.some(l => l.productId === it.productId && l.quantity >= it.quantity))
          || new Set(quote.barrelPurchases.map(it => it.productId)).size !== quote.barrelPurchases.length))) {
        throw new Error('桶押金办理方式暂未核实')
      }
      this.setData({ homeQuoteState: 'ready' })
      this._homeQuote = quote
      this._homeQuoteContext = context
      this.refreshDerived(undefined, false)
    } catch (error) {
      // 保留水款预估和“未核实”，不把查询失败解释成无需押金或全额收押金。
      if (current()) {
        this.setData({ homeQuoteState: 'error' })
        console.warn('[home] 桶押金报价未核实:', error && error.message)
      }
    }
  },

  onAddressTap() {
    wx.navigateTo({ url: '/pages/address/list?from=home' })
  },

  onAgainTap() {
    const { againOrder, currentStationId } = this.data
    if (!againOrder || !againOrder.id || this._againNavigating) return
    this._againNavigating = true
    this.invalidateHomeQuote()
    wx.navigateTo({
      url: `/pages/order/create?reorderId=${encodeURIComponent(againOrder.id)}&stationId=${currentStationId || ''}`,
      fail: () => wx.showToast({ title: '打开确认页失败，请重试', icon: 'none' }),
      complete: () => { this._againNavigating = false }
    })
  },

  onViewActiveOrder() {
    const { activeShip } = this.data
    if (activeShip) {
      wx.navigateTo({ url: `/pages/order/detail?id=${activeShip.id}` })
    }
  },

  /** 多张进行中订单时的「查看全部」：订单列表是 tabBar 页，只能用 switchTab
      （navigateTo 跳 tab 页必然 fail，点了静默无反应） */
  onGoActiveOrders() {
    wx.switchTab({ url: '/pages/order/list' })
  },

  onGoBarrel() {
    wx.navigateTo({ url: '/pages/barrel/index' })
  },

  // 顶部水光只作装饰；失败回到原底色，不影响选水和结算。
  onHomeWaterBackdropError() {
    if (this._homeDestroyed) return
    this.setData({ homeWaterBackdropFailed: true })
  },

  // 海报仅为装饰资源；失败保留原生标题与真实商品信息，不阻断订水。
  onPosterArtError() {
    this.setData({ posterArtFailed: true })
  },

  onHomeProductImageError(e) {
    const { id, url, kind } = e.currentTarget.dataset
    if (this._homeHidden || this._homeDestroyed) return
    if (kind === 'again') {
      const again = this.data.againOrder
      if (again && String(again.id) === String(id) && again.img === url) this.setData({ againOrder: { ...again, img: '' } })
      return
    }
    // 2026-10-23：非空坏URL曾始终保留image而不进占位；只更新匹配原URL的显示字段。
    if (!this.data.products.some(p => String(p.id) === String(id) && p.imageUrl === url)) return
    this.setData({ products: this.data.products.map(p => String(p.id) === String(id) && p.imageUrl === url ? { ...p, imageUrl: '' } : p) })
    this.refreshDerived(undefined, false)
  },

  onPosterProductImageError() {
    this.setData({ posterProductImageFailed: true })
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
    app.setCartQty(stationId, id, qty)
    this.setData({ cart })
    this.refreshDerived(undefined, true, true)
  },

  onSubmit() {
    const { loading, catalogState, currentStationId } = this.data
    // 旧界面的点击可能在刷新后才到达；商品未核实时不带旧清单进入确认页。
    if (loading || catalogState === 'loading' || catalogState === 'error' || catalogState === 'empty' || !currentStationId) {
      const title = !currentStationId ? '请先选择水站' : loading || catalogState === 'loading'
        ? '订水信息加载中，请稍候' : catalogState === 'empty' ? '该水站暂无可订商品' : '商品信息暂未核实，请重新加载'
      wx.showToast({ title, icon: 'none' })
      return
    }
    const { address, total } = this.data
    if (total.count === 0) {
      wx.showToast({ title: '请选择商品', icon: 'none' })
      return
    }
    if (!address) {
      wx.showModal({
        title: this._homeChosenAddressId != null ? '请重新选择配送地址' : '还没有配送地址',
        content: this._homeChosenAddressId != null ? '先前选择的地址已不可用，请重新选择后再确认订单' : '先设置一个收货地址，水才能送到家',
        confirmText: '去设置',
        success: (r) => {
          if (r.confirm) wx.navigateTo({ url: '/pages/address/list?from=home' })
        }
      })
      return
    }
    this.invalidateHomeQuote()
    this.refreshDerived(undefined, false)
    const items = this.collectCartItems()
    const itemsParam = items.map(it => ({ productId: it.productId, quantity: it.quantity }))
    // [2026-09-27 走查 U01 定位] **url 太长会让 wx.navigateTo 直接失败**（小程序对页面路径+
    // 参数有长度上限，超限时既不跳转也没有任何提示）。本页把整份商品清单 + 地址文本塞进 query，
    // 实测口径：3 件商品约 60 字符，而 `addressDetail` 是**未截断**的用户地址（中文经
    // encodeURIComponent 后每字 9 字符），长地址 + 大车就能把整串顶到上限附近。
    // 现象与走查 U01 记录的"点击后暂不跳转 / 无业务错误"完全一致，而本地没有任何门禁能发现。
    // 两条处置（不动 create.js —— 它被另一条工作流占着）：
    //   ① 加 `fail` 回调：真失败时**出声**（toast + console），不再静默无反应；
    //   ② 打一行 url 长度与商品行数，下一次走查能把"点了没反应"直接对上号（只记长度，不记地址内容）。
    const url = `/pages/order/create?items=${encodeURIComponent(JSON.stringify(itemsParam))}&addressId=${address.id}&addressDetail=${encodeURIComponent(address.detail || '')}&source=3&stationId=${this.data.currentStationId || ''}`
    console.log('[home] 去结算：url 长度=' + url.length + '，商品行=' + itemsParam.length)
    wx.navigateTo({
      url,
      fail: (err) => {
        console.error('[home] 去结算跳转失败:', err && err.errMsg, 'url 长度=' + url.length)
        wx.showToast({ title: '打开结算页失败，请重试', icon: 'none' })
      }
    })
  }
})
