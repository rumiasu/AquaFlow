// 后端连接检测工具
const { getBaseUrl } = require('../config/api')

const backendCheck = {
  // 检测后端是否可达
  async ping() {
    const baseUrl = getBaseUrl()
    console.log('[BackendCheck] 检测后端:', baseUrl)
    try {
      const res = await new Promise((resolve, reject) => {
        wx.request({
          url: baseUrl + '/api/stations/public',
          method: 'GET',
          timeout: 5000,
          success: (r) => {
            if (r.statusCode === 200 && r.data && (r.data.code === 0 || r.data.code === 200)) {
              resolve({ ok: true, baseUrl })
            } else {
              resolve({ ok: false, baseUrl, error: `状态码${r.statusCode}, code=${r.data ? r.data.code : 'null'}` })
            }
          },
          fail: (err) => {
            resolve({ ok: false, baseUrl, error: err.errMsg || '网络请求失败' })
          }
        })
      })
      console.log('[BackendCheck] 结果:', res)
      return res
    } catch (e) {
      console.error('[BackendCheck] 异常:', e)
      return { ok: false, baseUrl, error: e.message }
    }
  }
}

module.exports = { backendCheck }
