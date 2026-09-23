// 认证相关接口（后端: LoginController）
const { post } = require('../utils/request')
const { API } = require('../config/api')

// 微信登录（后端: POST /api/auth/wx-login）
// code 来自 wx.login，后端用它换 openid 并查/建用户
const wxLogin = (code) => {
  return post(API.WX_LOGIN, { code })
}

// 开发模式登录（后端: POST /api/auth/dev-login）
const devLogin = (nickname) => {
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

module.exports = { wxLogin, devLogin, updateProfile, login }
