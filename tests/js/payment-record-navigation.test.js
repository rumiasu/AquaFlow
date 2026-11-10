'use strict'
const assert = require('node:assert/strict'), fs = require('node:fs'), path = require('node:path')
const vm = require('node:vm')
const { loadPage, createWx, createApp, armWatchdog, ROOT } = require('./harness')
const { parseWxml, renderElements } = require('./wxml-tree')
const { createNavigator } = require('../../miniapp-delivery/utils/navigation')

const PAGE = 'miniapp-delivery/pages/station-mgmt/payments/index'
const tree = parseWxml(fs.readFileSync(path.join(ROOT, PAGE + '.wxml'), 'utf8'))
const ok = data => ({ code: 0, data })
const event = id => ({ currentTarget: { dataset: { id } } })
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
const row = (extra = {}) => ({ id: 101, stationId: 11, orderId: 51, customerId: 7, customerName: '合成客户', amount: 20,
  paymentMethod: 2, createTime: '2026-11-04 12:00:00', ...extra })

function fixture(rows = [row()], read = async url => ok({ id: Number(url.split('/').at(-1)) })) {
  const wx = createWx(), calls = [], dialogs = [], writtenIds = []
  const app = createApp({ _loginGeneration: 1, globalData: { isLogin: true,
    userInfo: { staffId: 9, stationId: 11, role: 'STATION_MANAGER', bindStatus: 'BOUND' } } })
  let writes = 0, listReads = 0
  let page, timerId = 0
  const timers = new Map(), navigationFeedback = []
  const navigation = createNavigator({ wx, pages: () => page ? [page] : [],
    session: () => JSON.stringify([app._loginGeneration, app.globalData]),
    setTimer: fn => { timers.set(++timerId, fn); return timerId }, clearTimer: id => timers.delete(id),
    feedback: feedback => navigationFeedback.push(feedback) })
  page = loadPage(PAGE + '.js', { app, wx, stubs: {
    'utils/navigation': navigation,
    'utils/business-dialog': { showBusinessModal: options => dialogs.push(options) },
    'api/station-mgmt': { getPendingPayments: async () => { listReads++; return ok({ items: rows, nextCursor: null }) },
      confirmPayment: async id => { writes++; writtenIds.push(id); return ok(null) } },
    'utils/request': { get: async url => { calls.push(url); return url === '/api/payments' ? ok(rows) : read(url) } }
  } })
  return { page, wx, app, calls, dialogs, writtenIds, navigationFeedback,
    tickNavigation: () => { for (const [id, fn] of [...timers]) { timers.delete(id); fn() } },
    writes: () => writes, listReads: () => listReads }
}
const view = t => renderElements(tree, t.page.data, { includeText: true })
const links = t => view(t).filter(node => ['onOpenPaymentOrder', 'onOpenPaymentCustomer'].includes(node.attrs.catchtap))
async function tap(t, label, paymentId = 101) {
  const node = links(t).find(n => n.text === label && String(n.id) === String(paymentId))
  assert.ok(node, 'missing visible action: ' + label)
  assert.equal(node.tag, 'button'); assert.equal(node.attrs.bindtap, undefined, 'navigation must stop bubbling')
  return t.page[node.attrs.catchtap](event(node.id))
}
const tests = [], test = (name, run) => tests.push({ name, run })

