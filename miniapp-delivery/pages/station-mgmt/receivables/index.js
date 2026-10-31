const { showBusinessModal } = require('../../../utils/business-dialog')
// ⚠️ 直接用 utils/request，不引 api/station-mgmt.js 与 config/api.js：
// 那两个文件正被另一个工作流（商品图片库）改动，共用会让两边未提交的改动纠缠在一起。
// 路径常量写在本文件里，理由同上。
const { get, post, put } = require('../../../utils/request')
const { amountCents } = require('../../../utils/collection-confirmation')

const AR = '/api/manager/receivables'
const CREDIT_TERMS = '/api/manager/customers/'

// 只合计本次勾选的服务端订单金额；按分相加，不推算价格或客户余额。
const validId = value => (typeof value === 'number' || typeof value === 'string')
  && /^[1-9]\d*$/.test(String(value)) && Number.isSafeInteger(Number(value))
const amountText = cents => '¥' + Math.floor(cents / 100) + '.' + String(cents % 100).padStart(2, '0')
function receivableSession() {
  const app = getApp(), g = app.globalData || {}, u = g.userInfo || {}
  return { app, generation: app._loginGeneration || 0,
    identity: JSON.stringify([!!g.isLogin, !!g.accessToken, u.staffId, u.role, u.stationId, u.bindStatus]),
    allowed: !!(g.isLogin && g.accessToken && validId(u.staffId) && validId(u.stationId)
      && ['STATION_MANAGER', 'manager', 'MANAGER'].includes(u.role) && app.canAccessStationBusiness()) }
}
const sameSession = (a, b) => a && b && a.app === b.app && a.generation === b.generation && a.identity === b.identity
const canRegister = order => order && order.paymentStatus === 1 && order.settlementStatus === 1
  && [1, 2, 3, 4].includes(order.status)
function selectedReceivables(orders, customer) {
  const selected = (orders || []).filter(o => o.checked), rows = [], seen = new Map()
  let cents = 0
  for (const order of selected) {
    if (!validId(order.orderId) || !customer || !validId(customer.customerId)
      || String(order.customerId) !== String(customer.customerId) || !canRegister(order)) {
      return { error: '所选订单的客户或收款状态待核实，请重新加载明细' }
    }
    const amount = amountCents(order.totalAmount), id = Number(order.orderId)
    if (amount === null) return { error: '所选订单金额待核实，请重新加载明细' }
    if (seen.has(id)) {
      if (seen.get(id) !== amount) return { error: '同一订单金额不一致，请重新加载明细' }
      continue
    }
    seen.set(id, amount); cents += amount
    if (!Number.isSafeInteger(cents)) return { error: '所选合计超出可核实范围，请减少所选订单' }
    rows.push({ id, amountText: amountText(amount) })
  }
  return { rows, ids: rows.map(row => row.id), amountText: amountText(cents),
    signature: JSON.stringify(selected.map(o => [o.orderId, o.customerId, o.totalAmount, o.paymentStatus, o.settlementStatus, o.status])) }
}

/**
 * 站长端「应收账款」台账：待收款 + 账期 + 逾期。规格见 docs/design/20 §1 / §1.5。
 *
 * 口径（单笔金额、总览与核销结果均由服务端算）：
 *   · 待收款 = 支付状态为「待收款」且未取消的订单合计（与站长看板的待收款合计同源）
 *   · 账期   = 下单时**快照**进订单的应付日期（due_date）；没设账期的客户为 null（即时结清）
 *   · 逾期   = 有应付日期且已过期的那部分；**逾期只提醒、不改任何金额**
 *
 * 本页只做三件事：
 *   1. 读台账（总览 / 按客户 / 按订单），逾期的那部分标红；
 *   2. 在「全部待收 / 只看逾期」之间切换（[2026-09-27 走查 M05] 见 _applyOverdueFilter）；
 *   3. **核销**：把选中的挂账订单「收款 + 核销」一次做完（服务端同一事务）。
 *
 * ⚠️ 四条不要改的地方：
 *   · 不重算价格、客户余额或逾期金额；登记前只按整数分合计本次所选的已知订单金额。
 *   · 「只看逾期」**只过滤列表的行**（按服务端下发的 `overdueAmount` / `overdueOrderCount`），
 *     顶部三个总览数字**照旧是全量** —— 那三个数由服务端算好，前端不重算，
 *     所以筛选时在标题上说清"列表按逾期筛过"，不假装总额变了。
 *   · 核销的入参是**订单集合**，收款与核销由服务端一起完成 —— 前端不做两次调用，
 *     否则中间失败会留下「钱收了、账没销」。
 *   · 「已核销」的订单会被服务端跳过而不是报错（幂等重放），所以重复点不该当成失败。
 */
