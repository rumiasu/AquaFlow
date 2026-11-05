const assert = require('assert'), fs = require('fs'), path = require('path'), vm = require('vm')
const { loadPage, createWx, createApp, armWatchdog, ROOT } = require('./harness')
const { parseWxml, renderElements } = require('./wxml-tree')
const done = armWatchdog(30000), tests = [], test = (name, fn) => tests.push({ name, fn })
const tick = () => new Promise(resolve => setImmediate(resolve))
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
const event = (id = 5) => ({ currentTarget: { dataset: { id } } })
const ok = data => ({ code: 0, data })
const row = (id = 5) => ({ id, status: 1, stationId: 3, deliveryStationId: 3, deliveryStaffId: 7, paymentStatus: 1, items: [] })
const tree = parseWxml(fs.readFileSync(path.join(ROOT, 'miniapp-delivery/pages/home/index.wxml'), 'utf8'))
function fixture(manager = false) {
  const wx = createWx(), app = createApp({ _loginGeneration: 1,
    globalData: { isLogin: true, userInfo: { staffId: 7, stationId: 3, role: manager ? 'STATION_MANAGER' : 'DELIVERY', bindStatus: 'BOUND' } } })
  const reads = [], writes = [], modals = []
  const sources = { getAssignedToMe: () => ok([row()]), getDeliveringOrders: () => ok([{ ...row(6), status: 2 }]),
    getCompletedToday: () => ok([]), getDeliveredUnpaid: () => ok([]), getTransferList: () => ok([row(8)]), getPendingOrders: () => ok([row(9)]) }
  const api = Object.fromEntries(Object.keys(sources).map(key => [key, async () => { reads.push(key); return sources[key]() }]))
  const actions = {
    acceptOrder: async (...args) => { writes.push({ name: 'accept', args }); return ok({}) },
    respondTransfer: async (...args) => { writes.push({ name: 'transfer', args }); return ok({}) }
  }
  api.acceptOrder = (...args) => actions.acceptOrder(...args)
  api.respondTransfer = (...args) => actions.respondTransfer(...args)
  const page = loadPage('miniapp-delivery/pages/home/index.js', { wx, app, stubs: {
    'api/delivery': api, 'utils/request': { get: async () => ok([]) },
    'utils/business-dialog': { showBusinessModal: options => modals.push(options) }
  } })
  page.setData({ isManager: manager })
  page.loadStationPending = () => {}; page.loadHomeStationIdentity = () => {}
  return { page, wx, app, sources, reads, writes, modals, api: actions }
}
async function loaded(manager = false) { const t = fixture(manager); await t.page.loadData(manager); return t }
function buttons(page) {
  return renderElements(tree, page.data).filter(node => ['onAcceptOrder', 'onAcceptTransfer', 'onRejectTransfer', 'onCompleteOrder'].includes(node.attrs.catchtap))
}
function disabled(node, data) {
  assert.ok(node.attrs.disabled, 'task button must expose its disabled state')
  return !!vm.runInNewContext(node.attrs.disabled.slice(2, -2), data)
}
test('refresh retains readable old cards but disables their actual WXML and handlers until success', async () => {
  const t = await loaded(), pending = deferred()
  t.sources.getAssignedToMe = () => pending.promise
  const refresh = t.page.onRetryVisibleData()
  assert.strictEqual(t.page.data.assignedOrders[0].id, 5)
  for (const tab of ['assigned', 'delivering']) {
    t.page.setData({ activeTab: tab }); assert.ok(buttons(t.page).length)
    for (const button of buttons(t.page)) assert.strictEqual(disabled(button, t.page.data), true)
  }
  for (const [handler, id] of [['onAcceptOrder', 5], ['onAcceptTransfer', 8], ['onRejectTransfer', 8], ['onCompleteOrder', 6]]) await t.page[handler](event(id))
  assert.strictEqual(t.modals.length, 0); assert.strictEqual(t.wx.__calls.nav.length, 0)
  pending.resolve(ok([row()])); await refresh
  t.page.setData({ activeTab: 'assigned' }); assert.ok(buttons(t.page).every(node => !disabled(node, t.page.data)))
  await t.page.onAcceptOrder(event()); await t.modals[0].success({ confirm: true }); await tick()
  assert.strictEqual(t.writes.length, 1)
})
test('failed assigned read stays non-actionable and retry restores processing without hiding successful tasks', async () => {
  const t = await loaded(); t.sources.getAssignedToMe = () => { throw Error('合成弱网失败') }
  await t.page.onRetryVisibleData(); await t.page.onAcceptOrder(event())
  assert.strictEqual(t.modals.length, 0); assert.strictEqual(t.page.data.readReady.assignedActions, false)
  t.page.onCompleteOrder(event(6)); assert.strictEqual(t.wx.__calls.nav[0].url, '/pages/order/complete?id=6&from=home')
  t.sources.getAssignedToMe = () => ok([row()]); await t.page.onRetryVisibleData(); await t.page.onAcceptOrder(event())
  assert.strictEqual(t.modals.length, 1)
})
test('manager partial success preserves only the actual returned assigned or pending orders', async () => {
  for (const failed of ['getAssignedToMe', 'getPendingOrders']) {
    const t = fixture(true); t.sources[failed] = () => { throw Error('合成单项失败') }; await t.page.loadData(true)
    assert.strictEqual(t.page.data.readReady.assigned, false); assert.strictEqual(t.page.data.readReady.assignedActions, true)
    const validId = failed === 'getAssignedToMe' ? 9 : 5
    await t.page.onAcceptOrder(event(validId === 5 ? 9 : 5)); assert.strictEqual(t.modals.length, 0)
    await t.page.onAcceptOrder(event(validId)); assert.strictEqual(t.modals.length, 1)
  }
})
test('a malformed refresh cannot leave retained old cards actionable after the loading flag clears', async () => {
  const t = await loaded(); t.sources.getAssignedToMe = () => ok([null])
  await t.page.onRetryVisibleData()
  assert.strictEqual(t.page.data.loading, false); assert.ok(t.page.data.loadError)
  assert.strictEqual(t.page.data.assignedOrders[0].id, 5)
  assert.ok(buttons(t.page).every(node => disabled(node, t.page.data)))
  await t.page.onAcceptOrder(event()); await t.page.onAcceptTransfer(event(8)); t.page.onCompleteOrder(event(6))
  assert.strictEqual(t.modals.length, 0); assert.strictEqual(t.wx.__calls.nav.length, 0)
})
test('late confirmations after hide, unload, login, station change or a newer read never submit', async () => {
  for (const handler of ['onAcceptOrder', 'onAcceptTransfer', 'onRejectTransfer']) {
    for (const change of [t => t.page.onHide(), t => t.page.onUnload(), t => { t.app._loginGeneration++ },
      t => { t.app.globalData.userInfo.stationId = 4 }, t => { t.app.globalData.isLogin = false }, t => t.page.loadData(false)]) {
      const t = await loaded(); await t.page[handler](event(handler === 'onAcceptOrder' ? 5 : 8))
      await change(t); await t.modals[0].success({ confirm: true }); assert.strictEqual(t.writes.length, 0, handler)
    }
  }
})
test('fresh foreground rows allow a new confirmation while an obsolete callback cannot unlock it', async () => {
  const t = await loaded(); await t.page.onAcceptOrder(event()); const old = t.modals[0]
  t.page.onHide(); t.page.onShow(); await tick(); await t.page.onAcceptOrder(event())
  const current = t.page._accepting; assert.strictEqual(t.modals.length, 2)
  await old.success({ confirm: true }); assert.strictEqual(t.page._accepting, current); assert.strictEqual(t.writes.length, 0)
  await t.modals[1].success({ confirm: true }); await tick(); assert.strictEqual(t.writes.length, 1)
})
test('in-flight submit stays locked across hide/show and its completion only refreshes the same session', async () => {
  for (const changeSession of [false, true]) {
    const t = await loaded(), write = deferred()
    t.api.acceptOrder = async (...args) => { t.writes.push(args); return write.promise }
    await t.page.onAcceptOrder(event()); const submitting = t.modals[0].success({ confirm: true })
    t.page.onHide(); if (changeSession) t.app._loginGeneration++
    t.page.onShow(); await tick(); await t.page.onAcceptOrder(event()); assert.strictEqual(t.modals.length, 1)
    const readCount = t.reads.length; write.resolve(ok({})); await submitting; await tick()
    assert.strictEqual(t.writes.length, 1); assert.strictEqual(t.wx.__calls.toast.length, 0)
    assert.strictEqual(t.reads.length, readCount + (changeSession ? 0 : 5)); assert.strictEqual(t.page._accepting, null)
  }
})
test('cancel and modal failure release the task lock without writes or forced refresh', async () => {
  const t = await loaded(), reads = t.reads.length
  await t.page.onAcceptOrder(event()); await t.modals[0].success({ confirm: false })
  await t.page.onAcceptOrder(event()); t.modals[1].fail({ errMsg: 'synthetic modal failure' })
  assert.strictEqual(t.page._accepting, null); assert.strictEqual(t.writes.length, 0); assert.strictEqual(t.reads.length, reads)
})
test('transfer accept/reject use their original action and refresh after either server outcome', async () => {
  for (const [handler, action] of [['onAcceptTransfer', 'claim'], ['onRejectTransfer', 'reject']]) {
    for (const fail of [false, true]) {
      const t = await loaded(); t.api.respondTransfer = async (id, body) => { t.writes.push({ id, body }); if (fail) throw Error('合成拒绝'); return ok({}) }
      await t.page[handler](event(8)); await t.modals[0].success({ confirm: true }); await tick()
      assert.deepStrictEqual(t.writes, [{ id: 8, body: { action } }]); assert.strictEqual(t.reads.length, 10)
      assert.strictEqual(t.page._transferBusy, null)
    }
  }
})
test('displayed offer token remains captured across the confirmation, including explicit zero fees', async () => {
  const t = fixture(), old = 'a'.repeat(64)
  t.sources.getAssignedToMe = () => ok([{ ...row(), dispatchAgreement: { status: 'OFFERED', quoteToken: old, serviceAmount: 0, barrelAmount: 0 } }])
  await t.page.loadData(false); await t.page.onAcceptOrder(event()); assert.ok(t.modals[0].content.includes('¥0'))
  t.page.data.assignedOrders[0].dispatchAgreement = { quoteToken: 'b'.repeat(64), serviceAmount: 100 }
  await t.modals[0].success({ confirm: true }); await tick(); assert.strictEqual(t.writes[0].args[1], old)
})
test('unknown rows and unfinished initial reads cannot initiate tasks', async () => {
  const t = fixture(); t.page.setData({ assignedOrders: [row()] }); await t.page.onAcceptOrder(event())
  assert.strictEqual(t.modals.length, 0)
  await t.page.loadData(false); await t.page.onAcceptOrder(event(999)); await t.page.onAcceptTransfer(event(999)); t.page.onCompleteOrder(event(999))
  assert.strictEqual(t.modals.length, 0); assert.strictEqual(t.wx.__calls.nav.length, 0)
})
test('a sole latest read invalidated by identity or station change ends loading and can recover', async () => {
  for (const change of [t => { t.app._loginGeneration++ }, t => { t.app.globalData.userInfo.stationId = 4 }]) {
    const t = await loaded(), pending = deferred(); t.sources.getAssignedToMe = () => pending.promise
    const refresh = t.page.onRetryVisibleData(); change(t); pending.resolve(ok([row()])); await refresh
    assert.strictEqual(t.page.data.loading, false); assert.deepStrictEqual(t.page.data.readReady, {})
    for (const field of ['assignedOrders', 'deliveringOrders', 'completedOrders', 'deliveredUnpaidOrders', 'incomingTransfers']) assert.deepStrictEqual(t.page.data[field], [])
    assert.ok(t.page.data.loadError.includes('重新加载')); assert.strictEqual(t.page._taskDataContext, null)
    t.sources.getAssignedToMe = () => ok([row()]); await t.page.onRetryVisibleData()
    assert.strictEqual(t.page.data.loading, false); assert.strictEqual(t.page.data.loadError, '')
    await t.page.onAcceptOrder(event()); assert.strictEqual(t.modals.length, 1)
  }
})
test('stale finally cannot stop a newer load or change a hidden page', async () => {
  for (const hidden of [false, true]) {
    const t = await loaded(), older = deferred(), newer = deferred()
    t.sources.getAssignedToMe = () => older.promise; const first = t.page.onRetryVisibleData()
    let second
    if (hidden) t.page.onHide()
    else { t.app._loginGeneration++; t.sources.getAssignedToMe = () => newer.promise; second = t.page.onRetryVisibleData() }
    const snapshot = JSON.stringify(t.page.data); older.resolve(ok([row(50)])); await first
    assert.strictEqual(JSON.stringify(t.page.data), snapshot)
    if (!hidden) { assert.strictEqual(t.page.data.loading, true); newer.resolve(ok([row(60)])); await second; assert.strictEqual(t.page.data.assignedOrders[0].id, 60) }
  }
})
;(async () => {
  let failed = 0
  try {
    for (const { name, fn } of tests) { try { await fn(); console.log('PASS ' + name) } catch (err) { failed++; console.error('FAIL ' + name + ': ' + err.stack) } }
    if (failed) process.exitCode = 1
    else console.log('AQUAFLOW_SUITE_OK ' + tests.length)
  } finally { done() }
})().catch(err => { done(); console.error(err); process.exitCode = 1 })
