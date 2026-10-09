const assert = require('assert'), fs = require('fs'), path = require('path')
const { loadPage, createWx, createApp, armWatchdog, ROOT } = require('./harness')
const { parseWxml, renderElements } = require('./wxml-tree')
const { orderActions } = require('../../miniapp-delivery/utils/order-actions')
const done = armWatchdog(30000), tests = []
const test = (name, run) => tests.push({ name, run })
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r }); return { promise, resolve } }
const base = id => ({ id, stationId: 1, status: 1, canCancel: true, canRepay: false, items: [], totalAmount: 10 })
function fixture(api) {
  const calls = [], wx = createWx(), app = createApp()
  const page = loadPage('miniapp-user/pages/order/detail.js', { wx, app, stubs: {
    'api/order': { getOrderDetail: id => { calls.push(id); return api(id) } },
    'api/orderImage': { getOrderImages: async () => ({ data: [] }) },
    'api/product': { getProductDetail: async () => ({ data: {} }) },
    'api/station': { getStationPublicPhone: async () => ({ data: {} }) }
  } })
  return { page, app, calls }
}
test('only the backend arrangement hint is shown without creating a consent or ETA', async () => {
  const hint = '配送安排遇到问题，水站正在重新安排，可能需要延后。请联系水站确认时间。'
  const t = fixture(async id => ({ data: { ...base(id), deliveryArrangementHint: hint, specialNote: '[内部] 不应展示' } }))
  await t.page.loadOrder(20)
  assert.equal(t.page.data.deliveryArrangementHint, hint)
  assert.equal(t.page.data.order.status, 1)
  assert.equal(t.page.data.order.delayAccepted, undefined)
  assert.equal(t.page.data.order.promisedArrivalTime, undefined)
  assert.deepStrictEqual(t.calls, [20])
  const tree = parseWxml(fs.readFileSync(path.join(ROOT, 'miniapp-user/pages/order/detail.wxml'), 'utf8'))
  const elements = renderElements(tree, t.page.data)
  const banner = elements.find(e => e.className.split(/\s+/).includes('delivery-arrangement-hint'))
  assert.ok(banner, 'the actual WXML must render the arrangement card')
})
test('a normal pending order and raw internal markers never invent a delay hint', async () => {
  const t = fixture(async id => ({ data: { ...base(id), specialNote: '[指定退回待确认] [取消外派]' } }))
  await t.page.loadOrder(20)
  assert.equal(t.page.data.deliveryArrangementHint, '')
  const tree = parseWxml(fs.readFileSync(path.join(ROOT, 'miniapp-user/pages/order/detail.wxml'), 'utf8'))
  assert.ok(!renderElements(tree, t.page.data).some(e => e.className.split(/\s+/).includes('delivery-arrangement-hint')))
})
test('a later actual arrangement clears the warning and a failed refresh cannot retain it', async () => {
  let stage = 0
  const t = fixture(async id => { if (stage === 2) throw new Error('offline'); return { data: { ...base(id), deliveryArrangementHint: stage === 0 ? '水站正在重新安排配送' : null } } })
  await t.page.loadOrder(20); assert.ok(t.page.data.deliveryArrangementHint)
  stage = 1; await t.page.loadOrder(20); assert.equal(t.page.data.deliveryArrangementHint, '')
  stage = 0; await t.page.loadOrder(20); stage = 2; await t.page.loadOrder(20)
  assert.equal(t.page.data.deliveryArrangementHint, ''); assert.equal(t.page.data.order, null)
})
test('a late response for another order or customer cannot bring back an old warning', async () => {
  const d = deferred(), t = fixture(id => id === 20 ? d.promise : Promise.resolve({ data: base(id) }))
  const old = t.page.loadOrder(20); await t.page.loadOrder(21)
  d.resolve({ data: { ...base(20), deliveryArrangementHint: '旧单重新安排' } }); await old
  assert.equal(t.page.data.order.id, 21); assert.equal(t.page.data.deliveryArrangementHint, '')
  const c = deferred(), changed = fixture(() => c.promise), pending = changed.page.loadOrder(20)
  changed.app.globalData.customerId = 8; c.resolve({ data: { ...base(20), deliveryArrangementHint: '别人的重新安排' } }); await pending
  assert.equal(changed.page.data.order, null); assert.equal(changed.page.data.deliveryArrangementHint, '')
})
test('directed return retains its delivery hold while colleague transfer keeps normal fulfillment', () => {
  const user = { role: 'DELIVERY', stationId: 2, staffId: 8 }
  const order = { status: 2, deliveryStationId: 2, deliveryStaffId: 8, transferPending: true }
  for (const kind of [{ transferKind: 'DIRECTED' }, { transferPendingKind: 'DIRECTED' }]) {
    const actions = orderActions({ ...order, ...kind }, user)
    assert.equal(actions.canComplete, false); assert.equal(actions.canReport, false)
  }
  const staff = orderActions({ ...order, transferKind: 'STAFF', transferPendingKind: 'STAFF', transferPendingSubKind: 'TRANSFER' }, user)
  assert.equal(staff.canComplete, true); assert.equal(staff.canReport, true)
  assert.equal(orderActions(order, { ...user, stationId: 1 }).canComplete, false)
})
;(async () => {
  let failed = 0
  for (const t of tests) { try { await t.run(); console.log('PASS ' + t.name) } catch (error) { failed++; console.error('FAIL ' + t.name + ': ' + error.message) } }
  if (failed) process.exitCode = 1; else console.log('AQUAFLOW_SUITE_OK ' + tests.length)
  done()
})()
