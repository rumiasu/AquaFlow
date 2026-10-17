const assert = require('assert')
const { loadPage, createWx, createApp, armWatchdog } = require('./harness')
const { STORAGE_KEYS } = require('../../miniapp-delivery/utils/storage-keys')
const tick = () => new Promise(resolve => setImmediate(resolve))
const clone = value => JSON.parse(JSON.stringify(value))
const ok = data => ({ code: 0, data })
const event = id => ({ currentTarget: { dataset: { id } } })
function record(status = 'APPROVED', extra = {}) {
  return Object.assign({ id: 77, type: 2, status: status === 'RECEIVED' ? 2 : status === 'REFUNDED' ? 3 : 1,
    stationId: 1, customerId: 7, productId: 10, quantity: 2, depositRefund: 60,
    createTime: '2026-10-07T09:00:00', statusText: '真实状态文案',
    returnDetail: { status, pickupMode: 'STORE', pickupModeText: '到店退桶', requiredBarrels: 2, receivedBarrels: 0, pickupFee: 0, feePaymentId: null, customerConfirmationRequired: false, arrangementVersion: 1, customerConfirmationCurrent: status !== 'APPLIED',
      approvedTime: status !== 'APPLIED' ? '2026-10-07T09:10:00' : null,
      customerConfirmedTime: status !== 'APPLIED' ? '2026-10-07T09:20:00' : null }
  }, extra)
}
function setup(initial, options = {}) {
  let current = clone(initial), readFail = false, eligibilityFail = false, pendingWrite
  const calls = { writes: [], reads: 0, eligibility: 0, modals: [] }
  const app = options.app || createApp({ globalData: { isLogin: true, userInfo: { staffId: 1, stationId: 1, role: 'STATION_MANAGER' } } })
  const wx = options.wx || createWx()
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
    if (kind === 'approve') { d.status = 'APPROVED'; d.customerConfirmedTime = null; d.customerConfirmationCurrent = false }
    if (kind === 'arrangement') { d.status = 'APPLIED'; d.arrangementVersion++; d.customerConfirmationRequired = true; d.customerConfirmationCurrent = false; d.customerConfirmedTime = null }
    if (kind === 'collect') d.feePaymentStatus = 2
    if (kind === 'feeRefund') d.feePaymentStatus = 3
  }
  async function write(kind, id, extra) {
    calls.writes.push({ kind, id, extra })
    if (options.deferWrite) await new Promise(resolve => { pendingWrite = resolve })
    if (options.businessCode) return { code: 1, message: '原渠道明确拒绝，本次未办理' }
    if ((!options.failWrite || options.commitThenFail) && !options.delayedCommit) mutate(kind)
    if (options.failWrite) throw options.writeError || new Error('网络超时，未收到办理结果')
    return ok({})
  }
  const page = loadPage('miniapp-delivery/pages/station-mgmt/barrel-return/index.js', { wx, app, stubs: {
    'api/station-mgmt': {
      getAllBarrelRecords: async () => { if (readFail) throw Error('offline'); return ok([clone(current)]) },
      getRefundUndelivered: async () => ok({ records: [], count: 0, amount: 0 }),
      getBarrelRefundEligibility: async () => { calls.eligibility++; if (eligibilityFail) throw Error('原款读取失败'); if (options.deferEligibility) return options.deferEligibility(eligibility()); return ok(eligibility()) },
      updateBarrelRecordStatus: (id, kind, extra) => write(kind, id, extra),
      approveBarrelReturn: (id, fee) => write('approve', id, fee), confirmPayment: id => write('collect', id), markRefundPaid: id => write('paid', id),
      changeBarrelReturnArrangement: (id, body) => write('arrangement',id,body), getReturnCompanionOrders: async () => ok(options.companionOrders || [])
    },
    'api/business-rules': { refundService: id => write('feeRefund', id), getReturnApplications: async args => {
      if (readFail) throw Error('offline')
      return ok({ stationId: 1, scope: args.scope, limit: 50, items: [clone(current)], nextBeforeId: null })
    } },
    'utils/pending-reminder': { syncPendingReminder: async () => {}, getPendingReturnRecord: async id => {
      calls.reads++; if (readFail) throw Error('offline'); assert.equal(String(id), String(current.id)); return ok(clone(current))
    } }
  } })
  return { page, calls, wx, app, current: () => current, commitRefund: () => mutate(3), setReadFail: v => { readFail = v }, setEligibilityFail: v => { eligibilityFail = v }, releaseWrite: () => pendingWrite && pendingWrite(),
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
  await test('free original arrangement can hand over without recording customer consent', async () => {
    for (const mode of ['STORE', 'PICKUP', 'COMBINED']) {
      const r = record(); Object.assign(r.returnDetail, { pickupMode: mode, customerConfirmedTime: null })
      const t = setup(r); await t.ready()
      assert.equal(t.page.data.selected.primaryAction, 'receive')
      assert(!t.page.data.selected.timeline.some(s => s.title === '客户确认安排'))
      await t.page.onApprove(event(77))
      assert.equal(t.calls.writes[0].kind, 2); assert.equal(t.current().returnDetail.customerConfirmedTime, null)
    }
  })
  await test('new fee or missing permission projection cannot receive or collect without consent', async () => {
    for (const required of [true, undefined]) {
    const r = record(); Object.assign(r.returnDetail, { customerConfirmationRequired: required, customerConfirmationCurrent: false, customerConfirmedTime: null, pickupFee: 8, feePaymentId: 88, feePaymentStatus: 1 })
    const t = setup(r); await t.ready(); await t.page.onApprove(event(77)); await t.page.onCollectPickupFee(event(77))
    assert.equal(t.page.data.selected.primaryAction, ''); assert.equal(t.calls.writes.length, 0)
    }
  })
  await test('pending fee has one current action and cannot handover before collected', async () => {
    const r = record(); Object.assign(r.returnDetail, { pickupMode: 'PICKUP', feePaymentId: 88, pickupFee: 8, feePaymentStatus: 1 })
    const t = setup(r); await t.ready(); assert.equal(t.page.data.selected.primaryAction, 'collect')
    await t.page.onApprove(event(77)); assert.equal(t.calls.writes.length, 0)
    await t.page.onCollectPickupFee(event(77)); assert.equal(t.calls.writes[0].id, 88); assert.equal(t.page.data.selected.primaryAction, 'receive')
  })
  await test('manager change preserves original record target and submits inspected version and reason', async () => {
    const t=setup(record(),{modalContent:'客户希望独立上门'}); await t.ready()
    t.wx.showActionSheet=o=>o.success({tapIndex:1})
    await t.page.onChangeArrangement(event(77))
    assert.equal(t.calls.writes.length,1); const write=t.calls.writes[0]
    assert.equal(write.id,77); assert.equal(write.kind,'arrangement'); assert.equal(write.extra.expectedVersion,1)
    assert.equal(write.extra.pickupMode,'PICKUP'); assert.equal(write.extra.companionOrderId,null); assert.equal(write.extra.reason,'客户希望独立上门')
    assert.equal(t.current().returnDetail.customerConfirmedTime,null); assert.equal(t.current().returnDetail.customerConfirmationCurrent,false)
    assert.equal(t.current().depositRefund,60); assert.equal(t.current().quantity,2)
  })
  await test('paid old fee and received request cannot change arrangement or create refunds', async () => {
    for(const r of [record('RECEIVED'),record()]) {
      if(r.returnDetail.status==='APPROVED')Object.assign(r.returnDetail,{feePaymentId:88,feePaymentStatus:2})
      const t=setup(r); await t.ready(); await t.page.onChangeArrangement(event(77)); assert.equal(t.calls.writes.length,0)
    }
  })
  await test('manager combined mode requires a deliberate order choice and excludes other owners', async () => {
    const t=setup(record(),{modalContent:'旧单取消，客户申请改为此单',companionOrders:[{id:900,customerId:8,stationId:1,status:1},{id:901,customerId:7,stationId:1,status:2}]}); await t.ready()
    const choices=[2,0], sheets=[];t.wx.showActionSheet=o=>{sheets.push(o.itemList);o.success({tapIndex:choices.shift()})}
    await t.page.onChangeArrangement(event(77));assert.equal(sheets.length,2);assert.deepEqual(sheets[1],['订单 901']);assert.equal(t.calls.writes[0].extra.companionOrderId,901)
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
  // 原桌面时序：POST 超时 -> GET 仍 RECEIVED -> 原事务随后提交 -> GET REFUNDED。
  // 不能只测“先提交再丢响应”；那会漏掉旧 GET 解锁后再次提示交现金的窗口。
  await test('timeout before commit keeps new and historical refunds query-only until original result arrives', async () => {
    for (const legacy of [false, true]) {
      const t = setup(record('RECEIVED', legacy ? { returnDetail: null } : {}), { failWrite: true }); await t.ready()
      await t.page.onRefund(event(77))
      assert.equal(t.page.data.recoveryNeeded, true); assert(t.page._pendingAction)
      assert.equal(t.page.data.eligibility, null); assert.match(t.page.data.actionError, /勿重复交款/)
      const eligibilityReads = t.calls.eligibility
      await t.page.onRefund(event(77)); await t.page.onPrimary(); await t.page.onRetryEligibility()
      await t.page.onRetry(); await t.page.loadData(); await t.page.onRefund(event(77))
      assert.equal(t.calls.writes.length, 1); assert.equal(t.calls.modals.length, 1)
      assert.equal(t.calls.eligibility, eligibilityReads); assert.equal(t.page.data.recoveryNeeded, true)
      assert.equal(t.page.data.selected.primaryAction, '')
      t.commitRefund(); await t.page.onRetry()
      assert.equal(t.page.data.recoveryNeeded, false); assert.equal(t.page._pendingAction, null)
      assert.equal(t.page.data.selected.needsRefund, false); assert.equal(t.calls.writes.length, 1)
      assert.equal(t.wx.getStorageSync(STORAGE_KEYS.BARREL_REFUND_RECOVERY + '1:1'), '')
    }
  })
  await test('even a successful write response cannot unlock a stale pre-commit read', async () => {
    const t = setup(record('RECEIVED'), { delayedCommit: true }); await t.ready(); await t.page.onRefund(event(77))
    assert.equal(t.page.data.recoveryNeeded, true); await t.page.onRefund(event(77)); assert.equal(t.calls.writes.length, 1)
    t.commitRefund(); await t.page.onRetry(); assert.equal(t.page.data.recoveryNeeded, false)
  })
  await test('explicit business refusal is distinct from unknown transport or system failure', async () => {
    for (const options of [{ businessCode: true }, { failWrite: true, writeError: Object.assign(Error('渠道明确拒绝'), { businessRejected: true }) }]) {
      const t = setup(record('RECEIVED'), options); await t.ready(); await t.page.onRefund(event(77)); await tick()
      assert.equal(t.page.data.recoveryNeeded, false); assert.equal(t.calls.writes.length, 1)
      assert.equal(t.wx.getStorageSync(STORAGE_KEYS.BARREL_REFUND_RECOVERY + '1:1'), '')
    }
    const t = setup(record('RECEIVED'), { failWrite: true, writeError: Object.assign(Error('处理中断'), { backendCode: 500 }) })
    await t.ready(); await t.page.onRefund(event(77)); assert.equal(t.page.data.recoveryNeeded, true)
  })
  await test('hide/show and a fresh page/app instance retain the exact unresolved refund number', async () => {
    const t = setup(record('RECEIVED'), { failWrite: true }); await t.ready(); await t.page.onRefund(event(77))
    t.page.onHide(); await t.page.onShow(); assert.equal(t.page.data.recoveryNeeded, true)
    t.page.onUnload()
    const reopened = setup(t.current(), { wx: t.wx }); reopened.page.onLoad({ recordId: '999' }); await reopened.page.onShow()
    assert.equal(String(reopened.page.data.selectedId), '77'); assert.equal(reopened.page.data.recoveryNeeded, true)
    await reopened.page.onRefund(event(77)); assert.equal(reopened.calls.writes.length, 0); assert.equal(reopened.calls.modals.length, 0)
    reopened.commitRefund(); await reopened.page.onRetry()
    assert.equal(reopened.page.data.selected.needsRefund, false); assert.equal(reopened.page.data.recoveryNeeded, false)
    assert.equal(reopened.calls.writes.length, 0)
  })
  await test('a durable unknown receipt is isolated from another employee or station', async () => {
    for (const userInfo of [{ staffId: 2, stationId: 1, role: 'STATION_MANAGER' }, { staffId: 1, stationId: 2, role: 'STATION_MANAGER' }]) {
      const t = setup(record('RECEIVED'), { failWrite: true }); await t.ready(); await t.page.onRefund(event(77))
      t.page.onUnload()
      const other = setup(record('RECEIVED'), { wx: t.wx, app: createApp({ globalData: { isLogin: true, userInfo } }) })
      assert.equal(other.page.restoreRefundRecovery(), true); assert.equal(other.page._pendingAction, undefined)
      assert.equal(t.wx.getStorageSync(STORAGE_KEYS.BARREL_REFUND_RECOVERY + '1:1').recordId, '77')
    }
  })
  await test('storage failure stops cash instructions before the native confirmation or write', async () => {
    const t = setup(record('RECEIVED')); await t.ready(); t.wx.setStorageSync = () => { throw Error('disk unavailable') }
    await t.page.onRefund(event(77))
    assert.equal(t.calls.modals.length, 0); assert.equal(t.calls.writes.length, 0); assert.match(t.page.data.actionError, /请勿交款/)
  })
  await test('a storage write that persisted then threw restores query-only recovery without cash instructions', async () => {
    const t = setup(record('RECEIVED')); await t.ready(); const set = t.wx.setStorageSync
    t.wx.setStorageSync = (key, value) => { set(key, value); throw Error('write confirmation unavailable') }
    await t.page.onRefund(event(77))
    assert.equal(t.calls.modals.length, 0); assert.equal(t.calls.writes.length, 0)
    assert.equal(t.page.data.recoveryNeeded, true); assert.equal(t.page.data.actionBusy, false)
    await t.page.onRetry(); assert.equal(t.page.data.recoveryNeeded, true); assert.equal(t.calls.writes.length, 0)
  })
  await test('unreadable/corrupt recovery storage fails closed; a repaired read allows normal loading', async () => {
    const t = setup(record('RECEIVED')), get = t.wx.getStorageSync
    t.wx.getStorageSync = () => { throw Error('read unavailable') }; await t.page.onShow()
    assert.equal(t.page.data.recordsReady, false); assert.equal(t.page.data.recoveryNeeded, true)
    t.wx.getStorageSync = get; t.wx.__storage.set(STORAGE_KEYS.BARREL_REFUND_RECOVERY + '1:1', { version: 1, actor: '1:1', recordId: 'bad' })
    await t.page.onRetry(); assert.equal(t.page.data.recordsReady, false); await t.page.onRefund(event(77)); assert.equal(t.calls.writes.length, 0)
    t.wx.__storage.clear(); await t.page.onRetry(); assert.equal(t.page.data.recordsReady, true); assert.equal(t.page.data.recoveryNeeded, false)
  })
  await test('cancelled confirmation clears its preflight receipt and still makes no write', async () => {
    const t = setup(record('RECEIVED'), { autoModal: false }); await t.ready(); const task = t.page.onRefund(event(77)); await tick()
    assert.equal(t.wx.getStorageSync(STORAGE_KEYS.BARREL_REFUND_RECOVERY + '1:1').recordId, '77')
    t.calls.modals[0].success({ confirm: false }); await task
    assert.equal(t.wx.getStorageSync(STORAGE_KEYS.BARREL_REFUND_RECOVERY + '1:1'), ''); assert.equal(t.calls.writes.length, 0)
  })
  await test('hide/show before the old modal callback cannot turn an unsubmitted confirmation into an unknown refund', async () => {
    const t = setup(record('RECEIVED'), { autoModal: false }); await t.ready(); const task = t.page.onRefund(event(77)); await tick()
    t.page.onHide(); await t.page.onShow(); assert.equal(t.page.data.recoveryNeeded, false)
    t.calls.modals[0].success({ confirm: true }); await task
    assert.equal(t.calls.writes.length, 0); assert.equal(t.wx.getStorageSync(STORAGE_KEYS.BARREL_REFUND_RECOVERY + '1:1'), '')
  })
  await test('receipt cleanup failure after final result stays safe and can be retried without refund replay', async () => {
    const t = setup(record('RECEIVED')); await t.ready(); const remove = t.wx.removeStorageSync
    t.wx.removeStorageSync = () => { throw Error('remove unavailable') }; await t.page.onRefund(event(77))
    assert.equal(t.page.data.recoveryNeeded, true); await t.page.onRefund(event(77)); assert.equal(t.calls.writes.length, 1)
    t.wx.removeStorageSync = remove; await t.page.onRetry(); assert.equal(t.page.data.recoveryNeeded, false); assert.equal(t.calls.writes.length, 1)
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
