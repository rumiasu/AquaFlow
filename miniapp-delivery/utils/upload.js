// 统一文件上传工具（与 request.js 保持一致的 Token 来源 + 基础 refresh 重试）
const { getBaseUrl, API } = require('../config/api')
const { STORAGE_KEYS } = require('../utils/storage-keys')

let isRefreshing = false
let uploadQueue = []

/**
 * 上传文件
 * @param {Object} options
 * @param {string} options.filePath - 本地临时文件路径
 * @param {string} options.name - 文件 key（默认 file）
 * @param {string} options.url - API 路径（不含 baseUrl）
 * @param {Object} [options.formData] - 额外表单字段
 * @returns {Promise<Object>} 解析后的 JSON 响应
 */
const upload = (options) => {
  return new Promise((resolve, reject) => {
    const app = getApp()
    const baseUrl = getBaseUrl()
    const accessToken = app.globalData.accessToken || wx.getStorageSync(STORAGE_KEYS.ACCESS_TOKEN)

    const header = {}
    if (accessToken) {
      header['Authorization'] = `Bearer ${accessToken}`
    }
    // 合并自定义 header
    if (options.header) {
      Object.assign(header, options.header)
    }

    wx.uploadFile({
      url: baseUrl + options.url,
      filePath: options.filePath,
      name: options.name || 'file',
      formData: options.formData || {},
      header,
      success: (res) => {
        if (res.statusCode === 200) {
          try {
            const data = JSON.parse(res.data)
            if (data.code === 0 || data.code === 200) {
              resolve(data)
            } else if (data.code === 401) {
              handleUpload401(options, resolve, reject)
            } else {
              reject(new Error(data.message || '上传失败'))
            }
          } catch {
            reject(new Error('上传失败'))
          }
        } else if (res.statusCode === 401) {
          handleUpload401(options, resolve, reject)
        } else {
          reject(new Error('上传失败 ' + res.statusCode))
        }
      },
      fail: (err) => {
        reject(err)
      }
    })
  })
}

// 401 自动续期（简化版，复用 request.js 的 refresh 逻辑）
function handleUpload401(originalOptions, resolve, reject) {
  if (isRefreshing) {
    uploadQueue.push({ resolve, reject, options: originalOptions })
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
    // 统一走 API.REFRESH，勿硬编码 '/api/auth/refresh'。
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

        // 重试原上传
        const retryHeader = { Authorization: `Bearer ${accessToken}` }
        if (originalOptions.header) Object.assign(retryHeader, originalOptions.header)
        const retryOptions = { ...originalOptions, header: retryHeader }
        upload(retryOptions).then(resolve).catch(reject)
        processQueue(null)
      } else {
        clearAndRedirect()
        reject(new Error('登录已过期'))
        processQueue(new Error('refresh failed'))
      }
    },
    fail: () => {
      clearAndRedirect()
      reject(new Error('网络错误'))
      processQueue(new Error('network error'))
    },
    complete: () => {
      isRefreshing = false
    }
  })
}

function processQueue(error) {
  uploadQueue.forEach(item => {
    if (error) {
      item.reject(error)
    } else {
      upload(item.options).then(item.resolve).catch(item.reject)
    }
  })
  uploadQueue = []
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

module.exports = { upload }
