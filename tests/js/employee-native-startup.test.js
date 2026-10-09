// 2026-10-09：限定员工端 App 注册期与原生 JSON 模块差异；不模拟业务写入成功。
const assert = require('assert')
const fs = require('fs')
const path = require('path')
const Module = require('module')
const { createWx, createApp, loadPage, armWatchdog, ROOT } = require('./harness')
const done = armWatchdog(30000)
const tests = []
const test = (name, run) => tests.push({ name, run })
const staffRoot = path.join(ROOT, 'miniapp-delivery') + path.sep
const { STORAGE_KEYS: K } = require('../../miniapp-delivery/utils/storage-keys')

function clearStaffModules() {
  for (const name of Object.keys(require.cache)) if (name.startsWith(staffRoot)) delete require.cache[name]
}
function boot(user) {
  clearStaffModules()
  const wx = createWx(), sent = []
  wx.request = options => sent.push(options)
  if (user) {
    wx.__storage.set(K.ACCESS_TOKEN, 'synthetic-staff-access')
    wx.__storage.set(K.REFRESH_TOKEN, 'synthetic-staff-refresh')
    wx.__storage.set(K.USER_INFO, user)
  }
  let app, registered, earlyReads = 0
  global.wx = wx
  global.__wxConfig = { envVersion: 'develop' }
  global.getCurrentPages = () => [{ route: 'pages/login/index' }]
  global.getApp = () => { if (!registered) earlyReads++; return registered }
  global.App = config => {
    app = config
    app.onLaunch()
    registered = app
  }
  require(path.join(ROOT, 'miniapp-delivery/app.js'))
  return { app, wx, sent, earlyReads: () => earlyReads }
}
function withoutNodeJsonModules(run) {
  const original = Module._load
  Module._load = function (request, parent) {
    if (parent && parent.filename.startsWith(staffRoot) && request.endsWith('.json')) {
      throw new Error('Native module is not defined: ' + request + '.js')
    }
    return original.apply(this, arguments)
  }
  try { return run() } finally { Module._load = original }
}

test('employee guest launch does not read global App before registration or send authentication', () => {
  const t = boot()
  assert.equal(t.earlyReads(), 0)
  assert.equal(t.app.globalData.isLogin, false)
  assert.equal(t.sent.length, 0)
  assert.equal(t.wx.__calls.storageSet.length, 0)
})
test('restored delivery launch preserves bound identity without early global App reads', () => {
  const t = boot({ staffId: 17, role: 'DELIVERY', stationId: 3, bindStatus: 'BOUND' })
  assert.equal(t.earlyReads(), 0)
  assert.equal(t.app.globalData.userInfo.staffId, 17)
  assert.equal(t.app.globalData.userInfo.stationId, 3)
  assert.equal(t.app.globalData.isLogin, true)
  assert.equal(t.sent.length, 0)
  assert.equal(t.wx.__calls.storageSet.length, 0)
})
test('restored manager launch normalizes cached identity without credential replacement', () => {
  const t = boot({ staffId: 18, role: 'MANAGER', stationId: 2 })
  assert.equal(t.earlyReads(), 0)
  assert.equal(t.app.globalData.userInfo.role, 'STATION_MANAGER')
  assert.equal(t.app.globalData.userInfo.stationId, 2)
  assert.equal(t.app.globalData.accessToken, 'synthetic-staff-access')
  assert.equal(t.sent.length, 0)
  assert.equal(t.wx.__calls.storageSet.length, 0)
})
test('employee guest first show clears only the local reminder and does not authenticate', () => {
  const t = boot()
  t.app.onShow()
  assert.equal(t.sent.length, 0)
  assert.equal(t.wx.__calls.nav.length, 0)
  assert.ok(t.wx.__calls.storageSet.every(write => write.k === 'homeTabDotOn'))
  assert.equal(t.app.globalData.isLogin, false)
})
test('employee offline agreements load under the native JavaScript-only module contract', () => {
  clearStaffModules()
  const helper = withoutNodeJsonModules(() => require(path.join(ROOT, 'miniapp-delivery/utils/agreements.js')))
  const mirror = JSON.parse(fs.readFileSync(path.join(ROOT, 'miniapp-delivery/data/agreement-drafts.json'), 'utf8'))
  assert.deepStrictEqual(helper.drafts, mirror)
  assert.equal(helper.validCatalog(helper.drafts), true)
  assert.ok(helper.drafts.documents.every(doc => doc.audience === 'STAFF' && doc.active === false))
  assert.equal(helper.loginEvidence(helper.drafts), undefined)
})
test('native-compatible employee login fallback never authenticates or records acceptance', async () => {
  clearStaffModules()
  const wx = createWx(), app = createApp({ globalData: { isLogin: false } })
  const login = withoutNodeJsonModules(() => loadPage('miniapp-delivery/pages/login/index.js', { wx, app,
    stubs: { 'api/auth': {}, 'api/agreements': { current: async () => { throw Error('synthetic offline') } } } }))
  await login.loadAgreements()
  assert.equal(login.data.agreementCatalog.enabled, false)
  assert.equal(login.data.loading, false)
  assert.equal(wx.__calls.request.length, 0)
  assert.equal(wx.__calls.storageSet.length, 0)
})
test('employee native read-only agreement fallback displays the matching disabled draft', async () => {
  clearStaffModules()
  const wx = createWx(), app = createApp({ globalData: { isLogin: false } })
  wx.setNavigationBarTitle = () => {}
  const page = withoutNodeJsonModules(() => loadPage('miniapp-delivery/pages/mine/agreement/index.js', { wx, app,
    stubs: { 'api/agreements': { current: async () => { throw Error('synthetic offline') } } } }))
  page.onLoad({ type: 'privacy' })
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(page.data.doc.type, 'privacy')
  assert.equal(page.data.doc.audience, 'STAFF')
  assert.equal(page.data.doc.active, false)
  assert.ok(page.data.error)
  assert.ok(page.data.fallbackNotice)
  assert.equal(page.data.loading, false)
  assert.equal(wx.__calls.request.length, 0)
  assert.equal(wx.__calls.storageSet.length, 0)
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
