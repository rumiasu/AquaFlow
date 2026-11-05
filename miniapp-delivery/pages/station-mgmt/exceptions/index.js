const { showBusinessModal } = require('../../../utils/business-dialog')
// 站长端「异常订单」：把**同一件事的两个面**放在一个页面里用页签切换 ——
//   · 页签 1「桶异常单」= 待办清单（原单、应回与实回、资产及核实结案）；
//   · 页签 2「处理留痕」= 处理流水（补偿已执行 / 异常被忽略 的告警记录）。
//
// [2026-09-19 合并] 原来这是两个页面（`barrel-exceptions` 与 `alerts`）。合并不是为省一个入口，
// 而是因为「运营告警」的内容**本来就只来自桶异常单这一个流程**
// （见原 alerts 页的 RELATED_TEXT：只有 ORDER_BARREL_EXCEPTION；级别只有 待处置 / 留痕）——
// 同一件事拆成两张卡、两个页面，站长看到的是"两个都要点，但说的是一件事"。
//
// 四条口径（改这个页面时必须守住）：
//   1. 2026-11-03：错误自动建议补偿已停用；核对原单/资产/实际还桶或有理由结案。
//      历史记录保留，不能通过旧处理函数新增票款，也不能将未退现金写成已退。
//   2. **前端不做任何判定**：状态/类别的中文用服务端下发的 `statusText` / `categoryText`，
//      "还等着处理"用服务端下发的 `pending` 布尔（不自带状态码表，也不比对中文文案）。
//      告警级别同理，用后端下发的 level 只做配色与筛选。
//   3. **系统告警永远不进这里**：`/api/manager/alerts` 固定只返回 `alert_type='OPERATION'`
//      且本站的记录。对账不平 / 补偿失败 / 未预期 500 那些带平台级细节的告警收件人是系统管理员，
//      漏给站长就是越权知情 —— 前端**不要**试图"顺带把全部告警拉出来"。
//   4. **不显示桶损耗**（2026-09-19 删）：2026-09-18 已定"本站不做桶损耗出账"（桶是跟水厂换的，
//      破损丢失归水厂），原先那张卡正常情况下永远是 0，还要配一句"0 不代表没丢过"的解释 ——
//      一个恒为 0 的数字加一段免责声明，只会让这一页显得乱。客户弄丢/欠着的桶在「欠桶台账」。
const { get, post } = require('../../../utils/request')
const { API } = require('../../../config/api')
// 告警走 api 模块里的包装函数（不直接拼路径）：那个函数是这个端点的**唯一**调用实现，
// 绕开它会立刻变成"定义了没人用"的死包装函数（本仓 §0.3 的判据：定义 ≠ 调用）。
const { getAlerts } = require('../../../api/station-mgmt')
const { referenceText } = require('../../../utils/display-message')

const EXCEPTIONS = API.MANAGER_EXCEPTIONS

/** 页签。key 同时是 wxml 里 pane 的判断值，也是 `?tab=` 的取值。 */
const TABS = [
  { key: 'exceptions', label: '桶异常单' },
  { key: 'alerts', label: '处理留痕' }
]

// 告警级别 → 展示文案与配色类。后端运营侧只有 WARN / INFO；
// ERROR 留给系统告警，这里仍保留映射以免将来复用该页时漏样式。
const LEVEL_TEXT = { ERROR: '严重', WARN: '待处置', INFO: '留痕' }
const LEVEL_CLASS = { ERROR: 'level-error', WARN: 'level-warn', INFO: 'level-info' }

// 关联业务对象类型 → 中文。后端传常量字符串，前端只做展示映射，不做逻辑判断。
const RELATED_TEXT = { ORDER_BARREL_EXCEPTION: '桶异常单' }

