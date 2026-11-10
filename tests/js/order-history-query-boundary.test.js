const assert = require('assert'), path = require('path')
const { loadPage, createWx, createApp, armWatchdog, ROOT } = require('./harness')
const done = armWatchdog(30000)
const event = (field, value) => ({ currentTarget: { dataset: { field, id: value } }, detail: { value } })
const deferred = () => { let resolve, reject; const promise = new Promise((a,b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
const rows = (start, count = 1) => Array.from({ length: count }, (_, i) => ({ id: start + i, status: 1, statusText: '待配送' }))
function fixture(getOrders, getCrossStationOrders = async () => ({ data: { count: 0, orders: [] } })) {
  delete require.cache[path.join(ROOT, 'miniapp-delivery/utils/station-history-customer.js')]
  const app = createApp({ globalData: { userInfo: { staffId: 11, role: 'STATION_MANAGER', stationId: 1 } } }), requests = []
  const page = loadPage('miniapp-delivery/pages/station-mgmt/orders/index.js', { app, wx: createWx(), stubs: { 'api/station-mgmt': { getOrders: async q => { requests.push(q); return getOrders(q) }, getCrossStationOrders }, 'utils/request': { get: async () => ({ data: [] }) } } })
  page.setData({ startDate: '2026-10-01', endDate: '2026-10-06' })
  return { page, app, requests }
}
async function invalid(page) { return page.onHistoryDateChange(event('startDate', '2026-10-07')) }
function emptyInvalid(page) { assert.deepEqual(page.data.list, []); assert.equal(page.data.page, 0); assert.equal(page.data.hasMore, false); assert.equal(page.data.loading, false); assert.equal(page.data.loadingMore, false); assert.equal(page.data.listError, '开始日期不能晚于结束日期') }
let passed = 0, failed = 0
async function test(name, fn) { try { await fn(); passed++; console.log('PASS ' + name) } catch (e) { failed++; console.error('FAIL ' + name + ': ' + e.message) } }
;(async () => {
  await test('valid in-flight success is discarded after invalid date selection with no new request', async () => {
    const old = deferred(), { page, requests } = fixture(async () => old.promise)
    const pending = page.loadData(); await invalid(page); old.resolve({ data: rows(1) }); await pending
    emptyInvalid(page); assert.equal(requests.length, 1)
  })
  await test('late failure cannot overwrite invalid-range explanation', async () => {
    const old = deferred(), { page } = fixture(async () => old.promise)
    const pending = page.loadData(); await invalid(page); old.reject(new Error('old network failure')); await pending; emptyInvalid(page)
  })
  await test('page-two in flight is invalidated and existing rows/pagination cleared', async () => {
    const old = deferred(), { page, requests } = fixture(async q => q.page === 1 ? { data: rows(1,20) } : old.promise)
    await page.loadData(); const more = page.onLoadMore(); await invalid(page); old.resolve({ data: rows(21,20) }); await more; await page.onLoadMore()
    emptyInvalid(page); assert.equal(requests.length, 2)
  })
  await test('in-flight cross-station response is discarded after invalid filter', async () => {
    const old = deferred(), started = deferred(), { page } = fixture(async () => ({ data: rows(1) }), async () => { started.resolve(); return old.promise })
    const pending = page.loadData(); await started.promise; await invalid(page); old.resolve({ data: { count: 1, orders: rows(99) } }); await pending
    emptyInvalid(page); assert.equal(page.data.crossStation, null)
  })
  await test('customer selection while invalid remains empty and cannot receive earlier customer rows', async () => {
    const old = deferred(), { page, requests } = fixture(async () => old.promise)
    page.setData({ customerId: 7 }); const pending = page.loadData(); await invalid(page)
    page.setData({ customerResults: [{ id: 8, name: 'synthetic B' }] }); await page.onSelectHistoryCustomer(event('', 8))
    old.resolve({ data: rows(7000) }); await pending; emptyInvalid(page); assert.equal(page.data.customerId, 8); assert.equal(requests.length,1)
  })
  await test('status switch while invalid invalidates earlier response without fetching', async () => {
    const old = deferred(), { page, requests } = fixture(async () => old.promise)
    const pending = page.loadData(); await invalid(page); await page.onTabChange(event('',4)); old.resolve({ data: rows(1) }); await pending
    emptyInvalid(page); assert.equal(page.data.currentTab,4); assert.equal(requests.length,1)
  })
  await test('correcting range restores latest query and rejects earlier success', async () => {
    const old = deferred(); let calls = 0
    const { page, requests } = fixture(async () => ++calls === 1 ? old.promise : { data: rows(2000) })
    const pending = page.loadData(); await invalid(page); await page.onHistoryDateChange(event('startDate','2026-10-02'))
    old.resolve({ data: rows(1000) }); await pending
    assert.deepEqual(page.data.list.map(r => r.id),[2000]); assert.equal(page.data.listError,''); assert.equal(requests[1].createTimeStart,'2026-10-02')
  })
  await test('latest empty result stays empty when older success finishes', async () => {
    const old = deferred(); let calls = 0
    const { page } = fixture(async () => ++calls === 1 ? old.promise : { data: [] })
    const pending = page.loadData(); await invalid(page); await page.onClearHistoryDates(); old.resolve({ data: rows(1000) }); await pending
    assert.deepEqual(page.data.list,[]); assert.equal(page.data.listError,''); assert.equal(page.data.hasMore,false)
  })
  await test('same-filter retry sequence rejects late original failure', async () => {
    const old = deferred(); let calls = 0
    const { page } = fixture(async () => ++calls === 1 ? old.promise : { data: rows(2000) })
    const pending = page.loadData(); await page.loadData(); old.reject(new Error('old failure')); await pending
    assert.deepEqual(page.data.list.map(r=>r.id),[2000]); assert.equal(page.data.listError,'')
  })
  await test('condition snapshot rejects results even if conditions change without starting request', async () => {
    const old = deferred(), { page } = fixture(async () => old.promise)
    const pending = page.loadData(); page.setData({ customerId: 8, currentTab: 4 }); old.resolve({ data: rows(1000) }); await pending
    assert.deepEqual(page.data.list,[]); assert.equal(page.data.loading,false); assert.equal(page.data.hasMore,false)
  })
  await test('query snapshot rejects response after station identity changes', async () => {
    const old = deferred(), { page, app } = fixture(async () => old.promise)
    const pending = page.loadData(); app.globalData.userInfo.stationId=2; old.resolve({ data: rows(1000) }); await pending
    assert.deepEqual(page.data.list,[]); assert.equal(page.data.loading,false)
  })
  await test('clear dates sends new conditions and latest query still paginates', async () => {
    const { page, requests } = fixture(async q => ({ data: q.page===1 ? rows(1,20) : rows(21) }))
    await invalid(page); await page.onClearHistoryDates(); await page.onLoadMore()
    assert.equal(requests[0].createTimeStart,undefined); assert.equal(requests[0].createTimeEnd,undefined); assert.equal(requests[1].page,2); assert.equal(page.data.list.length,21); assert.equal(page.data.listError,'')
  })
  await test('returning from details retains the loaded range, refreshes it, and continues pagination', async () => {
    const refresh = deferred()
    let restoring = false
    const { page, requests } = fixture(async q => {
      if (restoring) return refresh.promise
      return { data: rows(1, 42).slice((q.page - 1) * q.pageSize, q.page * q.pageSize) }
    })
    page.setData({ customerId: 7, filtersExpanded: true })
    await page.loadData()
    await page.onLoadMore()
    page.onOrderTap(event('', 25))
    page.onHide()
    restoring = true
    const pending = page.onShow()
    assert.equal(page.data.loading, false)
    assert.equal(page.data.list.length, 40)
    assert.equal(page.data.filtersExpanded, true)
    assert.equal(requests.at(-1).customerId, 7)
    assert.equal(requests.at(-1).page, 1)
    assert.equal(requests.at(-1).pageSize, 40)
    refresh.resolve({ data: rows(1, 41).filter(row => row.id !== 25) })
    await pending
    assert.equal(page.data.page, 2)
    assert(!page.data.list.some(row => row.id === 25), 'a changed order must leave the filtered list')
    assert.equal(page.data.list.at(-1).id, 41)
    restoring = false
    await page.onLoadMore()
    assert.equal(requests.at(-1).page, 3)
    assert.equal(requests.at(-1).pageSize, 20)
    assert.equal(page.data.list.at(-1).id, 42)
  })
  await test('restoring more than one server batch preserves the visible range and next page', async () => {
    const all = rows(1, 521)
    const { page, requests } = fixture(async q => ({ data: all.slice((q.page - 1) * q.pageSize, q.page * q.pageSize) }))
    await page.loadData()
    for (let i = 1; i < 26; i++) await page.onLoadMore()
    page.onHide()
    await page.onShow()
    assert.equal(page.data.list.length, 520)
    assert.equal(page.data.page, 26)
    assert.deepEqual(requests.slice(-2).map(q => [q.page, q.pageSize]), [[1, 500], [2, 500]])
    await page.onLoadMore()
    assert.equal(requests.at(-1).page, 27)
    assert.equal(page.data.list.at(-1).id, 521)
  })
  await test('a new login generation never reuses the old order rows on show', async () => {
    const fresh = deferred()
    const { page, app, requests } = fixture(async q => app._loginGeneration === 2
      ? fresh.promise : { data: rows((q.page - 1) * q.pageSize + 1, q.pageSize) })
    app._loginGeneration = 1
    await page.loadData()
    await page.onLoadMore()
    page.onHide()
    app._loginGeneration = 2
    const loading = page.onShow()
    assert.equal(page.data.list.length, 0)
    assert.equal(requests.at(-1).page, 1)
    assert.equal(requests.at(-1).pageSize, 20)
    fresh.resolve({ data: rows(100) })
    await loading
    assert.equal(page.data.list[0].id, 100)
  })
  console.log(`order query boundary: ${passed} passed, ${failed} failed`)
  if (failed) process.exitCode=1; else console.log('AQUAFLOW_SUITE_OK '+passed)
})().catch(e=>{console.error(e);process.exitCode=1}).finally(done)
