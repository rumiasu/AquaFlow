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
          reject(new Error('网络错误 ' + res.statusCode))
        }
      },
      fail: (err) => {
        console.error('[request] wx.request 失败:', JSON.stringify(err))
        reject(err)
      }
    })
  })
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
      processQueue(err)
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
      success: (res) => {
        if (res.statusCode === 200 && (res.data.code === 0 || res.data.code === 200)) {
          resolve(res.data)
        } else {
          reject(new Error(res.data.message || '请求失败'))
        }
      },
      fail: reject
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
