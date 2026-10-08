// 站长订单管理
const { getOrders, getCrossStationOrders } = require('../../../api/station-mgmt')
const historyCustomer = require('../../../utils/station-history-customer')

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
      { id: 0, name: '全部' },
      { id: 1, name: '待配送' },
      { id: 2, name: '配送中' },
      { id: 4, name: '已完成' }
    ],
    currentTab: 0,
    // 跨站履约单（本站是履约站、归属站是别站）：默认**归并成一行**，点开才看逐单明细。
    // 后端一次给全（总数/金额合计/按状态分类/明细），明细里没有客户画像（后端已掩码）。
    crossStation: null,
    crossExpanded: false
  },

  onShow() {
    const app = getApp()
    if (!app.canAccessStationBusiness()) {
      app.routeByRole(true)
      return
    }
    this.loadData()
  },

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
    const owner = getApp().globalData.userInfo || null
    const query = { owner, stationId: owner?.stationId, currentTab: this.data.currentTab,
      customerId: this.data.customerId, startDate: this.data.startDate, endDate: this.data.endDate,
      pageSize: this.data.pageSize }
    query.key = JSON.stringify([owner?.staffId, owner?.role, query.stationId, query.currentTab,
      query.customerId, query.startDate, query.endDate, query.pageSize])
    return query
  },
  orderQueryMatches(query) {
    const current = this.orderQuerySnapshot()
    return query.owner === current.owner && query.key === current.key
  },

  async loadData(reset = true) {
    if (!reset && (this.data.loading || this.data.loadingMore || !this.data.hasMore)) return
    // 筛选无效也必须淘汰旧请求：序号在日期校验之前前进。
    const seq = this._ordersSeq = (this._ordersSeq || 0) + 1
    const query = this.orderQuerySnapshot()
    if (query.startDate && query.endDate && query.startDate > query.endDate) {
      this.setData({ list: [], page: 0, hasMore: false, loading: false, loadingMore: false,
        crossStation: null, listError: '开始日期不能晚于结束日期' }); return
    }
    const page = reset ? 1 : this.data.page + 1
    const stationId = query.stationId
    // tab → 订单状态码：0=全部(null) 1=待配送 2=配送中 4=已完成。
    // 旧实现把 tab=2(配送中) 错映射成 status=3(已送达)，导致「配送中」标签筛出的是已送达订单。
    const statusMap = { 0: null, 1: 1, 2: 2, 4: 4 }
    const status = statusMap[query.currentTab]

    this.setData({ loading: !!reset, loadingMore: !reset, listError: '' })
    if (reset) this.setData({ list: [], hasMore: false, page: 0, crossStation: null })
    try {
      const res = await getOrders({ stationId, status, customerId: query.customerId || undefined,
        createTimeStart: query.startDate || undefined, createTimeEnd: query.endDate || undefined,
        page, pageSize: query.pageSize })
      if (seq !== this._ordersSeq || !this.orderQueryMatches(query)) return
      if (!Array.isArray(res.data)) throw new Error('订单列表尚未核对')
      // 文案一律取后端下发的 statusText；上面的 STATUS_CLASS_MAP 只提供**样式类**，
      // 不是文案映射表（AGENTS.md §6：前端禁止自带状态码→中文映射）。
      const list = (res.data || []).map(o => ({
        ...o,
        statusText: o.statusText || '未知',
        statusCls: STATUS_CLASS_MAP[o.status] || 'default'
      }))
      const prior = reset ? [] : this.data.list, ids = new Set(prior.map(it => it.id))
      const added = list.filter(it => !ids.has(it.id) && ids.add(it.id))
      this.setData({ list: prior.concat(added), page,
        hasMore: list.length === query.pageSize })
      if (!reset && list.length && !added.length) this.setData({ hasMore: false, listError: '列表未更新，请重新查询' })
      // 跨站履约单：与本站单是两套口径（本站单按归属站、跨站单按履约站），失败不影响主列表
      if (reset) try {
        const cs = await getCrossStationOrders()
        if (seq !== this._ordersSeq || !this.orderQueryMatches(query)) return
        const d = cs.data || {}
        const orders = (d.orders || []).map(o => ({
          ...o,
          statusText: o.statusText || '未知',
          statusCls: STATUS_CLASS_MAP[o.status] || 'default'
        }))
        this.setData({ crossStation: { ...d, orders } })
      } catch (err) {
        // 归并行拿不到就不显示：它只是附加信息，不该让整个订单页报错
        console.warn('[cross-station] 取跨站履约单失败:', err && err.message)
        if (seq === this._ordersSeq && this.orderQueryMatches(query)) this.setData({ crossStation: null })
      }
    } catch (err) {
      if (seq === this._ordersSeq && this.orderQueryMatches(query)) this.setData({ listError: err.message || '订单加载失败，请重试' })
    } finally {
      if (seq === this._ordersSeq) {
        if (!this.orderQueryMatches(query)) this.setData({ list: [], page: 0, hasMore: false,
          crossStation: null, listError: '筛选条件已变化，请重新查询' })
        this.setData({ loading: false, loadingMore: false })
      }
    }
  },

  /** 展开/收起跨站履约单明细（归并行点击）。 */
  onToggleCrossStation() {
    this.setData({ crossExpanded: !this.data.crossExpanded })
  },

  onOrderTap(e) {
    const { id } = e.currentTarget.dataset
    wx.navigateTo({ url: `/pages/order/detail?id=${id}` })
  }
})
