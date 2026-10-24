'use strict'
// 实际页面读取/展示投影；不访问微信、真实账号或数据库。
const assert = require('assert'), fs = require('fs'), path = require('path')
const { loadPage, createWx, createApp, armWatchdog, ROOT } = require('./harness')
const { parseWxml, renderElements } = require('./wxml-tree')
const done = armWatchdog(), tests = [], test = (name, run) => tests.push({ name, run })
const ok = data => ({ code: 0, data })
function fixture(route, stubs = {}, role = 'STATION_MANAGER') {
  const wx = createWx(), app = createApp({ globalData: { isLogin: true,
    userInfo: { staffId: 7, stationId: 11, stationName: '合成办理站', role, bindStatus: 'BOUND' } } })
  app.setLoginState = data => { app.globalData.userInfo = { ...app.globalData.userInfo, ...data }; return app.globalData.userInfo }
  const page = loadPage('miniapp-delivery/pages/' + route + '.js', { wx, app, stubs })
  return { page, wx, app }
}
function view(route, data) {
  return renderElements(parseWxml(fs.readFileSync(path.join(ROOT, 'miniapp-delivery/pages/' + route + '.wxml'), 'utf8')),
    data, { includeText: true }).map(row => row.text || '').join('\n')
}
for (const unbind of [true, false]) test('binding label and station source follow the verified mode: ' + unbind, async () => {
  const route = 'bind-wait/index', state = unbind ? 'PENDING_UNBIND' : 'PENDING'
  let reads = 0
  const response = { bindingStatus: state, stationId: 11, stationName: '当前绑定站', applyStationId: 22, applyStationName: '本次申请站' }
  const t = fixture(route, { 'utils/request': { get: async () => { reads++; return ok(response) } } }, 'DELIVERY')
  t.app.globalData.userInfo.applyStationName = '另一份旧申请站'; t.app.globalData.userInfo.applyStationId = 99
  await t.page.refresh(true)
  const text = view(route, t.page.data)
  assert(text.includes(unbind ? '申请解绑：当前绑定站' : '申请绑定：本次申请站'))
  assert(!text.includes('另一份旧申请站')); assert.equal(reads, 2)
  assert.equal(t.page.data.statusReady, true); assert.equal(t.app.globalData.userInfo.stationId, 11)
})
for (const named of [true, false]) test('adjustment detail uses authorized names or labelled references: ' + named, async () => {
  const route = 'station-mgmt/customers/adjust/detail/index'
  const response = { id: 9, customerId: 8, productId: 5, customerName: named ? '已核客户' : null,
    productName: named ? '已核商品' : null, adjustNo: 'ADJ9' }, before = JSON.stringify(response)
  let reads = 0
  const t = fixture(route, { 'api/station-mgmt': { getAdjustment: async () => { reads++; return ok(response) } } })
  t.page.setData({ id: 9 }); await t.page.loadData()
  const text = view(route, t.page.data)
  assert(text.includes(named ? '已核客户（客户编号：8）' : '客户编号：8'))
  assert(text.includes(named ? '已核商品（商品编号：5）' : '商品编号：5'))
  assert.equal(reads, 1); assert.equal(JSON.stringify(response), before)
})
test('waiting rows use provided station names and current-station context without extra reads', async () => {
  const route = 'station-mgmt/business-waiting/index'
  const stock = { orderId: 81, shortageQty: 1, responsibleStationId: 11 }
  const recovery = { orderId: 82, responsibleStationId: 11, fromStationId: 11, toStationId: 22,
    toStationName: '合成收款站', amount: 10 }
  const before = JSON.stringify([stock, recovery]), calls = []
  const t = fixture(route, { 'api/business-rules': {
    getWaiting: async () => { calls.push('waiting'); return ok({ schemaAvailable: true,
      counts: { waitingStock: 1, returnsTotal: 0, recoveriesTotal: 1, barrelsTotal: 0 }, stock: [stock], returns: [], recoveries: [recovery], barrels: [] }) },
    getRefusals: async () => { calls.push('refusals'); return { code: 1 } },
    getTicketExitBatches: async () => { calls.push('tickets'); return ok([]) }
  } })
  await t.page.loadData()
  const text = view(route, t.page.data)
  assert(text.includes('本站（水站编号：11）')); assert(text.includes('合成收款站（水站编号：22）'))
  assert.equal(t.page.data.recoveries[0].toStationId, 22); assert.equal(JSON.stringify([stock, recovery]), before)
  assert.deepStrictEqual(calls, ['waiting', 'refusals', 'tickets'])
})
for (const named of [true, false]) test('exception page uses response customer name without scanning customers: ' + named, async () => {
  const route = 'station-mgmt/exceptions/index'; let reads = 0
  const t = fixture(route, { 'utils/request': { get: async () => { reads++; return ok({ total: 1,
    records: [{ id: 9, orderId: 81, customerId: 8, customerName: named ? '已核客户' : null }] }) } } })
  await t.page.loadList(true)
  const text = view(route, t.page.data)
  assert(text.includes(named ? '已核客户（客户编号：8）' : '客户编号：8'))
  assert.equal(reads, 1); assert.equal(t.page.data.items[0].orderId, 81)
})
test('aftercare reuses only names from the current authorized ledger and drops them after identity change', async () => {
  const route = 'station-mgmt/inter-station/index', t = fixture(route)
  t.page.setData({ items: [{ fromStationId: 11, fromStationName: '当前退款站', toStationId: 22, toStationName: '授权合作站' }], dangling: [] })
  t.page._ledgerReadSession = t.page.aftercareSession()
  assert.equal(t.page.aftercareStationText(22), '授权合作站（水站编号：22）')
  assert.equal(t.page.aftercareStationText(33), '水站编号：33')
  t.app.globalData.userInfo.stationId = 44
  assert.equal(t.page.aftercareStationText(22), '水站编号：22')
  assert.equal(t.page.aftercareStationText(44), '本站（水站编号：44）')
})
test('barrel return names and historical delivery alerts preserve original customer/product ids', async () => {
  const route = 'station-mgmt/barrel-return/index', response = { id: 9, customerId: 8, customerName: '已核客户',
    productId: 5, productName: '已核商品', stationId: 11, type: 2, status: 1, quantity: 1, depositRefund: 30 }
  const before = JSON.stringify(response); let reads = 0
  const t = fixture(route, { 'api/station-mgmt': { getRefundUndelivered: async () => { reads++; return ok({ records: [response], count: 1, amount: 30 }) } } })
  t.page.setData({ scopeStationId: 11, scopeStationName: '合成办理站' })
  const row = t.page.decorateRecord(response)
  assert.equal(row.customerText, '已核客户（客户编号：8）'); assert.equal(row.productText, '已核商品（商品编号：5）')
  assert.equal(row.stationText, '合成办理站（水站编号：11）')
  await t.page.loadUndelivered()
  assert.equal(t.page.data.undelivered[0].customerText, row.customerText)
  assert.equal(reads, 1); assert.equal(JSON.stringify(response), before)
})
test('aftercare reads one scoped response and renders cached station names without changing responsibility fields', async () => {
  const route = 'station-mgmt/inter-station/index', row = { refundPaymentId: 9, refundVerified: true,
    refundStationId: 11, otherStationId: 22, refundAmount: 30, amount: null, version: 0, status: 'UNRESOLVED',
    statusText: '待核实', history: [], canPropose: true, canAgree: false, canPay: false }
  const before = JSON.stringify(row); let reads = 0
  const t = fixture(route, { 'api/business-rules': { getStationAftercare: async id => {
    reads++; return ok({ orderId: id, eligible: true, items: [row] })
  } } })
  t.page.setData({ items: [{ fromStationId: 11, fromStationName: '当前退款站', toStationId: 22, toStationName: '授权合作站' }] })
  t.page._ledgerReadSession = t.page.aftercareSession()
  await t.page.loadAftercare(81)
  assert.equal(t.page.data.aftercareReady, true)
  assert(view(route, t.page.data).includes('当前退款站（水站编号：11）'))
  assert.equal(t.page.data.aftercareItems[0].otherStationText, '授权合作站（水站编号：22）')
  assert.equal(reads, 1); assert.equal(JSON.stringify(row), before)
})
;(async () => {
  let failed = 0
  for (const t of tests) { try { await t.run(); console.log('PASS ' + t.name) }
    catch (e) { failed++; console.error('FAIL ' + t.name + ': ' + e.stack) } }
  done(); if (failed) process.exitCode = 1; else console.log('AQUAFLOW_SUITE_OK ' + tests.length)
})().catch(e => { done(); console.error(e); process.exitCode = 1 })
