const { getAllBarrelRecords, updateBarrelRecordStatus, markRefundPaid, getRefundUndelivered, approveBarrelReturn, confirmPayment, getBarrelRefundEligibility } = require('../../../api/station-mgmt')
const businessRules = require('../../../api/business-rules')
const { getPendingReturnRecord, syncPendingReminder } = require('../../../utils/pending-reminder')

// 资格只是交款前的只读快照，现有退款写接口仍核对原渠道、状态和账目。
const CHANNEL_CASH = 'CASH'
const CHANNEL_ONLINE = 'ONLINE'
function session() {
  const app = getApp(), data = app.globalData || {}, user = data.userInfo || {}
  return { app, generation: app._loginGeneration || 0, identity: JSON.stringify([!!data.isLogin, user.staffId, user.role, user.stationId, user.bindStatus]) }
}
function sameSession(before) {
  const now = session()
  return before && before.app === now.app && before.generation === now.generation && before.identity === now.identity
}
function money(value) { const n = Number(value); return Number.isFinite(n) ? n.toFixed(2) : '待核实' }
function eventId(e) { return e && e.currentTarget && e.currentTarget.dataset.id }
function sameId(a, b) { return a != null && b != null && String(a) === String(b) }
function checked(res, fallback) {
  if (!res || res.code !== 0) throw new Error(res && res.message || fallback)
  return res.data
}

