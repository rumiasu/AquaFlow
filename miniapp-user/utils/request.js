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
            // access_token 过期，尝试自动续期
            handle401(options, resolve, reject)
          } else {
            const msg = res.data.message || '请求失败'
            // [2026-09-14] 系统级错误（后端 code=500）用户既看不懂也做不了，
            // 主动询问是否上报给水站；业务错误（code=1）的文案本身已"能看懂、能处理"，
            // 不再弹窗打扰（见 offerErrorReport 的说明）。
            if (res.data.code === 500) offerErrorReport(msg, options)
            reject(new Error(msg))
          }
        } else if (res.statusCode === 401) {
          handle401(options, resolve, reject)
        } else {
          reject(new Error('网络错误 ' + res.statusCode))
        }
      },
      fail: (err) => {
        reject(err)
      }
    })
  })
}

/* ==================== 系统级错误「一键上报给水站」 ==================== */

// 本次启动内已询问过的错误文案：防止同一问题反复弹窗（用户误触 / 一次操作多个请求同时失败）
const offeredReports = new Set()

/**
 * 系统级错误（后端 code=500）主动询问用户是否上报给水站。
 *
 * <p><b>为什么只对 500 弹、不对业务错误弹</b>：业务错误（code=1）的文案本身就是
 * "能看懂、能处理"的（如"水票余额不足，请先购买水票后再试"），再弹一次上报纯属打扰；
 * 而 500 意味着服务端异常，用户既看不懂也做不了，唯一有价值的动作就是把它交给站长。</p>
 *
 * <p>复用既有的反馈通道：提交走 {@code POST /api/feedback}，站长在
 * {@code miniapp-delivery/pages/feedback} 里接收（{@code GET /api/feedback/customers}）。</p>
 */
function offerErrorReport(message, options) {
  if (!message || offeredReports.has(message)) return
  offeredReports.add(message)

  wx.showModal({
    title: '遇到点问题',
    content: '系统好像开小差了。要把这个问题告诉水站吗？',
    confirmText: '上报',
    cancelText: '不用了',
    success: (r) => {
      if (r.confirm) submitErrorReport(message, options)
    }
  })
}

function submitErrorReport(message, options) {
  const pages = (typeof getCurrentPages === 'function') ? getCurrentPages() : []
  const route = pages.length ? (pages[pages.length - 1].route || '未知页面') : '未知页面'
  const api = (options && options.url) ? options.url : '未知接口'

  const d = new Date()
  const p2 = (n) => (n < 10 ? '0' + n : '' + n)
  const ts = `${d.getFullYear()}-${p2(d.getMonth() + 1)}-${p2(d.getDate())} `
    + `${p2(d.getHours())}:${p2(d.getMinutes())}`

  // 后端 FeedbackCreateDTO 限制 content ≤ 1000 字，超长会被拒 → 先截断
  const content = `【自动上报·系统错误】\n错误：${message}\n页面：${route}\n接口：${api}\n时间：${ts}`
    .slice(0, 1000)

  const app = getApp()
  const token = (app && app.globalData && app.globalData.accessToken)
    || wx.getStorageSync(STORAGE_KEYS.ACCESS_TOKEN)

  // 刻意不复用本模块的 request()：上报自身若再失败，会再次触发上报询问（递归弹窗）。
  wx.request({
    url: getBaseUrl() + API.FEEDBACK,
    method: 'POST',
    header: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${token}`
    },
    data: { category: '错误报告', content },
    success: () => wx.showToast({ title: '已上报，水站会尽快处理', icon: 'none' }),
    fail: () => wx.showToast({ title: '上报失败，请稍后再试', icon: 'none' })
  })
}

// 401 自动续期处理
function handle401(originalOptions, resolve, reject) {
  // 如果是刷新或登录请求，不重试
  if (originalOptions.url.includes('/auth/refresh') ||
      originalOptions.url.includes('/auth/login') ||
      originalOptions.url.includes('/auth/wx-login') ||
      originalOptions.url.includes('/auth/dev-login')) {
    clearAndRedirect()
    reject(new Error('登录已过期'))
    return
  }

  if (isRefreshing) {
    // 正在刷新，排队等待
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

  const { getBaseUrl, API } = require('../config/api')
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

        // 重试原始请求
        retryRequest(originalOptions, accessToken).then(resolve).catch(reject)
        // 处理排队中的请求
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

// 重试请求（用新 token）
function retryRequest(options, newToken) {
  return new Promise((resolve, reject) => {
    const app = getApp()
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

// 处理排队中的请求
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
