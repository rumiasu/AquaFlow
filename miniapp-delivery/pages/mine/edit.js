// 编辑资料页
const { updateProfile } = require('../../api/auth')
const { STORAGE_KEYS } = require('../../utils/storage-keys')
const { captureSession, currentSession } = require('../../utils/request')

Page({
  data: {
    nickname: '',
    phone: '',
    submitting: false, error: ''
  },

  onLoad() {
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
    this.setData({ nickname: e.detail.value, error: '' }); this.updateLeaveAlert()
  },

  onPhoneInput(e) {
    this.setData({ phone: e.detail.value, error: '' }); this.updateLeaveAlert()
  },

  async onSubmit() {
    if (this.data.submitting || !currentSession(this._session)) return
    const { nickname, phone } = this.data
    if (!nickname.trim()) {
      wx.showToast({ title: '请输入昵称', icon: 'none' })
      return
    }
    if (phone && !/^1\d{10}$/.test(phone)) {
      wx.showToast({ title: '手机号格式不正确', icon: 'none' })
      return
    }

    const session = this._session
    this.setData({ submitting: true, error: '' })
    try {
      await updateProfile({ nickname: nickname.trim(), phone: phone.trim() })
      if (this._unloaded || !currentSession(session)) return
      // 更新本地
      const app = getApp()
      const userInfo = app.globalData.userInfo || {}
      userInfo.nickname = nickname.trim()
      userInfo.phone = phone.trim()
      app.globalData.userInfo = userInfo
      wx.setStorageSync(STORAGE_KEYS.USER_INFO, userInfo)

      this._saved = { nickname: nickname.trim(), phone: phone.trim() }; this.updateLeaveAlert()
      wx.showToast({ title: '保存成功', icon: 'success' })
      setTimeout(() => { if (!this._unloaded && currentSession(session)) wx.navigateBack() }, 1500)
    } catch (err) {
      if (!this._unloaded && currentSession(session)) this.setData({ error: err.message || '保存失败，请重试。' })
    } finally {
      if (!this._unloaded && currentSession(session)) this.setData({ submitting: false })
    }
  }
})
