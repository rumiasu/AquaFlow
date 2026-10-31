// 认证相关接口（后端: LoginController）
const { post } = require('../utils/request')
const { API } = require('../config/api')
const { isDevelopEnv, isTestLoginClientEnv } = require('../config/api')

// 微信登录（后端: POST /api/auth/wx-login）
// code 来自 wx.login，后端用它换 openid 并查/建用户
const wxLogin = (code, agreement) => {
  return post(API.WX_LOGIN, agreement ? { code, agreement } : { code })
}

// 临时账号只提交凭据，不发送角色、用户编号或站点授权。
const testLogin = (account, password) => {
  if (!isTestLoginClientEnv()) throw new Error('当前版本不支持测试账号登录')
  return post(API.TEST_LOGIN, { account, password })
}

// 原本地开发API保留给既有合成工具；页面入口已改为测试账号表单。
const devLogin = (nickname) => {
  if (!isDevelopEnv()) throw new Error('开发登录仅在 develop 环境可用')
  return post(API.DEV_LOGIN, { openid: 'dev-openid-001', nickname: nickname || '测试用户' })
}

// 更新用户资料（后端: POST /api/auth/update-profile）
const updateProfile = (data) => {
  return post(API.UPDATE_PROFILE, data)
}

// 管理员登录
const login = (username, password) => {
  return post(API.LOGIN, { username, password })
}

module.exports = { testLogin, wxLogin, devLogin, updateProfile, login }
