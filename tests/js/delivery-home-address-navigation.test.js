'use strict'
const assert = require('assert'), fs = require('fs'), path = require('path')
const { loadPage, createWx, createApp, armWatchdog, ROOT } = require('./harness')
const done = armWatchdog(30000), tests = [], test = (name, fn) => tests.push({ name, fn })
const ok = data => ({ code: 0, data })
const row = (id, extra = {}) => ({ id, status: 1, stationId: 3, deliveryStationId: 3, deliveryStaffId: 7, paymentStatus: 1, items: [], ...extra })
async function fixture(manager = true) {
  const wx = createWx(), calls = { open: [], choose: [], modal: [] }
  wx.openLocation = data => calls.open.push(data)
  wx.chooseLocation = data => calls.choose.push(data)
  wx.showModal = data => calls.modal.push(data)
  const app = createApp({ _loginGeneration: 1, globalData: { isLogin: true, userInfo: {
    staffId: 7, stationId: 3, role: manager ? 'STATION_MANAGER' : 'DELIVERY', bindStatus: 'BOUND' } } })
  const api = {
    getAssignedToMe: async () => ok([row(5, { addressSnapshot: '下单地址', addressDetail: '客户修改后地址',
      addressSnapshotLat: '30.5', addressSnapshotLng: '120.2', addressFloor: 8 })]),
    getPendingOrders: async () => ok([row(9, { deliveryStaffId: null, addressSnapshot: '无坐标地址' }), row(10, { deliveryStaffId: null, transferPendingKind: 'DIRECTED' })]),
    getDeliveringOrders: async () => ok([row(6, { status: 2, addressSnapshot: '配送地址', addressSnapshotLat: 0, addressSnapshotLng: 0 })]),
    getCompletedToday: async () => ok([]), getDeliveredUnpaid: async () => ok([]), getTransferList: async () => ok([])
  }
  const page = loadPage('miniapp-delivery/pages/home/index.js', { wx, app, stubs: {
    'api/delivery': api, 'utils/request': { get: async () => ok([]) }
  } })
  page.setData({ isManager: manager }); await page.loadData(manager)
  return { page, app, wx, calls }
}
const event = (id, field = 'assignedOrders') => ({ currentTarget: { dataset: { id, field } } })
test('manager labels each source without calling a directed pending row unassigned', async () => {
  const { page } = await fixture()
  assert.deepStrictEqual(page.data.assignedOrders.map(x => x.assignmentLabel), ['派给我', '未分配', '待处理'])
  assert.strictEqual(page.data.assignedOrders[0].addressText, '下单地址')
  assert.strictEqual(page.data.assignedOrders[0].floorText, '')
  assert.strictEqual(page.data.assignedOrders[2].addressText, '订单地址快照缺失，请核实')
  const delivery = await fixture(false)
  assert.deepStrictEqual(delivery.page.data.assignedOrders.map(x => x.id), [5])
})
test('navigation opens the same immutable address and coordinates shown on the card', async () => {
  const { page, calls } = await fixture(); page.onNavigateOrder(event(5))
  assert.strictEqual(calls.open.length, 1)
  assert.strictEqual(calls.open[0].name, '下单地址'); assert.strictEqual(calls.open[0].address, '下单地址')
  assert.strictEqual(calls.open[0].latitude, 30.5); assert.strictEqual(calls.open[0].longitude, 120.2)
  assert.strictEqual(calls.choose.length, 0)
})
test('missing or invalid coordinates require explicit manual selection with the snapshot', async () => {
  const { page, calls } = await fixture(); page.onNavigateOrder(event(9))
  assert.ok(calls.modal[0].content.includes('无坐标地址')); assert.strictEqual(calls.open.length, 0)
  calls.modal[0].success({ confirm: true }); assert.strictEqual(calls.choose.length, 1)
  calls.choose[0].success({ latitude: 31, longitude: 121, name: '员工选择位置', address: '手动选点' })
  assert.strictEqual(calls.open[0].name, '员工选择位置')
  page.data.deliveringOrders[0].addressSnapshotLat = 999
  page.onNavigateOrder(event(6, 'deliveringOrders')); assert.strictEqual(calls.modal.length, 2)
})
test('missing snapshot or stale context cannot navigate; stale modal callbacks are ignored', async () => {
  const t = await fixture(); t.page.onNavigateOrder(event(10))
  assert.strictEqual(t.calls.open.length, 0); assert.strictEqual(t.calls.modal.length, 0)
  t.page.onNavigateOrder(event(9)); t.app._loginGeneration++
  t.calls.modal[0].success({ confirm: true }); assert.strictEqual(t.calls.choose.length, 0)
  t.page.onNavigateOrder(event(5)); assert.strictEqual(t.calls.open.length, 0)
})
test('WXML uses the snapshot projection, exposes source badge and catchtap navigation', () => {
  const w = fs.readFileSync(path.join(ROOT, 'miniapp-delivery/pages/home/index.wxml'), 'utf8')
  assert.ok(!w.includes('item.addressDetail || item.addressSnapshot'))
  assert.ok(w.includes('{{item.addressText}}')); assert.ok(w.includes('{{item.assignmentLabel}}'))
  assert.strictEqual((w.match(/catchtap="onNavigateOrder"/g) || []).length, 2)
})
;(async () => {
  let failures = 0
  for (const t of tests) { try { await t.fn(); console.log('PASS ' + t.name) } catch (e) { failures++; console.error('FAIL ' + t.name, e) } }
  done(); if (failures) process.exitCode = 1; else console.log('AQUAFLOW_SUITE_OK ' + tests.length)
})().catch(e => { done(); console.error(e); process.exitCode = 1 })