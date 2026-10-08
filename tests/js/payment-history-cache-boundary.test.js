const assert = require('assert')
const path = require('path')
const { loadPage, createWx, createApp, armWatchdog, ROOT } = require('./harness')
const done = armWatchdog(30000)
const event = (field, value) => ({ currentTarget: { dataset: { field, id: value, key: value } }, detail: { value } })
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
const rows = (customer, count = 1) => Array.from({ length: count }, (_, i) => ({ id: customer * 1000 + i, ticketQty: 10, amount: 80, createTime: '2026-10-06 12:00:00' }))
function fixture(get) {
  delete require.cache[path.join(ROOT, 'miniapp-delivery/utils/station-history-customer.js')]
  const app = createApp({ globalData: { isLogin: true, userInfo: { staffId: 11, role: 'STATION_MANAGER', stationId: 1 } } })
  let writes = 0
  const request = { get }
  const page = loadPage('miniapp-delivery/pages/station-mgmt/payments/index.js', { app, wx: createWx(), stubs: { 'utils/request': request, './request': request, 'api/station-mgmt': { getPendingPayments: async () => ({ data: [] }), confirmPayment: async () => { writes++ } } } })
  page.onLoad({ view: 'ticketHistory', customerId: '7' })
  return { page, app, writes: () => writes }
}
function select(page, id) { page.setData({ customerResults: [{ id, name: 'synthetic #' + id }] }); return page.onSelectHistoryCustomer(event('', id)) }
function filter(page) { page.onPaymentHistoryDate(event('startDate', '2026-10-06')); page.onClearPaymentDates(); page.onHistoryMore() }
let passed = 0, failed = 0
async function test(name, fn) { try { await fn(); passed++; console.log('PASS ' + name) } catch (e) { failed++; console.error('FAIL ' + name + ': ' + e.message) } }
;(async () => {
  await test('failed customer switch cannot re-show previous rows or erase failure through date actions', async () => {
    const { page } = fixture(async url => { if (url.endsWith('/7')) return { data: rows(7) }; throw new Error('synthetic B unavailable') })
    await page.loadData(); await select(page, 8); filter(page)
    assert.equal(page.data.customerId, 8); assert.deepEqual(page.data.list, []); assert.equal(page.data.loadError, 'synthetic B unavailable')
  })
  await test('customer switch clears old pagination and search selection before failed request completes', async () => {
    const pending = deferred(), { page } = fixture(async url => url.endsWith('/7') ? { data: rows(7, 101) } : pending.promise)
    await page.loadData(); page.onHistoryMore(); assert.equal(page.data.historyPage, 2)
    page.setData({ customerKeyword: 'old keyword', customerSearchError: 'old search failure' })
    const switched = select(page, 8)
    const state = { page: page.data.historyPage, more: page.data.historyHasMore, keyword: page.data.customerKeyword, searchError: page.data.customerSearchError }
    pending.reject(new Error('B unavailable')); await switched; page.onHistoryMore()
    assert.deepEqual(state, { page: 1, more: false, keyword: '', searchError: '' }); assert.deepEqual(page.data.list, [])
  })
  await test('late A success cannot restore cache after B failed', async () => {
    const old = deferred(), { page } = fixture(async url => { if (url.endsWith('/7')) return old.promise; throw new Error('B unavailable') })
    const first = page.loadData(); await select(page, 8); old.resolve({ data: rows(7, 70) }); await first; filter(page)
    assert.deepEqual(page.data.list, []); assert.equal(page.data.loadError, 'B unavailable')
  })
  await test('late A failure cannot erase successful B records', async () => {
    const old = deferred(), { page } = fixture(async url => url.endsWith('/7') ? old.promise : { data: rows(8) })
    const first = page.loadData(); await select(page, 8); old.reject(new Error('old A error')); await first; filter(page)
    assert.deepEqual(page.data.list.map(r => r.id), [8000]); assert.equal(page.data.loadError, '')
  })
  await test('same-customer overlapping refresh retains only latest successful result', async () => {
    const old = deferred(); let calls = 0
    const { page } = fixture(async () => ++calls === 1 ? old.promise : { data: rows(8) })
    const first = page.loadData(); await page.loadData(); old.resolve({ data: rows(7) }); await first
    assert.deepEqual(page.data.list.map(r => r.id), [8000])
  })
  await test('hide and return reentry clear cache; failed reload cannot resurrect prior records', async () => {
    let calls = 0; const { page } = fixture(async () => { if (++calls === 1) return { data: rows(7, 60) }; throw new Error('return reload failed') })
    await page.loadData(); if (page.onHide) page.onHide(); await page.onShow(); await Promise.resolve(); filter(page)
    assert.deepEqual(page.data.list, []); assert.equal(page.data.historyHasMore, false); assert.equal(page.data.loadError, 'return reload failed')
  })
  await test('hidden page rejects old in-flight response before returning', async () => {
    const old = deferred(), { page } = fixture(async () => old.promise)
    const pending = page.loadData(); if (page.onHide) page.onHide(); old.resolve({ data: rows(7) }); await pending
    assert.deepEqual(page.data.list, []); assert.equal(page.data.loading, false)
  })
  await test('retry recovers B history and pagination without A rows or collection writes', async () => {
    let b = 0; const { page, writes } = fixture(async url => { if (url.endsWith('/7')) return { data: rows(7) }; if (++b === 1) throw new Error('B failed'); return { data: rows(8, 61) } })
    await page.loadData(); await select(page, 8); page.onPaymentHistoryDate(event('startDate', '2026-10-06'))
    await page.onRetryLoad(); await Promise.resolve(); page.onHistoryMore(); page.onConfirm(event('', 8000))
    assert.equal(page.data.list.length, 61); assert.ok(page.data.list.every(r => r.id >= 8000)); assert.equal(page.data.loadError, ''); assert.equal(writes(), 0)
  })
  await test('station change without new request rejects in-flight results and cached filtering', async () => {
    const old = deferred(), { page, app } = fixture(async () => old.promise)
    const pending = page.loadData(); app.globalData.userInfo.stationId = 2; old.resolve({ data: rows(7) }); await pending; filter(page)
    assert.deepEqual(page.data.list, [])
  })
  await test('new login object for same staff invalidates prior session cache', async () => {
    const { page, app } = fixture(async () => ({ data: rows(7) }))
    await page.loadData(); app.globalData.userInfo = { ...app.globalData.userInfo }; filter(page)
    assert.deepEqual(page.data.list, [])
  })
  await test('pending/history view round-trip cannot resurrect earlier history after failure', async () => {
    let calls = 0; const { page } = fixture(async () => { if (++calls === 1) return { data: rows(7) }; throw new Error('history reload failed') })
    await page.loadData(); await page.onSetView(event('', 'pending')); await page.onSetView(event('', 'ticketHistory')); filter(page)
    assert.deepEqual(page.data.list, []); assert.equal(page.data.loadError, 'history reload failed')
  })
  await test('valid cached date correction works while invalid range hides paging and stale rows', async () => {
    const { page } = fixture(async () => ({ data: rows(7, 60) }))
    await page.loadData(); page.onPaymentHistoryDate(event('startDate', '2026-10-07')); page.onPaymentHistoryDate(event('endDate', '2026-10-06')); page.onHistoryMore()
    assert.deepEqual(page.data.list, []); assert.equal(page.data.historyHasMore, false); assert.ok(page.data.loadError)
    page.onClearPaymentDates(); assert.equal(page.data.list.length, 50); assert.equal(page.data.loadError, ''); page.onHistoryMore(); assert.equal(page.data.list.length, 60)
  })
  console.log(`payment history boundary: ${passed} passed, ${failed} failed`)
  if (failed) process.exitCode = 1; else console.log('AQUAFLOW_SUITE_OK ' + passed)
})().catch(e => { console.error(e); process.exitCode = 1 }).finally(done)
