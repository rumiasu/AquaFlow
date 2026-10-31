const assert = require('assert')
const { loadPage, createWx, createApp, armWatchdog } = require('./harness')
const { beginSession } = require('../../miniapp-user/utils/token')
const done = armWatchdog(), tests = [], test = (name, run) => tests.push({ name, run })
const deferred = () => { let resolve, reject; const promise = new Promise((ok, fail) => { resolve = ok; reject = fail }); return { promise, resolve, reject } }
const station = id => ({ id, name: 'SYNTH station ' + id, status: 1 })
const product = id => ({ id, name: 'SYNTH product ' + id, effectivePrice: id })
const response = data => ({ code: 0, data })
const event = id => ({ currentTarget: { dataset: { id } } })
function fixture(api = {}) {
  const wx = createWx(), writes = [], reads = []
  wx.setStorageSync('selectedStation', station(1)); wx.setStorageSync('stationSwitchNoticeDisabled', true)
  const app = createApp({ getCurrentStationId: () => wx.getStorageSync('selectedStation').id,
    getCartCount: () => 0, addToCart: (stationId, productId, quantity) => writes.push({ owner: app.globalData.customerId, stationId, productId, quantity }) })
  const page = loadPage('miniapp-user/pages/shop/index.js', { wx, app, stubs: {
    'api/product': { getStationProducts: id => { reads.push(id); return api.products ? api.products(id) : Promise.resolve(response([product(id)])) } },
    'api/station': { getPublicStations: () => api.list ? api.list() : Promise.resolve(response([station(1), station(2), station(3)])),
      getStationStatus: id => api.status ? api.status(id) : Promise.resolve(response({ customerHint: 'SYNTH hint ' + id })) }
  } })
  page.setData({ isLogin: true, currentStationId: 1, currentStation: station(1), stationList: [station(1), station(2), station(3)] })
  return { page, app, wx, writes, reads }
}

