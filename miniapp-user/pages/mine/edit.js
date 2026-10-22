const { updateProfile } = require('../../api/auth')
const { STORAGE_KEYS } = require('../../utils/storage-keys')
const { captureSession, isCurrentSession } = require('../../utils/token')

Page({
  data: {
    loading: false,
    submitting: false,
    saveError: '',
    nickname: '',
    phone: ''
  },

  onLoad() {
    this._session = captureSession()
    const needLogin = !this._session.loggedIn || !this._session.customerId
    this.setData({ needLogin })
    const app = getApp()
    const userInfo = needLogin ? {} : app.globalData.userInfo || {}
    this.setData({
      nickname: userInfo.nickname || userInfo.nickName || '',
      phone: userInfo.phone || ''
    })
  },
  onShow() { if (!isCurrentSession(this._session)) this.onLoad() },
  onLogin() { wx.navigateTo({ url: '/pages/login/index' }) },

  onNicknameInput(e) {
    this.setData({ nickname: e.detail.value })
  },

  onPhoneInput(e) {
    this.setData({ phone: e.detail.value })
  },

  async onSubmit() {
    if (this.data.submitting || this._unloaded) return
    if (this.data.needLogin) { this.setData({ saveError: '请先登录后修改本人资料。' }); return }
    if (!isCurrentSession(this._session)) { this.setData({ saveError: '登录状态已变化，请返回后重新进入个人资料。' }); return }
    const { nickname, phone } = this.data
    if (!nickname.trim()) {
      wx.showToast({ title: '请输入昵称', icon: 'none' })
      return
    }
    if (phone && !/^1\d{10}$/.test(phone)) {
      wx.showToast({ title: '手机号格式不正确', icon: 'none' })
      return
    }

    this.setData({ submitting: true, saveError: '' })
    try {
      await updateProfile({ nickname: nickname.trim(), phone: phone.trim() })
      if (this._unloaded || !isCurrentSession(this._session)) return
      // 更新本地存储
      const app = getApp()
      const userInfo = app.globalData.userInfo || {}
      userInfo.nickname = nickname.trim()
      userInfo.phone = phone.trim()
      app.globalData.userInfo = userInfo
      wx.setStorageSync(STORAGE_KEYS.USER_INFO, userInfo)

      wx.showToast({ title: '保存成功', icon: 'success' })
      setTimeout(() => { if (!this._unloaded && isCurrentSession(this._session)) wx.navigateBack() }, 1500)
    } catch (error) {
      if (!this._unloaded && isCurrentSession(this._session)) this.setData({ saveError: '资料未能保存，输入已保留，请重试。' })
    } finally {
      if (!this._unloaded) this.setData({ submitting: false })
    }
  },
  onUnload() { this._unloaded = true }
})
