// 站长端「异常订单」：把**同一件事的两个面**放在一个页面里用页签切换 ——
//   · 页签 1「桶异常单」= 待办清单（哪几单回桶有差异、系统建议补什么）；
//   · 页签 2「处理留痕」= 处理流水（补偿已执行 / 异常被忽略 的告警记录）。
//
// [2026-09-19 合并] 原来这是两个页面（`barrel-exceptions` 与 `alerts`）。合并不是为省一个入口，
// 而是因为「运营告警」的内容**本来就只来自桶异常单这一个流程**
// （见原 alerts 页的 RELATED_TEXT：只有 ORDER_BARREL_EXCEPTION；级别只有 待处置 / 留痕）——
// 同一件事拆成两张卡、两个页面，站长看到的是"两个都要点，但说的是一件事"。
//
// 四条口径（改这个页面时必须守住）：
//   1. **本页只读**。后端确实有处置入口（handle / execute：同意、改判、忽略、退水票、退现金、
//      调资产），但那些动作会**真动钱和桶账**，要不要在界面上开放是产品决定 —— 所以这里
//      一个按钮都没有。别顺手加"一键补偿"：§8.17 那次事故就是"界面显示已补偿、账上一分没动"。
//   2. **前端不做任何判定**：状态/类别的中文用服务端下发的 `statusText` / `categoryText`，
//      "还等着处理"用服务端下发的 `pending` 布尔（不自带状态码表，也不比对中文文案）。
//      告警级别同理，用后端下发的 level 只做配色与筛选。
//   3. **系统告警永远不进这里**：`/api/manager/alerts` 固定只返回 `alert_type='OPERATION'`
//      且本站的记录。对账不平 / 补偿失败 / 未预期 500 那些带平台级细节的告警收件人是系统管理员，
//      漏给站长就是越权知情 —— 前端**不要**试图"顺带把全部告警拉出来"。
//   4. **不显示桶损耗**（2026-09-19 删）：2026-09-18 已定"本站不做桶损耗出账"（桶是跟水厂换的，
//      破损丢失归水厂），原先那张卡正常情况下永远是 0，还要配一句"0 不代表没丢过"的解释 ——
//      一个恒为 0 的数字加一段免责声明，只会让这一页显得乱。客户弄丢/欠着的桶在「欠桶台账」。
const { get } = require('../../../utils/request')
const { API } = require('../../../config/api')
// 告警走 api 模块里的包装函数（不直接拼路径）：那个函数是这个端点的**唯一**调用实现，
// 绕开它会立刻变成"定义了没人用"的死包装函数（本仓 §0.3 的判据：定义 ≠ 调用）。
const { getAlerts } = require('../../../api/station-mgmt')

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

    // —— 页签 1：桶异常单 ——
    onlyPending: false,
    items: [],
    total: 0,
    loaded: 0,
    stats: null,

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
    alertWarnCount: 0
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
    const app = getApp()
    if (!app.canAccessStationBusiness()) {
      app.routeByRole(true)
      return
    }
    this.load()
  },

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
  async load() {
    this.setData({ loading: true })
    const today = new Date()
    const from = dayText(new Date(today.getTime() - 29 * 24 * 3600 * 1000))
    const to = dayText(today)

    const [listRes, statsRes, alertsRes] = await Promise.allSettled([
      // 一次拿 50 条：水站的异常单量级很小（真实库跑了一个多月只有个位数），
      // 超过时页面会明确提示"只显示了最近 N 条"，不做静默截断。
      get(EXCEPTIONS, { page: 1, size: 50 }),
      get(EXCEPTIONS + '/stats', { startDate: from, endDate: to }),
      getAlerts(200)
    ])

    const next = { loading: false }

    if (listRes.status === 'fulfilled') {
      const raw = (listRes.value.data && listRes.value.data.records) || []
      next.items = raw.map(r => ({
        id: r.id,
        orderId: r.orderId,
        customerId: r.customerId,
        pending: !!r.pending,
        categoryText: r.categoryText,
        statusText: r.statusText,
        // 差异：交付 N 桶、收回 M 桶 —— 少收的那部分就是这张单要处理的
        qtyText: '送 ' + numText(r.deliveryQty) + ' / 回 ' + numText(r.returnQty)
          + ' / 差 ' + numText(r.discrepancy),
        staffNote: r.staffNote || '',
        managerNote: r.managerNote || '',
        suggestText: suggestText(r),
        timeText: dateTimeText(r.createdAt)
      }))
      next.total = (listRes.value.data && listRes.value.data.total) || raw.length
      next.loaded = raw.length
    } else {
      // 静默失败会让站长以为"本站没有异常"，从而漏掉处置 —— 必须出声
      next.items = []
      next.total = 0
      next.loaded = 0
      wx.showToast({ title: errText(listRes.reason, '异常列表加载失败'), icon: 'none' })
    }

    if (statsRes.status === 'fulfilled') {
      next.stats = statsRes.value.data || null
    } else {
      next.stats = null
      console.warn('[Exceptions] 近 30 天统计加载失败:', errText(statsRes.reason, ''))
    }

    if (alertsRes.status === 'fulfilled') {
      const all = (alertsRes.value.data || []).map(a => ({
        id: a.id,
        title: a.title || '(无标题)',
        content: a.content || '',
        level: a.level || 'INFO',
        levelText: LEVEL_TEXT[a.level] || a.level || '未知',
        levelClass: LEVEL_CLASS[a.level] || 'level-info',
        sourceText: a.source ? ('来源 ' + a.source) : '',
        timeText: dateTimeText(a.createTime),
        relatedText: a.relatedType
          ? ((RELATED_TEXT[a.relatedType] || a.relatedType) + (a.relatedId ? ' #' + a.relatedId : ''))
          : '',
        // 投递状态如实标注：LOGGED = 已落库（**本页面就是它的送达方式**，不是"没发出去"）
        notifyText: a.notifyStatus === 'PUSHED' ? '已推送'
          : (a.notifyStatus === 'FAILED' ? '推送失败（已落库）' : '已落库')
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

/** 服务端给的"建议补偿"（它自己不判断要不要补，只把当初算出来的数摆出来） */
function suggestText(r) {
  const parts = []
  if (r.suggestedTicketQty) parts.push('水票 ' + r.suggestedTicketQty + ' 张')
  if (r.suggestedCashAmount && Number(r.suggestedCashAmount) !== 0) {
    parts.push('现金 ￥' + Number(r.suggestedCashAmount).toFixed(2))
  }
  return parts.length ? parts.join(' / ') : ''
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
