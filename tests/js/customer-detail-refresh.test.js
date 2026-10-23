// Actual Page lifecycle and read boundaries; synthetic APIs, no HTTP/native claims.
const assert = require('node:assert/strict')
const fs = require('node:fs'), path = require('node:path')
const { loadPage, createApp, createWx, armWatchdog, ROOT } = require('./harness')
const { parseWxml, renderElements, validateConditionalSiblings } = require('./wxml-tree')
const done = armWatchdog(30000), tests = [], test = (name, run) => tests.push({ name, run })
const ok = data => ({ code: 0, data })
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
const assets = n => ({ customerId: 7, independentRights: true, actualBuckets: n, heldBuckets: n, barrels: [], tickets: [], records: [] })
const tree = parseWxml(fs.readFileSync(path.join(ROOT, 'miniapp-delivery/pages/station-mgmt/customers/detail/index.wxml'), 'utf8'))
const visible = page => renderElements(tree, page.data, { includeText: true })
const text = page => visible(page).map(node => node.text || '').join('\n')
function fixture(overrides = {}, manager = true) {
  const wx = createWx(), calls = { profile: 0, assets: 0, privileges: 0, writes: 0, stopped: 0 }
  wx.stopPullDownRefresh = () => { calls.stopped++ }
  const app = createApp({ isStationManager: () => manager, globalData: { isLogin: true,
    userInfo: { staffId: 1, stationId: 1, role: manager ? 'STATION_MANAGER' : 'DELIVERY' } } })
  const page = loadPage('miniapp-delivery/pages/station-mgmt/customers/detail/index.js', { app, wx, stubs: {
    'api/station-mgmt': {
      getCustomerProfile: () => { calls.profile++; return overrides.profile ? overrides.profile(calls.profile) : Promise.resolve(ok({ name: 'SYNTH customer', favoriteProducts: [], tagsList: [], recentOrders: [] })) },
      getCustomerAssets: () => { calls.assets++; return overrides.assets ? overrides.assets(calls.assets) : Promise.resolve(ok(assets(2))) },
      returnEmptyBuckets: async () => { calls.writes++; return ok({ duplicate: true }) },
      updateOfflinePayment: overrides.cod || (async () => ok({}))
    },
    'utils/request': { get: () => { calls.privileges++; return overrides.privileges ? overrides.privileges(calls.privileges) : Promise.resolve(ok({ privileges: [], grantableTypes: [] })) } }
  } })
  const set = page.setData
  page.setData = (patch, callback) => {
    const plain = {}
    for (const [key, value] of Object.entries(patch)) {
      if (key.startsWith('profile.')) page.data.profile[key.slice('profile.'.length)] = value
      else plain[key] = value
    }
    set(plain, callback)
  }
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
test('WB-03 failed profile still shows verified assets, customer ID and the existing adjustment action', async () => {
  const t = fixture({ profile: async () => { throw Error('资料暂未核实') } })
  await t.page.onLoad({ id: 7 })
  validateConditionalSiblings(tree)
  assert(t.page.data.assets); assert.equal(t.page.data.profile, null)
  assert(text(t.page).includes('本站资产')); assert(text(t.page).includes('客户编号：7'))
  assert(visible(t.page).some(n => n.attrs.bindtap === 'onOpenAdjust'))
  t.page.onOpenAdjust(); assert(t.wx.__calls.nav.at(-1).url.includes('customerId=7'))
})
test('failed assets remain an independent error while verified customer profile stays readable', async () => {
  const t = fixture({ assets: async () => { throw Error('资产未能读取') } }); await t.page.onLoad({ id: 7 })
  assert(text(t.page).includes('SYNTH customer')); assert(text(t.page).includes('资产未能读取'))
  assert(!text(t.page).includes('该客户在本站暂无任何水桶'))
  assert(visible(t.page).some(n => n.attrs.bindtap === 'onRefreshAssets'))
})
test('two independent read failures do not masquerade as an empty asset account', async () => {
  const t = fixture({ profile: async () => { throw Error('资料未能读取') }, assets: async () => { throw Error('资产未能读取') } })
  await t.page.onLoad({ id: 7 })
  assert(text(t.page).includes('资料未能读取')); assert(text(t.page).includes('资产未能读取'))
  assert(!visible(t.page).some(n => n.className === 'asset-empty'))
})
test('courier profile failure cannot reveal manager assets or adjustment controls', async () => {
  const t = fixture({ profile: async () => { throw Error('资料未能读取') } }, false)
  await t.page.onLoad({ id: 7 })
  assert.equal(t.calls.assets, 0); assert(!text(t.page).includes('本站资产'))
  assert(!visible(t.page).some(n => n.attrs.bindtap === 'onOpenAdjust'))
})
test('a pending profile read does not conceal an already verified asset panel', async () => {
  const gate = deferred(), t = fixture({ profile: () => gate.promise })
  const load = t.page.onLoad({ id: 7 }); await new Promise(setImmediate)
  assert.equal(t.page.data.loading, true); assert(t.page.data.assets)
  assert(visible(t.page).some(n => n.className.includes('asset-grid')))
  gate.resolve(ok({ name: 'SYNTH customer' })); await load
})
test('a genuinely empty verified asset account still has its own empty state when profile fails', async () => {
  const t = fixture({ profile: async () => { throw Error('资料暂未核实') }, assets: async () => ok({ ...assets(0), empty: true }) })
  await t.page.onLoad({ id: 7 })
  assert(text(t.page).includes('该客户在本站暂无任何水桶、水票或押金'))
})
test('unload invalidates outstanding asset data', async () => {
  const gate = deferred(), t = fixture({ assets: () => gate.promise })
  const load = t.page.onLoad({ id: 7 }); t.page.onUnload(); gate.resolve(ok(assets(9))); await load
  assert.equal(t.page.data.assets, null)
})
test('return refresh waits for an in-flight COD save rather than fetching an obsolete profile', async () => {
  const saved = deferred(); let enabled = false
  const t = fixture({ cod: () => saved.promise, profile: async () => ok({ name: 'SYNTH', codEnabled: enabled }) })
  await t.page.onLoad({ id: 7 }); t.page.onShow()
  const save = t.page.onToggleCod({ detail: { value: true } }); t.page.onHide(); await t.page.onShow()
  assert.equal(t.calls.profile, 1); assert.equal(t.page.data.saving, true)
  enabled = true; saved.resolve(ok({})); await save; await new Promise(setImmediate)
  assert.equal(t.calls.profile, 2); assert.equal(t.page.data.profile.codEnabled, true)
})
test('a hidden COD success releases the save lock and returning reads the confirmed profile', async () => {
  const saved = deferred(); let enabled = false
  const t = fixture({ cod: () => saved.promise, profile: async () => ok({ name: 'SYNTH', codEnabled: enabled }) })
  await t.page.onLoad({ id: 7 }); t.page.onShow()
  const save = t.page.onToggleCod({ detail: { value: true } }); t.page.onHide()
  enabled = true; saved.resolve(ok({})); await save
  assert.equal(t.page.data.saving, false); await t.page.onShow()
  assert.equal(t.page.data.profile.codEnabled, true)
})
test('a COD result from the previous owner cannot recreate the cleared old profile', async () => {
  const saved = deferred(), t = fixture({ cod: () => saved.promise })
  await t.page.onLoad({ id: 7 }); t.page.onShow()
  const save = t.page.onToggleCod({ detail: { value: true } }); t.app.globalData.userInfo.stationId = 2
  t.page.onShow(); assert.equal(t.page.data.profile, null)
  saved.resolve(ok({})); await save; assert.equal(t.page.data.profile, null)
})
;(async () => {
  let failed = 0
  for (const t of tests) { try { await t.run(); console.log('PASS ' + t.name) } catch (e) { failed++; console.error('FAIL ' + t.name + ': ' + e.stack) } }
  done(); if (failed) process.exitCode = 1; else console.log('AQUAFLOW_SUITE_OK ' + tests.length)
})().catch(e => { done(); console.error(e); process.exitCode = 1 })
