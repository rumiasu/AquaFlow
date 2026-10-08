const assert = require('assert')
const { loadPage, createWx, createApp, armWatchdog } = require('./harness')
const tick = () => new Promise(resolve => setImmediate(resolve))
const clone = value => JSON.parse(JSON.stringify(value))
const ok = data => ({ code: 0, data })
const event = id => ({ currentTarget: { dataset: { id } } })
function record(status = 'APPROVED', extra = {}) {
  return Object.assign({ id: 77, type: 2, status: status === 'RECEIVED' ? 2 : status === 'REFUNDED' ? 3 : 1,
    stationId: 1, customerId: 7, productId: 10, quantity: 2, depositRefund: 60,
    createTime: '2026-10-07T09:00:00', statusText: '真实状态文案',
    returnDetail: { status, pickupMode: 'STORE', pickupModeText: '到店退桶', requiredBarrels: 2, receivedBarrels: 0, pickupFee: 0, feePaymentId: null,
      approvedTime: status !== 'APPLIED' ? '2026-10-07T09:10:00' : null,
      customerConfirmedTime: status !== 'APPLIED' ? '2026-10-07T09:20:00' : null }
  }, extra)
}
function setup(initial, options = {}) {
  let current = clone(initial), readFail = false, eligibilityFail = false, pendingWrite
  const calls = { writes: [], reads: 0, eligibility: 0, modals: [] }
  const app = createApp({ globalData: { isLogin: true, userInfo: { staffId: 1, stationId: 1, role: 'STATION_MANAGER' } } })
  const wx = createWx()
  wx.showModal = modal => { calls.modals.push(modal); if (options.autoModal !== false) modal.success({ confirm: true, content: options.modalContent || '0' }) }
  function eligibility() {
    const d = current.returnDetail
    return Object.assign({ recordId: current.id, recordStatus: current.status, detailStatus: d && d.status || null,
      available: d ? d.status === 'RECEIVED' : current.status === 2, channel: 'CASH', refundAmount: 60, productName: '真实商品名称', reason: '资格已核对' }, options.eligibility || {})
  }
  function mutate(kind) {
    const d = current.returnDetail
    if (kind === 2) { current.status = 2; if (d) { d.status = 'RECEIVED'; d.receivedTime = '2026-10-07T10:00:00'; d.receivedBarrels = d.requiredBarrels } }
    if (kind === 3 || kind === 'paid') { current.status = 3; current.refundPaidTime = '2026-10-07T10:10:00'; if (d) d.status = 'REFUNDED' }
    if (kind === 4) { current.status = 4; if (d) d.status = 'REJECTED' }
    if (kind === 'approve') { d.status = 'APPROVED'; d.customerConfirmedTime = null }
    if (kind === 'collect') d.feePaymentStatus = 2
    if (kind === 'feeRefund') d.feePaymentStatus = 3
  }
  async function write(kind, id, extra) {
    calls.writes.push({ kind, id, extra })
    if (options.deferWrite) await new Promise(resolve => { pendingWrite = resolve })
    if (!options.failWrite || options.commitThenFail) mutate(kind)
    if (options.failWrite) throw new Error('网络超时，未收到办理结果')
    return ok({})
  }
  const page = loadPage('miniapp-delivery/pages/station-mgmt/barrel-return/index.js', { wx, app, stubs: {
    'api/station-mgmt': {
      getAllBarrelRecords: async () => { if (readFail) throw Error('offline'); return ok([clone(current)]) },
      getRefundUndelivered: async () => ok({ records: [], count: 0, amount: 0 }),
      getBarrelRefundEligibility: async () => { calls.eligibility++; if (eligibilityFail) throw Error('原款读取失败'); if (options.deferEligibility) return options.deferEligibility(eligibility()); return ok(eligibility()) },
      updateBarrelRecordStatus: (id, kind, extra) => write(kind, id, extra),
      approveBarrelReturn: (id, fee) => write('approve', id, fee), confirmPayment: id => write('collect', id), markRefundPaid: id => write('paid', id)
    },
    'api/business-rules': { refundService: id => write('feeRefund', id) },
    'utils/pending-reminder': { syncPendingReminder: async () => {}, getPendingReturnRecord: async id => {
      calls.reads++; if (readFail) throw Error('offline'); assert.equal(String(id), String(current.id)); return ok(clone(current))
    } }
  } })
  return { page, calls, wx, app, current: () => current, setReadFail: v => { readFail = v }, setEligibilityFail: v => { eligibilityFail = v }, releaseWrite: () => pendingWrite && pendingWrite(),
    async ready() { await page.loadData(); await page.onSelectRecord(event(current.id)); await tick() } }
}

