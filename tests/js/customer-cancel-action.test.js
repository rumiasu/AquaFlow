const assert = require('assert'), fs = require('fs'), path = require('path'), Module = require('module')
const { loadPage, createWx, createApp, armWatchdog, ROOT } = require('./harness')
const { cancelView, cancelResultText } = require('../../miniapp-user/utils/customer-cancel-view')
const done = armWatchdog(30000), tests = [], test = (name, run) => tests.push({ name, run })
const tick = () => new Promise(resolve => setImmediate(resolve))
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
const order = status => ({ id: 20, stationId: 1, status, canCancel: status === 1 || status === 2, canRepay: false, items: [] })
function fixture(type, status = 2, options = {}) {
  const wx = createWx(), app = createApp(), calls = { cancel: [], events: [], read: 0 }
  let fresh = order(status), readError = null
  const api = {
    cancelOrder: id => { calls.cancel.push(id); return options.cancel ? options.cancel(id) : Promise.resolve({ code: 0, data: null }) },
    getOrderDetail: async () => { calls.read++; if (readError) throw readError; if (options.read) return options.read(); return { data: fresh } }
  }
  const stubs = { 'api/order': api, 'api/orderImage': { getOrderImages: async () => ({ data: [] }) },
    'api/product': { getProductDetail: async () => ({ data: {} }) }, 'api/station': { getStationPublicPhone: async () => ({ data: {} }) } }
  // Also intercept the original component's lazy require inside the modal callback.
  const apiFile = require.resolve('../../miniapp-user/api/order')
  require.cache[apiFile] = { id: apiFile, filename: apiFile, loaded: true, exports: api }
  let page, config
  if (type === 'detail') {
    page = loadPage('miniapp-user/pages/order/detail.js', { wx, app, stubs })
  } else {
    global.wx = wx; global.getApp = () => app; global.Component = c => { config = c }
    const read = Module._load
    Module._load = function (request) { for (const k of Object.keys(stubs)) if (request === k || request.endsWith(k)) return stubs[k]; return read.apply(this, arguments) }
    try { const file = path.join(ROOT, 'miniapp-user/components/OrderCard/index.js'); delete require.cache[require.resolve(file)]; require(file) } finally { Module._load = read }
    page = { data: JSON.parse(JSON.stringify(config.data || {})), setData(p) { Object.assign(this.data, p) }, triggerEvent(name, payload) { calls.events.push({ name, payload }) } }
    for (const [name, run] of Object.entries(config.methods || {})) page[name] = run.bind(page)
    page.update = row => { page.data.order = row; config.observers.order.call(page, row) }
    page.detach = () => { if (config.lifetimes && config.lifetimes.detached) config.lifetimes.detached.call(page) }
    page.update(fresh)
  }
  return { page, wx, app, calls, setFresh(row) { fresh = row }, setReadFailure() { readError = new Error('offline') }, async ready() { if (type === 'detail') await page.loadOrder(20) } }
}
test('server permission is required even when the order is pending', () => {
  for (const canCancel of [false, undefined, null, 'true', 1]) assert.equal(cancelView({ status: 1, canCancel }).canCancel, false)
  assert.equal(cancelView(order(1)).canCancel, true)
})
test('delivery means an application with explicit approval wording', () => {
  const v = cancelView(order(2)); assert.equal(v.label, '申请取消'); assert.equal(v.confirmText, '提交申请'); assert.ok(v.content.includes('不代表订单已取消')); assert.ok(v.content.includes('等待水站处理'))
})
test('pending means direct cancellation without claiming a refund arrived', () => {
  const v = cancelView(order(1)); assert.equal(v.label, '取消订单'); assert.ok(v.content.includes('不代表退款已到账'))
})
test('fresh cancellation result distinguishes cancelled, requested and unknown', () => {
  assert.equal(cancelResultText(order(5)), '订单已取消'); assert.equal(cancelResultText(order(2)), '取消申请已提交'); assert.ok(cancelResultText(null).includes('刷新确认')); assert.ok(cancelResultText(order(1)).includes('刷新确认'))
})
test('customer sees pending, rejected, approved and automatic delivery-close results from the server', async () => {
  const { parseWxml, renderElements }=require('./wxml-tree')
  const tree=parseWxml(fs.readFileSync(path.join(ROOT,'miniapp-user/pages/order/detail.wxml'),'utf8'))
  for(const [status,resultStatus,statusText,note] of [[2,'PENDING','取消申请待水站处理','申请不代表取消'],[2,'REJECTED','取消申请未获同意','水站未同意'],[5,'APPROVED','取消申请已获同意','退款以实际结果为准'],[4,'REJECTED','取消申请未获同意','完成配送，本次申请未生效']]) {
    const t=fixture('detail',status),row={...order(status),canCancel:false,customerCancelRequest:{status:resultStatus,statusText,resultNote:note,handledTime:'2026-10-08T08:00:00'}}
    t.setFresh(row);await t.ready();assert.equal(t.page.data.order.customerCancelRequest.resultNote,note)
    const nodes=renderElements(tree,t.page.data,{includeText:true});assert(nodes.some(n=>n.attrs.class==='section-title' && n.text===statusText));assert(nodes.some(n=>n.text===note))
    t.page.onCancel();await tick();assert.equal(t.calls.cancel.length,0)
  }
})
for (const type of ['card', 'detail']) {
  test(type + ' binds canCancel and the action label from the shared helper', async () => {
    const t = fixture(type); await t.ready(); assert.equal(t.page.data.canCancel, true); assert.equal(t.page.data.cancelLabel, '申请取消')
    const file = type === 'card' ? 'miniapp-user/components/OrderCard/index.wxml' : 'miniapp-user/pages/order/detail.wxml'
    const wxml = fs.readFileSync(path.join(ROOT, file), 'utf8'); assert.ok(wxml.includes('wx:if="{{canCancel}}"')); assert.ok(wxml.includes('{{cancelLabel}}'))
  })
  test(type + ' shows a request modal and confirms only the fresh request result', async () => {
    const t = fixture(type); await t.ready(); t.page.onCancel(); await tick(); await tick()
    assert.equal(t.wx.__calls.modal[0].confirmText, '提交申请'); assert.ok(t.wx.__calls.modal[0].content.includes('等待水站处理'))
    assert.deepStrictEqual(t.calls.cancel, [20]); assert.equal(t.wx.__calls.toast.at(-1).title, '取消申请已提交')
  })
  test(type + ' direct cancellation success is based on a fresh cancelled order', async () => {
    const t = fixture(type, 1); await t.ready(); t.setFresh(order(5)); t.page.onCancel(); await tick(); await tick()
    assert.equal(t.wx.__calls.modal[0].confirmText, '确认取消'); assert.equal(t.wx.__calls.toast.at(-1).title, '订单已取消')
  })
  test(type + ' a pending-to-delivering race does not claim direct cancellation', async () => {
    const t = fixture(type, 1); await t.ready(); t.setFresh(order(2)); t.page.onCancel(); await tick(); await tick()
    assert.equal(t.wx.__calls.toast.at(-1).title, '取消申请已提交')
  })
  test(type + ' unavailable permission prevents programmatic cancellation', async () => {
    const t = fixture(type, 1); t.setFresh({ ...order(1), canCancel: false }); if (type === 'card') t.page.update({ ...order(1), canCancel: false }); await t.ready()
    t.page.onCancel(); await tick(); assert.equal(t.calls.cancel.length, 0); assert.equal(t.wx.__calls.modal.length, 0)
  })
  test(type + ' rapid repeated clicks make one mutation', async () => {
    const d = deferred(), t = fixture(type, 2, { cancel: () => d.promise }); await t.ready(); t.page.onCancel(); t.page.onCancel()
    try { assert.equal(t.calls.cancel.length, 1); assert.equal(t.wx.__calls.modal.length, 1) } finally { d.resolve({ code: 0 }); await tick(); await tick() }
  })
  test(type + ' changing order while the modal is open prevents a wrong mutation', async () => {
    const t = fixture(type); await t.ready(); let modal; t.wx.showModal = x => { modal = x }; t.page.onCancel()
    if (type === 'card') t.page.update({ ...order(2), id: 21 }); else await t.page.loadOrder(21)
    // The detail test deliberately returns another id: it becomes an error and must not cancel either id.
    modal.success({ confirm: true }); await tick(); assert.equal(t.calls.cancel.length, 0)
  })
  test(type + ' switching customer during confirmation prevents mutation', async () => {
    const t = fixture(type); await t.ready(); let modal; t.wx.showModal = x => { modal = x }; t.page.onCancel(); t.app.globalData.customerId = 8
    modal.success({ confirm: true }); await tick(); assert.equal(t.calls.cancel.length, 0)
  })
  for (const field of ['id', 'status']) test(type + ' in-place ' + field + ' change cannot alter the captured modal target', async () => {
    const t = fixture(type); await t.ready(); let modal; t.wx.showModal = x => { modal = x }; t.page.onCancel()
    t.page.data.order[field] = field === 'id' ? 21 : 1
    modal.success({ confirm: true }); await tick(); assert.equal(t.calls.cancel.length, 0)
  })
  test(type + ' same-customer new login cycle discards a late successful cancellation', async () => {
    const d = deferred(), t = fixture(type, 2, { cancel: () => d.promise }); await t.ready(); t.page.onCancel()
    require(path.join(ROOT, 'miniapp-user/utils/token')).beginSession(); d.resolve({ code: 0 }); await tick(); await tick()
    assert.equal(t.wx.__calls.toast.length, 0); assert.equal(t.calls.events.length, 0)
  })
  test(type + ' a failed follow-up read never invents a cancelled outcome', async () => {
    const t = fixture(type, 1); await t.ready(); t.setReadFailure()
    t.page.onCancel(); await tick(); await tick(); assert.ok(t.wx.__calls.toast.at(-1).title.includes('刷新确认'))
  })
  test(type + ' server rejection stays a failure and does not emit success', async () => {
    const t = fixture(type, 2, { cancel: async () => { throw new Error('已提交取消申请，请等待水站处理') } }); await t.ready(); t.page.onCancel(); await tick(); await tick()
    assert.equal(t.wx.__calls.toast.at(-1).title, '已提交取消申请，请等待水站处理'); assert.equal(t.calls.events.length, 0)
  })
}
test('delivered and final orders remain unavailable using actual backend permissions', async () => {
  const t = fixture('card')
  for (const status of [3, 4, 5]) { t.page.update(order(status)); assert.equal(t.page.data.canCancel, false); t.page.onCancel() }
  await tick(); assert.equal(t.calls.cancel.length, 0)
})
test('detached component does not emit a late cancellation result', async () => {
  const d = deferred(), t = fixture('card', 2, { cancel: () => d.promise }); t.page.onCancel(); t.page.detach(); d.resolve({ code: 0 }); await tick(); await tick()
  assert.equal(t.wx.__calls.toast.length, 0); assert.equal(t.calls.events.length, 0)
})
test('already logged-out customer cannot use a stale card permission', async () => {
  const t = fixture('card'); t.app.globalData.isLogin = false; t.page.onCancel(); await tick()
  assert.equal(t.calls.cancel.length, 0); assert.equal(t.wx.__calls.modal.length, 0)
})
;(async () => {
  let passed = 0, failed = 0
  for (const t of tests) { try { await t.run(); passed++; console.log('PASS ' + t.name) } catch (e) { failed++; console.error('FAIL ' + t.name + ' [' + e.name + ': ' + e.message + ']') } }
  console.log(`customer cancel: ${passed} passed, ${failed} failed`)
  if (failed) process.exitCode = 1; else console.log('AQUAFLOW_SUITE_OK ' + passed)
  done()
})()
