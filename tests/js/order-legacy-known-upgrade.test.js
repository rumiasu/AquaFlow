'use strict'
// Actual Page + actual order API read wrappers; synthetic transport, no service/database access.
const assert = require('assert')
const path = require('path')
const { loadPage, createWx, createApp, armWatchdog } = require('./harness')
const done = armWatchdog(), tests = []
const test = (name, run) => tests.push({ name, run })
const flush = async () => { for (let i = 0; i < 50; i++) await Promise.resolve() }
const key = 'oi-legacy-owned', fingerprint = '7|1|11|2|5x1'
function fixture(options = {}) {
  for (const file of Object.keys(require.cache)) if (file.includes(path.sep + 'miniapp-user' + path.sep)) delete require.cache[file]
  const wx = createWx(), app = createApp({ globalData: { isLogin: true, customerId: 7, tempStationId: 1 } })
  wx.__modalAutoConfirm = false
  const at = Date.now() - (options.age == null ? 11 * 60 * 1000 : options.age)
  const legacy = { key, fingerprint, customerId: 7, savedAt: at - 500 }
  wx.setStorageSync('orderIntent.v1', legacy)
  if (options.receipt !== false) wx.setStorageSync('recentOrder.v1', { orderId: 205, fingerprint, at, paid: true, paymentState: 'paid' })
  const order = { id: 205, customerId: 7, idempotencyKey: key, stationId: 1, deliveryStationId: 1,
    addressId: 11, paymentMethod: 2, paymentStatus: 2, status: 4, statusText: '已完成', payState: 'PAID', payStateText: '已付款',
    createTime: new Date(at).toISOString(), items: [{ productId: 5, quantity: 1, price: 20, category: 2 }] }
  const calls = { gets: [], creates: [] }
  const request = { get: async (url, params) => {
    calls.gets.push({ url, params })
    if (url === '/api/orders') return { data: options.pages ? (options.pages[(params && params.page) || 1] || []) : [order] }
    if (/^\/api\/orders\/\d+$/.test(url)) {
      if (options.detail) return options.detail(url, order)
      return { data: order }
    }
    if (/\/products\/\d+$/.test(url)) return { data: { id: 5, name: 'Synthetic', price: 20, category: 2 } }
    if (/address/.test(url)) return { data: [{ id: 11, isDefault: true }] }
    if (/summary/.test(url)) return { data: [] }
    return { data: {} }
  }, post: async (url, body) => {
    if (url !== '/api/orders/create') throw Error('unexpected write ' + url)
    calls.creates.push(JSON.parse(JSON.stringify(body))); return { data: { orderId: 900, warnings: [] } }
  }, put: async () => ({ data: {} }) }
  const stubs = { 'utils/request': request, 'api/payment': { getQuote: async body => ({ data: {
    waterAmount: body.items[0].quantity * 20, totalAmount: body.items[0].quantity * 20, extraDeposit: 0, barrelPurchases: [],
    blocked: false, allowOfflinePayment: true, methods: [{ id: 2, name: '货到付款', enabled: true }], defaultMethod: 2 } }) } }
  function createPage() {
    const page = loadPage('miniapp-user/pages/order/create.js', { wx, app, stubs })
    page.setData({ stationId: 1, address: { id: 11 }, products: [{ id: 5, name: 'Synthetic', quantity: 1, price: 20, category: 2 }],
      selectedMethod: 2, loading: false })
    return page
  }
  const page = createPage()
  return { page, createPage, app, wx, stubs, calls, order, legacy, at,
    module: () => require('../../miniapp-user/utils/orderIntent'), session: () => require('../../miniapp-user/utils/token').captureSession() }
}
async function restore(t) { t.page._restorePendingOrderFromStorage(); t.page._restoreOrderRequest(); await flush() }
async function submit(t, quantity = 2) {
  t.page.setData({ products: [{ ...t.page.data.products[0], quantity }] }); await t.page.refreshQuote(); await t.page._createOrder(false)
}

