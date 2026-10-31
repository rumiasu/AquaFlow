const fs = require('fs'), path = require('path'), vm = require('vm')
const ROOT = path.resolve(__dirname, '../..')
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
const flush = async () => { for (let i = 0; i < 12; i++) await Promise.resolve() }
const localChannel = () => ({ method: 1, enabled: true, simulated: true, label: '模拟付款（未扣款）',
  simulationAuthorization: { version: 1, scope: 'LOCAL_TEST', customerId: 7 } })
function fixture(options = {}) {
  let version = options.version || 'develop', epoch = 1, customerId = 7, channel = options.channel || localChannel()
  let route = options.route || (version === 'trial'
    ? { mode: 'cloudContainer', env: 'synthetic-env', service: 'synthetic-service' }
    : { mode: 'https', baseUrl: 'http://127.0.0.1:8080' })
  const binding = { environmentId: 'synthetic-env', serviceId: 'synthetic-service', bindingId: 'synthetic-binding-001', apiBaseUrl: 'http://127.0.0.1:8080' }
  const posts = [], reads = [], toast = [], modal = [], cache = new Map()
  const config = { API: { PAYMENTS: '/api/payments', TICKETS: '/api/tickets', BARREL_RIGHT_PURCHASE: '/api/barrel-rights/purchase' },
    API_CONFIG: { paymentSandbox: { ...binding, ...options.binding } }, getTransportConfig: () => route,
    isDevelopEnv: () => version === 'develop', isReleaseEnv: () => version === 'release' }
  const request = {
    get(url) { reads.push(url); return options.read ? options.read(url) : Promise.resolve({ code: 0, data: { wechatPay: channel } }) },
    post(url, data) { const row = { url, data }; posts.push(row); return options.post ? options.post(row) : Promise.resolve({ code: 0, data: { id: 12, status: 2 } }) }
  }
  const token = { captureSession: () => ({ epoch, customerId }), isCurrentSession: s => s.epoch === epoch }
  function load(relative) {
    const file = path.resolve(ROOT, relative)
    if (cache.has(file)) return cache.get(file)
    const module = { exports: {} }; cache.set(file, module.exports)
    const context = { module, wx: { showToast: x => toast.push(x), showModal: x => modal.push(x) }, require(name) {
      const p = path.resolve(path.dirname(file), name + (name.endsWith('.js') ? '' : '.js')).replace(/\\/g, '/')
      if (p.endsWith('/utils/request.js')) return request
      if (p.endsWith('/config/api.js')) return config
      if (p.endsWith('/utils/token.js')) return token
      if (p.endsWith('/utils/orderIntent.js')) return {}
      return load(p)
    } }
    vm.runInNewContext(fs.readFileSync(file, 'utf8'), context, { filename: file }); cache.set(file, module.exports); return module.exports
  }
  return { posts, reads, toast, modal, config, load, nextSession: () => epoch++, customer: id => { customerId = id; epoch++ },
    version: value => { version = value }, route: value => { route = value }, channel: value => { channel = value } }
}
module.exports = { fixture, localChannel, deferred, flush }
