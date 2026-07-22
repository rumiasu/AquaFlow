const { wxLogin, devLogin } = require('../../api/auth')
const { storage } = require('../../utils/storage')

Page({
  data: {
    loading: false,
    canIUseGetUserProfile: false
  },

  onLoad() {
    const accessToken = storage.get('accessToken')
    if (accessToken) {
      wx.switchTab({ url: '/pages/home/index' })
    }
  },

  // 微信一键登录
  async onWxLogin() {
    if (this.data.loading) return
    this.setData({ loading: true })

    try {
      const loginRes = await new Promise((resolve, reject) => {
        wx.login({
          success: resolve,
          fail: reject
        })
      })

      if (!loginRes.code) {
        wx.showToast({ title: '微信登录失败', icon: 'none' })
        return
      }

      const res = await wxLogin(loginRes.code)

      if (res.data && res.data.accessToken) {
        const app = getApp()
        app.setLoginInfo(res.data.accessToken, res.data.refreshToken, {
          nickname: res.data.nickname || '微信用户',
          phone: res.data.phone || ''
        })
        // 兼容：保存 customerId
        if (res.data.customerId) {
          wx.setStorageSync('customerId', res.data.customerId)
        }

        wx.showToast({ title: '登录成功', icon: 'success' })
        setTimeout(() => {
          wx.switchTab({ url: '/pages/home/index' })
        }, 1000)
      } else {
        wx.showToast({ title: res.message || '登录失败', icon: 'none' })
      }
    } catch (error) {
      console.error('微信登录失败:', error)
      wx.showToast({ title: '微信登录失败，请使用开发登录', icon: 'none', duration: 3000 })
    } finally {
      this.setData({ loading: false })
    }
  },

  // 开发模式登录（跳过微信验证）
  async onDevLogin() {
    if (this.data.loading) return
    this.setData({ loading: true })

    try {
      const res = await devLogin('测试用户')

      if (res.data && res.data.accessToken) {
        const app = getApp()
        app.setLoginInfo(res.data.accessToken, res.data.refreshToken, {
          nickname: res.data.nickname || '测试用户',
          phone: res.data.phone || ''
        })
        if (res.data.customerId) {
          wx.setStorageSync('customerId', res.data.customerId)
        }

        wx.showToast({ title: '登录成功', icon: 'success' })
        setTimeout(() => {
          wx.switchTab({ url: '/pages/home/index' })
        }, 1000)
      } else {
        wx.showToast({ title: res.message || '登录失败', icon: 'none' })
      }
    } catch (error) {
      console.error('开发登录失败:', error)
      wx.showToast({ title: '登录失败: ' + (error.message || ''), icon: 'none', duration: 3000 })
    } finally {
      this.setData({ loading: false })
    }
  }
})
