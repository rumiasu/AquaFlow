// 站长端「客户反馈」：普通反馈只读；退款关联记录只向责任站展示并允许追加说明。
//
// 为什么这个页面必须存在（别删）：
//   顾客端 `miniapp-user/utils/request.js` 在接口 `code=500` 时会弹「要把这个问题告诉水站吗？」，
//   用户点「上报」就真的 `POST /api/feedback` 落库。在后端 `GET /api/feedback/customers` 被接进本页
//   之前，水站侧**没有任何页面能读到它** —— 顾客以为"告诉水站了"，水站永远看不到。
//   「我的反馈」(`pages/feedback/index`) 原是**员工自己提的**反馈，与本页人群不重叠 —— 该页已于
//   2026-09-30 **删除**（零入口的孤儿页，登记见 `docs/audit/删除登记表.md`）。
//   注意别误会：后端 `POST /api/feedback` 仍在使用（顾客端 `code=500` 时的「上报」就调它，
//   本页读的正是它落库的数据）；删掉的只是那个没有入口的员工端页面。
//
// 三条口径（改这个页面时必须守住）：
//   1. 普通反馈保持只读。2026-10-08退款争议允许责任站登记结果并结案，客户可重提；
//      争议状态与实际退款分别处理，业务判权仍由原申请/原款确定。
//   2. **加载失败必须与"没有反馈"区分开**（本仓 §8.22：零覆盖端点的真实形态是"界面空白"，
//      与"确实没有"无法区分）。失败时把后端 `message` 原样打在页面上，并**清空 list**，
//      绝不能让"请求挂了"渲染成"本站暂无客户反馈"。
//   3. **所有展示文案都取自后端下发的字段，或由后端字段直接拼出；本文件不建任何
//      「分类→中文」映射表**（本仓明令禁止：两端各写一套映射出过下单 100% 失败的事故）。
//      后端 `feedback.category` 存的就是提交方写的原文（顾客端一键上报写死 '错误报告'），
//      **原样透传**即可，没有可映射的枚举。
//   4. **匿名由后端保证，不是由本页保证**（2026-09-18 产品裁定「客户反馈支持匿名提交」）。
//      匿名记录在 `GET /api/feedback/customers` 的响应里**根本没有** customerId / customerName
//      —— 后端 `FeedbackMapper.listCustomerFeedbackByStation` 用 `CASE WHEN f.anonymous = 1
//      THEN NULL` 在 SQL 层就置空了，前端无从显示。本页只按"有没有姓名/客户号"决定显示
//      「匿名顾客」这一句展示文案，**绝不读 `anonymous` 标志位自己拼身份文案**
//      （响应里该字段恒为 null，后端刻意不下发；详见 decorate 的注释）。
//
// 已知的数据边界（属后端问题、本页不掩盖）：
//   · `contact` 字段顾客端一键上报**根本不发**（`request.js` 的 data 只有 category 与 content），
//     因此这列对自动上报恒为空，只有顾客手动填过联系方式的反馈才有值。
//   · 普通反馈没有回复/处置状态；关联退款可追加说明，不能据此标记退款已完成。
//   · 自动上报（顾客端一键上报）**刻意保持实名**（顾客端 `utils/request.js` 的
//     `offerErrorReport` 不传 anonymous）：报障的价值在于站长能复现/追问；
//     只有顾客在「客服/反馈」页手动提交时才可能匿名。
//
// [2026-09-18 同日修复，两处已在后端改掉，改本页时别再把旧行为写回来]
//   · `FeedbackMapper.listCustomerFeedbackByStation` 原来只 `select f.*`、**不 JOIN customer**，
//     `customerName` 恒为 null，页面只能显示"客户 #<id>"。现在已补 `left join customer`，
//     姓名正常下发（下面的代码仍保留"没有名字就退回客户号"的兜底）。
//   · 原 SQL 用 `inner join customer_station_config` 判本站（只看"绑定行"），
//     于是**只在小程序下过单、没有绑定行的老顾客**提交的反馈不进这个列表 ——
//     界面显示"暂无客户反馈"，库里却有记录。现在归属口径改为与
//     `CustomerMapper.countCustomerOfStation` 一致的并集：绑定行 **或** 本站订单。
//
// 权限：后端 `FeedbackController.customerFeedback` 带 `@RequireRole({"STATION_MANAGER"})` ——
// 配送员调只会拿到一条 permission denied 的业务错误，所以本页进页就拦掉非站长。

