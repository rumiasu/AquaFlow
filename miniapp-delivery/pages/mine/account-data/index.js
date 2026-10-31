const { registerSessionDraftCleanup } = require('../../../utils/draft-guard')
const api = require('../../../api/account-data-requests')
// 未确认申请只在本次登录的进程内保留，绝不写入 UI 草稿或设备存储。
const requestSlots = new WeakMap()
// Marker contains no request body; it blocks a duplicate if the identity object is replaced.
const requestMarkers = new WeakMap()
function requestSlot(user, session, previous) {
  let marker = requestMarkers.get(session.app)
  if (!marker || marker.generation !== session.generation || marker.identity !== session.identity) {
    marker = { generation: session.generation, identity: session.identity, pending: false }
    requestMarkers.set(session.app, marker)
  }
  let slot = requestSlots.get(user)
  if (!slot && previous && previous.generation === session.generation && previous.identity === session.identity && previous.marker === marker) {
    slot = previous; requestSlots.set(user, slot)
  }
  if (!slot || slot.generation !== session.generation || slot.identity !== session.identity || slot.marker !== marker) {
    if (slot) { slot.submission = null; slot.flight = null; slot.pages.clear() }
    slot = { generation: session.generation, identity: session.identity, marker, submission: null, flight: null, missing: marker.pending, pages: new Set() }
    requestSlots.set(user, slot)
  }
  if (!slot.clearOnSessionEnd) slot.clearOnSessionEnd = registerSessionDraftCleanup(session.app, () => {
    slot.submission = null; slot.flight = null
    for (const page of slot.pages) { page._submission = null; page._submissionFlight = null; try { page.setData({ note: '', detail: null }) } catch (_) {} }
    slot.pages.clear(); requestSlots.delete(user); slot.clearOnSessionEnd = null
  })
  return slot
}
function syncSlot(slot, notice, receipt) {
  for (const page of slot.pages) {
    if (!page.current(page._epoch) || page._requestSlot !== slot) continue
    page._submission = slot.submission; page._submissionFlight = slot.flight
    const patch = { pending: !!slot.submission || !!slot.missing, originalUnavailable: !!slot.missing, submitting: !!slot.flight }
    if (notice) patch.submitNotice = notice
    if (receipt) { page._selectedType = null; patch.note = ''; patch.selectedIndex = -1; patch.selectedLabel = '请选择请求类型'; patch.activeView = 'records'; patch.detail = { request: receipt, closureCheck: null, checkNotice: '' } }
    page.setData(patch); page.updateLeaveWarning()
    if (receipt) page.loadList(false, page._epoch).catch(() => { if (page.current(page._epoch)) page.setData({ error: '申请已登记，列表暂时无法刷新，可重试查询。' }) })
  }
}
// Employee app owns its login generation; a normal token refresh keeps the same identity.
function captureSession() {
  const app = getApp(), data = app.globalData, user = data.userInfo || {}
  return { app, generation: app._loginGeneration || 0,
    identity: JSON.stringify([data.isLogin, user.staffId, user.role, user.stationId, user.bindStatus]) }
}
function isCurrentSession(session) {
  const now = captureSession()
  return !!session && session.app === now.app && session.generation === now.generation && session.identity === now.identity
}
Page({
  onSetView(e) { const view = e.currentTarget.dataset.view; if (view === 'apply' || view === 'records') this.setData({ activeView: view }) },
  onToggleDetails() { this.setData({detailsExpanded:!this.data.detailsExpanded}) },
  data: {
    activeView:'apply', detailsExpanded:false, checkError:'', options: null, loading: false, error: '', items: [], nextBeforeId: null, selectedIndex: -1,
    selectedLabel: '请选择请求类型', note: '', submitting: false, pending: false, originalUnavailable: false, submitNotice: '', detail: null, check: null },
  onShow() {
    if (this._unloaded) return
    if (this._session && !isCurrentSession(this._session)) {
      if (this._requestSlot) { this._requestSlot.pages.delete(this); this._requestSlot.submission = null; this._requestSlot.flight = null; this._requestSlot.marker.pending = false }
      this._submission = null; this._submissionFlight = null; this._selectedType = null
      this.setData({ selectedIndex: -1, selectedLabel: '请选择请求类型', note: '', submitNotice: '' })
      if (typeof wx.disableAlertBeforeUnload === 'function') wx.disableAlertBeforeUnload()
    }
    this._session = captureSession(); this._hidden = false; this._epoch = (this._epoch || 0) + 1
    const user = getApp().globalData.userInfo
    this._requestSlot = user && typeof user === 'object' ? requestSlot(user, this._session, this._requestSlot) : null
    if (this._requestSlot) {
      this._requestSlot.pages.add(this); this._submission = this._requestSlot.submission; this._submissionFlight = this._requestSlot.flight
      if (this._submission) { this._selectedType = this._submission.requestType; this.setData({ note: this._submission.note, submitNotice: '原申请结果尚未确认；请查看记录或重试原申请。' }) }
      else if (this._requestSlot.missing) this.setData({ note: '', submitNotice: '原申请内容无法恢复，请先核对申请记录；不要重复登记。' })
    }
    this.setData({ pending: !!this._submission || !!(this._requestSlot && this._requestSlot.missing), originalUnavailable: !!(this._requestSlot && this._requestSlot.missing), submitting: !!this._submissionFlight, submitNotice: this.data.submitNotice }); this.updateLeaveWarning(); this.load()
  },
  onHide() { if (this._requestSlot) this._requestSlot.pages.delete(this); this._hidden = true; this._epoch = (this._epoch || 0) + 1 },
  onUnload() { if (this._requestSlot) { this._requestSlot.pages.delete(this); if (this._session && !isCurrentSession(this._session)) { this._requestSlot.submission = null; this._requestSlot.flight = null; this._requestSlot.marker.pending = false; this._requestSlot.pages.clear() } } this._unloaded = true; this._epoch = (this._epoch || 0) + 1; if (typeof wx.disableAlertBeforeUnload === 'function') wx.disableAlertBeforeUnload() },
  updateLeaveWarning() {
    const dirty = this.data.note || this.data.selectedIndex >= 0 || this.data.pending
    if (dirty && typeof wx.enableAlertBeforeUnload === 'function') wx.enableAlertBeforeUnload({ message: '申请尚未完成，离开后请核实记录。' })
    else if (!dirty && typeof wx.disableAlertBeforeUnload === 'function') wx.disableAlertBeforeUnload()
  },
  current(epoch) { return !this._unloaded && !this._hidden && epoch === this._epoch && isCurrentSession(this._session) },
  async load() {
    const epoch = this._epoch
    this.setData({ loading: true, error: '', options: null, items: [], detail: null, check: null, checkError: '' })
    try {
      const res = await api.options()
      if (!this.current(epoch)) return
      if (!res || !res.data || typeof res.data.intakeEnabled !== 'boolean' || !Array.isArray(res.data.requestTypes)) throw Error('请求设置暂时无法核实')
      const selectedIndex = this._selectedType == null ? -1 : res.data.requestTypes.findIndex(type => type.value === this._selectedType)
      this.setData({ options: res.data, selectedIndex, selectedLabel: selectedIndex < 0 ? '请选择请求类型' : res.data.requestTypes[selectedIndex].label })
      await this.loadList(false, epoch)
    } catch (error) { if (this.current(epoch)) this.setData({ error: '资料请求信息暂时无法加载，请重试。' }) }
    finally { if (this.current(epoch)) this.setData({ loading: false }) }
  },
  onRetry() {
    if (this.data.loading) return
    if (this._requestSlot && this._requestSlot.missing && !this._requestSlot.marker.pending) { this._requestSlot.missing = false; this.setData({ pending: false, originalUnavailable: false, submitNotice: '原申请已确认完成，请查看记录。' }); this.updateLeaveWarning() }
    this.load()
  },
  async loadList(more, epoch) {
    const res = await api.mine(more ? this.data.nextBeforeId : undefined)
    if (!this.current(epoch)) return
    if (!res || !res.data || !Array.isArray(res.data.items)) throw Error('申请记录暂时无法加载')
    this.setData({ items: more ? this.data.items.concat(res.data.items) : res.data.items, nextBeforeId: res.data.nextBeforeId })
  },
  async onMore() {
    if (this.data.loading || !this.data.nextBeforeId) return
    const epoch = this._epoch; this.setData({ loading: true })
    try { await this.loadList(true, epoch) } catch (error) { if (this.current(epoch)) this.setData({ error: '申请记录暂时无法加载，请重试。' }) }
    finally { if (this.current(epoch)) this.setData({ loading: false }) }
  },
  onType(e) {
    if (this.data.pending || this.data.submitting) return
    const index = Number(e.detail.value), type = this.data.options && this.data.options.requestTypes[index]
    if (type) { this._selectedType = type.value; this.setData({ selectedIndex: index, selectedLabel: type.label }); this.updateLeaveWarning() }
  },
  onNote(e) { if (!this.data.pending && !this.data.submitting) { this.setData({ note: e.detail.value }); this.updateLeaveWarning() } },
  async onSubmit() {
    if (!this.current(this._epoch) || this.data.submitting || !this.data.options || !this.data.options.intakeEnabled) return
    const slot = this._requestSlot, session = this._session
    if (!slot || slot.flight) return
    if (slot.missing) { wx.showToast({ title: '请先核对原申请记录', icon: 'none' }); return }
    const type = this.data.options.requestTypes[this.data.selectedIndex]
    if (!slot.submission && !type) { wx.showToast({ title: '请选择请求类型', icon: 'none' }); return }
    const payload = slot.submission || { requestType: type.value, note: this.data.note.trim(),
      idempotencyKey: 'account-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2) }
    slot.submission = payload; slot.marker.pending = true
    const flight = slot.flight = {}
    syncSlot(slot, '')
    try {
      const res = await api.submit(payload)
      if (!isCurrentSession(session) || slot.flight !== flight) return
      if (!res || !res.data || !res.data.id) throw Error('登记结果待核实')
      slot.submission = null; slot.marker.pending = false
      syncSlot(slot, res.data.notice || '申请已登记。', res.data)
    } catch (error) {
      if (isCurrentSession(session) && slot.flight === flight) syncSlot(slot, '登记结果尚未确认；重试将使用原提交编号和原申请内容。')
    } finally {
      if (slot.flight === flight) { slot.flight = null; if (isCurrentSession(session)) syncSlot(slot, ''); else { slot.submission = null; slot.marker.pending = false; slot.pages.clear() } }
    }
  },
  async onDetail(e) {
    if (this.data.loading) return
    const id = e.currentTarget.dataset.id, epoch = this._epoch; this.setData({ loading: true })
    try { const res = await api.detail(id); if (this.current(epoch)) this.setData({ detail: res.data }) }
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
