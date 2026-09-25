// 用户状态管理
const { storage } = require('../utils/storage')

const userStore = {
  state: {
    userInfo: null,
    accessToken: null,
    refreshToken: null,
    isLogin: false
  },

  init() {
    const accessToken = storage.get('accessToken')
    const refreshToken = storage.get('refreshToken')
    const userInfo = storage.get('userInfo')
    if (accessToken && userInfo) {
      this.state.accessToken = accessToken
      this.state.refreshToken = refreshToken
      this.state.userInfo = userInfo
      this.state.isLogin = true
    }
  },

  setLoginInfo(accessToken, refreshToken, userInfo) {
    this.state.accessToken = accessToken
    this.state.refreshToken = refreshToken
    this.state.userInfo = userInfo
    this.state.isLogin = true
    storage.set('accessToken', accessToken)
    storage.set('refreshToken', refreshToken)
    storage.set('userInfo', userInfo)
  },

  updateUserInfo(userInfo) {
    this.state.userInfo = { ...this.state.userInfo, ...userInfo }
    storage.set('userInfo', this.state.userInfo)
  },

  clearLoginInfo() {
    this.state.accessToken = null
    this.state.refreshToken = null
    this.state.userInfo = null
    this.state.isLogin = false
    storage.remove('accessToken')
    storage.remove('refreshToken')
    storage.remove('userInfo')
  },

  checkLogin() {
    return this.state.isLogin
  }
}

module.exports = userStore
