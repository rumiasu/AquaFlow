const { showBusinessModal } = require('../../../utils/business-dialog')
// ⚠️ 直接用 utils/request，不引 api/station-mgmt.js：
// 那个文件由另一个工作流（商品图片库）维护，共用会让两边未提交的改动纠缠在一起。
// [2026-09-19 修订] 路径常量**已移入 config/api.js**（MANAGER_GROSS_PROFIT）——
// 原来说"config/api.js 也在被别的改动占着"，那个改动早已合并，本页再留一份副本
// 只会让同一条路径有两个定义（本仓最忌讳的"口径分叉"）。
const { get } = require('../../../utils/request')
const navigation = require('../../../utils/navigation')
const { API } = require('../../../config/api')

const GP = API.MANAGER_GROSS_PROFIT


function buildProfitHelpSections(report) {
  const note = typeof report.helpNote === 'string' ? report.helpNote.trim() : ''
  const parts = note ? note.split(/\n(?=\d+\.\s)/) : []
  let preface = ''
  if (parts.length > 1) preface = parts.shift().trim()
  else if (parts.length === 1 && !/^\s*\d+\.\s/.test(parts[0])) preface = parts.shift().trim()
  const clauses = {}
  parts.forEach(part => {
    const match = part.match(/^(\d+)\.\s*/)
    if (match) clauses[Number(match[1])] = part.slice(match[0].length).trim()
  })
  // 2026-11-09：收入说明收进帮助，不在金额旁常驻重复提醒。
  const scopeNotes = [report.incomeBasisNote, report.periodBasisNote, preface, clauses[1]]
  const feeNotes = [clauses[2], clauses[3], clauses[4], clauses[5], report.cooperation && report.cooperation.scopeNote]
  const costNotes = [clauses[6], clauses[7]]
  if (!note) {
    scopeNotes.push(report.profitBasisNote)
    costNotes.push(report.costBasisNote)
  } else {
    if (!scopeNotes.some(value => value)) scopeNotes.push(report.profitBasisNote)
    if (!costNotes.some(value => value)) costNotes.push(report.costBasisNote)
  }
  const known = new Set([1, 2, 3, 4, 5, 6, 7])
  const otherNotes = Object.keys(clauses).filter(key => !known.has(Number(key)))
    .sort((a, b) => Number(a) - Number(b)).map(key => clauses[key])
  return [
    { key: 'scope', title: '统计时间与收入范围', notes: scopeNotes },
    { key: 'fees', title: '费用、水票与合作款', notes: feeNotes },
    { key: 'costs', title: '成本与未计利润', notes: costNotes },
    { key: 'other', title: '其他计算说明', notes: otherNotes }
  ].filter(group => group.notes.some(value => typeof value === 'string' && value.trim()))
    .map(group => ({ ...group, notes: group.notes.filter(value => typeof value === 'string' && value.trim()), expanded: false }))
}

// 只分组服务端已有行，未知字段也保留在“其他合作款”中。
function buildCooperationView(cooperation, moneyText) {
  const rows = cooperation && Array.isArray(cooperation.rows) ? cooperation.rows : []
  const summaryKeys = ['pendingPayAmount', 'pendingReceiveAmount']
  const groups = [
    { key: 'agreement', title: '原合作约定', keys: ['agreedPayAmount', 'agreedReceiveAmount', 'quoteDifferenceAmount'] },
    { key: 'customerCash', title: '客户实款与登记净额', keys: ['customerReceivedAmount', 'customerRefundedAmount', 'recordedCashNetAmount'] },
    { key: 'settlement', title: '合作交款与收款', keys: ['settledPayAmount', 'settledReceiveAmount'] },
    { key: 'recovery', title: '退款后返还', keys: ['recoveryPendingPayAmount', 'recoveryPendingReceiveAmount', 'recoverySentPayAmount', 'recoveryReceivedAmount', 'recoveryAwaitingConfirmationAmount'] }
  ]
  const formatted = rows.map(row => ({ ...row, amountText: moneyText(row.amount) }))
  const known = new Set(summaryKeys.concat(...groups.map(group => group.keys)))
  const sections = groups.map(group => ({ key: group.key, title: group.title, expanded: false, rows: formatted.filter(row => group.keys.includes(row.key)) }))
  sections.push({ key: 'other', title: '其他合作款', expanded: false, rows: formatted.filter(row => !known.has(row.key)) })
  return { summary: formatted.filter(row => summaryKeys.includes(row.key)), sections: sections.filter(group => group.rows.length) }
}

