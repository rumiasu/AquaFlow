'use strict'
// Real config/transport/request/App/Page code; SDK Promises and clocks only are synthetic.
const assert = require('assert'), path = require('path'), fs = require('fs')
const { createWx, loadPage, armWatchdog } = require('./harness')
const ROOT = path.resolve(__dirname, '../..'), tests = []
const test = (name, run) => tests.push({ name, run })
const flush = async () => { await new Promise(resolve => setImmediate(resolve)); for (let i = 0; i < 10; i++) await Promise.resolve() }
function deferred() { let resolve, reject; const promise = new Promise((y, n) => { resolve = y; reject = n }); return { promise, resolve, reject } }
const ok = (data = {}) => ({ statusCode: 200, data: { code: 0, data }, header: { synthetic: 'only' }, callID: 'synthetic' })
function observed(promise) {
  const state = { settled: false }
  promise.then(value => Object.assign(state, { settled: true, value }), error => Object.assign(state, { settled: true, error }))
  return state
}
function setup(side, options = {}) {
  for (const key of Object.keys(require.cache)) if (key.startsWith(path.join(ROOT, side) + path.sep)) delete require.cache[key]
  const wx = createWx(), http = [], calls = [], resources = [], inits = []
  wx.request = o => { http.push(o); return { abort() {} } }
  wx.__modalAutoConfirm = false
  wx.cloud = { Cloud: class {
    constructor(resource) { resources.push(resource) }
    init() { inits.push(true); return options.init ? options.init() : Promise.resolve() }
    callContainer(o) { const gate = deferred(); calls.push({ options: o, ...gate }); return gate.promise }
  } }
  global.wx = wx; global.__wxConfig = { envVersion: options.version || 'trial' }
  global.getCurrentPages = () => [{ route: 'pages/order/create' }]
  let app
  global.App = value => { app = value }; global.getApp = () => app
  const config = require(path.join(ROOT, side, 'config/api'))
  config.API_CONFIG.trial.transport = 'cloudContainer'
  config.API_CONFIG.trial.cloudContainer = { env: 'synthetic-env', service: 'synthetic-service', resourceAppid: 'wx0000000000000001' }
  require(path.join(ROOT, side, 'app.js'))
  if (side === 'miniapp-user') {
    app.setLoginInfo('synthetic-access', 'synthetic-refresh', { customerId: 7 })
    app.globalData.customerId = 7
    wx.setStorageSync(require(path.join(ROOT, side, 'utils/storage-keys')).STORAGE_KEYS.CUSTOMER_ID, 7)
  }
  else app.setLoginState({ accessToken: 'synthetic-access', refreshToken: 'synthetic-refresh', staffId: 7, role: 'DELIVERY', stationId: 1, bindStatus: 'BOUND' })
  const transport = require(path.join(ROOT, side, 'utils/transport'))
  const request = require(path.join(ROOT, side, 'utils/request'))
  return { side, wx, app, config, transport, request, http, calls, resources, inits }
}
async function clock(run) {
  const previousSet = global.setTimeout, previousClear = global.clearTimeout, timers = new Map()
  let next = 1
  global.setTimeout = (fn, delay, ...args) => {
    if (delay !== 15000 && delay !== 5000) return previousSet(fn, delay, ...args)
    const id = next++; timers.set(id, { fn: () => fn(...args), delay }); return id
  }
  global.clearTimeout = id => timers.has(id) ? timers.delete(id) : previousClear(id)
  const expire = delay => { const matches = [...timers].filter(([, timer]) => timer.delay === delay); assert(matches.length); for (const [id, timer] of matches) { timers.delete(id); timer.fn() } }
  try { await run({ timers, expire }) } finally { global.setTimeout = previousSet; global.clearTimeout = previousClear }
}
for (const side of ['miniapp-user', 'miniapp-delivery']) {
  test(side + ': JSON success, JWT/service routing and GET query survive cloud Promise adaptation', async () => {
    const t = setup(side), body = { idempotencyKey: 'synthetic-key', nested: { quantity: 2 } }
    const post = observed(t.request.post('/api/orders/create', body)); await flush()
    assert.strictEqual(t.calls.length, 1); const first = t.calls[0]
    assert.deepStrictEqual(t.resources, [{ resourceEnv: 'synthetic-env', resourceAppid: 'wx0000000000000001' }])
    assert.strictEqual(first.options.path, '/api/orders/create'); assert.strictEqual(first.options.timeout, 15000)
    assert.strictEqual(first.options.header.Authorization, 'Bearer synthetic-access')
    assert.strictEqual(first.options.header['X-WX-SERVICE'], 'synthetic-service'); assert.deepStrictEqual(first.options.data, body)
    first.resolve(ok({ orderId: 42 })); await flush(); assert.strictEqual(post.value.data.orderId, 42)
    const get = observed(t.request.get('/api/stations/public?fixed=1', { q: 'a b&c', empty: null, zero: 0 })); await flush()
    assert.strictEqual(t.calls[1].options.path, '/api/stations/public?fixed=1&q=a%20b%26c&zero=0')
    t.calls[1].resolve(ok([])); await flush(); assert(get.settled && !get.error)
    assert.strictEqual(t.inits.length, 1); assert.strictEqual(t.http.length, 0)
  })
  test(side + ': current develop route uses cloud SDK without HTTPS fallback', async () => {
    const t = setup(side, { version: 'develop' }), state = observed(t.request.get('/api/test')); await flush()
    assert.strictEqual(t.calls.length, 1); assert.strictEqual(t.http.length, 0)
    t.calls[0].resolve(ok({ verified: true })); await flush(); assert.strictEqual(state.value.data.verified, true)
  })
  for (const kind of ['business', 'HTTP', 'malformed']) test(side + ': ' + kind + ' failure is not a cloud success', async () => {
    const t = setup(side), state = observed(t.request.get('/api/test')); await flush()
    t.calls[0].resolve(kind === 'business' ? { statusCode: 200, data: { code: 1, message: 'synthetic rejection' } }
      : kind === 'HTTP' ? { statusCode: 503, data: {} } : { statusCode: 200, data: { code: '0' } })
    await flush(); assert(state.error); assert.strictEqual(state.error.businessRejected === true, kind === 'business')
    assert.strictEqual(t.calls.length, 1); assert.strictEqual(t.http.length, 0)
  })
  for (const kind of ['HTTP', 'envelope']) test(side + ': concurrent ' + kind + ' 401 refreshes once and replays original body/key', async () => {
    const t = setup(side), body = { idempotencyKey: 'synthetic-original', nested: { quantity: 2 } }
    const a = observed(t.request.post('/api/orders/create', body)), b = observed(t.request.post('/api/orders/create', body))
    body.nested.quantity = 99; await flush()
    const expired = kind === 'HTTP' ? { statusCode: 401 } : { statusCode: 200, data: { code: 401 } }
    t.calls[0].resolve(expired); t.calls[1].resolve(expired); await flush()
    assert.strictEqual(t.calls.length, 3); assert.strictEqual(t.calls[2].options.path, '/api/auth/refresh')
    assert.deepStrictEqual(t.calls[2].options.data, { refreshToken: 'synthetic-refresh' })
    t.calls[2].resolve(ok({ accessToken: 'synthetic-next', refreshToken: 'synthetic-next-refresh' })); await flush()
    assert.strictEqual(t.calls.length, 5)
    for (const call of t.calls.slice(3)) {
      assert.strictEqual(call.options.header.Authorization, 'Bearer synthetic-next')
      assert.deepStrictEqual(call.options.data, { idempotencyKey: 'synthetic-original', nested: { quantity: 2 } })
      call.resolve(ok({ orderId: 42 }))
    }
    await flush(); assert(a.value && b.value); assert.strictEqual(t.http.length, 0)
  })
  test(side + ': replay 401 stops after one refresh', async () => {
    const t = setup(side), state = observed(t.request.get('/api/test')); await flush()
    t.calls[0].resolve({ statusCode: 401 }); await flush()
    t.calls[1].resolve(ok({ accessToken: 'synthetic-next', refreshToken: 'synthetic-next-refresh' })); await flush()
    t.calls[2].resolve({ statusCode: 401 }); await flush()
    assert(state.error); assert.strictEqual(t.calls.length, 3); assert.strictEqual(t.http.length, 0)
  })
  test(side + ': timed-out refresh cannot accept late tokens or replay the pending write', () => clock(async ({ expire }) => {
    const t = setup(side), state = observed(t.request.post('/api/orders/create', { idempotencyKey: 'synthetic' })); await flush()
    t.calls[0].resolve({ statusCode: 401 }); await flush(); assert.strictEqual(t.calls.length, 2)
    expire(15000); await flush(); assert(state.error)
    const afterTimeout = JSON.stringify(t.app.globalData)
    t.calls[1].resolve(ok({ accessToken: 'synthetic-late', refreshToken: 'synthetic-late-refresh' })); await flush()
    assert.strictEqual(JSON.stringify(t.app.globalData), afterTimeout); assert.strictEqual(t.calls.length, 2)
  }))
  for (const kind of ['env', 'service', 'mode', 'SDK', 'init-method', 'call-method']) test(side + ': missing ' + kind + ' fails clearly without dispatch or fallback', async () => {
    const t = setup(side)
    if (kind === 'env' || kind === 'service') t.config.API_CONFIG.trial.cloudContainer[kind] = ''
    if (kind === 'mode') t.config.API_CONFIG.trial.transport = 'unknown'
    if (kind === 'SDK') delete t.wx.cloud
    if (kind === 'init-method' || kind === 'call-method') t.wx.cloud.Cloud = class { constructor() { this.init = () => Promise.resolve(); this.callContainer = () => Promise.resolve(ok()); delete this[kind === 'init-method' ? 'init' : 'callContainer'] } }
    const state = observed(t.request.get('/api/test')); await flush()
    assert(state.error); assert.match(state.error.message, /未配置|缺少|不支持|服务暂不可用/)
    assert.strictEqual(state.error.transportConfiguration, true)
    assert.notStrictEqual(state.error.requestMayHaveOccurred, true)
    assert.strictEqual(t.calls.length, 0); assert.strictEqual(t.http.length, 0)
  })
  test(side + ': failed initialization is retryable only on an explicit later request', async () => {
    let attempts = 0
    const t = setup(side, { init: () => ++attempts === 1 ? Promise.reject(Error('synthetic init')) : Promise.resolve() })
    const first = observed(t.request.get('/api/test')); await flush(); assert(first.error)
    assert.strictEqual(t.calls.length, 0); assert.strictEqual(t.http.length, 0)
    const retry = observed(t.request.get('/api/test')); await flush(); assert.strictEqual(t.calls.length, 1)
    t.calls[0].resolve(ok()); await flush(); assert(retry.value); assert.strictEqual(attempts, 2)
  })
  test(side + ': cloud rejection never retries through HTTPS', async () => {
    const t = setup(side), state = observed(t.request.post('/api/orders/create', { idempotencyKey: 'synthetic' })); await flush()
    t.calls[0].reject(Error('synthetic cloud failure')); await flush()
    assert(state.error); assert.strictEqual(t.calls.length, 1); assert.strictEqual(t.http.length, 0)
  })
  test(side + ': local 15-second timeout settles once and ignores a late 401', () => clock(async ({ expire, timers }) => {
    const t = setup(side), state = observed(t.request.post('/api/orders/create', { idempotencyKey: 'synthetic' })); await flush()
    assert.strictEqual(t.calls[0].options.timeout, 15000); expire(15000); await flush(); const error = state.error
    assert(error); assert.match(error.message, /超时/)
    t.calls[0].resolve({ statusCode: 401 }); await flush(); assert.strictEqual(state.error, error)
    assert.strictEqual(t.calls.length, 1); assert.strictEqual(t.http.length, 0); assert.strictEqual(timers.size, 0)
  }))
  test(side + ': initialization timeout cannot dispatch after late initialization', () => clock(async ({ expire }) => {
    const init = deferred(), t = setup(side, { init: () => init.promise }), state = observed(t.request.get('/api/test'))
    await flush(); expire(15000); await flush(); assert(state.error)
    init.resolve(); await flush(); assert.strictEqual(t.calls.length, 0); assert.strictEqual(t.http.length, 0)
  }))
  test(side + ': local abort reports uncertainty and ignores late success without claiming SDK cancellation', async () => {
    const t = setup(side), outcomes = []
    const task = t.transport.send({ path: '/api/orders/create', timeout: 15000, success: r => outcomes.push(r), fail: e => outcomes.push(e) })
    await flush(); task.abort(); assert.strictEqual(outcomes.length, 1)
    assert.strictEqual(outcomes[0].localOnly, true); assert.strictEqual(outcomes[0].requestMayHaveOccurred, true)
    t.calls[0].resolve(ok()); await flush(); assert.strictEqual(outcomes.length, 1)
  })
  test(side + ': late success from an old login cannot resolve under the new login', async () => {
    const t = setup(side), state = observed(t.request.get('/api/test')); await flush()
    if (side === 'miniapp-user') t.app.setLoginInfo('synthetic-B', 'synthetic-B-refresh', { customerId: 8 })
    else t.app.setLoginState({ accessToken: 'synthetic-B', refreshToken: 'synthetic-B-refresh', staffId: 8, role: 'DELIVERY', stationId: 2, bindStatus: 'BOUND' })
    t.calls[0].resolve(ok()); await flush(); assert(state.error && state.error.sessionChanged)
  })
  for (const version of ['develop', 'trial', 'release']) test(side + ': ' + version + ' HTTPS retains original wx.request path/task', async () => {
    const t = setup(side, { version }); t.config.API_CONFIG.trial.transport = 'https'; t.config.API_CONFIG.dev.transport = 'https'
    t.config.API_CONFIG.trial.baseUrl = 'https://synthetic-trial.invalid'; t.config.API_CONFIG.prod.baseUrl = 'https://synthetic-prod.invalid'
    const state = observed(t.request.get('/api/test', { q: 'a b' })); await flush()
    assert.strictEqual(t.http.length, 1); assert.strictEqual(t.calls.length, 0); assert.strictEqual(t.resources.length, 0)
    assert.strictEqual(t.http[0].url, t.config.getBaseUrl() + '/api/test?q=a%20b')
    assert.strictEqual(t.http[0].timeout, 15000); assert.strictEqual(t.http[0].header.Authorization, 'Bearer synthetic-access')
    t.http[0].success(ok()); await flush(); assert(state.value)
  })
}
test('mirrored transport bytes and original runner registration stay aligned', () => {
  assert.strictEqual(fs.readFileSync(path.join(ROOT, 'miniapp-user/utils/transport.js'), 'utf8'), fs.readFileSync(path.join(ROOT, 'miniapp-delivery/utils/transport.js'), 'utf8'))
  assert(fs.readFileSync(path.join(ROOT, 'tests/js/run-all.js'), 'utf8').includes("file: 'trial-cloud-transport.test.js'"))
})
test('GET connection probe shares cloud route and its 5-second deadline', () => clock(async ({ expire }) => {
  const t = setup('miniapp-user'), { backendCheck } = require(path.join(ROOT, 'miniapp-user/utils/backendCheck'))
  const first = observed(backendCheck.ping()); await flush()
  assert.strictEqual(t.calls[0].options.path, '/api/stations/public'); assert.strictEqual(t.calls[0].options.timeout, 5000)
  t.calls[0].resolve(ok([])); await flush(); assert.strictEqual(first.value.ok, true)
  const second = observed(backendCheck.ping()); await flush(); expire(5000); await flush()
  assert.strictEqual(second.value.ok, false); t.calls[1].resolve(ok([])); await flush(); assert.strictEqual(second.value.ok, false)
  assert.strictEqual(t.http.length, 0)
}))
test('system error report uses cloud route with JWT and no recursive business wrapper', async () => {
  const t = setup('miniapp-user'), state = observed(t.request.get('/api/test')); await flush()
  t.calls[0].resolve({ statusCode: 200, data: { code: 500, message: 'synthetic system failure' } }); await flush()
  assert(state.error); const prompt = t.wx.__calls.modal.at(-1); assert(prompt && prompt.success)
  prompt.success({ confirm: true }); await flush()
  assert.strictEqual(t.calls[1].options.path, '/api/feedback'); assert.strictEqual(t.calls[1].options.header.Authorization, 'Bearer synthetic-access')
  t.calls[1].resolve(ok()); await flush(); assert.strictEqual(t.http.length, 0)
})
test('actual create Page retains unknown original key/body after cloud timeout and late response', () => clock(async ({ expire }) => {
  const t = setup('miniapp-user')
  const page = loadPage('miniapp-user/pages/order/create.js', { wx: t.wx, app: t.app, stubs: {
    'api/order': { createOrder: body => t.request.post('/api/orders/create', body), getOrders: async () => ({ data: [] }) }
  } })
  page.setData({ stationId: 1, address: { id: 11 }, products: [{ id: 5, quantity: 2 }], selectedMethod: 2,
    quoteReady: true, quoteLoading: false, blocked: false, totalAmount: 20, note: 'synthetic original' })
  page._quotedContext = { key: page._quoteContextKey(), session: require(path.join(ROOT, 'miniapp-user/utils/token')).captureSession() }
  page._restorePendingOrderFromStorage(); page._syncPendingOrderState()
  const submit = page._createOrder(false); await flush(); assert.strictEqual(t.calls.length, 1)
  const original = JSON.parse(JSON.stringify(t.calls[0].options.data)); expire(15000); await submit
  assert.strictEqual(page._originalOrderRequest.phase, 'unknown')
  t.calls[0].resolve(ok({ orderId: 42 })); await flush(); assert.strictEqual(page._originalOrderRequest.phase, 'unknown')
  page.setData({ stationId: 2, note: 'synthetic edited' }); await page._createOrder(false); assert.strictEqual(t.calls.length, 1)
  page.setData({ stationId: 1 })
  const retry = page.onRetryUnknown(); await flush(); assert.strictEqual(t.calls.length, 2)
  assert.deepStrictEqual(t.calls[1].options.data, original); t.calls[1].resolve(ok({ orderId: 42 })); await retry; await flush()
  assert.strictEqual(page._originalOrderRequest.phase, 'known'); assert.strictEqual(t.http.length, 0)
}))
;(async () => {
  const done = armWatchdog(); let failed = 0
  for (const t of tests) { try { await t.run(); console.log('PASS ' + t.name) } catch (error) { failed++; console.error('FAIL ' + t.name + ': ' + error.stack) } }
  if (failed) process.exitCode = 1
  else console.log('AQUAFLOW_SUITE_OK ' + tests.length)
  done()
})().catch(error => { console.error(error.stack); process.exitCode = 1 })
