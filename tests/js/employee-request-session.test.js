// 员工请求的会话/续期隔离。执行真实 App、导航及请求源码，所有原生请求均截获。
const fs = require('fs')
const path = require('path')
const vm = require('vm')
const assert = require('assert')
const ROOT = path.resolve(__dirname, '../..')
const tests = []
const test = (name, fn) => tests.push({ name, fn })
const tick = () => new Promise(resolve => setImmediate(resolve))

function fixture() {
  let app
  const storage = new Map(), sent = [], routes = [], dependencies = {}
  const wx = {
    getStorageSync: key => storage.get(key),
    setStorageSync: (key, value) => storage.set(key, value),
    removeStorageSync: key => storage.delete(key),
    request: request => sent.push(request),
    reLaunch(options) { routes.push(options.url); options.success?.({}); options.complete?.({}) },
    showModal() {}, showToast() {}, hideLoading() {}, switchTab() {}, navigateTo() {}, navigateBack() {}
  }
  const config = { getBaseUrl: () => 'https://employee-session.invalid',
    API: { REFRESH: '/api/auth/refresh', LOGOUT: '/api/auth/logout', ME: '/api/auth/me' },
    BINDING_STATUS: { UNBOUND: 'UNBOUND', PENDING: 'PENDING', BOUND: 'BOUND', REJECTED: 'REJECTED', PENDING_UNBIND: 'PENDING_UNBIND' } }
  function load(name, filename) {
    const module = { exports: {} }
    vm.runInNewContext(fs.readFileSync(path.join(ROOT, filename), 'utf8'), {
      module, exports: module.exports, wx, getApp: () => app, App: value => { app = value },
      getCurrentPages: () => [{ route: 'pages/home/index' }], setTimeout, clearTimeout,
      console: { log() {}, warn() {}, error() {} },
      require(id) {
        if (id.includes('storage-keys')) return dependencies.keys
        if (id.endsWith('config/api')) return config
        if (id.endsWith('/navigation')) return dependencies.navigation
        if (id.endsWith('/request')) return dependencies.request
        if (id.includes('pending-reminder')) return { syncTabBarDot() {} }
        throw new Error('Unexpected test dependency')
      }
    }, { filename })
    dependencies[name] = module.exports
  }
  load('keys', 'miniapp-delivery/utils/storage-keys.js')
  load('navigation', 'miniapp-delivery/utils/navigation.js')
  load('request', 'miniapp-delivery/utils/request.js')
  load('app', 'miniapp-delivery/app.js')
  const keys = dependencies.keys.STORAGE_KEYS
  function login(label = 'A', id = 1) {
    app.setLoginState({ accessToken: label + '-synthetic-access', refreshToken: label + '-synthetic-refresh',
      staffId: id, role: 'STATION_MANAGER', stationId: id, bindStatus: 'BOUND' })
  }
  function renew(label) {
    app.globalData.accessToken = label + '-synthetic-access'
    app.globalData.refreshToken = label + '-synthetic-refresh'
    wx.setStorageSync(keys.ACCESS_TOKEN, app.globalData.accessToken)
    wx.setStorageSync(keys.REFRESH_TOKEN, app.globalData.refreshToken)
  }
  function observe(promise) {
    const result = { state: 'pending' }
    promise.then(value => { result.state = 'resolved'; result.value = value }, error => { result.state = 'rejected'; result.error = error })
    return result
  }
  const finish = (request, response) => { request.success(response); request.complete?.() }
  const fail = request => { request.fail({ errMsg: 'request:fail synthetic timeout' }); request.complete?.() }
  const ok = (data = {}) => ({ statusCode: 200, data: { code: 0, data } })
  const refreshed = label => ok({ accessToken: label + '-synthetic-access', refreshToken: label + '-synthetic-refresh' })
  const expired = { statusCode: 401 }
  login()
  return { get app() { return app }, replaceApp(value) { app = value }, sent, routes, keys, wx, login, renew, observe,
    finish, fail, ok, refreshed, expired, api: dependencies.request }
}

