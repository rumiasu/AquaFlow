// 统一 token 获取（带命名空间）
const { STORAGE_KEYS } = require('./storage-keys')

const getAccessToken = () => {
  const app = getApp()
  if (app.globalData.isLogin === false) return null
  return app.globalData.accessToken || wx.getStorageSync(STORAGE_KEYS.ACCESS_TOKEN)
}

const getRefreshToken = () => {
  const app = getApp()
  if (app.globalData.isLogin === false) return null
  return app.globalData.refreshToken || wx.getStorageSync(STORAGE_KEYS.REFRESH_TOKEN)
}

const getCustomerId = () => {
  const app = getApp()
  if (app.globalData.isLogin === false) return null
  return app.globalData.customerId || wx.getStorageSync(STORAGE_KEYS.CUSTOMER_ID)
}

// 2026-10-02：客户 ID 相同也可能已经退出重登；请求须绑定登录周期，不能只在 POST 前查 ID。
let currentSession = null
let nextEpoch = 0

function readSession() {
  const app = getApp()
  return { app, customerId: getCustomerId() || null, accessToken: getAccessToken() || null,
    refreshToken: getRefreshToken() || null, loggedIn: app.globalData.isLogin !== false }
}

/** 登录/退出生命周期调用；同一客户、同一令牌再次登录也必须形成新周期。 */
function beginSession() { currentSession = null; nextEpoch++ }

/** 捕获最初身份和令牌所属周期；外部身份/令牌改变保守按新会话处理。 */
function captureSession() {
  const now = readSession()
  if (!currentSession || ['app', 'customerId', 'accessToken', 'refreshToken', 'loggedIn']
    .some(k => currentSession[k] !== now[k])) {
    currentSession = Object.assign({ epoch: ++nextEpoch, credentialVersion: 0 }, now)
  }
  return Object.assign({}, currentSession)
}

function isCurrentSession(session) {
  return !!session && captureSession().epoch === session.epoch
}

/** 同周期续期也会使旧凭据过时；旧刷新结果不能覆盖或清掉后来取得的令牌。 */
function isCurrentCredentials(session) {
  const current = captureSession()
  return !!session && current.epoch === session.epoch && current.credentialVersion === session.credentialVersion
}

/** 仅当前凭据可接受续期；成功后保留登录周期并递增凭据版本。 */
function acceptRefreshedTokens(session, accessToken, refreshToken) {
  if (!isCurrentCredentials(session) || typeof accessToken !== 'string' || !accessToken) return false
  const nextRefresh = refreshToken || currentSession.refreshToken
  wx.setStorageSync(STORAGE_KEYS.ACCESS_TOKEN, accessToken)
  if (nextRefresh) wx.setStorageSync(STORAGE_KEYS.REFRESH_TOKEN, nextRefresh)
  session.app.globalData.accessToken = accessToken
  session.app.globalData.refreshToken = nextRefresh
  currentSession = Object.assign({}, currentSession, { accessToken, refreshToken: nextRefresh,
    credentialVersion: currentSession.credentialVersion + 1 })
  return true
}

module.exports = { getAccessToken, getRefreshToken, getCustomerId, captureSession, isCurrentSession, isCurrentCredentials,
  beginSession, acceptRefreshedTokens }
