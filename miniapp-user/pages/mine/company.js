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
    info: null, form: formOf(null) },
  onShow() {
    this._disposed = false
    if (!this.ensurePageSession()) return
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
    this.setData({ editing: true, saveMessage: '', saveUnknown: false, saveSucceeded: false })
  },
  onInput(e) {
    if (this.data.saving) return
    this.setData({ ['form.' + e.currentTarget.dataset.field]: e.detail.value })
  },
  onCancelEdit() {
    if (this.data.saving) return
    this.setData({ editing: false, form: formOf(this.data.info), saveMessage: '' })
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
      this.setData({ info, form: formOf(info), editing: false, saveSucceeded: true, saveMessage: '企业资料已保存。' })
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
