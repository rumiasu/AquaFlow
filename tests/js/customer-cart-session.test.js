const assert = require('assert')
const { createWx, armWatchdog, ROOT } = require('./harness')
const path = require('path')
const done = armWatchdog(30000), tests = []
const test = (name, run) => tests.push({ name, run })
const { STORAGE_KEYS: K } = require('../../miniapp-user/utils/storage-keys')
const key = id => 'aq_user_cart:' + id
function loadApp(wx = createWx()) {
  let app
  global.wx = wx; global.getApp = () => app
  global.__wxConfig = { envVersion: 'develop' }
  wx.getDeviceInfo = () => ({ platform: 'devtools' })
  global.App = config => { app = config }
  const filename = path.join(ROOT, 'miniapp-user/app.js')
  delete require.cache[require.resolve(filename)]; require(filename)
  return { app, wx }
}
function login(t, id) {
  // Match the existing login page: identity is written AFTER setLoginInfo.
  t.app.setLoginInfo('synthetic-access', 'synthetic-refresh', { nickname: 'synthetic' })
  t.wx.setStorageSync(K.CUSTOMER_ID, id)
}
test('customer B never inherits customer A cart at the same station', () => {
  const t = loadApp(); login(t, 7); t.app.addToCart(1, 10, 2)
  login(t, 8); assert.deepStrictEqual(t.app.getCart(1), {})
})
test('the login handoff gap contains no previous customer identity or cart', () => {
  const t = loadApp(); login(t, 7); t.app.globalData.customerId = 7; t.app.addToCart(1, 10, 2)
  t.app.setLoginInfo('synthetic-new', 'synthetic-refresh', {})
  assert.ok(!t.wx.getStorageSync(K.CUSTOMER_ID)); assert.ok(!t.app.globalData.customerId)
  assert.deepStrictEqual(t.app.getCart(1), {})
  t.app.getCart(1)[11] = 3; t.wx.setStorageSync(K.CUSTOMER_ID, 8)
  assert.deepStrictEqual(t.app.getCart(1), {})
})
test('station carts remain isolated for one customer', () => {
  const t = loadApp(); login(t, 7); t.app.addToCart(1, 10, 2); t.app.addToCart(2, 10, 4)
  assert.equal(t.app.getCartCount(1), 2); assert.equal(t.app.getCartCount(2), 4)
})
test('logout clears memory without exposing the saved customer cart to guests', () => {
  const t = loadApp(); login(t, 7); t.app.addToCart(1, 10, 2); t.app.clearLoginInfo()
  assert.deepStrictEqual(t.app.globalData.cart, {}); assert.deepStrictEqual(t.app.getCart(1), {})
  assert.equal(t.wx.getStorageSync(key(7)).stations['1']['10'], 2)
})
test('returning customer restores only their own saved draft', () => {
  const t = loadApp(); login(t, 7); t.app.addToCart(1, 10, 2)
  login(t, 8); t.app.addToCart(1, 11, 4); login(t, 7)
  assert.deepStrictEqual(t.app.getCart(1), { 10: 2 })
})
test('home direct reference edits are saved when the app enters background', () => {
  const t = loadApp(); login(t, 7); const cart = t.app.getCart(1); cart[10] = 3; cart[11] = 0
  t.app.onHide(); assert.deepStrictEqual(t.wx.getStorageSync(key(7)).stations['1'], { 10: 3 })
})
test('cold start restores customer and station drafts with network calls intercepted', () => {
  const t = loadApp(); login(t, 7); t.app.addToCart(1, 10, 2); t.app.addToCart(2, 11, 4)
  const cold = loadApp(t.wx); cold.app.onLaunch()
  assert.equal(cold.app.globalData.isLogin, true)
  assert.deepStrictEqual(cold.app.getCart(1), { 10: 2 }); assert.deepStrictEqual(cold.app.getCart(2), { 11: 4 })
})
test('each explicit cart mutation persists immediately', () => {
  const t = loadApp(); login(t, 7); t.app.addToCart(1, 10, 2)
  assert.equal(t.wx.getStorageSync(key(7)).stations['1']['10'], 2)
  t.app.setCartQty(1, 10, 5); assert.equal(t.wx.getStorageSync(key(7)).stations['1']['10'], 5)
  t.app.removeFromCart(1, 10); assert.deepStrictEqual(t.wx.getStorageSync(key(7)).stations['1'] || {}, {})
})
test('order success clearing persists only the selected station removal', () => {
  const t = loadApp(); login(t, 7); t.app.addToCart(1, 10, 2); t.app.addToCart(2, 11, 4)
  t.app.clearCart(1); const cold = loadApp(t.wx); cold.app.onLaunch()
  assert.deepStrictEqual(cold.app.getCart(1), {}); assert.deepStrictEqual(cold.app.getCart(2), { 11: 4 })
})
test('guest drafts do not merge into a logged-in customer', () => {
  const t = loadApp(); t.app.addToCart(1, 10, 2); login(t, 7)
  assert.deepStrictEqual(t.app.getCart(1), {})
})
test('a mismatched stored owner is never restored under another customer key', () => {
  const t = loadApp(); t.wx.setStorageSync(key(7), { version: 1, owner: '8', stations: { 1: { 10: 9 } } })
  login(t, 7); assert.deepStrictEqual(t.app.getCart(1), {})
})
test('persisted malformed IDs and quantities cannot enter the usable draft', () => {
  const t = loadApp(); t.wx.setStorageSync(key(7), { version: 1, owner: '7', stations: {
    1: { 10: 2, 11: -1, 12: 0, 13: 1.5, 14: '4', 15: true, bad: 3 }, bad: { 10: 5 }
  } })
  login(t, 7); assert.deepStrictEqual(t.app.getCart(1), { 10: 2, 14: 4 })
})
test('storage failure preserves usable current-customer memory and isolation', () => {
  const t = loadApp(); login(t, 7)
  const original = t.wx.setStorageSync
  t.wx.setStorageSync = (k, v) => { if (k.startsWith('aq_user_cart:')) throw new Error('synthetic quota'); original(k, v) }
  t.app.addToCart(1, 10, 2); assert.deepStrictEqual(t.app.getCart(1), { 10: 2 })
  login(t, 8); assert.deepStrictEqual(t.app.getCart(1), {})
})
test('direct identity changes persist the old owner without relabelling their cart', () => {
  const t = loadApp(); login(t, 7); t.app.getCart(1)[10] = 3
  t.wx.setStorageSync(K.CUSTOMER_ID, 8); assert.deepStrictEqual(t.app.getCart(1), {})
  assert.equal(t.wx.getStorageSync(key(7)).stations['1']['10'], 3)
})
;(async () => {
  let passed = 0, failed = 0
  for (const t of tests) {
    try { await t.run(); passed++; console.log('PASS ' + t.name) }
    catch (e) { failed++; console.error('FAIL ' + t.name + ' [' + e.name + ']') }
  }
  console.log(`customer cart session: ${passed} passed, ${failed} failed`)
  if (failed) process.exitCode = 1; else console.log('AQUAFLOW_SUITE_OK ' + passed)
})().catch(() => { process.exitCode = 1 }).finally(done)
