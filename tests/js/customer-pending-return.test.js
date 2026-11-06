const assert = require('assert')
const { loadPage, createWx, createApp, armWatchdog } = require('./harness')
const done = armWatchdog(), tests = [], test = (name, run) => tests.push({ name, run })
const ok = data => ({ code: 0, data })
const pending = () => { let resolve; const promise = new Promise(r => { resolve = r }); return { resolve, promise } }
const event = id => ({ currentTarget: { dataset: { id } } })
function fixture(read) {
  const wx = createWx(), app = createApp()
  const page = loadPage('miniapp-user/pages/home/index.js', { wx, app, stubs: {
    'api/barrel': { getPendingReturnConfirmations: read }
  } })
  page._homeHidden = false; page._homeDestroyed = false
  return { wx, app, page }
}
test('lists each station and opens the selected original application', async () => {
  const t = fixture(async () => ok([{ recordId: 4, stationId: 8, stationName: '站甲' }, { recordId: 5, stationId: 9, stationName: '站乙' }]))
  await t.page.checkPendingReturns(); assert.equal(t.page.data.pendingReturns.length, 2)
  t.page.onPendingReturnTap(event(5))
  assert.equal(t.wx.__calls.nav[0].url, '/pages/barrel/index?stationId=9&returnId=5')
  t.page.onPendingReturnTap(event(99)); assert.equal(t.wx.__calls.nav.length, 1)
})
test('failed or malformed reads never retain an actionable stale reminder', async () => {
  let count = 0; const t = fixture(async () => ++count === 1 ? ok([{ recordId: 4, stationId: 8 }]) : ok([{ recordId: 'bad', stationId: 8 }]))
  await t.page.checkPendingReturns(); await t.page.checkPendingReturns()
  assert.equal(t.page.data.pendingReturnsError, true); assert.deepEqual(t.page.data.pendingReturns, [])
  t.page.onPendingReturnTap(event(4)); assert.equal(t.wx.__calls.nav.length, 0)
})
test('later refresh wins and empty is distinct from failure', async () => {
  const slow = pending(); let n = 0; const t = fixture(() => ++n === 1 ? slow.promise : Promise.resolve(ok([])))
  const first = t.page.checkPendingReturns(); await t.page.checkPendingReturns(); slow.resolve(ok([{ recordId: 4, stationId: 8 }])); await first
  assert.deepEqual(t.page.data.pendingReturns, []); assert.equal(t.page.data.pendingReturnsError, false)
})
test('account change and hide invalidate in-flight result and navigation', async () => {
  const slow = pending(); const t = fixture(() => slow.promise); const read = t.page.checkPendingReturns()
  t.app.globalData.customerId = 77; slow.resolve(ok([{ recordId: 4, stationId: 8 }])); await read
  assert.deepEqual(t.page.data.pendingReturns, []); t.page.onPendingReturnTap(event(4)); assert.equal(t.wx.__calls.nav.length, 0)
  const slow2 = pending(); const u = fixture(() => slow2.promise); const read2 = u.page.checkPendingReturns()
  u.page.onHide(); slow2.resolve(ok([{ recordId: 4, stationId: 8 }])); await read2
  assert.deepEqual(u.page.data.pendingReturns, [])
})
test('direct arrival opens the right record after the fresh station read, not a confirmation dialog', async () => {
  const wx = createWx(), app = createApp(), scroll = []; wx.pageScrollTo = o => scroll.push(o.selector)
  wx.__storage.set('selectedStation', { id: 99, name: '下单站' })
  delete require.cache[require.resolve('../../miniapp-user/utils/asset-view-station')]
  const record = { id: 4, type: 2, stationId: 8, returnDetail: { status: 'APPROVED', customerConfirmationRequired: true, customerConfirmationCurrent: false } }
  const page = loadPage('miniapp-user/pages/barrel/index.js', { wx, app, stubs: {
    'api/asset-stations': { getAssetStation: async id => ok({ id, name: '资产站' }), listAssetStations: async () => ok({ stations: [], hasMore: false }) },
    'api/barrel': { getBarrelSummary: async () => ok({ independentRights: true, rightBuckets: 1, availableRights: 1, owedBuckets: 0, depositBalance: 30 }),
      getBarrelRecords: async () => ok([record]), getBarrelSummaryByType: async () => ok([]) }
  } })
  page.onLoad({ stationId: '8', returnId: '4' }); await page.onShow()
  assert.equal(page.data.viewStationId, 8); assert.equal(page.data.showAllRecords, true)
  assert.deepEqual(scroll, ['#return-record-4']); assert.equal(wx.__calls.modal.length, 0)
  assert.equal(wx.__storage.get('selectedStation').id, 99)
})
;(async () => { try { for (const t of tests) { await t.run(); console.log('PASS ' + t.name) }; console.log('AQUAFLOW_SUITE_OK ' + tests.length) } catch (e) { console.error(e); process.exitCode = 1 } finally { done() } })()
