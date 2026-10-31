const { createDraftGuard } = require('../../utils/draft-guard')
const { showBusinessModal } = require('../../utils/business-dialog')
const { captureSession, isCurrentSession } = require('../../utils/token')
const { getCompanyInfo, updateCompanyInfo } = require('../../api/company')
const { formatTime } = require('../../utils/format')
function formOf(info) {
  return { companyName: info && info.companyName || '', contactPerson: info && info.contactPerson || '',
    contactPhone: info && info.contactPhone || '', paymentMethod: info && info.paymentMethod || '',
    dueDays: info && info.dueDays != null ? String(info.dueDays) : '' }
}
function denied(error) {
  const code = error && (error.code || error.statusCode)
  return String(code) === '401' || String(code) === '403' || /登录|权限|无权|客户账号|客户身份|HTTP\s*(401|403)|UNAUTHORIZED|FORBIDDEN/i.test(String(error && error.message || ''))
}
Page({
  data: { loading: true, loaded: false, loadError: '', accessDenied: false,
    saving: false, editing: false, saveMessage: '', saveUnknown: false, saveSucceeded: false, comparisonReady: false, sessionChanged: false,
    info: null, form: formOf(null), draftState: 'empty', draftMessage: '' },
  onShow() {
    this._disposed = false
    if (!this.ensurePageSession()) return
    if (!this._draftGuard) this.initDraft()
    // 返回页面时不拿服务器数据覆盖正在填写的内容。
    if (!this.data.editing && !this.data.saving) this.loadInfo()
  },
  onHide() { this._readId = (this._readId || 0) + 1; this.setData({ loading: false }) },
  onUnload() { this._disposed = true; this._readId = (this._readId || 0) + 1 },
  onRetry() { return this.loadInfo() },
  onLogin() { wx.navigateTo({ url: '/pages/login/index' }) },
  ensurePageSession() {
    if (!this._pageSession) { this._pageSession = captureSession(); return true }
    if (isCurrentSession(this._pageSession)) return true
    this._readId = (this._readId || 0) + 1
    const patch = { loading: false, accessDenied: true, sessionChanged: true, loadError: '登录身份已变化，请退出本页后重新进入。' }
    if (this.data.saving) { patch.saveUnknown = false; patch.saveMessage = '登录身份已变化，输入已保留，请重新登录后保存。' }
    this.setData(patch)
    return false
  },
  async loadInfo() {
    if (!this.ensurePageSession()) return
    if (this.data.saving) return
    const readId = this._readId = (this._readId || 0) + 1
    this.setData({ loading: true, loadError: '' })
    try {
      const res = await getCompanyInfo()
      if (readId !== this._readId || this._disposed || !this.ensurePageSession()) return
      if (!res || !Object.prototype.hasOwnProperty.call(res, 'data') ||
        (res.data !== null && (typeof res.data !== 'object' || Array.isArray(res.data)))) throw new Error('企业资料数据不完整')
      const info = res.data ? { ...res.data, updateTimeText: res.data.updateTime ? formatTime(res.data.updateTime) : '更新时间待核实' } : null
      const patch = { info, loaded: true, accessDenied: false, comparisonReady: this.data.saveUnknown }
      // 刷新只更新展示资料，不覆盖当前输入。
      if (!this.data.editing) patch.form = formOf(info)
      this.setData(patch)
    } catch (error) {
      if (readId !== this._readId || this._disposed || !this.ensurePageSession()) return
      console.warn('Load company error:', error)
      this.setData({ accessDenied: denied(error), loadError: denied(error)
        ? '当前账号无法查看企业资料，请使用客户账号重新登录。' : '企业资料未能读取，请检查网络后重试。' })
    } finally {
      if (readId === this._readId && !this._disposed) this.setData({ loading: false })
    }
  },
  onStartEdit() {
    if (this.data.loading || this.data.loadError || !this.data.info || this.data.accessDenied) return
    this._readId = (this._readId || 0) + 1
    this._saveCompleted = false
    this.setData({ editing: true, saveMessage: '', saveUnknown: false, saveSucceeded: false })
  },
  draftScope() {
    const session = captureSession()
    return session.loggedIn && session.customerId ? { app: 'miniapp-user', actor: session.customerId, station: 'personal', route: 'pages/mine/company', entity: 'company', generation: session.epoch } : {}
  },
  validDraft(d) { return !!d && ['companyName', 'contactPerson', 'contactPhone', 'paymentMethod', 'dueDays'].every(k => typeof d[k] === 'string') },
  initDraft() {
    this._draftGuard = createDraftGuard(() => this.draftScope()); this._draftToken = this._draftGuard.begin()
    this._draftRevision = 0; this._draftOwned = false; this._saveCompleted = false; this.readDraftOffer()
  },
  readDraftOffer() {
    const result = this._draftGuard.read(this._draftToken), valid = this.validDraft(result.draft)
    this._draftOffered = result.state === 'saved' && valid
    this.setData({ draftState: this._draftOffered ? 'saved' : result.state === 'saved' ? 'unknown' : result.state,
      draftMessage: result.state === 'unknown' || result.state === 'saved' && !valid ? '草稿暂无法读取，请重试。' : '' })
  },
  onRetryDraft() { if (this._saveCompleted && this._draftOwned) { this.clearSavedDraft(); return } this.readDraftOffer(); if (!this._draftOffered && this.data.draftState === 'empty' && this.data.editing && this.isDirty()) this.saveEditableDraft() },
  saveEditableDraft() {
    if (!this._draftGuard || !this._draftToken || this._draftOffered || this.data.draftState === 'unknown') {
      this.setData({ draftMessage: '草稿尚未确认保存，请先处理已有草稿或重试。' }); return
    }
    this._draftOwned = true; this._draftSnapshot = JSON.stringify(this.data.form)
    const result = this._draftGuard.save(this._draftToken, this.data.form)
    this.setData({ draftState: result.state === 'saved' ? 'active' : result.state,
      draftMessage: result.state === 'saved' ? '' : '草稿尚未确认保存，请稍后重试。' })
  },
  onRestoreDraft() {
    const token = this._draftToken, revision = this._draftRevision, result = this._draftGuard.read(token)
    if (result.state !== 'saved' || !this.validDraft(result.draft)) { this.readDraftOffer(); return }
    const restore = () => {
      const current = this._draftGuard.read(token)
      if (this._disposed || revision !== this._draftRevision || current.state !== 'saved' || JSON.stringify(current.draft) !== JSON.stringify(result.draft) || this.data.saving || this.data.loading || this.data.accessDenied || !this.data.info) return
      this._draftOffered = false; this._draftOwned = true; this._draftSnapshot = JSON.stringify(result.draft); this._draftRevision++
      this.setData({ form: result.draft, editing: true, draftState: 'active', draftMessage: '' }, () => this.updateLeaveWarning())
    }
    if (this.data.editing && this.isDirty()) return showBusinessModal({ title: '恢复之前的输入？', content: '当前未保存的企业资料会被替换。', confirmText: '恢复', cancelText: '继续编辑', success: res => { if (res.confirm) restore() } })
    restore()
  },
  onDiscardSavedDraft() {
    const token = this._draftToken, revision = this._draftRevision; const offered = this._draftGuard.read(token)
    showBusinessModal({ title: '放弃之前的输入？', content: '确认后将清除已保存的草稿。', confirmText: '放弃', cancelText: '保留', success: res => {
      if (!res.confirm || this._disposed || revision !== this._draftRevision) return
      const current = this._draftGuard.read(token)
      if (offered.state !== 'saved' || current.state !== 'saved' || JSON.stringify(current.draft) !== JSON.stringify(offered.draft)) { this.setData({ draftState: 'unknown', draftMessage: '草稿已有变化或暂无法读取，请重试。' }); return }
      const result = this._draftGuard.clear(token)
      if (result.state !== 'empty') { this.setData({ draftState: result.state, draftMessage: '草稿尚未确认清除，请重试。' }); return }
      this._draftOffered = false; this._draftOwned = false; this.setData({ draftState: 'empty', draftMessage: '' }); this.updateLeaveWarning()
    } })
  },
  clearSavedDraft() {
    if (!this._draftGuard || !this._draftOwned) return true
    const current = this._draftGuard.read(this._draftToken)
    if (current.state === 'empty') { this._draftOwned = false; this.setData({ draftState: 'empty', draftMessage: '' }); return true }
    if (current.state !== 'saved' || JSON.stringify(current.draft) !== this._draftSnapshot) { this.setData({ draftState: 'unknown', draftMessage: '草稿已有变化或暂无法读取，请重试。' }); return false }
    const result = this._draftGuard.clear(this._draftToken)
    if (result.state !== 'empty') { this.setData({ draftState: result.state, draftMessage: '草稿尚未确认清除，请重试。' }); return false }
    this._draftOwned = false; this.setData({ draftState: 'empty', draftMessage: '' }); return true
  },
  isDirty() { return JSON.stringify(this.data.form) !== JSON.stringify(formOf(this.data.info)) },
  updateLeaveWarning() {
    if (this.data.editing && (this.isDirty() || (this._draftOwned && !this._saveCompleted))) this.saveEditableDraft()
    if (this.data.editing && this.isDirty() && wx.enableAlertBeforeUnload) wx.enableAlertBeforeUnload({ message: '企业资料尚未保存，确定离开吗？' })
    else if (wx.disableAlertBeforeUnload) wx.disableAlertBeforeUnload()
  },
  onInput(e) {
    if (this.data.saving) return
    this._draftRevision = (this._draftRevision || 0) + 1; this.setData({ ['form.' + e.currentTarget.dataset.field]: e.detail.value }, () => this.updateLeaveWarning())
  },
  onCancelEdit() {
    if (this.data.saving) return
    const session = captureSession()
    const discard = () => {
      if (this._disposed || !isCurrentSession(session) || this.data.saving) return
      if (!this.clearSavedDraft()) return
      this.setData({ editing: false, form: formOf(this.data.info), saveMessage: '' }, () => this.updateLeaveWarning())
    }
    // 2026-10-28：取消编辑原会直接丢失输入；有变化时让用户明确选择。
    if (this.isDirty()) return showBusinessModal({ title: '放弃修改？', content: '尚未保存的企业资料将不会保留。', confirmText: '放弃', cancelText: '继续编辑', success: res => { if (res.confirm) discard() } })
    discard()
  },
  async onSave() {
    if (!this.ensurePageSession()) return
    if (this.data.saving || this.data.loading || this.data.loadError || !this.data.editing || this.data.accessDenied || !this.data.info) return
    const { form } = this.data
    if (!form.companyName.trim()) { this.setData({ saveMessage: '请填写公司名称。' }); return }
    this._readId = (this._readId || 0) + 1
    this.setData({ saving: true, loading: false, saveMessage: '正在保存企业资料…', saveSucceeded: false, comparisonReady: false })
    try {
      const res = await updateCompanyInfo({ companyName: form.companyName.trim(), contactPerson: form.contactPerson.trim(),
        contactPhone: form.contactPhone.trim(), paymentMethod: form.paymentMethod.trim(), dueDays: form.dueDays ? parseInt(form.dueDays) : null })
      if (this._disposed || !this.ensurePageSession()) return
      // PUT 返回保存后的资料；缺失结果不伪造成功，也不清掉输入。
      if (!res || !res.data || typeof res.data !== 'object' || Array.isArray(res.data)) throw new Error('保存结果无法确认')
      const info = { ...res.data, updateTimeText: res.data.updateTime ? formatTime(res.data.updateTime) : '更新时间待核实' }
      this._saveCompleted = true
      const cleared = this.clearSavedDraft()
      this.setData({ info, form: formOf(info), editing: false, saveSucceeded: true, saveMessage: cleared ? '企业资料已保存。' : '企业资料已保存，但草稿尚未确认清除。' }, () => this.updateLeaveWarning())
      wx.showToast({ title: '资料已保存', icon: 'success' })
    } catch (error) {
      if (this._disposed || !this.ensurePageSession()) return
      console.warn('Save company error:', error)
      const rejected = error && (error.businessRejected === true || String(error.code || error.statusCode) === '401' || String(error.code || error.statusCode) === '403')
      this.setData({ accessDenied: denied(error), saveUnknown: false, saveMessage: denied(error)
        ? '当前账号无权保存企业资料，输入已保留，请使用客户账号登录。'
        : rejected ? '保存未通过，输入已保留，请核对后重试。'
          : '暂未收到保存结果，输入已保留，可直接重试保存。' })
    } finally {
      if (!this._disposed) this.setData({ saving: false })
    }
  }
})