;(async () => {
  const done = armWatchdog(); let count = 0
  async function test(name, fn) { await fn(); count++; console.log('ok ' + count + ' ' + name) }
  await test('new zero-barrel handover says no actual barrels and only writes handover', async () => {
    const r = record(); r.returnDetail.requiredBarrels = 0
    const t = setup(r, { autoModal: false }); await t.ready()
    assert.equal(t.page.data.selected.requiredText, '无需实际交桶')
    assert.equal(t.page.data.selected.primaryAction, 'receive')
    const action = t.page.onApprove(event(77)); await tick()
    assert.match(t.calls.modals[0].content, /无需实际收到空桶/)
    t.calls.modals[0].success({ confirm: true }); await action
    assert.deepEqual(t.calls.writes.map(w => w.kind), [2]); assert.equal(t.page.data.selected.needsRefund, true)
  })
  await test('unconfirmed customer cannot receive or collect', async () => {
    const r = record(); r.returnDetail.customerConfirmedTime = null
    const t = setup(r); await t.ready(); await t.page.onApprove(event(77)); await t.page.onCollectPickupFee(event(77))
    assert.equal(t.page.data.selected.primaryAction, ''); assert.equal(t.calls.writes.length, 0)
  })
  await test('pending fee has one current action and cannot handover before collected', async () => {
    const r = record(); Object.assign(r.returnDetail, { pickupMode: 'PICKUP', feePaymentId: 88, pickupFee: 8, feePaymentStatus: 1 })
    const t = setup(r); await t.ready(); assert.equal(t.page.data.selected.primaryAction, 'collect')
    await t.page.onApprove(event(77)); assert.equal(t.calls.writes.length, 0)
    await t.page.onCollectPickupFee(event(77)); assert.equal(t.calls.writes[0].id, 88); assert.equal(t.page.data.selected.primaryAction, 'receive')
  })
  await test('refunded fee blocks receive but retains unreceived rejection', async () => {
    const r = record(); Object.assign(r.returnDetail, { feePaymentId: 88, pickupFee: 8, feePaymentStatus: 3 })
    const t = setup(r); await t.ready(); await t.page.onApprove(event(77))
    assert.equal(t.calls.writes.length, 0); assert.equal(t.page.data.selected.canReject, true)
  })
  await test('fee refund is separate from deposit and unlocks pre-handover rejection', async () => {
    const r = record(); Object.assign(r.returnDetail, { feePaymentId: 88, pickupFee: 8, feePaymentStatus: 2 })
    const t = setup(r); await t.ready(); await t.page.onReject(event(77)); assert.equal(t.calls.writes.length, 0)
    await t.page.onRefundPickupFee(event(77)); assert.equal(t.calls.writes[0].kind, 'feeRefund'); assert.equal(t.calls.writes[0].id, 88)
    assert.equal(t.page.data.selected.canReject, true); assert.equal(t.current().returnDetail.status, 'APPROVED')
  })
  await test('received new request cannot reject, historical confirmed request still can', async () => {
    const t = setup(record('RECEIVED')); await t.ready(); await t.page.onReject(event(77)); assert.equal(t.calls.writes.length, 0)
    const old = setup(record('RECEIVED', { returnDetail: null })); await old.ready(); await old.page.onReject(event(77)); assert.equal(old.calls.writes[0].kind, 4)
  })
  await test('cash handover gets fresh server channel and exact amount before modal', async () => {
    const t = setup(record('RECEIVED'), { eligibility: { refundAmount: 57.25 } }); await t.ready()
    const initialReads = t.calls.eligibility; await t.page.onRefund(event(77))
    assert(t.calls.eligibility > initialReads); assert.match(t.calls.modals[0].content, /57.25/)
    assert.deepEqual(t.calls.writes[0].extra, { refundChannel: 'CASH' }); assert.equal(t.current().returnDetail.status, 'REFUNDED')
  })
  await test('online unavailable gives reason without cash option or financial write', async () => {
    const t = setup(record('RECEIVED'), { eligibility: { available: false, channel: 'ONLINE', reason: '线上尚不可用，请勿改交现金' } }); await t.ready(); await t.page.onRefund(event(77))
    assert.equal(t.calls.modals.length, 0); assert.equal(t.calls.writes.length, 0); assert.match(t.page.data.actionError, /线上尚不可用/)
  })
  await test('known available online uses original channel only', async () => {
    const t = setup(record('RECEIVED'), { eligibility: { channel: 'ONLINE' } }); await t.ready(); await t.page.onRefund(event(77))
    assert.deepEqual(t.calls.writes[0].extra, { refundChannel: 'ONLINE' }); assert(!t.calls.modals[0].content.includes('已实际把'))
  })
  await test('zero refund has no cash handover instruction', async () => {
    const t = setup(record('RECEIVED'), { eligibility: { refundAmount: 0 } }); await t.ready(); await t.page.onRefund(event(77))
    assert.match(t.calls.modals[0].content, /无需实际支付现金/); assert.equal(t.calls.writes[0].kind, 3)
  })
  await test('unknown source, missing amount, wrong record and stale status fail closed', async () => {
    for (const eligibility of [{ channel: 'UNKNOWN' }, { refundAmount: null }, { recordId: 99 }, { detailStatus: 'REFUNDED' }]) {
      const t = setup(record('RECEIVED'), { eligibility }); await t.ready(); await t.page.onRefund(event(77)); assert.equal(t.calls.writes.length, 0); assert.equal(t.calls.modals.length, 0)
    }
  })
  await test('read failure preserves old records but removes executable actions', async () => {
    const t = setup(record('RECEIVED')); await t.ready(); t.setReadFail(true); await t.page.loadData(); await t.page.onRefund(event(77))
    assert.equal(t.page.data.list.length, 1); assert.equal(t.page.data.recordsReady, false); assert(t.page.data.loadError); assert.equal(t.calls.writes.length, 0)
  })
  await test('refund eligibility failure has retry and no cash handover modal', async () => {
    const t = setup(record('RECEIVED')); await t.ready(); t.setEligibilityFail(true); await t.page.onRefund(event(77))
    assert(t.page.data.eligibilityError); assert.equal(t.calls.modals.length, 0); assert.equal(t.page.data.actionBusy, false)
  })
  await test('lock starts before modal and duplicate tap cannot open another one', async () => {
    const t = setup(record('RECEIVED'), { autoModal: false }); await t.ready()
    const first = t.page.onRefund(event(77)); await tick(); const second = t.page.onRefund(event(77)); await second
    assert.equal(t.calls.modals.length, 1); assert.equal(t.page.data.actionBusy, true)
    t.calls.modals[0].success({ confirm: false }); await first; assert.equal(t.page.data.actionBusy, false); assert.equal(t.calls.writes.length, 0)
  })
  await test('modal platform failure releases lock without writing', async () => {
    const t = setup(record('RECEIVED'), { autoModal: false }); await t.ready(); const first = t.page.onRefund(event(77)); await tick()
    t.calls.modals[0].fail({ errMsg: 'platform failure' }); await first
    assert.equal(t.page.data.actionBusy, false); assert.equal(t.calls.writes.length, 0)
  })
  await test('hide during modal cancels late confirmation instead of submitting', async () => {
    const t = setup(record('RECEIVED'), { autoModal: false }); await t.ready(); const first = t.page.onRefund(event(77)); await tick(); t.page.onHide()
    t.calls.modals[0].success({ confirm: true }); await first; assert.equal(t.calls.writes.length, 0); assert.equal(t.page.data.actionBusy, false)
  })
  await test('session changes invalidate late money confirmation; token renewal does not', async () => {
    const t = setup(record('RECEIVED'), { autoModal: false }); await t.ready(); const first = t.page.onRefund(event(77)); await tick()
    t.app._loginGeneration = 1; t.calls.modals[0].success({ confirm: true }); await first; assert.equal(t.calls.writes.length, 0)
    const renewal = setup(record('RECEIVED'), { autoModal: false }); await renewal.ready(); const second = renewal.page.onRefund(event(77)); await tick()
    renewal.wx.setStorageSync('aq_delivery_accessToken', 'renewed'); renewal.calls.modals[0].success({ confirm: true }); await second; assert.equal(renewal.calls.writes.length, 1)
  })
  await test('identity changed before tap cannot use former account records', async () => {
    const t = setup(record('RECEIVED')); await t.ready(); t.app.globalData.userInfo.stationId = 2
    await t.page.onRefund(event(77)); assert.equal(t.calls.modals.length, 0); assert.equal(t.calls.writes.length, 0)
  })
  await test('in-flight write cannot be repeated; result is read by original id', async () => {
    const t = setup(record('RECEIVED'), { deferWrite: true }); await t.ready(); const first = t.page.onRefund(event(77)); await tick()
    await t.page.onRefund(event(77)); assert.equal(t.calls.writes.length, 1); assert.equal(t.page.data.recoveryNeeded, true)
    t.releaseWrite(); await first; assert.equal(t.calls.reads, 1); assert.equal(t.page.data.recoveryNeeded, false)
  })
  await test('timeout after server commit recovers original result without refund replay', async () => {
    const t = setup(record('RECEIVED'), { failWrite: true, commitThenFail: true }); await t.ready(); await t.page.onRefund(event(77))
    assert.equal(t.calls.writes.length, 1); assert.equal(t.calls.reads, 1); assert.equal(t.page.data.selected.needsRefund, false); assert.equal(t.page.data.actionError, '')
  })
  await test('success then read failure stays blocked until original result retry', async () => {
    const t = setup(record('RECEIVED'), { deferWrite: true }); await t.ready(); const first = t.page.onRefund(event(77)); await tick(); t.setReadFail(true); t.releaseWrite(); await first
    assert.equal(t.page.data.recoveryNeeded, true); assert.equal(t.page.data.recordsReady, false)
    await t.page.onRefund(event(77)); assert.equal(t.calls.writes.length, 1)
    t.setReadFail(false); await t.page.onRetry(); assert.equal(t.page.data.recoveryNeeded, false); assert.equal(t.page.data.actionBusy, false); assert.equal(t.calls.writes.length, 1)
  })
  await test('failed write with unreadable result cannot retry money before original read', async () => {
    const t = setup(record('RECEIVED'), { deferWrite: true, failWrite: true }); await t.ready(); const first = t.page.onRefund(event(77)); await tick(); t.setReadFail(true); t.releaseWrite(); await first
    await t.page.onRefund(event(77)); assert.equal(t.calls.writes.length, 1)
    t.setReadFail(false); await t.page.onRetry(); assert.equal(t.calls.writes.length, 1); assert.match(t.page.data.actionError, /勿重复交款/)
  })
  await test('hide during write keeps recovery responsibility and show reads original result', async () => {
    const t = setup(record('RECEIVED'), { deferWrite: true }); await t.ready(); const first = t.page.onRefund(event(77)); await tick(); t.page.onHide(); t.releaseWrite(); await first
    assert.equal(t.page.data.recoveryNeeded, true); await t.page.onShow(); assert.equal(t.calls.writes.length, 1); assert.equal(t.page.data.selected.needsRefund, false)
  })
  await test('late original eligibility cannot replace another selected request', async () => {
    let resolver
    const t = setup(record('RECEIVED'), { deferEligibility: data => new Promise(resolve => { resolver = () => resolve(ok(data)) }) })
    await t.page.loadData(); const pending = t.page.onSelectRecord(event(77)); await tick()
    const other = t.page.decorateRecord(record('APPLIED', { id: 78 })); t.page.setData({ list: [other], selectedId: 78 }); t.page.refreshSelected()
    resolver(); await pending; assert.equal(t.page.data.selected.id, 78); assert.equal(t.page.data.eligibility, null)
  })
  await test('history supplement writes fact only and does not refund again', async () => {
    const t = setup(record('REFUNDED', { returnDetail: null })); await t.ready(); await t.page.onConfirmPaid(event(77))
    assert.deepEqual(t.calls.writes.map(w => w.kind), ['paid']); assert.equal(t.page.data.selected.primaryAction, '')
  })
  await test('non-return record has no refund timeline or financial action', async () => {
    const t = setup(record('REFUNDED', { type: 8, returnDetail: null })); await t.page.loadData(); await t.page.onSelectRecord(event(77)); await t.page.onRefund(event(77))
    assert.equal(t.page.data.selected, null); assert.equal(t.page.data.list[0].timeline.length, 0); assert.equal(t.calls.writes.length, 0)
  })
  await test('timeline only shows actual facts and preserves legacy three-step structure', async () => {
    const t = setup(record()); await t.ready(); assert.equal(t.page.data.selected.timeline.length, 5); assert.equal(t.page.data.selected.timeline[3].done, false)
    const legacy = setup(record('RECEIVED', { returnDetail: null })); await legacy.ready(); assert.equal(legacy.page.data.selected.timeline.length, 3)
  })
  await test('unknown required-barrel count cannot approve or receive', async () => {
    for (const state of ['APPLIED', 'APPROVED']) {
      const r = record(state); r.returnDetail.requiredBarrels = null
      const t = setup(r); await t.ready(); await t.page.onApproveArrangement(event(77)); await t.page.onApprove(event(77))
      assert.equal(t.calls.writes.length, 0); assert.equal(t.page.data.selected.primaryAction, '')
    }
  })
  done(); console.log('AQUAFLOW_SUITE_OK ' + count)
})().catch(error => { console.error(error); process.exit(1) })
