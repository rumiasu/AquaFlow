const assert = require('assert'), path = require('path')
const { loadPage, createWx, armWatchdog, ROOT } = require('./harness')
const { STORAGE_KEYS: K } = require('../../miniapp-user/utils/storage-keys')
const done = armWatchdog(30000), tests = [], test = (name, run) => tests.push({ name, run })
const flush = async () => { for (let i = 0; i < 15; i++) await Promise.resolve() }
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
function clock() {
  let now = 0, next = 0; const tasks = new Map()
  return { setTimeout(fn, delay) { const id = next++; tasks.set(id, { fn, at: now + delay }); return id },
    clearTimeout(id) { tasks.delete(id) }, size: () => tasks.size,
    async advance(ms) { now += ms; for (const [id, task] of [...tasks]) if (task.at <= now) { tasks.delete(id); task.fn() }; await flush() } }
}
const quoted = qty => ({ code: 0, data: { independentRights: true,
  barrelPurchases: [{ productId: 10, quantity: qty, unitPrice: 40, amount: qty * 40 }] } })
function fixture(handler = async body => quoted(body.items[0].quantity)) {
  const wx = createWx(), calls = []; let app
  global.wx = wx; global.getApp = () => app; global.__wxConfig = { envVersion: 'develop' }
  wx.getDeviceInfo = () => ({ platform: 'devtools' }); global.App = config => { app = config }
  const appFile = path.join(ROOT, 'miniapp-user/app.js'); delete require.cache[require.resolve(appFile)]; require(appFile)
  app.setLoginInfo('synthetic-access', 'synthetic-refresh', { nickname: 'synthetic' }); wx.setStorageSync(K.CUSTOMER_ID, 7)
  const products = [{ id: 10, category: 1, price: 20, deposit: 40 }]
  const page = loadPage('miniapp-user/pages/home/index.js', { wx, app, stubs: {
    'api/payment': { getQuote: body => { calls.push(JSON.parse(JSON.stringify(body))); return handler(body) } },
    'api/order': { getOrders: async () => ({ data: [] }), getOrderDetail: async () => ({ data: null }) },
    'api/product': { getStationProducts: async () => ({ data: products }) },
    'api/address': { getAddresses: async () => ({ data: [] }) },
    'api/barrel': { getBarrelSummary: async () => ({ data: {} }), getBarrelSummaryByType: async () => ({ data: [] }) },
    'api/station': { getStationStatus: async () => ({ data: {} }), getMyLatestStation: async () => ({ data: null }) },
    'api/notification': { getUnreadNotifications: async () => ({ data: [] }) }
  } })
  app.setCartQty(1, 10, 1)
  page.setData({ isLogin: true, state: 'ready', loading: false, currentStationId: 1, currentStation: { id: 1, name: 'S1' },
    products, cart: app.getCart(1), address: { id: 12, detail: 'synthetic address' } })
  const press = type => page.onCartQtyChange({ currentTarget: { dataset: { id: 10, type } } })
  return { page, app, wx, calls, press }
}
test('rapid plus/minus saves every quantity immediately but sends one final quote', async timer => {
  const t = fixture(); t.press('add'); t.press('add'); t.press('minus')
  assert.equal(t.page.data.total.count, 2); assert.equal(t.app.getCart(1)[10], 2)
  assert.equal(t.wx.getStorageSync(K.CART_PREFIX + '7').stations['1']['10'], 2)
  assert.equal(t.calls.length, 0); await timer.advance(299); assert.equal(t.calls.length, 0)
  await timer.advance(1); assert.equal(t.calls.length, 1); assert.equal(t.calls[0].items[0].quantity, 2)
})
test('the debounce interval starts again after the last press', async timer => {
  const t = fixture(); t.press('add'); await timer.advance(200); t.press('add')
  await timer.advance(100); assert.equal(t.calls.length, 0); await timer.advance(199); assert.equal(t.calls.length, 0)
  await timer.advance(1); assert.equal(t.calls.length, 1); assert.equal(t.calls[0].items[0].quantity, 3)
})
test('editing immediately invalidates the previously verified deposit and request sequence', async () => {
  const t = fixture(); await t.page.refreshDerived(); assert.equal(t.page.data.total.depositText, '40')
  const seq = t.page._homeQuoteSeq; t.press('add')
  assert.equal(t.page._homeQuote, null); assert.ok(t.page._homeQuoteSeq > seq); assert.equal(t.page.data.total.depositText, '0')
  assert.ok(t.page.data.total.detailText.includes('未核实')); assert.equal(t.page.data.total.count, 2); assert.equal(t.calls.length, 1)
})
test('an old in-flight quote cannot restore a verified price during the delay', async timer => {
  const old = deferred(); let n = 0; const t = fixture(body => ++n === 1 ? old.promise : Promise.resolve(quoted(body.items[0].quantity)))
  const pending = t.page.refreshDerived(); t.press('add'); old.resolve(quoted(1)); await pending
  assert.equal(t.page._homeQuote, null); assert.ok(t.page.data.total.detailText.includes('未核实'))
  await timer.advance(300); assert.equal(t.calls.length, 2); assert.equal(t.page.data.total.depositText, '80')
})
test('removing the final unit cancels the pending quote and deletes the saved row', async timer => {
  const t = fixture(); t.press('add'); t.press('minus'); t.press('minus')
  assert.equal(t.page.data.total.count, 0); assert.equal(timer.size(), 0); assert.deepStrictEqual(t.app.getCart(1), {})
  await timer.advance(300); assert.equal(t.calls.length, 0)
})
test('a fresh explicit load quotes immediately and replaces a scheduled edit quote', async timer => {
  const t = fixture(); t.press('add'); await t.page.refreshDerived()
  assert.equal(t.calls.length, 1); assert.equal(t.calls[0].items[0].quantity, 2); assert.equal(timer.size(), 0)
  await timer.advance(300); assert.equal(t.calls.length, 1)
})
test('station confirmation clears the old timer before changing the saved station', async timer => {
  const t = fixture(); t.page.setData({ stationList: [{ id: 2, name: 'S2' }] }); t.press('add')
  t.page.checkStation = async () => t.page.setData({ currentStationId: 2 })
  await t.page.confirmStation(2); assert.equal(timer.size(), 0); assert.equal(t.page._homeQuote, null)
  await timer.advance(300); assert.equal(t.calls.length, 0)
})
test('a changed station before the timer cannot send the old station intent', async timer => {
  const t = fixture(); t.press('add'); t.page.setData({ currentStationId: 2 }); await timer.advance(300)
  assert.equal(t.calls.length, 0); assert.equal(timer.size(), 0)
})
test('same-customer logout and relogin invalidates the scheduled intent', async timer => {
  const t = fixture(); t.press('add'); require('../../miniapp-user/utils/token').beginSession(); await timer.advance(300)
  assert.equal(t.calls.length, 0); assert.equal(timer.size(), 0)
})
test('changing customer before the timer never quotes the old draft as the new customer', async timer => {
  const t = fixture(); t.press('add'); t.app.globalData.customerId = 8; await timer.advance(300)
  assert.equal(t.calls.length, 0); assert.equal(t.wx.getStorageSync(K.CART_PREFIX + '7').stations['1']['10'], 2)
})
test('logging out before the timer causes no new quote', async timer => {
  const t = fixture(); t.press('add'); t.app.globalData.isLogin = false; await timer.advance(300); assert.equal(t.calls.length, 0)
})
test('page hide cancels the timer and leaves the immediately saved cart intact', async timer => {
  const t = fixture(); t.press('add'); t.page.onHide(); assert.equal(timer.size(), 0)
  await timer.advance(300); assert.equal(t.calls.length, 0); assert.equal(t.app.getCart(1)[10], 2)
})
test('unload cancels even a zero timer id without issuing a request', async timer => {
  const t = fixture(); t.press('add'); t.page.onUnload(); assert.equal(timer.size(), 0)
  await timer.advance(300); assert.equal(t.calls.length, 0)
})
test('a quote already sent before page hide cannot repaint the hidden page', async () => {
  const old = deferred(), t = fixture(() => old.promise), pending = t.page.refreshDerived()
  t.page.onHide(); const frozen = JSON.stringify(t.page.data); old.resolve(quoted(1)); await pending
  assert.equal(JSON.stringify(t.page.data), frozen); assert.equal(t.page._homeQuote, null)
})
test('a quote already sent before unload cannot restore the old price', async () => {
  const old = deferred(), t = fixture(() => old.promise), pending = t.page.refreshDerived()
  t.page.onUnload(); old.resolve(quoted(1)); await pending; assert.equal(t.page._homeQuote, null)
})
test('returning clears the old quote synchronously before restored data arrives', async () => {
  const t = fixture(); await t.page.refreshDerived(); assert.equal(t.page.data.total.depositText, '40')
  let loads = 0; t.page.checkStation = async () => { loads++ }; t.page.checkNotifications = async () => {}
  t.page.onShow(); assert.equal(t.page._homeQuote, null); assert.ok(t.page.data.total.detailText.includes('未核实')); assert.equal(loads, 1)
})
test('address changes invalidate an already issued quote even without another quantity event', async () => {
  const old = deferred(), t = fixture(() => old.promise), pending = t.page.refreshDerived()
  t.page.setData({ address: { id: 13 } }); old.resolve(quoted(1)); await pending
  assert.equal(t.page._homeQuote, null); assert.ok(t.page.data.total.detailText.includes('未核实'))
})
test('checkout cancels the timer and passes current quantities with no cached price fields', async timer => {
  const t = fixture(); await t.page.refreshDerived(); t.press('add'); t.page.onSubmit()
  assert.equal(timer.size(), 0); assert.equal(t.page._homeQuote, null)
  const url = t.wx.__calls.nav.at(-1).url, params = new URLSearchParams(url.split('?')[1])
  assert.deepStrictEqual(JSON.parse(params.get('items')), [{ productId: 10, quantity: 2 }])
  assert.equal(params.get('stationId'), '1'); assert.equal(params.get('addressId'), '12')
  assert.equal(params.get('amount'), null); assert.equal(params.get('quote'), null); assert.equal(params.get('totalAmount'), null)
  await timer.advance(300); assert.equal(t.calls.length, 1); assert.equal(t.app.getCart(1)[10], 2)
})
test('failed debounced quote leaves the current quantity visible and deposit unverified', async timer => {
  const t = fixture(async () => { throw new Error('synthetic offline') }); t.press('add'); await timer.advance(300)
  assert.equal(t.calls.length, 1); assert.equal(t.page._homeQuote, null); assert.equal(t.page.data.total.count, 2)
  assert.ok(t.page.data.total.detailText.includes('未核实'))
})
test('repeated buy-again adds every selection immediately and shares the final quote', async timer => {
  const t = fixture(); t.page.setData({ againOrder: { items: [{ productId: 10, quantity: 2 }] } }); t.page.onAgainTap(); t.page.onAgainTap()
  assert.equal(t.app.getCart(1)[10], 5); assert.equal(t.wx.getStorageSync(K.CART_PREFIX + '7').stations['1']['10'], 5)
  assert.equal(t.calls.length, 0); await timer.advance(300); assert.equal(t.calls.length, 1); assert.equal(t.calls[0].items[0].quantity, 5)
})
;(async () => {
  let passed = 0, failed = 0
  for (const t of tests) {
    const timer = clock(), oldSet = global.setTimeout, oldClear = global.clearTimeout
    global.setTimeout = timer.setTimeout; global.clearTimeout = timer.clearTimeout
    try { await t.run(timer); passed++; console.log('PASS ' + t.name) }
    catch (e) { failed++; console.error('FAIL ' + t.name + ' [' + e.name + ': ' + e.message + ']') }
    finally { global.setTimeout = oldSet; global.clearTimeout = oldClear }
  }
  console.log(`home quote debounce: ${passed} passed, ${failed} failed`)
  if (failed) process.exitCode = 1; else console.log('AQUAFLOW_SUITE_OK ' + passed)
})().catch(() => { console.error('home debounce interrupted'); process.exitCode = 1 }).finally(done)
