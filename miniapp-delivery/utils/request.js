// 网络请求封装（JWT 双 Token + 自动续期）
const { getBaseUrl, API } = require('../config/api')
const { STORAGE_KEYS } = require('../utils/storage-keys')
const navigation = require('./navigation')

let refreshFlight = null

// 登录周期与普通续期分开：同账号重新登录也会产生新的 generation。
function captureSession() {
  const app = getApp()
  const data = app.globalData
  const user = data.userInfo || {}
  return {
    app, generation: app._loginGeneration || 0,
    identity: JSON.stringify([!!data.isLogin, user.staffId, user.role, user.stationId, user.bindStatus]),
    // App restores cache on launch. A missing in-memory credential must not
    // borrow a cached credential belonging to an earlier login.
    accessToken: data.isLogin ? data.accessToken : null,
    refreshToken: data.isLogin ? data.refreshToken : null
  }
}

function sameSession(a, b) {
  return a.app === b.app && a.generation === b.generation && a.identity === b.identity
}

function currentSession(session) { return sameSession(session, captureSession()) }
function sameCredentials(a, b) { return a.accessToken === b.accessToken && a.refreshToken === b.refreshToken }
function changedSessionError() {
  const error = new Error('登录状态已变化，请重新操作')
  error.sessionChanged = true
  return error
}

// 重试沿用发起时的业务内容和幂等键，避免调用方后续编辑改变原请求。
function snapshot(value) {
  if (Array.isArray(value)) return value.map(snapshot)
  if (value && typeof value === 'object') {
    const copy = {}
    Object.keys(value).forEach(key => { copy[key] = snapshot(value[key]) })
    return copy
  }
  return value
}

const request = (options) => {
  options = { ...options, data: snapshot(options.data), query: snapshot(options.query), header: snapshot(options.header) }
  return new Promise((resolve, reject) => {
    const session = captureSession()
    const baseUrl = getBaseUrl()
    const accessToken = session.accessToken

    const header = {
      'Content-Type': 'application/json',
      ...options.header
    }

    if (accessToken) {
      header['Authorization'] = `Bearer ${accessToken}`
    }

    // 拼接 query 参数
    let url = baseUrl + options.url
    if (options.query) {
      const qs = Object.keys(options.query)
        .filter(k => options.query[k] !== undefined && options.query[k] !== null)
        .map(k => `${k}=${encodeURIComponent(options.query[k])}`)
        .join('&')
      if (qs) {
        url += (url.includes('?') ? '&' : '?') + qs
      }
    }

    wx.request({
      url,
      method: options.method || 'GET',
      data: options.data,
      header,
      timeout: 15000,
      success: (res) => {
        if (!currentSession(session)) { reject(changedSessionError()); return }
        const error = responseError(res)
        if (error) { reject(error); return }
        if (res.statusCode === 200) {
          if (res.data.code === 0 || res.data.code === 200) {
            resolve(res.data)
          } else if (res.data.code === 401) {
            handle401(options, resolve, reject, session)
          } else {
            const businessError = new Error(responseMessage(res.data))
            businessError.businessRejected = res.data.code === 1
            reject(businessError)
          }
        } else if (res.statusCode === 401) {
          handle401(options, resolve, reject, session)
        } else {
          // [2026-09-20] 原来直接把状态码拼给用户看（「网络错误 500」）—— 站长/配送员看不懂也没法处理。
          // 保留状态码在括号里，排查时仍能一眼看出是 4xx 还是 5xx。
          reject(new Error('服务暂时不可用（HTTP ' + res.statusCode + '），请稍后重试'))
        }
      },
      fail: (err) => {
        if (!currentSession(session)) { reject(changedSessionError()); return }
        console.error('[request] wx.request 失败:', JSON.stringify(err))
        reject(toNetworkError(err))
      }
    })
  })
}

// 2026-10-02：平台 success 在 Promise executor 结束后触发；直接读空 body 曾异步抛错，页面永久加载。
// 只检查响应外壳/code；message 是错误元数据，不能给合法成功新增业务门槛。
function responseError(res) {
  if (!res || typeof res !== 'object' || Array.isArray(res) || !Number.isInteger(res.statusCode)) return malformedResponseError()
  if (res.statusCode !== 200) return null // 真 HTTP401 即使无 body 仍须进入原续期路径。
  const body = res.data
  return !body || typeof body !== 'object' || Array.isArray(body) || !Number.isInteger(body.code) ? malformedResponseError() : null
}

