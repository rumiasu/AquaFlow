const assert = require('assert')
const { loadPage, createWx, createApp, armWatchdog } = require('./harness')
const done = armWatchdog(30000), cases = []
const test = (name, run) => cases.push({ name, run })
const CONFIG = '/api/manager/delivery-config', SETUP = '/api/manager/setup-guide'
const flush = async () => { for (let i = 0; i < 15; i++) await Promise.resolve() }
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
const config = value => ({ code: 0, data: { configured: true, config: { baseDeliveryFee: value, floorFreeLevel: null }, guidance: {} } })
const setup = label => ({ code: 0, data: { summaryText: label, items: [{ key: 'stationPosition', done: false, level: 'P0', why: label }] } })
function fixture(handler = url => Promise.resolve(url === CONFIG ? config(3) : setup('fresh'))) {
  const wx = createWx(), calls = [], puts = [], state = { stops: 0, redirects: 0, allowed: true }
  wx.stopPullDownRefresh = () => state.stops++
  const app = createApp({ _loginGeneration: 1, globalData: { isLogin: true, accessToken: 'synthetic-A', refreshToken: 'synthetic-R',
    userInfo: { staffId: 9, role: 'STATION_MANAGER', stationId: 2, bindStatus: 'BOUND' } },
    canAccessStationBusiness: () => state.allowed, routeByRole: () => state.redirects++ })
  global.__wxConfig = { envVersion: 'develop' }
  const page = loadPage('miniapp-delivery/pages/station-mgmt/delivery-config/index.js', { wx, app, stubs: {
    'utils/request': { get: url => { calls.push(url); return handler(url) }, put: async (url, body) => { puts.push({ url, body }); return { code: 0 } } }
  } })
  const writes = [], original = page.setData
  page.setData = (patch, callback) => { writes.push(patch); original.call(page, patch, callback) }
  return { page, wx, app, calls, puts, state, writes }
}
function pendingFixture() {
  const c = deferred(), s = deferred(), t = fixture(url => url === CONFIG ? c.promise : s.promise)
  return { ...t, c, s }
}
function finish(t, value = 4) { t.c.resolve(config(value)); t.s.resolve(setup('fresh')) }
test('native pull refresh has an executable Page handler', () => {
  assert.equal(typeof fixture().page.onPullDownRefresh, 'function')
})
test('pull reloads both configuration and setup once then stops', async () => {
  const t = fixture(); await t.page.onPullDownRefresh()
  assert.deepStrictEqual(t.calls, [CONFIG, SETUP]); assert.equal(t.state.stops, 1)
  assert.equal(t.page.data.form.baseDeliveryFee, '3'); assert.equal(t.page.data.setup.summaryText, 'fresh'); assert.equal(t.page.data.loading, false)
})
test('pull waits for the supplemental setup request before stopping', async () => {
  const gate = deferred(), t = fixture(url => url === SETUP ? gate.promise : Promise.resolve(config(3)))
  const pull = t.page.onPullDownRefresh(); await flush(); assert.equal(t.state.stops, 0)
  gate.resolve(setup('late setup')); await pull; assert.equal(t.state.stops, 1); assert.equal(t.page.data.setup.summaryText, 'late setup')
})
test('configuration failure keeps existing form and always stops loading and pull', async () => {
  const t = fixture(url => url === CONFIG ? Promise.reject(Error('synthetic config offline')) : Promise.resolve(setup('fresh')))
  t.page.setData({ form: { ...t.page.data.form, baseDeliveryFee: '8' } }); await t.page.onPullDownRefresh()
  assert.equal(t.page.data.form.baseDeliveryFee, '8'); assert.equal(t.page.data.loading, false); assert.equal(t.state.stops, 1)
  assert(t.wx.__calls.toast.some(o => o.title === 'synthetic config offline'))
})
test('setup failure retains the existing optional-card failure behavior and stops', async () => {
  const t = fixture(url => url === SETUP ? Promise.reject(Error('synthetic setup offline')) : Promise.resolve(config(5)))
  await t.page.onPullDownRefresh(); assert.equal(t.page.data.form.baseDeliveryFee, '5'); assert.deepStrictEqual(t.page.data.setupPending, [])
  assert.equal(t.state.stops, 1); assert.equal(t.wx.__calls.toast.length, 0)
})
test('both request failures stop exactly once', async () => {
  const t = fixture(() => Promise.reject(Error('synthetic offline'))); await t.page.onPullDownRefresh()
  assert.equal(t.state.stops, 1); assert.equal(t.page.data.loading, false); assert.equal(t.wx.__calls.toast.length, 1)
})
test('reload preserves null versus real zero and existing default modes', async () => {
  const t = fixture(url => Promise.resolve(url === CONFIG ? config(0) : setup('fresh'))); await t.page.onPullDownRefresh()
  assert.equal(t.page.data.form.baseDeliveryFee, '0'); assert.equal(t.page.data.form.floorFreeLevel, '')
  assert.equal(t.page.data.form.minOrderMode, 'WARN'); assert.equal(t.page.data.form.overRadiusMode, 'WARN')
})
test('an unauthorized pull requests no station data and still stops', async () => {
  const t = fixture(); t.state.allowed = false; await t.page.onPullDownRefresh()
  assert.equal(t.calls.length, 0); assert.equal(t.state.redirects, 1); assert.equal(t.state.stops, 1)
})
test('onShow preserves the existing station-business guard', async () => {
  const t = fixture(); t.state.allowed = false; await t.page.onShow()
  assert.equal(t.calls.length, 0); assert.equal(t.state.redirects, 1)
})
test('logout and login prevent both old success callbacks from repainting', async () => {
  const t = pendingFixture(), pull = t.page.onPullDownRefresh(), n = t.writes.length
  t.app._loginGeneration++; t.app.globalData.accessToken = 'synthetic-B'; finish(t); await pull
  assert.equal(t.writes.length, n); assert.equal(t.wx.__calls.toast.length, 0); assert.equal(t.state.stops, 1)
})
test('same staff and same credentials still require the original login generation', async () => {
  const t = pendingFixture(), pull = t.page.onPullDownRefresh(), n = t.writes.length
  t.app._loginGeneration++; finish(t); await pull; assert.equal(t.writes.length, n); assert.equal(t.state.stops, 1)
})
test('station changes discard old data and old error feedback', async () => {
  const t = pendingFixture(), pull = t.page.onPullDownRefresh(), n = t.writes.length
  t.app.globalData.userInfo.stationId = 3; t.c.reject(Error('old station failure')); t.s.resolve(setup('old'))
  await pull; assert.equal(t.writes.length, n); assert.equal(t.wx.__calls.toast.length, 0); assert.equal(t.state.stops, 1)
})
test('role changes discard old callbacks', async () => {
  const t = pendingFixture(), pull = t.page.onPullDownRefresh(), n = t.writes.length
  t.app.globalData.userInfo.role = 'DELIVERY'; finish(t); await pull; assert.equal(t.writes.length, n)
})
test('a different App instance cannot receive old-page data even with identical identity fields', async () => {
  const t = pendingFixture(), pull = t.page.onPullDownRefresh(), n = t.writes.length
  global.getApp = () => ({ ...t.app, globalData: { ...t.app.globalData } }); finish(t); await pull; assert.equal(t.writes.length, n)
})
test('unload stops an active pull immediately and late responses never write or stop again', async () => {
  const t = pendingFixture(), pull = t.page.onPullDownRefresh(); t.page.onUnload(); const n = t.writes.length
  assert.equal(t.state.stops, 1); finish(t); await pull; assert.equal(t.writes.length, n); assert.equal(t.state.stops, 1)
})
test('an older pull finishing first cannot stop the newer pull or overwrite it', async () => {
  const c = [deferred(), deferred()], s = [deferred(), deferred()], count = { [CONFIG]: 0, [SETUP]: 0 }
  const t = fixture(url => (url === CONFIG ? c : s)[count[url]++].promise)
  const first = t.page.onPullDownRefresh(), second = t.page.onPullDownRefresh()
  c[0].resolve(config(1)); s[0].resolve(setup('old')); await first
  assert.equal(t.state.stops, 0); assert.equal(t.page.data.loading, true)
  c[1].resolve(config(2)); s[1].resolve(setup('new')); await second
  assert.equal(t.page.data.form.baseDeliveryFee, '2'); assert.equal(t.page.data.setup.summaryText, 'new'); assert.equal(t.state.stops, 1)
})
test('an older pull finishing last cannot restore stale values or stop twice', async () => {
  const c = [deferred(), deferred()], s = [deferred(), deferred()], count = { [CONFIG]: 0, [SETUP]: 0 }
  const t = fixture(url => (url === CONFIG ? c : s)[count[url]++].promise)
  const first = t.page.onPullDownRefresh(), second = t.page.onPullDownRefresh()
  c[1].resolve(config(9)); s[1].resolve(setup('new')); await second
  c[0].resolve(config(1)); s[0].resolve(setup('old')); await first
  assert.equal(t.page.data.form.baseDeliveryFee, '9'); assert.equal(t.page.data.setup.summaryText, 'new'); assert.equal(t.state.stops, 1)
})
test('onShow loads superseded by pull cannot repaint after the refreshed result', async () => {
  const c = [deferred(), deferred()], s = [deferred(), deferred()], count = { [CONFIG]: 0, [SETUP]: 0 }
  const t = fixture(url => (url === CONFIG ? c : s)[count[url]++].promise)
  const show = t.page.onShow(), pull = t.page.onPullDownRefresh()
  c[1].resolve(config(6)); s[1].resolve(setup('fresh')); await pull
  c[0].resolve(config(1)); s[0].resolve(setup('old')); await show; await flush()
  assert.equal(t.page.data.form.baseDeliveryFee, '6'); assert.equal(t.page.data.setup.summaryText, 'fresh'); assert.equal(t.state.stops, 1)
})
test('existing save payload still distinguishes blank from zero and reloads configuration', async () => {
  const t = fixture(); t.page.setData({ form: { ...t.page.data.form, baseDeliveryFee: '0', floorFreeLevel: '' } })
  await t.page.onSave(); await flush(); assert.equal(t.puts.length, 1); assert.equal(t.puts[0].url, CONFIG)
  assert.equal(t.puts[0].body.baseDeliveryFee, 0); assert.equal(t.puts[0].body.floorFreeLevel, null)
  assert.deepStrictEqual(t.calls, [CONFIG]); assert.equal(t.page.data.saving, false)
})
test('direct loads after unload send no requests and write nothing', async () => {
  const t = fixture(); t.page.onUnload(); const n = t.writes.length
  await t.page.load(); await t.page.loadSetup(); assert.equal(t.calls.length, 0); assert.equal(t.writes.length, n)
})
test('credential rotation within the same login does not discard valid reload data', async () => {
  const t = pendingFixture(), pull = t.page.onPullDownRefresh()
  t.app.globalData.accessToken = 'synthetic-rotated'; t.app.globalData.refreshToken = 'synthetic-R-rotated'
  finish(t, 7); await pull; assert.equal(t.page.data.form.baseDeliveryFee, '7'); assert.equal(t.page.data.loading, false); assert.equal(t.state.stops, 1)
})
async function main() {
  let failed = 0
  for (const entry of cases) { try { await entry.run(); console.log('PASS ' + entry.name) } catch (error) { failed++; console.error('FAIL ' + entry.name + ': ' + error.message) } }
  done(); console.log('delivery config pull refresh: ' + (cases.length-failed) + ' passed, ' + failed + ' failed')
  if (failed) process.exitCode = 1; else console.log('AQUAFLOW_SUITE_OK ' + cases.length)
}
main().catch(error => { done(); console.error(error); process.exitCode = 1 })
