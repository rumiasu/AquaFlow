App({
  globalData: {
    userInfo: null,
    accessToken: null,
    refreshToken: null,
    isLogin: false
  },

  onLaunch() {
    this.checkLogin()
  },

  checkLogin() {
    const accessToken = wx.getStorageSync('accessToken')
    const refreshToken = wx.getStorageSync('refreshToken')
    const userInfo = wx.getStorageSync('userInfo')
    if (accessToken && userInfo) {
      this.globalData.accessToken = accessToken
      this.globalData.refreshToken = refreshToken
      this.globalData.userInfo = userInfo
      this.globalData.isLogin = true
      // 用 /api/auth/me 验证 token 是否有效（通过 JWT 识别用户，不再传 customerId）
      this.validateToken()
    } else {
      this.globalData.isLogin = false
    }
  },

  validateToken() {
    const { getBaseUrl } = require('./config/api')
    const baseUrl = getBaseUrl()
    wx.request({
      url: baseUrl + '/api/auth/me',
      method: 'GET',
      header: {
        'Authorization': 'Bearer ' + this.globalData.accessToken
      },
      success: (res) => {
        if (res.statusCode === 200 && res.data.code === 0) {
          this.globalData.isLogin = true
          this.globalData.userInfo = res.data.data
        } else if (res.statusCode === 401 || (res.data && res.data.code === 401)) {
          // access_token 失效，尝试用 refresh_token 续期
          this.tryRefresh().then(() => {
            this.globalData.isLogin = true
          }).catch(() => {
            this.clearLoginInfo()
          })
        } else {
          this.clearLoginInfo()
        }
      },
      fail: () => {
        console.warn('[App] 验证登录状态失败，保留本地状态')
      }
    })
  },

  tryRefresh() {
    return new Promise((resolve, reject) => {
      const refreshToken = this.globalData.refreshToken || wx.getStorageSync('refreshToken')
      if (!refreshToken) return reject(new Error('no refresh token'))

      const { getBaseUrl } = require('./config/api')
      wx.request({
        url: getBaseUrl() + '/api/auth/refresh',
        method: 'POST',
        data: { refreshToken },
        success: (res) => {
          if (res.statusCode === 200 && res.data && res.data.code === 0) {
            const { accessToken, refreshToken: newRefreshToken } = res.data.data
            this.globalData.accessToken = accessToken
            if (newRefreshToken) this.globalData.refreshToken = newRefreshToken
            wx.setStorageSync('accessToken', accessToken)
            if (newRefreshToken) wx.setStorageSync('refreshToken', newRefreshToken)
            resolve()
          } else {
            reject(new Error('refresh failed'))
          }
        },
        fail: reject
      })
    })
  },

  setLoginInfo(accessToken, refreshToken, userInfo) {
    this.globalData.accessToken = accessToken
    this.globalData.refreshToken = refreshToken
    this.globalData.userInfo = userInfo
    this.globalData.isLogin = true
    wx.setStorageSync('accessToken', accessToken)
    wx.setStorageSync('refreshToken', refreshToken)
    wx.setStorageSync('userInfo', userInfo)
  },

  clearLoginInfo() {
    this.globalData.accessToken = null
    this.globalData.refreshToken = null
    this.globalData.userInfo = null
    this.globalData.isLogin = false
    wx.removeStorageSync('accessToken')
    wx.removeStorageSync('refreshToken')
    wx.removeStorageSync('userInfo')
  }
})