function malformedResponseError() { return new Error('收到的数据不完整，请重试') }

function responseMessage(body, fallback = '请求失败') {
  return body && typeof body.message === 'string' && body.message.trim() ? body.message : fallback
}

function validRefreshData(data) {
  return data && typeof data === 'object' && !Array.isArray(data)
    && typeof data.accessToken === 'string' && !!data.accessToken.trim()
    && typeof data.refreshToken === 'string' && !!data.refreshToken.trim()
}

/**
 * 把 wx 的失败对象归一化成带可读 message 的 Error。
 *
 * <p>[2026-09-20 真机联调] 这不是美化文案，是修一个**系统性根因**：`wx.request` 的 `fail`
 * 回调拿到的是 `{errMsg: "request:fail fail:time out", errno: 5}` 这种**没有 `message` 字段**的对象，
 * 原样 `reject` 出去后，全端大量 `err.message || 'xxx失败'` 会**全部走兜底分支** ——
 * 于是真机弱网/后端没起时，配送员看到的是「操作失败: 」这类**没有原因**的提示，
 * 排查时也拿不到任何线索（且本端 `catch` 数量远多于顾客端）。</p>
 *
 * <p>⚠️ 别把这里改回 `reject(err)`：`err.message` 恒为 `undefined` 是 wx 的既定形状，
 * 不是偶发。要加新文案就在这里加分支，不要在调用点各写一套。</p>
 *
 * <p>⚠️⚠️ **[2026-09-22 真机实测] 超时判据必须同时匹配 `timeout` 与 `time out`（两个词）。**
 * 微信实际给的是 `"request:fail fail:time out"` —— 原来的 `/timeout/i` **匹配不上**，
 * 于是**超时被误报成「网络连接失败」**，把最有用的那句"请确认手机与后端在同一网络"吞掉了；
 * 而"超时"恰恰是"手机路由不到后端"最典型的症状（跨网段时对方不回 RST，只会静默丢包）。</p>
 */
function toNetworkError(err) {
  const raw = (err && (err.errMsg || err.message)) || ''
  if (/time\s*out/i.test(raw)) {
    return new Error('网络超时，请检查手机网络后重试')
  }
  if (/fail/i.test(raw)) {
    return new Error('网络连接失败，请检查网络后重试')
  }
  return new Error(raw || '网络连接失败，请重试')
}

// 每个续期任务只拥有本次登录的等待请求，旧 complete 不释放新任务。
function handle401(originalOptions, resolve, reject, session) {
  const current = captureSession()
  if (!sameSession(session, current)) { reject(changedSessionError()); return }
  if (originalOptions.url.includes('/auth/refresh') ||
      originalOptions.url.includes('/auth/login') ||
      originalOptions.url.includes('/auth/wx-login') ||
      originalOptions.url.includes('/auth/dev-login')) {
    clearAndRedirect(session)
    reject(new Error('登录已过期'))
    return
  }
  if (!sameCredentials(session, current) && current.accessToken) {
    retryRequest(originalOptions, current.accessToken, current).then(resolve, reject)
    return
  }
  const waiter = { options: originalOptions, resolve, reject, session }
  if (refreshFlight && !refreshFlight.done && sameSession(refreshFlight.session, current)
      && sameCredentials(refreshFlight.session, current)) {
    refreshFlight.waiters.push(waiter)
    return
  }
  if (!current.refreshToken) {
    clearAndRedirect(session)
    reject(new Error('登录已过期'))
    return
  }
  const flight = { session: current, waiters: [waiter], done: false }
  refreshFlight = flight

  // 外部同会话已完成续期时，旧响应不再写 token 或登出，只用当前凭据重试。
  function superseded() {
    if (flight.done) return true
    const latest = captureSession()
    if (!sameSession(flight.session, latest)) {
      settleFlight(flight, changedSessionError())
      return true
    }
    if (!sameCredentials(flight.session, latest)) {
      settleFlight(flight, latest.accessToken ? null : changedSessionError(), latest.accessToken)
      return true
    }
    return false
  }

  wx.request({
    url: getBaseUrl() + API.REFRESH,
    method: 'POST',
    data: { refreshToken: current.refreshToken },
    timeout: 15000,
    success: (res) => {
      if (superseded()) return
      const error = responseError(res)
        || (res.statusCode === 200 && res.data.code === 0 && !validRefreshData(res.data.data) ? malformedResponseError() : null)
      if (error) {
        failFlight(flight, error)
        return
      }
      if (res.statusCode === 200 && res.data.code === 0) {
        const { accessToken, refreshToken: newRefreshToken } = res.data.data
        const app = flight.session.app
        app.globalData.accessToken = accessToken
        app.globalData.refreshToken = newRefreshToken
        try {
          wx.setStorageSync(STORAGE_KEYS.ACCESS_TOKEN, accessToken)
          wx.setStorageSync(STORAGE_KEYS.REFRESH_TOKEN, newRefreshToken)
        } catch (_) {
          // The server has already rotated the pair; do not reuse the old refresh.
          failFlight(flight, new Error('登录凭据未能保存，请重新登录'))
          return
        }
        settleFlight(flight, null, accessToken)
      } else {
        failFlight(flight, new Error(responseMessage(res.data, '登录已过期')))
      }
    },
    fail: (err) => {
      if (superseded()) return
      failFlight(flight, toNetworkError(err))
    },
    complete: () => {
      if (refreshFlight === flight) refreshFlight = null
    }
  })
}

