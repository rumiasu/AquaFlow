// 网络请求封装
const { getBaseUrl } = require('../config/api')

const request = (options) => {
  return new Promise((resolve, reject) => {
    const app = getApp()
    const baseUrl = getBaseUrl()
    const token = app.globalData.token

    const header = {
      'Content-Type': 'application/json',
      ...options.header
    }

    if (token) {
      header['Authorization'] = `Bearer ${token}`
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
      success: (res) => {
        if (res.statusCode === 200) {
          if (res.data.code === 0 || res.data.code === 200) {
            resolve(res.data)
          } else if (res.data.code === 401) {
            wx.removeStorageSync('token')
            wx.removeStorageSync('userInfo')
            wx.navigateTo({ url: '/pages/login/index' })
            reject(new Error('登录已过期'))
          } else {
            reject(new Error(res.data.message || '请求失败'))
          }
        } else if (res.statusCode === 401) {
          wx.removeStorageSync('token')
          wx.removeStorageSync('userInfo')
          wx.navigateTo({ url: '/pages/login/index' })
          reject(new Error('登录已过期'))
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

const get = (url, query) => request({ url, method: 'GET', query })
const post = (url, data, query) => request({ url, method: 'POST', data, query })
const put = (url, data, query) => request({ url, method: 'PUT', data, query })
const del = (url, query) => request({ url, method: 'DELETE', query })

module.exports = { request, get, post, put, del }