test('late A 401 cannot refresh B or replay an A write', async () => {
  const f = fixture(), a = f.observe(f.api.post('/probe/write', { owner: 'A' }))
  f.login('B', 2); f.finish(f.sent[0], f.expired); await tick()
  assert.equal(a.state, 'rejected'); assert.equal(f.sent.length, 1); assert.equal(f.app.globalData.userInfo.staffId, 2)
})
test('old refresh success cannot replace a new login', async () => {
  const f = fixture(), a = f.observe(f.api.post('/probe/write', { owner: 'A' }))
  f.finish(f.sent[0], f.expired); f.login('B', 2); f.finish(f.sent[1], f.refreshed('A2')); await tick()
  assert.equal(a.state, 'rejected'); assert.equal(f.sent.length, 2); assert.ok(f.app.globalData.accessToken === 'B-synthetic-access')
})
test('old refresh failure cannot clear or redirect a new login', async () => {
  const f = fixture(), a = f.observe(f.api.get('/probe/read'))
  f.finish(f.sent[0], f.expired); f.login('B', 2); f.fail(f.sent[1]); await tick()
  assert.equal(a.state, 'rejected'); assert.equal(f.routes.length, 0); assert.equal(f.app.globalData.userInfo.staffId, 2)
})
test('B starts its own refresh while A refresh is pending', async () => {
  const f = fixture(), a = f.observe(f.api.post('/probe/write', { owner: 'A' }))
  f.finish(f.sent[0], f.expired); f.login('B', 2)
  const b = f.observe(f.api.post('/probe/write', { owner: 'B' })); f.finish(f.sent[2], f.expired)
  assert.equal(f.sent.length, 4, 'new session needs its own refresh')
  assert.ok(f.sent[3].data.refreshToken === 'B-synthetic-refresh')
  f.finish(f.sent[1], f.refreshed('A2')); await tick()
  assert.equal(a.state, 'rejected'); assert.equal(b.state, 'pending')
  f.finish(f.sent[3], f.refreshed('B2')); f.finish(f.sent[4], f.ok()); await tick()
  assert.equal(b.state, 'resolved'); assert.equal(f.sent[4].data.owner, 'B')
})
test('old complete cannot release the current refresh flight', async () => {
  const f = fixture(), a = f.observe(f.api.get('/probe/a'))
  f.finish(f.sent[0], f.expired); f.login('B', 2)
  const b = f.observe(f.api.get('/probe/b')); f.finish(f.sent[2], f.expired)
  assert.equal(f.sent.length, 4); f.fail(f.sent[1])
  const c = f.observe(f.api.get('/probe/c')); f.finish(f.sent[4], f.expired)
  assert.equal(f.sent.length, 5, 'B requests should share only B flight')
  f.finish(f.sent[3], f.refreshed('B2')); f.finish(f.sent[5], f.ok()); f.finish(f.sent[6], f.ok()); await tick()
  assert.equal(a.state, 'rejected'); assert.equal(b.state, 'resolved'); assert.equal(c.state, 'resolved')
})
test('malformed old refresh cannot reject the current queue', async () => {
  const f = fixture(), a = f.observe(f.api.get('/probe/a'))
  f.finish(f.sent[0], f.expired); f.login('B', 2)
  const b = f.observe(f.api.get('/probe/b')); f.finish(f.sent[2], f.expired)
  assert.equal(f.sent.length, 4); f.finish(f.sent[1], { statusCode: 200, data: null }); await tick()
  assert.equal(a.state, 'rejected'); assert.equal(b.state, 'pending')
  f.finish(f.sent[3], f.refreshed('B2')); f.finish(f.sent[4], f.ok()); await tick(); assert.equal(b.state, 'resolved')
})
test('ordinary success from a previous login is rejected', async () => {
  const f = fixture(), a = f.observe(f.api.get('/probe/read'))
  f.login('B', 2); f.finish(f.sent[0], f.ok({ receipt: 'A' })); await tick(); assert.equal(a.state, 'rejected')
})
test('retry success from a previous login is rejected', async () => {
  const f = fixture(), a = f.observe(f.api.post('/probe/write', { owner: 'A' }))
  f.finish(f.sent[0], f.expired); f.finish(f.sent[1], f.refreshed('A2'))
  f.login('B', 2); f.finish(f.sent[2], f.ok()); await tick(); assert.equal(a.state, 'rejected')
})
test('retry failure reports session change without clearing the new login', async () => {
  const f = fixture(), a = f.observe(f.api.get('/probe/read'))
  f.finish(f.sent[0], f.expired); f.finish(f.sent[1], f.refreshed('A2'))
  f.login('B', 2); f.fail(f.sent[2]); await tick()
  assert.equal(a.state, 'rejected'); assert.equal(a.error.sessionChanged, true); assert.equal(f.app.globalData.userInfo.staffId, 2)
})
test('same credentials explicitly logged in again form a new session', async () => {
  const f = fixture(), a = f.observe(f.api.get('/probe/read'))
  f.login(); f.finish(f.sent[0], f.expired); await tick(); assert.equal(a.state, 'rejected'); assert.equal(f.sent.length, 1)
})
test('old refresh cannot overwrite an explicit same-credential relogin', async () => {
  const f = fixture(), a = f.observe(f.api.get('/probe/read'))
  f.finish(f.sent[0], f.expired); f.login(); f.finish(f.sent[1], f.refreshed('A2')); await tick()
  assert.equal(a.state, 'rejected'); assert.ok(f.app.globalData.accessToken === 'A-synthetic-access')
})
test('late 401 after ordinary renewal replays once with current credentials', async () => {
  const f = fixture(), a = f.observe(f.api.get('/probe/read'))
  f.renew('A2'); f.finish(f.sent[0], f.expired)
  assert.equal(f.sent.length, 2); assert.ok(f.sent[1].header.Authorization === 'Bearer A2-synthetic-access')
  assert.equal(f.sent[1].url.endsWith('/probe/read'), true); f.finish(f.sent[1], f.ok()); await tick(); assert.equal(a.state, 'resolved')
})
test('same-session renewal does not discard an ordinary success', async () => {
  const f = fixture(), a = f.observe(f.api.get('/probe/read'))
  f.renew('A2'); f.finish(f.sent[0], f.ok()); await tick(); assert.equal(a.state, 'resolved')
})
test('one current flight refreshes all current queued requests', async () => {
  const f = fixture(), a = f.observe(f.api.get('/probe/a')), b = f.observe(f.api.get('/probe/b'))
  f.finish(f.sent[0], f.expired); f.finish(f.sent[1], f.expired); assert.equal(f.sent.length, 3)
  f.finish(f.sent[2], f.refreshed('A2')); assert.equal(f.sent.length, 5)
  f.finish(f.sent[3], f.ok()); f.finish(f.sent[4], f.ok()); await tick(); assert.equal(a.state, 'resolved'); assert.equal(b.state, 'resolved')
})
test('retry preserves original body, query, headers and idempotency intent', async () => {
  const f = fixture(), body = { qty: 1, nested: { product: 3 }, idempotencyKey: 'same-intent' }, query = { page: 1 }, header = { 'X-Intent': 'original' }
  const a = f.observe(f.api.request({ url: '/probe/write', method: 'POST', data: body, query, header }))
  body.qty = 9; body.nested.product = 4; query.page = 2; header['X-Intent'] = 'changed'
  f.finish(f.sent[0], f.expired); f.finish(f.sent[1], f.refreshed('A2'))
  assert.equal(f.sent[2].data.qty, 1); assert.equal(f.sent[2].data.nested.product, 3)
  assert.equal(f.sent[2].data.idempotencyKey, 'same-intent'); assert.equal(f.sent[2].url.endsWith('page=1'), true)
  assert.equal(f.sent[2].header['X-Intent'], 'original'); f.finish(f.sent[2], f.ok()); await tick(); assert.equal(a.state, 'resolved')
})
for (const response of ['success', 'malformed', 'failure']) test('superseded same-session refresh ' + response + ' preserves newer credentials', async () => {
  const f = fixture(), a = f.observe(f.api.get('/probe/read'))
  f.finish(f.sent[0], f.expired); f.renew('A2')
  if (response === 'failure') f.fail(f.sent[1])
  else f.finish(f.sent[1], response === 'success' ? f.refreshed('obsolete') : { statusCode: 200, data: null })
  assert.equal(f.sent.length, 3); assert.ok(f.app.globalData.accessToken === 'A2-synthetic-access')
  assert.ok(f.sent[2].header.Authorization === 'Bearer A2-synthetic-access')
  f.finish(f.sent[2], f.ok()); await tick(); assert.equal(a.state, 'resolved'); assert.equal(f.routes.length, 0)
})
test('an App owner replacement invalidates callbacks even with matching credentials', async () => {
  const f = fixture(), a = f.observe(f.api.get('/probe/read'))
  f.replaceApp({ globalData: { ...f.app.globalData }, _loginGeneration: f.app._loginGeneration })
  f.finish(f.sent[0], f.ok()); await tick(); assert.equal(a.state, 'rejected')
})

async function main() {
  const watchdog = setTimeout(() => { console.error('employee session suite timed out'); process.exit(1) }, 30000)
  let passed = 0, failed = 0
  for (const item of tests) {
    try { await item.fn(); passed++; console.log('PASS ' + item.name) }
    catch (error) { failed++; console.error('FAIL ' + item.name + ' [' + error.name + ']') }
  }
  clearTimeout(watchdog)
  console.log('employee request session: ' + passed + ' passed, ' + failed + ' failed')
  if (failed) process.exitCode = 1
  else console.log('AQUAFLOW_SUITE_OK ' + passed)
}
main().catch(() => { console.error('employee session suite interrupted'); process.exitCode = 1 })
