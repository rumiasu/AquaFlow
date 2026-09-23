// 网络请求封装（JWT 双 Token + 自动续期）
const { getBaseUrl, API } = require('../config/api')
const { STORAGE_KEYS } = require('../utils/storage-keys')

let isRefreshing = false
let refreshQueue = []

const request = (options) => {
  return new Promise((resolve, reject) => {
    const app = getApp()
    const baseUrl = getBaseUrl()
    const accessToken = app.globalData.accessToken || wx.getStorageSync(STORAGE_KEYS.ACCESS_TOKEN)

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
        if (res.statusCode === 200) {
          if (res.data.code === 0 || res.data.code === 200) {
            resolve(res.data)
          } else if (res.data.code === 401) {
            handle401(options, resolve, reject)
          } else {
            reject(new Error(res.data.message || '请求失败'))
          }
        } else if (res.statusCode === 401) {
          handle401(options, resolve, reject)
        } else {
          // [2026-09-20] 原来直接把状态码拼给用户看（「网络错误 500」）—— 站长/配送员看不懂也没法处理。
          // 保留状态码在括号里，排查时仍能一眼看出是 4xx 还是 5xx。
          reject(new Error('服务暂时不可用（HTTP ' + res.statusCode + '），请稍后重试'))
        }
      },
      fail: (err) => {
        console.error('[request] wx.request 失败:', JSON.stringify(err))
        reject(toNetworkError(err))
      }
    })
  })
}

/**
 * 把 wx 的失败对象归一化成带可读 message 的 Error。
 *
 * <p>[2026-09-20 真机联调] 这不是美化文案，是修一个**系统性根因**：`wx.request` 的 `fail`
 * 回调拿到的是 `{errMsg: "request:fail timeout"}` 这种**没有 `message` 字段**的对象，
 * 原样 `reject` 出去后，全端大量 `err.message || 'xxx失败'` 会**全部走兜底分支** ——
 * 于是真机弱网/后端没起时，配送员看到的是「操作失败: 」这类**没有原因**的提示，
 * 排查时也拿不到任何线索（且本端 `catch` 数量远多于顾客端）。</p>
 *
 * <p>⚠️ 别把这里改回 `reject(err)`：`err.message` 恒为 `undefined` 是 wx 的既定形状，
 * 不是偶发。要加新文案就在这里加分支，不要在调用点各写一套。</p>
 */
function toNetworkError(err) {
  const raw = (err && (err.errMsg || err.message)) || ''
  if (/timeout/i.test(raw)) {
    return new Error('网络超时，请确认手机与后端在同一网络后重试')
  }
  if (/fail/i.test(raw)) {
    return new Error('网络连接失败，请检查网络后重试')
  }
  return new Error(raw || '网络连接失败，请重试')
}

// 401 自动续期处理
function handle401(originalOptions, resolve, reject) {
  if (originalOptions.url.includes('/auth/refresh') ||
      originalOptions.url.includes('/auth/login') ||
      originalOptions.url.includes('/auth/wx-login') ||
      originalOptions.url.includes('/auth/dev-login')) {
    clearAndRedirect()
    reject(new Error('登录已过期'))
    return
  }

  if (isRefreshing) {
    refreshQueue.push({ resolve, reject, options: originalOptions })
    return
  }

  isRefreshing = true
  const refreshToken = wx.getStorageSync(STORAGE_KEYS.REFRESH_TOKEN)

  if (!refreshToken) {
    clearAndRedirect()
    reject(new Error('登录已过期'))
    isRefreshing = false
    return
  }

  wx.request({
    // 统一走 API.REFRESH。勿硬编码 '/api/auth/refresh'——本项目出现过
    // "常量定义了没人用、路径却散落硬编码在四处"的不一致（2026-09-14 已统一）。
    url: getBaseUrl() + API.REFRESH,
    method: 'POST',
    data: { refreshToken },
    // [2026-09-20] 必须带 timeout：refresh 请求原本没有任何超时，真机切网/弱网时可能
    // 既不 success 也不 fail → refreshQueue 里的 promise 永不 settle → 按钮一直转圈。
    // 有了超时会走 fail 分支，processQueue 才会把排队的请求放掉。
    timeout: 15000,
    success: (res) => {
      if (res.statusCode === 200 && res.data && res.data.code === 0) {
        const { accessToken, refreshToken: newRefreshToken } = res.data.data
        const app = getApp()
        app.globalData.accessToken = accessToken
        if (newRefreshToken) app.globalData.refreshToken = newRefreshToken
        wx.setStorageSync(STORAGE_KEYS.ACCESS_TOKEN, accessToken)
        if (newRefreshToken) wx.setStorageSync(STORAGE_KEYS.REFRESH_TOKEN, newRefreshToken)

        retryRequest(originalOptions, accessToken).then(resolve).catch(reject)
        processQueue(null, accessToken)
      } else {
        clearAndRedirect()
        reject(new Error('登录已过期'))
        processQueue(new Error('refresh failed'))
      }
    },
    fail: (err) => {
      clearAndRedirect()
      reject(new Error('网络错误'))
      // 排队的请求也要拿到**可读**的错误：原来是原样透传 wx 的 `{errMsg}` 对象，
      // 那些请求的 catch 里 `err.message` 同样是 undefined。
      processQueue(toNetworkError(err))
    },
    complete: () => {
      isRefreshing = false
    }
  })
}

function retryRequest(options, newToken) {
  return new Promise((resolve, reject) => {
    const baseUrl = getBaseUrl()
    const header = {
      'Content-Type': 'application/json',
      ...options.header,
      'Authorization': `Bearer ${newToken}`
    }

    let url = baseUrl + options.url
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
      // [2026-09-20] 与首次请求（上面的 `timeout: 15000`）保持一致。
      // 原实现漏了这一个，续期后的重试会走系统默认超时（60s），弱网下表现为长时间卡死。
      timeout: 15000,
      success: (res) => {
        if (res.statusCode === 200 && (res.data.code === 0 || res.data.code === 200)) {
          resolve(res.data)
        } else {
          reject(new Error(res.data.message || '请求失败'))
        }
      },
      fail: (err) => reject(toNetworkError(err))
    })
  })
}

function processQueue(error, token) {
  refreshQueue.forEach(item => {
    if (error) {
      item.reject(error)
    } else {
      retryRequest(item.options, token).then(item.resolve).catch(item.reject)
    }
  })
  refreshQueue = []
}

function clearAndRedirect() {
  wx.removeStorageSync(STORAGE_KEYS.ACCESS_TOKEN)
  wx.removeStorageSync(STORAGE_KEYS.REFRESH_TOKEN)
  wx.removeStorageSync(STORAGE_KEYS.USER_INFO)
  wx.removeStorageSync(STORAGE_KEYS.STAFF_ID)
  wx.removeStorageSync(STORAGE_KEYS.STAFF_NAME)
  wx.removeStorageSync(STORAGE_KEYS.STAFF_ROLE)
  wx.removeStorageSync(STORAGE_KEYS.STATION_ID)
  const app = getApp()
  if (app) {
    app.globalData.accessToken = null
    app.globalData.refreshToken = null
    app.globalData.userInfo = null
    app.globalData.isLogin = false
  }
  wx.redirectTo({ url: '/pages/login/index' })
}

const get = (url, query) => request({ url, method: 'GET', query })
const post = (url, data, query) => request({ url, method: 'POST', data, query })
const put = (url, data, query) => request({ url, method: 'PUT', data, query })
const del = (url, query) => request({ url, method: 'DELETE', query })

module.exports = { request, get, post, put, del }
