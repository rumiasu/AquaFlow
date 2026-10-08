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

function newIdentity(overrides) {
  return { accessToken: 'B-synthetic-access', refreshToken: 'B-synthetic-refresh',
    staffId: 2, role: 'STATION_MANAGER', stationId: 2, bindStatus: 'BOUND', ...overrides }
}
for (const value of [undefined, null, '', '   ']) {
  test('a new login with missing/empty refresh does not reuse the old cached refresh ' + String(value), async () => {
    const f = fixture(); f.app.setLoginState(newIdentity({ refreshToken: value }))
    assert.ok(!f.wx.getStorageSync(f.keys.REFRESH_TOKEN))
    const request = f.observe(f.api.get('/probe/b')); f.finish(f.sent[0], f.expired); await tick()
    assert.equal(f.sent.length, 1); assert.equal(request.state, 'rejected')
  })
  test('a new login with missing/empty access cannot reuse the old cached access ' + String(value), async () => {
    const f = fixture(); f.app.setLoginState(newIdentity({ accessToken: value }))
    assert.ok(!f.wx.getStorageSync(f.keys.ACCESS_TOKEN)); assert.equal(f.app.globalData.isLogin, false)
    const request = f.observe(f.api.get('/probe/b'))
    assert.ok(!f.sent[0].header.Authorization); f.finish(f.sent[0], f.expired); await tick()
    assert.equal(f.sent.length, 1); assert.equal(request.state, 'rejected')
  })
}
test('late A ordinary and refresh responses cannot resurrect a new incomplete B login', async () => {
  const f = fixture(), old = f.observe(f.api.get('/probe/a')), ordinary = f.observe(f.api.get('/probe/a-ordinary'))
  f.finish(f.sent[0], f.expired)
  f.app.setLoginState(newIdentity({ accessToken: null, refreshToken: null }))
  f.finish(f.sent[1], f.ok({ owner: 'A' })); f.finish(f.sent[2], f.refreshed('A2')); await tick()
  assert.equal(old.state, 'rejected'); assert.equal(ordinary.state, 'rejected')
  assert.equal(f.app.globalData.isLogin, false); assert.ok(!f.wx.getStorageSync(f.keys.ACCESS_TOKEN))
})
test('valid UNSELECTED login preserves both credentials without a staff ID or station', async () => {
  const f = fixture(); f.app.setLoginState(newIdentity({ staffId: null, stationId: null, role: 'UNSELECTED', needSelectRole: true }))
  assert.equal(f.app.globalData.isLogin, true); assert.equal(f.app.globalData.userInfo.role, 'UNSELECTED')
  const request = f.observe(f.api.get('/api/auth/me')); f.finish(f.sent[0], f.ok()); await tick()
  assert.equal(request.state, 'resolved'); assert.ok(f.sent[0].header.Authorization === 'Bearer B-synthetic-access')
})
test('binding status update passing the current complete pair keeps that pair', () => {
  const f = fixture(); const current = f.app.globalData
  f.app.setLoginState({ ...current.userInfo, accessToken: current.accessToken, refreshToken: current.refreshToken, bindStatus: 'PENDING_UNBIND' })
  assert.ok(f.wx.getStorageSync(f.keys.ACCESS_TOKEN) === 'A-synthetic-access')
  assert.ok(f.wx.getStorageSync(f.keys.REFRESH_TOKEN) === 'A-synthetic-refresh')
  assert.equal(f.app.globalData.isLogin, true)
})
for (const target of ['ACCESS_TOKEN', 'REFRESH_TOKEN', 'USER_INFO']) {
  test('login cache write failure never leaves an old/new credential mixture ' + target, () => {
    const f = fixture(), original = f.wx.setStorageSync
    f.wx.setStorageSync = (key, value) => { if (key === f.keys[target]) throw new Error('synthetic storage failure'); original(key, value) }
    assert.throws(() => f.app.setLoginState(newIdentity()))
    assert.equal(f.app.globalData.isLogin, false)
    assert.ok(!f.wx.getStorageSync(f.keys.ACCESS_TOKEN)); assert.ok(!f.wx.getStorageSync(f.keys.REFRESH_TOKEN))
  })
}
for (const target of ['ACCESS_TOKEN', 'REFRESH_TOKEN']) {
  test('rotated credential cache failure settles every waiter without replaying old refresh ' + target, async () => {
    const f = fixture(), a = f.observe(f.api.get('/probe/a')), b = f.observe(f.api.get('/probe/b'))
    f.finish(f.sent[0], f.expired); f.finish(f.sent[1], f.expired)
    const original = f.wx.setStorageSync
    f.wx.setStorageSync = (key, value) => { if (key === f.keys[target]) throw new Error('synthetic storage failure'); original(key, value) }
    assert.doesNotThrow(() => f.finish(f.sent[2], f.refreshed('A2'))); await tick()
    assert.equal(a.state, 'rejected'); assert.equal(b.state, 'rejected'); assert.equal(f.sent.length, 3)
    assert.equal(f.app.globalData.isLogin, false)
    assert.ok(!f.wx.getStorageSync(f.keys.ACCESS_TOKEN)); assert.ok(!f.wx.getStorageSync(f.keys.REFRESH_TOKEN))
    f.wx.setStorageSync = original; f.login('C', 3)
    const next = f.observe(f.api.get('/probe/c')); f.finish(f.sent[3], f.expired)
    assert.ok(f.sent[4].data.refreshToken === 'C-synthetic-refresh')
    f.finish(f.sent[4], f.refreshed('C2')); f.finish(f.sent[5], f.ok()); await tick(); assert.equal(next.state, 'resolved')
  })
}
test('rotation response missing the new refresh token is refused instead of reusing the consumed old token', async () => {
  const f = fixture(), request = f.observe(f.api.get('/probe/a')); f.finish(f.sent[0], f.expired)
  f.finish(f.sent[1], f.ok({ accessToken: 'A2-synthetic-access' })); await tick()
  assert.equal(request.state, 'rejected'); assert.equal(f.sent.length, 2); assert.equal(f.app.globalData.isLogin, false)
})
test('credential cleanup attempts all keys even if one native removal fails', () => {
  const f = fixture(), original = f.wx.removeStorageSync
  f.wx.removeStorageSync = key => { if (key === f.keys.ACCESS_TOKEN) throw new Error('synthetic remove failure'); original(key) }
  assert.doesNotThrow(() => f.app.clearLoginState()); assert.equal(f.app.globalData.isLogin, false)
  assert.ok(!f.wx.getStorageSync(f.keys.REFRESH_TOKEN)); assert.ok(!f.wx.getStorageSync(f.keys.USER_INFO))
})
test('a missing in-memory refresh never borrows the stale stored refresh even before cache repair', async () => {
  const f = fixture()
  Object.assign(f.app.globalData, { accessToken: 'B-synthetic-access', refreshToken: null,
    userInfo: { staffId: 2, role: 'STATION_MANAGER', stationId: 2, bindStatus: 'BOUND' } })
  const request = f.observe(f.api.get('/probe/b')); f.finish(f.sent[0], f.expired); await tick()
  assert.equal(request.state, 'rejected'); assert.equal(f.sent.length, 1)
})
test('refresh cache and cleanup failures still settle all waiters and suppress remaining stored credentials', async () => {
  const f = fixture(), a = f.observe(f.api.get('/probe/a')), b = f.observe(f.api.get('/probe/b'))
  f.finish(f.sent[0], f.expired); f.finish(f.sent[1], f.expired)
  f.wx.setStorageSync = () => { throw new Error('synthetic write failure') }
  f.wx.removeStorageSync = () => { throw new Error('synthetic remove failure') }
  assert.doesNotThrow(() => f.finish(f.sent[2], f.refreshed('A2'))); await tick()
  assert.equal(a.state, 'rejected'); assert.equal(b.state, 'rejected'); assert.equal(f.app.globalData.isLogin, false)
  const c = f.observe(f.api.get('/probe/c')); assert.ok(!f.sent[3].header.Authorization)
  f.finish(f.sent[3], f.expired); await tick(); assert.equal(c.state, 'rejected'); assert.equal(f.sent.length, 4)
})
async function main() {
  const watchdog = setTimeout(() => { console.error('employee credential suite timed out'); process.exit(1) }, 30000)
  let passed = 0, failed = 0
  for (const item of tests) {
    try { await item.fn(); passed++; console.log('PASS ' + item.name) }
    catch (error) { failed++; console.error('FAIL ' + item.name + ' [' + error.name + ']') }
  }
  clearTimeout(watchdog)
  console.log(`employee credential faults: ${passed} passed, ${failed} failed`)
  if (failed) process.exitCode = 1; else console.log('AQUAFLOW_SUITE_OK ' + passed)
}
main().catch(() => { console.error('employee credential suite interrupted'); process.exitCode = 1 })