// 条长只表示服务端金额的相对大小，不计算利润；负值用绝对值长度及明确标记。
function buildProfitChart(report, profitView) {
  // 2026-11-10：旧图只计水费与票款，少了服务费和计件。与首屏、明细共用销售收入口径。
  const fields = [
    { key: 'revenue', label: '销售收入', value: report.totalIncome, text: profitView.totalIncomeText },
    { key: 'cost', label: '出库成本', value: report.totalCost, text: profitView.totalCostText },
    { key: 'wage', label: '计件工钱', value: report.wage, text: profitView.wageText },
    { key: 'profit', label: '销售净利', value: report.netProfit, text: profitView.netProfitText }
  ]
  if (fields.some(item => item.value === null || item.value === undefined || item.value === '' || !Number.isFinite(Number(item.value)))) return []
  const max = Math.max(...fields.map(item => Math.abs(Number(item.value))))
  return fields.map(item => ({ ...item, negative: Number(item.value) < 0, width: max ? Math.abs(Number(item.value)) / max * 100 : 0 }))
}

// 2026-10-28：仅完整的期间成本快照显示利润；收入沿用服务端口径，成本仅在入库选填。
Page({
  data: {
    stationId: null,
    costComplete: false, showProfit: false, splitIncome: false, profitUnavailableReason: '',
    loading: true, reportError: '', missingError: '', missingReady: false,
    quickRange: 0,
    periodExpanded: false,
    incomeDetailsExpanded: false,
    from: '',
    to: '',
    report: null,
    // 缺数时的展示态（唯一在 js 里算的东西，见 _withheldMoney）：wxml 不做判断，
    // 否则 `'¥' + undefined` 会渲染成「¥undefined」——最坏的一种"看起来正常"。
    profitView: {},
    missingCost: [],
    profitHelpSections: [],
    profitHelpAllExpanded: false,
    profitHelpVisible: false,
    cooperationView: { summary: [], sections: [] },
    profitChart: [],
    // 从「我的 → 今日净利」跳进来时置为 'today'：只用于副标题文案与默认期间
    range: '',
    // 成本编辑弹层
    costVisible: false,
    costProduct: null,
    costInput: '',
    costSaving: false
  },

  /**
   * `?range=today` 来自「我的」页收入卡片。
   * 日期仍由这里算好后当 with from/to 传给服务端 —— 但**口径解释权仍在服务端**
   * （它会把生效区间回传，界面显示回传值而不是本地这份）。
   */
  onLoad(options) {
    if (options && options.range === 'today') {
      const today = this._todayStr()
      this.setData({ range: 'today', quickRange: 1, from: today, to: today })
    }
  },

  _todayStr() {
    const d = new Date()
    return d.getFullYear() + '-' +
      String(d.getMonth() + 1).padStart(2, '0') + '-' +
      String(d.getDate()).padStart(2, '0')
  },

  onShow() {
    const app = getApp()
    if (!app.canAccessStationBusiness()) {
      app.routeByRole(true)
      return
    }
    const stationId = (app.globalData.userInfo || {}).stationId
    if (!stationId) {
      wx.showToast({ title: '未识别到所属水站', icon: 'none' })
      return
    }
    // 2026-11-10：缺少可见登记时，微信漏回导航回调会让已打开的利润页误报超时。
    // 页面可见即登记，报表是否读完由页内状态单独反馈。
    navigation.show(this)
    this.setData({ stationId })
    if ([1, 7, 30].includes(this.data.quickRange)) {
      const dates = this._rangeDates(this.data.quickRange)
      this.setData({ from: dates.from, to: dates.to })
    }
    this.load()
  },

  onHide() {
    navigation.hide(this)
    this._loadSeq = (this._loadSeq || 0) + 1
  },

  onUnload() {
    this.onHide()
    navigation.dispose(this)
  },

  /** 利润缺值的原因由服务端说明；null 也可能来自水票无法对应，不能自行解释为缺成本。 */
  _withheldMoney(v) {
    if (v === null) return '待核实'
    return this._moneyText(v)
  },

  _moneyText(v) {
    return v === null || v === undefined || v === '' || !Number.isFinite(Number(v)) ? '待核实' : '¥' + v
  },

  onRetryReport() { return this.load() },

  onTogglePeriod() { this.setData({ periodExpanded: !this.data.periodExpanded }) },

  async load() {
    const seq = this._loadSeq = (this._loadSeq || 0) + 1
    this.setData({ loading: true, costComplete: false, showProfit: false, splitIncome: false,
      profitUnavailableReason: '', report: null, reportError: '', missingError: '', missingReady: false, missingCost: [], profitView: {} })
    const qs = []
    // The month shortcut stays relative when the page is reopened after a month boundary.
    if (this.data.quickRange !== 0 && this.data.from) qs.push('from=' + this.data.from)
    if (this.data.quickRange !== 0 && this.data.to) qs.push('to=' + this.data.to)
    const [reportResult] = await Promise.allSettled([get(GP + (qs.length ? '?' + qs.join('&') : ''))])
    if (seq !== this._loadSeq) return
    const patch = { loading: false }
    if (reportResult.status === 'fulfilled' && reportResult.value && reportResult.value.data && Array.isArray(reportResult.value.data.items)) {
      const d = reportResult.value.data
      const knownMoney = value => value !== null && value !== undefined && value !== '' && Number.isFinite(Number(value))
      const costComplete = d.costComplete === true && knownMoney(d.totalCost)
      const showProfit = costComplete && d.profitComparable === true
        && [d.totalIncome, d.wage, d.netProfit].every(knownMoney)
      patch.costComplete = costComplete
      patch.showProfit = showProfit
      patch.splitIncome = d.profitComparable === false
      patch.profitUnavailableReason = showProfit ? '' : d.profitUnavailableReason || '利润口径待核实，请重新读取报表。'
      const profitView = { netProfitText: this._withheldMoney(showProfit ? d.netProfit : undefined) }
      for (const key of ['totalCost', 'orderRevenue', 'ticketRevenue', 'deliveryFee', 'floorFee', 'totalIncome', 'orderIncome', 'wage']) profitView[key + 'Text'] = this._moneyText(d[key])
      profitView.orderCountText = d.orderCount === null || d.orderCount === undefined || d.orderCount === '' ? '待核实' : d.orderCount
      const report = { ...d, items: d.items.map(item => ({ ...item, orderRevenueText: this._moneyText(item.revenue), ticketRevenueText: this._moneyText(item.ticketRevenue), revenueTotalText: this._moneyText(item.revenueTotal), costAmountText: this._moneyText(item.costAmount), soldQtyText: item.soldQty === null || item.soldQty === undefined ? '待核实' : item.soldQty })) }
      if (d.cooperation && Array.isArray(d.cooperation.rows)) report.cooperation = { ...d.cooperation, rows: d.cooperation.rows.map(item => ({ ...item, amountText: this._moneyText(item.amount) })) }
      Object.assign(patch, {
        report, profitView, profitHelpSections: buildProfitHelpSections(report), profitHelpAllExpanded: false, profitHelpVisible: false,
        cooperationView: buildCooperationView(d.cooperation, value => this._moneyText(value)),
        profitChart: showProfit ? buildProfitChart(d, profitView) : [],
        from: d.from || this.data.from, to: d.to || this.data.to
      })
    } else patch.reportError = '本期报表加载失败，请重试'
    this.setData(patch)
  },

  onDate(e) {
    // ⚠️ 手改日期就不再是"今日"视图了：`range` 必须清掉，否则标题还写着「今日收入与净利」
    // 而下面列的是别的期间 —— 一个自己会撒谎的标题比没有标题更糟。
    this.setData({ range: '', quickRange: null, [e.currentTarget.dataset.field]: e.detail.value }, () => this.load())
  },

  /** 快捷区间：只改 from/to 再拉一次，日期口径仍由服务端解释。 */
  _rangeDates(days) {
    const fmt = d => d.getFullYear() + '-' +
      String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0')
    const end = new Date()
    const start = new Date(end.getFullYear(), end.getMonth(), end.getDate())
    start.setDate(start.getDate() - days + 1)
    return { from: fmt(start), to: fmt(end) }
  },

  onQuickRange(e) {
    const days = Number(e.currentTarget.dataset.days)
    if (days === 0) {
      // 本月：交给服务端默认（清空 from/to）
      this.setData({ range: '', quickRange: 0, from: '', to: '' }, () => this.load())
      return
    }
    const dates = this._rangeDates(days)
    // days === 1 才是"今日"；其余区间必须把 range 清掉（同 onDate 的理由）
    this.setData({ range: days === 1 ? 'today' : '', quickRange: days, from: dates.from, to: dates.to }, () => this.load())
  },

  /**
   * 「利润怎么算」：正文用**服务端下发的 helpNote**（口径长文只有这一个来源）。
   *
   * [2026-09-26 产品反馈"利润里太罗嗦了"] 那段长解释以前是铺在报表上的（costBasisNote /
   * profitBasisNote 加起来三百多字），现在屏幕上是两条一行版，长文挪到这里，点开才看。
   * ⚠️ 前端**不要**自己抄一份口径文案进来（本仓铁律：口径只有一个来源，见 AGENTS §6）。
   */
  onShowHelp() {
    const groups = this.data.profitHelpSections || []
    if (!groups.length) {
      const note = this.data.report && this.data.report.helpNote
      // 老版本服务端没有 helpNote：给一句能用的最小说明，别弹一个空框
      showBusinessModal({ confirmColor: '#1264D6', cancelColor: '#637083',  confirmText: '知道了',
        title: '统计说明',
        content: note || '统计说明暂未加载，请重试。',
        showCancel: false
      })
      return
    }
    const expanded = !this.data.profitHelpAllExpanded
    this.setData({
      profitHelpAllExpanded: expanded,
      profitHelpSections: groups.map(group => ({ ...group, expanded }))
    })
  },

  onToggleIncomeDetails() { this.setData({ incomeDetailsExpanded: !this.data.incomeDetailsExpanded }) },
  onToggleAmountComparison() { this.setData({ amountComparisonExpanded: !this.data.amountComparisonExpanded }) },
  onToggleHelpVisible() {
    this.setData({ profitHelpVisible: !this.data.profitHelpVisible })
  },

  onToggleCooperationGroup(e) {
    const key = e.currentTarget.dataset.group
    const view = this.data.cooperationView
    this.setData({ cooperationView: { ...view, sections: view.sections.map(group => group.key === key ? { ...group, expanded: !group.expanded } : group) } })
  },

  onToggleHelpGroup(e) {
    const key = e.currentTarget.dataset.group
    const groups = this.data.profitHelpSections || []
    this.setData({
      profitHelpAllExpanded: false,
      profitHelpSections: groups.map(group => group.key === key ? { ...group, expanded: !group.expanded } : group)
    })
  }
})
