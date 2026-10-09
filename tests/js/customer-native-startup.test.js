// Native errors observed on 2026-10-09: App is unavailable during onLaunch;
// the mini-program loader resolves JavaScript modules, not Node's JSON modules.
const assert = require('assert')
const fs = require('fs')
const path = require('path')
const Module = require('module')
const { createWx, createApp, loadPage, armWatchdog, ROOT } = require('./harness')
const done = armWatchdog(30000)
const tests = []
const test = (name, run) => tests.push({ name, run })
const customerRoot = path.join(ROOT, 'miniapp-user') + path.sep
const { STORAGE_KEYS: K } = require('../../miniapp-user/utils/storage-keys')

function clearCustomerModules() {
  for (const name of Object.keys(require.cache)) if (name.startsWith(customerRoot)) delete require.cache[name]
}
function boot(restored = true) {
  clearCustomerModules()
  const wx = createWx(), sent = []
  wx.request = options => sent.push(options)
  if (restored) {
    wx.__storage.set(K.ACCESS_TOKEN, 'synthetic-original-access')
    wx.__storage.set(K.REFRESH_TOKEN, 'synthetic-original-refresh')
    wx.__storage.set(K.CUSTOMER_ID, 7)
    wx.__storage.set(K.USER_INFO, { customerId: 7, nickName: 'synthetic' })
  }
  let app, registered, earlyReads = 0
  global.wx = wx
  global.__wxConfig = { envVersion: 'develop' }
  global.getCurrentPages = () => [{}]
  global.getApp = () => { if (!registered) earlyReads++; return registered }
  global.App = config => {
    app = config
    // Unlike older fixtures, do not expose the App until its launch callback returns.
    app.onLaunch()
    registered = app
  }
  require(path.join(ROOT, 'miniapp-user/app.js'))
  return { app, wx, sent, earlyReads: () => earlyReads,
    token: require(path.join(ROOT, 'miniapp-user/utils/token.js')) }
}
function withoutNodeJsonModules(run) {
  const original = Module._load
  Module._load = function (request, parent) {
    if (parent && parent.filename.startsWith(customerRoot) && request.endsWith('.json')) {
      throw new Error('Native module is not defined: ' + request + '.js')
    }
    return original.apply(this, arguments)
  }
  try { return run() } finally { Module._load = original }
}

test('restored customer cold start works before global App registration', () => {
  const t = boot()
  assert.equal(t.earlyReads(), 0)
  assert.equal(t.sent.length, 1)
  assert.equal(t.sent[0].header.Authorization, 'Bearer synthetic-original-access')
  assert.equal(t.token.captureSession().app, t.app)
  assert.equal(t.token.captureSession().customerId, 7)
  assert.equal(t.wx.__calls.storageSet.length, 0, 'launch must not replace credentials')
  t.sent[0].success({ statusCode: 200, data: { code: 0, data: { customerId: 7, nickName: 'verified' } } })
  assert.equal(t.app.globalData.userInfo.nickName, 'verified')
})
test('guest cold start has no authentication request or storage side effect', () => {
  const t = boot(false)
  assert.equal(t.earlyReads(), 0)
  assert.equal(t.sent.length, 0)
  assert.equal(t.token.captureSession().loggedIn, false)
  assert.equal(t.token.getCustomerId(), null)
  assert.equal(t.wx.__calls.storageSet.length, 0)
})
test('late startup response cannot overwrite a later customer login', () => {
  const t = boot()
  t.app.setLoginInfo('synthetic-new-access', 'synthetic-new-refresh', { customerId: 8, nickName: 'new' })
  t.wx.setStorageSync(K.CUSTOMER_ID, 8)
  t.sent[0].success({ statusCode: 200, data: { code: 0, data: { customerId: 7, nickName: 'old' } } })
  assert.equal(t.app.globalData.userInfo.nickName, 'new')
  assert.equal(t.token.getCustomerId(), 8)
  assert.equal(t.sent.length, 1)
})
test('late startup rejection cannot clear a later login or navigate away', () => {
  const t = boot()
  t.app.setLoginInfo('synthetic-new-access', 'synthetic-new-refresh', { customerId: 8 })
  t.wx.setStorageSync(K.CUSTOMER_ID, 8)
  t.sent[0].success({ statusCode: 401, data: { code: 401 } })
  assert.equal(t.app.globalData.accessToken, 'synthetic-new-access')
  assert.equal(t.sent.length, 1)
  assert.equal(t.wx.__calls.nav.length, 0)
})
test('customer offline agreements load with the native JavaScript-only module contract', () => {
  clearCustomerModules()
  const helper = withoutNodeJsonModules(() => require(path.join(ROOT, 'miniapp-user/utils/agreements.js')))
  const mirror = JSON.parse(fs.readFileSync(path.join(ROOT, 'miniapp-user/data/agreement-drafts.json'), 'utf8'))
  assert.deepStrictEqual(helper.drafts, mirror)
  assert.equal(helper.validCatalog(helper.drafts), true)
  assert.equal(helper.loginEvidence(helper.drafts), undefined)
})
test('native-compatible login fallback remains a draft without acceptance or authentication', () => {
  clearCustomerModules()
  const wx = createWx(), app = createApp({ globalData: { isLogin: false } })
  const login = withoutNodeJsonModules(() => loadPage('miniapp-user/pages/login/index.js', { wx, app,
    stubs: { 'api/auth': {}, 'api/agreements': { current: async () => { throw Error('synthetic offline') } } } }))
  return login.loadAgreements().then(() => {
    assert.equal(login.data.agreementCatalog.enabled, false)
    assert.equal(wx.__calls.request.length, 0)
    assert.equal(wx.__calls.storageSet.length, 0)
  })
})

;(async () => {
  let failed = 0
  for (const t of tests) {
    try { await t.run(); console.log('PASS ' + t.name) }
    catch (error) { failed++; console.error('FAIL ' + t.name, error.message) }
  }
  done()
  if (failed) process.exitCode = 1
  else console.log('AQUAFLOW_SUITE_OK ' + tests.length)
})()
