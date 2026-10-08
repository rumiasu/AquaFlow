const assert = require('assert')
const { loadPage, createWx, createApp, armWatchdog } = require('./harness')
const done = armWatchdog(30000)
const tests = [], test = (name, run) => tests.push({ name, run })
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
const row = (id, status, createTime = '2026-10-06T10:00:00') => ({ id, status, createTime })
function fixture(history = [], read, detail) {
  const requests = [], details = [], wx = createWx(), app = createApp()
  const server = q => {
    const filtered = q.status ? history.filter(o => o.status === q.status) : history
    const page = q.page || 1, size = q.pageSize || 200
    return { data: filtered.slice((page - 1) * size, page * size) }
  }
  const page = loadPage('miniapp-user/pages/home/index.js', { wx, app, stubs: {
    'api/order': { getOrders: q => { requests.push({ ...q }); return Promise.resolve().then(() => read ? read(q, server) : server(q)) },
      getOrderDetail: id => { details.push(id); return Promise.resolve().then(() => detail ? detail(id) : { data: { items: [{ productId: 5, quantity: 1, productNameSnapshot: 'P' + id }] } }) } },
    'api/address': { getAddresses: async () => ({ data: [] }) },
    'api/barrel': { getBarrelSummary: async () => ({ data: {} }), getBarrelSummaryByType: async () => ({ data: [] }) },
    'api/product': { getStationProducts: async () => ({ data: [] }) },
    'api/station': { getStationStatus: async () => ({ data: {} }) },
    'api/payment': { getQuote: async () => ({ data: { independentRights: false } }) }
  } })
  page.setData({ currentStationId: 1 })
  return { page, app, requests, details }
}
test('older active order survives more than 200 newer completed orders', async () => {
  const history = [...Array.from({ length: 201 }, (_, i) => row(i + 1, 4)), row(999, 2, '2026-09-01T10:00:00')]
  const t = fixture(history); await t.page.loadData()
  assert.equal(t.page.data.activeShip.id, 999); assert.equal(t.page.data.activeOrderCount, 1)
  assert(t.requests.every(q => [1, 2, 3, 4].includes(q.status)))
  assert(t.requests.filter(q => q.status === 3 || q.status === 4).every(q => q.pageSize === 1 && q.page === 1))
})
test('active count drains all active pages instead of stopping at 200', async () => {
  const t = fixture(Array.from({ length: 501 }, (_, i) => row(i + 1, 2))); await t.page.loadData()
  assert.equal(t.page.data.activeOrderCount, 501)
  assert.deepEqual(t.requests.filter(q => q.status === 2).map(q => q.page), [1, 2, 3])
})
test('again order chooses the newer delivered/completed order and keeps the original product preview', async () => {
  const t = fixture([row(10, 1), row(30, 3, '2026-10-05T10:00:00'), row(40, 4, '2026-10-04T10:00:00')])
  await t.page.loadData()
  assert.deepEqual(t.details, [10, 30]); assert.equal(t.page.data.againOrder.items[0].name, 'P30')
  assert.equal(t.page.data.activeOrderCount, 1)
})
test('active later-page failure remains a failed order read rather than a partial successful count', async () => {
  const t = fixture(Array.from({ length: 201 }, (_, i) => row(i + 1, 2)), (q, server) => {
    if (q.status === 2 && q.page === 2) throw new Error('synthetic later-page failure')
    return server(q)
  })
  await t.page.loadData(); assert.ok(t.page.data.loadError.includes('订单')); assert.equal(t.page.data.loading, false)
})
test('older load cannot replace the latest empty overview', async () => {
  const old = deferred(); let reads = 0
  const t = fixture([], q => {
    const pending = q.status === undefined ? ++reads === 1 : ++reads <= 4
    return pending ? old.promise.then(() => ({ data: [row(1, q.status || 2)] })) : { data: [] }
  })
  const initial = t.page.loadData(); await t.page.loadData(); old.resolve(); await initial
  assert.equal(t.page.data.activeShip, null); assert.equal(t.page.data.againOrder, null); assert.equal(t.page.data.loadError, '')
})
test('customer change discards the whole old overview before rendering', async () => {
  const old = deferred(), t = fixture([], q => old.promise.then(() => ({ data: [row(1, q.status || 2)] })))
  const pending = t.page.loadData(); t.app.globalData.customerId = 8
  t.page.setData({ activeShip: { id: 88 } }); old.resolve(); await pending
  assert.equal(t.page.data.activeShip.id, 88)
})
test('station change during detail read cannot paint the previous station card', async () => {
  const old = deferred(), started = deferred()
  const t = fixture([row(7, 2)], null, () => { started.resolve(); return old.promise })
  const pending = t.page.loadData(); await started.promise
  t.page.setData({ currentStationId: 2, activeShip: { id: 88 } })
  old.resolve({ data: { items: [] } }); await pending; assert.equal(t.page.data.activeShip.id, 88)
})
test('repeated full pages stop with a readable failure instead of looping or claiming a count', async () => {
  const repeated = Array.from({ length: 200 }, (_, i) => row(i + 1, 2))
  const t = fixture([], q => ({ data: q.status === undefined || q.status === 2 ? repeated : [] }))
  await t.page.loadData(); assert.ok(t.page.data.loadError.includes('订单')); assert(t.requests.length <= 6)
})
test('unload invalidates the whole pending overview', async () => {
  const old = deferred(), t = fixture([], q => old.promise.then(() => ({ data: [row(1, q.status || 2)] })))
  const pending = t.page.loadData(); t.page.onUnload(); old.resolve(); await pending; assert.equal(t.page.data.activeShip, null)
})
;(async () => {
  let passed = 0, failed = 0
  for (const item of tests) {
    try { await item.run(); passed++; console.log('PASS ' + item.name) }
    catch (e) { failed++; console.error('FAIL ' + item.name + ' [' + e.name + ']') }
  }
  console.log(`customer home order query: ${passed} passed, ${failed} failed`)
  if (failed) process.exitCode = 1; else console.log('AQUAFLOW_SUITE_OK ' + passed)
})().catch(() => { console.error('home order suite interrupted'); process.exitCode = 1 }).finally(done)
