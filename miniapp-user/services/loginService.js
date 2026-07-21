// 登录业务逻辑
const { storage } = require('../utils/storage')

const loginService = {
  // 检查登录状态
  checkLogin: () => {
    const token = storage.get('token')
    const userInfo = storage.get('userInfo')
    return {
      isLogin: !!token && !!userInfo,
      token,
      userInfo
    }
  },

  // 退出登录
  logout: () => {
    storage.remove('token')
    storage.remove('userInfo')
    storage.remove('customerId')
    wx.reLaunch({ url: '/pages/home/index' })
  }
}

module.exports = loginService