for (const age of [5 * 60 * 1000, 10 * 60 * 1000, 11 * 60 * 1000, 24 * 60 * 60 * 1000]) {
  test('legacy successful receipt age=' + age + ': retain fact, exact GET confirms original, new basket gets fresh key', async () => {
    const t = fixture({ age }); await restore(t)
    assert.equal(t.page.data.lastSubmittedOrder.orderId, 205, 'UI TTL must not discard the known receipt')
    const record = t.module().getRequest(t.session())
    assert.equal(record.phase, 'known'); assert.equal(record.orderId, 205); assert.equal(record.body, null, 'no fabricated original body')
    await submit(t); assert.equal(t.calls.creates.length, 1); assert.notEqual(t.calls.creates[0].idempotencyKey, key)
  })
}
test('fresh same-cart success navigates to original without another POST', async () => {
  const t = fixture({ age: 5 * 60 * 1000 }); await restore(t); await submit(t, 1); assert.equal(t.calls.creates.length, 0)
})
test('expired same-cart success is a real new request after exact original confirmation', async () => {
  const t = fixture(); await restore(t); await submit(t, 1); assert.equal(t.calls.creates.length, 1); assert.notEqual(t.calls.creates[0].idempotencyKey, key)
})
test('cold startup after known-only migration still creates a fresh request without reconstructing the legacy body', async () => {
  const t = fixture(); await restore(t); t.page.onUnload()
  for (const file of Object.keys(require.cache)) if (file.includes(path.sep + 'miniapp-user' + path.sep)) delete require.cache[file]
  t.page = t.createPage(); await submit(t); assert.equal(t.calls.creates.length, 1); assert.notEqual(t.calls.creates[0].idempotencyKey, key)
})
test('loadReorder 205 confirms the old exact key and permits explicitly changed products', async () => {
  const t = fixture({ receipt: false }); await t.page.loadReorder(205); await flush()
  assert.equal(t.module().getRequest(t.session()).orderId, 205)
  await submit(t); assert.equal(t.calls.creates.length, 1); assert.notEqual(t.calls.creates[0].idempotencyKey, key)
})
for (const source of ['list', 'detail', 'success']) test('real ' + source + ' Page read reconciles own exact legacy key', async () => {
  const t = fixture({ receipt: false })
  const page = loadPage('miniapp-user/pages/order/' + source + '.js', { wx: t.wx, app: t.app, stubs: t.stubs })
  if (source === 'list') await page.loadOrders()
  else if (source === 'detail') await page.loadOrder(205)
  else await page.loadOrderStatus(205)
  await flush(); assert.equal(t.module().getRequest(t.session()).orderId, 205)
  t.page = t.createPage(); await submit(t); assert.equal(t.calls.creates.length, 1)
})
test('returning to the same checkout clears stale unknown copy only after a positive own-order read', async () => {
  const t = fixture({ receipt: false }); await restore(t); assert.equal(t.page.data.submitState, 'unknown')
  await require('../../miniapp-user/api/order').getOrders({ page: 1 })
  await t.page.refreshQuote(); assert.equal(t.page.data.submitState, 'idle'); assert.equal(t.page.data.unknownResultText, '')
  await submit(t); assert.equal(t.calls.creates.length, 1)
})
test('first page miss and empty GET do not release key-only unknown; later positive page may reconcile', async () => {
  const pages = { 1: [{ id: 999, customerId: 7, idempotencyKey: 'other-key' }], 2: [] }
  const t = fixture({ receipt: false, pages })
  const api = require('../../miniapp-user/api/order')
  await api.getOrders({ page: 1 }); await api.getOrders({ page: 2 }); await submit(t)
  assert.equal(t.calls.creates.length, 0); assert.equal(t.wx.getStorageSync('orderIntent.v1').key, key)
  pages[2] = [t.order]; await api.getOrders({ page: 2 })
  assert.equal(t.module().getRequest(t.session()).orderId, 205)
  await submit(t); assert.equal(t.calls.creates.length, 1)
})
for (const kind of ['missing', 'wrong-key', 'wrong-owner', 'failure']) test('receipt detail ' + kind + ' cannot infer existence of this own original', async () => {
  const t = fixture({ detail: async (_, order) => {
    if (kind === 'failure') throw Error('synthetic read failure')
    if (kind === 'missing') return { data: null }
    return { data: { ...order, ...(kind === 'wrong-key' ? { idempotencyKey: 'other-key' } : { customerId: 8 }) } }
  } }); await restore(t); await submit(t)
  assert.equal(t.calls.creates.length, 0); assert.equal(t.wx.getStorageSync('orderIntent.v1').key, key)
})
test('expired raw receipt without matching key proof stays protected', async () => {
  const t = fixture({ detail: async (_, order) => ({ data: { ...order, idempotencyKey: 'different-current-intent' } }) })
  await restore(t); await submit(t); assert.equal(t.calls.creates.length, 0)
})
test('same-customer relogin rejects the old detail callback; explicit fresh read can reconcile', async () => {
  let release
  const pending = new Promise(resolve => { release = resolve })
  const t = fixture({ receipt: false, detail: () => pending })
  const api = require('../../miniapp-user/api/order'), first = api.getOrderDetail(205)
  require('../../miniapp-user/utils/token').beginSession(); release({ data: t.order }); await first
  assert.throws(() => t.module().getRequest(t.session()))
  assert.equal(t.module().reconcileKnownOrder(t.order, t.session()), true)
  await submit(t); assert.equal(t.calls.creates.length, 1)
})
test('other identity cannot adopt the legacy record even with the same key string', async () => {
  const t = fixture({ receipt: false }); t.app.globalData.customerId = 8
  const token = require('../../miniapp-user/utils/token'); token.beginSession()
  assert.equal(t.module().reconcileKnownOrder(t.order, t.session()), false)
  assert.equal(t.module().reconcileKnownOrder({ ...t.order, customerId: 8 }, t.session()), false)
  assert.equal(t.wx.getStorageSync('orderIntent.v1').customerId, 7)
})
test('known GET fact cannot be rolled back by a later unconfirmed write response', async () => {
  const t = fixture({ receipt: false }); assert.equal(t.module().reconcileKnownOrder(t.order, t.session()), true)
  const record = t.module().getRequest(t.session())
  t.module().settleRequest(record, 'prepared'); assert.equal(record.phase, 'known')
  t.module().settleRequest(record, 'known', 999); assert.equal(record.orderId, 205)
})

;(async () => {
  let failed = 0
  for (const item of tests) {
    try { await item.run(); console.log('PASS ' + item.name) }
    catch (error) { failed++; console.error('FAIL ' + item.name + ': ' + error.message) }
  }
  console.log('UPGRADE_RESULT ' + JSON.stringify({ total: tests.length, passed: tests.length - failed, failed }))
  if (failed) process.exitCode = 1; else console.log('AQUAFLOW_SUITE_OK ' + tests.length)
})().catch(error => { console.error(error); process.exitCode = 1 }).finally(done)
