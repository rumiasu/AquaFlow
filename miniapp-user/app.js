App({
  globalData: {
    userInfo: null,
    token: null,
    isLogin: false
  },

  onLaunch() {
    this.checkLogin()
  },

  checkLogin() {
    const token = wx.getStorageSync('token')
    const userInfo = wx.getStorageSync('userInfo')
    const customerId = wx.getStorageSync('customerId')
    if (token && customerId) {
      this.globalData.token = token
      this.globalData.userInfo = userInfo
      this.globalData.isLogin = true
      // 服务端验证 token 是否有效
      this.validateToken(customerId)
    } else {
      this.globalData.isLogin = false
    }
  },

  validateToken(customerId) {
    const { getBaseUrl } = require('./config/api')
    const baseUrl = getBaseUrl()
    wx.request({
      url: `${baseUrl}/api/auth/me?customerId=${customerId}`,
      method: 'GET',
      success: (res) => {
        if (res.statusCode === 200 && res.data.code === 0) {
          this.globalData.isLogin = true
          this.globalData.userInfo = res.data.data
        } else {
          // token 失效，清除登录状态
          this.clearLoginInfo()
        }
      },
      fail: () => {
        // 网络错误，保留本地登录状态
        console.warn('[App] 验证登录状态失败，保留本地状态')
      }
    })
  },

  setLoginInfo(token, userInfo, customerId) {
    this.globalData.token = token
    this.globalData.userInfo = userInfo
    this.globalData.isLogin = true
    wx.setStorageSync('token', token)
    wx.setStorageSync('userInfo', userInfo)
    wx.setStorageSync('customerId', customerId)
  },

  clearLoginInfo() {
    this.globalData.token = null
    this.globalData.userInfo = null
    this.globalData.isLogin = false
    wx.removeStorageSync('token')
    wx.removeStorageSync('userInfo')
    wx.removeStorageSync('customerId')
  }
})
