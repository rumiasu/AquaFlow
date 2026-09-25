// 认证相关接口（后端: LoginController）
const { post } = require('../utils/request')
const { API } = require('../config/api')

const devLogin = (role = 'DELIVERY') => {
  return post(API.DEV_LOGIN, { role })
}

const login = (data) => {
  return post(API.LOGIN, data)
}

// 微信登录（用户小程序入口）：仅 code，自动注册/登录
const wxLogin = (code) => {
  return post(API.WX_LOGIN, { code })
}

// 微信登录（配送员小程序）：仅 code，已绑定员工直接登录
const wxLoginStaff = (code) => {
  return post(API.WX_LOGIN_STAFF, { code })
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

module.exports = { devLogin, login, wxLogin, wxLoginStaff, logout, getMe, updateProfile }
