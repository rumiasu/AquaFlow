// Actual Page handlers with synthetic evidence; no HTTP, database or physical handover.
const assert = require('node:assert/strict'), fs = require('fs'), path = require('path')
const { loadPage, createWx, createApp, armWatchdog, ROOT } = require('./harness')
const { parseWxml, renderElements } = require('./wxml-tree')
const tests = [], test = (name, run) => tests.push({ name, run })
const event = { currentTarget: { dataset: { id: 77 } } }
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r }); return { promise, resolve } }
const ready = { recordId: 77, requiresLinkedReceipt: true, companionOrderId: 99, orderNo: 'SYNTH-99', orderStatus: 3,
  productId: 5, productName: '测试桶装水', requiredBarrels: 1, deliveryReceiptId: 600, returnedQty: 2,
  allocatedQty: 0, availableQty: 2, eligible: true, reason: '核对原订单回桶后关联本申请', reasonCode: 'READY', linked: false,
  canOpenCompanion: true, companionNextStep: '可查看关联订单核对实际交接' }
function setup(options = {}) {
  const wx = createWx(), app = createApp({ globalData: { isLogin: true, userInfo: { staffId: 1, stationId: 1, role: 'STATION_MANAGER' } } })
  const writes = [], reads = [], row = { id: 77, stationId: 1, type: 2, status: 1, customerId: 7, productId: 5, quantity: 1, depositRefund: 30,
    returnDetail: { status: 'APPROVED', pickupMode: 'COMBINED', companionOrderId: 99, requiredBarrels: 1, pickupFee: 0,
      arrangementVersion: 1, customerConfirmationRequired: false, ...options.detail } }
  const page = loadPage('miniapp-delivery/pages/station-mgmt/barrel-return/index.js', { wx, app, stubs: {
    'utils/business-dialog': { showBusinessModal: o => wx.showModal(o) },
    'api/business-rules': { getReturnApplications: async args => ({ code: 0, data: { stationId: 1, scope: args.scope, limit: 50, items: [row], nextBeforeId: null } }) },
    'utils/pending-reminder': { getPendingReturnRecord: async () => ({ code: 0, data: row }), syncPendingReminder() {} },
    'api/station-mgmt': {
      getBarrelHandoverEvidence: async id => { reads.push(id); return { code: 0, data: typeof options.evidence === 'function' ? await options.evidence(reads.length) : { ...ready, ...options.evidence } } },
      getBarrelRefundEligibility: async () => ({ code: 0, data: { recordId: 77, detailStatus: row.returnDetail.status, available: false, reason: '待交接' } }),
      getRefundUndelivered: async () => ({ code: 0, data: { records: [] } }),
      updateBarrelRecordStatus: async (id, status, extra) => {
        writes.push({ id, status, extra });
        if (options.writeError) throw Object.assign(Error(options.writeError), { businessRejected: true })
        row.returnDetail.status = 'RECEIVED'; row.status = 2; return { code: 0 }
      }
    }
  } })
  return { page, app, wx, row, writes, reads, async open() { await page.loadData(); await page.onSelectRecord(event) } }
}
test('unfinished companion directs to real order page and cannot register another handover', async () => {
  const t = setup({ evidence: { eligible: false, deliveryReceiptId: null, orderStatus: 2, reasonCode: 'ORDER_NOT_DELIVERED', reason: '请先完成关联订单的实际回桶登记' } }); await t.open()
  assert.equal(t.page.data.selected.primaryAction, 'companionOrder')
  await t.page.onPrimary(); assert.equal(t.wx.__calls.nav[0].url, '/pages/order/detail?id=99')
  await t.page.onApprove(event); assert.equal(t.writes.length, 0)
})
test('unpaid or outsourced companion keeps the original request and shows the real next step without a dead link', async () => {
  const tree = parseWxml(fs.readFileSync(path.join(ROOT, 'miniapp-delivery/pages/station-mgmt/barrel-return/index.wxml'), 'utf8'))
  for (const companionNextStep of ['请客户在原送水订单完成付款，再回本申请重新核对', '请联系履约站核对实际送达和回桶，再回本申请重新核对']) {
    const t = setup({ evidence: { eligible: false, orderStatus: 2, reasonCode: 'ORDER_NOT_DELIVERED', canOpenCompanion: false,
      reason: '关联送水订单尚未完成实际交付', companionNextStep } }); await t.open()
    assert.equal(t.page.data.selected.canOpenCompanion, false)
    assert.equal(t.page.data.selected.primaryAction, 'recheckHandover')
    const nodes = renderElements(tree, t.page.data, { includeText: true })
    assert(!nodes.some(n => n.attrs.bindtap === 'onOpenCompanionOrder'))
    assert(nodes.some(n => n.text.includes(companionNextStep)))
    await t.page.onOpenCompanionOrder(); await t.page.onPrimary()
    assert.equal(t.wx.__calls.nav.length, 0); assert.equal(t.writes.length, 0)
    assert.equal(t.page.data.selected.id, 77); assert.equal(t.reads.length, 2)
  }
})
test('navigation requires literal true for the same evidence and remains absent when no evidence is read', async () => {
  for (const canOpenCompanion of [undefined, null, 'true', 1]) {
    const t = setup({ evidence: { eligible: false, canOpenCompanion } }); await t.open()
    await t.page.onOpenCompanionOrder(); assert.equal(t.wx.__calls.nav.length, 0)
    assert.equal(t.page.data.selected.canOpenCompanion, false)
  }
  const zero = setup({ detail: { requiredBarrels: 0 } }); await zero.open()
  assert.equal(zero.reads.length, 0); await zero.page.onOpenCompanionOrder(); assert.equal(zero.wx.__calls.nav.length, 0)
  for (const changed of [{ recordId: 78 }, { companionOrderId: 100 }, { productId: 8 }, { requiredBarrels: 2 }]) {
    const t = setup(); await t.open(); assert.equal(t.page.data.selected.canOpenCompanion, true)
    t.page.setData({ handoverEvidence: { ...ready, ...changed } })
    await t.page.onOpenCompanionOrder(); assert.equal(t.wx.__calls.nav.length, 0)
  }
})
test('ready evidence requires explicit confirmation and sends the original delivery receipt once', async () => {
  const t = setup(); await t.open(); assert.equal(t.page.data.selected.primaryAction, 'linkReceipt')
  await t.page.onPrimary(); assert.equal(t.writes.length, 1)
  assert.deepEqual(t.writes[0], { id: 77, status: 2, extra: { deliveryReceiptId: 600 } })
  assert(t.reads.length >= 2, 're-read evidence immediately before the confirmation')
  const copy = t.wx.__calls.modal.map(m => m.content).join('\n')
  for (const text of ['SYNTH-99', '600', '测试桶装水', '1', '不再']) assert(copy.includes(text), text)
  await t.page.onPrimary(); assert.equal(t.writes.length, 1)
})
test('cancelled confirmation, changed identity and changed evidence never link stale receipts', async () => {
  const cancelled = setup(); await cancelled.open(); cancelled.wx.__modalAutoConfirm = false; await cancelled.page.onPrimary(); assert.equal(cancelled.writes.length, 0)
  const switched = setup(); await switched.open(); switched.wx.showModal = o => { switched.app.globalData.userInfo.staffId = 2; o.success({ confirm: true }) }
  await switched.page.onPrimary(); assert.equal(switched.writes.length, 0)
  const changed = setup({ evidence: n => n === 1 ? ready : { ...ready, availableQty: 0, allocatedQty: 2, eligible: false, reasonCode: 'RECEIPT_INSUFFICIENT', reason: '原回桶凭据已被其他申请占用' } })
  await changed.open(); await changed.page.onPrimary(); assert.equal(changed.writes.length, 0)
})
test('two taps while confirming produce one allocation and late reads after leaving cannot enable actions', async () => {
  const t = setup(); await t.open(); let confirm
  t.wx.showModal = o => { confirm = o }
  const first = t.page.onPrimary()
  for (let i = 0; i < 8 && !confirm; i++) await Promise.resolve()
  assert(confirm); await t.page.onPrimary(); assert.equal(t.writes.length, 0)
  confirm.success({ confirm: true }); await first; assert.equal(t.writes.length, 1)
  const gate = deferred(), late = setup({ evidence: () => gate.promise }), opening = late.open()
  for (let i = 0; i < 8 && !late.reads.length; i++) await Promise.resolve()
  assert.equal(late.reads.length, 1); late.page.onHide(); gate.resolve(ready); await opening
  assert.equal(late.page.data.handoverEvidence, null); await late.page.onOpenCompanionOrder(); assert.equal(late.wx.__calls.nav.length, 0)
})
test('failed evidence can be retried without a write; an already linked receipt only refreshes the original request', async () => {
  const t = setup({ evidence: n => { if (n === 1) throw Error('SYNTH unavailable'); return ready } }); await t.open()
  assert(t.page.data.handoverError.includes('SYNTH unavailable')); assert.equal(t.page.data.selected.primaryAction, '')
  await t.page.onRetryHandover(); assert.equal(t.page.data.selected.primaryAction, 'linkReceipt'); assert.equal(t.writes.length, 0)
  const linked = setup({ evidence: { eligible: false, linked: true, reasonCode: 'ALREADY_LINKED', reason: '原交接已关联，请核对原申请' } }); await linked.open()
  assert.equal(linked.page.data.selected.primaryAction, 'refreshRecord'); await linked.page.onApprove(event); assert.equal(linked.writes.length, 0)
})
test('missing, mismatched and incomplete evidence cannot fall back to a fresh physical receipt', async () => {
  for (const evidence of [
    { eligible: false, deliveryReceiptId: null, reasonCode: 'RECEIPT_MISSING', reason: '请核实原交接，不得再次登记已回桶' },
    { productId: 6 }, { recordId: 78 }, { requiredBarrels: 2 }, { deliveryReceiptId: null }, { availableQty: 0 }
  ]) {
    const t = setup({ evidence }); await t.open(); await t.page.onApprove(event)
    if (t.page.data.selected.primaryAction === 'linkReceipt') await t.page.onPrimary()
    assert.equal(t.writes.length, 0)
    assert.notEqual(t.page.data.selected.primaryAction, 'receive')
  }
})
test('other-product combined and unused rights retain the existing explicit handover path', async () => {
  for (const options of [{ evidence: { requiresLinkedReceipt: false, eligible: true, deliveryReceiptId: null } }, { detail: { requiredBarrels: 0 } }]) {
    const t = setup(options); await t.open(); assert.equal(t.page.data.selected.primaryAction, 'receive')
    await t.page.onPrimary(); assert.equal(t.writes.length, 1); assert.equal(t.writes[0].extra, undefined)
  }
})
test('business rejection retains the reason and does not retry a different physical entry', async () => {
  const t = setup({ writeError: '原回桶凭据可关联数量不足，请核实原交接' }); await t.open(); await t.page.onPrimary()
  assert.equal(t.writes.length, 1); assert(t.page.data.actionError.includes('原回桶凭据可关联数量不足'))
  assert.equal(t.page.data.recoveryNeeded, false)
})
test('physical balance warning has a real customer return-empty route with duplicate-registration caution', async () => {
  const t = setup({ evidence: { eligible: false, reasonCode: 'PHYSICAL_BALANCE_CHANGED', reason: '请先核实本商品还空桶记录' } }); await t.open()
  const tree = parseWxml(fs.readFileSync(path.join(ROOT, 'miniapp-delivery/pages/station-mgmt/barrel-return/index.wxml'), 'utf8'))
  const nodes = renderElements(tree, t.page.data, { includeText: true })
  assert(nodes.some(n => n.attrs.bindtap === 'onReviewReturnEmpty'))
  await t.page.onReviewReturnEmpty(); assert.equal(t.wx.__calls.nav[0].url, '/pages/station-mgmt/customers/detail/index?id=7&action=return-empty')
  assert(t.wx.__calls.modal.some(m => m.content.includes('已登记') && m.content.includes('重复')))
})
test('delivery entry tells staff to count all buckets once including refund buckets', () => {
  const tree = parseWxml(fs.readFileSync(path.join(ROOT, 'miniapp-delivery/pages/order/complete.wxml'), 'utf8'))
  const page = loadPage('miniapp-delivery/pages/order/complete.js')
  const nodes = renderElements(tree, { ...page.data, orderLoaded: true, hasBarrelItems: true, items: [], order: {} }, { includeText: true })
  assert(nodes.some(n => n.text.includes('退押金') && n.text.includes('总数')))
})
test('customer preview explicitly shows zero physical handover before submitting unused rights', () => {
  const tree = parseWxml(fs.readFileSync(path.join(ROOT, 'miniapp-user/pages/barrel/index.wxml'), 'utf8'))
  const page = loadPage('miniapp-user/pages/barrel/index.js')
  const nodes = renderElements(tree, { ...page.data, loading: false, showReturnModal: true, previewReady: true,
    summary: { independentRights: true }, preview: { refundAmount: 30, requiredBarrels: 0, lots: [] } }, { includeText: true })
  assert(nodes.some(n => n.text.includes('预计需交回 0 个实物桶')))
})
test('customer can inspect the exact companion order and cannot follow stale hidden records', () => {
  const wx = createWx(), page = loadPage('miniapp-user/pages/barrel/index.js', { wx })
  page.setData({ records: [{ id: 77, returnDetail: { companionOrderId: 99 } }], refreshing: false, recordsFailed: false })
  page.onReturnCompanionOrder(event); assert.equal(wx.__calls.nav[0].url, '/pages/order/detail?id=99')
  page.onHide(); page.onReturnCompanionOrder(event); assert.equal(wx.__calls.nav.length, 1)
})
test('the evidence API wrapper is exported and uses the original return record endpoint', async () => {
  const Module = require('module'), original = Module._load, calls = []
  const target = path.join(ROOT, 'miniapp-delivery/api/station-mgmt.js')
  Module._load = function (request, parent, main) {
    if (request.endsWith('/utils/request')) return { get: async (...args) => { calls.push(args); return { code: 0 } } }
    return original.apply(this, arguments)
  }
  try {
    delete require.cache[require.resolve(target)]
    const api = require(target); assert.equal(typeof api.getBarrelHandoverEvidence, 'function')
    await api.getBarrelHandoverEvidence(77); assert.equal(calls[0][0], '/api/barrels/records/77/handover-evidence')
  } finally { Module._load = original; delete require.cache[require.resolve(target)] }
})
;(async () => { const done = armWatchdog(30000); let passed = 0; for (const t of tests) { await t.run(); console.log('PASS ' + t.name); passed++ } done(); console.log('AQUAFLOW_SUITE_OK ' + passed) })().catch(error => { console.error(error); process.exit(1) })
