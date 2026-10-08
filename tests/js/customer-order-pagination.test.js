const assert = require('assert')
const { loadPage, createWx, createApp, armWatchdog } = require('./harness')
const done = armWatchdog(30000)
const rows = (start, count = 1, status = 1) => Array.from({ length: count }, (_, i) => ({ id: start + i, status }))
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
function fixture(read) {
  const requests = [], wx = createWx(), app = createApp({ globalData: { isLogin: true, customerId: 7, accessToken: 'synthetic', refreshToken: 'synthetic-refresh' } })
  let stops = 0
  wx.stopPullDownRefresh = () => { stops++ }
  const page = loadPage('miniapp-user/pages/order/list.js', { wx, app, stubs: {
    'api/order': { getOrders: q => { requests.push({ ...q }); return Promise.resolve().then(() => read({ ...q, page: q.page || 1, pageSize: q.pageSize || 200 })) } }
  } })
  return { page, app, requests, wx, get stops() { return stops } }
}
let passed = 0, failed = 0
async function test(name, run) {
  try { await run(); passed++; console.log('PASS ' + name) }
  catch (e) { failed++; console.error('FAIL ' + name + ' [' + e.name + ']') }
}
;(async () => {
  await test('explicit page and page size expose history beyond the previous 200-row cutoff', async () => {
    const history = rows(1, 201), t = fixture(q => ({ data: history.slice((q.page - 1) * q.pageSize, q.page * q.pageSize) }))
    await t.page.loadOrders()
    assert.equal(t.requests[0].page, 1); assert.equal(t.requests[0].pageSize, 20)
    for (let i = 0; i < 10; i++) await t.page.onReachBottom()
    assert.equal(t.page.data.orders.length, 201); assert.equal(t.page.data.orders[200].id, 201)
    assert.equal(t.page.data.hasMore, false); assert.equal(t.requests.length, 11)
  })
  await test('short final page appends without duplicates and stops further reads', async () => {
    const t = fixture(q => ({ data: q.page === 1 ? rows(1, 20) : [rows(20)[0], ...rows(21, 2)] }))
    await t.page.loadOrders(); await t.page.onReachBottom(); await t.page.onReachBottom()
    assert.equal(t.page.data.orders.length, 22); assert.equal(t.requests.length, 2); assert.equal(t.page.data.page, 2)
  })
  await test('repeated bottom events share only one in-flight page', async () => {
    const old = deferred(), t = fixture(q => q.page === 1 ? { data: rows(1, 20) } : old.promise)
    await t.page.loadOrders(); const a = t.page.onReachBottom(), b = t.page.onReachBottom()
    await Promise.resolve(); assert.equal(t.requests.length, 2)
    old.resolve({ data: rows(21) }); await Promise.all([a, b]); assert.equal(t.page.data.orders.length, 21)
  })
  await test('failed next page preserves rows and page number and can retry that same page', async () => {
    let attempts = 0
    const t = fixture(q => q.page === 1 ? { data: rows(1, 20) } : (++attempts === 1 ? Promise.reject(new Error('synthetic failure')) : { data: rows(21) }))
    await t.page.loadOrders(); await t.page.onReachBottom()
    assert.equal(t.page.data.orders.length, 20); assert.equal(t.page.data.page, 1); assert.equal(t.page.data.hasMore, true); assert.ok(t.page.data.loadError)
    await t.page.onReachBottom(); assert.equal(t.page.data.orders.length, 21); assert.equal(t.page.data.loadError, '')
    assert.equal(t.requests[1].page, 2); assert.equal(t.requests[2].page, 2)
  })
  await test('tab change filters on the server and discards the previous page-two success', async () => {
    const old = deferred(), t = fixture(q => q.status === 2 ? { data: rows(500, 1, 2) } : q.page === 1 ? { data: rows(1, 20) } : old.promise)
    await t.page.loadOrders(); const more = t.page.onReachBottom()
    await t.page.onTabChange({ currentTarget: { dataset: { index: 2 } } })
    old.resolve({ data: rows(21) }); await more
    assert.deepEqual(t.page.data.orders.map(o => o.id), [500]); assert.equal(t.requests[2].status, 2); assert.equal(t.requests[2].page, 1)
  })
  await test('same-filter reread ignores an older success', async () => {
    const old = deferred(); let reads = 0
    const t = fixture(() => ++reads === 1 ? old.promise : { data: rows(500) })
    const initial = t.page.loadOrders(); await t.page.loadOrders(); old.resolve({ data: rows(1) }); await initial
    assert.deepEqual(t.page.data.orders.map(o => o.id), [500])
  })
  await test('customer change discards old data and clears pending state', async () => {
    const old = deferred(), t = fixture(() => old.promise), initial = t.page.loadOrders()
    t.app.globalData.customerId = 8; old.resolve({ data: rows(1) }); await initial
    assert.deepEqual(t.page.data.orders, []); assert.equal(t.page.data.loading, false); assert.equal(t.page.data.hasMore, false)
  })
  await test('explicit same-credential relogin also discards old reads', async () => {
    const old = deferred(), t = fixture(() => old.promise), initial = t.page.loadOrders()
    require('../../miniapp-user/utils/token').beginSession(); old.resolve({ data: rows(1) }); await initial
    assert.deepEqual(t.page.data.orders, []); assert.equal(t.page.data.loading, false)
  })
  await test('page unload invalidates pending response', async () => {
    const old = deferred(), t = fixture(() => old.promise), initial = t.page.loadOrders()
    t.page.onUnload(); old.resolve({ data: rows(1) }); await initial; assert.deepEqual(t.page.data.orders, [])
  })
  await test('pull refresh resets to page one and stops on failure', async () => {
    let fail = false
    const t = fixture(q => fail ? Promise.reject(new Error('synthetic failure')) : { data: rows(q.page === 1 ? 1 : 21, q.page === 1 ? 20 : 1) })
    await t.page.loadOrders(); await t.page.onReachBottom(); fail = true; await t.page.onPullDownRefresh()
    assert.equal(t.requests[2].page, 1); assert.equal(t.stops, 1); assert.equal(t.page.data.loading, false); assert.ok(t.page.data.loadError)
  })
  await test('malformed page body cannot become a successful empty history', async () => {
    const t = fixture(() => ({ data: {} })); await t.page.loadOrders()
    assert.ok(t.page.data.loadError); assert.deepEqual(t.page.data.orders, []); assert.equal(t.page.data.hasMore, false)
  })
  await test('late failure cannot replace the latest genuinely empty result', async () => {
    const old = deferred(); let reads = 0
    const t = fixture(() => ++reads === 1 ? old.promise : { data: [] })
    const initial = t.page.loadOrders(); await t.page.loadOrders(); old.reject(new Error('old synthetic failure')); await initial
    assert.deepEqual(t.page.data.orders, []); assert.equal(t.page.data.loadError, ''); assert.equal(t.page.data.hasMore, false)
  })
  console.log(`customer pagination: ${passed} passed, ${failed} failed`)
  if (failed) process.exitCode = 1
  else console.log('AQUAFLOW_SUITE_OK ' + passed)
})().catch(() => { console.error('customer pagination suite interrupted'); process.exitCode = 1 }).finally(done)
