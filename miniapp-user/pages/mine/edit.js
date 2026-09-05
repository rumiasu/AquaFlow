const { updateProfile } = require('../../api/auth')
const { STORAGE_KEYS } = require('../../utils/storage-keys')

Page({
  data: {
    loading: false,
    submitting: false,
    nickname: '',
    phone: ''
  },

  onLoad() {
    const app = getApp()
    const userInfo = app.globalData.userInfo || {}
    this.setData({
      nickname: userInfo.nickname || userInfo.nickName || '',
      phone: userInfo.phone || ''
    })
  },

  onNicknameInput(e) {
    this.setData({ nickname: e.detail.value })
  },

  onPhoneInput(e) {
    this.setData({ phone: e.detail.value })
  },

  async onSubmit() {
    const { nickname, phone } = this.data
    if (!nickname.trim()) {
      wx.showToast({ title: '请输入昵称', icon: 'none' })
      return
    }
    if (phone && !/^1\d{10}$/.test(phone)) {
      wx.showToast({ title: '手机号格式不正确', icon: 'none' })
      return
    }

    this.setData({ submitting: true })
    try {
      await updateProfile({ nickname: nickname.trim(), phone: phone.trim() })
      // 更新本地存储
      const app = getApp()
      const userInfo = app.globalData.userInfo || {}
      userInfo.nickname = nickname.trim()
      userInfo.phone = phone.trim()
      app.globalData.userInfo = userInfo
      wx.setStorageSync(STORAGE_KEYS.USER_INFO, userInfo)

      wx.showToast({ title: '保存成功', icon: 'success' })
      setTimeout(() => wx.navigateBack(), 1500)
    } catch (error) {
      wx.showToast({ title: error.message || '保存失败', icon: 'none' })
    } finally {
      this.setData({ submitting: false })
    }
  }
})
