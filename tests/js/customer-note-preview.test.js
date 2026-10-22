'use strict'
const assert = require('assert'), fs = require('fs'), path = require('path'), Module = require('module')
const { loadPage, createWx, createApp, armWatchdog, ROOT } = require('./harness')
const { parseWxml, renderElements } = require('./wxml-tree')
const { customerNotePreview } = require('../../miniapp-user/utils/customer-note')
const employeeNotes = require('../../miniapp-delivery/utils/customer-note')
const read = file => fs.readFileSync(path.join(ROOT, file), 'utf8')
const noteRow = (extra = {}) => ({ id: 81, status: 1, statusText: '待配送', stationId: 11,
  deliveryStationId: 11, deliveryStaffId: 7, customerNote: ' 门铃坏了\n请先电话联系 <门口> & "轻放" ',
  specialNote: '[分配] 内部记录\n[接单] 内部记录', addressDetail: '合成地址：清泉路8号2栋601',
  receiverName: '合成收件人', receiverPhone: '13800000000', itemSummary: '纯净水 2桶，矿泉水 1瓶',
  totalAmount: 36, payState: 'PENDING', payStateText: '待收款', needCollect: true,
  paymentMethod: 2, paymentStatus: 1,
  createTime: '2026-10-22T09:30:00', updateTime: '2026-10-22T10:30:00', items: [], ...extra })
const appFor = (role = 'DELIVERY') => createApp({ _loginGeneration: 1, globalData: { isLogin: true,
  customerId: 9, accessToken: 'synthetic', userInfo: { staffId: 7, role, stationId: 11, bindStatus: 'BOUND' } } })