test('late initial station A products cannot replace chosen B or enter B cart', async () => {
  const old = deferred(), t = fixture({ products: id => id === 1 ? old.promise : Promise.resolve(response([product(id)])) })
  const pending = t.page.loadData(); await t.page.onSelectStation(event(2))
  old.resolve(response([product(1)])); await pending
  assert.equal(t.page.data.currentStationId, 2); assert.deepStrictEqual(t.page.data.allProducts.map(p => p.id), [2])
  t.page.onAddToCart(event(2)); assert.deepStrictEqual(t.writes, [{ owner: 7, stationId: 2, productId: 2, quantity: 1 }])
})
test('latest same-station product refresh wins', async () => {
  const old = deferred(); let calls = 0
  const t = fixture({ products: () => ++calls === 1 ? old.promise : Promise.resolve(response([product(12)])) })
  const pending = t.page.loadData(); await t.page.loadData(); old.resolve(response([product(11)])); await pending
  assert.deepStrictEqual(t.page.data.allProducts.map(p => p.id), [12])
})
test('old failure cannot erase B, raise stale error or toast after B succeeds', async () => {
  const old = deferred(), t = fixture({ products: id => id === 1 ? old.promise : Promise.resolve(response([product(id)])) })
  const pending = t.page.loadData(); await t.page.onSelectStation(event(2))
  old.reject(new Error('SYNTH old failure')); await pending
  assert.equal(t.page.data.currentStationId, 2); assert.equal(t.page.data.loadError, ''); assert.equal(t.wx.__calls.toast.length, 0)
})
test('late selection B response cannot overwrite later selection C', async () => {
  const old = deferred(), t = fixture({ products: id => id === 2 ? old.promise : Promise.resolve(response([product(id)])) })
  await t.page.loadData(); const pending = t.page.onSelectStation(event(2)); await t.page.onSelectStation(event(3))
  old.resolve(response([product(2)])); await pending
  assert.equal(t.wx.getStorageSync('selectedStation').id, 3); assert.deepStrictEqual(t.page.data.allProducts.map(p => p.id), [3])
})
test('old finally cannot clear the current product loading indicator', async () => {
  const old = deferred(), current = deferred(); let calls = 0
  const t = fixture({ products: () => ++calls === 1 ? old.promise : current.promise })
  const a = t.page.loadData(), b = t.page.loadData(); old.resolve(response([product(11)])); await a
  assert.equal(t.page.data.loading, true)
  current.resolve(response([product(12)])); await b; assert.equal(t.page.data.loading, false)
})
for (const change of ['customer', 'same-customer-login']) test(change + ' isolates an earlier product request', async () => {
  const old = deferred(); let calls = 0
  const t = fixture({ products: () => ++calls === 1 ? old.promise : Promise.resolve(response([product(12)])) })
  const pending = t.page.loadData()
  if (change === 'customer') t.app.globalData.customerId = 8; else beginSession()
  await t.page.loadData()
  if (change === 'customer') old.reject(new Error('SYNTH original session rejected')); else old.resolve(response([product(11)]))
  await pending
  assert.deepStrictEqual(t.page.data.allProducts.map(p => p.id), [12])
})
for (const lifecycle of ['onHide', 'onUnload']) test(lifecycle + ' prevents late products and loading writes', async () => {
  const old = deferred(), t = fixture({ products: () => old.promise }); const pending = t.page.loadData()
  if (t.page[lifecycle]) t.page[lifecycle]()
  const snapshot = JSON.stringify(t.page.data); old.resolve(response([product(11)])); await pending
  assert.equal(JSON.stringify(t.page.data), snapshot)
})
test('returning onShow loads the current station again', async () => {
  const t = fixture(); await t.page.onShow(); if (t.page.onHide) t.page.onHide()
  t.wx.setStorageSync('selectedStation', station(2)); await t.page.onShow()
  assert.equal(t.page.data.currentStationId, 2); assert.deepStrictEqual(t.page.data.allProducts.map(p => p.id), [2])
})
test('old station status cannot replace the current station hint', async () => {
  const old = deferred(), t = fixture({ status: id => id === 1 ? old.promise : Promise.resolve(response({ customerHint: 'SYNTH B' })) })
  const pending = t.page.loadStationStatus(1); t.wx.setStorageSync('selectedStation', station(2)); await t.page.loadData()
  old.resolve(response({ customerHint: 'SYNTH A' })); await pending
  assert.equal(t.page.data.stationStatusHint, 'SYNTH B')
})
test('latest same-station status request wins', async () => {
  const old = deferred(); let calls = 0
  const t = fixture({ status: () => ++calls === 1 ? old.promise : Promise.resolve(response({ customerHint: 'SYNTH fresh' })) })
  const pending = t.page.loadStationStatus(1); await t.page.loadStationStatus(1)
  old.resolve(response({ customerHint: 'SYNTH stale' })); await pending; assert.equal(t.page.data.stationStatusHint, 'SYNTH fresh')
})
for (const failure of [false, true]) test('latest station list survives old ' + (failure ? 'failure' : 'success'), async () => {
  const old = deferred(); let calls = 0
  const t = fixture({ list: () => ++calls === 1 ? old.promise : Promise.resolve(response([station(3)])) })
  const pending = t.page.loadStationList(); await t.page.loadStationList()
  if (failure) old.reject(new Error('SYNTH old list')); else old.resolve(response([station(2)]))
  await pending; assert.deepStrictEqual(t.page.data.stationList.map(s => s.id), [3]); assert.equal(t.page.data.stationListError, '')
  assert.equal(t.wx.__calls.toast.length, 0)
})
test('failed public refresh clears stale selectable stations and forged selection does not persist', async () => {
  const t = fixture({ list: async () => { throw new Error('SYNTH unavailable') } })
  await t.page.loadStationList(); assert.deepStrictEqual(t.page.data.stationList, [])
  await t.page.onSelectStation(event(99)); assert.equal(t.wx.getStorageSync('selectedStation').id, 1)
})
for (const change of ['customer', 'hide', 'refresh']) test('pending switch dialog cannot persist after ' + change, async () => {
  const t = fixture(); await t.page.loadData(); t.wx.setStorageSync('stationSwitchNoticeDisabled', false)
  const modals = []; t.wx.showModal = options => { modals.push(options); if (modals.length > 1) options.success({ confirm: true }) }
  const pending = t.page.onSelectStation(event(2))
  if (change === 'customer') t.app.globalData.customerId = 8
  if (change === 'hide' && t.page.onHide) t.page.onHide()
  if (change === 'refresh') await t.page.loadData()
  modals[0].success({ confirm: true }); await pending
  assert.equal(t.wx.getStorageSync('selectedStation').id, 1); assert.equal(modals.length, 1)
  assert.equal(t.wx.getStorageSync('stationSwitchNoticeDisabled'), false)
})
test('remember preference and station cannot persist after identity changes during the single confirmation', async () => {
  const t = fixture(); await t.page.loadData(); t.wx.setStorageSync('stationSwitchNoticeDisabled', false)
  t.page.onSwitchRememberChange({ detail: { value: ['remember'] } })
  const modals = []; t.wx.showModal = options => { modals.push(options) }
  const pending = t.page.onSelectStation(event(2)); t.app.globalData.customerId = 8
  modals[0].success({ confirm: true }); await pending
  assert.equal(modals.length, 1)
  assert.equal(t.wx.getStorageSync('selectedStation').id, 1); assert.equal(t.wx.getStorageSync('stationSwitchNoticeDisabled'), false)
})
for (const change of ['station', 'customer']) test('stale visible products cannot mutate cart after ' + change, async () => {
  const t = fixture(); await t.page.loadData()
  if (change === 'station') t.wx.setStorageSync('selectedStation', station(2)); else t.app.globalData.customerId = 8
  t.page.onAddToCart(event(1)); assert.deepStrictEqual(t.writes, [])
})
for (const action of ['onBuyNow', 'onGoCheckout']) test(action + ' cannot navigate using an obsolete displayed station', async () => {
  const t = fixture(); await t.page.loadData(); t.wx.setStorageSync('selectedStation', station(2))
  t.page[action](event(1)); assert.equal(t.wx.__calls.nav.length, 0)
})
test('valid current product actions preserve station and product route', async () => {
  const t = fixture(); await t.page.loadData(); t.page.onAddToCart(event(1)); t.page.onBuyNow(event(1)); t.page.onGoCheckout()
  assert.equal(t.writes.length, 1); assert(t.wx.__calls.nav[0].url.includes('productId=1&stationId=1'))
  assert(t.wx.__calls.nav[1].url.includes('stationId=1'))
})
for (const action of ['onAddToCart', 'onBuyNow']) test(action + ' rejects a queued card event absent from the current catalog', async () => {
  const t = fixture(); await t.page.loadData(); t.page[action](event(99))
  assert.equal(t.writes.length, 0); assert.equal(t.wx.__calls.nav.length, 0)
})
test('pending switch dialog is invalidated by a refreshed station directory', async () => {
  const t = fixture(); await t.page.loadData(); t.wx.setStorageSync('stationSwitchNoticeDisabled', false)
  const modals = []; t.wx.showModal = options => { modals.push(options); if (modals.length > 1) options.success({ confirm: true }) }
  const pending = t.page.onSelectStation(event(2)); await t.page.loadStationList()
  modals[0].success({ confirm: true }); await pending
  assert.equal(t.wx.getStorageSync('selectedStation').id, 1); assert.equal(modals.length, 1)
})
test('a failed current catalog does not block reviewing the same-owner same-station saved cart', async () => {
  const t = fixture({ products: async () => { throw new Error('SYNTH catalog offline') } })
  await t.page.loadData(); t.page.onGoCheckout()
  assert.equal(t.wx.__calls.nav.length, 1); assert(t.wx.__calls.nav[0].url.includes('stationId=1'))
})
test('selected station immediately determines the restored cart badge', async () => {
  const t = fixture(); t.app.getCartCount = id => id === 1 ? 2 : 7
  await t.page.onShow(); assert.equal(t.page.data.cartCount, 2)
  await t.page.onSelectStation(event(2)); assert.equal(t.page.data.cartCount, 7)
})
test('guest catalog browsing remains available without cart writes', async () => {
  const t = fixture(); t.app.globalData.isLogin = false; await t.page.onShow()
  assert.deepStrictEqual(t.page.data.allProducts.map(p => p.id), [1]); assert.equal(t.page.data.isLogin, false)
  t.page.onAddToCart(event(1)); assert.equal(t.writes.length, 0)
})
test('a successful empty catalog remains a normal empty state', async () => {
  const t = fixture({ products: async () => response([]) }); await t.page.loadData()
  assert.deepStrictEqual(t.page.data.allProducts, []); assert.equal(t.page.data.loadError, '')
})
test('stationless reload clears previous station error and hint without product calls', async () => {
  const t = fixture(); t.wx.removeStorageSync('selectedStation'); t.page.setData({ loadError: 'SYNTH old error', stationStatusHint: 'SYNTH old hint' })
  await t.page.loadData(); assert.equal(t.page.data.loadError, ''); assert.equal(t.page.data.stationStatusHint, ''); assert.equal(t.reads.length, 0)
})
test('malformed successful product data is not exposed as a catalog', async () => {
  const t = fixture({ products: async () => response({ invalid: true }) }); await t.page.loadData()
  assert(Array.isArray(t.page.data.allProducts)); assert(t.page.data.loadError)
})
test('valid switch confirms once and does not infer inactive stations as available', async () => {
  const t = fixture({ list: async () => response([station(2), { ...station(3), status: 0 }]) })
  await t.page.loadData(); await t.page.loadStationList(); assert.deepStrictEqual(t.page.data.stationList.map(s => s.id), [2])
  t.wx.setStorageSync('stationSwitchNoticeDisabled', false); await t.page.onSelectStation(event(2))
  assert.equal(t.wx.__calls.modal.length, 1); assert.equal(t.wx.getStorageSync('selectedStation').id, 2)
})
;(async () => {
  let failed = 0
  for (const t of tests) { try { await t.run(); console.log('PASS ' + t.name) } catch (e) { failed++; console.error('FAIL ' + t.name + ': ' + e.stack) } }
  done(); if (failed) process.exitCode = 1; else console.log('AQUAFLOW_SUITE_OK ' + tests.length)
  console.log('SHOP_CONTEXT_RESULT tests=' + tests.length + ' failed=' + failed)
})().catch(e => { done(); console.error(e); process.exitCode = 1 })
