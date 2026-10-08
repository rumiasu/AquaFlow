const assert = require('assert'), path = require('path')
const { loadPage, createWx, armWatchdog, ROOT } = require('./harness')
const { STORAGE_KEYS: K } = require('../../miniapp-user/utils/storage-keys')
const done = armWatchdog(30000), tests = []
const test = (name, run) => tests.push({ name, run })
function loadApp(wx) {
  let app
  global.wx = wx; global.getApp = () => app
  global.__wxConfig = { envVersion: 'develop' }
  wx.getDeviceInfo = () => ({ platform: 'devtools' })
  global.App = config => { app = config }
  const filename = path.join(ROOT, 'miniapp-user/app.js')
  delete require.cache[require.resolve(filename)]; require(filename)
  return app
}
function login(t, id) {
  t.app.setLoginInfo('synthetic-access', 'synthetic-refresh', { nickname: 'synthetic' })
  t.wx.setStorageSync(K.CUSTOMER_ID, id)
}
function fixture(options = {}) {
  const wx = createWx(), app = loadApp(wx), t = { wx, app }
  login(t, 7)
  const products = options.products || [{ id: 10, category: 1, price: 20, deposit: 40 }, { id: 11, category: 2, price: 8 }]
  const page = loadPage('miniapp-user/pages/home/index.js', { wx, app, stubs: {
    'api/product': { getStationProducts: async () => ({ code: 0, data: products }) },
    'api/order': { getOrders: options.getOrders || (async () => ({ code: 0, data: [] })), getOrderDetail: async () => ({ code: 0, data: null }) },
    'api/address': { getAddresses: async () => ({ code: 0, data: [] }) },
    'api/barrel': { getBarrelSummary: async () => ({ code: 0, data: {} }), getBarrelSummaryByType: async () => ({ code: 0, data: [] }) },
    'api/station': { getStationStatus: async () => ({ code: 0, data: {} }) },
    'api/payment': { getQuote: async () => ({ code: 0, data: { independentRights: false } }) }
  } })
  page.setData({ isLogin: true, currentStationId: 1, products, cart: app.getCart(1) })
  return Object.assign(t, { page })
}
const change = (page, id, type) => page.onCartQtyChange({ currentTarget: { dataset: { id, type } } })
function restart(t) { const app = loadApp(t.wx); app.onLaunch(); return app }

test('add survives immediate cold restart without an onHide callback', () => {
  const t = fixture(); change(t.page, 10, 'add')
  assert.equal(t.page.data.total.count, 1); assert.deepStrictEqual(restart(t).getCart(1), { 10: 1 })
})
test('minus survives immediate cold restart', () => {
  const t = fixture(); t.app.setCartQty(1, 10, 3); change(t.page, 10, 'minus')
  assert.deepStrictEqual(restart(t).getCart(1), { 10: 2 })
})
test('removing the last selected unit cannot reappear after restart', () => {
  const t = fixture(); t.app.setCartQty(1, 10, 1); change(t.page, 10, 'minus')
  assert.equal(t.page.data.total.count, 0); assert.deepStrictEqual(restart(t).getCart(1), {})
})
test('buy again persists all matched products while excluding an unavailable product', () => {
  const t = fixture(); t.page.setData({ againOrder: { items: [{ productId: 10, quantity: 2 }, { productId: 11, quantity: 3 }, { productId: 99, quantity: 9 }] } })
  t.page.onAgainTap(); assert.equal(t.page.data.total.count, 5)
  assert.deepStrictEqual(restart(t).getCart(1), { 10: 2, 11: 3 })
})
test('shop buy-now handoff persists during loadData before backgrounding', async () => {
  const t = fixture(); t.page._pendingProductId = 10; await t.page.loadData()
  assert.equal(t.page._pendingProductId, null); assert.deepStrictEqual(restart(t).getCart(1), { 10: 1 })
})
test('unavailable buy-now handoff never adds an absent product', async () => {
  const t = fixture(); t.page._pendingProductId = 99; await t.page.loadData()
  assert.deepStrictEqual(restart(t).getCart(1), {})
})
test('a failed save keeps the latest visible and in-memory selection and the prior stored draft', () => {
  const t = fixture(); t.app.setCartQty(1, 10, 2)
  const original = t.wx.setStorageSync
  t.wx.setStorageSync = (key, value) => { if (key.startsWith(K.CART_PREFIX)) throw new Error('synthetic quota'); original(key, value) }
  change(t.page, 10, 'add')
  assert.equal(t.page.data.cart[10], 3); assert.equal(t.app.getCart(1)[10], 3)
  assert.equal(t.wx.getStorageSync(K.CART_PREFIX + '7').stations['1']['10'], 2)
})
test('different stations retain their own latest selections on restart', () => {
  const t = fixture(); change(t.page, 10, 'add'); t.page.setData({ currentStationId: 2, cart: t.app.getCart(2) })
  change(t.page, 11, 'add'); change(t.page, 11, 'add')
  const cold = restart(t); assert.deepStrictEqual(cold.getCart(1), { 10: 1 }); assert.deepStrictEqual(cold.getCart(2), { 11: 2 })
})
test('new customer cannot inherit the old home selection and the returning customer restores theirs', () => {
  const t = fixture(); change(t.page, 10, 'add'); login(t, 8)
  assert.deepStrictEqual(t.app.getCart(1), {}); login(t, 7); assert.deepStrictEqual(t.app.getCart(1), { 10: 1 })
})
test('persisted selection contains IDs and quantities and excludes prices and rights', () => {
  const t = fixture(); change(t.page, 10, 'add')
  assert.deepStrictEqual(t.wx.getStorageSync(K.CART_PREFIX + '7'), { version: 1, owner: '7', stations: { 1: { 10: 1 } } })
})
test('restored quantities use current product prices rather than saved historical amounts', async () => {
  const t = fixture(); change(t.page, 10, 'add'); const cold = restart(t)
  global.getApp = () => cold
  t.page.setData({ products: [{ id: 10, category: 2, price: 27 }], cart: cold.getCart(1) })
  await t.page.refreshDerived(); assert.equal(t.page.data.total.waterText, '27')
})
test('guest home offers no selectable product list or cart migration', () => {
  const t = fixture(); change(t.page, 10, 'add'); t.app.clearLoginInfo(); t.page.onShow()
  assert.equal(t.page.data.state, 'guest'); assert.deepStrictEqual(t.page.data.products, [])
  assert.deepStrictEqual(t.app.getCart(1), {}); login(t, 8); assert.deepStrictEqual(t.app.getCart(1), {})
})
test('late home load cannot apply buy-now handoff after customer changes', async () => {
  let resolve; const waiting = new Promise(r => { resolve = r })
  const t = fixture({ getOrders: () => waiting }); t.page._pendingProductId = 10
  const initial = t.page.loadData(); login(t, 8); resolve({ code: 0, data: [] }); await initial
  assert.deepStrictEqual(t.app.getCart(1), {}); assert.ok(!t.wx.getStorageSync(K.CART_PREFIX + '8'))
})
;(async () => {
  let passed = 0, failed = 0
  for (const t of tests) {
    try { await t.run(); passed++; console.log('PASS ' + t.name) }
    catch (e) { failed++; console.error('FAIL ' + t.name + ' [' + e.name + ']') }
  }
  console.log(`customer home cart persistence: ${passed} passed, ${failed} failed`)
  if (failed) process.exitCode = 1; else console.log('AQUAFLOW_SUITE_OK ' + passed)
})().catch(() => { process.exitCode = 1 }).finally(done)
