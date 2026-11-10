const { employeeCustomerNotePreview } = require('../../../utils/customer-note')
// 站长订单管理
const { getOrders, getCrossStationOrders } = require('../../../api/station-mgmt')
const historyCustomer = require('../../../utils/station-history-customer')
const navigation = require('../../../utils/navigation')

const MAX_ORDER_PAGE_SIZE = 500 // OrderController.list 的单次上限

// 状态 → 样式类：**只**决定配色。状态文案一律用后端下发的 `statusText`
// （`Orders.getStatusText()` → `constant/OrderStatus.textOf`）。
//
// [2026-09-18] 这里原本是一张 `STATUS_MAP = {1:{text:'待配送',cls:'pending'}, …}`，
// 页面上只用了 `.cls`，`text` 是死字段 —— 但它正是一张**现成的 status → 中文 映射表**，
// 下一个 agent 顺手拿它渲染就会违反本仓明令（前端禁止自带映射表，曾导致新客下单 100% 失败）。
// 死代码也是一份"看起来权威"的口径，所以直接删掉，只留类名。
const STATUS_CLASS_MAP = {
  1: 'pending',
  2: 'delivering',
  3: 'delivered',
  4: 'completed',
  5: 'cancelled'
}

Page({
  ...historyCustomer.methods,
  data: {
    ...historyCustomer.data,
    loading: true,
    list: [],
    startDate: '', endDate: '', listError: '', page: 0, pageSize: 20, hasMore: false, loadingMore: false,
    // tab id 即订单状态码（0 = 不筛状态），与后端 OrderStatus 编号一致。
    //
    // 「已完成」页签（2026-09-18 接线）刻意走**通用列表** `GET /api/orders?status=4`，
    // 而不是 `GET /api/delivery/orders/station-completed`：两者读的是同一张 orders 表、
    // 同一组条件（station_id + status），后端 station-completed 的 SQL 就是
    // OrderMapper.listByStationIdAndStatus（StationDeliveryConsoleController#getStationCompletedOrders）。同一个界面接两条
    // 同源读路径 = 口径分叉的土壤（改一侧忘另一侧，两边显示不一致且没人发现），
    // 所以这里复用已在用的通用列表；站别由后端按登录态强制覆盖（OrderController:90-95），
    // 前端传的 stationId 只用于后端比对、传错会被覆盖，不构成越权读取他站数据的通道。
    tabs: [
      { id: 1, name: '待配送' },
      { id: 2, name: '配送中' },
      { id: 3, name: '已送达' },
      { id: 4, name: '已完成' },
      { id: 5, name: '已取消' },
      { id: 0, name: '全部' }
    ],
    // 先看当前待安排订单；全量历史仍在「全部」，不把已完成/取消挤在首屏。
    currentTab: 1,
    // 跨站履约单（本站是履约站、归属站是别站）：默认**归并成一行**，点开才看逐单明细。
    // 后端一次给全（总数/金额合计/按状态分类/明细），明细里没有客户画像（后端已掩码）。
    crossStation: null,
    crossState: 'idle',
    crossExpanded: false
  },

  onLoad(options) {
    if (options && options.scope === 'all') this.setData({ currentTab: 0 })
  },

  onShow() {
    this._crossHidden = false
    const app = getApp()
    if (!app.canAccessStationBusiness()) {
      app.routeByRole(true)
      return
    }
    navigation.show(this)
    return this.loadData(true, true)
  },

  onHide() {
    this._crossHidden = true
    this._crossContext = null
    this._ordersSeq = (this._ordersSeq || 0) + 1
    navigation.hide(this)
  },
  onUnload() {
    this.onHide()
    navigation.dispose(this)
    historyCustomer.methods.onUnload.call(this)
  },

  onToggleFilters() { this.setData({ filtersExpanded: !this.data.filtersExpanded }) },

  // 2026-11-10：切到调度页时带上正在查的订单，避免在待分配清单里再找一遍。
  // 这里只传定位意图，可分配性仍以调度页新读的清单和提交校验为准。
  onOpenDispatchWork(e) { return this.openDispatchQueue(e, 'pending') },

  openDispatchQueue(e, queue) {
    const app = getApp(), g = app.globalData, user = g.userInfo || {}
    if (this._crossHidden || !g.isLogin || !user.stationId || !app.isStationManager() || !app.canAccessStationBusiness()) return
    const id = String(e?.currentTarget?.dataset?.id || '')
    const row = id && this.data.list.find(order => String(order.id) === id)
    if (id && (!/^[1-9]\d*$/.test(id) || !this._loadedOrderQuery
        || !this.orderQueryMatches(this._loadedOrderQuery)
        || !(row && (queue === 'dispatch' ? row.otherStationDelivery : row.canLocateDispatch)))) return
    const intent = { id, queue,
      generation: app._loginGeneration || 0, staffId: user.staffId,
      stationId: user.stationId, expiresAt: Date.now() + 12000 }
    g.orderDispatchIntent = intent
    wx.switchTab({ url: '/pages/coordination/index', fail: () => {
      if (g.orderDispatchIntent !== intent) return
      delete g.orderDispatchIntent
      if (!this._crossHidden) wx.showToast({ title: intent.queue === 'dispatch' ? '外派进度暂时打不开，请重试' : '待分配暂时打不开，请重试', icon: 'none' })
    } })
  },
  onOpenCollectionWork() { wx.navigateTo({ url: '/pages/station-mgmt/payments/index' }) },

  onPullDownRefresh() {
    this.loadData().then(() => wx.stopPullDownRefresh())
  },

  onTabChange(e) {
    this.setData({ currentTab: Number(e.currentTarget.dataset.id) })
    return this.loadData()
  },

  onHistoryDateChange(e) {
    const field = e.currentTarget.dataset.field
    if (!['startDate', 'endDate'].includes(field)) return
    this.setData({ [field]: e.detail.value })
    return this.loadData()
  },
  onClearHistoryDates() { this.setData({ startDate: '', endDate: '' }); return this.loadData() },
  onReachBottom() { return this.loadData(false) },
  onLoadMore() { return this.loadData(false) },

  orderQuerySnapshot() {
    const app = getApp(), owner = app.globalData.userInfo || null
    const query = { app, owner, generation: app._loginGeneration, stationId: owner?.stationId, currentTab: this.data.currentTab,
      customerId: this.data.customerId, startDate: this.data.startDate, endDate: this.data.endDate,
      pageSize: this.data.pageSize }
    query.key = JSON.stringify([app.globalData.isLogin, owner?.staffId, owner?.role, query.stationId, query.currentTab,
      query.customerId, query.startDate, query.endDate, query.pageSize])
    return query
  },
  orderQueryMatches(query) {
    const current = this.orderQuerySnapshot()
    return query.app === current.app && query.owner === current.owner
      && query.generation === current.generation && query.key === current.key
  },

  async loadData(reset = true, keepRange = false) {
    if (!reset && (this.data.loading || this.data.loadingMore || !this.data.hasMore)) return
    // 筛选无效也必须淘汰旧请求：序号在日期校验之前前进。
    const seq = this._ordersSeq = (this._ordersSeq || 0) + 1
    const query = this.orderQuerySnapshot()
    if (query.startDate && query.endDate && query.startDate > query.endDate) {
      this.setData({ list: [], page: 0, hasMore: false, loading: false, loadingMore: false,
        crossStation: null, crossState: 'idle', listError: '开始日期不能晚于结束日期' }); return
    }
    // 2026-11-10：从详情返回时清空到第一页会丢掉已展开的分页。仅同会话同筛选保留已读范围，仍重读订单状态。
    const preserve = reset && keepRange && this.data.page > 0 && this.data.list.length > 0
      && this._loadedOrderQuery && this.orderQueryMatches(this._loadedOrderQuery)
    const page = reset ? (preserve ? this.data.page : 1) : this.data.page + 1
    const rowLimit = (reset ? page : 1) * query.pageSize
    const requestSize = Math.min(rowLimit, MAX_ORDER_PAGE_SIZE)
    const stationId = query.stationId
    // 2026-10-24：补齐送达与取消筛选；页签 id 沿用状态码，角标仍只计当前已加载记录。
    // 旧实现把 tab=2(配送中) 错映射成 status=3(已送达)，导致「配送中」标签筛出的是已送达订单。
    const statusMap = { 0: null, 1: 1, 2: 2, 3: 3, 4: 4, 5: 5 }
    const status = statusMap[query.currentTab]

    this.setData({ loading: !!reset && !preserve, loadingMore: !reset || !!preserve, listError: '' })
    if (reset && !preserve) this.setData({ list: [], hasMore: false, page: 0 })
    // 2026-11-10：等跨站请求才结束加载会挡住已经读好的本站订单。两块各自结束加载。
    if (reset) {
      this.setData({ crossStation: null, crossState: 'loading' })
      this.loadCrossStation(query)
    }
    try {
      const rows = []
      for (let requestPage = reset ? 1 : page; rows.length < rowLimit; requestPage++) {
        const res = await getOrders({ stationId, status, customerId: query.customerId || undefined,
          createTimeStart: query.startDate || undefined, createTimeEnd: query.endDate || undefined,
          page: requestPage, pageSize: requestSize })
        if (seq !== this._ordersSeq || !this.orderQueryMatches(query)) return
        if (!Array.isArray(res.data)) throw new Error('订单列表尚未核对')
        rows.push(...res.data.slice(0, rowLimit - rows.length))
        if (res.data.length < requestSize) break
      }
      // 文案一律取后端下发的 statusText；上面的 STATUS_CLASS_MAP 只提供**样式类**，
      // 不是文案映射表（AGENTS.md §6：前端禁止自带状态码→中文映射）。
      const list = rows.map(o => {
        const hasDeliveryStation = o.deliveryStationId != null
        const otherStationDelivery = stationId != null && hasDeliveryStation && String(o.deliveryStationId) !== String(stationId)
        const localPending = o.status === 1 && hasDeliveryStation && String(o.deliveryStationId) === String(stationId)
        // 只看配送员是否已分配，不用订单状态推断为「待接单」或「外派中」：
        // 跨站/转单的实际待办须去工作台核对，不能把它误报为本站可分配。
        return { ...o,
          customerNotePreview: employeeCustomerNotePreview(o),
          statusText: o.statusText || '未知',
          assignmentText: localPending ? (o.deliveryStaffId ? '已安排配送员' : '尚未安排配送员') : '',
          canLocateDispatch: localPending && !o.deliveryStaffId,
          otherStationDelivery,
          statusCls: STATUS_CLASS_MAP[o.status] || 'default'
        }
      })
      const prior = reset ? [] : this.data.list, ids = new Set(prior.map(it => it.id))
      const added = list.filter(it => !ids.has(it.id) && ids.add(it.id))
      this._loadedOrderQuery = query
      this.setData({ list: prior.concat(added), page,
        hasMore: rows.length === rowLimit })
      if (!reset && list.length && !added.length) this.setData({ hasMore: false, listError: '列表未更新，请重新查询' })
    } catch (err) {
      if (seq === this._ordersSeq && this.orderQueryMatches(query)) this.setData({ listError: err.message || '订单加载失败，请重试' })
    } finally {
      if (seq === this._ordersSeq) {
        if (!this.orderQueryMatches(query)) this.setData({ list: [], page: 0, hasMore: false,
          crossStation: null, crossState: 'idle', listError: '筛选条件已变化，请重新查询' })
        this.setData({ loading: false, loadingMore: false })
      }
    }
  },

  async loadCrossStation(query) {
    const version = this._crossVersion = (this._crossVersion || 0) + 1
    const app = getApp(), context = { app, loginGeneration: app._loginGeneration, query }
    this._crossContext = null
    const current = () => version === this._crossVersion && this.crossStationContextMatches(context)
    try {
      const cs = await getCrossStationOrders()
      if (!current()) return
      const d = cs && cs.data
      if (!d || d.count === null || d.count === undefined || d.count === '' || !Number.isInteger(Number(d.count)) || Number(d.count) < 0 || (d.orders != null && !Array.isArray(d.orders)) || (Number(d.count) > 0 && !Array.isArray(d.orders))) throw new Error('跨站记录不完整')
      const orders = (d.orders || []).map(o => ({ ...o, statusText: o.statusText || '未知',
        statusCls: STATUS_CLASS_MAP[o.status] || 'default' }))
      this._crossContext = context
      this.setData({ crossStation: { ...d, count: Number(d.count), orders }, crossState: 'ready' })
    } catch (err) {
      if (current()) this.setData({ crossStation: null, crossState: 'error' })
    }
  },

  onRetryCrossStation() {
    const query = this.orderQuerySnapshot()
    this.setData({ crossState: 'loading' })
    return this.loadCrossStation(query)
  },

  /** 展开/收起跨站履约单明细（归并行点击）。 */
  onToggleCrossStation() {
    this.setData({ crossExpanded: !this.data.crossExpanded })
  },

  /** 跨站行只能沿用当前会话成功读取的列表，换号/换站后的旧行不能继续跳转。 */
  crossStationContextMatches(context) {
    const app = getApp()
    return !!context && !this._crossHidden && context.app === app &&
      context.loginGeneration === app._loginGeneration && this.orderQueryMatches(context.query)
  },

  onCrossStationOrderTap(e) {
    const app = getApp(), owner = app.globalData.userInfo
    if (!app.globalData.isLogin || !owner || !app.isStationManager() || !app.canAccessStationBusiness() ||
        !this.crossStationContextMatches(this._crossContext) ||
        this.data.crossState !== 'ready' || !this.data.crossExpanded || !this.data.crossStation?.count) return
    const id = String(e.currentTarget.dataset.id || '')
    if (!/^[1-9]\d*$/.test(id) || !this.data.crossStation.orders.some(order => String(order.id) === id)) return
    // 2026-11-04：跨站行原先只冒泡收起汇总，无法进入详情；复用员工详情，仅传订单 id。
    // 归属站不等于履约站，不能把行内 stationId 当登录站或另开客户画像入口；详情仍由服务端验归属。
    wx.navigateTo({ url: `/pages/order/detail?id=${id}` })
  },

  onOrderTap(e) {
    const { id } = e.currentTarget.dataset
    // 2026-11-10：原站可查外派单，但配送详情按履约站判权，原站从外派记录查看进度。
    if (this.data.list.some(row => String(row.id) === String(id) && row.otherStationDelivery)) {
      return this.openDispatchQueue(e, 'dispatch')
    }
    wx.navigateTo({ url: `/pages/order/detail?id=${id}` })
  }
})
