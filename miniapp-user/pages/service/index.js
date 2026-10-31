const { showBusinessModal } = require('../../utils/business-dialog')
const { createDraftGuard } = require('../../utils/draft-guard')
const { submitFeedback, getMyFeedback, appendRefundNote, getRefundNotes, getRefundOptions, getRefundDisputes, openRefundDispute } = require('../../api/feedback')
const noteIntent = require('../../utils/refund-note-intent')
const disputeIntent = require('../../utils/exception-action-intent')
const { getCustomerId, captureSession, isCurrentSession } = require('../../utils/token')
const { stationStorage } = require('../../utils/storage')
const { getStationPublicPhone } = require('../../api/station')

function validDraft(draft) { return !!draft && typeof draft.content === 'string' && typeof draft.contact === 'string' && typeof draft.disputeReason === 'string' && draft.disputeReason.length <= 1000 && draft.content.length <= 1000 && draft.contact.length <= 500 && ['配送服务', '商品与水', '水桶押金', '其他'].includes(draft.category) && typeof draft.anonymous === 'boolean' }

// 只清理错误的展示文字；原对象的 code、resultUnknown、businessRejected 均不改动。
function viewError(error, fallback = '暂时无法完成，请稍后重试') {
  const text = error && typeof error.message === 'string' ? error.message.trim() : ''
  return /[\u3400-\u9fff]/.test(text)
    && !/(?:Exception|Error:|\bat\s+\S+\(|\bSQL\b|https?:\/\/|request:fail|errno|stack|[A-Z][A-Z_]{5,})/.test(text)
    ? text : fallback
}

Page({
  data: {
    isLogin: false, serviceTab: 'form', showRefundNote: false, showDisputeHistory: false, draftAvailable: false, draftNotice: '', draftContextMismatch: false,
    serviceInfo: {
      phone: '',
      stationName: ''
    },
    // 反馈表单
    category: '配送服务',
    content: '',
    contact: '',
    // 匿名提交开关（v46，产品裁定 2026-09-18）：**默认 false = 实名**。
    // ⚠️ 这里只是把用户的意愿原样传给后端；**身份脱敏是后端做在查询 SQL 上的**
    // （FeedbackMapper.listCustomerFeedbackByStation 对 anonymous=1 的记录把 customer_id
    //   与姓名都置 NULL）。前端**不得**自己拼"匿名顾客 / 匿名用户"之类的身份文案 ——
    // 前端能隐藏的东西，前端也能一眼看穿（改个 setData、翻个接口就没了）。
    anonymous: false,
    // 历史
    history: [],
    showHistory: false,
    // ===== 失败标记（[2026-09-20 真机联调]，空串 = 一切正常）=====
    // phoneError：水站公开电话**没查到**的原因（空串 = 查到了，或本地缓存里本来就有）。
    //   为什么要单独一个字段：下面 onCallPhone 原来无论什么原因都只说「暂未获取到客服电话，
    //   请稍后再试」—— 把"接口失败"与"这个水站确实没登记电话"混成一句，客户只会反复重试
    //   （或者以为水站不接电话）。两者必须分开说（AGENTS §8.22 的"失败被当成事实"）。
    // historyError：反馈历史没加载出来的原因（空串 = 正常）。原来只 console.warn，
    //   区块直接不渲染 —— 提过反馈的客户会以为自己的记录丢了。
    phoneError: '',
    phoneLoading: false, phoneLoaded: false, stationSelected: false,
    historyError: '',
    historyLoading: false, historyLoaded: false, submitError: '',
    submitting: false,
    refundRef: null, refundReady: false, refundError: '', refundOptions: [], refundOptionsError: '',
    refundOptionsMore: false, refundOptionsPage: 0, pendingRefundNote: false,
    refundOptionsLoading: false, refundOptionsLoaded: false,
    refundLoading: false, refundStale: false,
    dispute: null, disputeReason: '', disputeSubmitting: false, pendingDispute: false,
    myDisputes: [], myDisputesError: '', myDisputesLoading: false, myDisputesPage: 0, myDisputesMore: false
  },

  draftScope() {
    const session = captureSession(), ref = this.data.refundRef
    if (!session.loggedIn || !/^[1-9]\d*$/.test(String(session.customerId || '')) || !Number.isSafeInteger(Number(session.customerId))) return null
    const station = ref ? 'personal' : stationStorage.getId()
    if (!ref && (!/^[1-9]\d*$/.test(String(station || '')) || !Number.isSafeInteger(Number(station)))) return null
    return { app: 'customer', actor: session.customerId, station, route: 'service', entity: ref ? ref.refundType + ':' + ref.refundId : 'feedback', generation: session.epoch }
  },
  hasDraftInput() { return !!(this.data.content || this.data.contact || this.data.disputeReason || this.data.category !== '配送服务' || this.data.anonymous) },
  initDraft() {
    this._draftGuard = createDraftGuard(() => this.draftScope()); this._draftToken = this._draftGuard.begin(); this._draftOwned = false
    const result = this._draftGuard.read(this._draftToken), draft = result.draft
    this._draftSaved = result.state === 'saved' && validDraft(draft) ? draft : null
    this._draftLast = null
    this._draftBlocked = result.state !== 'empty'
    this.setData({ draftAvailable: !!this._draftSaved, draftContextMismatch: false,
      draftNotice: result.state === 'unknown' || result.state === 'stale' || (result.state === 'saved' && !this._draftSaved) ? '草稿暂时无法核实，当前输入尚未确认保存。' : '' })
  },
  saveDraft() {
    if (!this._draftGuard || !this._draftToken || this.data.draftContextMismatch) { this.setData({ draftNotice: '草稿尚未确认保存，请核对登录及水站。' }); return }
    if (this._draftBlocked) { this.setData({ draftNotice: '请先处理原草稿；当前输入尚未确认保存。' }); return }
    const existing = this._draftGuard.read(this._draftToken)
    if (existing.state === 'unknown' || existing.state === 'stale') { this.setData({ draftNotice: '草稿尚未确认保存，请保留当前输入并重试。' }); return }
    if (existing.state === 'saved' && (!this._draftOwned || JSON.stringify(existing.draft) !== JSON.stringify(this._draftLast))) {
      this._draftBlocked = true; this._draftOwned = false; this._draftSaved = validDraft(existing.draft) ? existing.draft : null
      this.setData({ draftAvailable: !!this._draftSaved, draftNotice: this._draftSaved ? '原草稿已变化，请先处理；当前输入尚未确认保存。' : '草稿暂时无法核实，当前输入尚未确认保存。' }); return
    }
    const draft = { category: this.data.category, content: this.data.content, contact: this.data.contact, anonymous: this.data.anonymous, disputeReason: this.data.disputeReason }
    const result = this._draftGuard.save(this._draftToken, draft)
    if (result.state === 'saved') { this._draftOwned = true; this._draftLast = draft; this.setData({ draftNotice: '' }) }
    else this.setData({ draftNotice: '草稿尚未确认保存，请保留当前输入并重试。' })
  },
  onRestoreDraft() {
    if (!this._draftSaved || this.data.submitting || this.data.disputeSubmitting || this.data.pendingRefundNote || this.data.pendingDispute || this.data.draftContextMismatch || (this.data.refundRef && !this.data.refundReady)) return
    const draft = this._draftSaved, token = this._draftToken, session = captureSession(), epoch = this._noteEpoch
    const restore = () => {
      if (this._draftHidden || this._unloaded || epoch !== this._noteEpoch || !isCurrentSession(session) || !this._draftGuard || this.data.pendingRefundNote || this.data.pendingDispute) return
      const current = this._draftGuard.read(token)
      if (current.state !== 'saved' || JSON.stringify(current.draft) !== JSON.stringify(draft)) { this.setData({ draftNotice: '原草稿已变化，请重新进入后核对。' }); return }
      this._draftOwned = true; this._draftBlocked = false; this._draftLast = draft; this._draftSaved = null
      this.setData({ category: draft.category, content: draft.content, contact: draft.contact, anonymous: draft.anonymous, disputeReason: draft.disputeReason,
        showRefundNote: !!this.data.refundRef && !!(draft.content || draft.contact), draftAvailable: false, draftNotice: '' }, () => this.updateLeaveWarning())
    }
    if (this.hasDraftInput()) return showBusinessModal({ title: '恢复原输入？', content: '恢复会替换当前填写的内容。', confirmText: '恢复', cancelText: '继续填写', success: res => { if (res.confirm) restore() } })
    restore()
  },
  onDiscardDraft() {
    if (!this._draftSaved || this.data.submitting || this.data.disputeSubmitting || this.data.pendingRefundNote || this.data.pendingDispute || this.data.draftContextMismatch) return
    const token = this._draftToken, session = captureSession(), epoch = this._noteEpoch, expected = this._draftSaved
    showBusinessModal({ title: '放弃原草稿？', content: '放弃后原草稿将不再保留。', confirmText: '放弃', cancelText: '保留', success: res => {
      if (!res.confirm || this._draftHidden || this._unloaded || epoch !== this._noteEpoch || !isCurrentSession(session)) return
      const current = this._draftGuard.read(token)
      if (current.state !== 'saved' || JSON.stringify(current.draft) !== JSON.stringify(expected)) { this.setData({ draftNotice: '原草稿已变化，请重新进入后核对。' }); return }
      const result = this._draftGuard.clear(token)
      if (result.state !== 'empty') { this.setData({ draftNotice: '草稿尚未确认清除，请重试。' }); return }
      this._draftBlocked = false; this._draftSaved = null; this.setData({ draftAvailable: false, draftNotice: '' })
      if (this.hasDraftInput()) this.saveDraft()
    } })
  },
  clearOwnedDraft() {
    if (!this._draftOwned || !this._draftGuard) return true
    const current = this._draftGuard.read(this._draftToken)
    if (current.state === 'empty') { this._draftOwned = false; this._draftLast = null; return true }
    if (current.state !== 'saved' || JSON.stringify(current.draft) !== JSON.stringify(this._draftLast)) {
      this._draftOwned = false; this._draftBlocked = true; this._draftSaved = current.state === 'saved' && validDraft(current.draft) ? current.draft : null
      this.setData({ draftAvailable: !!this._draftSaved, draftNotice: '草稿已变化或暂时无法核实，请重新核对。' }); return false
    }
    const result = this._draftGuard.clear(this._draftToken)
    if (result.state === 'empty') { this._draftOwned = false; this._draftLast = null; return true }
    this.setData({ draftNotice: '草稿尚未确认清除，请保留当前输入并重试。' }); return false
  },
  afterConfirmedInput(submitted) {
    // 只有与已确认请求完全相同的字段可从未恢复草稿移除，其他未提交输入必须保留。
    if (this._draftSaved && submitted && this._draftGuard) {
      const current = this._draftGuard.read(this._draftToken)
      if (current.state === 'saved' && JSON.stringify(current.draft) === JSON.stringify(this._draftSaved)
          && Object.keys(submitted).every(k => this._draftSaved[k] === submitted[k])) {
        const remaining = Object.assign({}, this._draftSaved)
        Object.keys(submitted).forEach(k => { remaining[k] = '' })
        const hasRemaining = !!(remaining.content || remaining.contact || remaining.disputeReason)
        const result = hasRemaining ? this._draftGuard.save(this._draftToken, remaining) : this._draftGuard.clear(this._draftToken)
        if ((hasRemaining && result.state === 'saved') || (!hasRemaining && result.state === 'empty')) {
          this._draftSaved = hasRemaining ? remaining : null; this._draftBlocked = hasRemaining
          this.setData({ draftAvailable: hasRemaining })
        } else this.setData({ draftNotice: '已提交，但原草稿尚未确认更新。' })
      }
    }
    if (this.hasDraftInput()) { if (this._draftOwned) this.saveDraft() }
    else this.clearOwnedDraft()
    this.updateLeaveWarning()
  },
  updateLeaveWarning() {
    const dirty = !!(this.data.content || this.data.contact || this.data.disputeReason)
    if (dirty && wx.enableAlertBeforeUnload) wx.enableAlertBeforeUnload({ message: '填写内容尚未提交，确定离开吗？' })
    else if (!dirty && wx.disableAlertBeforeUnload) wx.disableAlertBeforeUnload()
  },
  onServiceTab(e) {
    const tab = e.currentTarget.dataset.tab
    if (tab === 'form' || tab === 'records') { if (tab !== this.data.serviceTab && wx.hideKeyboard) wx.hideKeyboard({}); this.setData({ serviceTab: tab }) }
  },
  onToggleRefundNote() { if (this.data.showRefundNote && wx.hideKeyboard) wx.hideKeyboard({}); this.setData({ showRefundNote: !this.data.showRefundNote }) },
  onToggleDisputeHistory() { this.setData({ showDisputeHistory: !this.data.showDisputeHistory }) },
  onToggleHistory() {
    this.setData({ showHistory: !this.data.showHistory })
  },

  onLoad(options = {}) {
    this._noteEpoch = 1; this._draftHidden = false; this._unloaded = false
    this._pageCustomer = getCustomerId()
    const id = Number(options.refundId)
    if (options.refundType && Number.isSafeInteger(id) && id > 0) {
      this.setData({ refundRef: { refundType: options.refundType, refundId: id } })
    }
  },
  onHide() { this._draftHidden = true; this._noteEpoch = (this._noteEpoch || 0) + 1 },
  onUnload() { this._draftHidden = true; this._unloaded = true; this._noteEpoch = (this._noteEpoch || 0) + 1 },

  onShow() {
    if (this._unloaded) return
    this._draftHidden = false
    this._noteEpoch = (this._noteEpoch || 0) + 1
    const customer = getCustomerId()
    if (this._pageCustomer !== undefined && this._pageCustomer !== customer) {
      this.setData({ refundRef: null, refundOptions: [], refundReady: false, refundError: '',
        refundOptionsError: '', refundOptionsMore: false, refundOptionsPage: 0, history: [], historyError: '',
        content: '', contact: '', pendingRefundNote: false, dispute: null, disputeReason: '', pendingDispute: false,
        myDisputes: [], myDisputesError: '', myDisputesPage: 0, myDisputesMore: false })
    }
    this._pageCustomer = customer
    const loggedIn = !!customer && captureSession().loggedIn
    this.setData({ isLogin: loggedIn })
    this.setData({ submitting: false, disputeSubmitting: false, refundOptionsLoading: false, historyLoading: false })
    if (!loggedIn) {
      this.setData({ history: [], historyLoaded: false, myDisputes: [], myDisputesError: '', refundReady: false,
        refundError: this.data.refundRef ? '请先登录后查看本人退款记录。' : '' })
      if (!this.data.refundRef) this.loadStation()
      return
    }
    const probe = createDraftGuard(() => this.draftScope()).begin()
    if (!this._draftToken || !probe || this._draftToken.identity !== probe.identity) {
      if (this._draftToken && this.hasDraftInput() && this._draftToken.key !== (probe && probe.key) && this._pageCustomer === customer) this.setData({ draftContextMismatch: true, draftNotice: '水站或退款对象已变化，请返回原处处理当前输入。' })
      else this.initDraft()
    } else if (this.data.draftContextMismatch) this.setData({ draftContextMismatch: false, draftNotice: '' })
    if (this.data.refundRef) this.loadRefundThread()
    else { this.loadHistory(); this.loadStation(); this.loadMyDisputes() }
  },
  async loadMyDisputes(page = 1) {
    const session = captureSession(), epoch = this._noteEpoch
    if (!session.loggedIn || !getCustomerId()) return
    const serial = this._myDisputeSerial = (this._myDisputeSerial || 0) + 1
    const current = () => isCurrentSession(session) && epoch === this._noteEpoch && serial === this._myDisputeSerial
    this.setData({ myDisputesLoading: true, myDisputesError: '' })
    try {
      const response = await getRefundDisputes(page)
      if (!current()) return
      if (!Array.isArray(response.data)) throw new Error('退款争议没加载出来')
      const rows = response.data.map(row => Object.assign({}, row, { disputeKey: row.refundType + ':' + row.refundId }))
      const all = page === 1 ? rows : this.data.myDisputes.concat(rows)
      this.setData({ myDisputes: Array.from(new Map(all.map(r => [r.disputeKey, r])).values()), myDisputesPage: page, myDisputesMore: response.data.length === 200 })
    } catch (err) { if (current()) this.setData({ myDisputesError: (this.data.myDisputes.length ? '以下为上次读取的争议记录。' : '') + viewError(err, '退款争议没加载出来，请重试') }) }
    finally { if (current()) this.setData({ myDisputesLoading: false }) }
  },
  onRetryMyDisputes() { return this.loadMyDisputes() },
  onMoreMyDisputes() { if (!this.data.myDisputesLoading) return this.loadMyDisputes(this.data.myDisputesPage + 1) },

  onShowRefundOptions() {
    if (!captureSession().loggedIn || !getCustomerId()) { this.setData({ refundOptionsError: '请先登录后选择本人退款记录。' }); return }
    return this.loadRefundOptions(1)
  },
  onMoreRefundOptions() { return this.loadRefundOptions(this.data.refundOptionsPage + 1) },
  async loadRefundOptions(page) {
    if (this.data.refundOptionsLoading) return
    const session = captureSession(), epoch = this._noteEpoch
    const serial = this._optionsSerial = (this._optionsSerial || 0) + 1
    const current = () => serial === this._optionsSerial && epoch === this._noteEpoch && isCurrentSession(session)
    this.setData({ refundOptionsLoading: true, refundOptionsError: '' })
    try {
      const res = await getRefundOptions(page)
      if (!current()) return
      if (!res.data || !Array.isArray(res.data.options)) throw new Error('退款记录没加载出来')
      const options = page === 1 ? res.data.options : this.data.refundOptions.concat(res.data.options)
      this.setData({ refundOptions: options, refundOptionsMore: !!res.data.hasMore, refundOptionsPage: page,
        refundOptionsLoaded: true, refundOptionsError: '' })
    } catch (e) {
      if (current()) this.setData({ refundOptionsError: viewError(e, '退款记录没加载出来') })
    } finally { if (current()) this.setData({ refundOptionsLoading: false }) }
  },
  onSelectRefund(e) {
    if (this.data.submitting || this.data.disputeSubmitting) return
    const ref = this.data.refundOptions[Number(e.detail.value)]
    if (!ref) return
    this.openRefundRef(ref.refundType, ref.refundId)
  },
  async loadRefundThread() {
    const ref = this.data.refundRef
    if (!ref) return
    const serial = this._refundSerial = (this._refundSerial || 0) + 1
    const session = captureSession(), epoch = this._noteEpoch
    const key = ref.refundType + ':' + ref.refundId
    const keep = this._threadScope && this._threadScope.key === key && isCurrentSession(this._threadScope.session)
    this.setData({ refundReady: false, refundError: '', refundLoading: true, refundStale: !!keep })
    const current = () => epoch === this._noteEpoch && serial === this._refundSerial && isCurrentSession(session)
    try {
      const res = await getRefundNotes(ref.refundType, ref.refundId)
      if (!current()) return
      const d = res.data
      if (!d || d.refundType !== ref.refundType || Number(d.refundId) !== ref.refundId || !d.objectText || !Array.isArray(d.notes)) throw new Error('退款说明记录未能完整加载')
      const pending = noteIntent.read('CUSTOMER:' + getCustomerId(), ref.refundType, ref.refundId)
      const patch = { refundRef: Object.assign({}, ref, { objectText: d.objectText }), refundReady: true,
        history: d.notes, historyError: '', pendingRefundNote: !!pending, dispute: d.dispute || null, refundStale: false }
      const pendingOpen = disputeIntent.read('CUSTOMER:' + getCustomerId(), ref.refundType + ':' + ref.refundId, 'OPEN')
      patch.pendingDispute = !!pendingOpen
      if (pendingOpen) patch.disputeReason = pendingOpen.reason
      if (pending) Object.assign(patch, { content: pending.content, contact: pending.contact })
      this.setData(patch)
      this._threadScope = { key, session }
    } catch (e) {
      if (current()) this.setData({ refundReady: false, refundStale: !!keep,
        refundError: (keep ? '刷新失败，以下为上次读取的退款说明与处理记录。' : '') + viewError(e, '退款说明没加载出来'),
        history: keep ? this.data.history : [], dispute: keep ? this.data.dispute : null })
    } finally { if (current()) this.setData({ refundLoading: false }) }
  },
  onRetryRefund() { this.loadRefundThread() },
  onDisputeReason(e) { if (this._draftHidden || this._unloaded) return; this.setData({ disputeReason: e.detail.value }, () => { this.updateLeaveWarning(); this.saveDraft() }) },
  onHistoryRefund(e) {
    const { type, id } = e.currentTarget.dataset
    if (!type || !id || this.data.submitting || this.data.disputeSubmitting) return
    return this.openRefundRef(type, id)
  },
  openRefundRef(type, id) {
    const session = captureSession(), epoch = this._noteEpoch
    const open = () => {
      if (epoch !== this._noteEpoch || !isCurrentSession(session) || this.data.submitting || this.data.disputeSubmitting) return
      if (this._draftOwned && !this.clearOwnedDraft()) return
      this.setData({ refundRef: { refundType: type, refundId: Number(id) }, refundReady: false, dispute: null,
        history: [], refundStale: false, refundError: '', content: '', contact: '', disputeReason: '', pendingRefundNote: false, pendingDispute: false })
      this.updateLeaveWarning(); this.initDraft()
      return this.loadRefundThread()
    }
    // 2026-10-28：切换记录原会直接清空普通反馈；先让用户决定是否放弃。
    if (this.data.content || this.data.contact || this.data.disputeReason) {
      // RC3：共享弹层明确说明放弃后切换；原生回退保留微信按钮最多四字的限制。
      const dialog = typeof this.selectComponent === 'function' && this.selectComponent('#business-dialog')
      const confirmText = dialog && typeof dialog.open === 'function' ? '放弃并切换' : '确认切换'
      return showBusinessModal({ title: '放弃当前填写？', content: '切换退款记录会清除当前未提交的内容。', confirmText, cancelText: '继续填写', success: res => { if (res.confirm) open() } })
    }
    return open()
  },
  onRetryDispute() {
    try {
      const ref = this.data.refundRef
      const pending = disputeIntent.read('CUSTOMER:' + getCustomerId(), ref.refundType + ':' + ref.refundId, 'OPEN')
      if (!pending) return
      this.setData({ disputeReason: pending.reason }); return this.onOpenDispute()
    } catch (err) { wx.showToast({ title: viewError(err), icon: 'none' }) }
  },
  async onOpenDispute() {
    const ref = this.data.refundRef, state = this.data.dispute
    if (this.data.disputeSubmitting || this.data.draftContextMismatch || !this.data.refundReady || !ref || !state || (!state.canOpen && !this.data.pendingDispute)) return
    const session = captureSession(), epoch = this._noteEpoch, customer = getCustomerId()
    if (!customer) return
    const current = () => epoch === this._noteEpoch && isCurrentSession(session) && this.data.refundRef === ref
    this.setData({ disputeSubmitting: true })
    let intent, recovering = false
    try {
      recovering = !!disputeIntent.read('CUSTOMER:' + customer, ref.refundType + ':' + ref.refundId, 'OPEN')
      intent = disputeIntent.prepare('CUSTOMER:' + customer, ref.refundType + ':' + ref.refundId, 'OPEN', this.data.disputeReason, state.version)
      this.setData({ pendingDispute: true })
      const response = await openRefundDispute(Object.assign(disputeIntent.payload(intent), { refundType: ref.refundType, refundId: ref.refundId }))
      if (!current()) return
      const data = response.data
      if (!data || !data.id || data.refundType !== ref.refundType || Number(data.refundId) !== ref.refundId || !['OPEN', 'REOPEN'].includes(data.action)) throw new Error('异议提交结果未确认，请原样重试')
      disputeIntent.clear(intent)
      this.setData({ pendingDispute: false, disputeReason: '' }, () => this.afterConfirmedInput({ disputeReason: intent.reason }))
      wx.showToast({ title: '异议已提交', icon: 'success' }); await this.loadRefundThread()
    } catch (err) {
      if (current()) {
        // 2026-10-22：恢复时的拒绝可能发生在回执查询前，不能据此抹掉首次未知请求。
        if (intent && err.businessRejected && !err.resultUnknown && !recovering) { disputeIntent.clear(intent); this.setData({ pendingDispute: false }); await this.loadRefundThread() }
        wx.showToast({ title: (recovering ? '原异议结果仍未确认。' : '') + viewError(err, '提交结果未确认，请重试'), icon: 'none' })
      }
    }
    finally { if (epoch === this._noteEpoch && isCurrentSession(session)) this.setData({ disputeSubmitting: false }) }
  },
  onRetryOriginalNote() {
    try {
      const ref = this.data.refundRef, pending = noteIntent.read('CUSTOMER:' + getCustomerId(), ref.refundType, ref.refundId)
      if (!pending) return
      this.setData({ content: pending.content, contact: pending.contact })
      return this.onSubmit()
    } catch (e) { wx.showToast({ title: viewError(e), icon: 'none' }) }
  },

  /**
   * 水站联系方式。
   *
   * ⚠️ 电话**优先走公开接口** `GET /api/stations/{id}/public-phone`（免登录、只回 id/name/phone），
   * 本地缓存只作兜底：`selectedStation` 是选站时存下的快照，**不保证带 phone**，
   * 只读缓存会让"拨打"按钮在部分客户那儿永远提示"暂未获取到客服电话"——
   * api/station.js 的注释里记着同类事故（误调站长路由导致电话恒定取不到，排查成本很高）。
   */
  async loadStation() {
    const epoch = this._noteEpoch, seq = this._phoneSeq = (this._phoneSeq || 0) + 1
    const cached = stationStorage.get() || {}
    const info = { phone: cached.phone || '', stationName: cached.name || '' }
    const stationId = cached.id || cached.stationId
    const current = () => epoch === this._noteEpoch && seq === this._phoneSeq && String(stationStorage.getId() || '') === String(stationId || '')
    this.setData({ phoneLoading: !!stationId, phoneLoaded: false, stationSelected: !!stationId, phoneError: '' })
    let phoneError = ''
    if (stationId) {
      try {
        const res = await getStationPublicPhone(stationId)
        if (!current()) return
        if (!res || res.code !== 0 || !res.data || typeof res.data !== 'object') throw new Error('水站电话暂未加载，请重试')
        const phone = res && res.data && res.data.phone
        info.phone = phone ? String(phone) : ''
      } catch (err) {
        // [2026-09-20 真机联调] 原来这里只 console.warn —— 于是"电话接口失败"与"水站没配电话"
        // 在界面上长得完全一样（都显示「未获取到」+ 点拨打弹「暂未获取到客服电话，请稍后再试」）。
        // 拿不到就退回缓存值（老行为不变），但**必须把失败原因记下来**，由 onCallPhone/页面据此区分。
        console.warn('[Service] 读取水站公开电话失败:', err && (err.message || err.errMsg))
        phoneError = viewError(err, '网络异常')
      }
    }
    if (current()) this.setData({ serviceInfo: info, phoneError, phoneLoading: false, phoneLoaded: !!stationId && !phoneError })
  },

  onRetryStation() { if (!this.data.phoneLoading) return this.loadStation() },
  onRetryHistory() { if (!this.data.historyLoading) return this.loadHistory() },
  onLogin() { wx.navigateTo({ url: '/pages/login/index' }) },

  onCallPhone() {
    if (this.data.phoneLoading) return
    if (!this.data.stationSelected) { wx.showToast({ title: '请先在订水页选择服务水站', icon: 'none' }); return }
    const phone = this.data.serviceInfo.phone
    if (!phone) {
      // [2026-09-20 真机联调] 这里原来是一句断言：「暂未获取到客服电话，请稍后再试」——
      // 把两种完全不同的情况说成同一件事，客户无从判断是自己网络的问题还是水站没留电话。
      // 现在分开：接口失败 → 说明原因 + 不谎称"没有电话"；查到了但确实为空 → 直说没登记。
      if (this.data.phoneError) {
        showBusinessModal({ confirmColor: '#1264D6', cancelColor: '#637083',
          title: '电话没查出来',
          content: '没能取到水站的客服电话（' + this.data.phoneError + '）。'
            + '这不代表水站没有留电话 —— 请稍后重试，或换个网络再试。',
          showCancel: false,
          confirmText: '知道了'
        })
        return
      }
      wx.showToast({ title: '该水站暂未登记客服电话', icon: 'none' })
      return
    }
    wx.makePhoneCall({
      phoneNumber: phone,
      fail: () => wx.showToast({ title: '电话未拨出，请稍后重试', icon: 'none' })
    })
  },

  // [2026-09-17 删除] onCopyWechat 与 data.wechat：那个微信号 'aquaflow_service' 是**编造的**，
  // 后端 Station 实体根本没有微信字段，客户复制到的是一个不存在的账号。
  // 同理删掉了硬编码的 workTime '08:00-20:00' —— 也没有数据来源。
  // **判据：界面上不放假数据。没有数据源就不展示，而不是填一个看起来合理的值**
  //（真要展示营业时间，先给 Station 加字段并让站长可配）。

  onSelectCategory(e) {
    if (this._draftHidden || this._unloaded) return
    this.setData({ category: e.currentTarget.dataset.category }, () => this.saveDraft())
  },

  onContentInput(e) {
    if (this._draftHidden || this._unloaded) return
    this.setData({ content: e.detail.value }, () => { this.updateLeaveWarning(); this.saveDraft() })
  },

  onContactInput(e) {
    if (this._draftHidden || this._unloaded) return
    this.setData({ contact: e.detail.value }, () => { this.updateLeaveWarning(); this.saveDraft() })
  },

  /**
   * 匿名开关。只改本地状态，提交时才随 `anonymous` 一起发给后端。
   *
   * <p>⚠️ 别把这个开关做成"前端隐藏姓名"：姓名从来就不是前端藏起来的 ——
   * 站长端拿到的响应里**根本没有** customerId / customerName（后端 SQL 已置 NULL）。
   * 前端只需如实上报用户的选择。</p>
   */
  onAnonymousChange(e) {
    if (this._draftHidden || this._unloaded) return
    this.setData({ anonymous: !!e.detail.value }, () => this.saveDraft())
  },

  async loadHistory() {
    const session = captureSession(), epoch = this._noteEpoch
    if (!session.loggedIn || !getCustomerId()) { this.setData({ history: [], historyLoaded: false, historyLoading: false, historyError: '请先登录后查看本人反馈记录。' }); return }
    const current = () => epoch === this._noteEpoch && isCurrentSession(session)
    this.setData({ historyLoading: true, historyError: '' })
    try {
      const res = await getMyFeedback()
      if (!current()) return
      if (!res || !Array.isArray(res.data)) throw new Error('反馈历史暂未加载，请重试')
      const history = res.data.map(row => ({ ...row, displayContent: row.category === '错误报告' && /^【自动上报·系统错误】/.test(row.content || '')
        ? '已上报一次服务问题，水站可依据原记录核查；处理进度请联系水站。' : row.content }))
      this.setData({ history, historyLoaded: true, historyError: '' })
    } catch (err) {
      if (!current()) return
      // [2026-09-20 真机联调] 原来是「未登录或加载失败时不强提示」只 console.warn：失败时
      // 历史区块整块不渲染，提过反馈的客户会以为记录丢了（与"确实没提过反馈"无法区分，AGENTS §8.22）。
      // 仍然不用弹窗打断（本页主任务是提交反馈），但空态要改成"没加载出来"。
      console.warn('[Service] 反馈历史加载失败:', err && (err.message || err.errMsg))
      this.setData({ historyError: (this.data.historyLoaded ? '以下为上次读取的反馈记录。' : '') + viewError(err, '反馈历史暂未加载，请重试。') })
    } finally { if (current()) this.setData({ historyLoading: false }) }
  },

  /**
   * 提交反馈。
   *
   * <p><b>匿名（v46，产品裁定 2026-09-18）</b>：随请求体带 `anonymous`，由后端落 `feedback.anonymous`。
   * 提交后**不重置开关**：用户选了匿名就一直是匿名，免得第二次提交在他没注意时变回实名
   * （对隐私而言，悄悄变回实名是更差的方向；而开关就在屏幕上，状态是看得见的）。</p>
   *
   * <p>⚠️ 只有本页（用户手动提交）带 `anonymous`。顾客端的**自动错误上报**
   * （`utils/request.js` 的 `offerErrorReport`）**保持实名不变**，原因写在那边的注释里。</p>
   */
  async onSubmit() {
    if (this.data.submitting || this.data.draftContextMismatch) return
    if (!captureSession().loggedIn || !getCustomerId()) { this.setData({ submitError: '请先登录后提交本人反馈或说明，输入已保留。' }); return }
    if (this.data.refundRef) return this.submitRefundNote()
    const { category, content, contact, anonymous } = this.data
    if (!content.trim()) {
      wx.showToast({ title: '请填写反馈内容', icon: 'none' })
      return
    }

    const session = captureSession(), epoch = this._noteEpoch
    const current = () => epoch === this._noteEpoch && isCurrentSession(session)
    this.setData({ submitting: true, submitError: '' })
    try {
      await submitFeedback({ category, content, contact, anonymous })
      if (!current()) return
      wx.showToast({ title: '反馈已提交，感谢！', icon: 'success' })
      this.setData({ content: '', contact: '' }, () => this.afterConfirmedInput({ content, contact }))
      this.loadHistory()
    } catch (error) {
      if (current()) this.setData({ submitError: viewError(error, '反馈结果尚未确认，输入已保留；请先查看反馈历史，避免重复提交。') })
    } finally {
      if (current()) this.setData({ submitting: false })
    }
  },
  async submitRefundNote() {
    if (!this.data.refundReady) { wx.showToast({ title: this.data.refundError || '请先加载退款记录', icon: 'none' }); return }
    const session = captureSession(), epoch = this._noteEpoch, ref = this.data.refundRef
    if (!getCustomerId()) { wx.showToast({ title: '请先登录', icon: 'none' }); return }
    const current = () => epoch === this._noteEpoch && isCurrentSession(session)
    this.setData({ submitting: true })
    try {
      const intent = noteIntent.prepare('CUSTOMER:' + getCustomerId(), ref.refundType, ref.refundId, this.data.content, this.data.contact)
      this.setData({ pendingRefundNote: true })
      const response = await appendRefundNote(noteIntent.payload(intent))
      if (!current()) return
      if (!response.data || !response.data.id || response.data.refundType !== ref.refundType || Number(response.data.refundId) !== ref.refundId) throw new Error('说明提交结果未确认，请原样重试')
      noteIntent.clear(intent)
      this.setData({ content: '', contact: '', pendingRefundNote: false }, () => this.afterConfirmedInput({ content: intent.content, contact: intent.contact }))
      wx.showToast({ title: '说明已保存', icon: 'success' })
      await this.loadRefundThread()
    } catch (e) {
      if (current()) wx.showToast({ title: viewError(e, '提交结果未确认，请重试'), icon: 'none' })
    } finally { if (current()) this.setData({ submitting: false }) }
  }
})
