const assert = require('assert'), fs = require('fs'), path = require('path')
const { loadPage, createWx, createApp, armWatchdog, ROOT } = require('./harness')
const done = armWatchdog(30000), tests = []
const test = (name, run) => tests.push({ name, run })
const tick = () => new Promise(resolve => setImmediate(resolve))
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
const order = (id = 20) => ({ id, stationId: 1, status: 1, canCancel: true, canRepay: true,
  items: [{ productId: 10, productNameSnapshot: 'water', quantity: 2, barrelItem: true }], totalAmount: 12 })
function fixture(api = {}) {
  const wx = createWx(), app = createApp(), calls = { orders: [], images: [], stops: 0, previews: [] }
  wx.stopPullDownRefresh = () => { calls.stops++ }
  wx.previewImage = x => { calls.previews.push(x) }
  const page = loadPage('miniapp-user/pages/order/detail.js', { wx, app, stubs: {
    'api/order': { getOrderDetail: id => { calls.orders.push(id); return api.order ? api.order(id) : Promise.resolve({ data: order(Number(id)) }) } },
    'api/orderImage': { getOrderImages: id => { calls.images.push(id); return api.images ? api.images(id) : Promise.resolve({ data: [] }) } },
    'api/product': { getProductDetail: (id, stationId) => api.product ? api.product(id, stationId) : Promise.resolve({ data: {} }) },
    'api/station': { getStationPublicPhone: id => api.phone ? api.phone(id) : Promise.resolve({ data: {} }) }
  } })
  return { page, wx, app, calls }
}
test('opening a pending detail shows loading and keeps the requested id', async () => {
  const d = deferred(), t = fixture({ order: () => d.promise }), pending = t.page.onLoad({ id: '20' })
  try { assert.equal(t.page.data.detailState, 'loading'); assert.equal(String(t.page._orderId), '20') }
  finally { d.resolve({ data: order() }); await pending; await tick() }
})
test('first failure stays visible and retry fetches the original id', async () => {
  let fail = true; const t = fixture({ order: async () => { if (fail) throw new Error('网络不可用'); return { data: order() } } })
  await t.page.onLoad({ id: '20' }); await tick()
  assert.equal(t.page.data.detailState, 'error'); assert.equal(t.page.data.loadError, '网络不可用'); assert.equal(t.page.data.order, null)
  fail = false; await t.page.onRetry(); assert.equal(t.page.data.detailState, 'ready'); assert.deepStrictEqual(t.calls.orders, ['20', '20'])
})
test('a null success result is not mistaken for an order envelope', async () => {
  const t = fixture({ order: async () => ({ code: 0, data: null }) }); await t.page.loadOrder(20)
  assert.equal(t.page.data.detailState, 'notfound'); assert.equal(t.page.data.order, null); assert.equal(t.page.data.canCancel, false)
})
test('the actual backend order-not-found error has its own state', async () => {
  const t = fixture({ order: async () => { throw new Error('订单不存在') } }); await t.page.loadOrder(20)
  assert.equal(t.page.data.detailState, 'notfound'); assert.equal(t.page.data.loadError, '订单不存在')
})
test('permission denial is an error rather than a nonexistent order', async () => {
  const t = fixture({ order: async () => { throw new Error('无权查看他人订单') } }); await t.page.loadOrder(20)
  assert.equal(t.page.data.detailState, 'error'); assert.equal(t.page.data.loadError, '无权查看他人订单')
})
test('pull refresh recovers after the first load failed without an order object', async () => {
  let fail = true; const t = fixture({ order: async () => { if (fail) throw new Error('offline'); return { data: order() } } })
  await t.page.onLoad({ id: '20' }); await tick(); fail = false; await t.page.onPullDownRefresh(); await tick()
  assert.equal(t.page.data.order.id, 20); assert.equal(t.calls.orders.length, 2); assert.equal(t.calls.stops, 1)
})
test('pull refresh always stops after another request failure', async () => {
  const t = fixture({ order: async () => { throw new Error('offline') } }); await t.page.onLoad({ id: '20' }); await tick()
  await t.page.onPullDownRefresh(); assert.equal(t.calls.stops, 1); assert.equal(t.page.data.detailState, 'error')
})
test('missing route id leaves an explicit state and pull refresh still stops', async () => {
  const t = fixture(); await t.page.onLoad({}); await t.page.onPullDownRefresh()
  assert.equal(t.page.data.detailState, 'notfound'); assert.equal(t.calls.orders.length, 0); assert.equal(t.calls.stops, 1)
})
test('refresh failure clears the previous order and all action permissions', async () => {
  let fail = false; const t = fixture({ order: async () => { if (fail) throw new Error('offline'); return { data: order() } } })
  await t.page.loadOrder(20); fail = true; await t.page.loadOrder(20)
  assert.equal(t.page.data.detailState, 'error'); assert.equal(t.page.data.order, null); assert.deepStrictEqual(t.page.data.items, [])
  assert.equal(t.page.data.canCancel, false); assert.equal(t.page.data.canRepay, false); assert.equal(t.page.data.callPhone, '')
})
test('proof-image failure keeps the order body and offers a local retry', async () => {
  let fail = true; const t = fixture({ images: async () => { if (fail) throw new Error('图片服务不可用'); return { data: [{ id: 1, url: 'proof' }] } } })
  await t.page.loadOrder(20); await tick(); assert.equal(t.page.data.order.id, 20); assert.equal(t.page.data.detailState, 'ready'); assert.equal(t.page.data.imagesState, 'error')
  fail = false; await t.page.onRetryImages(); assert.equal(t.page.data.imagesState, 'ready'); assert.equal(t.page.data.images[0].url, 'proof'); assert.equal(t.calls.orders.length, 1)
})
test('an actual empty proof list is successful, not an image failure', async () => {
  const t = fixture(); await t.page.loadOrder(20); await tick(); assert.equal(t.page.data.imagesState, 'ready'); assert.equal(t.page.data.imagesError, '')
})
test('product-image errors only leave placeholders without losing quantities or fees', async () => {
  const t = fixture({ product: async () => { throw new Error('offline') } }); await t.page.loadOrder(20); await tick()
  assert.equal(t.page.data.detailState, 'ready'); assert.equal(t.page.data.items[0].quantity, 2); assert.equal(t.page.data.items[0].imageUrl, ''); assert.equal(t.page.data.fee.totalText, '12.00')
})
test('a slow old order response cannot replace the new order', async () => {
  const d = deferred(), t = fixture({ order: id => id === 20 ? d.promise : Promise.resolve({ data: order(21) }) })
  const old = t.page.loadOrder(20); await t.page.loadOrder(21); d.resolve({ data: order(20) }); await old; await tick(); assert.equal(t.page.data.order.id, 21)
})
test('an older retry for the same id cannot overwrite the newest result', async () => {
  const d = deferred(); let n = 0; const t = fixture({ order: () => ++n === 1 ? d.promise : Promise.resolve({ data: { ...order(), addressDetail: 'new' } }) })
  const old = t.page.loadOrder(20); await t.page.loadOrder(20); d.resolve({ data: { ...order(), addressDetail: 'old' } }); await old
  assert.equal(t.page.data.order.addressDetail, 'new')
})
test('a changed customer discards the old primary response and hides permissions', async () => {
  const d = deferred(), t = fixture({ order: () => d.promise }), pending = t.page.loadOrder(20); t.app.globalData.customerId = 8
  d.resolve({ data: order() }); await pending; assert.equal(t.page.data.order, null); assert.equal(t.page.data.canCancel, false); assert.equal(t.page.data.detailState, 'error')
})
test('same-customer logout and login is a new cycle and discards old detail', async () => {
  const d = deferred(), t = fixture({ order: () => d.promise }), pending = t.page.loadOrder(20)
  require(path.join(ROOT, 'miniapp-user/utils/token')).beginSession(); d.resolve({ data: order() }); await pending; assert.equal(t.page.data.order, null); assert.equal(t.page.data.detailState, 'error')
})
test('old phone lookup for the same order cannot overwrite a later retry', async () => {
  const d = deferred(); let n = 0; const t = fixture({ phone: () => ++n === 1 ? d.promise : Promise.resolve({ data: { phone: 'new' } }) })
  const old = t.page.loadOrder(20); await tick(); await t.page.loadOrder(20); d.resolve({ data: { phone: 'old' } }); await old; await tick()
  assert.equal(t.page.data.callPhone, 'new')
})
test('old product image cannot replace the same product in another order', async () => {
  const d = deferred(); let n = 0; const t = fixture({ product: () => ++n === 1 ? d.promise : Promise.resolve({ data: { imageUrl: 'new' } }) })
  const old = t.page.loadOrder(20); await tick(); await t.page.loadOrder(21); d.resolve({ data: { imageUrl: 'old' } }); await old; await tick()
  assert.equal(t.page.data.items[0].imageUrl, 'new'); assert.equal(t.page.data.order.id, 21)
})
test('old proof images cannot replace those of a newer order', async () => {
  const d = deferred(), t = fixture({ images: id => id === 20 ? d.promise : Promise.resolve({ data: [{ id: 2, url: 'new' }] }) })
  const old = t.page.loadOrder(20); await tick(); await t.page.loadOrder(21); d.resolve({ data: [{ id: 1, url: 'old' }] }); await old; await tick()
  assert.equal(t.page.data.images[0].url, 'new')
})
test('unloaded page ignores primary and supplemental completion', async () => {
  const d = deferred(), t = fixture({ order: () => d.promise }), pending = t.page.loadOrder(20)
  t.page.onUnload(); const frozen = JSON.stringify(t.page.data); d.resolve({ data: order() }); await pending; assert.equal(JSON.stringify(t.page.data), frozen)
})
test('native product image error clears only its own image', async () => {
  const t = fixture({ product: async () => ({ data: { imageUrl: 'broken' } }) }); await t.page.loadOrder(20); await tick()
  t.page.onItemImageError({ currentTarget: { dataset: { index: 0, url: 'broken' } } })
  assert.equal(t.page.data.items[0].imageUrl, ''); assert.equal(t.page.data.items[0].quantityUnit, '桶'); assert.equal(t.page.data.order.id, 20)
})
test('native proof failure keeps body, reports local error and excludes failed preview', async () => {
  const t = fixture({ images: async () => ({ data: [{ id: 1, url: 'broken' }, { id: 2, url: 'good' }] }) }); await t.page.loadOrder(20); await tick()
  t.page.onProofImageError({ currentTarget: { dataset: { url: 'broken' } } }); t.page.onPreviewImage({ currentTarget: { dataset: { url: 'good' } } })
  assert.equal(t.page.data.detailState, 'ready'); assert.equal(t.page.data.imagesState, 'error'); assert.deepStrictEqual(t.calls.previews[0].urls, ['good'])
})
test('native refresh and minimal error/retry/image bindings are actually connected', () => {
  const wxml = fs.readFileSync(path.join(ROOT, 'miniapp-user/pages/order/detail.wxml'), 'utf8')
  assert.ok(wxml.includes("detailState !== 'ready'")); assert.ok(wxml.includes('bindtap="onRetry"')); assert.ok(wxml.includes('bindtap="onRetryImages"'))
  assert.ok(wxml.includes('binderror="onItemImageError"')); assert.ok(wxml.includes('binderror="onProofImageError"'))
  assert.equal(JSON.parse(fs.readFileSync(path.join(ROOT, 'miniapp-user/pages/order/detail.json'), 'utf8')).enablePullDownRefresh, true)
})
test('customer switch also discards pending phone, product and proof responses', async () => {
  const phone = deferred(), product = deferred(), images = deferred()
  const t = fixture({ phone: () => phone.promise, product: () => product.promise, images: () => images.promise })
  const pending = t.page.loadOrder(20); await tick(); t.app.globalData.customerId = 8
  phone.resolve({ data: { phone: 'old-customer' } }); product.resolve({ data: { imageUrl: 'old-customer' } }); images.resolve({ data: [{ id: 1, url: 'old-customer' }] })
  await pending; assert.equal(t.page.data.order, null); assert.equal(t.page.data.callPhone, ''); assert.deepStrictEqual(t.page.data.images, []); assert.deepStrictEqual(t.page.data.items, [])
})
test('a slower failed proof request cannot erase the successful local retry', async () => {
  const d = deferred(); let n = 0
  const t = fixture({ images: () => ++n === 1 ? d.promise : Promise.resolve({ data: [{ id: 2, url: 'new' }] }) })
  const pending = t.page.loadOrder(20); await tick(); await t.page.onRetryImages(); d.reject(new Error('old failure')); await pending
  assert.equal(t.page.data.imagesState, 'ready'); assert.equal(t.page.data.images[0].url, 'new')
})
test('an unexpected response id never turns into another actionable order', async () => {
  const t = fixture({ order: async () => ({ data: order(99) }) }); await t.page.loadOrder(20)
  assert.equal(t.page.data.detailState, 'error'); assert.equal(t.page.data.order, null); assert.equal(t.page.data.canCancel, false)
})
;(async () => {
  let passed = 0, failed = 0
  for (const t of tests) { try { await t.run(); passed++; console.log('PASS ' + t.name) } catch (e) { failed++; console.error('FAIL ' + t.name + ' [' + e.name + ': ' + e.message + ']') } }
  console.log(`customer order detail: ${passed} passed, ${failed} failed`)
  if (failed) process.exitCode = 1; else console.log('AQUAFLOW_SUITE_OK ' + passed)
  done()
})()
