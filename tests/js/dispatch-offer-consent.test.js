'use strict'
const assert = require('assert'), Module = require('module'), path = require('path')
const { loadPage, createWx, createApp, armWatchdog } = require('./harness')
const finish = armWatchdog(25000), tests = []
const test = (name, run) => tests.push({ name, run })
const OLD = 'a'.repeat(64), NEW = 'b'.repeat(64)
function page(kind, api = {}) {
  const wx = createWx(), app = createApp({ globalData: { isLogin: true, userInfo: { staffId: 7, stationId: 3, role: 'STATION_MANAGER' } } })
  wx.showActionSheet = o => o.success({ tapIndex: 0 })
  const p = loadPage('miniapp-delivery/pages/' + kind + '/index.js', { wx, app, stubs: {
    'api/delivery': api.delivery || {}, 'utils/request': api.request || {},
    'utils/business-dialog': { showBusinessModal: o => { wx.__calls.modal.push(o); if (api.manualModal) return; return o.success({ confirm: true }) } }
  } })
  p.loadAllData = () => {}; p.loadData = () => {}
  if (kind === 'home') {
    // 本套聚焦已经读到的报价；弱网读取状态另由 delivery-task-read-recovery 覆盖。
    p._taskDataContext = p._collectionContext()
    p.setData({ loading: false, readReady: { assignedActions: true } })
  }
  if (kind === 'coordination') {
    p.setData({ staffList: [{ id: 8, name: '合成配送' }], lists: { pending: [], pool: [], incoming: [] } })
    p._confirmRisk = async () => ({ ok: true, acknowledged: false })
  }
  return { p, wx, app }
}
const order = token => ({ id: 5, dispatchAgreement: { status: 'OFFERED', quoteToken: token, serviceAmount: 40, barrelAmount: 0, barrelNote: '补空桶' } })
const event = (id = 5) => ({ currentTarget: { dataset: { id, name: '合成配送' } } })
test('real API wrappers use consent routes only when a displayed token is supplied', async () => {
  const file = path.resolve(__dirname, '../../miniapp-delivery/api/delivery.js'), calls = [], load = Module._load
  Module._load = function (request) {
    if (request === '../utils/request') return { post: async (url, data) => { calls.push({ url, data }); return { code: 0 } } }
    return load.apply(this, arguments)
  }
  try {
    delete require.cache[file]; const api = require(file)
    await api.acceptOrder(5, OLD); await api.assignOrder(5, { deliveryStaffId: 8, quoteToken: OLD }); await api.claimPoolOrder(5, { deliveryStaffId: 8, quoteToken: OLD })
    assert.deepEqual(calls.map(x => x.url), ['/api/delivery/orders/5/accept-quoted', '/api/delivery/orders/assign-quoted/5', '/api/delivery/orders/5/claim-pool-quoted'])
    assert(calls.every(x => x.data.quoteToken === OLD))
    calls.length = 0; await api.acceptOrder(5); await api.assignOrder(5, { deliveryStaffId: 8 }); await api.claimPoolOrder(5, { deliveryStaffId: 8 })
    assert.deepEqual(calls.map(x => x.url), ['/api/delivery/orders/5/accept', '/api/delivery/orders/assign/5', '/api/delivery/orders/5/claim-pool'])
  } finally { Module._load = load }
})
test('assignment freezes the offer shown before its risk confirmation', async () => {
  let sent
  const x = page('coordination', { request: { post: async (url, data) => { sent = { url, data } } } })
  const row = order(OLD); x.p.setData({ lists: { incoming: [row], pool: [] } }); x.p.onShowAssign(event())
  x.p._confirmRisk = async () => { row.dispatchAgreement = { ...row.dispatchAgreement, quoteToken: NEW, serviceAmount: 5 }; return { ok: true, acknowledged: true } }
  await x.p.onConfirmAssign(event(8))
  assert.equal(sent.url, '/api/delivery/orders/assign-quoted/5'); assert.equal(sent.data.quoteToken, OLD); assert.equal(sent.data.riskAcknowledged, true)
})
test('pool confirmation carries the original displayed offer, never a later read', async () => {
  let sent
  const x = page('coordination', { delivery: { claimPoolOrder: async (id, data) => { sent = { id, data } } } })
  const row = order(OLD); x.p.setData({ lists: { pool: [row] } })
  x.p._confirmRisk = async () => { row.dispatchAgreement.quoteToken = NEW; return { ok: true } }
  x.p.onClaimPool(event()); await new Promise(setImmediate); await new Promise(setImmediate)
  assert.equal(sent.id, 5); assert.equal(sent.data.quoteToken, OLD)
})
test('home confirmation displays and sends the same captured terms', async () => {
  let sent
  const x = page('home', { manualModal: true, delivery: { acceptOrder: async (id, token) => { sent = { id, token } } } })
  const row = order(OLD); x.p.setData({ assignedOrders: [row] })
  const accept = x.p.onAcceptOrder || x.p.onAccept; assert.equal(typeof accept, 'function'); accept(event())
  const modal = x.wx.__calls.modal.at(-1); assert.match(modal.content, /40/)
  row.dispatchAgreement = { ...row.dispatchAgreement, quoteToken: NEW, serviceAmount: 5 }
  await modal.success({ confirm: true }); assert.equal(sent.token, OLD)
})
for (const action of ['assign', 'pool', 'home']) test('old backend offer without consent support blocks ' + action, async () => {
  let writes = 0
  const x = page(action === 'home' ? 'home' : 'coordination', { delivery: { acceptOrder: async () => writes++, claimPoolOrder: async () => writes++ }, request: { post: async () => writes++ } })
  const row = order(null)
  if (action === 'home') { x.p.setData({ assignedOrders: [row] }); (x.p.onAcceptOrder || x.p.onAccept)(event()) }
  else { x.p.setData({ lists: { incoming: [row], pool: [row] } }); if (action === 'assign') x.p.onShowAssign(event()); else x.p.onClaimPool(event()) }
  await new Promise(setImmediate); assert.equal(writes, 0); assert.match(x.wx.__calls.toast.at(-1).title, /更新|刷新/)
})
test('rejected consent does not fetch a new token and automatically submit again', async () => {
  let writes = 0, refreshes = 0
  const x = page('coordination', { request: { post: async () => { writes++; throw Error('报价已变化，请重新确认') } } })
  x.p.loadAllData = () => refreshes++; x.p.setData({ lists: { incoming: [order(OLD)], pool: [] } }); x.p.onShowAssign(event())
  await x.p.onConfirmAssign(event(8)); assert.equal(writes, 1); assert.equal(refreshes, 0)
  assert.match(x.wx.__calls.toast.at(-1).title, /报价已变化/)
})
test('explicit zero quote remains confirmable', async () => {
  let writes = 0
  const x = page('home', { manualModal: true, delivery: { acceptOrder: async () => writes++ } }), row = order(OLD)
  row.dispatchAgreement.serviceAmount = 0; x.p.setData({ assignedOrders: [row] }); (x.p.onAcceptOrder || x.p.onAccept)(event())
  const modal = x.wx.__calls.modal.at(-1); assert.match(modal.content, /¥0/); await modal.success({ confirm: true }); assert.equal(writes, 1)
})
async function run() {
  let failed = 0
  for (const t of tests) try { await t.run(); console.log('PASS ' + t.name) } catch (e) { failed++; console.error('FAIL ' + t.name + ': ' + e.stack) }
  finish(); if (failed) process.exitCode = 1; else console.log('AQUAFLOW_SUITE_OK ' + tests.length)
}
run().catch(e => { finish(); console.error(e); process.exitCode = 1 })
