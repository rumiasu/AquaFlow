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
  const previousProd = config.API_CONFIG.prod.baseUrl
  try {
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

    config.API_CONFIG.prod.baseUrl = previousProd
    global.__wxConfig = { envVersion: 'release' }
    check(!config.isDevelopEnv(), `${app}: release disables the dev-only gate`)
    rejects(() => config.getBaseUrl(), /占位|your-domain\.com/, `${app}: release placeholder fails closed`)
    global.__wxConfig = { envVersion: 'unknown' }
    rejects(() => config.getBaseUrl(), /不支持的小程序环境版本/, `${app}: unknown environment fails closed`)
  } finally {
    config.API_CONFIG.trial.baseUrl = previous
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
let paymentPosts = 0
require.cache[requestPath] = { id: requestPath, filename: requestPath, loaded: true,
  exports: { get() {}, put() {}, post() { paymentPosts += 1; return Promise.resolve({ ok: true }) } } }
delete require.cache[orderPath]
const orderApi = require(orderPath)
try {
  for (const envVersion of ['trial', 'release']) {
    global.__wxConfig = { envVersion }
    let simulated
    assert.doesNotThrow(() => { simulated = orderApi.createPayment({ orderId: 1, paymentMethod: 1 }, { simulatedWechatPay: true }) })
    await rejectsAsync(simulated, /develop/, `miniapp-user: explicit simulated order payment is rejected in ${envVersion}`)
    const postsBefore = paymentPosts
    check(paymentPosts === postsBefore, `${envVersion}: simulated payment request never reaches transport`)
    const real = await orderApi.createPayment({ orderId: 2, paymentMethod: 1 }, { simulatedWechatPay: false })
    check(real.ok === true && paymentPosts === postsBefore + 1, `${envVersion}: method 1 without mock marker remains available`)
  }
} finally {
  delete require.cache[orderPath]
  if (priorRequestModule) require.cache[requestPath] = priorRequestModule
  else delete require.cache[requestPath]
}
console.log(`Mini program environment checks passed: ${checks}`)
console.log(`AQUAFLOW_SUITE_OK ${checks}`)
})().catch(error => { console.error(error); process.exitCode = 1 })
