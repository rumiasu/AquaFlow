'use strict'
// Actual Page handlers with synthetic APIs/storage only; no business network or database.
const assert = require('assert'), fs = require('fs'), path = require('path')
const { loadPage, createWx, createApp, armWatchdog, ROOT } = require('./harness')
const { parseWxml, renderElements } = require('./wxml-tree')
const findNode = (node, predicate) => predicate(node) ? node : (node.children || []).map(child => findNode(child, predicate)).find(Boolean)
const sourceRoot = process.env.QWEN_BASELINE_ROOT || ROOT
const done = armWatchdog(30000), tests = [], test = (name, run) => tests.push({ name, run })
const tick = () => new Promise(resolve => setImmediate(resolve))
const event = id => ({ currentTarget: { dataset: { id } } })
function deferred() { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
function fixture(kind = 'detail', options = {}) {
  const wx = createWx(), modals = [], calls = { writes: [], reads: [] }
  wx.showModal = value => modals.push(value)
  const app = createApp({ _loginGeneration: 1, globalData: { isLogin: true, accessToken: 'synthetic',
    userInfo: { role: 'DELIVERY', staffId: 8, stationId: 1, bindStatus: 'BOUND' } } })
  const order = { id: 20, status: 3, stationId: 1, deliveryStaffId: 8, paymentMethod: 2,
    paymentStatus: 1, needCollect: true, totalAmount: '48.25', items: [], statusText: '已送达', ...options.order }
  const api = {
    getOrderDetail: async id => { calls.reads.push(id); return options.read ? options.read(id) : { code: 0, data: order } },
    confirmCollection: async id => { calls.writes.push(id); return options.write ? options.write(id) : { code: 0 } }
  }
  for (const method of ['getAssignedToMe', 'getDeliveringOrders', 'getCompletedToday', 'getDeliveredUnpaid', 'getTransferList', 'getPendingOrders']) {
    api[method] = async () => ({ code: 0, data: method === 'getDeliveredUnpaid' ? [order] : [] })
  }
  const request = { get: async () => ({ code: 0, data: [] }), put: async () => ({}), post: async () => ({}) }
  function pageOf(type) {
    const page = loadPage(path.join(sourceRoot, 'miniapp-delivery/pages/' + (type === 'detail' ? 'order/detail.js' : 'home/index.js')),
      { wx, app, stubs: { 'api/delivery': api, 'utils/request': request, 'utils/tabbar': { syncTabBar() {} } } })
    if (type === 'detail') page.setData({ orderId: 20, order, loading: false, hasMoreActions: true })
    else page.setData({ deliveredUnpaidOrders: [order], deliveringOrders: [], loading: false })
    return page
  }
  const page = pageOf(kind)
  return { kind, page, pageOf, wx, app, order, calls, modals }
}
async function start(t) {
  const pending = t.kind === 'detail' ? t.page.onConfirmCollection() : t.page.onConfirmCollection(event(20))
  await tick()
  return { pending, modal: t.modals[t.modals.length - 1] }
}
async function submit(t) { const attempt = await start(t); assert.ok(attempt.modal, 'confirmation required'); attempt.modal.success({ confirm: true }); await tick(); return attempt }
function render(t, relative) {
  const wxml = fs.readFileSync(path.join(sourceRoot, relative), 'utf8')
  return renderElements(parseWxml(wxml), t.page.data, { includeText: true })
}

for (const kind of ['detail', 'home']) {
  test(kind + ' confirmation names exact amount and cash bookkeeping consequence', async () => {
    const t = fixture(kind), { pending, modal } = await start(t)
    assert.ok(modal.content.includes('¥48.25')); assert.ok(modal.content.includes('不会向客户扣款'))
    assert.ok(modal.content.includes('请勿再次')); modal.success({ confirm: false }); await pending
  })
  for (const value of [null, '', ' ', false, 'garbage', -1, '12.345', Infinity]) {
    test(kind + ' blocks invalid collection amount ' + JSON.stringify(value), async () => {
      const t = fixture(kind, { order: { totalAmount: value } }), { pending } = await start(t)
      assert.equal(t.modals.length, 0); await pending; assert.equal(t.calls.writes.length, 0)
      assert.ok(t.wx.__calls.toast.some(value => value.title.includes('金额待核实')))
    })
  }
  test(kind + ' duplicate taps while confirming and writing produce exactly one request', async () => {
    const d = deferred(), t = fixture(kind, { write: () => d.promise })
    const attempt = await start(t)
    const second = kind === 'detail' ? t.page.onConfirmCollection() : t.page.onConfirmCollection(event(20))
    await tick(); assert.equal(t.modals.length, 1)
    attempt.modal.success({ confirm: true }); await tick()
    const third = kind === 'detail' ? t.page.onConfirmCollection() : t.page.onConfirmCollection(event(20))
    await tick(); const count = t.calls.writes.length
    d.resolve({ code: 0 }); await Promise.all([attempt.pending, second, third]); await tick()
    assert.equal(count, 1); assert.equal(t.calls.writes.length, 1)
  })
  test(kind + ' timeout keeps unknown lock after GET still reports unpaid', async () => {
    const t = fixture(kind, { write: async () => { throw Object.assign(Error('timeout'), { requestMayHaveOccurred: true }) } })
    const attempt = await submit(t); await attempt.pending; await tick()
    if (kind === 'detail') {
      assert.equal(t.page.data.writeUnknown, true); await t.page.onVerifyOrderWrite()
      assert.equal(t.page.data.writeUnknown, true); await t.page.onConfirmCollection()
    } else {
      assert.equal(t.page.data.collectionRecoveryItems.length, 1); await t.page.onVerifyCollection(event(20))
      assert.equal(t.page.data.collectionRecoveryItems.length, 1); await t.page.onConfirmCollection(event(20))
    }
    assert.equal(t.modals.length, 1); assert.equal(t.calls.writes.length, 1)
  })
  test(kind + ' known rejection clears lock and permits a new confirmation', async () => {
    const t = fixture(kind, { write: async () => { throw Error('确定业务拒绝') } })
    const first = await submit(t); await first.pending; await tick()
    assert.ok(t.wx.__calls.toast.some(value => value.title === '确定业务拒绝'))
    const next = await start(t); assert.equal(t.modals.length, 2); next.modal.success({ confirm: false }); await next.pending
  })
  for (const change of ['hide', 'identity', 'order']) test(kind + ' old confirmation cannot submit after ' + change, async () => {
    const t = fixture(kind), first = await start(t)
    if (change === 'hide') t.page.onHide()
    else if (change === 'identity') t.app._loginGeneration++
    else if (kind === 'detail') t.page.setData({ orderId: 21 })
    else { t.page.onHide(); t.page._homeIdentityHidden = false }
    first.modal.success({ confirm: true }); await first.pending; await tick()
    assert.equal(t.calls.writes.length, 0)
  })
}

test('unknown collection survives detail unload, homepage reentry, token renewal and current paid verification', async () => {
  const t = fixture('detail', { write: async () => { throw Object.assign(Error('timeout'), { requestMayHaveOccurred: true }) } })
  const attempt = await submit(t); await attempt.pending; t.page.onUnload()
  t.app.globalData.accessToken = 'renewed-synthetic'
  const home = t.pageOf('home'); await home.loadData(false)
  assert.equal(home.data.collectionRecoveryItems.length, 1)
  await home.onConfirmCollection(event(20)); assert.equal(t.calls.writes.length, 1)
  t.order.paymentStatus = 2; t.order.needCollect = false; t.order.status = 4
  await home.onVerifyCollection(event(20))
  assert.equal(home.data.collectionRecoveryItems.length, 0); assert.equal(t.calls.writes.length, 1)
  assert.ok(t.wx.__calls.toast.some(value => value.title.includes('请勿再次收款')))
})
test('unknown record is isolated from another staff member and returns for original staff', async () => {
  const t = fixture('detail', { write: async () => { throw Object.assign(Error('timeout'), { requestMayHaveOccurred: true }) } })
  const attempt = await submit(t); await attempt.pending
  t.app.globalData.userInfo.staffId = 9
  const home = t.pageOf('home'); await home.loadData(false); assert.equal(home.data.collectionRecoveryItems.length, 0)
  t.app.globalData.userInfo.staffId = 8; await home.loadData(false); assert.equal(home.data.collectionRecoveryItems.length, 1)
})
test('in-flight homepage request also blocks detail collection until it settles unknown', async () => {
  const d = deferred(), t = fixture('home', { write: () => d.promise }), attempt = await submit(t)
  t.page.onHide(); const detail = t.pageOf('detail'); await detail.loadOrderDetail(20)
  assert.equal(detail.data.writePending, true); await detail.onConfirmCollection()
  const count = t.calls.writes.length
  d.reject(Object.assign(Error('timeout'), { requestMayHaveOccurred: true })); await attempt.pending; await tick()
  await detail.onVerifyOrderWrite(); assert.equal(detail.data.writeUnknown, true); assert.equal(count, 1)
})
test('unknown collection never unlocks on failed or foreign order verification', async () => {
  let bad = false
  const t = fixture('detail', { read: () => bad ? { code: 0, data: { id: 21, paymentStatus: 2 } } : { code: 0, data: t.order },
    write: async () => { throw Object.assign(Error('timeout'), { requestMayHaveOccurred: true }) } })
  const attempt = await submit(t); await attempt.pending; bad = true
  await t.page.onVerifyOrderWrite(); assert.equal(t.page.data.writeUnknown, true)
})
for (const status of [2, 3, 4]) test('collection current payment status ' + status + ' resolves without a second write', async () => {
  const t = fixture('detail', { write: async () => { throw Object.assign(Error('timeout'), { requestMayHaveOccurred: true }) } })
  const attempt = await submit(t); await attempt.pending
  t.order.paymentStatus = status; t.order.needCollect = false
  await t.page.onVerifyOrderWrite(); assert.equal(t.page.data.writeUnknown, false)
  assert.ok(t.page.data.writeVerifiedText.includes('请勿再次收款')); assert.equal(t.calls.writes.length, 1)
})
for (const [actionName, projection] of [
  ['onTransfer', { transferPending: true, transferPendingSubKind: 'TRANSFER' }],
  ['onReturnToStation', { transferPending: true, transferPendingSubKind: 'RETURN_STATION' }],
  ['onRequestCancel', { transferPending: true, transferPendingSubKind: 'CANCEL_REQUEST' }]
]) test(actionName + ' unknown reads old state conservatively and recognizes current application', async () => {
  const t = fixture('detail', { order: { status: 2 } })
  await t.page._runOrderAction(actionName, ctx => t.page._actionWrite(ctx, async () => {
    t.calls.writes.push(20); throw Object.assign(Error('timeout'), { requestMayHaveOccurred: true })
  }))
  assert.equal(t.page.data.writeUnknown, true); await t.page.onVerifyOrderWrite()
  assert.equal(t.page.data.writeUnknown, true)
  Object.assign(t.order, projection); await t.page.onVerifyOrderWrite()
  assert.equal(t.page.data.writeUnknown, false); assert.ok(t.page.data.writeVerifiedText.includes('勿重复提交'))
  assert.equal(t.calls.writes.length, 1)
})
test('unverifiable refusal stays conservative and provides a usable exit', async () => {
  const t = fixture('detail'); t.app.globalData.userInfo.role = 'STATION_MANAGER'
  await t.page._runOrderAction('onRefusalWriteOff', ctx => t.page._actionWrite(ctx, async () => {
    throw Object.assign(Error('timeout'), { requestMayHaveOccurred: true })
  }))
  await t.page.onVerifyOrderWrite(); assert.equal(t.page.data.writeUnknown, true)
  const visible = render(t, 'miniapp-delivery/pages/order/detail.wxml')
  const source = fs.readFileSync(path.join(sourceRoot, 'miniapp-delivery/pages/order/detail.wxml'), 'utf8')
  const root = parseWxml(source)
  for (const [handler, label] of [['onVerifyOrderWrite', '核实当前订单'], ['onReturnToTasks', '返回任务列表']]) {
    assert.ok(visible.some(value => value.tag === 'button' && value.attrs.bindtap === handler))
    const button = findNode(root, node => node.tag === 'button' && node.attrs.bindtap === handler && findNode(node, child => child.tag === '#text' && child.value.trim() === label))
    assert(button)
    assert(renderElements({ children: [button] }, t.page.data, { includeText: true }).some(value => value.text === label))
  }
  t.page.onReturnToTasks(); assert.equal(t.wx.__calls.nav.at(-1).url, '/pages/home/index')
})
test('cash collecting detail keeps navigation alongside collection', async () => {
  const t = fixture('detail', { order: { status: 2, addressSnapshot: '合成地址', latitude: 1, longitude: 2 } })
  await t.page.loadOrderDetail(20)
  const buttons = render(t, 'miniapp-delivery/pages/order/detail.wxml').filter(value => value.tag === 'button')
  assert.ok(buttons.some(value => value.attrs.bindtap === 'onNavigate'))
  assert.ok(buttons.some(value => value.attrs.bindtap === 'onConfirmCollection'))
})
test('in-flight lock survives storage clearing and unknown is restored without allowing another write', async () => {
  const d = deferred(), t = fixture('home', { write: () => d.promise }), attempt = await submit(t)
  t.wx.clearStorageSync()
  const detail = t.pageOf('detail'); await detail.loadOrderDetail(20)
  const blocked = detail.onConfirmCollection()
  assert.equal(t.modals.length, 1, 'storage loss must not open a second collection confirmation')
  await blocked
  const before = t.calls.writes.length
  t.wx.setStorageSync = () => { throw Error('synthetic storage unavailable') }
  d.reject(Object.assign(Error('timeout'), { requestMayHaveOccurred: true })); await attempt.pending
  await detail.onVerifyOrderWrite(); assert.equal(before, 1); assert.equal(t.calls.writes.length, 1)
  assert.equal(detail.data.writeUnknown, true)
  await detail.onConfirmCollection(); assert.equal(t.calls.writes.length, 1)
})
test('homepage initial GET timeout stays visible and retryable without any unknown write', async () => {
  const t = fixture('home', { read: async () => { throw Object.assign(Error('收款信息读取超时'), { requestMayHaveOccurred: true }) } })
  const first = await start(t); await first.pending
  assert.equal(t.modals.length, 0); assert.equal(t.calls.writes.length, 0)
  assert.equal(t.page.data.collectionRecoveryItems.length, 0)
  assert.ok(t.wx.__calls.toast.some(value => value.title === '收款信息读取超时'))
  const next = await start(t); await next.pending; assert.equal(t.calls.reads.length, 2)
})
test('same-order settled success clears recovery index', async () => {
  const t = fixture('detail'), attempt = await submit(t); await attempt.pending
  assert.ok(!Array.from(t.wx.__storage.keys()).some(key => key.startsWith('aquaflow:order-write-recovery:')))
})
test('staff directory timeout is read-only and never creates unknown write recovery', async () => {
  const t = fixture('detail', { order: { status: 2 } })
  const api = { getOrderDetail: async () => ({ data: t.order }), getStaffList: async () => { throw Object.assign(Error('directory timeout'), { requestMayHaveOccurred: true }) } }
  const page = loadPage(path.join(sourceRoot, 'miniapp-delivery/pages/order/detail.js'), { wx: t.wx, app: t.app,
    stubs: { 'api/delivery': api, 'utils/request': { get: async () => ({ data: [] }) } } })
  page.setData({ orderId: 20, order: t.order, loading: false })
  await page.onTransfer(); assert.equal(page.data.writeUnknown, false); assert.equal(page.data.actionBusy, false)
  assert.ok(!Array.from(t.wx.__storage.keys()).some(key => key.startsWith('aquaflow:order-write-recovery:')))
  await page.onTransfer(); assert.equal(page.data.writeUnknown, false)
})
test('today delivered cards preserve backend delivered-unpaid status and immutable delivery date', async () => {
  const t = fixture('home'); t.page.setData({ activeTab: 'completed', readReady: { completed: true },
    completedOrders: [{ ...t.order, isUnpaid: true, payLabel: '需收', amountText: '¥48.25', deliveryTime: '2026-10-24 09:00:00', updateTime: '2026-10-25 10:00:00' }] })
  const text = render(t, 'miniapp-delivery/pages/home/index.wxml').map(value => value.text).join(' ')
  assert.ok(text.includes('今日送达')); assert.ok(text.includes('已送达')); assert.ok(text.includes('需收'))
  assert.ok(text.includes('2026-10-24 09:00:00')); assert.ok(!text.includes('2026-10-25 10:00:00'))
})
;(async () => {
  let failed = 0
  for (const entry of tests) {
    try { await entry.run(); console.log('PASS ' + entry.name) }
    catch (error) { failed++; console.error('FAIL ' + entry.name + ': ' + error.message) }
  }
  console.log('RESULT ' + (tests.length - failed) + '/' + tests.length)
  done(); if (failed) process.exitCode = 1; else console.log('AQUAFLOW_SUITE_OK ' + tests.length)
})().catch(error => { done(); console.error(error); process.exitCode = 1 })
