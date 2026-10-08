const { getPendingPayments, confirmPayment } = require('../../../api/station-mgmt')
const historyCustomer = require('../../../utils/station-history-customer')
const { get } = require('../../../utils/request')

// 支付方式/支付状态文案一律由后端下发（PaymentRecord.getMethodText / getStatusText，
// 真相源为 PayMethod.java / PaymentStatus.java）。前端不再自建 1/2/3 映射表。

Page({
  ...historyCustomer.methods,
  data: {
    ...historyCustomer.data,
    list: [],
    view: 'pending', startDate: '', endDate: '', historyScope: '', historyPage: 1, historyHasMore: false,
    loading: false,
    /**
     * [2026-09-27 走查 C05/U01 同族修] 加载失败的原因（空串 = 成功）。
     *
     * 原来失败只有一句 `wx.showToast`：toast 两三秒后消失，页面剩一句「暂无待确认收款」——
     * 与"本站真的没有待收款"一模一样（AGENTS §8.22 的老形状），而且这里**没有下拉刷新之外的重试路**。
     * 现在把失败原因常驻在页面上，并给一个「重新加载」。
     */
    loadError: ''
  },

  onLoad(options) {
    if (options && options.view === 'ticketHistory') this.setData({ view: 'ticketHistory' })
    const id = Number(options && options.customerId)
    if (Number.isSafeInteger(id) && id > 0) this.setData({ customerId: id, customerName: '客户 #' + id })
    this.syncNavigationTitle()
  },
  syncNavigationTitle() {
    if (typeof wx.setNavigationBarTitle === 'function') {
      wx.setNavigationBarTitle({ title: this.data.view === 'ticketHistory' ? '水票购买收款历史' : '待确认收款' })
    }
  },
  onSetView(e) {
    const view = e.currentTarget.dataset.key
    if (!['pending', 'ticketHistory'].includes(view) || view === this.data.view) return
    this.setData({ view, list: [], loadError: '' })
    this.syncNavigationTitle()
    return this.loadData()
  },
  // 缓存只属于当前登录对象、员工/站别、客户与视图；日期是该缓存的本地筛选。
  paymentQueryIdentity() {
    const owner = getApp().globalData.userInfo || null
    const user = owner || {}
    return { owner, key: JSON.stringify([user.staffId, user.role, user.stationId, this.data.customerId || null, this.data.view]) }
  },
  paymentQueryMatches(query) {
    const current = this.paymentQueryIdentity()
    return !this._paymentsHidden && query && query.owner === current.owner && query.key === current.key
  },
  clearHistoryCache() {
    this._historyRecords = []
    this._filteredHistory = []
    this._historyCacheQuery = null
    this._historyState = 'idle'
    this.setData({ list: [], historyScope: '', historyPage: 1, historyHasMore: false })
  },
  hasCurrentHistory() {
    if (this._historyState === 'ready' && this.paymentQueryMatches(this._historyCacheQuery)
        && this._historyCacheSeq === this._paymentsSeq) return true
    if (this._historyState === 'ready') {
      this.clearHistoryCache()
      this._historyLoadError = '查询条件已变化，请重新加载'
    }
    this.setData({ list: [], historyScope: '', historyPage: 1, historyHasMore: false,
      loadError: this._historyLoadError || this.data.loadError })
    return false
  },
  resetHistoryCustomerSearch() {
    this._historySearchSeq = (this._historySearchSeq || 0) + 1
    this.setData({ customerKeyword: '', customerResults: [], customerSearchDone: false,
      customerSearching: false, customerSearchError: '' })
  },
  onSelectHistoryCustomer(e) {
    const row = this.data.customerResults.find(x => String(x.id) === String(e.currentTarget.dataset.id))
    if (!row) return
    this.setData({ customerId: row.id, customerName: [row.name || '客户', row.phone || ''].filter(Boolean).join(' · ') })
    this.resetHistoryCustomerSearch()
    return this.loadData()
  },
  onClearHistoryCustomer() {
    this.setData({ customerId: null, customerName: '' })
    this.resetHistoryCustomerSearch()
    return this.loadData()
  },
  onPaymentHistoryDate(e) {
    const field = e.currentTarget.dataset.field
    if (!['startDate', 'endDate'].includes(field)) return
    this.setData({ [field]: e.detail.value })
    return this.applyHistoryFilters()
  },
  onClearPaymentDates() { this.setData({ startDate: '', endDate: '' }); this.applyHistoryFilters() },
  applyHistoryFilters() {
    if (this.data.view !== 'ticketHistory' || !this.hasCurrentHistory()) return
    if (this.data.startDate && this.data.endDate && this.data.startDate > this.data.endDate) {
      this._filteredHistory = []
      this.setData({ list: [], historyPage: 1, historyHasMore: false, loadError: '开始日期不能晚于结束日期' }); return
    }
    const rows = (this._historyRecords || []).filter(r => {
      if (r.orderId || !(Number(r.ticketQty) > 0)) return false
      const date = String(r.createTime || '').slice(0, 10)
      return (!this.data.startDate || date >= this.data.startDate) && (!this.data.endDate || date && date <= this.data.endDate)
    })
    this._filteredHistory = rows
    this.setData({ list: rows.slice(0, 50), historyPage: 1, historyHasMore: rows.length > 50, loadError: '' })
  },
  onHistoryMore() {
    if (this.data.view !== 'ticketHistory' || this.data.loading || !this.hasCurrentHistory()
        || this.data.loadError || !this.data.historyHasMore) return
    const page = this.data.historyPage + 1, rows = this._filteredHistory || []
    this.setData({ list: rows.slice(0, page * 50), historyPage: page, historyHasMore: rows.length > page * 50 })
  },

  onHide() {
    this._paymentsHidden = true
    this._paymentsSeq = (this._paymentsSeq || 0) + 1
    this._historySearchSeq = (this._historySearchSeq || 0) + 1
    this.clearHistoryCache()
    this.setData({ loading: false })
  },
  onUnload() { this.onHide() },
  onShow() {
    this._paymentsHidden = false
    this.syncNavigationTitle()
    const app = getApp()
    if (!app.canAccessStationBusiness()) {
      this.onHide()
      app.routeByRole(true)
      return
    }
    return this.loadData()
  },

  onPullDownRefresh() {
    this.loadData().then(() => wx.stopPullDownRefresh())
  },

  async loadData() {
    if (this._paymentsHidden) return
    const seq = this._paymentsSeq = (this._paymentsSeq || 0) + 1
    const query = this.paymentQueryIdentity()
    const view = this.data.view, customerId = this.data.customerId
    const stationId = (query.owner || {}).stationId
    this.clearHistoryCache()
    this._historyState = 'loading'
    this._historyLoadError = ''
    this.setData({ loading: true, loadError: '' })
    try {
      const res = view === 'pending' ? await getPendingPayments()
        : customerId ? await get('/api/payments/customer/' + customerId, { stationId })
          : await get('/api/payments', { limit: 200 })
      if (seq !== this._paymentsSeq || !this.paymentQueryMatches(query)) return
      if (!Array.isArray(res.data)) throw new Error('收款记录尚未核对')
      // wxml 里不能做函数调用与三元嵌套，所有展示字段在 JS 里预计算好。
      const list = (res.data || []).map(item => Object.assign({}, item, {
        methodText: item.methodText || '—',
        // [2026-09-27 走查 M01 修] 兜底从 `'—'` 改成 `'待核实到账'`：
        // 本列表按 `status = 1`（待收款）过滤，缺文案时写"待核实到账"是**照实说**，
        // 与页面顶部说明、按钮（确认已到账）同口径；写 `'—'` 则等于把这一格的信息丢掉，
        // 而写"已收到"会与事实相反。⚠️ 这**不是**前端自带映射表 ——
        // 文案正本仍是后端 PaymentStatus.textOf（有值就用它），这里只是缺值时的兜底。
        statusText: item.statusText || (view === 'pending' ? '待核实到账' : '状态未核对'),
        amountText: item.amount !== null && item.amount !== undefined && Number.isFinite(Number(item.amount))
          ? Number(item.amount).toFixed(2) : '未核对',
        // 无订单收款还包括独立押金和上门收桶费，不能统一当成购票。
        isTicketPurchase: !item.orderId && item.ticketQty > 0,
        purposeText: item.purposeText || item.note || '请核实款项用途',
        // 备注后端带的是「线上购买水票」等中文，直接展示；为空时给个兜底
        noteText: item.note || item.purposeText || '独立款项待核实',
      }))
      if (view === 'pending') this.setData({ list, loadError: '' })
      else {
        this._historyRecords = list
        this._historyCacheQuery = query
        this._historyCacheSeq = seq
        this._historyState = 'ready'
        this.setData({ historyScope: customerId
          ? '在该客户本站全部支付记录中筛选线上购票；这里不是持券余额或补票记录。'
          : '在本站最近200笔支付记录中筛选线上购票，可能不含更早记录；按客户查询可查看该客户本站历史。' })
        this.applyHistoryFilters()
      }
    } catch (err) {
      // 失败必须**常驻可见**（不是只弹一次 toast）：见 data.loadError 的注释
      if (seq !== this._paymentsSeq || !this.paymentQueryMatches(query)) return
      this.clearHistoryCache()
      this._historyState = 'error'
      this._historyLoadError = (err && err.message) || '网络异常'
      this.setData({ loadError: this._historyLoadError })
      wx.showToast({ title: err.message || '加载失败', icon: 'none' })
    } finally {
      if (seq === this._paymentsSeq) {
        if (!this.paymentQueryMatches(query)) {
          this.clearHistoryCache()
          this._historyLoadError = '查询条件已变化，请重新加载'
          this.setData({ loadError: this._historyLoadError })
        }
        this.setData({ loading: false })
      }
    }
  },

  /** 失败提示条上的「重新加载」（wxml 绑定，不存在会导致点击静默无反应） */
  onRetryLoad() {
    return this.loadData()
  },

  /**
   * 确认某笔收款已到账。
   * 后端按订单、购票、独立押金及收桶费来源分派实际入账，
   * 无订单款项不能提示“订单已付款”。
   * 因此重复点击是安全的，但仍要提示清楚"确认的是钱已到手"，避免误把未到账的钱点成已确认。
   */
  onConfirm(e) {
    if (this.data.view !== 'pending') return
    const seq = this._paymentsSeq
    const id = e.currentTarget.dataset.id
    const item = this.data.list.find(x => x.id === id)
    if (!item) return
    const what = item.isTicketPurchase
      ? `确认已收到该客户购买 ${item.ticketQty || 0} 张水票的款项 ¥${item.amountText}？确认后水票立即入账。`
      : item.orderId
        ? `确认已收到该客户支付 ¥${item.amountText}？确认后订单将标记为已付款。`
        : `确认已收到该客户的${item.purposeText} ¥${item.amountText}？请核对款项用途和实际到账后登记。`

    wx.showModal({
      title: '确认收款',
      content: what,
      confirmText: '确认到账',
      confirmColor: '#2E9E6B',
      success: async (res) => {
        if (!res.confirm || this.data.view !== 'pending' || seq !== this._paymentsSeq) return
        wx.showLoading({ title: '确认中...' })
        try {
          await confirmPayment(id)
          wx.hideLoading()
          wx.showToast({ title: '已确认到账', icon: 'success' })
          this.loadData()
        } catch (err) {
          wx.hideLoading()
          wx.showToast({ title: err.message || '确认失败', icon: 'none', duration: 3000 })
          // 失败常见原因是状态已被改（重复确认）→ 重新拉一次让列表自愈
          this.loadData()
        }
      }
    })
  }
})
