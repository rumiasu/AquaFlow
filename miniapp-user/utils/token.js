// 统一 token 获取（带命名空间）
const { STORAGE_KEYS } = require('./storage-keys')

const getAccessToken = () => {
  const app = getApp()
  return app.globalData.accessToken || wx.getStorageSync(STORAGE_KEYS.ACCESS_TOKEN)
}

const getRefreshToken = () => {
  const app = getApp()
  return app.globalData.refreshToken || wx.getStorageSync(STORAGE_KEYS.REFRESH_TOKEN)
}

const getCustomerId = () => {
  const app = getApp()
  return app.globalData.customerId || wx.getStorageSync(STORAGE_KEYS.CUSTOMER_ID)
}

module.exports = { getAccessToken, getRefreshToken, getCustomerId }