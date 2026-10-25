'use strict'
// Execute real product handlers with synthetic APIs/storage; no HTTP, login or database.
const assert = require('node:assert/strict'), fs = require('node:fs'), path = require('node:path')
const { loadPage, createWx, createApp, armWatchdog, ROOT } = require('./harness')
const { parseWxml, validateConditionalSiblings } = require('./wxml-tree')
const repo = process.env.AQUAFLOW_REPO || ROOT, pagePath = path.join(repo, 'miniapp-delivery/pages/station-mgmt/products/index.js')
const tests = [], test = (name, run) => tests.push({ name, run })
const copy = value => JSON.parse(JSON.stringify(value))
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
const unknown = () => Object.assign(Error('synthetic response lost'), { requestMayHaveOccurred: true })
const row = (qty = 5, price = '50') => ({ qty: String(qty), price, deleted: false })
const item = (id = 17) => ({ id, selected: true, name: 'Synthetic ' + id, displayName: 'Synthetic ' + id,
  category: 1, price: 12, deposit: 50, quantity: 10, enabled: 1, ticketEnabled: 1, ticketPrice: 12 })

function fixture(shared) {
  const wx = shared ? shared.wx : createWx(), app = shared ? shared.app : createApp({ globalData: {
    isLogin: true, userInfo: { staffId: 1, stationId: 11, role: 'STATION_MANAGER' } } })
  const state = { stock: 10 }, calls = [], packages = [], receipts = new Map()
  const api = {
    updateCatalogSetting: async (id, body) => { calls.push(['setting', id, copy(body)]); return state.setting ? state.setting(id, body) : { code: 0 } },
    updateMyProduct: async (id, body) => { calls.push(['edit', id, copy(body)]); return state.edit ? state.edit(id, body) : { code: 0 } },
    createMyProduct: async body => { calls.push(['create', copy(body)]); return state.create ? state.create(body) : { code: 0, data: 91 } },
    inboundProducts: async (station, items, key) => {
      calls.push(['inbound', station, copy(items), key])
      if (state.inbound) return state.inbound(station, items, key)
      if (!receipts.has(key)) { state.stock += items[0].quantity; receipts.set(key, { code: 0, data: { status: 'APPLIED', stationId: station, items: copy(items), clientRequestId: key } }) }
      return receipts.get(key)
    }
  }
  let draft = 0
  const page = loadPage(pagePath, { wx, app, stubs: {
    'config/api': { API: {}, getTransportConfig: () => ({ mode: 'https', baseUrl: 'https://synthetic.invalid' }) },
    'api/station-mgmt': api,
    'utils/request': { get: async () => ({ code: 0, data: [] }), post: async (url, body) => {
      packages.push(copy(body)); return state.package ? state.package(body, packages.length) : { code: 0 }
    }, del: async url => { packages.push({ delete: url }); return { code: 0 } } },
    'utils/upload-recovery-page': { wrapUploadRecovery: page => page },
    'utils/upload': { uploadDraft: () => 'synthetic-' + (++draft), isUploadSessionCurrent: () => true, chooseImages: () => {} }
  } })
  page.setData = function (patch, cb) {
    for (const [key, value] of Object.entries(patch)) {
      const keys = key.replace(/\[(\d+)\]/g, '.$1').split('.'); let target = this.data
      for (const part of keys.slice(0, -1)) target = target[part]
      target[keys.at(-1)] = value
    }
    if (cb) cb()
  }
  page.loadPkgRows = async () => {}
  page.loadData = async () => { state.reads = (state.reads || 0) + 1; page.setData({ list: [{ ...item(), quantity: state.stock }], loadError: '' }) }
  page.setData({ stationId: 11 })
  const openSetting = (id = 17) => { page.onOpenSetting({ currentTarget: { dataset: { item: item(id) } } }); page.setData({ pkgReady: true, pkgRows: [row()] }) }
  const openEdit = (id = 17) => { page.openEdit({ currentTarget: { dataset: { item: item(id) } } }); page.setData({ pkgReady: true, pkgRows: [row()] }) }
  const openAdd = () => { page.openAdd(); page.setData({ 'editForm.name': 'Synthetic new', 'editForm.price': '12', 'editForm.deposit': '50', 'editForm.ticketEnabled': true, pkgRows: [row()] }) }
  return { page, wx, app, state, calls, packages, openSetting, openEdit, openAdd }
}
const count = (t, kind) => t.calls.filter(call => call[0] === kind).length