function retryRequest(options, newToken, session) {
  return new Promise((resolve, reject) => {
    if (!currentSession(session)) { reject(changedSessionError()); return }
    const header = {
      'Content-Type': 'application/json',
      ...options.header,
      'Authorization': `Bearer ${newToken}`
    }
    let url = getBaseUrl() + options.url
    if (options.query) {
      const qs = Object.keys(options.query)
        .filter(k => options.query[k] !== undefined && options.query[k] !== null)
        .map(k => `${k}=${encodeURIComponent(options.query[k])}`)
        .join('&')
      if (qs) url += (url.includes('?') ? '&' : '?') + qs
    }
    wx.request({
      url,
      method: options.method || 'GET',
      data: options.data,
      header,
      timeout: 15000,
      success: (res) => {
        if (!currentSession(session)) { reject(changedSessionError()); return }
        const error = responseError(res)
        if (error) { reject(error); return }
        if (res.statusCode === 200 && (res.data.code === 0 || res.data.code === 200)) {
          resolve(res.data)
        } else {
          const businessError = new Error(responseMessage(res.data, res.statusCode === 401 ? '登录已过期' : '请求失败'))
          businessError.businessRejected = res.statusCode === 200 && res.data.code === 1
          reject(businessError)
        }
      },
      fail: (err) => reject(currentSession(session) ? toNetworkError(err) : changedSessionError())
    })
  })
}

function failFlight(flight, error) {
  try {
    clearAndRedirect(flight.session)
  } catch (_) {
    console.warn('[request] 登录状态清理未完成，请重新登录')
  } finally {
    settleFlight(flight, error)
  }
}

function settleFlight(flight, error, token) {
  if (flight.done) return
  flight.done = true
  if (refreshFlight === flight) refreshFlight = null
  const waiters = flight.waiters
  flight.waiters = []
  waiters.forEach(item => {
    if (error) item.reject(error)
    else retryRequest(item.options, token, item.session).then(item.resolve, item.reject)
  })
}

function clearAndRedirect(session) {
  if (!currentSession(session)) return
  const app = session.app
  const removeCache = keys => keys.forEach(key => {
    try { wx.removeStorageSync(key) } catch (_) { console.warn('[request] 登录缓存清理失败') }
  })
  removeCache([STORAGE_KEYS.STAFF_ID, STORAGE_KEYS.STAFF_NAME, STORAGE_KEYS.STAFF_ROLE, STORAGE_KEYS.STATION_ID])
  if (typeof app.clearLoginState === 'function') {
    app.clearLoginState()
  } else {
    const previousSession = navigation.sessionKey()
    app._loginGeneration = (app._loginGeneration || 0) + 1
    removeCache([STORAGE_KEYS.ACCESS_TOKEN, STORAGE_KEYS.REFRESH_TOKEN, STORAGE_KEYS.USER_INFO])
    app.globalData.accessToken = null
    app.globalData.refreshToken = null
    app.globalData.userInfo = null
    app.globalData.isLogin = false
    if (navigation.sessionKey() !== previousSession) navigation.invalidate()
  }
  navigation.open('/pages/login/index', { mode: 'reset', guard: true, owner: app })
}

const get = (url, query) => request({ url, method: 'GET', query })
const post = (url, data, query) => request({ url, method: 'POST', data, query })
const put = (url, data, query) => request({ url, method: 'PUT', data, query })
const del = (url, query) => request({ url, method: 'DELETE', query })

module.exports = { request, get, post, put, del }
