// 编辑资料页
const { updateProfile, getMe } = require('../../api/auth')
const { STORAGE_KEYS } = require('../../utils/storage-keys')
const { captureSession, currentSession } = require('../../utils/request')

Page({
  data: {
    nickname: '',
    phone: '',
    submitting: false, error: '', profileReadPending: false
  },

  onLoad() {
    this._unloaded = false; this._profileFlight = null; this._pendingProfileSave = null
    this.setData({ profileReadPending: false, submitting: false, profileReadPending: false })
    this._session = captureSession()
    const app = getApp()
    const userInfo = app.globalData.userInfo || {}
    const nickname = userInfo.nickname || userInfo.nickName || '', phone = userInfo.phone || ''
    this._saved = { nickname, phone }
    this.setData({ nickname, phone, error: '', submitting: false })
  },

  onShow() {
    if (this._session && !currentSession(this._session)) { this._unloaded = false; this.onLoad(); this.updateLeaveAlert() }
  },
  onUnload() { this._unloaded = true; if (typeof wx.disableAlertBeforeUnload === 'function') wx.disableAlertBeforeUnload() },
  updateLeaveAlert() {
    if (!this._saved) return
    const dirty = this.data.nickname !== this._saved.nickname || this.data.phone !== this._saved.phone
    if (dirty && typeof wx.enableAlertBeforeUnload === 'function') wx.enableAlertBeforeUnload({ message: '资料尚未保存，确定离开吗？' })
    else if (!dirty && typeof wx.disableAlertBeforeUnload === 'function') wx.disableAlertBeforeUnload()
  },

  onNicknameInput(e) {
    if (this.data.submitting) return
    this.setData({ nickname: e.detail.value, error: '' }); this.updateLeaveAlert()
  },

  onPhoneInput(e) {
    if (this.data.submitting) return
    this.setData({ phone: e.detail.value, error: '' }); this.updateLeaveAlert()
  },

  // 2026-11-01 F-01: bind the receipt to its original flight; separate the write from local synchronization.
  profileFlightCurrent(flight) {
    return !this._unloaded && this._profileFlight === flight && currentSession(flight.session)
  },
  async readProfileSave(flight) {
    try {
      if (typeof getMe !== 'function') return false
      const response = await getMe(), value = response && response.data
      if (!this.profileFlightCurrent(flight) || !value || String(value.staffId) !== String(flight.actor)
          || value.nickname !== flight.saved.nickname || (value.phone || '') !== flight.saved.phone) return false
      return true
    } catch (_) { return false }
  },
  finishProfileSave(flight, verified) {
    if (!this.profileFlightCurrent(flight)) return
    const unchanged = this.data.nickname === flight.input.nickname && this.data.phone === flight.input.phone
    this._saved = { ...flight.saved }
    const app = getApp()
    const userInfo = { ...(app.globalData.userInfo || {}), ...flight.saved }
    app.globalData.userInfo = userInfo
    let cacheSynced = true
    try { wx.setStorageSync(STORAGE_KEYS.USER_INFO, userInfo) } catch (_) { cacheSynced = false }
    if (unchanged) this.setData({ ...flight.saved })
    const cleared = true
    this.updateLeaveAlert()
    this._pendingProfileSave = null
    this.setData({ profileReadPending: false, error: cacheSynced ? '' : '资料已保存，本机信息尚未同步，请保留当前页面。' })
    wx.showToast({ title: verified ? '已核对，资料已保存' : '保存成功', icon: 'success' })
    if (cacheSynced && cleared && unchanged) setTimeout(() => {
      if (this.profileFlightCurrent(flight) && this.data.nickname === flight.saved.nickname
          && this.data.phone === flight.saved.phone) wx.navigateBack()
    }, 1500)
  },
  async onRetryProfileRead() {
    const flight = this._pendingProfileSave
    if (!flight || !this.profileFlightCurrent(flight) || this.data.submitting) return
    this.setData({ submitting: true })
    try {
      if (await this.readProfileSave(flight)) this.finishProfileSave(flight, true)
      else if (this.profileFlightCurrent(flight)) this.setData({ error: '保存结果仍未确认，输入已保留，请稍后核对。' })
    } finally { if (this.profileFlightCurrent(flight)) this.setData({ submitting: false }) }
  },
  async onSubmit() {
    if (this.data.submitting || this._unloaded) return

    if (!currentSession(this._session)) { this.setData({ error: '登录状态已变化，请返回后重新进入个人资料。' }); return }
    const input = { nickname: this.data.nickname, phone: this.data.phone }
    if (!input.nickname.trim()) { wx.showToast({ title: '请输入昵称', icon: 'none' }); return }
    if (input.phone && !/^1\d{10}$/.test(input.phone)) { wx.showToast({ title: '手机号格式不正确', icon: 'none' }); return }
    const flight = { session: this._session, input, actor: (getApp().globalData.userInfo || {}).staffId,
      // The existing update-profile contract treats an empty phone as KEEP, not CLEAR.
      saved: { nickname: input.nickname.trim(), phone: input.phone.trim() || this._saved.phone || '' } }
    this._profileFlight = flight; this._pendingProfileSave = null
    this.setData({ profileReadPending: false, submitting: true, profileReadPending: false, error: '' })
    let confirmed = false, verified = false
    try {
      await updateProfile({ nickname: input.nickname.trim(), phone: input.phone.trim() })
      confirmed = true
    } catch (error) {
      if (!this.profileFlightCurrent(flight)) return
      if (error && error.businessRejected) {
        this.setData({ error: '资料未保存，输入已保留，请核对后重试。' })
      } else {
        verified = confirmed = await this.readProfileSave(flight)
        if (!confirmed && this.profileFlightCurrent(flight)) {
          this._pendingProfileSave = flight
          this.setData({ profileReadPending: true, error: '保存结果未确认，输入已保留。请先核对结果。' })
        }
      }
    } finally {
      // Local cache and draft failures cannot be reported as a rejected server write.
      if (confirmed && this.profileFlightCurrent(flight)) this.finishProfileSave(flight, verified)
      if (this.profileFlightCurrent(flight)) this.setData({ submitting: false })
    }
  },

})
