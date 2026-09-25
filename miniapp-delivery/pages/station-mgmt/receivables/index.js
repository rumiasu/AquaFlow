// ⚠️ 直接用 utils/request，不引 api/station-mgmt.js 与 config/api.js：
// 那两个文件正被另一个工作流（商品图片库）改动，共用会让两边未提交的改动纠缠在一起。
// 路径常量写在本文件里，理由同上。
const { get, post, put } = require('../../../utils/request')

const AR = '/api/manager/receivables'
const CREDIT_TERMS = '/api/manager/customers/'

/**
 * 站长端「应收账款」台账：待收款 + 账期 + 逾期。规格见 docs/design/20 §1 / §1.5。
 *
 * 口径（全部由服务端算，**前端不重算任何金额**）：
 *   · 待收款 = 支付状态为「待收款」且未取消的订单合计（与站长看板的待收款合计同源）
 *   · 账期   = 下单时**快照**进订单的应付日期（due_date）；没设账期的客户为 null（即时结清）
 *   · 逾期   = 有应付日期且已过期的那部分；**逾期只提醒、不改任何金额**
 *
 * 本页只做两件事：
 *   1. 读台账（总览 / 按客户 / 按订单），逾期的那部分标红；
 *   2. **核销**：把选中的挂账订单「收款 + 核销」一次做完（服务端同一事务）。
 *
 * ⚠️ 三条不要改的地方：
 *   · **不要在这里自己算钱**（合计、逾期金额一律用服务端下发的值）。两条算法迟早算出两个数。
 *   · 核销的入参是**订单集合**，收款与核销由服务端一起完成 —— 前端不做两次调用，
 *     否则中间失败会留下「钱收了、账没销」。
 *   · 「已核销」的订单会被服务端跳过而不是报错（幂等重放），所以重复点不该当成失败。
 */
Page({
  data: {
    loading: true,
    onlyOverdue: false,
    overview: null,
    customers: [],
    // 选中的客户（null = 停留在客户列表）
    customer: null,
    orders: [],
    ordersLoading: false,
    selectedCount: 0,
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
      if (this.data.customer) {
        await this.loadOrders()
      } else {
        await this.load()
      }
    } finally {
      wx.stopPullDownRefresh()
    }
  },

  /** 从明细返回客户列表时也要刷新（核销后数字会变）。 */
  onBack() {
    this.setData({ customer: null, orders: [], selectedCount: 0 })
    this.load()
  },

  async load() {
    this.setData({ loading: true })
    try {
      const res = await get(AR)
      const d = res.data || {}
      this.setData({
        overview: d,
        customers: d.customers || []
      })
    } catch (err) {
      wx.showToast({ title: err.message || '台账加载失败', icon: 'none' })
    } finally {
      this.setData({ loading: false })
    }
  },

  async onPickCustomer(e) {
    const id = Number(e.currentTarget.dataset.id)
    const customer = this.data.customers.find(c => c.customerId === id)
    if (!customer) return
    this.setData({ customer, orders: [], selectedCount: 0 })
    await this.loadOrders()
  },

  async loadOrders() {
    const c = this.data.customer
    if (!c) return
    this.setData({ ordersLoading: true })
    try {
      const res = await get(AR + '/orders?customerId=' + c.customerId)
      // 展示用的截断放在这里做：wxml 里不能调方法/函数，而 ISO 串直接渲染又太长
      const orders = (res.data || []).map(o => Object.assign({}, o, {
        createDateText: String(o.createTime || '').slice(0, 10),
        dueDateText: o.dueDate ? String(o.dueDate).slice(0, 10) : '',
        overdue: o.overdueDays > 0
      }))
      this.setData({ orders })
    } catch (err) {
      wx.showToast({ title: err.message || '明细加载失败', icon: 'none' })
    } finally {
      this.setData({ ordersLoading: false })
    }
  },

  /**
   * 勾选一张订单。
   *
   * 勾选态存在**订单行自己身上**（`checked`），不用 `selected[id]` 这种映射 ——
   * WXML 里按动态键取对象属性容易踩解析差异，而"勾了没反应"是静默失败，最难查。
   */
  onToggleOrder(e) {
    const id = Number(e.currentTarget.dataset.id)
    const orders = this.data.orders.map(o =>
      o.orderId === id ? Object.assign({}, o, { checked: !o.checked }) : o)
    this.setData({ orders, selectedCount: orders.filter(o => o.checked).length })
  },

  onSelectAll() {
    const orders = this.data.orders || []
    // 只要还有没勾的就全勾上，全勾了就全取消
    const all = this.data.selectedCount < orders.length
    const next = orders.map(o => Object.assign({}, o, { checked: all }))
    this.setData({ orders: next, selectedCount: all ? next.length : 0 })
  },

  /**
   * 核销选中的订单：服务端「收款 + 核销」同一事务。
   * 回显用服务端返回的 settledAmount，不用前端累加（少一处会算出两个数的地方）。
   */
  async onSettle() {
    if (this.data.settling) return
    const c = this.data.customer
    const ids = (this.data.orders || []).filter(o => o.checked).map(o => o.orderId)
    if (!ids.length) {
      wx.showToast({ title: '请先勾选要核销的订单', icon: 'none' })
      return
    }

    this.setData({ settling: true })
    try {
      const res = await post(AR + '/settle', { customerId: c.customerId, orderIds: ids })
      const d = res.data || {}
      wx.showModal({
        title: '核销完成',
        content: '已核销 ' + d.settledCount + ' 单，金额 ¥' + d.settledAmount +
          (d.collectedCount ? '\n本次同时记账收款 ' + d.collectedCount + ' 单' : '') +
          (d.alreadySettledCount ? '\n另有 ' + d.alreadySettledCount + ' 单此前已核销（已跳过）' : ''),
        showCancel: false
      })
      this.setData({ selectedCount: 0 })
      await this.loadOrders()
    } catch (err) {
      // 服务端整批回滚并说明是哪一单、为什么（不会出现"部分核销"）
      wx.showModal({ title: '核销未完成', content: err.message || '请稍后重试', showCancel: false })
    } finally {
      this.setData({ settling: false })
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
      wx.showModal({
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
    wx.showModal({
      title: '账期怎么用',
      content: '账期是"月结多少天"，只影响之后下的单：下单时按它算出应付日期并快照进订单，' +
        '事后改账期不会改动历史订单的到期日。\n\n只有现金（货到付款）单才有应付日期；' +
        '微信即时到账、水票下单即视同已付，两者都不产生账期。\n\n留空表示未设账期（即时结清）。',
      showCancel: false
    })
  }
})
