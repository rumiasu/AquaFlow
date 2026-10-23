// Actual Page lifecycle and read boundaries; synthetic APIs, no HTTP/native claims.
const assert = require('node:assert/strict')
const { loadPage, createApp, createWx, armWatchdog } = require('./harness')
const done = armWatchdog(30000), tests = [], test = (name, run) => tests.push({ name, run })
const ok = data => ({ code: 0, data })
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
const assets = n => ({ customerId: 7, independentRights: true, actualBuckets: n, heldBuckets: n, barrels: [], tickets: [], records: [] })
function fixture(overrides = {}, manager = true) {
  const wx = createWx(), calls = { profile: 0, assets: 0, privileges: 0, writes: 0, stopped: 0 }
  wx.stopPullDownRefresh = () => { calls.stopped++ }
  const app = createApp({ isStationManager: () => manager, globalData: { isLogin: true,
    userInfo: { staffId: 1, stationId: 1, role: manager ? 'STATION_MANAGER' : 'DELIVERY' } } })
  const page = loadPage('miniapp-delivery/pages/station-mgmt/customers/detail/index.js', { app, wx, stubs: {
    'api/station-mgmt': {
      getCustomerProfile: () => { calls.profile++; return overrides.profile ? overrides.profile(calls.profile) : Promise.resolve(ok({ name: 'SYNTH customer' })) },
      getCustomerAssets: () => { calls.assets++; return overrides.assets ? overrides.assets(calls.assets) : Promise.resolve(ok(assets(2))) },
      returnEmptyBuckets: async () => { calls.writes++; return ok({ duplicate: true }) }
    },
    'utils/request': { get: () => { calls.privileges++; return overrides.privileges ? overrides.privileges(calls.privileges) : Promise.resolve(ok({ privileges: [], grantableTypes: [] })) } }
  } })
  return { page, app, wx, calls }
}
test('WB-02 actual initial lifecycle reads once and return from child updates all current data', async () => {
  let held = 2
  const t = fixture({ assets: async () => ok(assets(held)) })
  await t.page.onLoad({ id: 7 }); await t.page.onShow()
  assert.equal(t.calls.assets, 1); assert.equal(t.page.data.assets.actualBuckets, 2)
  t.page.onOpenAdjust(); t.page.onHide(); held = 3; await t.page.onShow()
  assert.equal(t.calls.profile, 2); assert.equal(t.calls.assets, 2); assert.equal(t.calls.privileges, 2)
  assert.equal(t.page.data.assets.actualBuckets, 3)
})
test('initial onShow during pending onLoad never starts duplicate reads', async () => {
  const gate = deferred(), t = fixture({ assets: () => gate.promise })
  const load = t.page.onLoad({ id: 7 }); t.page.onShow()
  assert.equal(t.calls.assets, 1); gate.resolve(ok(assets(2))); await load
})
test('pre-hide response cannot overwrite the fresh asset snapshot after return', async () => {
  const old = deferred(), t = fixture({ assets: n => n === 1 ? old.promise : Promise.resolve(ok(assets(3))) })
  const first = t.page.onLoad({ id: 7 }); t.page.onShow(); t.page.onHide()
  await t.page.onShow(); old.resolve(ok(assets(2))); await first
  assert.equal(t.page.data.assets.actualBuckets, 3)
})
test('the latest same-page asset read owns values and loading even when earlier response arrives first', async () => {
  const first = deferred(), latest = deferred(), t = fixture({ assets: n => n === 1 ? first.promise : latest.promise })
  const load = t.page.onLoad({ id: 7 }); const retry = t.page.loadAssets(7)
  first.resolve(ok(assets(2))); await load
  assert.equal(t.page.data.assetsLoading, true); assert.equal(t.page.data.assets, null)
  latest.resolve(ok(assets(4))); await retry
  assert.equal(t.page.data.assets.actualBuckets, 4); assert.equal(t.page.data.assetsLoading, false)
})
test('profile and privilege callbacks from before hide cannot replace the refreshed customer snapshot', async () => {
  const profile = deferred(), privileges = deferred()
  const t = fixture({ profile: n => n === 1 ? profile.promise : Promise.resolve(ok({ name: 'SYNTH new' })),
    privileges: n => n === 1 ? privileges.promise : Promise.resolve(ok({ privileges: [{ type: 'new' }] })) })
  const initial = t.page.onLoad({ id: 7 }); t.page.onShow(); t.page.onHide(); await t.page.onShow()
  profile.resolve(ok({ name: 'SYNTH old' })); privileges.resolve(ok({ privileges: [{ type: 'old' }] })); await initial
  assert.equal(t.page.data.profile.name, 'SYNTH new'); assert.equal(t.page.data.privileges[0].type, 'new')
})
for (const [field, value] of [['staffId', 2], ['stationId', 2], ['role', 'DELIVERY']]) {
  test('identity change ' + field + ' clears old data and refuses retargeting the existing page', async () => {
    const t = fixture(); await t.page.onLoad({ id: 7 }); t.page.onShow(); t.page.onHide()
    t.app.globalData.userInfo[field] = value; await t.page.onShow()
    assert.equal(t.page.data.profile, null); assert.equal(t.page.data.assets, null)
    assert.equal(t.page.data.canViewAssets, false); assert(t.page.data.error.includes('重新进入'))
    assert.equal(t.calls.profile, 1); assert.equal(t.calls.assets, 1)
  })
}
test('same staff new login generation cannot repaint from a pending old asset read', async () => {
  const old = deferred(), t = fixture({ assets: () => old.promise })
  const load = t.page.onLoad({ id: 7 }); t.page.onShow(); t.app._loginGeneration = 1
  old.resolve(ok(assets(9))); await load; t.page.onShow()
  assert.equal(t.page.data.assets, null); assert(t.page.data.error.includes('重新进入'))
})
test('courier profile returns refresh only the permitted profile read', async () => {
  const t = fixture({}, false); await t.page.onLoad({ id: 7 }); t.page.onShow(); t.page.onHide(); await t.page.onShow()
  assert.equal(t.calls.profile, 2); assert.equal(t.calls.assets, 0); assert.equal(t.calls.privileges, 0)
  assert.equal(t.page.data.returnRecoveryError, '')
})
test('pull-to-refresh shares the current refresh entry and always stops its indicator', async () => {
  const t = fixture(); await t.page.onLoad({ id: 7 }); t.page.onShow(); await t.page.onPullDownRefresh()
  assert.equal(t.calls.assets, 2); assert.equal(t.calls.privileges, 2); assert.equal(t.calls.stopped, 1)
})
test('new return draft waits for a current asset read while original pending intent remains recoverable', async () => {
  const t = fixture(); t.page.setData({ id: 7, canViewAssets: true,
    assets: { barrels: [{ productId: 5, maxReturnableQty: 4, overQty: 1 }] } })
  t.page.onShowReturnEmpty(); t.page.setData({ assetsLoading: true })
  await t.page.onSubmitReturnEmpty(); assert.equal(t.calls.writes, 0)
  assert(t.page.data.returnMessage.includes('核对客户资产'))
})
test('unload invalidates outstanding asset data', async () => {
  const gate = deferred(), t = fixture({ assets: () => gate.promise })
  const load = t.page.onLoad({ id: 7 }); t.page.onUnload(); gate.resolve(ok(assets(9))); await load
  assert.equal(t.page.data.assets, null)
})
;(async () => {
  let failed = 0
  for (const t of tests) { try { await t.run(); console.log('PASS ' + t.name) } catch (e) { failed++; console.error('FAIL ' + t.name + ': ' + e.stack) } }
  done(); if (failed) process.exitCode = 1; else console.log('AQUAFLOW_SUITE_OK ' + tests.length)
})().catch(e => { done(); console.error(e); process.exitCode = 1 })
