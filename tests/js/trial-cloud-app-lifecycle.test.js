'use strict'
// Executes actual App registration/startup/logout; only native transport and time are synthetic.
const assert = require('assert'), fs = require('fs'), path = require('path'), Module = require('module')
const { createWx, armWatchdog } = require('./harness')
const ROOT = path.resolve(__dirname, '../..')
const SOURCE = process.env.AQUAFLOW_APP_SOURCE_ROOT ? path.resolve(process.env.AQUAFLOW_APP_SOURCE_ROOT) : ROOT
const tests = [], test = (name, run) => tests.push({ name, run })
const flush = async () => { await new Promise(resolve => setImmediate(resolve)); for (let i = 0; i < 12; i++) await Promise.resolve() }
const ok = (data = {}) => ({ statusCode: 200, data: { code: 0, data }, header: {} })
function boot(side, mode, cached = true, options = {}) {
  for (const key of Object.keys(require.cache)) if (key.startsWith(path.join(ROOT, side) + path.sep)) delete require.cache[key]
  const wx = createWx(), calls = [], http = [], resources = []
  wx.__modalAutoConfirm = false
  wx.request = request => {
    if (options.nativeThrow) throw Error('synthetic native request failure')
    http.push(request); const call = { options: request,
      success: value => { request.success?.(value); request.complete?.(value) },
      fail: () => { request.fail?.({ errMsg: 'request:fail synthetic offline' }); request.complete?.() } }
    calls.push(call)
  }
  wx.cloud = { Cloud: class {
    constructor(resource) { resources.push(resource) }
    init() { return options.init ? options.init() : Promise.resolve() }
    callContainer(request) {
      let success, fail
      const promise = new Promise((yes, no) => { success = yes; fail = () => no(Error('synthetic cloud failure')) })
      calls.push({ options: request, success, fail }); return promise
    }
  } }
  global.wx = wx; global.__wxConfig = { envVersion: 'trial' }
  global.getCurrentPages = () => [{ route: 'pages/login/index' }]
  const config = require(path.join(ROOT, side, 'config/api'))
  config.API_CONFIG.trial.transport = mode
  config.API_CONFIG.trial.baseUrl = options.missing ? '' : 'https://synthetic-trial.invalid'
  config.API_CONFIG.trial.cloudContainer = { env: options.missing ? '' : 'synthetic-env', service: 'synthetic-service', resourceAppid: 'wx0000000000000001' }
  if (options.missingSDK) delete wx.cloud
  const { STORAGE_KEYS: K } = require(path.join(ROOT, side, 'utils/storage-keys'))
  if (cached) {
    wx.setStorageSync(K.ACCESS_TOKEN, 'synthetic-cached-access'); wx.setStorageSync(K.REFRESH_TOKEN, 'synthetic-cached-refresh')
    wx.setStorageSync(K.USER_INFO, side === 'miniapp-user' ? { customerId: 7, nickName: 'synthetic cached' }
      : { staffId: 7, role: 'DELIVERY', stationId: 1, bindStatus: 'BOUND' })
    if (side === 'miniapp-user') wx.setStorageSync(K.CUSTOMER_ID, 7)
  }
  let app, registered, earlyReads = 0
  global.getApp = () => { if (!registered) earlyReads++; return registered }
  global.App = definition => { app = definition; app.onLaunch(); registered = app }
  const filename = path.join(ROOT, side, 'app.js'), wrapper = new Module(filename, module)
  wrapper.filename = filename; wrapper.paths = Module._nodeModulePaths(path.dirname(filename)); require.cache[filename] = wrapper
  wrapper._compile(fs.readFileSync(path.join(SOURCE, side, 'app.js'), 'utf8'), filename); wrapper.loaded = true
  return { side, mode, wx, app, config, K, calls, http, resources, earlyReads: () => earlyReads }
}
async function clock(run) {
  const nativeSet = global.setTimeout, nativeClear = global.clearTimeout, timers = new Map(); let id = 100000
  global.setTimeout = (fn, delay, ...args) => {
    if (delay !== 15000) return nativeSet(fn, delay, ...args)
    const token = ++id; timers.set(token, () => fn(...args)); return token
  }
  global.clearTimeout = token => timers.has(token) ? timers.delete(token) : nativeClear(token)
  const expire = () => { assert(timers.size); for (const [token, fn] of [...timers]) { timers.delete(token); fn() } }
  try { await run(expire) } finally { global.setTimeout = nativeSet; global.clearTimeout = nativeClear }
}
function assertRoute(t, call, endpoint, method) {
  assert.strictEqual(call.options.method, method)
  assert.strictEqual(t.mode === 'cloudContainer' ? call.options.path : call.options.url, t.mode === 'cloudContainer' ? endpoint : 'https://synthetic-trial.invalid' + endpoint)
  assert.strictEqual(call.options.timeout, 15000)
  assert.strictEqual(call.options.header.Authorization, 'Bearer synthetic-cached-access')
  if (t.mode === 'cloudContainer') { assert.strictEqual(t.http.length, 0); assert.strictEqual(call.options.header['X-WX-SERVICE'], 'synthetic-service') }
}
for (const mode of ['https', 'cloudContainer']) {
  test(mode + ': customer cached startup sends /me and accepts verified identity', async () => {
    const t = boot('miniapp-user', mode); await flush(); assert.strictEqual(t.calls.length, 1)
    assertRoute(t, t.calls[0], '/api/auth/me', 'GET'); assert.strictEqual(t.earlyReads(), 0); assert.strictEqual(t.wx.__calls.modal.length, 0)
    t.calls[0].success(ok({ customerId: 7, nickName: 'synthetic verified' })); await flush()
    assert.strictEqual(t.app.globalData.userInfo.nickName, 'synthetic verified'); assert.strictEqual(t.app.globalData.isLogin, true)
  })
  test(mode + ': customer guest startup sends no authentication or SDK initialization', async () => {
    const t = boot('miniapp-user', mode, false); await flush()
    assert.strictEqual(t.calls.length, 0); assert.strictEqual(t.resources.length, 0); assert.strictEqual(t.earlyReads(), 0)
    assert.strictEqual(t.app.globalData.isLogin, false)
  })
  test(mode + ': customer startup network failure preserves cached credentials', async () => {
    const t = boot('miniapp-user', mode); await flush(); t.calls[0].fail(); await flush()
    assert.strictEqual(t.app.globalData.isLogin, true); assert.strictEqual(t.app.globalData.accessToken, 'synthetic-cached-access')
    assert.strictEqual(t.wx.getStorageSync(t.K.ACCESS_TOKEN), 'synthetic-cached-access'); assert.strictEqual(t.wx.__calls.nav.length, 0)
  })
  test(mode + ': missing startup settings explain setup without early getApp reads or clearing cache', async () => {
    const t = boot('miniapp-user', mode, true, { missing: true }); await flush()
    assert.strictEqual(t.calls.length, 0); assert.strictEqual(t.http.length, 0); assert.strictEqual(t.earlyReads(), 0)
    assert.strictEqual(t.wx.__calls.modal.length, 1); assert.strictEqual(t.app.globalData.isLogin, true)
    assert.strictEqual(t.app.globalData.accessToken, 'synthetic-cached-access')
  })
  for (const kind of ['HTTP', 'envelope']) test(mode + ': cached customer ' + kind + ' 401 uses existing refresh lifecycle once', async () => {
    const t = boot('miniapp-user', mode); await flush()
    t.calls[0].success(kind === 'HTTP' ? { statusCode: 401 } : { statusCode: 200, data: { code: 401 } }); await flush()
    assert.strictEqual(t.calls.length, 2)
    assert.strictEqual(mode === 'cloudContainer' ? t.calls[1].options.path : t.calls[1].options.url, mode === 'cloudContainer' ? '/api/auth/refresh' : 'https://synthetic-trial.invalid/api/auth/refresh')
    assert.deepStrictEqual(t.calls[1].options.data, { refreshToken: 'synthetic-cached-refresh' })
    t.calls[1].success(ok({ accessToken: 'synthetic-renewed', refreshToken: 'synthetic-renewed-refresh' })); await flush()
    assert.strictEqual(t.app.globalData.accessToken, 'synthetic-renewed'); assert.strictEqual(t.app.globalData.isLogin, true)
    assert.strictEqual(t.calls.length, 2)
  })
  for (const outcome of ['success', '401', 'failure']) test(mode + ': late startup ' + outcome + ' cannot change a newer login', async () => {
    const t = boot('miniapp-user', mode); await flush()
    t.app.setLoginInfo('synthetic-new-login', 'synthetic-new-login-refresh', { customerId: 8, nickName: 'synthetic B' })
    t.app.globalData.customerId = 8; t.wx.setStorageSync(t.K.CUSTOMER_ID, 8)
    if (outcome === 'failure') t.calls[0].fail()
    else t.calls[0].success(outcome === '401' ? { statusCode: 401 } : ok({ customerId: 7, nickName: 'synthetic late A' }))
    await flush(); assert.strictEqual(t.app.globalData.accessToken, 'synthetic-new-login')
    assert.strictEqual(t.app.globalData.userInfo.nickName, 'synthetic B'); assert.strictEqual(t.calls.length, 1)
  })
  for (const outcome of ['success', 'failure']) test(mode + ': employee logout ' + outcome + ' clears locally and duplicate exits send once', async () => {
    const t = boot('miniapp-delivery', mode); await flush(); assert.strictEqual(t.calls.length, 0)
    assert.doesNotThrow(() => { t.app.logout(); t.app.logout() }); await flush(); assert.strictEqual(t.calls.length, 1)
    assertRoute(t, t.calls[0], '/api/auth/logout', 'POST')
    if (outcome === 'success') t.calls[0].success(ok()); else t.calls[0].fail()
    await flush(); assert.strictEqual(t.app.globalData.isLogin, false); assert.strictEqual(t.app._logoutAttempt, null)
    assert(!t.wx.getStorageSync(t.K.ACCESS_TOKEN)); assert.strictEqual(t.wx.__calls.nav.length, 1)
    t.app.logout(); await flush(); assert.strictEqual(t.calls.length, 1); assert.strictEqual(t.wx.__calls.nav.length, 1)
  })
  test(mode + ': employee logout missing configuration clears locally and never strands its lock', async () => {
    const t = boot('miniapp-delivery', mode, true, { missing: true })
    assert.doesNotThrow(() => t.app.logout()); await flush()
    assert.strictEqual(t.app.globalData.isLogin, false); assert.strictEqual(t.app._logoutAttempt, null)
    assert.strictEqual(t.calls.length, 0); assert.strictEqual(t.http.length, 0); t.app.logout(); assert.strictEqual(t.calls.length, 0)
  })
  for (const outcome of ['success', 'failure']) test(mode + ': old logout ' + outcome + ' cannot clear a newer employee login', async () => {
    const t = boot('miniapp-delivery', mode); t.app.logout(); await flush()
    t.app.setLoginState({ accessToken: 'synthetic-B', refreshToken: 'synthetic-B-refresh', staffId: 8, role: 'DELIVERY', stationId: 2, bindStatus: 'BOUND' })
    if (outcome === 'success') t.calls[0].success(ok()); else t.calls[0].fail()
    await flush(); assert.strictEqual(t.app.globalData.isLogin, true); assert.strictEqual(t.app.globalData.accessToken, 'synthetic-B')
    assert.strictEqual(t.wx.__calls.nav.length, 0)
    t.app.logout(); await flush(); assert.strictEqual(t.calls.length, 2); t.calls[1].success(ok()); await flush()
    assert.strictEqual(t.app.globalData.isLogin, false)
  })
}
test('cloud cached startup missing SDK is explicit, keeps cache, and makes no early global App read', async () => {
  const t = boot('miniapp-user', 'cloudContainer', true, { missingSDK: true }); await flush()
  assert.strictEqual(t.calls.length, 0); assert.strictEqual(t.earlyReads(), 0); assert.strictEqual(t.app.globalData.isLogin, true)
  assert.match(t.wx.__calls.modal[0].content, /缺少云连接能力/)
})
test('cloud cached startup initialization rejection keeps cache and explains setup', async () => {
  const t = boot('miniapp-user', 'cloudContainer', true, { init: () => Promise.reject(Error('synthetic init failure')) }); await flush()
  assert.strictEqual(t.calls.length, 0); assert.strictEqual(t.app.globalData.isLogin, true); assert.strictEqual(t.wx.__calls.modal.length, 1)
})
test('cloud cached startup timeout ignores late /me success and keeps cached identity', () => clock(async expire => {
  const t = boot('miniapp-user', 'cloudContainer'); await flush(); expire(); await flush()
  assert.strictEqual(t.app.globalData.isLogin, true)
  t.calls[0].success(ok({ customerId: 7, nickName: 'synthetic late' })); await flush()
  assert.strictEqual(t.app.globalData.userInfo.nickName, 'synthetic cached'); assert.strictEqual(t.calls.length, 1)
}))
test('cloud employee logout timeout clears once and ignores late completion', () => clock(async expire => {
  const t = boot('miniapp-delivery', 'cloudContainer'); t.app.logout(); t.app.logout(); await flush()
  assert.strictEqual(t.calls.length, 1); expire(); await flush(); assert.strictEqual(t.app.globalData.isLogin, false)
  const generation = t.app._loginGeneration; t.calls[0].success(ok()); await flush()
  assert.strictEqual(t.app._loginGeneration, generation); assert.strictEqual(t.wx.__calls.nav.length, 1)
  t.app.logout(); assert.strictEqual(t.calls.length, 1)
}))
test('HTTPS employee synchronous native failure still completes local logout', async () => {
  const t = boot('miniapp-delivery', 'https', true, { nativeThrow: true })
  assert.doesNotThrow(() => t.app.logout()); await flush()
  assert.strictEqual(t.app.globalData.isLogin, false); assert.strictEqual(t.app._logoutAttempt, null)
})
test('cloud employee missing SDK still completes local logout with no fallback', async () => {
  const t = boot('miniapp-delivery', 'cloudContainer', true, { missingSDK: true })
  t.app.logout(); await flush(); assert.strictEqual(t.app.globalData.isLogin, false)
  assert.strictEqual(t.calls.length, 0); assert.strictEqual(t.http.length, 0)
})
test('excluded upload rejects cloud mode before upload or its HTTP-only refresh', async () => {
  const t = boot('miniapp-delivery', 'cloudContainer'); let uploads = 0; t.wx.uploadFile = () => uploads++
  await assert.rejects(require(path.join(ROOT, 'miniapp-delivery/utils/upload')).upload({ url: '/api/files', filePath: 'synthetic', name: 'file' }), /不支持此操作/)
  assert.strictEqual(uploads, 0); assert.strictEqual(t.calls.length, 0); assert.strictEqual(t.http.length, 0)
})
;(async () => {
  const done = armWatchdog(30000); let failed = 0
  for (const t of tests) { try { await t.run(); console.log('PASS ' + t.name) } catch (error) { failed++; console.error('FAIL ' + t.name + ': ' + error.stack) } }
  console.log('APP_LIFECYCLE_RESULT ' + JSON.stringify({ passed: tests.length - failed, failed, total: tests.length }))
  if (failed) process.exitCode = 1; else console.log('AQUAFLOW_SUITE_OK ' + tests.length)
  done()
})().catch(error => { console.error(error.stack); process.exitCode = 1 })
