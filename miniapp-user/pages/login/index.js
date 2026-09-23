const { wxLogin, devLogin } = require('../../api/auth')
const { storage } = require('../../utils/storage')
const { STORAGE_KEYS } = require('../../utils/storage-keys')

Page({
  data: {
    loading: false,
    agreed: false
  },

  onLoad() {
    const accessToken = storage.get(STORAGE_KEYS.ACCESS_TOKEN)
    if (accessToken) {
      wx.switchTab({ url: '/pages/home/index' })
    }
  },

  // 协议勾选切换
  onToggleAgreement() {
    this.setData({ agreed: !this.data.agreed })
  },

  // 微信一键登录：wx.login 静默拿 code → 后端换 openid 自动注册/登录
  async onWxLogin() {
    if (this.data.loading) return

    if (!this.data.agreed) {
      wx.showToast({ title: '请先阅读并同意用户协议和隐私政策', icon: 'none', duration: 2500 })
      return
    }

    this.setData({ loading: true })

    try {
      // 1. wx.login 拿临时登录凭证 code
      const loginRes = await new Promise((resolve, reject) => {
        wx.login({ success: resolve, fail: reject })
      })

      if (!loginRes.code) {
        wx.showToast({ title: '微信登录失败', icon: 'none' })
        return
      }

      // 2. 把 code 发给后端，后端用 code 换 openid 并查/建用户
      const res = await wxLogin(loginRes.code)

      if (res && res.data && res.data.accessToken) {
        const data = res.data
        const app = getApp()
        app.setLoginInfo(data.accessToken, data.refreshToken, {
          nickname: data.nickname || '微信用户',
          phone: data.phone || ''
        })
        if (data.customerId) {
          wx.setStorageSync(STORAGE_KEYS.CUSTOMER_ID, data.customerId)
        }

        wx.showToast({ title: '登录成功', icon: 'success' })
        setTimeout(() => {
          wx.switchTab({ url: '/pages/home/index' })
        }, 800)
      } else {
        wx.showToast({ title: (res && res.message) || '登录失败', icon: 'none' })
      }
    } catch (error) {
      console.error('微信登录失败:', error)
      wx.showToast({ title: (error && error.message) || '微信登录失败', icon: 'none', duration: 2500 })
    } finally {
      this.setData({ loading: false })
    }
  },

  // 开发模式登录（跳过微信验证）
  async onDevLogin() {
    if (this.data.loading) return
    if (!this.data.agreed) {
      wx.showToast({ title: '请先阅读并同意用户协议和隐私政策', icon: 'none', duration: 2500 })
      return
    }
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
          wx.setStorageSync(STORAGE_KEYS.CUSTOMER_ID, res.data.customerId)
        }

        wx.showToast({ title: '登录成功', icon: 'success' })
        setTimeout(() => {
          wx.switchTab({ url: '/pages/home/index' })
        }, 500)
      } else {
        wx.showToast({ title: res.message || '登录失败', icon: 'none' })
      }
    } catch (error) {
      console.error('开发登录失败:', error)
      wx.showToast({ title: '登录失败: ' + (error.message || '网络错误'), icon: 'none', duration: 3000 })
    } finally {
      this.setData({ loading: false })
    }
  }
})