const event = id => ({ currentTarget: { dataset: { id } } })
function cardFixture() {
  const wx = createWx(), app = appFor(), calls = [], previous = global.Component
  let config
  global.wx = wx; global.getApp = () => app; global.Component = c => { config = c }
  const original = Module._load
  Module._load = function (request) {
    if (request.endsWith('/api/order')) return { getOrderDetail() { calls.push('detail') }, cancelOrder() { calls.push('cancel') } }
    return original.apply(this, arguments)
  }
  try { const file = path.join(ROOT, 'miniapp-user/components/OrderCard/index.js'); delete require.cache[file]; require(file) }
  finally { Module._load = original; global.Component = previous }
  const page = { data: JSON.parse(JSON.stringify(config.data)), setData(p) { Object.assign(this.data, p) }, triggerEvent() {} }
  for (const [key, value] of Object.entries(config.methods)) page[key] = value.bind(page)
  page.update = row => { page.data.order = row; config.observers.order.call(page, row) }
  return { page, wx, calls }
}
function homeFixture(role = 'DELIVERY') {
  let rows = [noteRow()]
  const calls = [], api = {}, wx = createWx(), app = appFor(role)
  for (const key of ['getAssignedToMe', 'getDeliveringOrders', 'getCompletedToday', 'getDeliveredUnpaid', 'getTransferList', 'getPendingOrders']) {
    api[key] = async () => { calls.push(key); return { code: 0, data: rows } }
  }
  const page = loadPage('miniapp-delivery/pages/home/index.js', { wx, app, stubs: { 'api/delivery': api } })
  return { page, wx, app, calls, setRows(value) { rows = value } }
}
function recordFixture(folder) {
  let rows = [noteRow()], reads = 0
  const app = appFor(), wx = createWx()
  const response = async () => { reads++; return { code: 0, data: rows } }
  const page = loadPage(`miniapp-delivery/pages/${folder}/index.js`, { app, wx,
    stubs: { 'api/delivery': { getDeliveryHistory: response, getTransferRecords: response } } })
  return { page, wx, app, reads: () => reads, setRows(value) { rows = value } }
}
function managerFixture() {
  let rows = [noteRow()]
  const calls = [], app = appFor('STATION_MANAGER'), wx = createWx()
  const page = loadPage('miniapp-delivery/pages/station-mgmt/orders/index.js', { app, wx, stubs: {
    'api/station-mgmt': {
      getOrders: async q => { calls.push(q); return { data: rows } },
      getCrossStationOrders: async () => ({ data: { count: 1, orders: rows } })
    }, 'utils/request': { get: async () => ({ data: [] }) }
  } })
  return { page, wx, app, calls, setRows(value) { rows = value } }
}
function coordinationFixture() {
  const calls = [], app = appFor('STATION_MANAGER'), wx = createWx()
  const row = noteRow(), response = data => ({ code: 0, data })
  const api = {
    getPoolOrders: async () => { calls.push('pool'); return response([row]) },
    getDispatchTracking: async () => { calls.push('dispatch'); return response([{ ...row, dispatchKind: 'POOL' }, { ...row, id: 82, dispatchKind: 'DIRECTED' }]) },
    getDirectedIncoming: async () => { calls.push('incoming'); return response([{ ...row, deliveryStaffId: null }, { ...row, id: 82, status: 2 }]) },
    getPendingApprovals: async () => { calls.push('approvals'); return response({ customer: [row], station: [{ ...row, transferPendingSubKind: 'RETURN_STATION' }] }) },
    getStaffList: async () => { calls.push('staff'); return response([]) }
  }
  const page = loadPage('miniapp-delivery/pages/coordination/index.js', { app, wx, stubs: {
    'api/delivery': api, 'utils/request': { get: async url => { calls.push(url); return response(url.endsWith('station-pending') ? [row] : []) } }
  } })
  return { page, wx, app, calls }
}
function detailFixture() {
  let row = noteRow(), reads = 0
  const app = appFor(), wx = createWx()
  const page = loadPage('miniapp-delivery/pages/order/detail.js', { app, wx, stubs: {
    'api/delivery': { getOrderDetail: async () => { reads++; return { data: row } } },
    'api/orderImage': { getOrderImages: async () => ({ data: [] }) },
    'api/payment': { getPaymentRecords: async () => ({ data: [] }) }
  } })
  page.loadFloorPhotos = page.loadDeliveryPhotos = page.loadPayments = () => {}
  page.setData({ orderId: 81 })
  return { page, wx, reads: () => reads, setRow(value) { row = value } }
}
function findNodes(file, predicate) {
  const found = [], walk = node => { if (predicate(node)) found.push(node); for (const child of node.children || []) walk(child) }
  walk(parseWxml(read(file))); return found
}
function render(node, data) { return renderElements({ tag: '#root', children: [node] }, data, { includeText: true }) }
function cardNodes(file) { return findNodes(file, n => !!n.attrs && /(^|\s)order-card(\s|$)/.test(n.attrs.class || '')) }
function previewNodes(file, data) {
  return findNodes(file, n => n.attrs && n.attrs.class === 'customer-note-preview').flatMap(n => render(n, data))
}
const tests = [], test = (name, run) => tests.push({ name, run })
test('both packages read only customerNote; normalize display whitespace and retain the raw source', () => {
  for (const value of [null, undefined, '', ' \r\n\t　 ', 42, {}, [], true]) {
    assert.strictEqual(customerNotePreview({ customerNote: value, specialNote: '内部记录' }), '')
    assert.strictEqual(employeeNotes.customerNotePreview({ customerNote: value, specialNote: '内部记录' }), '')
  }
  for (const value of ['长'.repeat(400), '<script>" & {{x}}</script>', '[接单] 这是客户原话', '第一行\r\n第二行\t第三行']) {
    const row = { customerNote: value, specialNote: '内部记录' }
    assert.strictEqual(customerNotePreview(row), value.replace(/\s+/g, ' ').trim()); assert.strictEqual(row.customerNote, value)
    assert.strictEqual(employeeNotes.customerNotePreview(row), customerNotePreview(row))
  }
  assert.strictEqual(customerNotePreview({ specialNote: '只有旧备注' }), '')
})
test('customer OrderCard shows exactly one preview in all five statuses without a detail request', () => {
  const t = cardFixture(), file = 'miniapp-user/components/OrderCard/index.wxml'
  for (const status of [1, 2, 3, 4, 5]) {
    t.page.update(noteRow({ status })); const nodes = previewNodes(file, t.page.data)
    assert.strictEqual(nodes.length, 1); assert.strictEqual(nodes[0].text, '备注：' + customerNotePreview(noteRow()))
  }
  assert.deepStrictEqual(t.calls, [])
  t.page.onOrderTap(); assert.strictEqual(t.wx.__calls.nav.at(-1).url, '/pages/order/detail?id=81')
})
test('reusing a customer card clears the note for blank, legacy-only, null and a new order', () => {
  const t = cardFixture(), file = 'miniapp-user/components/OrderCard/index.wxml'
  for (const row of [noteRow({ customerNote: ' \n ' }), { id: 82, specialNote: '旧备注' }, null, { id: 83 }]) {
    t.page.update(noteRow()); t.page.update(row)
    assert.strictEqual(t.page.data.customerNotePreview, ''); assert.deepStrictEqual(previewNodes(file, t.page.data), [])
  }
})
test('delivery home keeps five courier and six manager list reads with four preview lists', async () => {
  for (const role of ['DELIVERY', 'STATION_MANAGER']) {
    const t = homeFixture(role); await t.page.loadData(role === 'STATION_MANAGER')
    assert.strictEqual(t.calls.length, role === 'DELIVERY' ? 5 : 6)
    assert.strictEqual(t.calls.includes('getPendingOrders'), role === 'STATION_MANAGER')
    const nodes = cardNodes('miniapp-delivery/pages/home/index.wxml')
    for (const node of nodes) {
      const rendered = render(node, t.page.data), previews = rendered.filter(n => n.className === 'customer-note-preview')
      assert.strictEqual(previews.length, 1); assert.ok(previews[0].text.includes('<门口>'))
      assert.ok(rendered.some(n => n.text === '¥36.00')); assert.ok(rendered.some(n => n.text === noteRow().addressDetail))
    }
    t.page.onOrderTap(event(81)); assert.strictEqual(t.wx.__calls.nav.at(-1).url, '/pages/order/detail?id=81')
  }
})
test('pending transfer remains non-clickable and has no preview even when its payload contains a note', async () => {
  const t = homeFixture(); await t.page.loadData(false)
  const [node] = findNodes('miniapp-delivery/pages/home/index.wxml', n => n.attrs && n.attrs.class === 'transfer-in-card')
  const nodes = render(node, t.page.data)
  assert.strictEqual(node.attrs.bindtap, undefined)
  assert.ok(!nodes.some(n => n.className === 'customer-note-preview'))
  assert.deepStrictEqual(nodes.filter(n => n.tag === 'button').map(n => n.attrs.catchtap), ['onAcceptTransfer', 'onRejectTransfer'])
})
test('home refresh removes notes from every status rather than leaving old display values', async () => {
  const t = homeFixture(); await t.page.loadData(false); t.setRows([noteRow({ customerNote: ' \n ' })]); await t.page.loadData(false)
  for (const key of ['assignedOrders', 'deliveringOrders', 'completedOrders', 'deliveredUnpaidOrders']) assert.strictEqual(t.page.data[key][0].customerNotePreview, '')
})
for (const folder of ['history', 'transfer']) {
  test(folder + ' actual list mapping preserves raw notes, navigation and one list read per refresh', async () => {
    const t = recordFixture(folder); await t.page.loadData()
    assert.strictEqual(t.reads(), 1); assert.strictEqual(t.page.data.orders[0].customerNote, noteRow().customerNote)
    assert.strictEqual(previewNodes(`miniapp-delivery/pages/${folder}/index.wxml`, { item: t.page.data.orders[0] }).length, 1)
    t.page.onOrderTap(event(81)); assert.strictEqual(t.wx.__calls.nav.at(-1).url, '/pages/order/detail?id=81')
    t.setRows([noteRow({ customerNote: undefined })]); await t.page.loadData()
    assert.strictEqual(t.reads(), 2); assert.strictEqual(t.page.data.orders[0].customerNotePreview, '')
  })
  test(folder + ' late data from a prior station is discarded', async () => {
    let resolve
    const t = recordFixture(folder), api = folder === 'history' ? 'getDeliveryHistory' : 'getTransferRecords'
    const app = t.app, d = new Promise(r => { resolve = r })
    const page = loadPage(`miniapp-delivery/pages/${folder}/index.js`, { app, wx: t.wx, stubs: { 'api/delivery': { [api]: () => d } } })
    const task = page.loadData(); app.globalData.userInfo.stationId = 22; resolve({ code: 0, data: [noteRow()] }); await task
    assert.deepStrictEqual(page.data.orders, [])
  })
}
test('station order filtering refreshes the note while the cross-station summary gets no entry or preview', async () => {
  const t = managerFixture(); await t.page.loadData()
  assert.strictEqual(t.page.data.list[0].customerNotePreview, customerNotePreview(noteRow()))
  t.setRows([noteRow({ status: 4, customerNote: '' })]); await t.page.onTabChange(event(4))
  assert.strictEqual(t.calls.at(-1).status, 4); assert.strictEqual(t.calls.at(-1).stationId, 11)
  assert.strictEqual(t.page.data.list[0].customerNotePreview, '')
  const [cross] = findNodes('miniapp-delivery/pages/station-mgmt/orders/index.wxml', n => n.attrs && n.attrs.class === 'cross-row')
  assert.strictEqual(cross.attrs.bindtap, undefined)
  assert.ok(!render(cross, { ...t.page.data, crossExpanded: true }).some(n => n.className === 'customer-note-preview'))
})
test('coordination preserves its seven reads and five authorized preview card families', async () => {
  const t = coordinationFixture(); await t.page.loadAllData()
  assert.strictEqual(t.calls.length, 7); assert.strictEqual(t.page.data.loadError, '')
  for (const node of cardNodes('miniapp-delivery/pages/coordination/index.wxml')) {
    const family = node.attrs['wx:for'].match(/lists\.(\w+)/)[1]
    const nodes = render(node, t.page.data)
    const allowed = ['pending', 'approvalCustomer', 'approvalStation', 'directedIncoming', 'directedAccepted'].includes(family)
    assert.strictEqual(nodes.filter(n => n.className === 'customer-note-preview').length, allowed ? 1 : 0, family)
  }
})
test('employee full note keeps original whitespace and special characters beside untouched processing records', async () => {
  const t = detailFixture(); assert.strictEqual(await t.page.loadOrderDetail(81), true)
  assert.strictEqual(t.reads(), 1); assert.strictEqual(t.page.data.order.customerNote, noteRow().customerNote)
  assert.deepStrictEqual(t.page.data.order.notes, ['[分配] 内部记录', '[接单] 内部记录'])
  const [section] = findNodes('miniapp-delivery/pages/order/detail.wxml', n => n.attrs && n.attrs['wx:if'] === '{{order.customerNotePreview}}')
  const nodes = render(section, t.page.data)
  assert.ok(nodes.some(n => n.className === 'customer-note-full' && n.text === noteRow().customerNote.trim()))
  for (const value of ['', undefined, ' \n ']) {
    t.setRow(noteRow({ customerNote: value })); await t.page.loadOrderDetail(81)
    assert.deepStrictEqual(render(section, t.page.data), [])
    assert.strictEqual(t.page.data.order.notes.length, 2)
  }
})
test('all note rows are text only with no event binding; existing phone/payment/accept actions remain', () => {
  const files = ['miniapp-user/components/OrderCard/index.wxml', ...['home', 'history', 'transfer', 'coordination', 'station-mgmt/orders'].map(f => `miniapp-delivery/pages/${f}/index.wxml`)]
  for (const file of files) for (const node of findNodes(file, n => n.attrs && n.attrs.class === 'customer-note-preview')) {
    assert.strictEqual(node.tag, 'view'); assert.ok(node.children.length > 0 && node.children.every(child => child.tag === '#text'))
    assert.ok(!Object.keys(node.attrs).some(k => /^(bind|catch)/.test(k)))
  }
  const home = read('miniapp-delivery/pages/home/index.wxml')
  for (const handler of ['onCallCustomer', 'onConfirmCollection', 'onAcceptOrder', 'onCompleteOrder']) assert.ok(home.includes('catchtap="' + handler + '"'))
  for (const handler of ['onPayNow', 'onCancel', 'onReorder']) assert.ok(read(files[0]).includes('catchtap="' + handler + '"'))
})
test('both preview styles enforce a single ellipsized line and employee detail preserves line breaks', () => {
  for (const file of ['miniapp-user/components/OrderCard/index.wxss', 'miniapp-delivery/app.wxss']) {
    const css = read(file).match(/\.customer-note-preview\s*{([^}]+)}/)[1]
    for (const rule of ['overflow: hidden', 'text-overflow: ellipsis', 'white-space: nowrap', 'min-width: 0']) assert.ok(css.includes(rule))
  }
  assert.ok(read('miniapp-delivery/pages/order/detail.wxss').match(/\.customer-note-full\s*{[^}]*white-space:\s*pre-wrap/s))
  assert.ok(read('miniapp-user/pages/order/detail.wxml').includes('{{order.customerNote}}'))
})

