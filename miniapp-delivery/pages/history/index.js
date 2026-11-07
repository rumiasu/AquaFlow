const { employeeCustomerNotePreview } = require('../../utils/customer-note')
// 配送历史页
const { getDeliveryHistory } = require('../../api/delivery')
const { STORAGE_KEYS } = require('../../utils/storage-keys')
const { withItemUnits, orderSummary } = require('../../utils/order-item-view')
const textOf = value => typeof value === 'string' ? value.trim() : ''
function productView(order) {
  // 2026-10-07：第一商品乘全单数量会错报混合单；逐行取快照，旧数据仅用中性摘要。
  const productItems = withItemUnits((Array.isArray(order.items) ? order.items : []).filter(row => row && typeof row === 'object' && !Array.isArray(row))).map(row => ({
    ...row, name: textOf(row.productNameSnapshot) || textOf(row.productName) || '商品',
    spec: textOf(row.specSnapshot) || textOf(row.spec), imageUrl: textOf(row.imageUrl),
    quantityText: (typeof row.quantity === 'number' || (typeof row.quantity === 'string' && row.quantity.trim()))
      && Number.isInteger(Number(row.quantity)) && Number(row.quantity) >= 0
      ? `× ${Number(row.quantity)} ${row.quantityUnit}` : '数量待核对'
  }))
  return { productItems, productFallback: orderSummary(order), customerNotePreview: employeeCustomerNotePreview(order) }
}
function validId(value) { return /^[1-9][0-9]*$/.test(String(value)) }
function validTime(value) { return value == null || value === '' || (typeof value === 'string' && Number.isFinite(new Date(value).getTime())) }

