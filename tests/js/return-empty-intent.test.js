// Actual Page + durable intent helper; all API calls synthetic, no HTTP/DB/money.
const assert = require('node:assert/strict')
const { loadPage, createApp, createWx, armWatchdog } = require('./harness')
const done = armWatchdog(30000), tests = []
const test = (name, run) => tests.push({ name, run })
const route = 'miniapp-delivery/pages/station-mgmt/customers/detail/index.js'
const key = 'return-empty-intent:1:1:7'
const unknown = () => Object.assign(Error('SYNTH timeout'), { requestMayHaveOccurred: true })
const confirmed = items => ({ code: 0, data: { changes: items.map(item => ({ ...item })) } })
const duplicate = { code: 0, data: { duplicate: true, changes: [] } }
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
function fixture(send = async (id, items) => confirmed(items), wx = createWx()) {
  const writes = [], reads = []
  const app = createApp({ globalData: { isLogin: true, userInfo: { staffId: 1, stationId: 1, role: 'STATION_MANAGER' } }, isStationManager: () => true })
  const page = loadPage(route, { wx, app, stubs: { 'api/station-mgmt': {
    returnEmptyBuckets: async (...args) => { writes.push(structuredClone(args)); return send(...args) },
    getCustomerAssets: async id => { reads.push(id); return { code: 0, data: { barrels: [], tickets: [], records: [] } } }
  } } })
  page.setData({ id: 7, canViewAssets: true, returnItems: [{ productId: 5, qty: 1 }], showReturnEmpty: true,
    assets: { barrels: [{ productId: 5, productName: 'SYNTH water', maxReturnableQty: 4, overQty: 1 }] } })
  page.onShowReturnEmpty()
  return { page, app, wx, writes, reads }
}