test('Astra transfer counterexample: courier 7 cannot preview courier 8, while own and manager records remain visible', async () => {
  const t = recordFixture('transfer')
  t.setRows([noteRow({ deliveryStaffId: 8 })]); await t.page.loadData()
  assert.strictEqual(t.page.data.orders[0].customerNotePreview, '')
  assert.deepStrictEqual(previewNodes('miniapp-delivery/pages/transfer/index.wxml', { item: t.page.data.orders[0] }), [])
  t.setRows([noteRow()]); await t.page.loadData()
  assert.strictEqual(t.page.data.orders[0].customerNotePreview, customerNotePreview(noteRow()))
  t.app.globalData.userInfo.role = 'STATION_MANAGER'
  t.setRows([noteRow({ deliveryStaffId: 8 })]); await t.page.loadData()
  assert.strictEqual(t.page.data.orders[0].customerNotePreview, customerNotePreview(noteRow()))
  assert.strictEqual(t.reads(), 3)
})
test('Astra history counterexample: a new station binding cannot preview the original station completed order', async () => {
  const t = recordFixture('history'), row = noteRow({ status: 4, paymentStatus: 2 })
  t.app.globalData.userInfo.stationId = 22; t.setRows([row]); await t.page.loadData()
  assert.strictEqual(t.page.data.orders[0].customerNotePreview, '')
  assert.deepStrictEqual(previewNodes('miniapp-delivery/pages/history/index.wxml', { item: t.page.data.orders[0] }), [])
  t.setRows([{ ...row, deliveryStationId: 22 }]); await t.page.loadData()
  assert.strictEqual(t.page.data.orders[0].customerNotePreview, customerNotePreview(row))
  assert.strictEqual(t.reads(), 2)
})
test('Astra manager counterexample: ownership does not authorize the other fulfillment station preview', async () => {
  const t = managerFixture()
  t.setRows([noteRow({ stationId: 11, deliveryStationId: 22 })]); await t.page.loadData()
  assert.strictEqual(t.page.data.list[0].customerNotePreview, '')
  assert.deepStrictEqual(previewNodes('miniapp-delivery/pages/station-mgmt/orders/index.wxml', { item: t.page.data.list[0] }), [])
  t.setRows([noteRow({ stationId: 22, deliveryStationId: 11 })]); await t.page.loadData()
  assert.strictEqual(t.page.data.list[0].customerNotePreview, customerNotePreview(noteRow()))
  assert.strictEqual(t.calls.length, 2)
})
test('missing or unconfirmed current identity never generates an employee preview', () => {
  const app = appFor(), user = app.globalData.userInfo
  global.getApp = () => app
  for (const identity of [null, {}, { ...user, staffId: undefined }, { ...user, staffId: 0 },
    { ...user, stationId: undefined }, { ...user, stationId: '' }, { ...user, role: undefined },
    { ...user, role: 'UNSELECTED' }, { ...user, role: 'CUSTOMER' }, { ...user, needSelectRole: true },
    { ...user, bindStatus: undefined }, { ...user, bindStatus: 'UNBOUND' }]) {
    app.globalData.userInfo = identity
    assert.strictEqual(employeeNotes.employeeCustomerNotePreview(noteRow()), '', JSON.stringify(identity))
  }
  app.globalData.userInfo = user
  for (const isLogin of [false, undefined, 'true']) {
    app.globalData.isLogin = isLogin; assert.strictEqual(employeeNotes.employeeCustomerNotePreview(noteRow()), '')
  }
  app.globalData.isLogin = true
  assert.strictEqual(employeeNotes.employeeCustomerNotePreview(noteRow()), customerNotePreview(noteRow()))
})
test('missing necessary order evidence and malformed IDs cannot be mistaken for authorized previews', () => {
  const app = appFor(); global.getApp = () => app
  for (const row of [null, {}, noteRow({ id: undefined }), noteRow({ id: 0 }), noteRow({ status: undefined }),
    noteRow({ status: 99 }), noteRow({ deliveryStaffId: null }), noteRow({ deliveryStaffId: {} }),
    noteRow({ deliveryStationId: null, stationId: undefined }), noteRow({ deliveryStationId: '' }),
    noteRow({ deliveryStationId: 0 }), noteRow({ deliveryStationId: [11] }),
    noteRow({ paymentMethod: undefined, paymentStatus: undefined })]) {
    assert.strictEqual(employeeNotes.employeeCustomerNotePreview(row), '', JSON.stringify(row))
  }
  for (const row of [noteRow({ deliveryStationId: null }), noteRow({ deliveryStationId: undefined }),
    noteRow({ id: '81', stationId: '11', deliveryStationId: '11', deliveryStaffId: '7' })]) {
    assert.strictEqual(employeeNotes.employeeCustomerNotePreview(row), customerNotePreview(row))
  }
})
test('employee visibility follows paid, cash or terminal evidence and never substitutes presentation labels', () => {
  const app = appFor(); global.getApp = () => app
  for (const status of [1, 2]) {
    for (const row of [noteRow({ status, paymentMethod: 1, paymentStatus: 0 }),
      noteRow({ status, paymentMethod: undefined, paymentStatus: undefined, payState: 'PAID', needCollect: false }),
      noteRow({ status, paymentMethod: [2], paymentStatus: {} })]) assert.strictEqual(employeeNotes.employeeCustomerNotePreview(row), '')
    for (const row of [noteRow({ status, paymentMethod: 1, paymentStatus: 2 }), noteRow({ status, paymentMethod: 2, paymentStatus: 1 })]) {
      assert.strictEqual(employeeNotes.employeeCustomerNotePreview(row), customerNotePreview(row))
    }
  }
  for (const status of [3, 4, 5]) {
    const row = noteRow({ status, paymentMethod: undefined, paymentStatus: undefined })
    assert.strictEqual(employeeNotes.employeeCustomerNotePreview(row), customerNotePreview(row))
  }
})
test('all four delivery home mappings apply the same detail access guard without extra reads', async () => {
  const t = homeFixture()
  for (const row of [noteRow({ deliveryStaffId: 8 }), noteRow({ deliveryStationId: 22 }),
    noteRow({ paymentMethod: 1, paymentStatus: 0 })]) {
    t.setRows([row]); await t.page.loadData(false)
    for (const key of ['assignedOrders', 'deliveringOrders', 'completedOrders', 'deliveredUnpaidOrders']) assert.strictEqual(t.page.data[key][0].customerNotePreview, '')
  }
  assert.strictEqual(t.calls.length, 15)
  t.setRows([noteRow()]); await t.page.loadData(false)
  for (const key of ['assignedOrders', 'deliveringOrders', 'completedOrders', 'deliveredUnpaidOrders']) assert.strictEqual(t.page.data[key][0].customerNotePreview, customerNotePreview(noteRow()))
})
test('all five coordination preview families recheck the current employee identity and fulfillment station', async () => {
  const t = coordinationFixture()
  await t.page.loadAllData()
  const keys = ['pending', 'approvalCustomer', 'approvalStation', 'directedIncoming', 'directedAccepted']
  for (const key of keys) assert.ok(t.page.data.lists[key][0].customerNotePreview, key)
  t.app.globalData.userInfo.stationId = 22; await t.page.loadAllData()
  for (const key of keys) for (const row of t.page.data.lists[key]) assert.strictEqual(row.customerNotePreview, '', key)
  t.app.globalData.userInfo = {}; await t.page.loadAllData()
  for (const key of keys) for (const row of t.page.data.lists[key]) assert.strictEqual(row.customerNotePreview, '', key)
})
test('employee detail also suppresses the new note section when its current identity cannot be confirmed', async () => {
  const t = detailFixture(); await t.page.loadOrderDetail(81)
  assert.ok(t.page.data.order.customerNotePreview)
  global.getApp().globalData.userInfo.staffId = null
  await t.page.loadOrderDetail(81)
  assert.strictEqual(t.page.data.order.customerNotePreview, '')
  assert.strictEqual(t.page.data.order.customerNote, noteRow().customerNote)
  assert.deepStrictEqual(t.page.data.order.notes, ['[分配] 内部记录', '[接单] 内部记录'])
})

async function main() {
  const done = armWatchdog(30000); let passed = 0
  try {
    for (const t of tests) { await t.run(); passed++; console.log('PASS ' + t.name) }
    console.log('AQUAFLOW_SUITE_OK ' + passed)
  } catch (e) { console.error(e.stack); process.exitCode = 1 } finally { done() }
}
module.exports = { cardFixture, homeFixture, recordFixture, managerFixture, coordinationFixture, detailFixture, noteRow, findNodes, ROOT }
if (require.main === module) main()
