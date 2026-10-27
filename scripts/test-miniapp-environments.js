(async () => {
const assert = require('node:assert/strict')
const path = require('node:path')

const root = path.resolve(__dirname, '..')
const apps = ['miniapp-user', 'miniapp-delivery']
let checks = 0
const check = (condition, message) => { assert.ok(condition, message); checks += 1 }
const rejects = (fn, pattern, message) => { assert.throws(fn, pattern, message); checks += 1 }
const rejectsAsync = async (promise, pattern, message) => { await assert.rejects(promise, pattern, message); checks += 1 }

global.wx = { getDeviceInfo: () => ({ platform: 'ios' }) }

for (const app of apps) {
  const config = require(path.join(root, app, 'config/api.js'))
  const previous = config.API_CONFIG.trial.baseUrl
  const previousTransport = config.API_CONFIG.trial.transport
  const previousProd = config.API_CONFIG.prod.baseUrl
  try {
    // Make fail-closed cases independent of the real acceptance configuration.
    config.API_CONFIG.trial.transport = 'https'
    config.API_CONFIG.trial.baseUrl = ''
    global.__wxConfig = { envVersion: 'develop' }
    check(config.getBaseUrl() === config.API_CONFIG.dev.baseUrl, `${app}: develop uses configured LAN API`)
    check(config.isDevelopEnv(), `${app}: develop enables the dev-only gate`)

    global.__wxConfig = { envVersion: 'trial' }
    check(!config.isDevelopEnv(), `${app}: trial disables the dev-only gate`)
    rejects(() => config.getBaseUrl(), /体验版 API 未配置/, `${app}: missing trial URL fails closed`)
    config.API_CONFIG.trial.baseUrl = 'http://test.example.invalid'
    rejects(() => config.getBaseUrl(), /必须使用独立的 HTTPS/, `${app}: non-HTTPS trial URL is rejected`)
    config.API_CONFIG.trial.baseUrl = 'https://acceptance.example.invalid'
    check(config.getBaseUrl() === config.API_CONFIG.trial.baseUrl, `${app}: configured HTTPS trial URL is used`)
    config.API_CONFIG.prod.baseUrl = 'https://prod.example.invalid'
    config.API_CONFIG.trial.baseUrl = 'https://prod.example.invalid/'
    rejects(() => config.getBaseUrl(), /independent|HTTPS/i, `${app}: trailing-slash production origin is rejected`)
    config.API_CONFIG.trial.baseUrl = 'https://PROD.EXAMPLE.INVALID'
    rejects(() => config.getBaseUrl(), /independent|HTTPS/i, `${app}: case-normalized production origin is rejected`)

    config.API_CONFIG.prod.baseUrl = 'https://your-domain.com'
    global.__wxConfig = { envVersion: 'release' }
    check(!config.isDevelopEnv(), `${app}: release disables the dev-only gate`)
    rejects(() => config.getBaseUrl(), /占位|your-domain\.com/, `${app}: release placeholder fails closed`)
    global.__wxConfig = { envVersion: 'unknown' }
    rejects(() => config.getBaseUrl(), /不支持的小程序环境版本/, `${app}: unknown environment fails closed`)
  } finally {
    config.API_CONFIG.trial.baseUrl = previous
    config.API_CONFIG.trial.transport = previousTransport
    config.API_CONFIG.prod.baseUrl = previousProd
  }
}

for (const app of apps) {
  const auth = require(path.join(root, app, 'api/auth.js'))
  for (const envVersion of ['trial', 'release']) {
    global.__wxConfig = { envVersion }
    rejects(() => auth.devLogin(), /仅在 develop 环境可用/, `${app}: dev login cannot be called in ${envVersion}`)
  }
}

const requestPath = require.resolve(path.join(root, 'miniapp-user/utils/request.js'))
const orderPath = require.resolve(path.join(root, 'miniapp-user/api/order.js'))
const priorRequestModule = require.cache[requestPath]
const priorGetApp = global.getApp
// 2026-10-27：支付 API 已按登录周期单飞，环境门禁夹具也须提供稳定的真实会话入口。
const paymentApp = { globalData: { isLogin: true, customerId: 101,
  accessToken: 'synthetic-environment-access', refreshToken: 'synthetic-environment-refresh' } }
global.getApp = () => paymentApp
let paymentPosts = 0
let simulatedCapability = true
const paymentConfig = require(path.join(root, 'miniapp-user/config/api'))
const priorProdUrl = paymentConfig.API_CONFIG.prod.baseUrl
paymentConfig.API_CONFIG.prod.baseUrl = 'https://production.synthetic.test'
require.cache[requestPath] = { id: requestPath, filename: requestPath, loaded: true,
  exports: { get() { return Promise.resolve({ code: 0, data: { wechatPay: { method: 1, enabled: true, simulated: simulatedCapability, label: '渠道夹具' } } }) }, put() {}, post() { paymentPosts += 1; return Promise.resolve({ ok: true }) } } }
delete require.cache[orderPath]
const orderApi = require(orderPath)
try {
  for (const envVersion of ['trial', 'release']) {
    global.__wxConfig = { envVersion }
    const postsBefore = paymentPosts
    let simulated
    assert.doesNotThrow(() => { simulated = orderApi.createPayment({ orderId: 1, paymentMethod: 1 }, { simulatedWechatPay: true }) })
    await rejectsAsync(simulated, /授权|模拟/, `miniapp-user: unauthorized simulated order payment is rejected in ${envVersion}`)
    check(paymentPosts === postsBefore, `${envVersion}: simulated payment request never reaches transport`)
    simulatedCapability = false
    const real = await orderApi.createPayment({ orderId: 2, paymentMethod: 1 }, { simulatedWechatPay: false })
    simulatedCapability = true
    check(real.ok === true && paymentPosts === postsBefore + 1, `${envVersion}: method 1 without mock marker remains available`)
  }
} finally {
  paymentConfig.API_CONFIG.prod.baseUrl = priorProdUrl
  delete require.cache[orderPath]
  if (priorRequestModule) require.cache[requestPath] = priorRequestModule
  else delete require.cache[requestPath]
  if (priorGetApp === undefined) delete global.getApp
  else global.getApp = priorGetApp
}

for (const app of apps) {
  const c = require(path.join(root, app, 'config/api.js'))
  const original = JSON.parse(JSON.stringify(c.API_CONFIG))
  try {
    global.__wxConfig = { envVersion: 'release' }
    for (const url of ['', null, 'http://127.0.0.1:8088', 'http://192.168.0.243:8080',
      'https://127.0.0.1:8088', 'https://192.168.1.2', 'https://10.1.2.3', 'https://172.16.1.2',
      'https://169.254.1.2', 'https://0.0.0.0', 'https://[::1]', 'https://localhost',
      'https://station.local', 'https://station.lan', 'https://station.home.arpa',
      'https://your-domain.com', 'https://YOUR-DOMAIN.COM/', 'https://api.your-domain.com',
      'https://-bad.example.com', 'https://api.example.com:65536', ' https://api.example.com',
      'https://127.0.0.0x1', 'https://192.168.1.0x1', 'https://127.0.0.0X1',
      'https://0x7f.0.0.1', 'https://0177.0.0.01', 'https://2130706433', 'https://0x7f000001',
      'https://api.example.com/api#prod', 'https://api.example.com/api?version=1',
      'https://api.example.com/api\u0000', 'https://api.example.com/api\u001f',
      'https://api.example.com/api\u007f', 'https://api.example.com/api\u0085']) {
      c.API_CONFIG.prod.baseUrl = url
      rejects(() => c.getBaseUrl(), /HTTPS/, app + ': release rejects invalid/local URL')
    }
    for (const url of ['https://api.example.com', 'HTTPS://API.EXAMPLE.COM:443/v1',
      'https://api.example.com./v1', 'https://service.ap-shanghai.app.tcloudbase.com',
      'https://api.example.com:8443/v1', 'https://api.example.com/api',
      'https://api.example.com:443/api/v1', 'https://api.example.com/api%23prod']) {
      c.API_CONFIG.prod.baseUrl = url
      check(c.getBaseUrl() === url, app + ': valid HTTPS domain preserved')
    }
    c.API_CONFIG.dev.baseUrl = 'http://DEV.EXAMPLE.COM:8088'
    c.API_CONFIG.dev.devtoolsBaseUrl = 'http://TOOLS.EXAMPLE.COM:8088'
    for (const url of ['https://dev.example.com:443/v1', 'https://DEV.EXAMPLE.COM./', 'https://tools.example.com']) {
      c.API_CONFIG.prod.baseUrl = url
      rejects(() => c.getBaseUrl(), /开发地址/, app + ': normalized develop hostname rejected')
    }
    global.wx = { getDeviceInfo: () => ({ platform: 'devtools' }) }
    c.API_CONFIG.prod.baseUrl = ''
    rejects(() => c.getBaseUrl(), /HTTPS/, app + ': release never falls back to devtools')
    c.API_CONFIG.prod.baseUrl = 'https://api.example.com'
    check(c.getBaseUrl() === c.API_CONFIG.prod.baseUrl, app + ': release on devtools uses prod')
    global.__wxConfig = { envVersion: 'develop' }
    check(c.getBaseUrl() === c.API_CONFIG.dev.devtoolsBaseUrl, app + ': develop devtools unchanged')
    global.wx = { getDeviceInfo: () => ({ platform: 'ios' }) }
    check(c.getBaseUrl() === c.API_CONFIG.dev.baseUrl, app + ': develop device unchanged')
    global.__wxConfig = { envVersion: 'trial' }
    c.API_CONFIG.trial.transport = 'https'
    c.API_CONFIG.trial.baseUrl = 'https://acceptance.example.invalid'
    check(c.getBaseUrl() === c.API_CONFIG.trial.baseUrl, app + ': trial unchanged')
  } finally {
    Object.assign(c.API_CONFIG, original)
    global.wx = { getDeviceInfo: () => ({ platform: 'ios' }) }
  }
}

console.log(`Mini program environment checks passed: ${checks}`)
console.log(`AQUAFLOW_SUITE_OK ${checks}`)
})().catch(error => { console.error(error); process.exitCode = 1 })
