// Read-only reachability probe uses the same trial transport as JSON business requests.
const { send } = require('./transport')
const { getTransportConfig } = require('../config/api')

const backendCheck = {
  async ping() {
    let baseUrl = ''
    try {
      const config = getTransportConfig()
      baseUrl = config.mode === 'https' ? config.baseUrl : 'cloudContainer'
      return await new Promise(resolve => {
        send({ path: '/api/stations/public', method: 'GET', timeout: 5000,
          success: res => {
            if (res && res.statusCode === 200 && res.data && (res.data.code === 0 || res.data.code === 200)) {
              resolve({ ok: true, baseUrl })
            } else resolve({ ok: false, baseUrl, error: '连接结果未核实，请重试' })
          },
          fail: error => resolve({ ok: false, baseUrl, error: error.errMsg || '网络请求失败' }) })
      })
    } catch (error) { return { ok: false, baseUrl, error: error.message } }
  }
}

module.exports = { backendCheck }
