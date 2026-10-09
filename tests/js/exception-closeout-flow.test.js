const assert = require('assert')
const fs = require('fs')
const path = require('path')
const { loadPage, createWx, createApp, armWatchdog, ROOT } = require('./harness')
const { parseWxml, renderElements } = require('./wxml-tree')
const done = armWatchdog()
let passed = 0
const ref = { refundType: 'BARREL_RETURN', refundId: 12 }
const defer = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
const event = data => ({ currentTarget: { dataset: data } })
function customer(api = {}, wx = createWx(), state = { status: 'CLOSED', statusText: '已结案，可重新提出', version: 2, canOpen: true, actions: [{ id: 1, actionText: '原结果', reason: '上一轮结果' }] }) {
  const app = createApp({ globalData: { isLogin: true, customerId: 7, accessToken: 'synthetic-session', refreshToken: 'synthetic-refresh' } })
  const sent = []
  const feedback = Object.assign({ getRefundNotes: async () => ({ data: Object.assign({ objectText: '申请12', notes: [], dispute: state }, ref) }),
    openRefundDispute: async payload => { sent.push(payload); return { data: Object.assign({ id: 1, action: 'REOPEN' }, ref) } }
  }, api)
  const page = loadPage('miniapp-user/pages/service/index.js', { wx, app, stubs: { 'api/feedback': feedback } }); page.onLoad(ref)
  return { page, wx, app, sent, state }
}
function manager(api = {}, wx = createWx()) {
  const app = createApp({ globalData: { isLogin: true, userInfo: { staffId: 17, stationId: 2, role: 'STATION_MANAGER' } }, isStationManager: () => true })
  const sent = [], row = Object.assign({ status: 'OPEN', statusText: '处理中', version: 1, canClose: true, objectText: '申请12', actions: [] }, ref)
  const feedback = Object.assign({ getRefundDisputes: async () => ({ data: [row] }), getCustomerFeedbacks: async () => ({ data: [] }),
    getRefundNotes: async () => ({ data: Object.assign({ dispute: row }, ref) }),
    closeRefundDispute: async p => { sent.push(p); return { data: Object.assign({ id: 2, action: 'CLOSE' }, ref) } }
  }, api)
  const page = loadPage('miniapp-delivery/pages/station-mgmt/customer-feedback/index.js', { wx, app, stubs: { 'api/feedback': feedback } }); page.onLoad(); page._noteEpoch = 1
  return { page, wx, app, sent, row }
}
function refusal(api = {}, wx = createWx()) {
  const app = createApp({ globalData: { isLogin: true, userInfo: { staffId: 17, stationId: 2, role: 'STATION_MANAGER' } } })
  const sent = [], row = { order_id: 31, version: 0, canRevoke: true, canRelease: false }
  const business = Object.assign({ getRefusals: async args => ({ code: 0, data: { stationId: 2, scope: args.orderId ? 'LOOKUP' : args.scope, limit: 50, items: [row], nextBeforeId: null } }), getWaiting: async () => ({ code: 0, data: {
    schemaAvailable: true, counts: { waitingStock: 0, returnsTotal: 0, recoveriesTotal: 0, barrelsTotal: 0 }, stock: [], returns: [], recoveries: [], barrels: [] } }),
    getTicketExitBatches: async () => ({ code: 0, data: [] }),
    revokeRefusal: async (id, p) => { sent.push(p); return { data: { id: 1, orderId: id, action: 'REVOKE' } } },
    refusalHistory: async () => ({ data: [{ id: 1, actionText: '撤销误判', reason: '原理由' }] })
  }, api)
  const page = loadPage('miniapp-delivery/pages/station-mgmt/business-waiting/index.js', { wx, app, stubs: { 'api/business-rules': business, 'utils/pending-reminder': { syncPendingReminder: async () => {} } } })
  page._refusalEpoch = 1; page._refusalRecordsSession = page.refusalSession(); page.setData({ ready: { refusals: true }, refusals: [row], stationId: 2 })
  return { page, wx, app, sent, row }
}
async function test(name, run) { await run(); passed++; process.stdout.write('PASS ' + name + '\n') }
async function main() {
  await test('customer reopens with explicit reason/version and no money or confirmation payload', async () => {
    const f = customer(); await f.page.loadRefundThread(); f.page.setData({ disputeReason: '仍有异议' }); await f.page.onOpenDispute()
    assert.strictEqual(f.sent.length, 1); assert.strictEqual(f.sent[0].expectedVersion, 2)
    assert.deepStrictEqual(Object.keys(f.sent[0]).sort(), ['expectedVersion', 'idempotencyKey', 'reason', 'refundId', 'refundType'])
    assert.strictEqual(f.page.data.pendingDispute, false); assert.strictEqual(f.wx.__storage.size, 0)
  })
  await test('blank reason and open case cannot create another dispute', async () => {
    const f = customer(); await f.page.loadRefundThread(); await f.page.onOpenDispute(); assert.strictEqual(f.sent.length, 0)
    f.page.setData({ disputeReason: '说明', dispute: { version: 1, canOpen: false } }); await f.page.onOpenDispute(); assert.strictEqual(f.sent.length, 0)
  })
  await test('customer unknown result retains same request across retry and page reentry', async () => {
    const sent = []; let first = true
    const f = customer({ openRefundDispute: async p => { sent.push(p); if (first) { first = false; throw new Error('网络中断') } return { data: Object.assign({ id: 1, action: 'REOPEN' }, ref) } } })
    await f.page.loadRefundThread(); f.page.setData({ disputeReason: '原异议' }); await f.page.onOpenDispute()
    f.state.version = 3; f.state.canOpen = false; await f.page.loadRefundThread(); assert.strictEqual(f.page.data.pendingDispute, true)
    f.page.setData({ disputeReason: '改理由' }); await f.page.onOpenDispute(); assert.strictEqual(sent.length, 1)
    await f.page.onRetryDispute(); assert.strictEqual(sent.length, 2); assert.strictEqual(sent[0].idempotencyKey, sent[1].idempotencyKey); assert.strictEqual(sent[1].expectedVersion, 2)
  })
  await test('customer duplicate tap and switched identity ignore late success without erasing intent', async () => {
    const d = defer(), sent = [], f = customer({ openRefundDispute: p => { sent.push(p); return d.promise } })
    await f.page.loadRefundThread(); f.page.setData({ disputeReason: '原异议' }); const running = f.page.onOpenDispute(); await f.page.onOpenDispute(); assert.strictEqual(sent.length, 1)
    f.app.globalData.customerId = 8; f.page.setData({ disputeReason: '新身份草稿' }); d.resolve({ data: Object.assign({ id: 1, action: 'REOPEN' }, ref) }); await running
    assert.strictEqual(f.page.data.disputeReason, '新身份草稿'); assert.strictEqual(f.wx.__storage.size, 1); assert.strictEqual(f.wx.__calls.toast.length, 0)
  })
  await test('known business rejection refreshes version and allows explicit new submission', async () => {
    const sent = []; let first = true; const f = customer({ openRefundDispute: async p => {
      sent.push(p); if (first) { first = false; const err = new Error('已有新处理'); err.businessRejected = true; throw err } return { data: Object.assign({ id: 1, action: 'REOPEN' }, ref) }
    } })
    await f.page.loadRefundThread(); f.page.setData({ disputeReason: '说明' }); await f.page.onOpenDispute(); assert.strictEqual(f.wx.__storage.size, 0)
    f.state.version = 4; await f.page.loadRefundThread(); await f.page.onOpenDispute(); assert.strictEqual(sent[1].expectedVersion, 4); assert.notStrictEqual(sent[0].idempotencyKey, sent[1].idempotencyKey)
  })
  await test('manager may close with result and no customer approval or funds action', async () => {
    const f = manager(); await f.page.loadDisputes(); await f.page.onOpenDispute(event({ type: ref.refundType, id: 12 }))
    await f.page.onCloseDispute(); assert.strictEqual(f.sent.length, 0)
    f.page.setData({ disputeResult: '核实并告知结果' }); await f.page.onCloseDispute(); assert.strictEqual(f.sent.length, 1); assert.strictEqual(f.sent[0].expectedVersion, 1)
    assert.deepStrictEqual(Object.keys(f.sent[0]).sort(), ['expectedVersion', 'idempotencyKey', 'reason', 'refundId', 'refundType'])
  })
  await test('manager unknown closure retries original after server case is closed', async () => {
    const sent = []; let first = true; const f = manager({ closeRefundDispute: async p => { sent.push(p); if (first) { first = false; throw new Error('未知') } return { data: Object.assign({ id: 2, action: 'CLOSE' }, ref) } } })
    await f.page.loadDisputes(); await f.page.onOpenDispute(event({ type: ref.refundType, id: 12 })); f.page.setData({ disputeResult: '原结果' }); await f.page.onCloseDispute()
    f.row.canClose = false; f.row.version = 2; await f.page.loadDisputes(); await f.page.onOpenDispute(event({ type: ref.refundType, id: 12 })); await f.page.onRetryCloseDispute()
    assert.strictEqual(sent.length, 2); assert.strictEqual(sent[0].idempotencyKey, sent[1].idempotencyKey); assert.strictEqual(sent[1].expectedVersion, 1)
  })
  await test('manager failed history and changed role prevent closure', async () => {
    const f = manager({ getRefundNotes: async () => { throw new Error('历史失败') } }); await f.page.loadDisputes(); await f.page.onOpenDispute(event({ type: ref.refundType, id: 12 })); f.page.setData({ disputeResult: '结果' }); await f.page.onCloseDispute()
    assert.strictEqual(f.sent.length, 0); assert.strictEqual(f.page.data.disputeReady, false)
    const g = manager(); await g.page.loadDisputes(); await g.page.onOpenDispute(event({ type: ref.refundType, id: 12 })); g.app.isStationManager = () => false; g.page.setData({ disputeResult: '结果' }); await g.page.onCloseDispute(); assert.strictEqual(g.sent.length, 0)
  })
  await test('refusal form uses server capability and preserves request after unknown result', async () => {
    const sent = []; let first = true; const f = refusal({ revokeRefusal: async (id, p) => { sent.push(p); if (first) { first = false; throw new Error('未知') } return { data: { id: 1, orderId: id, action: 'REVOKE' } } } })
    f.page.onOpenRefusalResolution(event({ id: 31, action: 'RELEASE' })); assert.strictEqual(f.page.data.selectedRefusal, null)
    f.page.onOpenRefusalResolution(event({ id: 31, action: 'REVOKE' })); f.page.setData({ refusalReason: '原判断错误' }); await f.page.onSubmitRefusalResolution()
    f.row.canRevoke = false; f.row.version = 1; await f.page.loadData(); f.page.onOpenRefusalResolution(event({ id: 31, action: 'REVOKE' })); await f.page.onRetryRefusalResolution()
    assert.strictEqual(sent.length, 2); assert.strictEqual(sent[0].idempotencyKey, sent[1].idempotencyKey); assert.strictEqual(sent[1].expectedVersion, 0)
    assert.deepStrictEqual(Object.keys(sent[0]).sort(), ['expectedVersion', 'idempotencyKey', 'reason'])
  })
  await test('refusal hidden page ignores late receipt and storage failure sends nothing', async () => {
    const d = defer(), f = refusal({ revokeRefusal: () => d.promise }); f.page.onOpenRefusalResolution(event({ id: 31, action: 'REVOKE' })); f.page.setData({ refusalReason: '原理由' }); const running = f.page.onSubmitRefusalResolution(); f.page.onHide()
    d.resolve({ data: { id: 1, orderId: 31, action: 'REVOKE' } }); await running;
    assert([...f.wx.__storage.keys()].some(k => k.startsWith('exception-action:')))
    assert(f.wx.__storage.has('refusal-recovery:STAFF:17:2'), '迟到回执不能清掉原订单定位')
    assert.strictEqual(f.wx.__calls.toast.length, 0)
    const g = refusal(); g.page.onOpenRefusalResolution(event({ id: 31, action: 'REVOKE' })); g.page.setData({ refusalReason: '理由' }); g.wx.setStorageSync = () => { throw new Error('存储失败') }; await g.page.onSubmitRefusalResolution(); assert.strictEqual(g.sent.length, 0)
  })
  await test('actual templates expose customer reopening and manager closure only in valid states', async () => {
    const tree = file => parseWxml(fs.readFileSync(path.join(ROOT, file), 'utf8'))
    const clientTree = tree('miniapp-user/pages/service/index.wxml'), f = customer(); await f.page.loadRefundThread()
    assert.strictEqual(renderElements(clientTree, f.page.data).filter(n => n.attrs.bindtap === 'onOpenDispute').length, 1)
    f.page.setData({ dispute: Object.assign({}, f.page.data.dispute, { canOpen: false }) })
    assert.strictEqual(renderElements(clientTree, f.page.data).filter(n => n.attrs.bindtap === 'onOpenDispute').length, 0)
    f.page.setData({ pendingDispute: true })
    assert.strictEqual(renderElements(clientTree, f.page.data).filter(n => n.attrs.bindtap === 'onRetryDispute').length, 1)
    const managerTree = tree('miniapp-delivery/pages/station-mgmt/customer-feedback/index.wxml'), g = manager()
    await g.page.loadDisputes(); await g.page.onOpenDispute(event({ type: ref.refundType, id: 12 }))
    assert.strictEqual(renderElements(managerTree, g.page.data).filter(n => n.attrs.bindtap === 'onCloseDispute').length, 1)
    g.page.setData({ selectedDispute: Object.assign({}, g.page.data.selectedDispute, { canClose: false }), pendingDispute: false })
    assert.strictEqual(renderElements(managerTree, g.page.data).filter(n => n.attrs.bindtap === 'onCloseDispute').length, 0)
    g.page.setData({ denied: true })
    assert.strictEqual(renderElements(managerTree, g.page.data).filter(n => n.attrs.bindtap === 'onOpenDispute').length, 0)
  })
  done(); process.stdout.write('AQUAFLOW_SUITE_OK ' + passed + '\n')
}
main().catch(err => { done(); console.error(err); process.exitCode = 1 })