Page({
  data: {
    tabs: TABS,
    activeTab: 'exceptions',
    loading: true,
    denied: false,

    // —— 页签 1：桶异常单 ——
    onlyPending: false,
    items: [],
    total: 0,
    loaded: 0,
    page: 0, size: 50, hasMore: false, listError: '', loadingMore: false,
    stats: null, alertsError: '', statsError: '',

    // —— 页签 2：处理留痕 ——
    // `alerts` 存全量，`visibleAlerts` 是当前筛选后的结果 —— **筛选在 js 里做**：
    // wxml 禁复杂表达式（本仓约定），而且"筛完还有几条"要用来决定给不给"试试全部"的提示。
    alerts: [],
    visibleAlerts: [],
    alertFilter: 'ALL',
    alertFilters: [
      { value: 'ALL', label: '全部', count: 0 },
      { value: 'WARN', label: '待处置', count: 0 },
      { value: 'INFO', label: '留痕', count: 0 }
    ],
    // 页签上的角标：待处置（WARN）的条数。它是"有没有事等着我"的信号，
    // 与其他两个级别的计数一样**在筛选之前**算好，切筛选不会把它清零
    // （否则切到「留痕」时角标变 0，会被读成"没有待处置的事"）
    statsExpanded: false, handling: false, alertWarnCount: 0,
    uncertainId: null, actionNotice: ''
  },

  /**
   * `?tab=alerts` 直接落在处理留痕上。
   * 入口来自首页待办卡的 `operationAlert`（它说的就是"有补偿/忽略的留痕"）；
   * 不认的参数一律退回默认页签 —— 拼错的参数不该把页面打成空白。
   */
  onLoad(options) {
    const key = options && options.tab
    if (key && TABS.some(t => t.key === key)) {
      this.setData({ activeTab: key })
    }
  },

  onShow() {
    this._exceptionsHidden = false
    const app = getApp()
    // 2026-11-05：已绑定配送员也能通过通用业务守卫，但本页查询与结案仅属站长。
    if (!this.canAccessExceptions()) {
      this._listContext = null
      this.setData({ denied: true, loading: false, items: [], stats: null, alerts: [], visibleAlerts: [], uncertainId: null, actionNotice: '' })
      app.routeByRole(true)
      return
    }
    const context = this.exceptionContext()
    if (this._viewContext && this._viewContext !== context) this.setData({ uncertainId: null, actionNotice: '', stats: null, alerts: [], visibleAlerts: [] })
    this._viewContext = context
    this.setData({ denied: false })
    return this.load()
  },

  canAccessExceptions() { const app = getApp(); return app.canAccessStationBusiness() && app.isStationManager() },
  currentExceptionRead(context) { return !this._exceptionsHidden && context === this.exceptionContext() && this.canAccessExceptions() },

  /** json 里开了 enablePullDownRefresh，就必须有对应的处理函数，否则下拉只转圈不停。 */
  async onPullDownRefresh() {
    try {
      await this.load()
    } finally {
      wx.stopPullDownRefresh()
    }
  },

  onSwitchTab(e) {
    const key = e.currentTarget.dataset.key
    if (key && key !== this.data.activeTab) {
      this.setData({ activeTab: key })
    }
  },

  onTogglePending() {
    this.setData({ onlyPending: !this.data.onlyPending })
    return this.loadList(true)
  },

  onAlertFilter(e) {
    const filter = e.currentTarget.dataset.value
    if (filter === this.data.alertFilter) return
    this.setData({
      alertFilter: filter,
      visibleAlerts: filterAlerts(this.data.alerts, filter)
    })
  },

  /**
   * 一次把两个页签的数据都拉回来。
   *
   * 用 `allSettled` 而不是 `Promise.all`：两个页签是两件互不依赖的事，一边失败不该把另一边
   * 也打成空白（"加载失败"与"确实没有异常"必须区分得开，见 §8.22）。
   */
  /* 核实处理逐单留理由，失败/结果未知保留原异常编号查询；不发补偿命令。 */

  /** 让站长填一句理由（必填）。返回 null = 取消或没填。 */
  askNote(title, placeholder, options = {}) {
    return new Promise((resolve) => {
      showBusinessModal({ confirmColor: options.confirmColor || '#1264D6', cancelColor: '#637083',
        title, initialValue: options.initialValue || '', description: options.description || '', details: options.details || [],
        editable: true,
        placeholderText: placeholder,
        confirmText: options.confirmText || '提交',
        success: (r) => {
          if (!r.confirm) return resolve(null)
          const v = (r.content || '').trim()
          if (!v) {
            wx.showToast({ title: '请填写原因', icon: 'none' })
            return resolve(null)
          }
          resolve(v)
        }, fail: () => resolve(null)
      })
    })
  },

  /** ① 忽略：最轻的动作，不动任何金额与桶账。 */
  async onIgnore(e) {
    if (this._exceptionAction || this._exceptionsHidden || this.data.uncertainId || !this.canAccessExceptions()) return
    const action = { context: this.exceptionContext() }
    this._exceptionAction = action
    this.setData({ handling: true })
    try {
      const id = e.currentTarget.dataset.id
      const note = await this.askNote('忽略这张异常单', '请填写忽略原因（会留痕）')
      if (note === null || this._exceptionAction !== action || this._exceptionsHidden
        || action.context !== this.exceptionContext() || !this.canAccessExceptions()) return
      await this.submitHandle(id, { action: 'IGNORE', managerNote: note }, '已忽略')
    } finally {
      if (this._exceptionAction === action) { this._exceptionAction = null; this.setData({ handling: false }) }
    }
  },

  /** 旧回调保留为明确拒绝，防止旧组件触发补偿。 */
  async onApproveSuggested(e) {
    if (this._exceptionsHidden || !this.canAccessExceptions()) return
    showBusinessModal({ title: '补偿已停用', content: '请核对原订单、客户资产及实际还桶，再按依据办理。', showCancel: false })
    },

  /** 带理由登记非财务处理；结果未知仅查询原异常。 */
  async submitHandle(id, body, okTitle) {
    const context = this.exceptionContext()
    const current = () => this.currentExceptionRead(context)
    try {
      wx.showLoading({ title: '处理中...' })
      await post(EXCEPTIONS + '/' + id + '/handle', body)
      wx.hideLoading()
      if (!current()) return false
      if (okTitle) {
        wx.showToast({ title: okTitle, icon: 'success' })
        await this.load()
      }
      return true
    } catch (err) {
      wx.hideLoading()
      if (!current()) return false
      // 2026-11-02：网络失败不代表事务失败，只查询原单，绝不追加 /execute 或自动重发补偿。
      if (!err.businessRejected) {
        this.setData({ uncertainId: id, actionNotice: '原办理结果待核对，请勿重复补偿。' })
        await this.executeCompensation(id)
        return false
      }
      showBusinessModal({ confirmColor: '#1264D6', cancelColor: '#637083',  confirmText: '知道了',
        title: '处理失败',
        content: (err && err.message) || '请稍后重试',
        showCancel: false
      })
      // 另一位站长可能已推进原异常；明确拒绝也要刷新，不能让旧待办继续诱导再次办理。
      this.setData({ actionNotice: '本次办理未获通过，请按最新记录核对。' })
      await this.load()
      return false
    }
  },

  /** 保留原入口名供既有调用兼容；现在只核对原异常；/handle亦禁止新增补偿。 */
  async executeCompensation(id) {
    if (this._exceptionsHidden || !this.canAccessExceptions()) return
    const context = this.exceptionContext()
    try {
      const res = await get(EXCEPTIONS + '/' + id)
      if (!this.currentExceptionRead(context)) return
      const row = res && res.data
      if (!row || String(row.id) !== String(id) || !row.status) throw new Error('原办理记录尚未核实')
      const terminal = ['EXECUTED', 'IGNORED', 'VERIFIED', 'RETURNED'].includes(row.status)
      this.setData({ uncertainId: terminal ? null : id, actionNotice: terminal
        ? '原记录显示：' + (row.statusText || '办理已结束') + '。请核对最新记录。'
        : row.status === 'STAFF_RECORDED' ? '原记录仍待处理。请先核实最新状态，再重新选择办理动作。' : '原记录正在办理，请稍后再次核对。' })
      if (row.status === 'STAFF_RECORDED') this.setData({ uncertainId: null })
      await this.load()
    } catch (err) {
      if (this.currentExceptionRead(context)) this.setData({ uncertainId: id,
        actionNotice: '原办理结果尚未核实，请重试核对，勿再次补偿。' })
    }
  },
  onVerifyHandling() { if (this.data.uncertainId) return this.executeCompensation(this.data.uncertainId) },

  exceptionForNavigation(e) {
    if (this._exceptionsHidden || !this.canAccessExceptions() || this._listContext !== this.exceptionContext()) return null
    return this.data.items.find(item => String(item.id) === String(e.currentTarget.dataset.id)) || null
  },
  onReturnExceptionEmpty(e) {
    const item = this.exceptionForNavigation(e)
    // 2026-11-05：历史差异不是当前待还数；只有服务端明确允许才打开既有实际收桶流程。
    if (item && item.canReturnEmpty && /^[1-9][0-9]*$/.test(String(item.customerId))) {
      wx.navigateTo({ url: '/pages/station-mgmt/customers/detail/index?id=' + item.customerId + '&action=return-empty' })
    }
  },
  onOpenResolution(e) {
    const item = this.exceptionForNavigation(e)
    if (item) wx.navigateTo({ url: '/pages/case-handling/index?kind=EXCEPTION&id=' + item.id })
  },
  onOpenExceptionOrder(e) {
    const item = this.exceptionForNavigation(e)
    if (item && /^[1-9][0-9]*$/.test(String(item.orderId))) wx.navigateTo({ url: '/pages/order/detail?id=' + item.orderId })
  },
  onOpenExceptionCustomer(e) {
    const item = this.exceptionForNavigation(e)
    if (item && /^[1-9][0-9]*$/.test(String(item.customerId))) wx.navigateTo({ url: '/pages/station-mgmt/customers/detail/index?id=' + item.customerId })
  },

  onReachBottom() { return this.onLoadMore() },
  onLoadMore() { return this.loadList(false) },
  onRetryList() { return this.loadList(this.data.page === 0) },
  exceptionContext() { const app = getApp(), u = app.globalData.userInfo || {}; return JSON.stringify([app._loginGeneration || 0, u.staffId, u.role, u.stationId]) },
  onToggleExceptionStats() { this.setData({ statsExpanded: !this.data.statsExpanded }) },
  onHide() {
    // 2026-11-05：去欠桶页办理后必须重读；离页前的慢响应不得覆盖返回后的新结果。
    this._exceptionsHidden = true; this._exceptionAction = null; this._listContext = null
    this._listSeq = (this._listSeq || 0) + 1; this._overviewSeq = (this._overviewSeq || 0) + 1
    this.setData({ handling: false })
  },
  onUnload() { this.onHide() },

  async loadList(reset) {
    if (this._exceptionsHidden || !this.canAccessExceptions()) return
    if (!reset && (this.data.loading || this.data.loadingMore || !this.data.hasMore)) return
    const context = this.exceptionContext()
    this._listContext = null
    const seq = this._listSeq = (this._listSeq || 0) + 1
    const page = reset ? 1 : this.data.page + 1
    const query = { page, size: this.data.size }
    // 2026-10-06：本地过滤最近50条会漏掉旧待办；状态与分页必须交给同一个本站查询。
    if (this.data.onlyPending) query.status = 'STAFF_RECORDED'
    this.setData({ loading: !!reset, loadingMore: !reset, listError: '' })
    if (reset) this.setData({ items: [], total: null, loaded: 0, page: 0, hasMore: false })
    try {
      const res = await get(EXCEPTIONS, query)
      if (seq !== this._listSeq || !this.currentExceptionRead(context)) return
      const data = res && res.data
      if (!data || !Array.isArray(data.records) || !Number.isSafeInteger(data.total) || data.total < 0) throw new Error('异常列表尚未核对')
      const raw = data.records
      const mapped = raw.map(r => ({
        id: r.id,
        orderId: r.orderId,
        customerId: r.customerId,
        // 2026-10-24：跨站只读订单收件人快照，不能把它标成他站客户档案姓名。
        customerText: typeof r.customerName === 'string' && r.customerName.trim() ? referenceText(r.customerName, r.customerId, '客户')
          : typeof r.receiverName === 'string' && r.receiverName.trim() ? '收件人：' + r.receiverName.trim() + '（客户编号：' + r.customerId + '）'
            : referenceText(null, r.customerId, '客户'),
        pending: !!r.pending,
        canReturnEmpty: r.pending === true && r.canReturnEmpty === true && /^[1-9][0-9]*$/.test(String(r.customerId)),
        canVerifyReturn: r.pending === true && r.canVerifyReturn === true,
        // 2026-11-05：缺少可归还凭据不能变成无入口；类别只提示去核对，不认定仍欠、不预填收桶。
        reviewOwedBarrels: r.pending === true && r.category === 'RETURN_SHORT' && r.canReturnEmpty !== true
          && r.canVerifyReturn !== true && /^[1-9][0-9]*$/.test(String(r.customerId)),
        handlingHint: typeof r.handlingHint === 'string' ? r.handlingHint : '',
        categoryText: r.categoryText,
        statusText: r.statusText,
        // 回桶异常的deliveryQty保存应回值（已排除新领容量），不能再标成送出数。
        qtyText: (String(r.category || '').startsWith('RETURN_') ? '应回 ' : '核对值 ') + numText(r.deliveryQty) + (String(r.category || '').startsWith('RETURN_') ? ' / 实回 ' : ' / 实际值 ') + numText(r.returnQty)
          + ' / 差 ' + numText(r.discrepancy),
        staffNote: r.staffNote || '',
        managerNote: r.managerNote || '',
        timeText: dateTimeText(r.createdAt)
      }))
      const previous = reset ? [] : this.data.items
      const ids = new Set(previous.map(it => it.id))
      const added = mapped.filter(it => !ids.has(it.id) && ids.add(it.id))
      const items = previous.concat(added)
      this._listContext = context
      this.setData({ items, total: data.total, loaded: items.length, page,
        hasMore: raw.length > 0 && page * this.data.size < data.total })
      if (!reset && raw.length && !added.length) {
        this.setData({ hasMore: false, listError: '列表暂未更新，请下拉刷新重试' })
      }
    } catch (e) {
      if (seq === this._listSeq && this.currentExceptionRead(context)) this.setData({ listError: errText(e, '异常列表加载失败，请重试') })
    } finally {
      if (seq === this._listSeq && this.currentExceptionRead(context)) this.setData({ loading: false, loadingMore: false })
    }
  },

  onRetryOverview() { return this.load() },

  async load() {
    if (this._exceptionsHidden || !this.canAccessExceptions()) return
    const context = this.exceptionContext()
    const seq = this._overviewSeq = (this._overviewSeq || 0) + 1
    this.setData({ loading: true, alertsError: '', statsError: '' })
    const today = new Date()
    const from = dayText(new Date(today.getTime() - 29 * 24 * 3600 * 1000))
    const to = dayText(today)

    const [listRes, statsRes, alertsRes] = await Promise.allSettled([
      this.loadList(true),
      get(EXCEPTIONS + '/stats', { startDate: from, endDate: to }),
      getAlerts(200)
    ])

    if (seq !== this._overviewSeq || !this.currentExceptionRead(context)) return
    const next = {}

    if (statsRes.status === 'fulfilled') {
      next.stats = statsRes.value.data || null
    } else {
      next.stats = null; next.statsError = '近30天统计尚未读取，请重试'
      console.warn('[Exceptions] 近 30 天统计加载失败:', errText(statsRes.reason, ''))
    }

    if (alertsRes.status === 'fulfilled') {
      const all = (alertsRes.value.data || []).map(a => ({
        id: a.id,
        title: a.title || '(无标题)',
        content: a.content || '',
        level: a.level || 'INFO',
        levelText: LEVEL_TEXT[a.level] || '级别待核实',
        levelClass: LEVEL_CLASS[a.level] || 'level-info',
        sourceText: a.source ? (a.source === 'OrderBarrelException' ? '来源：桶异常处理' : '记录来源待核实') : '',
        timeText: dateTimeText(a.createTime),
        relatedText: a.relatedType
          ? ((RELATED_TEXT[a.relatedType] || '关联类型待核实') + (a.relatedId ? ' #' + a.relatedId : ''))
          : '',
        // 投递状态如实标注：LOGGED = 已落库（**本页面就是它的送达方式**，不是"没发出去"）
        notifyText: a.notifyStatus === 'PUSHED' ? '已推送'
          : (a.notifyStatus === 'FAILED' ? '推送失败，记录已保存' : a.notifyStatus === 'LOGGED' ? '已保存' : '通知状态待核实')
      }))
      next.alerts = all
      next.visibleAlerts = filterAlerts(all, this.data.alertFilter)
      next.alertFilters = this.data.alertFilters.map(f => ({
        value: f.value,
        label: f.label,
        count: f.value === 'ALL' ? all.length : all.filter(a => a.level === f.value).length
      }))
      next.alertWarnCount = all.filter(a => a.level === 'WARN').length
    } else {
      next.alertsError = '处理留痕尚未读取，请重试后核对'
      next.alerts = []
      next.visibleAlerts = []
      next.alertFilters = this.data.alertFilters.map(f => ({ value: f.value, label: f.label, count: 0 }))
      next.alertWarnCount = 0
      wx.showToast({ title: errText(alertsRes.reason, '处理留痕加载失败'), icon: 'none' })
    }

    this.setData(next)
  }
})

/** 为空显示 '-'：0 与"没这个数"在界面上必须分得清（0 是有效值） */
function numText(v) {
  return (v === null || v === undefined) ? '-' : v
}

/** 按级别筛选告警。`ALL` 之外的取值直接交给 level 比较 —— 级别表是后端下发的，前端不写死枚举。 */
function filterAlerts(alerts, filter) {
  if (filter === 'ALL') return alerts
  return alerts.filter(a => a.level === filter)
}

function errText(err, fallback) {
  return (err && err.message) || fallback
}



function pad(n) {
  return n < 10 ? '0' + n : '' + n
}

/** 本地日期串（后端按 LocalDate 解析；不能用 toISOString —— 那是 UTC，会差一天） */
function dayText(d) {
  return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate())
}

/** ISO-8601 串直接 new Date()，禁止 replace(/-/g,'/')（本仓有明文约定） */
function dateTimeText(s) {
  if (!s) return ''
  const d = new Date(s)
  if (isNaN(d.getTime())) return ''
  return dayText(d) + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes())
}
