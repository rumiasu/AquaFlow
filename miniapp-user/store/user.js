// 用户状态管理
const { storage } = require('../utils/storage')

const userStore = {
  state: {
    userInfo: null,
    token: null,
    isLogin: false
  },

  // 初始化
  init() {
    const token = storage.get('token')
    const userInfo = storage.get('userInfo')
    if (token && userInfo) {
      this.state.token = token
      this.state.userInfo = userInfo
      this.state.isLogin = true
    }
  },

  // 设置登录信息
  setLoginInfo(token, userInfo) {
    this.state.token = token
    this.state.userInfo = userInfo
    this.state.isLogin = true
    storage.set('token', token)
    storage.set('userInfo', userInfo)
  },

  // 更新用户信息
  updateUserInfo(userInfo) {
    this.state.userInfo = { ...this.state.userInfo, ...userInfo }
    storage.set('userInfo', this.state.userInfo)
  },

  // 清除登录信息
  clearLoginInfo() {
    this.state.token = null
    this.state.userInfo = null
    this.state.isLogin = false
    storage.remove('token')
    storage.remove('userInfo')
  },

  // 检查登录状态
  checkLogin() {
    return this.state.isLogin
  }
}

module.exports = userStore