Page({
  data: {
    loading: true,
    loadError: '',
    /**
     * [2026-09-27 走查 M05] true = 只显示有逾期的客户。
     * 由 `?overdue=1` 打开（首页「逾期应收」待办带参进来，见 coordination/index.js 的 TODO_ROUTES）。
     * 原来是写死的 false 且**没有任何界面开关** —— 首页"逾期"入口与页面视图因此对不上：
     * 站长点的是逾期，落地看到的却是一张不做任何筛选的全部待收表（走查 M05）。
     */
    onlyOverdue: false,
    overview: null,
    /** 服务端下发的**全量**客户列表（`customers` 是它按筛选后的展示副本） */
    allCustomers: [],
    customers: [],
    // 选中的客户（null = 停留在客户列表）
    customer: null,
    orders: [],
    ordersLoading: false,
    ordersError: '',
    selectedCount: 0, selectedAmountText: '',
    settling: false,
    // 账期编辑弹层
    termsVisible: false,
    termsInput: '',
    termsSaving: false,
    // 重算未结账单（v60）：`POST /customers/{id}/credit-terms/recalculate`
    recalcBusy: false,
    // 验资画像（v60）：`GET /customers/{id}/risk`，null = 还没拉到 / 拉失败（不显示、不编造）
    risk: null
  },

  /**
   * [2026-09-27 走查 M05 修] `?overdue=1` = 首页「逾期应收」入口落地的视图。
   *
   * 原来入口只传页面路径、本页 `onlyOverdue` 写死 false ⇒ "点逾期、看到全部"，
   * 而页面上**没有任何筛选提示**，站长不知道自己在看哪个集合（走查 M05）。
   * 不认的参数一律退回"全部待收"（同 exceptions/staff 两页的口径）：拼错的参数不该把页面打成空白。
   */
  onLoad(options) {
    const overdue = options && (options.overdue === '1' || options.overdue === 1)
    if (overdue) this.setData({ onlyOverdue: true })
  },

  onShow() {
    this._hidden = false
    const app = getApp()
    if (!app.canAccessStationBusiness()) {
      app.routeByRole(true)
      return
    }
    const session = receivableSession()
    if (this._viewSession && !sameSession(this._viewSession, session)) {
      this.setData({ overview: null, allCustomers: [], customers: [], customer: null, orders: [],
        selectedCount: 0, selectedAmountText: '', ordersLoading: false, ordersError: '' })
    }
    this._viewSession = session
    this.load()
  },

  onHide() {
    this._hidden = true
    this._settleRevision = (this._settleRevision || 0) + 1
    if (this._settleFlight && this._settleFlight.cancelConfirmation) this._settleFlight.cancelConfirmation()
  },
  onUnload() {
    this.onHide()
    this._unloaded = true
    this._overviewRequest = (this._overviewRequest || 0) + 1
    this._ordersRequest = (this._ordersRequest || 0) + 1
  },

  /**
   * 按 `onlyOverdue` 从**全量**客户里挑出展示副本。
   *
   * 判据只用服务端下发的字段（`overdueAmount` / `overdueOrderCount`），前端不自己按日期算 ——
   * "哪些单算逾期"的算法只有服务端一份（due_date 快照 + 当前日期）。
   * ⚠️ 过滤**只影响客户列表**，不影响顶部三个总览数字（它们是全量口径，见文件头）。
   */
  _applyOverdueFilter() {
    const all = this.data.allCustomers || []
    const customers = this.data.onlyOverdue
      ? all.filter(c => Number(c.overdueAmount) > 0 || Number(c.overdueOrderCount) > 0)
      : all
    this.setData({ customers })
  },

  onFilterAll() {
    if (!this.data.onlyOverdue) return
    this._settleRevision = (this._settleRevision || 0) + 1
    this.setData({ onlyOverdue: false }, () => this._applyOverdueFilter())
  },

  onFilterOverdue() {
    if (this.data.onlyOverdue) return
    this._settleRevision = (this._settleRevision || 0) + 1
    this.setData({ onlyOverdue: true }, () => this._applyOverdueFilter())
  },

  /** json 里开了 enablePullDownRefresh，就必须有对应的处理函数，否则下拉只转圈不停。 */
  async onPullDownRefresh() {
    try {
      if (this.data.customer) {
        await Promise.all([this.load(), this.loadOrders()])
      } else {
        await this.load()
      }
    } finally {
      wx.stopPullDownRefresh()
    }
  },

  /** 从明细返回客户列表时也要刷新（核销后数字会变）。 */
  onBack() {
    this._ordersRequest = (this._ordersRequest || 0) + 1
    this._settleRevision = (this._settleRevision || 0) + 1
    this.setData({ customer: null, orders: [], selectedCount: 0, selectedAmountText: '', ordersLoading: false, ordersError: '' })
    this.load()
  },

  async load() {
    const session = receivableSession()
    const request = this._overviewRequest = (this._overviewRequest || 0) + 1
    const isCurrent = () => request === this._overviewRequest && !this._unloaded && sameSession(session, receivableSession())
    this.setData({ loading: true, loadError: '' })
    try {
      const res = await get(AR)
      if (!isCurrent()) return
      const d = res && res.data
      if (!d || !Array.isArray(d.customers)) throw new Error('待收账款数据不完整，请重试')
      const current = this.data.customer
      // 此接口返回完整、未分页的待收客户集合；成功响应中缺席才表示无待收。
      // 保留当前客户身份/账期，不按本次核销金额在前端做减法。
      const customer = current && (d.customers.find(c => c.customerId === current.customerId) ||
        Object.assign({}, current, { outstandingAmount: 0, orderCount: 0, creditOrderCount: 0,
          overdueAmount: 0, overdueOrderCount: 0, maxOverdueDays: 0, earliestDueDate: null }))
      // WXML 解析器不支持 {{ (''+x).length }}（Bad attr: unexpected token `.`），长度判断在这里算好再下发
      d.outstandingAmountLong = String(d.outstandingAmount == null ? '' : d.outstandingAmount).length > 11
      this.setData({
        overview: d,
        customer,
        // 全量留一份：筛选是**展示层**的事，切来切去不该重新请求（也不该动总览数字）
        allCustomers: d.customers || []
      })
      this._applyOverdueFilter()
    } catch (err) {
      if (!isCurrent()) return
      this.setData({ loadError: err.message || '待收账款没加载出来，请重试' })
      wx.showToast({ title: err.message || '台账加载失败', icon: 'none' })
    } finally {
      if (isCurrent()) this.setData({ loading: false })
    }
  },

  onRetryLoad() {
    return this.load()
  },

  async onPickCustomer(e) {
    if (this.data.loadError) return
    const id = Number(e.currentTarget.dataset.id)
    const customer = this.data.customers.find(c => c.customerId === id)
    if (!customer) return
    this._settleRevision = (this._settleRevision || 0) + 1
    this.setData({ customer, orders: [], selectedCount: 0, selectedAmountText: '' })
    await this.loadOrders()
  },

  async loadOrders() {
    const session = receivableSession()
    const c = this.data.customer
    if (!c) return
    const request = this._ordersRequest = (this._ordersRequest || 0) + 1
    const isCurrent = () => request === this._ordersRequest && !this._unloaded && sameSession(session, receivableSession()) && this.data.customer &&
      this.data.customer.customerId === c.customerId
    this.setData({ ordersLoading: true, ordersError: '' })
    try {
      const res = await get(AR + '/orders?customerId=' + c.customerId)
      if (!isCurrent()) return
      if (!res || !Array.isArray(res.data)) throw new Error('待收明细数据不完整，请重试')
      // 展示用的截断放在这里做：wxml 里不能调方法/函数，而 ISO 串直接渲染又太长
      const orders = res.data.map(o => Object.assign({}, o, {
        createDateText: String(o.createTime || '').slice(0, 10),
        dueDateText: o.dueDate ? String(o.dueDate).slice(0, 10) : '',
        overdue: o.overdueDays > 0, checked: false,
        amountText: amountCents(o.totalAmount) === null ? '金额待核实' : amountText(amountCents(o.totalAmount))
      }))
      this._ordersReadSession = session
      this.setData({ orders, selectedCount: 0, selectedAmountText: '' })
    } catch (err) {
      if (!isCurrent()) return
      this.setData({ ordersError: err.message || '待收明细没加载出来，请重试' })
      wx.showToast({ title: err.message || '明细加载失败', icon: 'none' })
    } finally {
      if (isCurrent()) this.setData({ ordersLoading: false })
    }
  },

  onRetryOrders() {
    return this.loadOrders()
  },

  /**
   * 勾选一张订单。
   *
   * 勾选态存在**订单行自己身上**（`checked`），不用 `selected[id]` 这种映射 ——
   * WXML 里按动态键取对象属性容易踩解析差异，而"勾了没反应"是静默失败，最难查。
   */
  onToggleOrder(e) {
    if (this.data.loadError || this.data.ordersError) return
    const id = Number(e.currentTarget.dataset.id)
    const orders = this.data.orders.map(o =>
      o.orderId === id && canRegister(o) ? Object.assign({}, o, { checked: !o.checked }) : o)
    this.updateSelection(orders)
  },

  updateSelection(orders) {
    this._settleRevision = (this._settleRevision || 0) + 1
    const selection = selectedReceivables(orders, this.data.customer)
    this.setData({ orders, selectedCount: new Set(orders.filter(o => o.checked).map(o => String(o.orderId))).size,
      selectedAmountText: selection.error ? '金额待核实' : selection.amountText })
  },

  onSelectAll() {
    if (this.data.loadError || this.data.ordersError) return
    const orders = this.data.orders || []
    // 只要还有没勾的就全勾上，全勾了就全取消
    const all = orders.some(o => canRegister(o) && !o.checked)
    const next = orders.map(o => Object.assign({}, o, { checked: all && canRegister(o) }))
    this.updateSelection(next)
  },

  /**
   * 核销选中的订单：服务端「收款 + 核销」同一事务。
   * 提交前合计所选订单的已知金额；完成回显仍以服务端 settledAmount 为准。
   */
  async onSettle() {
    if (this.data.loadError || this.data.ordersError) {
      wx.showToast({ title: '数据尚未成功刷新，请先重新加载', icon: 'none' })
      return
    }
    if (this.data.settling || this._settleFlight) return
    const c = this.data.customer && { ...this.data.customer }
    const selection = selectedReceivables(this.data.orders, c), session = receivableSession()
    if (!session.allowed || this._hidden || this._unloaded
      || this._viewSession && !sameSession(this._viewSession, session)
      || this._ordersReadSession && !sameSession(this._ordersReadSession, session)) {
      wx.showToast({ title: '身份或权限已变化，请返回后重新核对', icon: 'none' }); return
    }
    if (selection.error) { wx.showToast({ title: selection.error, icon: 'none' }); return }
    if (!selection.ids.length) {
      wx.showToast({ title: '请先勾选要核销的订单', icon: 'none' })
      return
    }

    const flight = this._settleFlight = {}, revision = this._settleRevision || 0
    const overviewRequest = this._overviewRequest, ordersRequest = this._ordersRequest, onlyOverdue = this.data.onlyOverdue
    const current = () => !this._hidden && !this._unloaded && sameSession(session, receivableSession()) && receivableSession().allowed
    const confirmationCurrent = () => {
      const next = selectedReceivables(this.data.orders, this.data.customer)
      return current() && !this.data.loadError && !this.data.ordersError && !this.data.ordersLoading
        && revision === (this._settleRevision || 0) && overviewRequest === this._overviewRequest && ordersRequest === this._ordersRequest
        && this.data.onlyOverdue === onlyOverdue && this.data.customer && this.data.customer.customerId === c.customerId
        && this.data.customer.customerName === c.customerName && !next.error && next.signature === selection.signature
    }
    this.setData({ settling: true })
    try {
      const confirmed = await new Promise(resolve => {
        flight.cancelConfirmation = () => resolve(false)
        showBusinessModal({ confirmColor: '#1264D6', cancelColor: '#637083',  title: '确认登记已收款', confirmText: '已收登记', cancelText: '再核对',
          content: '客户：' + (c.customerName || '客户 #' + c.customerId) + '（#' + c.customerId + '）\n'
            + selection.rows.map(row => '订单 #' + row.id + '：' + row.amountText).join('\n')
            + '\n所选 ' + selection.ids.length + ' 单，合计 ' + selection.amountText
            + '\n\n请确认上述款项已实际收到。登记后会记为已收款并销掉这些应收账，不会代客户付款。',
          success: result => resolve(!!result.confirm), fail: () => resolve(false) })
      })
      delete flight.cancelConfirmation
      if (!confirmed) return
      if (!confirmationCurrent()) { wx.showToast({ title: '所选订单或查看条件已变化，请重新核对后登记', icon: 'none' }); return }
      const res = await post(AR + '/settle', { customerId: c.customerId, orderIds: selection.ids })
      if (!current()) return
      const d = res && res.data
      if (!d || !Number.isSafeInteger(d.settledCount) || d.settledCount < 0 || amountCents(d.settledAmount) === null) {
        throw Object.assign(new Error('收款登记结果暂未查到'), { malformedResponse: true })
      }
      this.setData({ settlementReceipt: '已登记 ' + d.settledCount + ' 单 · ¥' + d.settledAmount + (d.alreadySettledCount ? '；此前已登记 ' + d.alreadySettledCount + ' 单已跳过' : '') })
      wx.showToast({ title: '已登记收款', icon: 'success' })
      this.setData({ selectedCount: 0, selectedAmountText: '' })
      await Promise.all([this.load(), this.loadOrders()])
    } catch (err) {
      if (!current()) return
      const unknown = err && (err.requestMayHaveOccurred === true || err.resultUnknown || err.malformedResponse
        || Number(err.statusCode) >= 500 || Number(err.backendCode) >= 500)
      if (unknown) {
        this._settleRevision = (this._settleRevision || 0) + 1
        this.setData({ orders: this.data.orders.map(o => ({ ...o, checked: false })), selectedCount: 0, selectedAmountText: '',
          ordersError: '登记结果待核实，请重新加载明细；核实前请勿重复登记，也不要再次向客户收款。' })
        showBusinessModal({ confirmColor: '#1264D6', cancelColor: '#637083',  confirmText: '知道了', title: '登记结果待核实', showCancel: false,
          content: '此次操作只登记已收到的钱，不会向客户扣款。回应未能确认，登记可能已完成，请勿再次向客户收款。\n\n已清除勾选，正在重新读取台账与待收明细核实，不会自动重发登记。' })
        await Promise.all([this.load(), this.loadOrders()])
        const verificationError = this.data.ordersError || this.data.loadError
        if (current() && verificationError) this.setData({ ordersError: '登记结果仍待核实。' + verificationError
          + '。核实前请勿重复登记，也不要再次向客户收款。' })
      } else {
        // 明确拒绝保留原原因；请求可能执行的异常只进入上面的只读核实分支。
        showBusinessModal({ confirmColor: '#1264D6', cancelColor: '#637083',  confirmText: '知道了', title: '核销未完成', content: err.message || '请稍后重试', showCancel: false })
      }
    } finally {
      if (this._settleFlight === flight) { this._settleFlight = null; if (!this._unloaded) this.setData({ settling: false }) }
    }
  },

  /* ---------------- 账期（客户级，只写 due_days 一列） ---------------- */

  onEditTerms() {
    const c = this.data.customer
    if (!c) return
    this.setData({
      termsVisible: true,
      // 留空 = 清除账期（即时结清）；0 与留空等价，UI 上不给站长制造"0 天"这种含糊值
      termsInput: c.dueDays === null || c.dueDays === undefined ? '' : String(c.dueDays),
      // 验资（v60）：GET /customers/{id}/risk 下发等级 / 可赊额度 / 已用 / 逾期
      risk: null
    })
    this.loadRisk(c.customerId)
  },

  /**
   * 拉该客户在本站的**信用画像**（验资）—— "设不设账期、设多少"正是要看它。
   *
   * ⚠️ 全部字段由服务端现算下发（`CustomerRiskService.assess`），前端**一个数都不算**：
   * 额度是按该客户近 90 天消费规模推的、逾期是按订单应付日期算的 —— 在这里重算必然分叉。
   * 拿不到就什么都不显示（留 `risk: null`），不编造一个"正常"。
   */
  async loadRisk(customerId) {
    try {
      const res = await get(CREDIT_TERMS + customerId + '/risk')
      if (res && res.code === 0 && res.data) {
        this.setData({ risk: res.data })
      }
    } catch (err) {
      console.warn('[receivables] 取信用画像失败（不显示验资块）:', err && err.message)
    }
  },

  /**
   * **重算未结账单**（v60）：把该客户在本站「还没结清」的挂账单按当前账期重算应付日期。
   *
   * ⚠️ 为什么必须有这个按钮：`orders.due_date` 是**下单时快照、之后只读**（与金额/地址快照同源），
   * 所以站长刚改完账期会发现"老单没变" —— 那是设计如此，不是坏了。想把老单也改过来，
   * 只能走这个显式动作（服务端每张被改动的单都会在 `special_note` 留痕）。
   */
  async onRecalcTerms() {
    if (this.data.recalcBusy) return
    const c = this.data.customer
    const res = await new Promise((resolve) => {
      showBusinessModal({ confirmColor: '#1264D6', cancelColor: '#637083',
        title: '重算未结账单',
        content: '把「还没结清」的挂账单按当前账期重算应付日期。\n\n已付款或已取消的单不受影响；每张被改动的单都会留下记录。',
        confirmText: '重算',
        success: resolve,
        fail: () => resolve({ confirm: false })
      })
    })
    if (!res || !res.confirm) return

    this.setData({ recalcBusy: true })
    try {
      const r = await post(CREDIT_TERMS + c.customerId + '/credit-terms/recalculate')
      const n = r && r.data ? r.data.changedCount : 0
      wx.showToast({ title: n > 0 ? ('已重算 ' + n + ' 张单') : '没有需要重算的单', icon: 'none' })
      this.setData({ termsVisible: false })
      this.load()
      this.loadOrders()
    } catch (err) {
      // 现结客户没有可重算的挂账单时，服务端回的是业务错误（而不是 0）—— 原样显示，别静默
      wx.showToast({ title: err.message || '重算失败', icon: 'none' })
    } finally {
      this.setData({ recalcBusy: false })
    }
  },

  onTermsInput(e) {
    this.setData({ termsInput: e.detail.value })
  },

  onCloseTerms() {
    this.setData({ termsVisible: false })
  },

  async onSaveTerms() {
    if (this.data.termsSaving) return
    const c = this.data.customer
    const raw = String(this.data.termsInput || '').trim()
    let dueDays = null
    if (raw !== '') {
      const n = Number(raw)
      if (isNaN(n) || n < 0 || n > 365) {
        wx.showToast({ title: '账期天数应在 0 ~ 365 之间', icon: 'none' })
        return
      }
      dueDays = n
    }

    this.setData({ termsSaving: true })
    try {
      const res = await put(CREDIT_TERMS + c.customerId + '/credit-terms', { dueDays })
      wx.showToast({ title: '已保存', icon: 'success' })
      this.setData({ termsVisible: false })
      // 账期只影响**之后**下的单（历史单的 due_date 是快照），所以这里刷新的是客户行的账期文案
      const dueDaysSaved = (res.data || {}).dueDays
      const customers = this.data.customers.map(x =>
        x.customerId === c.customerId ? Object.assign({}, x, { dueDays: dueDaysSaved }) : x)
      this.setData({
        customers,
        customer: Object.assign({}, c, { dueDays: dueDaysSaved })
      })
    } catch (err) {
      wx.showToast({ title: err.message || '保存失败', icon: 'none' })
    } finally {
      this.setData({ termsSaving: false })
    }
  },

  /** 逾期只是提示：这里没有任何"按逾期加收/罚款"的入口。 */
  onShowTermsHelp() {
    showBusinessModal({ confirmColor: '#1264D6', cancelColor: '#637083',  confirmText: '知道了',
      title: '账期怎么用',
      content: '账期是"月结多少天"，只影响之后下的单：下单时按它算出应付日期并快照进订单，' +
        '事后改账期不会改动历史订单的到期日。\n\n只有现金（货到付款）单才有应付日期；' +
        '微信即时到账、水票下单即视同已付，两者都不产生账期。\n\n留空表示未设账期（即时结清）。',
      showCancel: false
    })
  }
})
