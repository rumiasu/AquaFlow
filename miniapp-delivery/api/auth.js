// 认证相关接口（后端: LoginController）
const { post } = require('../utils/request')
const { API } = require('../config/api')
const { isDevelopEnv, isTestLoginClientEnv } = require('../config/api')

// 临时账号只提交凭据，不发送角色、用户编号或站点授权。
const testLogin = (account, password) => {
  if (!isTestLoginClientEnv()) throw new Error('当前版本不支持测试账号登录')
  return post(API.TEST_LOGIN, { account, password })
}

const devLogin = (role = 'DELIVERY') => {
  if (!isDevelopEnv()) throw new Error('开发登录仅在 develop 环境可用')
  return post(API.DEV_LOGIN, { role })
}

const login = (data) => {
  return post(API.LOGIN, data)
}

// 微信登录（用户小程序入口）：仅 code，自动注册/登录
const wxLogin = (code) => {
  return post(API.WX_LOGIN, { code })
}

// Exact displayed formal versions are optional; draft/offline/dev login never asserts acceptance.
const wxLoginStaff = (code, agreement) => {
  return post(API.WX_LOGIN_STAFF, agreement ? { code, agreement } : { code })
}

const logout = () => {
  return post(API.LOGOUT)
}

const getMe = () => {
  const { get } = require('../utils/request')
  return get(API.ME)
}

const updateProfile = (data) => {
  return post(API.UPDATE_PROFILE, data)
}

module.exports = { testLogin, devLogin, login, wxLogin, wxLoginStaff, logout, getMe, updateProfile }