Page({
  data: {
    list: [], recordId: null, loading: false, recordsReady: false, loadError: '',
    selectedId: null, selected: null, sheetOpen: false, showMore: false,
    eligibility: null, eligibilityLoading: false, eligibilityError: '',
    actionBusy: false, actionError: '', recoveryNeeded: false,
    undelivered: [], undeliveredCount: 0, undeliveredAmount: '0', undeliveredExpanded: false, undeliveredError: ''
  },

  onLoad(options) {
    const id = options && options.recordId
    this.setData({ recordId: id && /^\d+$/.test(String(id)) ? String(id) : null })
  },
  onShow() {
    this._hidden = false
    const app = getApp()
    if (!app.canAccessStationBusiness()) { this.clearSession(); app.routeByRole(true); return }
    const current = session()
    if (this._session && !sameSession(this._session)) this.clearSession()
    this._session = current
    const pending = this._pendingAction
    this.loadUndelivered()
    if (pending && pending.phase === 'write') return
    if (pending) return this.recoverAction(pending)
    return this.loadData()
  },
  onHide() {
    this._hidden = true
    this._recordsSeq = (this._recordsSeq || 0) + 1
    this._eligibilitySeq = (this._eligibilitySeq || 0) + 1
    this._undeliveredSeq = (this._undeliveredSeq || 0) + 1
    // 原生弹窗被 hide 打断时不能在返回后继续交款；已发出的写请求必须核对原结果。
    if (this._action && this._action.phase !== 'write') this.releaseAction(this._action)
    this.setData({ showMore: false, eligibilityLoading: false })
  },
  onUnload() { this.onHide(); this._unloaded = true },
  clearSession() {
    this._recordsSeq = (this._recordsSeq || 0) + 1
    this._eligibilitySeq = (this._eligibilitySeq || 0) + 1
    this._undeliveredSeq = (this._undeliveredSeq || 0) + 1
    this._action = null; this._pendingAction = null; this._recordsSession = null
    this.setData({ list: [], selected: null, selectedId: null, sheetOpen: false, eligibility: null, actionBusy: false, recoveryNeeded: false, recordsReady: false, undelivered: [], undeliveredCount: 0 })
  },
  validRead(seq, field, snapshot) { return seq === this[field] && !this._hidden && !this._unloaded && sameSession(snapshot) },

  decorateRecord(item) {
    const d = item.returnDetail, over = Number(item.owedBuckets) || 0
    const isReturn = item.type === 2
    const required = d ? d.requiredBarrels : item.quantity
    const result = Object.assign({}, item, {
      isReturn, owedQty: Math.max(0, over), storageQty: Math.max(0, -over),
      amountText: money(item.depositRefund == null ? 0 : item.depositRefund),
      productText: item.productName || (item.productId != null ? '商品 #' + item.productId : '商品待核实'),
      customerText: item.customerName || '客户 ID:' + item.customerId,
      requiredText: required === 0 ? '无需实际交桶' : required != null ? '需实际交桶 ' + required + ' 个' : '交桶数量待核实',
      delivered: isReturn && item.status === 3 && !!item.refundPaidTime,
      noDeliveryRecord: isReturn && item.status === 3 && !item.refundPaidTime,
      statusTone: !isReturn ? 'neutral' : item.status === 3 ? 'success' : item.status === 4 || d && d.status === 'WITHDRAWN' ? 'neutral' : 'warning',
      feeText: money(d && d.pickupFee || 0),
      feeStateText: !d || !d.feePaymentId ? '不另收服务费' : d.feePaymentStatus === 2 ? '已收取' : d.feePaymentStatus === 3 ? '已退还' : d.feePaymentStatus === 1 ? '待收取' : '费用状态待核实',
      timeline: [], primaryAction: '', primaryLabel: '', stepHint: '',
      canReject: isReturn && (d ? ['APPLIED', 'APPROVED'].includes(d.status) && d.feePaymentStatus !== 2 : [1, 2].includes(item.status)),
      rejectNeedsFeeRefund: !!(d && ['APPLIED', 'APPROVED'].includes(d.status) && d.feePaymentStatus === 2),
      canRefundFee: !!(isReturn && d && d.feePaymentId && d.feePaymentStatus === 2),
      needsRefund: isReturn && (d ? d.status === 'RECEIVED' : item.status === 2)
    })
    if (!isReturn) return result
    const steps = d ? [
      ['提交申请', item.createTime], ['批准安排', d.approvedTime], ['客户确认安排', d.customerConfirmedTime],
      [required === 0 ? '办理无需交桶的交接' : '实际交接', d.receivedTime], ['退款交付', item.refundPaidTime]
    ] : [['提交申请', item.createTime], ['确认收到空桶', item.confirmedTime], ['退款交付', item.refundPaidTime]]
    result.timeline = steps.map((step, i) => ({ key: i, title: step[0], time: step[1] || '', done: !!step[1] }))
    if (d) {
      if (['APPLIED', 'APPROVED'].includes(d.status) && (!Number.isInteger(d.requiredBarrels) || d.requiredBarrels < 0)) { result.primaryLabel = '先核实交桶数量'; result.stepHint = '本申请交桶数量未能核对，请重新读取原申请。' }
      else if (d.status === 'APPLIED') { result.primaryAction = 'approve'; result.primaryLabel = '批准交接安排'; result.stepHint = '先核对数量、金额和交接方式，再批准安排。' }
      else if (d.status === 'APPROVED' && !d.customerConfirmedTime) { result.primaryLabel = '等待客户确认'; result.stepHint = '客户尚未确认已批准的安排。' }
      else if (d.status === 'APPROVED' && d.feePaymentId && d.feePaymentStatus === 1) { result.primaryAction = 'collect'; result.primaryLabel = '登记已收到服务费'; result.stepHint = '请先核对真实收到的服务费，再办理交接。' }
      else if (d.status === 'APPROVED' && d.feePaymentId && d.feePaymentStatus !== 2) { result.primaryLabel = '先核实交接安排'; result.stepHint = '服务费已退还或状态待核实，当前不能继续登记交接。' }
      else if (d.status === 'APPROVED') { result.primaryAction = 'receive'; result.primaryLabel = required === 0 ? '确认无需交桶的交接' : '确认收到 ' + required + ' 个空桶'; result.stepHint = required === 0 ? '本次退还尚未领取的容量，无需实际收桶；交接后继续办理押金退款。' : '按本申请需交桶数量核对实物，收到空桶不等于已退押金。' }
      else if (d.status === 'REFUNDED') { result.stepHint = '押金退款已登记；收桶服务费如仍已收取，单独核对。' }
      else if (d.status === 'REJECTED' || d.status === 'WITHDRAWN') result.stepHint = '此申请已结束，保留原有记录。'
    } else if (item.status === 1) { result.primaryAction = 'receive'; result.primaryLabel = '确认收到空桶'; result.stepHint = '历史申请沿原流程办理，确认收桶后再退押金。' }
    else if (item.status === 3 && !item.refundPaidTime) { result.primaryAction = 'paid'; result.primaryLabel = '登记押金已交付'; result.stepHint = '这笔押金没有交付登记，请先核实钱是否已交到客户手上。' }
    if (result.needsRefund) {
      const eligibility = this.data.eligibility
      if (eligibility && sameId(eligibility.recordId, item.id) && eligibility.available === true && [CHANNEL_CASH, CHANNEL_ONLINE].includes(eligibility.channel)) {
        result.primaryAction = 'refund'; result.primaryLabel = Number(eligibility.refundAmount) === 0 ? '登记零额退还结果' : eligibility.channel === CHANNEL_CASH ? '退押金并登记现金交付' : '按线上原渠道退押金'
        result.amountText = money(eligibility.refundAmount)
      } else result.primaryLabel = '先核实退款资格'
      result.stepHint = eligibility && sameId(eligibility.recordId, item.id) ? eligibility.reason : '正在核对原收款方式，核对前请勿交付退款。'
    }
    return result
  },
  refreshSelected() {
    const item = this.data.list.find(r => sameId(r.id, this.data.selectedId))
    this.setData({ selected: item ? this.decorateRecord(item) : null })
  },
  onRetry() {
    if (this._pendingAction && this._pendingAction.phase === 'write') return
    if (this._action && this._action.phase === 'modal') this.releaseAction(this._action)
    return this._pendingAction ? this.recoverAction(this._pendingAction) : this.loadData()
  },
  async loadData() {
    const snapshot = session(), seq = this._recordsSeq = (this._recordsSeq || 0) + 1
    this.setData({ loading: true, recordsReady: false, loadError: '', eligibility: null })
    try {
      const res = this.data.recordId ? await getPendingReturnRecord(this.data.recordId) : await getAllBarrelRecords()
      if (!this.validRead(seq, '_recordsSeq', snapshot)) return false
      const data = checked(res, '退桶申请未能核对，请重试')
      const records = this.data.recordId ? data && [data] : data
      if (!Array.isArray(records)) throw new Error('退桶申请未能核对，请重试')
      this._recordsSession = snapshot
      this.setData({ list: records.map(item => this.decorateRecord(item)), recordsReady: true })
      if (this.data.recordId && !this.data.selectedId && records.length) this.setData({ selectedId: records[0].id, sheetOpen: true })
      this.refreshSelected()
      if (this.data.sheetOpen && this.data.selected) this.loadEligibility(this.data.selected.id)
      return true
    } catch (err) {
      if (this.validRead(seq, '_recordsSeq', snapshot)) this.setData({ loadError: '退桶申请未能核对，请重试；已有记录是上次读取的结果' })
      return false
    } finally { if (this.validRead(seq, '_recordsSeq', snapshot)) this.setData({ loading: false }) }
  },
  async loadUndelivered() {
    const snapshot = session(), seq = this._undeliveredSeq = (this._undeliveredSeq || 0) + 1
    try {
      const data = checked(await getRefundUndelivered(), '未交付记录未能核对')
      if (!this.validRead(seq, '_undeliveredSeq', snapshot)) return
      if (!data || !Array.isArray(data.records)) throw new Error('未交付记录未能核对')
      this.setData({ undelivered: data.records, undeliveredCount: data.count || 0, undeliveredAmount: money(data.amount || 0), undeliveredError: '' })
    } catch (err) {
      if (this.validRead(seq, '_undeliveredSeq', snapshot)) this.setData({ undeliveredError: '历史交付记录未能核对，请重试；已显示内容为上次结果' })
    }
  },
  onToggleUndelivered() { this.setData({ undeliveredExpanded: !this.data.undeliveredExpanded }) },
  onRetryUndelivered() { return this.loadUndelivered() },
  onSelectRecord(e) {
    if (this.data.actionBusy) return
    const item = this.data.list.find(r => sameId(r.id, eventId(e)))
    if (!item || !item.isReturn) return
    this._eligibilitySeq = (this._eligibilitySeq || 0) + 1
    this.setData({ selectedId: item.id, sheetOpen: true, showMore: false, eligibility: null, eligibilityError: '', actionError: '' })
    this.refreshSelected()
    if (this.data.recordsReady) return this.loadEligibility(item.id)
  },
  onCloseSheet() {
    if (this.data.actionBusy) return
    this._eligibilitySeq = (this._eligibilitySeq || 0) + 1
    this.setData({ sheetOpen: false, showMore: false, eligibilityLoading: false })
  },
  noop() {},
  onMore() { if (!this.data.actionBusy) this.setData({ showMore: !this.data.showMore }) },
  async loadEligibility(id, action) {
    const snapshot = session(), seq = this._eligibilitySeq = (this._eligibilitySeq || 0) + 1
    this.setData({ eligibility: null, eligibilityLoading: true, eligibilityError: '' }); this.refreshSelected()
    try {
      const data = checked(await getBarrelRefundEligibility(id), '退款资格未能核对')
      if (!this.validRead(seq, '_eligibilitySeq', snapshot) || !sameId(this.data.selectedId, id) || action && !this.actionCurrent(action)) return null
      const record = this.data.list.find(r => sameId(r.id, id))
      if (!data || !sameId(data.recordId, id) || typeof data.available !== 'boolean' || !record
          || record.returnDetail && data.detailStatus !== record.returnDetail.status
          || !record.returnDetail && data.recordStatus !== record.status
          || data.available && (data.refundAmount == null || !Number.isFinite(Number(data.refundAmount)) || Number(data.refundAmount) < 0)) throw new Error('申请状态或原款未能核对，请重新核对原申请')
      this.setData({ eligibility: data, list: this.data.list.map(r => sameId(r.id, id) && data.productName ? Object.assign({}, r, { productName: data.productName }) : r) })
      this.refreshSelected(); return data
    } catch (err) {
      if (this.validRead(seq, '_eligibilitySeq', snapshot)) { this.setData({ eligibilityError: err.message || '退款资格未能核对，请重试' }); this.refreshSelected() }
      return null
    } finally { if (this.validRead(seq, '_eligibilitySeq', snapshot)) this.setData({ eligibilityLoading: false }) }
  },
  onRetryEligibility() { if (!this.data.actionBusy && this.data.selected) return this.loadEligibility(this.data.selected.id) },
  beginAction(id, kind, historical) {
    if (this._action || this._pendingAction || this._hidden || this._unloaded || !this.data.recordsReady || this.data.loading || !sameSession(this._recordsSession)) return null
    const record = historical ? this.data.undelivered.find(r => sameId(r.id, id)) : this.data.list.find(r => sameId(r.id, id))
    if (!record || record.type !== 2) return null
    const action = { id: record.id, kind, record, snapshot: session(), phase: 'modal' }
    this._action = action
    this.setData({ actionBusy: true, actionError: '', showMore: false })
    return action
  },
  actionCurrent(action) { return this._action === action && !this._hidden && !this._unloaded && sameSession(action.snapshot) },
  releaseAction(action) {
    if (this._action !== action) return
    this._action = null
    if (!this._unloaded) this.setData({ actionBusy: false })
  },
  modal(action, options) {
    return new Promise(resolve => {
      if (!this.actionCurrent(action)) { resolve(null); return }
      wx.showModal(Object.assign({}, options, { success: result => resolve(this.actionCurrent(action) && result.confirm ? result : null), fail: () => resolve(null) }))
    })
  },
  onPrimary() {
    const record = this.data.selected
    if (!record || !record.primaryAction) return
    const e = { currentTarget: { dataset: { id: record.id, amount: record.depositRefund } } }
    const methods = { approve: 'onApproveArrangement', collect: 'onCollectPickupFee', receive: 'onApprove', refund: 'onRefund', paid: 'onConfirmPaid' }
    return this[methods[record.primaryAction]](e)
  },
  async onApproveArrangement(e) {
    const action = this.beginAction(eventId(e), 'approve')
    if (!action) return
    const d = action.record.returnDetail
    try {
      if (!d || this.decorateRecord(action.record).primaryAction !== 'approve') return
      const needsFee = d.pickupMode === 'PICKUP' && d.requiredBarrels > 0
      const result = await this.modal(action, { title: '批准交接安排', editable: needsFee, placeholderText: '独立上门费（元，可填0）', content: needsFee ? '填写本次独立上门费，批准后等待客户确认安排。' : '本次不另收上门费，批准后等待客户确认安排。' })
      if (!result) return
      const fee = needsFee ? Number(result.content) : 0
      if (!Number.isFinite(fee) || fee < 0 || fee > 10000) { wx.showToast({ title: '费用不合法', icon: 'none' }); return }
      return await this.performAction(action, () => approveBarrelReturn(action.id, fee, '批准交接安排'))
    } finally { if (!this._pendingAction) this.releaseAction(action) }
  },
  async onApprove(e) {
    const action = this.beginAction(eventId(e), 'receive')
    if (!action) return
    try {
      if (this.decorateRecord(action.record).primaryAction !== 'receive') return
      const d = action.record.returnDetail, qty = d ? d.requiredBarrels : action.record.quantity
      if (!await this.modal(action, { title: qty === 0 ? '确认无需交桶的交接' : '确认实际收到空桶', content: qty === 0 ? '本次退还尚未领取的容量，无需实际收到空桶。确认已办妥交接手续后，继续办理押金退款。' : '请核实本申请需交回的 ' + qty + ' 个空桶已实际收到。本次只登记交接，不登记已退款。', confirmText: '确认交接' })) return
      return await this.performAction(action, () => updateBarrelRecordStatus(action.id, 2))
    } finally { if (!this._pendingAction) this.releaseAction(action) }
  },
  async onCollectPickupFee(e) {
    const action = this.beginAction(eventId(e), 'collect')
    if (!action) return
    try {
      const d = action.record.returnDetail
      if (this.decorateRecord(action.record).primaryAction !== 'collect' || !d) return
      if (!await this.modal(action, { title: '登记收到服务费', content: '请核实已实际收到本申请的 ¥' + money(d.pickupFee) + ' 服务费，再登记收款。', confirmText: '已收到' })) return
      return await this.performAction(action, () => confirmPayment(d.feePaymentId))
    } finally { if (!this._pendingAction) this.releaseAction(action) }
  },
  async onRefundPickupFee(e) {
    const action = this.beginAction(eventId(e), 'feeRefund')
    if (!action) return
    try {
      if (!this.decorateRecord(action.record).canRefundFee) return
      const d = action.record.returnDetail
      if (!await this.modal(action, { title: '单独退收桶服务费', content: '请核实已向客户实际退还 ¥' + money(d.pickupFee) + ' 服务费，再确认。本操作不退押金，也不抹去已交接事实。', confirmText: '已退还' })) return
      return await this.performAction(action, () => businessRules.refundService(d.feePaymentId, '站长确认实际退还收桶服务费'))
    } finally { if (!this._pendingAction) this.releaseAction(action) }
  },
  async onRefund(e) {
    const action = this.beginAction(eventId(e), 'refund')
    if (!action) return
    try {
      if (!this.decorateRecord(action.record).needsRefund) return
      // 再读一次资格后才出现现金交付确认；页面不提供换渠道菜单。
      const eligibility = await this.loadEligibility(action.id, action)
      if (!eligibility || !this.actionCurrent(action)) return
      if (!eligibility.available || ![CHANNEL_CASH, CHANNEL_ONLINE].includes(eligibility.channel)) {
        this.setData({ actionError: eligibility.reason || '退款资格待核实，请勿交付退款' }); return
      }
      const cash = eligibility.channel === CHANNEL_CASH, zero = Number(eligibility.refundAmount) === 0
      const result = await this.modal(action, { title: zero ? '登记零额退还结果' : cash ? '退押金并登记现金交付' : '按线上原渠道退款', content: zero ? '本申请核对金额为 ¥0.00，无需实际支付现金。确认后登记本申请的零额退还结果。' : cash ? '原款已核对为现金办理，应退 ¥' + money(eligibility.refundAmount) + '。请确认已实际把本笔押金交给客户，再登记；已交过的请勿重复交款。' : '将按本申请原线上收款方式退回 ¥' + money(eligibility.refundAmount) + '，以实际办理结果为准。', confirmText: zero ? '确认登记' : cash ? '已交付' : '确认退款' })
      if (!result) return
      return await this.submitRefund(action.id, eligibility.channel, action)
    } finally { if (!this._pendingAction) this.releaseAction(action) }
  },
  async submitRefund(id, channel, action) {
    // 只能承接本页已核对原款的锁内动作，禁止绕过确认直接再发金融写请求。
    if (!action || !this.actionCurrent(action) || !sameId(action.id, id)) return
    const eligibility = this.data.eligibility
    if (!eligibility || !sameId(eligibility.recordId, id) || !eligibility.available || eligibility.channel !== channel) return
    return this.performAction(action, () => updateBarrelRecordStatus(id, 3, { refundChannel: channel }))
  },
  async onReject(e) {
    const action = this.beginAction(eventId(e), 'reject')
    if (!action) return
    try {
      if (!this.decorateRecord(action.record).canReject) return
      const result = await this.modal(action, { title: '驳回未交接申请', editable: true, placeholderText: '原因（选填）', content: action.record.returnDetail ? '本申请尚未实际交接。驳回后保留原记录。' : '按历史申请原规则驳回，保留原记录。' })
      if (!result) return
      return await this.performAction(action, () => updateBarrelRecordStatus(action.id, 4, { handleNote: result.content || '' }))
    } finally { if (!this._pendingAction) this.releaseAction(action) }
  },
  async onConfirmPaid(e) {
    const id = eventId(e), inList = this.data.list.some(r => sameId(r.id, id))
    const action = this.beginAction(id, 'paid', !inList)
    if (!action) return
    try {
      if (action.record.status !== 3 || action.record.refundPaidTime) return
      if (!await this.modal(action, { title: '登记押金已交付', content: '请核实这笔 ¥' + money(action.record.depositRefund) + ' 押金已经交到客户手上。这里只补交付登记，不再次退款。', confirmText: '已交付' })) return
      return await this.performAction(action, () => markRefundPaid(action.id))
    } finally { if (!this._pendingAction) this.releaseAction(action) }
  },
  async performAction(action, write) {
    if (!this.actionCurrent(action) || action.phase === 'write') return
    action.phase = 'write'; this._pendingAction = action
    this.setData({ eligibility: null, recoveryNeeded: true })
    try {
      checked(await write(), '办理结果未能确认')
      action.writeSucceeded = true
    } catch (err) { action.writeError = err && err.message || '办理结果未能确认' }
    action.phase = 'recovery'
    if (!sameSession(action.snapshot) || this._unloaded) { this._pendingAction = null; this.releaseAction(action); return }
    if (this._hidden) return
    return this.recoverAction(action)
  },
  async recoverAction(action) {
    if (!action || this._hidden || this._unloaded || !sameSession(action.snapshot)) return false
    // 列表有截断上限，必须按原编号回读；弱网未知绝不自动重发退款。
    this.setData({ actionBusy: true, loading: true, recordsReady: false, recoveryNeeded: true, eligibility: null })
    try {
      const record = checked(await getPendingReturnRecord(action.id), '原申请结果未能核对')
      if (this._hidden || this._unloaded || !sameSession(action.snapshot) || this._pendingAction !== action) return false
      if (!record || !sameId(record.id, action.id) || record.type !== 2) throw new Error('原申请结果未能核对')
      const list = this.data.list.filter(r => !sameId(r.id, record.id)); list.unshift(this.decorateRecord(record))
      const d = record.returnDetail
      const completed = action.kind === 'refund' ? !!record.refundPaidTime || d && d.status === 'REFUNDED'
        : action.kind === 'receive' ? d ? ['RECEIVED', 'REFUNDED'].includes(d.status) : record.status === 2 || record.status === 3
          : action.kind === 'approve' ? d && d.status !== 'APPLIED'
            : action.kind === 'collect' ? d && d.feePaymentStatus === 2
              : action.kind === 'feeRefund' ? d && d.feePaymentStatus === 3
                : action.kind === 'reject' ? record.status === 4 || d && d.status === 'REJECTED'
                  : !!record.refundPaidTime
      this._pendingAction = null; this._recordsSession = action.snapshot
      this.setData({ list, recordsReady: true, loading: false, actionBusy: false, recoveryNeeded: false, loadError: '',
        actionError: completed ? '' : (action.writeError || '原申请尚未显示办理结果') + '；已重新核对原申请。涉及交款请核实实际交付，勿重复交款。' })
      this.releaseAction(action); this.refreshSelected()
      if (completed) wx.showToast({ title: '已核对办理结果', icon: 'success' })
      this.loadUndelivered()
      if (action.kind === 'refund' && completed) syncPendingReminder()
      if (this.data.selected && this.data.selected.needsRefund) this.loadEligibility(this.data.selected.id)
      return true
    } catch (err) {
      if (!this._hidden && !this._unloaded && sameSession(action.snapshot) && this._pendingAction === action) {
        this.setData({ loading: false, actionBusy: false, recordsReady: false, actionError: (action.writeSucceeded ? '办理请求已成功，' : '办理结果尚未确认，') + '原申请未能重新核对。请先查原结果，勿再次交款或重复办理。', loadError: '原申请结果未能核对，请重试' })
        this.releaseAction(action)
      }
      return false
    }
  }
})