const { getCustomerFeedbacks, appendRefundNote, getRefundDisputes, getRefundNotes, closeRefundDispute } = require('../../../api/feedback')
const noteIntent = require('../../../utils/refund-note-intent')
const disputeIntent = require('../../../utils/exception-action-intent')

Page({
  data: {
    loading: true,
    list: [],
    // type: loading | error | empty | list（四个状态互斥，wxml 不回退成"看起来是空的"）
    type: 'loading',
    // 接口失败时原样展示的后端 message（绝不翻译、绝不替换成"暂无"）
    errMsg: '',
    denied: false,
    deniedText: '',
    selectedFeedbackId: null, selectedRefund: null, noteContent: '', noteSubmitting: false, pendingRefundNote: false,
    disputes: [], disputesError: '', disputesLoading: false, disputesPage: 0, disputesMore: false, selectedDispute: null, disputeReady: false, disputeError: '',
    disputeResult: '', disputeSubmitting: false, pendingDispute: false
  },

  onLoad() {
    const app = getApp()
    if (!app.canAccessStationBusiness()) {
      this.setData({ denied: true, deniedText: '当前账号没有水站业务权限' })
      app.routeByRole(true)
      return
    }
    // `canAccessStationBusiness()` 对已绑定水站的配送员同样返回 true，但客户反馈汇总
    // 是站长专属（后端 @RequireRole("STATION_MANAGER")）。这里前置拦掉，
    // 免得配送员进来只收到一条与本页无关的权限错误。（同 pages/station-mgmt/customers/adjust/index.js）
    if (!app.isStationManager()) {
      this.setData({ denied: true, deniedText: '客户反馈仅站长可查看，请联系站长处理' })
    }
  },

  onShow() {
    if (this.data.denied) return
    this._noteEpoch = (this._noteEpoch || 0) + 1
    this.setData({ noteSubmitting: false, selectedFeedbackId: null, selectedRefund: null, selectedDispute: null, disputeSubmitting: false })
    this.loadData()
    this.loadDisputes()
  },
  onHide() { this._noteEpoch = (this._noteEpoch || 0) + 1 },
  onUnload() { this._noteEpoch = (this._noteEpoch || 0) + 1 },
  noteSession() {
    const app = getApp(), user = app.globalData.userInfo || {}
    return { user, staffId: user.staffId, stationId: user.stationId, role: user.role, epoch: this._noteEpoch }
  },
  currentNoteSession(s) {
    const app = getApp(), user = app.globalData.userInfo
    return !!app.globalData.isLogin && this._noteEpoch === s.epoch && user === s.user &&
      user.staffId === s.staffId && user.stationId === s.stationId && user.role === s.role && app.isStationManager()
  },
  async loadDisputes(page = 1) {
    if (this.data.denied || !getApp().isStationManager()) return
    const s = this.noteSession(), serial = this._disputeSerial = (this._disputeSerial || 0) + 1
    const current = () => this.currentNoteSession(s) && serial === this._disputeSerial
    this.setData({ disputesLoading: true, disputesError: '' })
    try {
      const response = await getRefundDisputes(page)
      if (!current()) return
      if (!Array.isArray(response.data)) throw new Error('退款争议未完整加载')
      const actor = 'STAFF:' + s.staffId + ':' + s.stationId
      const disputes = response.data.map(row => Object.assign({}, row, {
        disputeKey: row.refundType + ':' + row.refundId, retryClose: !!disputeIntent.read(actor, row.refundType + ':' + row.refundId, 'CLOSE')
      }))
      const merged = page === 1 ? disputes : this.data.disputes.concat(disputes)
      this.setData({ disputes: Array.from(new Map(merged.map(r => [r.disputeKey, r])).values()), disputesPage: page, disputesMore: response.data.length === 200 })
    } catch (err) { if (current()) this.setData({ disputes: page === 1 ? [] : this.data.disputes, disputesError: err.message || '争议没加载出来，请重试' }) }
    finally { if (current()) this.setData({ disputesLoading: false }) }
  },
  onRetryDisputes() { return this.loadDisputes() },
  onMoreDisputes() { if (!this.data.disputesLoading) return this.loadDisputes(this.data.disputesPage + 1) },
  async onOpenDispute(e) {
    if (this.data.disputeSubmitting || this.data.denied || this.data.disputesLoading || this.data.disputesError) return
    const { type, id } = e.currentTarget.dataset
    const row = this.data.disputes.find(r => r.refundType === type && String(r.refundId) === String(id))
    const s = this.noteSession()
    if (!row || !this.currentNoteSession(s)) return
    this.setData({ selectedDispute: row, disputeReady: false, disputeError: '', disputeResult: '', pendingDispute: false })
    try {
      const response = await getRefundNotes(type, Number(id))
      if (!this.currentNoteSession(s) || this.data.selectedDispute !== row) return
      const d = response.data
      if (!d || d.refundType !== type || Number(d.refundId) !== Number(id) || !d.dispute || !Array.isArray(d.dispute.actions)) throw new Error('争议历史未完整加载')
      const pending = disputeIntent.read('STAFF:' + s.staffId + ':' + s.stationId, type + ':' + id, 'CLOSE')
      this.setData({ selectedDispute: Object.assign({}, row, d.dispute), disputeReady: true,
        disputeResult: pending ? pending.reason : '', pendingDispute: !!pending })
    } catch (err) { if (this.currentNoteSession(s)) this.setData({ disputeReady: false, disputeError: err.message || '争议历史没加载出来，请重试' }) }
  },
  onDisputeResult(e) { this.setData({ disputeResult: e.detail.value }) },
  onRetryCloseDispute() {
    try {
      const s = this.noteSession(), row = this.data.selectedDispute
      const pending = disputeIntent.read('STAFF:' + s.staffId + ':' + s.stationId, row.refundType + ':' + row.refundId, 'CLOSE')
      if (!pending) return
      this.setData({ disputeResult: pending.reason }); return this.onCloseDispute()
    } catch (err) { wx.showToast({ title: err.message, icon: 'none' }) }
  },
  async onCloseDispute() {
    const row = this.data.selectedDispute, s = this.noteSession()
    if (this.data.disputeSubmitting || !row || !this.data.disputeReady || (!row.canClose && !this.data.pendingDispute) || !this.currentNoteSession(s)) return
    this.setData({ disputeSubmitting: true })
    let intent
    try {
      intent = disputeIntent.prepare('STAFF:' + s.staffId + ':' + s.stationId, row.refundType + ':' + row.refundId, 'CLOSE', this.data.disputeResult, row.version)
      this.setData({ pendingDispute: true })
      const response = await closeRefundDispute(Object.assign(disputeIntent.payload(intent), { refundType: row.refundType, refundId: Number(row.refundId) }))
      if (!this.currentNoteSession(s)) return
      const d = response.data
      if (!d || !d.id || d.refundType !== row.refundType || Number(d.refundId) !== Number(row.refundId) || d.action !== 'CLOSE') throw new Error('结案结果未确认，请原样重试')
      disputeIntent.clear(intent)
      this.setData({ selectedDispute: null, pendingDispute: false, disputeResult: '' })
      wx.showToast({ title: '结果已登记并结案', icon: 'success' }); await this.loadDisputes()
    } catch (err) {
      if (this.currentNoteSession(s)) {
        if (intent && err.businessRejected) { disputeIntent.clear(intent); this.setData({ pendingDispute: false, selectedDispute: null }); await this.loadDisputes() }
        wx.showToast({ title: err.message || '结案结果未确认，请重试', icon: 'none' })
      }
    } finally { if (this.currentNoteSession(s)) this.setData({ disputeSubmitting: false }) }
  },
  onOpenRefundNote(e) {
    if (this.data.noteSubmitting || this.data.denied || !getApp().isStationManager()) return
    const item = this.data.list.find(r => r.id === Number(e.currentTarget.dataset.id))
    if (!item || !item.refundType || !item.refundId) return
    try {
      const s = this.noteSession()
      if (!s.staffId || !s.stationId) throw new Error('请先确认当前站长身份')
      const ref = { refundType: item.refundType, refundId: Number(item.refundId) }
      const pending = noteIntent.read('STAFF:' + s.staffId + ':' + s.stationId, ref.refundType, ref.refundId)
      this.setData({ selectedFeedbackId: item.id, selectedRefund: ref, noteContent: pending ? pending.content : '', pendingRefundNote: !!pending })
    } catch (e) { wx.showToast({ title: e.message, icon: 'none' }) }
  },
  onNoteInput(e) { this.setData({ noteContent: e.detail.value }) },
  onRetryOriginalNote() {
    try {
      const s = this.noteSession(), ref = this.data.selectedRefund
      const pending = noteIntent.read('STAFF:' + s.staffId + ':' + s.stationId, ref.refundType, ref.refundId)
      if (!pending) return
      this.setData({ noteContent: pending.content })
      return this.onSubmitRefundNote()
    } catch (e) { wx.showToast({ title: e.message, icon: 'none' }) }
  },
  async onSubmitRefundNote() {
    if (this.data.noteSubmitting || !this.data.selectedRefund || this.data.denied || !getApp().isStationManager()) return
    const s = this.noteSession(), ref = this.data.selectedRefund
    if (!s.staffId || !s.stationId) return
    this.setData({ noteSubmitting: true })
    try {
      const intent = noteIntent.prepare('STAFF:' + s.staffId + ':' + s.stationId, ref.refundType, ref.refundId, this.data.noteContent, '')
      this.setData({ pendingRefundNote: true })
      const response = await appendRefundNote(noteIntent.payload(intent))
      if (!this.currentNoteSession(s)) return
      if (!response.data || !response.data.id || response.data.refundType !== ref.refundType || Number(response.data.refundId) !== ref.refundId) throw new Error('说明提交结果未确认，请原样重试')
      noteIntent.clear(intent)
      this.setData({ noteContent: '', selectedFeedbackId: null, selectedRefund: null, pendingRefundNote: false })
      wx.showToast({ title: '说明已保存', icon: 'success' })
      await this.loadData()
    } catch (e) {
      if (this.currentNoteSession(s)) wx.showToast({ title: e.message || '提交结果未确认，请重试', icon: 'none' })
    } finally { if (this.currentNoteSession(s)) this.setData({ noteSubmitting: false }) }
  },

  async onPullDownRefresh() {
    if (this.data.denied) {
      wx.stopPullDownRefresh()
      return
    }
    await this.loadData()
    wx.stopPullDownRefresh()
  },

  /** 失败态上的「重试」按钮（wxml 里不能调方法，事件必须落在真实处理器上）。 */
  onRetry() {
    this.loadData()
  },

  async loadData() {
    const epoch = this._noteEpoch, user = getApp().globalData.userInfo
    const serial = this._listSerial = (this._listSerial || 0) + 1
    const current = () => epoch === this._noteEpoch && serial === this._listSerial && user === getApp().globalData.userInfo
    this.setData({ loading: true, type: 'loading' })
    try {
      const res = await getCustomerFeedbacks()
      if (!current()) return
      const list = (res.data || []).map(item => this.decorate(item))
      this.setData({
        list,
        type: list.length ? 'list' : 'empty',
        errMsg: ''
      })
    } catch (err) {
      if (!current()) return
      // 静默失败会让站长以为"本站没有客户反馈"，从而把顾客真报上来的问题漏掉 —— 必须出声。
      // 同时清空 list：半截的旧数据 + 报错混在一起，比只显示错误更难判断。
      console.error('[CustomerFeedback] 加载失败:', err)
      this.setData({
        list: [],
        type: 'error',
        errMsg: (err && err.message) || '加载失败'
      })
    } finally {
      if (current()) this.setData({ loading: false })
    }
  },

  /**
   * 逐条算好展示字段（wxml 里不能调用 Page 方法，也不能 new Date）。
   *
   * ⚠️ 这里只做"把后端字段搬进模板"的事，**不产生任何业务文案映射**：
   * category / content 原样透传，contact 只做"有 / 没有"的判空后原样显示。
   */
  decorate(item) {
    const customerId = item && item.customerId
    const customerName = (item && item.customerName) || ''
    // [2026-09-18 匿名提交] whoText 只看**后端有没有下发姓名/客户号**，不看任何标志位：
    //   · 有姓名 → 姓名（可识别，能闭环）；
    //   · 没姓名但有客户号 → "客户 #<id>"（可识别，只是客户没填名字）；
    //   · 两个都没有 → "匿名顾客"（**这一句是展示文案，允许写死在页面里**）。
    // ⚠️ **不要改成读 `item.anonymous` 来拼身份文案**：后端在站长端列表里
    // 刻意不下发该标志（响应里恒为 null），身份脱敏是后端 SQL 层做的
    // （FeedbackMapper.listCustomerFeedbackByStation 对 anonymous=1 的记录把
    // customer_id 与姓名都置 NULL）。前端据标志位自造文案 = 把"后端保证拿不到身份"
    // 降级成"前端决定不显示"，那是假的匿名（改个 setData 就看见了）。
    // ⚠️ 也别把匿名记录渲染成"客户 #null"—— 那既难听又暴露"这里本该有个 id"。
    const who = customerName || (customerId ? ('客户 #' + customerId) : '匿名顾客')
    return {
      id: item.id,
      whoText: who,
      customerIdText: customerId ? ('客户ID ' + customerId) : '',
      categoryText: (item && item.category) ? item.category : '',
      refundType: item.refundType || null,
      refundId: item.refundId || null,
      refundObjectText: item.refundObjectText || '',
      authorText: item.authorText || '',
      content: (item && item.content) ? item.content : '',
      contactText: (item && item.contact) ? ('联系方式：' + item.contact) : '',
      // 后端没有下发"能不能联系上他"，所以不猜，只如实告诉站长这一条没有联系方式
      noContactText: (item && item.contact) ? '' : '未留联系方式',
      timeText: this.formatTime(item && item.createTime)
    }
  },

  formatTime(d) {
    if (!d) return ''
    // 后端下发 ISO 字符串（Web 层 Jackson3 未开 WRITE_DATES_AS_TIMESTAMPS）。
    // wxml 里不能 new Date / 调方法，所以统一在这里格式化；解析失败则退化为截断显示，
    // 绝不显示 "Invalid Date"。
    const t = new Date(String(d).replace(' ', 'T'))
    if (isNaN(t.getTime())) return String(d).replace('T', ' ').slice(0, 16)
    const pad = n => (n < 10 ? '0' + n : '' + n)
    return t.getFullYear() + '-' + pad(t.getMonth() + 1) + '-' + pad(t.getDate())
      + ' ' + pad(t.getHours()) + ':' + pad(t.getMinutes())
  }
})