test('list renders only valid associated IDs and does not prefetch any details', async () => {
  const t = fixture([row(), row({ id: 102, orderId: null, customerId: 8 }),
    row({ id: 103, orderId: 0, customerId: null }), row({ id: 104, orderId: 'not-an-id', customerId: true }),
    row({ id: 105, orderId: Number.MAX_SAFE_INTEGER + 1, customerId: -7 })])
  await t.page.onShow()
  assert.equal(t.listReads(), 1); assert.deepEqual(t.calls, [])
  assert.deepEqual(links(t).map(n => [n.id, n.text]), [[101, '查看订单'], [101, '查看客户'], [102, '查看客户']])
  await t.page.onOpenPaymentOrder(event(104)); await t.page.onOpenPaymentCustomer(event(999))
  assert.equal(t.calls.length, 0)
})
test('order button checks the same protected detail route before opening and never confirms payment', async () => {
  const t = fixture(); await t.page.onShow(); await tap(t, '查看订单')
  assert.deepEqual(t.calls, ['/api/delivery/orders/51'])
  assert.deepEqual(t.wx.__calls.nav, [{ type: 'navigateTo', url: '/pages/order/detail?id=51' }])
  assert.equal(t.writes(), 0); assert.equal(t.dialogs.length, 0)
})
test('customer button verifies the protected customer detail rather than inferring permission from payment', async () => {
  const t = fixture(); await t.page.onShow(); await tap(t, '查看客户')
  assert.deepEqual(t.calls, ['/api/customers/7'])
  assert.equal(t.wx.__calls.nav[0].url, '/pages/station-mgmt/customers/detail/index?id=7')
  assert.equal(t.writes(), 0)
})
test('known cross-station order denial stays in the row and hides only that association', async () => {
  const t = fixture([row()], async () => { throw Object.assign(Error('无权查看他站订单'), { businessRejected: true }) })
  await t.page.onShow(); await tap(t, '查看订单')
  assert.equal(t.wx.__calls.nav.length, 0)
  assert.deepEqual(links(t).map(n => n.text), ['查看客户'])
  assert.ok(view(t).some(n => n.text.includes('订单：无权查看他站订单')))
  await t.page.onOpenPaymentOrder(event(101)); assert.equal(t.calls.length, 1)
})
test('customer name and payment station never override a customer-profile denial', async () => {
  const t = fixture([row({ orderId: null, ticketQty: 10 })], async () => ({ code: 1, message: '客户不存在或无权查看' }))
  await t.page.onShow(); await tap(t, '查看客户')
  assert.equal(t.wx.__calls.nav.length, 0); assert.equal(links(t).length, 0)
  assert.ok(view(t).some(n => n.text.includes('客户：客户不存在或无权查看')))
})
test('network errors remain visible but preserve a retryable link', async () => {
  let attempts = 0
  const t = fixture([row()], async () => { if (++attempts === 1) throw Error('网络超时，请重试'); return ok({ id: 51 }) })
  await t.page.onShow(); await tap(t, '查看订单')
  assert.equal(t.page.data.paymentNavigationBusy, false)
  assert.ok(view(t).some(n => n.text.includes('网络超时，请重试')))
  await tap(t, '查看订单'); assert.equal(t.calls.length, 2); assert.equal(t.wx.__calls.nav.length, 1)
})
for (const result of [ok({ id: 999 }), ok(null), { data: { id: 51 } }]) {
  test('malformed or mismatched association response cannot navigate: ' + JSON.stringify(result), async () => {
    const t = fixture([row()], async () => result)
    await t.page.onShow(); await tap(t, '查看订单')
    assert.equal(t.wx.__calls.nav.length, 0); assert.equal(t.page.data.list[0].canOpenOrder, true)
    assert.ok(t.page.data.list[0].orderNavigationError)
  })
}
for (const [name, invalidate] of [
  ['account', t => { t.app.globalData.userInfo = { ...t.app.globalData.userInfo, staffId: 10 } }],
  ['station', t => { t.app.globalData.userInfo.stationId = 12 }],
  ['same-account relogin', t => { t.app._loginGeneration++ }],
  ['logout', t => { t.app.globalData.isLogin = false }],
  ['role', t => { t.app.globalData.userInfo.role = 'DELIVERY' }],
  ['binding', t => { t.app.globalData.userInfo.bindStatus = 'UNBOUND' }],
  ['view', t => t.page.onSetView({ currentTarget: { dataset: { key: 'ticketHistory' } } })],
  ['hide', t => t.page.onHide()], ['unload', t => t.page.onUnload()],
  ['refresh', t => t.page.loadData()]
]) {
  test('late permission response after ' + name + ' cannot navigate', async () => {
    const pending = deferred(), t = fixture([row()], () => pending.promise)
    await t.page.onShow(); const opening = tap(t, '查看订单')
    await invalidate(t); pending.resolve(ok({ id: 51 })); await opening
    assert.equal(t.wx.__calls.nav.length, 0)
  })
}
test('fast repeated taps share one read and do not open a collection confirmation', async () => {
  const pending = deferred(), t = fixture([row()], () => pending.promise)
  await t.page.onShow(); const opening = tap(t, '查看订单')
  await t.page.onOpenPaymentOrder(event(101)); await t.page.onOpenPaymentCustomer(event(101)); t.page.onConfirm(event(101))
  assert.equal(t.calls.length, 1); assert.equal(t.dialogs.length, 0); assert.equal(t.writes(), 0)
  pending.resolve(ok({ id: 51 })); await opening
  assert.equal(t.wx.__calls.nav.length, 1); assert.equal(t.page.data.paymentNavigationBusy, false)
})
test('an open collection confirmation prevents a concurrent navigation read', async () => {
  const t = fixture(); await t.page.onShow(); t.page.onConfirm(event(101)); await t.page.onOpenPaymentCustomer(event(101))
  assert.equal(t.dialogs.length, 1); assert.deepEqual(t.calls, []); assert.equal(t.wx.__calls.nav.length, 0)
})
test('same-amount rows confirm the selected order number and its actual collection record', async () => {
  const t = fixture([row({ amount: '7.00' }), row({ id: 102, orderId: 52, amount: '7.00' })])
  await t.page.onShow()
  t.page.onConfirm(event('102'))
  assert.equal(t.dialogs[0].content, '确认已收到订单 #52 的 ¥7.00？')
  assert.deepEqual(t.writtenIds, [])
  await t.dialogs[0].success({ confirm: true })
  assert.deepEqual(t.writtenIds, [102])
})
test('an unverified amount neither looks like zero nor opens collection confirmation', async () => {
  for (const amount of [null, '', false, '7.001', -1]) {
    const t = fixture([row({ amount })]); await t.page.onShow()
    const nodes = view(t)
    assert(nodes.some(n => n.text === '金额待核实'))
    assert(!nodes.some(n => n.text === '¥0.00'))
    const button = nodes.find(n => n.attrs.catchtap === 'onConfirm')
    assert.equal(vm.runInNewContext(button.attrs.disabled.slice(2, -2),
      { ...t.page.data, item: t.page.data.list[0] }), true)
    t.page.onConfirm(event(101))
    assert.deepEqual(t.dialogs, [])
    assert.equal(t.writes(), 0)
  }
  const t = fixture([row({ amount: 0 })]); await t.page.onShow()
  assert(view(t).some(n => n.text === '¥0.00'))
})
test('a receipt from before relogin cannot open a new confirmation until reread', async () => {
  const t = fixture(); await t.page.onShow()
  t.app._loginGeneration++
  t.page.onConfirm(event(101))
  assert.deepEqual(t.dialogs, [])
  await t.page.onRetryLoad()
  t.page.onConfirm(event(101))
  assert.equal(t.dialogs.length, 1)
  assert.equal(t.writes(), 0)
})
test('signed history amounts remain visible without a confirmation action', async () => {
  const t = fixture([row({ orderId: null, ticketQty: 10, amount: '-7.00' })])
  t.page.onLoad({ view: 'ticketHistory' })
  await t.page.onShow()
  assert(view(t).some(n => n.text === '¥-7.00'))
  assert(!view(t).some(n => n.attrs.catchtap === 'onConfirm'))
})
test('navigation failure is visible and retry can succeed', async () => {
  const t = fixture(); await t.page.onShow(); const original = t.wx.navigateTo
  t.wx.navigateTo = options => options.fail({ errMsg: 'navigateTo:fail' })
  await tap(t, '查看订单')
  assert.equal(t.page.data.paymentNavigationBusy, false)
  assert.equal(t.navigationFeedback.length, 1); assert.match(t.navigationFeedback[0].message, /页面暂时无法打开/)
  t.wx.navigateTo = original; await tap(t, '查看订单'); assert.equal(t.wx.__calls.nav.length, 1)
})
test('missing native navigation callbacks never lock payment reads or confirmation indefinitely', async () => {
  const t = fixture(); await t.page.onShow(); const original = t.wx.navigateTo
  t.wx.navigateTo = () => {}
  await tap(t, '查看订单')
  assert.equal(t.page.data.paymentNavigationBusy, false)
  assert.equal(t.page._paymentNavigationFlight, null)
  t.page.onConfirm(event(101)); assert.equal(t.dialogs.length, 1)
  t.dialogs[0].success({ confirm: false }); t.tickNavigation()
  assert.equal(t.navigationFeedback.length, 1); assert.match(t.navigationFeedback[0].message, /超时/)
  t.wx.navigateTo = original; await tap(t, '查看订单'); assert.equal(t.wx.__calls.nav.length, 1)
})
test('explicit business rejection disables the association regardless of wording', async () => {
  const t = fixture([row()], async () => ({ code: 1, message: '关联信息暂不可用，请重新核对记录' }))
  await t.page.onShow(); await tap(t, '查看订单')
  assert.equal(t.page.data.list[0].canOpenOrder, false); assert.equal(t.wx.__calls.nav.length, 0)
})
test('history filters retain a known denial without new reads; reloading resets it', async () => {
  const t = fixture([row({ orderId: null, ticketQty: 10 })], async () => ({ code: 1, message: '客户不存在或无权查看' }))
  t.page.onLoad({ view: 'ticketHistory' }); await t.page.onShow(); await tap(t, '查看客户')
  const count = t.calls.length; t.page.onClearPaymentDates()
  assert.equal(links(t).length, 0); assert.equal(t.calls.length, count)
  await t.page.loadData(); assert.deepEqual(links(t).map(n => n.text), ['查看客户'])
})
test('removing a history row through date filtering cancels its late navigation', async () => {
  const pending = deferred(), t = fixture([row({ orderId: null, ticketQty: 10 })], () => pending.promise)
  t.page.onLoad({ view: 'ticketHistory' }); await t.page.onShow(); const opening = tap(t, '查看客户')
  t.page.onPaymentHistoryDate({ currentTarget: { dataset: { field: 'startDate' } }, detail: { value: '2026-11-05' } })
  pending.resolve(ok({ id: 7 })); await opening; assert.equal(t.wx.__calls.nav.length, 0)
})
test('delivery staff cannot gain management navigation from a fabricated payment list', async () => {
  const t = fixture(); t.app.globalData.userInfo.role = 'DELIVERY'; await t.page.loadData()
  assert.equal(links(t).length, 0); await t.page.onOpenPaymentOrder(event(101)); assert.deepEqual(t.calls, [])
})

;(async () => {
  const done = armWatchdog(60000); let failed = 0
  for (const item of tests) {
    try { await item.run(); console.log('PASS ' + item.name) }
    catch (error) { failed++; console.error('FAIL ' + item.name + ': ' + error.stack) }
  }
  done(); if (failed) process.exitCode = 1; else console.log('AQUAFLOW_SUITE_OK ' + tests.length)
})().catch(error => { console.error(error); process.exitCode = 1 })
