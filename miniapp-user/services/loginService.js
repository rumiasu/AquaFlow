// 登录业务逻辑
const { storage } = require('../utils/storage')

const loginService = {
  checkLogin: () => {
    const token = storage.get('accessToken')
    const userInfo = storage.get('userInfo')
    return {
      isLogin: !!token && !!userInfo,
      token,
      userInfo
    }
  },

  logout: () => {
    const app = getApp()
    storage.remove('accessToken')
    storage.remove('refreshToken')
    storage.remove('userInfo')
    storage.remove('customerId')
    if (app) {
      app.globalData.accessToken = null
      app.globalData.refreshToken = null
      app.globalData.userInfo = null
      app.globalData.isLogin = false
    }
    wx.reLaunch({ url: '/pages/login/index' })
  }
}

module.exports = loginService
