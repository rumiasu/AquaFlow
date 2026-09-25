const { STORAGE_KEYS } = require('./utils/storage-keys')
const { stationStorage } = require('./utils/storage')

App({
  globalData: {
    userInfo: null,
    accessToken: null,
    refreshToken: null,
    isLogin: false,
    // 购物车按站隔离：{ [stationId]: { [productId]: qty } }
    cart: {}
  },

  onLaunch() {
    this.checkLogin()
  },

  checkLogin() {
    const accessToken = wx.getStorageSync(STORAGE_KEYS.ACCESS_TOKEN)
    const refreshToken = wx.getStorageSync(STORAGE_KEYS.REFRESH_TOKEN)
    const userInfo = wx.getStorageSync(STORAGE_KEYS.USER_INFO)
    if (accessToken && userInfo) {
      this.globalData.accessToken = accessToken
      this.globalData.refreshToken = refreshToken
      this.globalData.userInfo = userInfo
      this.globalData.isLogin = true
      this.validateToken()
    } else {
      this.globalData.isLogin = false
    }
  },

  validateToken() {
    const { getBaseUrl, API } = require('./config/api')
    const baseUrl = getBaseUrl()
    wx.request({
      url: baseUrl + API.ME,
      method: 'GET',
      header: {
        'Authorization': 'Bearer ' + this.globalData.accessToken
      },
      success: (res) => {
        if (res.statusCode === 200 && res.data.code === 0) {
          this.globalData.isLogin = true
          this.globalData.userInfo = res.data.data
        } else if (res.statusCode === 401 || (res.data && res.data.code === 401)) {
          this.tryRefresh().then(() => {
            this.globalData.isLogin = true
          }).catch(() => {
            this.clearLoginInfo()
            wx.redirectTo({ url: '/pages/login/index' })
          })
        } else {
          this.clearLoginInfo()
          wx.redirectTo({ url: '/pages/login/index' })
        }
      },
      fail: () => {
        console.warn('[App] 验证登录状态失败，保留本地状态')
      }
    })
  },

  tryRefresh() {
    return new Promise((resolve, reject) => {
      const refreshToken = this.globalData.refreshToken || wx.getStorageSync(STORAGE_KEYS.REFRESH_TOKEN)
      if (!refreshToken) return reject(new Error('no refresh token'))

      const { getBaseUrl, API } = require('./config/api')
      wx.request({
        // 统一走 API.REFRESH，勿硬编码路径；同文件 validateToken 用 API.ME。
        url: getBaseUrl() + API.REFRESH,
        method: 'POST',
        data: { refreshToken },
        success: (res) => {
          if (res.statusCode === 200 && res.data && res.data.code === 0) {
            const { accessToken, refreshToken: newRefreshToken } = res.data.data
            this.globalData.accessToken = accessToken
            if (newRefreshToken) this.globalData.refreshToken = newRefreshToken
            wx.setStorageSync(STORAGE_KEYS.ACCESS_TOKEN, accessToken)
            if (newRefreshToken) wx.setStorageSync(STORAGE_KEYS.REFRESH_TOKEN, newRefreshToken)
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
    wx.setStorageSync(STORAGE_KEYS.ACCESS_TOKEN, accessToken)
    wx.setStorageSync(STORAGE_KEYS.REFRESH_TOKEN, refreshToken)
    wx.setStorageSync(STORAGE_KEYS.USER_INFO, userInfo)
  },

  clearLoginInfo() {
    this.globalData.accessToken = null
    this.globalData.refreshToken = null
    this.globalData.userInfo = null
    this.globalData.isLogin = false
    wx.removeStorageSync(STORAGE_KEYS.ACCESS_TOKEN)
    wx.removeStorageSync(STORAGE_KEYS.REFRESH_TOKEN)
    wx.removeStorageSync(STORAGE_KEYS.USER_INFO)
    // customerId 必须一起清：它由登录页写入、由 utils/token.js 的 getCustomerId() 读取，
    // 而多个页面会把它当 customerId 传给 /api/payments、/api/addresses。
    // 旧实现漏了这一行 → 退出登录后旧 customerId 仍留在本地，
    // 下一个登录的人（或未登录状态）会拿上一位客户的身份去构造请求参数。
    // 身份校验在后端以 JWT 为准（不会越权），但前端展示与请求参数会错人，属必须清掉的脏状态。
    wx.removeStorageSync(STORAGE_KEYS.CUSTOMER_ID)
  },

  // ===== 购物车工具方法（按站隔离）=====
  getCart(stationId) {
    const sid = String(stationId)
    if (!this.globalData.cart[sid]) {
      this.globalData.cart[sid] = {}
    }
    return this.globalData.cart[sid]
  },

  getCartCount(stationId) {
    const cart = this.getCart(stationId)
    return Object.values(cart).reduce((sum, qty) => sum + (parseInt(qty) || 0), 0)
  },

  addToCart(stationId, productId, qty = 1) {
    const cart = this.getCart(stationId)
    const pid = String(productId)
    cart[pid] = (parseInt(cart[pid]) || 0) + qty
  },

  removeFromCart(stationId, productId) {
    const cart = this.getCart(stationId)
    delete cart[String(productId)]
  },

  setCartQty(stationId, productId, qty) {
    const cart = this.getCart(stationId)
    const pid = String(productId)
    if (qty <= 0) {
      delete cart[pid]
    } else {
      cart[pid] = qty
    }
  },

  clearCart(stationId) {
    const sid = String(stationId)
    this.globalData.cart[sid] = {}
  },

  // 获取当前选择的站点 ID（从 stationStorage 读取）
  getCurrentStationId() {
    return stationStorage.getId()
  },

  // 获取当前站点的购物车
  getCurrentCart() {
    const stationId = this.getCurrentStationId()
    return stationId ? this.getCart(stationId) : {}
  },

  getCurrentCartCount() {
    const stationId = this.getCurrentStationId()
    return stationId ? this.getCartCount(stationId) : 0
  }
})