test('setting save is single flight and blocks close or change target', async () => {
  const t = fixture(), gate = deferred(); t.openSetting(); t.state.setting = () => gate.promise
  const pending = t.page.onSaveSetting(); t.page.closeSetting(); t.openSetting(18); const repeated = t.page.onSaveSetting()
  assert.equal(t.page.data.setting.id, 17); assert.equal(t.page.data.showSetting, true); assert.equal(count(t, 'setting'), 1)
  gate.resolve({ code: 0 }); await Promise.all([pending, repeated])
  assert.deepEqual(t.packages, [{ productId: 17, qty: 5, price: 50 }]); assert.equal(t.page.data.showSetting, false)
})
test('bypassed target change after setting await cannot attach new rows or close new editor', async () => {
  const t = fixture(), gate = deferred(); t.openSetting(); t.state.setting = () => gate.promise
  const pending = t.page.onSaveSetting()
  t.page.setData({ setting: { ...t.page.data.setting, id: 18 }, pkgRows: [row(9, '90')], showSetting: true })
  gate.resolve({ code: 0 }); await pending
  assert.deepEqual(t.packages, []); assert.equal(t.page.data.setting.id, 18); assert.equal(t.page.data.showSetting, true)
})
test('bypassed target change after custom update await cannot save new rows onto old product', async () => {
  const t = fixture(), gate = deferred(); t.openEdit(); t.state.edit = () => gate.promise
  const pending = t.page.onSaveEdit(); t.page.setData({ editId: 18, editForm: { ...t.page.data.editForm, name: 'New editor' }, pkgRows: [row(9, '90')] })
  gate.resolve({ code: 0 }); await pending
  assert.deepEqual(t.packages, []); assert.equal(t.page.data.showEdit, true); assert.equal(t.page.data.editId, 18)
})
test('live package mutation during base save cannot replace the snapshot', async () => {
  const t = fixture(), gate = deferred(); t.openSetting(); t.state.setting = () => gate.promise
  const pending = t.page.onSaveSetting(); t.page.data.pkgRows[0].price = '999'
  gate.resolve({ code: 0 }); await pending
  assert(!t.packages.some(body => body.price === 999)); assert.equal(t.page.data.showSetting, true)
})
test('identity, station, login generation or permission change stops later package writes', async () => {
  for (const change of [t => { t.app.globalData.userInfo.staffId = 2 }, t => { t.app.globalData.userInfo.stationId = 12 },
    t => { t.app._loginGeneration = 1 }, t => { t.app.canAccessStationBusiness = () => false }]) {
    const t = fixture(), gate = deferred(); t.openSetting(); t.state.setting = () => gate.promise
    const pending = t.page.onSaveSetting(); change(t); gate.resolve({ code: 0 }); await pending
    assert.deepEqual(t.packages, []); assert.equal(t.page.data.showSetting, true)
  }
})
test('identity changed before save cannot dispatch an old open editor under the new actor', async () => {
  const t = fixture(); t.openEdit(); t.app.globalData.userInfo.staffId = 2
  await t.page.onSaveEdit(); assert.equal(count(t, 'edit'), 0)
  const s = fixture(); s.openSetting(); s.app.globalData.userInfo.staffId = 2
  await s.page.onSaveSetting(); await s.page.doInbound(5); assert.equal(s.calls.length, 0)
})
test('token renewal within the same identity does not discard a valid snapshot', async () => {
  const t = fixture(), gate = deferred(); t.openSetting(); t.state.setting = () => gate.promise
  const pending = t.page.onSaveSetting(); t.app.globalData.accessToken = 'synthetic-renewed'
  gate.resolve({ code: 0 }); await pending; assert.equal(t.packages.length, 1)
})
test('created id survives package failure; retry only continues that id with original rows', async () => {
  const t = fixture(); t.openAdd(); t.state.package = () => { throw Object.assign(Error('synthetic package rejection'), { businessRejected: true }) }
  await t.page.onSaveEdit(); assert.equal(count(t, 'create'), 1)
  assert.equal(t.page.data.editId, 91); assert.equal(t.page.data.isAdd, false); assert.equal(t.page.data.productCreationRecovery.productId, 91)
  t.app.globalData.userInfo.bindStatus = 'BOUND'
  assert.equal(t.page.syncProductCreationRecovery().productId, 91, 'permission metadata changes retain the same actor/station recovery')
  t.page.setData({ pkgRows: [row(99, '999')] }); t.state.package = null
  await t.page.onSaveEdit(); assert.equal(count(t, 'create'), 1)
  assert.deepEqual(t.packages.at(-1), { productId: 91, qty: 5, price: 50 }); assert.equal(t.page.data.productCreationRecovery, null)
})
test('partial package progress resumes after the last confirmed row and survives page recreation', async () => {
  const t = fixture(); t.openAdd(); t.page.setData({ pkgRows: [row(), row(10, '90')] })
  t.state.package = (body, index) => { if (index === 2) throw unknown(); return { code: 0 } }
  await t.page.onSaveEdit(); assert.equal(count(t, 'create'), 1)
  const next = fixture(t); next.openAdd(); await next.page.onContinueCreatedProduct()
  assert.equal(count(next, 'create'), 0); assert.deepEqual(next.packages, [{ productId: 91, qty: 10, price: 90 }])
  assert.equal(next.page.data.productCreationRecovery, null)
})
test('trusted create response after identity change retains only the original owner recovery and never touches the new editor', async () => {
  const t = fixture(), gate = deferred(); t.openAdd(); t.state.create = () => gate.promise
  const pending = t.page.onSaveEdit(); t.app.globalData.userInfo.staffId = 2
  t.page.setData({ isAdd: false, editId: 18, editForm: { ...t.page.data.editForm, name: 'New owner editor' }, pkgRows: [row(9, '90')] })
  gate.resolve({ code: 0, data: 91 }); await pending
  assert.equal(t.page.data.editId, 18); assert.equal(t.page.data.showEdit, true); assert.deepEqual(t.packages, [])
  assert.equal(t.page.syncProductCreationRecovery(), null)
  t.app.globalData.userInfo.staffId = 1; assert.equal(t.page.syncProductCreationRecovery().productId, 91)
})
test('unknown or malformed create never guesses a catalog id or blindly creates again; existing edit remains usable', async () => {
  for (const response of [() => { throw unknown() }, () => ({ code: 0, data: { id: 91 } })]) {
    const t = fixture(); t.openAdd(); t.state.create = response
    await t.page.onSaveEdit(); await t.page.onSaveEdit(); assert.equal(count(t, 'create'), 1)
    assert.equal(t.page.data.productCreationRecovery.phase, 'create-unknown'); assert.equal(t.page.data.productCreationRecovery.productId, null)
    await t.page.onVerifyProductCreation(); assert.equal(t.page.data.productCreationRecovery.productId, null)
    t.openEdit(18); await t.page.onSaveEdit(); assert.equal(count(t, 'edit'), 1); assert.equal(count(t, 'create'), 1)
  }
})
test('clear business rejection permits a corrected new create; unrecorded intent never dispatches', async () => {
  const t = fixture(); t.openAdd(); t.state.create = () => { throw Object.assign(Error('synthetic validation rejection'), { businessRejected: true }) }
  await t.page.onSaveEdit(); assert.equal(t.page.data.productCreationRecovery || null, null)
  t.state.create = null; await t.page.onSaveEdit(); assert.equal(count(t, 'create'), 2)
  const bad = fixture(); bad.openAdd(); bad.wx.setStorageSync = () => { throw Error('synthetic full storage') }
  await bad.page.onSaveEdit(); assert.equal(count(bad, 'create'), 0)
})
test('failure to persist a trusted created id retains it in memory and cannot reopen create', async () => {
  const t = fixture(); t.openAdd(); const store = t.wx.setStorageSync
  t.wx.setStorageSync = (key, value) => { if (value.productId) throw Error('synthetic storage failure after receipt'); store(key, value) }
  await t.page.onSaveEdit(); assert.equal(t.page.data.productCreationRecovery.productId, 91)
  await t.page.onSaveEdit(); assert.equal(count(t, 'create'), 1)
  assert(t.packages.every(body => body.productId === 91))
})
test('inbound dispatches one durable original intent and matching receipt refreshes authoritative stock', async () => {
  const t = fixture(), gate = deferred(); t.openSetting(); t.state.inbound = () => gate.promise
  const pending = t.page.doInbound(5), repeated = t.page.doInbound(9)
  assert.equal(count(t, 'inbound'), 1); const [, station, items, key] = t.calls.find(c => c[0] === 'inbound')
  assert.equal(station, 11); assert.deepEqual(items, [{ productId: 17, quantity: 5 }]); assert.match(key, /^INB-/)
  t.state.stock = 15; gate.resolve({ code: 0, data: { status: 'APPLIED', stationId: station, items, clientRequestId: key } }); await Promise.all([pending, repeated])
  assert.equal(t.page.data.setting.quantity, 15); assert.equal(t.page.data.inboundRecovery, null)
})
test('lost inbound response replays same key and original quantity, never double increments or replaces with new input', async () => {
  const t = fixture(); t.openSetting(); let key, receipt
  t.state.inbound = (station, items, incoming) => {
    if (!key) { key = incoming; t.state.stock += items[0].quantity; receipt = { code: 0, data: { status: 'APPLIED', stationId: station, items: copy(items), clientRequestId: key } }; throw unknown() }
    assert.equal(incoming, key); return receipt
  }
  await t.page.doInbound(5); assert.equal(t.page.data.inboundRecovery.quantity, 5)
  t.page.onOpenInventory({ currentTarget: { dataset: { item: item() } } })
  assert.equal(t.page.data.showInventory, true); assert.equal(t.page.data.stockMode, 'in')
  t.page.setData({ stockInput: '9' }); await t.page.onSubmitStock()
  const calls = t.calls.filter(c => c[0] === 'inbound'); assert.equal(calls.length, 2)
  assert.deepEqual(calls[1].slice(1), calls[0].slice(1)); assert(t.wx.__calls.modal.at(-1).content.includes('5 件'))
  assert.equal(t.state.stock, 15); assert.equal(t.page.data.setting.quantity, 15)
})
test('inbound recovery survives reentry and requires a full matching receipt, including legacy and business failures', async () => {
  for (const bad of [() => ({ code: 0 }), (s, items, key) => ({ code: 0, data: { status: 'APPLIED', stationId: s, items, clientRequestId: key + 'wrong' } }),
    () => { throw Object.assign(Error('synthetic permission rejection'), { businessRejected: true }) }]) {
    const t = fixture(); t.openSetting(); t.state.inbound = bad; await t.page.doInbound(5)
    const first = t.calls.find(c => c[0] === 'inbound'); assert(t.page.data.inboundRecovery); assert.equal(t.page.data.setting.quantity, 10)
    const next = fixture(t); next.openSetting(); assert.equal(next.page.data.inboundRecovery.quantity, 5)
    await next.page.doInbound(99); const second = next.calls.find(c => c[0] === 'inbound')
    assert.deepEqual(second.slice(1), first.slice(1)); assert.equal(next.page.data.inboundRecovery, null)
  }
})
test('inbound confirmation cancel, hide, identity or permission change cannot issue the pending replay', async () => {
  for (const action of ['cancel', 'hide', 'identity', 'permission']) {
    const t = fixture(); t.openSetting(); t.state.inbound = () => { throw unknown() }; await t.page.doInbound(5)
    let modal; t.wx.showModal = options => { modal = options }; const pending = t.page.doInbound(9)
    if (action === 'hide') t.page.onHide()
    else { if (action === 'identity') t.app.globalData.userInfo.staffId = 2; if (action === 'permission') t.app.canAccessStationBusiness = () => false; modal.success({ confirm: action !== 'cancel' }) }
    await pending; assert.equal(count(t, 'inbound'), 1)
  }
})
test('inbound storage failure blocks first dispatch; receipt cleanup failure retains the original replay key', async () => {
  const bad = fixture(); bad.openSetting(); bad.wx.setStorageSync = () => { throw Error('synthetic full storage') }
  await bad.page.doInbound(5); assert.equal(count(bad, 'inbound'), 0)
  const t = fixture(); t.openSetting(); const remove = t.wx.removeStorageSync
  t.wx.removeStorageSync = () => { throw Error('synthetic cleanup failure') }; await t.page.doInbound(5)
  assert(t.page.data.inboundRecovery); t.wx.removeStorageSync = remove; await t.page.doInbound(9)
  const calls = t.calls.filter(c => c[0] === 'inbound'); assert.equal(calls.length, 2); assert.deepEqual(calls[1].slice(1), calls[0].slice(1))
  assert.equal(t.state.stock, 15); assert.equal(t.page.data.setting.quantity, 15)
})
test('product recovery controls remain reachable in balanced conditional WXML', () => {
  const source = fs.readFileSync(pagePath.replace(/\.js$/, '.wxml'), 'utf8')
  validateConditionalSiblings(parseWxml(source))
  assert(source.includes('bindtap="onContinueCreatedProduct"')); assert(source.includes('bindtap="onContinueOriginalInbound"'))
})

async function main() {
  const done = armWatchdog(30000), results = []
  try {
    for (const t of tests) {
      try { await t.run(); results.push({ name: t.name, pass: true }); console.log('PASS ' + t.name) }
      catch (error) { results.push({ name: t.name, pass: false, error: error.message }); console.log('FAIL ' + t.name + ': ' + error.message) }
    }
    if (process.env.REVIEW_OUTPUT) fs.writeFileSync(process.env.REVIEW_OUTPUT, JSON.stringify(results, null, 2))
    console.log('EXTREME_PRODUCT_SAVE ' + results.filter(r => r.pass).length + '/' + results.length)
    if (results.some(r => !r.pass)) process.exitCode = 1
    else console.log('AQUAFLOW_SUITE_OK ' + results.length)
  } finally { done() }
}
if (require.main === module) main().catch(error => { console.error(error); process.exitCode = 1 })
module.exports = { fixture }