test('WB-01 two actual handler calls while pending issue one request and one key', async () => {
  const gate = deferred(), t = fixture(() => gate.promise)
  const first = t.page.onSubmitReturnEmpty(); await t.page.onSubmitReturnEmpty()
  assert.equal(t.writes.length, 1); assert(t.wx.getStorageSync(key).clientToken)
  gate.resolve(confirmed([{ productId: 5, qty: 1 }])); await first
  assert.equal(t.page.data.returnPending, false); assert.equal(t.wx.getStorageSync(key), '')
})
test('a queued old tap after confirmed success cannot start a new registration', async () => {
  const t = fixture(); await t.page.onSubmitReturnEmpty(); await t.page.onSubmitReturnEmpty()
  assert.equal(t.writes.length, 1); assert.equal(t.page.data.showReturnEmpty, false)
})
test('a queued tap after cancelling the modal sends nothing', async () => {
  const t = fixture(); t.page.onCloseReturnEmpty(); await t.page.onSubmitReturnEmpty()
  assert.equal(t.writes.length, 0)
})
test('old modal confirmation cannot acquire a different staff identity', async () => {
  const t = fixture(); t.app.globalData.userInfo.staffId = 2
  await t.page.onSubmitReturnEmpty(); assert.equal(t.writes.length, 0)
  assert.equal(t.page.data.showReturnEmpty, false)
})
test('returning under a new login invalidates the previously open modal', async () => {
  const t = fixture(); t.app._loginGeneration = 1; t.page.onShow()
  assert.equal(t.page.data.showReturnEmpty, false)
  await t.page.onSubmitReturnEmpty(); assert.equal(t.writes.length, 0)
})
test('WB-01 uncertain retry preserves original key and quantity despite stale input', async () => {
  const t = fixture(async () => { throw unknown() })
  await t.page.onSubmitReturnEmpty(); const token = t.writes[0][2]
  t.page.onReturnEmptyQtyInput({ detail: { value: '3' }, currentTarget: { dataset: { idx: 0, max: 4 } } })
  assert.equal(t.page.data.returnItems[0].qty, 1)
  t.page.setData({ returnItems: [{ productId: 6, qty: 3 }] })
  await t.page.onSubmitReturnEmpty()
  assert.equal(t.writes.length, 2); assert.equal(t.writes[1][2], token)
  assert.deepEqual(t.writes[1][1], [{ productId: 5, qty: 1 }]); assert.equal(t.page.data.returnPending, true)
})
test('cold reentry recovers original request even if assets are unavailable', async () => {
  const first = fixture(async () => { throw unknown() }); await first.page.onSubmitReturnEmpty()
  const second = fixture(async () => duplicate, first.wx)
  second.page.setData({ assets: null, assetsError: 'SYNTH unavailable' })
  second.page.onShow(); second.page.onShowReturnEmpty()
  assert.equal(second.page.data.returnPending, true); assert.equal(second.page.data.returnItems[0].productId, 5)
  await second.page.onSubmitReturnEmpty()
  assert.equal(second.writes[0][2], first.writes[0][2]); assert.equal(second.page.data.returnPending, false)
  assert(second.wx.__calls.toast.some(t => t.title === '原还桶登记已确认'))
})
test('closing and reopening unknown modal retains original intent', async () => {
  const t = fixture(async () => { throw unknown() }); await t.page.onSubmitReturnEmpty()
  t.page.onCloseReturnEmpty(); assert.equal(t.page.data.showReturnEmpty, false)
  t.page.onShowReturnEmpty(); assert.equal(t.page.data.showReturnEmpty, true)
  assert.equal(t.page.data.returnItems[0].qty, 1); assert(t.wx.getStorageSync(key))
})
test('pending submit cannot close or edit its modal', async () => {
  const gate = deferred(), t = fixture(() => gate.promise), pending = t.page.onSubmitReturnEmpty()
  t.page.onCloseReturnEmpty(); t.page.onReturnEmptyQtyInput({ detail: { value: '3' }, currentTarget: { dataset: { idx: 0, max: 4 } } })
  assert.equal(t.page.data.showReturnEmpty, true); assert.equal(t.page.data.returnItems[0].qty, 1)
  gate.resolve(duplicate); await pending
})
for (const [name, error] of [['business rejection', { businessRejected: true }], ['not dispatched', { requestMayHaveOccurred: false }]]) {
  test('first definite ' + name + ' releases the unused intent for correction', async () => {
    const t = fixture(async () => { throw Object.assign(Error('SYNTH rejected'), error) })
    await t.page.onSubmitReturnEmpty()
    assert.equal(t.page.data.returnPending, false); assert.equal(t.wx.getStorageSync(key), '')
  })
  test('a prior unknown followed by ' + name + ' cannot erase the original key', async () => {
    let call = 0; const t = fixture(async () => { throw ++call === 1 ? unknown() : Object.assign(Error('SYNTH rejected'), error) })
    await t.page.onSubmitReturnEmpty(); await t.page.onSubmitReturnEmpty()
    assert.equal(t.page.data.returnPending, true); assert.equal(t.wx.getStorageSync(key).clientToken, t.writes[0][2])
  })
}
for (const [name, change] of [
  ['write throws', wx => { wx.setStorageSync = () => { throw Error('SYNTH disk') } }],
  ['silent write loss', wx => { wx.setStorageSync = () => {} }],
  ['read throws', wx => { wx.getStorageSync = () => { throw Error('SYNTH disk') } }],
  ['corrupt stored intent', wx => { wx.setStorageSync(key, { clientToken: 'broken' }) }]
]) test(name + ' refuses to send an unprotected request', async () => {
  const t = fixture(); change(t.wx); await t.page.onSubmitReturnEmpty()
  assert.equal(t.writes.length, 0); assert(t.page.data.returnRecoveryError)
  assert(!/SYNTH|disk|StorageSync/.test(t.page.data.returnRecoveryError))
})
test('temporary storage loss after uncertain response restores the in-memory original key', async () => {
  const t = fixture(async () => { throw unknown() }); await t.page.onSubmitReturnEmpty()
  const token = t.writes[0][2]; t.wx.removeStorageSync(key)
  await t.page.onSubmitReturnEmpty(); assert.equal(t.writes[1][2], token)
})
test('confirmed result with cleanup failure retries cleanup without another POST', async () => {
  const t = fixture(); const remove = t.wx.removeStorageSync
  t.wx.removeStorageSync = () => { throw Error('SYNTH cannot clear') }
  await t.page.onSubmitReturnEmpty(); assert.equal(t.page.data.returnPending, true)
  assert(t.page.data.returnMessage.includes('已确认'))
  t.wx.removeStorageSync = remove
  await t.page.onSubmitReturnEmpty(); assert.equal(t.writes.length, 1); assert.equal(t.page.data.returnPending, false)
})
for (const response of [null, { code: 0, data: {} }, { code: 0, data: { changes: [] } }, { code: 0, data: { changes: [{ productId: 6, qty: 1 }] } }]) {
  test('incomplete or mismatched success receipt preserves original key: ' + JSON.stringify(response), async () => {
    const t = fixture(async () => response); await t.page.onSubmitReturnEmpty()
    assert.equal(t.page.data.returnPending, true); assert(t.wx.getStorageSync(key)); assert.equal(t.wx.__calls.toast.length, 0)
  })
}
for (const [name, change] of [
  ['staff', t => { t.app.globalData.userInfo.staffId = 2 }],
  ['station', t => { t.app.globalData.userInfo.stationId = 2 }],
  ['new login generation', t => { t.app._loginGeneration = 1 }],
  ['customer', t => { t.page.data.id = 8 }]
]) test('late success after ' + name + ' changes cannot clear or repaint the new context', async () => {
  const gate = deferred(), t = fixture(() => gate.promise), pending = t.page.onSubmitReturnEmpty()
  change(t); t.page.setData({ returnMessage: 'SYNTH new context' })
  gate.resolve(duplicate); await pending
  assert.equal(t.page.data.returnMessage, 'SYNTH new context'); assert(t.wx.getStorageSync(key))
  assert.equal(t.reads.length, 0); assert.equal(t.wx.__calls.toast.length, 0)
})
test('hidden known success does not lose its receipt or reload hidden assets', async () => {
  const gate = deferred(), t = fixture(() => gate.promise), pending = t.page.onSubmitReturnEmpty()
  t.page.onHide(); gate.resolve(duplicate); await pending
  assert.equal(t.wx.getStorageSync(key), ''); assert.equal(t.page.data.showReturnEmpty, false)
  assert.equal(t.wx.__calls.toast.length, 0); assert.equal(t.reads.length, 0)
  t.page.onShow(); assert.equal(t.page.data.returnPending, false)
})
test('hidden or disposed page cannot start a stale return request', async () => {
  for (const method of ['onHide', 'onUnload']) {
    const t = fixture(); t.page[method](); await t.page.onSubmitReturnEmpty(); assert.equal(t.writes.length, 0)
  }
})
test('other staff, station and customer never inherit a saved return intent', async () => {
  const first = fixture(async () => { throw unknown() }); await first.page.onSubmitReturnEmpty()
  for (const change of [t => { t.app.globalData.userInfo.staffId = 2 }, t => { t.app.globalData.userInfo.stationId = 2 }, t => { t.page.data.id = 8 }]) {
    const t = fixture(undefined, first.wx); change(t); t.page.onShow()
    assert.equal(t.page.data.returnPending, false); assert(t.wx.getStorageSync(key))
  }
  const courier = fixture(undefined, first.wx)
  courier.app.isStationManager = () => false
  courier.page.setData({ canViewAssets: false })
  courier.page.onShow()
  assert.equal(courier.page.data.returnRecoveryError, '')
  assert.equal(courier.page.data.showReturnEmpty, false)
  await courier.page.onSubmitReturnEmpty(); assert.equal(courier.writes.length, 0)
})
test('multiple products replay in the same sorted order and never use edited quantities', async () => {
  const t = fixture(async () => { throw unknown() })
  t.page.setData({ returnItems: [{ productId: 9, qty: 2 }, { productId: 5, qty: 1 }] })
  await t.page.onSubmitReturnEmpty(); await t.page.onSubmitReturnEmpty()
  assert.deepEqual(t.writes[0][1], [{ productId: 5, qty: 1 }, { productId: 9, qty: 2 }])
  assert.deepEqual(t.writes[0], t.writes[1])
})

;(async () => {
  let failed = 0
  for (const t of tests) { try { await t.run(); console.log('PASS ' + t.name) } catch (error) { failed++; console.error('FAIL ' + t.name + ': ' + error.stack) } }
  done(); if (failed) process.exitCode = 1
  else console.log('AQUAFLOW_SUITE_OK ' + tests.length)
})().catch(error => { done(); console.error(error); process.exitCode = 1 })
