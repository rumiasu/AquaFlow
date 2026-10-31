const api = require('../../../api/account-data-requests')
const { captureSession, isCurrentSession } = require('../../../utils/token')
const { createDraftGuard } = require('../../../utils/draft-guard')
const { showBusinessModal } = require('../../../utils/business-dialog')

// 原申请编号必须单独持久化；UI草稿只保存可编辑字段，不能充当业务意图。
function intentKey(actor) { return 'account-data-intent:customer:' + actor }
function validActor(value) { return /^[1-9]\d*$/.test(String(value == null ? '' : value)) && Number.isSafeInteger(Number(value)) }
function validDraft(value) { return value && typeof value.note === 'string' && value.note.length <= 500 && typeof value.requestType === 'string' && value.requestType.length <= 100 }
function validIntent(value, actor) { return value && value.version === 1 && String(value.actor) === String(actor) && value.payload && Object.keys(value.payload).sort().join(',') === 'idempotencyKey,note,requestType' && /^[A-Z_]{1,64}$/.test(value.payload.requestType || '') && typeof value.payload.note === 'string' && value.payload.note.length <= 500 && /^[A-Za-z0-9._:-]{1,64}$/.test(value.payload.idempotencyKey || '') }
function readIntent(actor) {
  try { const value = wx.getStorageSync(intentKey(actor)); if (value == null || value === '') return { state: 'empty' }; return validIntent(value, actor) ? { state: 'saved', payload: JSON.parse(JSON.stringify(value.payload)) } : { state: 'unknown' } } catch (_) { return { state: 'unknown' } }
}
function saveIntent(actor, payload) {
  try { const existing = readIntent(actor); if (existing.state === 'unknown') return 'unknown'; if (existing.state === 'saved' && JSON.stringify(existing.payload) !== JSON.stringify(payload)) return 'conflict'; const value = { version: 1, actor, payload }; wx.setStorageSync(intentKey(actor), value); return JSON.stringify(wx.getStorageSync(intentKey(actor))) === JSON.stringify(value) ? 'saved' : 'unknown' } catch (_) { return 'unknown' }
}
function clearIntent(actor, payload) {
  try { const existing = readIntent(actor); if (existing.state !== 'saved' || JSON.stringify(existing.payload) !== JSON.stringify(payload)) return false; wx.removeStorageSync(intentKey(actor)); return readIntent(actor).state === 'empty' } catch (_) { return false }
}
Page({
  onTab(e) { const tab = e.currentTarget.dataset.tab; if (tab === 'apply' || tab === 'records') { if (tab !== this.data.tab && wx.hideKeyboard) wx.hideKeyboard({}); this.setData({ tab }) } },
  onToggleDetails() { this.setData({detailsExpanded:!this.data.detailsExpanded}) },
  data: {
    tab: 'apply', isClosure: false, detailsExpanded:false, checkError:'', options: null, loading: false, error: '', items: [], nextBeforeId: null, selectedIndex: -1,
    selectedLabel: '请选择请求类型', note: '', submitting: false, pending: false, intentBlocked: false, draftAvailable: false, draftNotice: '', submitNotice: '', detail: null, check: null },
  onShow() {
    if (this._unloaded) return
    if (this._session && !isCurrentSession(this._session)) {
      this._submission = null; this._submissionFlight = null; this._draftGuard = null; this._draftToken = null
      this.setData({ selectedIndex: -1, selectedLabel: '请选择请求类型', isClosure: false, note: '', items: [], detail: null, check: null, checkError: '' })
    }
    this._session = captureSession(); this._hidden = false; this._epoch = (this._epoch || 0) + 1
    if (this._session.loggedIn && validActor(this._session.customerId)) {
      const stored = readIntent(this._session.customerId)
      if (!this._submission && stored.state === 'saved') this._submission = stored.payload
      this.setData({ intentBlocked: stored.state === 'unknown' })
      if (stored.state === 'unknown') this.setData({ submitNotice: '原申请记录暂时无法读取，请联系水站核对，暂不能新建申请。' })
      if (!this._draftGuard) this.initDraft()
    } else { this._draftGuard = null; this._draftToken = null; this.setData({ draftAvailable: false, draftNotice: '', intentBlocked: !!this._session.loggedIn }) }
    this.setData({ pending: !!this._submission, submitting: !!this._submissionFlight,
      note: this._submission ? this._submission.note : this.data.note, submitNotice: this.data.intentBlocked ? this.data.submitNotice : (this._submission ? '上次申请结果待确认，可重试原申请。' : '') }); this.load()
  },
  onHide() { this._hidden = true; this._epoch = (this._epoch || 0) + 1 },
  onUnload() { this._unloaded = true; this._epoch = (this._epoch || 0) + 1 },
  current(epoch) { return !this._unloaded && !this._hidden && epoch === this._epoch && isCurrentSession(this._session) },
  async load() {
    const epoch = this._epoch
    const needLogin = !this._session || !this._session.loggedIn || !this._session.customerId
    this.setData({ needLogin })
    if (needLogin) { this.setData({ loading: false, options: null, items: [], detail: null, check: null, checkError: '', error: '请先登录后查看本人账户资料与申请。' }); return }
    const keep = this._loadedSession && isCurrentSession(this._loadedSession)
    this.setData({ loading: true, error: '', ...(keep ? {} : { options: null, items: [], detail: null, check: null, checkError: '' }) })
    try {
      const res = await api.options()
      if (!this.current(epoch)) return
      if (!res || !res.data || typeof res.data.intakeEnabled !== 'boolean' || !Array.isArray(res.data.requestTypes)) throw Error('请求设置暂时无法核实')
      const requestTypes = res.data.requestTypes
      const savedIndex = this._submission ? requestTypes.findIndex(t => t.value === this._submission.requestType) : -1
      this.setData({ options: res.data, ...(this._submission ? { selectedIndex: savedIndex, selectedLabel: savedIndex >= 0 ? requestTypes[savedIndex].label : '原申请类型待核实', isClosure: this._submission.requestType === 'CLOSURE' } : {}) })
      await this.loadList(false, epoch)
      if (this.current(epoch)) this._loadedSession = this._session
    } catch (error) { if (this.current(epoch)) this.setData({ error: (keep ? '刷新失败，以下为上次读取的信息。' : '') + '资料请求信息暂时无法加载，请重试。' }) }
    finally { if (this.current(epoch)) this.setData({ loading: false }) }
  },
  onRetry() { if (!this.data.loading) this.load() },
  onLogin() { wx.navigateTo({ url: '/pages/login/index' }) },
  async loadList(more, epoch) {
    const res = await api.mine(more ? this.data.nextBeforeId : undefined)
    if (!this.current(epoch)) return
    if (!res || !res.data || !Array.isArray(res.data.items)) throw Error('申请记录暂时无法加载')
    this.setData({ items: more ? this.data.items.concat(res.data.items) : res.data.items, nextBeforeId: res.data.nextBeforeId })
  },
  async onMore() {
    if (this.data.loading || !this.data.nextBeforeId) return
    const epoch = this._epoch; this.setData({ loading: true, error: '' })
    try { await this.loadList(true, epoch) } catch (error) { if (this.current(epoch)) this.setData({ error: '申请记录暂时无法加载，请重试。' }) }
    finally { if (this.current(epoch)) this.setData({ loading: false }) }
  },
  draftScope() {
    const session = captureSession()
    if (!session.loggedIn || !validActor(session.customerId)) return null
    return { app: 'customer', actor: session.customerId, station: 'personal', route: 'account-data', entity: 'new', generation: session.epoch }
  },
  initDraft() {
    this._draftGuard = createDraftGuard(() => this.draftScope()); this._draftToken = this._draftGuard.begin(); this._draftOwned = false
    const result = this._draftGuard.read(this._draftToken)
    this._draftBlocked = result.state !== 'empty'
    this._draftSaved = result.state === 'saved' && validDraft(result.draft) ? result.draft : null
    this._draftLast = null
    if (result.state === 'saved' && !this._draftSaved) this._draftBlocked = true
    if (this._submission && this._draftSaved && this._draftSaved.requestType === this._submission.requestType && this._draftSaved.note.trim() === this._submission.note) { this._draftOwned = true; this._draftBlocked = false; this._draftLast = this._draftSaved; this._draftSaved = null }
    this.setData({ draftAvailable: !!this._draftSaved, draftNotice: result.state === 'unknown' || result.state === 'stale' || (result.state === 'saved' && !this._draftSaved) ? '草稿暂时无法核实，当前输入尚未确认保存。' : '' })
  },
  saveDraft() {
    if (!this._draftGuard || !this._draftToken) { this.setData({ draftNotice: '草稿尚未确认保存，请登录后重试。' }); return }
    if (this._draftBlocked) { this.setData({ draftNotice: '请先处理原草稿；当前输入尚未确认保存。' }); return }
    const existing = this._draftGuard.read(this._draftToken)
    if (existing.state === 'unknown' || existing.state === 'stale') { this.setData({ draftNotice: '草稿尚未确认保存，请保留当前输入并重试。' }); return }
    if (existing.state === 'saved' && (!this._draftOwned || JSON.stringify(existing.draft) !== JSON.stringify(this._draftLast))) {
      this._draftBlocked = true; this._draftOwned = false; this._draftSaved = validDraft(existing.draft) ? existing.draft : null
      this.setData({ draftAvailable: !!this._draftSaved, draftNotice: this._draftSaved ? '原草稿已变化，请先处理；当前输入尚未确认保存。' : '草稿暂时无法核实，当前输入尚未确认保存。' }); return
    }
    const type = this.data.options && this.data.options.requestTypes[this.data.selectedIndex]
    const draft = { requestType: type ? type.value : '', note: this.data.note }
    const result = this._draftGuard.save(this._draftToken, draft)
    if (result.state === 'saved') { this._draftOwned = true; this._draftLast = draft; this.setData({ draftNotice: '' }) }
    else this.setData({ draftNotice: '草稿尚未确认保存，请保留当前输入并重试。' })
  },
  onRestoreDraft() {
    if (!this._draftSaved || this.data.pending || this.data.submitting) return
    const draft = this._draftSaved, token = this._draftToken, session = this._session, epoch = this._epoch
    const restore = () => {
      if (!this.current(epoch) || !isCurrentSession(session) || !this._draftGuard || this.data.pending) return
      const current = this._draftGuard.read(token)
      if (current.state !== 'saved' || JSON.stringify(current.draft) !== JSON.stringify(draft)) { this.setData({ draftNotice: '原草稿已变化，请重新进入后核对。' }); return }
      const index = this.data.options && this.data.options.requestTypes.findIndex(t => t.value === draft.requestType)
      if (draft.requestType && (index == null || index < 0)) { this.setData({ draftNotice: '原申请类型暂不可选，请稍后重试；草稿仍保留。' }); return }
      const type = index >= 0 && this.data.options.requestTypes[index]
      this._draftBlocked = false; this._draftOwned = true; this._draftLast = draft; this._draftSaved = null
      this.setData({ selectedIndex: index >= 0 ? index : -1, selectedLabel: type ? type.label : '请选择请求类型', isClosure: !!type && type.value === 'CLOSURE', note: draft.note, draftAvailable: false, draftNotice: '' }, () => this.updateLeaveWarning())
    }
    if (this.data.note || this.data.selectedIndex >= 0) return showBusinessModal({ title: '恢复原输入？', content: '恢复会替换当前填写的内容。', confirmText: '恢复', cancelText: '继续填写', success: res => { if (res.confirm) restore() } })
    restore()
  },
  onDiscardDraft() {
    if (!this._draftSaved || this.data.pending || this.data.submitting) return
    const token = this._draftToken, session = this._session, epoch = this._epoch, expected = this._draftSaved
    showBusinessModal({ title: '放弃原草稿？', content: '放弃后原草稿将不再保留。', confirmText: '放弃', cancelText: '保留', success: res => {
      if (!res.confirm || !this.current(epoch) || !isCurrentSession(session)) return
      const current = this._draftGuard.read(token)
      if (current.state !== 'saved' || JSON.stringify(current.draft) !== JSON.stringify(expected)) { this.setData({ draftNotice: '原草稿已变化，请重新进入后核对。' }); return }
      const result = this._draftGuard.clear(token)
      if (result.state !== 'empty') { this.setData({ draftNotice: '草稿尚未确认清除，请重试。' }); return }
      this._draftBlocked = false; this._draftSaved = null; this.setData({ draftAvailable: false, draftNotice: '' })
      if (this.data.note || this.data.selectedIndex >= 0) this.saveDraft()
    } })
  },
  clearOwnedDraft() {
    if (!this._draftOwned || !this._draftGuard) return
    const current = this._draftGuard.read(this._draftToken)
    if (current.state === 'empty') { this._draftOwned = false; this._draftLast = null; return }
    if (current.state !== 'saved' || JSON.stringify(current.draft) !== JSON.stringify(this._draftLast)) {
      this._draftOwned = false; this._draftBlocked = true; this._draftSaved = current.state === 'saved' && validDraft(current.draft) ? current.draft : null
      this.setData({ draftAvailable: !!this._draftSaved, draftNotice: '已提交；草稿已变化或暂时无法核实，请重新核对。' }); return
    }
    const result = this._draftGuard.clear(this._draftToken)
    if (result.state === 'empty') { this._draftOwned = false; this._draftLast = null }
    else this.setData({ draftNotice: '已提交，但草稿尚未确认清除。' })
  },
  updateLeaveWarning() {
    const dirty = !!this.data.note || this.data.selectedIndex >= 0
    if (dirty && wx.enableAlertBeforeUnload) wx.enableAlertBeforeUnload({ message: '申请尚未提交，确定离开吗？' })
    else if (!dirty && wx.disableAlertBeforeUnload) wx.disableAlertBeforeUnload()
  },
  onType(e) {
    if (this._hidden || this._unloaded || this.data.pending || this.data.submitting) return
    const index = Number(e.detail.value), type = this.data.options && this.data.options.requestTypes[index]
    if (type) this.setData({ selectedIndex: index, selectedLabel: type.label, isClosure: type.value === 'CLOSURE' }, () => { this.updateLeaveWarning(); this.saveDraft() })
  },
  onNote(e) { if (!this._hidden && !this._unloaded && !this.data.pending && !this.data.submitting) this.setData({ note: e.detail.value }, () => { this.updateLeaveWarning(); this.saveDraft() }) },
  async onSubmit() {
    if (!this.current(this._epoch) || this.data.submitting || this.data.intentBlocked || this.data.loading || !this.data.options || !this.data.options.intakeEnabled) return
    const type = this.data.options.requestTypes[this.data.selectedIndex]
    if (!this._submission && !type) { wx.showToast({ title: '请选择请求类型', icon: 'none' }); return }
    const payload = this._submission || { requestType: type.value, note: this.data.note.trim(),
      idempotencyKey: 'account-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2) }
    this._submission = payload
    const stored = saveIntent(this._session.customerId, payload)
    if (stored === 'conflict') { this._submission = null; this.setData({ pending: false, intentBlocked: true, submitNotice: '已有原申请待确认，请重新进入本页恢复原申请。' }); return }
    if (stored !== 'saved') { this.setData({ pending: true, submitNotice: '原申请记录尚未确认保存，尚未提交；可重试原申请。' }); return }
    const flight = this._submissionFlight = {}
    const epoch = this._epoch
    this.setData({ submitting: true, pending: true, submitNotice: '' })
    try {
      const res = await api.submit(payload)
      if (!this.current(epoch)) return
      if (!res || !res.data || !Number.isSafeInteger(Number(res.data.id)) || Number(res.data.id) <= 0) throw Error('登记结果待核实')
      if (!clearIntent(this._session.customerId, payload)) { this.setData({ submitNotice: '申请已登记，但原记录尚未确认清除；请核对记录后重试原申请。' }); return }
      this._submission = null
      this.clearOwnedDraft()
      if (wx.disableAlertBeforeUnload) wx.disableAlertBeforeUnload()
      this.setData({ pending: false, note: '', selectedIndex: -1, selectedLabel: '请选择请求类型', isClosure: false, tab: 'records', submitNotice: res.data.notice,
        detail: { request: res.data, closureCheck: null, checkNotice: '' } })
      try { await this.loadList(false, epoch) } catch (error) { if (this.current(epoch)) this.setData({ error: '申请已登记，列表暂时无法刷新，可重试查询。' }) }
    } catch (error) { if (this.current(epoch)) this.setData({ submitNotice: '登记结果尚未确认；重试将使用原提交编号和原申请内容。' }) }
    finally {
      if (this._submissionFlight === flight) {
        this._submissionFlight = null
        if (!this._unloaded && !this._hidden && isCurrentSession(this._session)) this.setData({ submitting: false })
      }
    }
  },
  async onDetail(e) {
    if (this.data.loading) return
    const id = e.currentTarget.dataset.id, epoch = this._epoch; this.setData({ loading: true, error: '' })
    try {
      const res = await api.detail(id)
      if (!res || !res.data || !res.data.request || String(res.data.request.id) !== String(id)) throw Error('申请详情暂未核实')
      if (this.current(epoch)) this.setData({ detail: res.data, tab: 'records' }, () => { if (wx.pageScrollTo) wx.pageScrollTo({ selector: '#request-detail', duration: 200 }) })
    }
    catch (error) { if (this.current(epoch)) this.setData({ error: '申请详情暂时无法加载，请重试。' }) }
    finally { if (this.current(epoch)) this.setData({ loading: false }) }
  },
  async onCheck() {
    if (this.data.loading || !this.data.options || !this.data.options.closureCheckSupported || typeof api.closureCheck !== 'function') return
    const epoch = this._epoch; this.setData({ loading: true, check: null, checkError: '' })
    try { const res = await api.closureCheck(); if (this.current(epoch)) this.setData({ check: res.data }) }
    catch (error) { if (this.current(epoch)) this.setData({ checkError: '注销前检查未完成，请重试。' }) }
    finally { if (this.current(epoch)) this.setData({ loading: false }) }
  }
})