Page({
  data: { emptyImageFailed: false, orders: [], loading: true, loaded: false, error: '', loadingMore: false, moreError: '', hasMore: false },

  // A missing empty-state illustration must not change list loading or access.
  onEmptyImageError() { this.setData({ emptyImageFailed: true }) },

  onLoad() { this._unloaded = false },
  // 2026-10-02：首屏只在 onShow 读取，重试也走同一访问守卫。
  onShow() { return this.loadData() },
  onRetry() { return this.loadData() },
  _context() {
    const app = getApp(), u = app.globalData.userInfo || wx.getStorageSync(STORAGE_KEYS.USER_INFO) || {}
    return JSON.stringify([app._loginGeneration || 0, u.staffId || u.id || '', u.role || '', u.stationId || '', u.bindStatus || ''])
  },
  _current(version, context) {
    return !this._unloaded && version === this._requestVersion && getApp().canAccessStationBusiness() && context === this._context()
  },

  async loadData() {
    if (this._unloaded) return
    const app = getApp()
    if (!app.canAccessStationBusiness()) {
      this._requestVersion = (this._requestVersion || 0) + 1
      this._dataContext = null
      this._cursor = null
      this.setData({ orders: [], loaded: false, loading: false, loadingMore: false, hasMore: false, moreError: '', error: '请先完成身份及水站绑定，再重新进入。' })
      app.routeByRole(true)
      return
    }
    const context = this._context(), version = this._requestVersion = (this._requestVersion || 0) + 1
    // 2026-10-02：旧筛选/身份响应曾覆盖新页；先清跨上下文数据，再守 success/catch/finally。
    if (this._dataContext !== context) { this._cursor = null; this.setData({ orders: [], loaded: false, hasMore: false }) }
    this._dataContext = context
    this.setData({ loading: true, loadingMore: false, error: '', moreError: '' })
    try {
      const res = await getDeliveryHistory({ pageSize: 20 })
      if (!this._current(version, context)) return
      const page = this._readPage(res)
      this._cursor = page.cursor
      const orders = page.rows.map(order => ({ ...order, ...productView(order), createTime: this.formatTime(order.createTime) }))
      this.setData({ orders, loaded: true, hasMore: !!page.cursor })
    } catch (err) {
      if (!this._current(version, context)) return
      const reason = err && typeof err.message === 'string' && err.message.trim() ? err.message : '暂时无法加载，请重试。'
      this.setData({ error: (this.data.loaded ? '刷新失败，显示上次成功加载的结果。' : '配送历史加载失败：') + reason })
    } finally {
      // 2026-10-02：仅丢旧响应会卡住最后一份 flight；无新请求时明确收尾并提示重读。
      // 已有新版本或卸载时仍不能写页，否则旧 finally 会清掉新请求的加载状态。
      if (!this._unloaded && version === this._requestVersion) {
        if (this._current(version, context)) this.setData({ loading: false })
        else {
          this._dataContext = null
          this._cursor = null
          this.setData({ orders: [], loaded: false, loading: false, loadingMore: false, hasMore: false, moreError: '', error: '身份或水站已变化，请重试或重新进入。' })
        }
      }
    }
  },
  _readPage(res) {
    if (!res || (res.code !== 0 && res.code !== 200)) throw new Error('收到的数据不完整，请重试。')
    // 旧服务忽略分页参数并返回完整数组；保留可用性，不把数组误当成第一页。
    const data = res.data, legacy = Array.isArray(data)
    const rows = legacy ? data : data && data.orders
    if (!Array.isArray(rows) || !rows.every(r => r && typeof r === 'object' && !Array.isArray(r) && validId(r.id) && validTime(r.createTime))) throw new Error('收到的数据不完整，请重试。')
    if (!legacy && (typeof data.hasMore !== 'boolean' || (data.hasMore && (!rows.length || !validId(data.nextBeforeId) || !data.nextBeforeTime || !validTime(data.nextBeforeTime))))) throw new Error('收到的数据不完整，请重试。')
    return { rows, cursor: !legacy && data.hasMore ? { beforeTime: data.nextBeforeTime, beforeId: data.nextBeforeId } : null }
  },
  async onLoadMore() {
    if (this._unloaded || this.data.loading || this.data.loadingMore || !this.data.hasMore || !this._cursor) return
    const context = this._context(), cursor = this._cursor
    if (!getApp().canAccessStationBusiness() || context !== this._dataContext) return this.loadData()
    const version = this._requestVersion = (this._requestVersion || 0) + 1
    this.setData({ loadingMore: true, moreError: '' })
    try {
      const res = await getDeliveryHistory({ pageSize: 20, ...cursor })
      if (!this._current(version, context)) return
      const page = this._readPage(res)
      if (page.cursor && String(page.cursor.beforeId) === String(cursor.beforeId) && page.cursor.beforeTime === cursor.beforeTime) throw new Error('收到的数据不完整，请重试。')
      // 同一订单可能在翻页期间发生变化；不让重复卡片出现。
      const seen = new Set(this.data.orders.map(row => String(row.id)))
      const next = page.rows.filter(row => !seen.has(String(row.id))).map(order => ({ ...order, ...productView(order), createTime: this.formatTime(order.createTime) }))
      this._cursor = page.cursor
      this.setData({ orders: this.data.orders.concat(next), hasMore: !!page.cursor })
    } catch (err) {
      if (this._current(version, context)) this.setData({ moreError: '加载更多失败，请重试。' })
    } finally {
      if (!this._unloaded && version === this._requestVersion) {
        if (this._current(version, context)) this.setData({ loadingMore: false })
        else { this._cursor = null; this._dataContext = null; this.setData({ orders: [], loaded: false, loadingMore: false, hasMore: false, error: '身份或水站已变化，请重试或重新进入。' }) }
      }
    }
  },
  async onPullDownRefresh() {
    const version = this._pullVersion = (this._pullVersion || 0) + 1
    this._pullRefreshing = true
    try { await this.loadData() } finally {
      if (version === this._pullVersion) { this._pullRefreshing = false; wx.stopPullDownRefresh() }
    }
  },
  onUnload() {
    this._unloaded = true
    this._requestVersion = (this._requestVersion || 0) + 1
    this._pullVersion = (this._pullVersion || 0) + 1
    if (this._pullRefreshing) { this._pullRefreshing = false; wx.stopPullDownRefresh() }
  },

  onProductImageError(e) {
    const { orderId, itemIndex, src } = e.currentTarget.dataset
    const index = this.data.orders.findIndex(row => String(row.id) === String(orderId))
    const order = this.data.orders[index], product = order && order.productItems[itemIndex]
    // 迟到的旧图片回调不能覆盖刷新后同位置的新商品图。
    if (this._unloaded || !product || product.imageUrl !== src || this._dataContext !== this._context()) return
    const orders = this.data.orders.map((row, i) => i === index ? { ...row,
      productItems: row.productItems.map((item, j) => j === Number(itemIndex) ? { ...item, imageUrl: '' } : item)
    } : row)
    this.setData({ orders })
  },

  // 点击订单卡片
  onOrderTap(e) {
    const id = e.currentTarget.dataset.id
    wx.navigateTo({ url: `/pages/order/detail?id=${id}` })
  },

  // 格式化时间
  formatTime(timeStr) {
    if (!timeStr) return ''
    const date = new Date(timeStr)
    const month = (date.getMonth() + 1).toString().padStart(2, '0')
    const day = date.getDate().toString().padStart(2, '0')
    const hour = date.getHours().toString().padStart(2, '0')
    const minute = date.getMinutes().toString().padStart(2, '0')
    return `${month}-${day} ${hour}:${minute}`
  }
})
