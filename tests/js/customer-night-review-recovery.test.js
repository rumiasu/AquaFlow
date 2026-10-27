// Astra 六个反例的修后断言及正常对照；实际 request/API 链路的 wx 传输全为离线替身。
const assert = require('assert'), path = require('path')
const { loadPage, createWx, createApp, armWatchdog, ROOT } = require('./harness')
const done = armWatchdog(30000), tests = []
const test = (name, run) => tests.push({ name, run })
const ok = data => ({ code: 0, data })
const tick = () => new Promise(resolve => setImmediate(resolve))
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
const event = data => ({ currentTarget: { dataset: data } })
function fixture(route, stubs = {}, env = {}) {
  global.__wxConfig = { envVersion: 'develop' }
  const wx = env.wx || createWx(), app = env.app || createApp()
  if (!wx.getStorageSync('selectedStation')) wx.setStorageSync('selectedStation', { id: 11, name: 'A站', status: 1 })
  wx.stopPullDownRefresh = () => {}
  app.getCurrentStationId = () => wx.getStorageSync('selectedStation').id
  delete require.cache[require.resolve(path.join(ROOT, 'miniapp-user/utils/asset-view-station'))]
  const page = loadPage('miniapp-user/pages/' + route + '.js', { wx, app, stubs })
  // 微信 setData 的路径语义，仅在本套件补齐，不改公共测试骨架。
  page.setData = function(patch, callback) {
    for (const [key, value] of Object.entries(patch)) {
      const parts = key.replace(/\[(\d+)\]/g, '.$1').split('.'); let object = this.data
      for (const part of parts.slice(0, -1)) object = object[part]
      object[parts.at(-1)] = value
    }
    if (callback) callback()
  }
  return { page, wx, app }
}
const summary = { depositBalance: 150, owedBuckets: 0, independentRights: true, rightBuckets: 3, availableRights: 3 }
const holdings = [{ productId: 5, productName: '用户商品 Aqua', independentRights: true, assetQty: 3, heldTotalQty: 3,
  inTransitQty: 0, occupiedQty: 3, owedQty: 0, storageQty: 0, availableRights: 3, deposit: 50 }]
async function barrel(options = {}, env) {
  const state = { failAssets: false, mode: 'timeout', writes: [], errors: [] }
  const t = fixture('barrel/index', { 'api/barrel': {
    getBarrelSummary: async () => { if (state.failAssets) throw Error('网络超时'); return ok(summary) },
    getBarrelRecords: async () => ok([]),
    getBarrelSummaryByType: async () => { if (state.failAssets) throw Error('网络超时'); return ok(holdings) },
    previewBarrelReturn: async (p, q) => ok({ blocked: false, refundAmount: q * 50 }),
    requestBarrelReturn: async (...args) => {
      try { return await require('../../miniapp-user/api/barrel').requestBarrelReturn(...args) }
      catch (error) { state.errors.push(error); throw error }
    }, ...options
  } }, env)
  t.wx.request = request => {
    state.writes.push(JSON.parse(JSON.stringify(request.data)))
    if (state.mode === 'hold') { state.held = request; return }
    if (state.mode === 'timeout') request.fail({ errMsg: 'request:fail timeout' })
    else request.success({ statusCode: 200, data: state.mode === 'rejected'
      ? { code: 1, message: '当前无法办理' } : ok({ recordId: 91 }) })
  }
  t.page.onLoad({}); await t.page.loadData(); t.page.onShowReturnModal(); await tick()
  t.page.onReturnNoteInput({ detail: { value: '客户原留言 User，不翻译' } })
  return { ...t, state }
}
test('R1: incomplete-product retry locks submission until every original item and the new quote are ready', async () => {
  let retry = false, reads = 0; const pending = deferred(), bodies = []
  const t = fixture('order/create', {
    'api/product': { getProductDetail: async id => { reads++; if (retry && id === 5) return pending.promise
      if (!retry && id === 6) throw Error('网络超时'); return ok({ id, name: '用户商品' + id, category: 2, price: 20 }) } },
    'api/payment': { getQuote: async () => ok({ waterAmount: 40, totalAmount: 40, barrelDeposit: 0, extraDeposit: 0,
      deliveryFee: 0, floorFee: 0, methods: [{ id: 1, name: '微信支付', enabled: true }],
      wechatPay: { method: 1, enabled: true, simulated: true, label: '模拟微信支付',
        simulationAuthorization: { version: 1, scope: 'LOCAL_TEST', customerId: 7 } } }) },
    'api/station': { getStationStatus: async () => ok({}) },
    'api/order': { createOrder: body => { bodies.push(body); return new Promise(() => {}) } }
  })
  t.page.setData({ loading: false, stationId: 11, address: { id: 3 }, items: [{ productId: 5, quantity: 1 }, { productId: 6, quantity: 1 }], selectedMethod: 1 })
  await t.page.loadItemsProducts(); await tick(); assert(t.page.data.productsError); assert.equal(t.page.data.quoteReady, false)
  retry = true; const work = t.page.onRetryProducts(), before = reads
  await t.page.onRetryProducts(); assert.equal(reads, before)
  assert.equal(t.page.data.productsLoading, true); assert.equal(t.page._finalSubmissionAllowed(), false)
  t.wx.__modalAutoConfirm = false; await t.page.onSubmit(); assert.equal(bodies.length, 0)
  pending.resolve(ok({ id: 5, name: '用户商品5', category: 2, price: 20 })); await work; await tick()
  assert.equal(t.page.data.productsError, ''); assert.equal(t.page.data.quoteReady, true)
  t.page.onSubmit(); await tick(); assert.deepEqual(bodies[0].items.map(i => i.productId), [5, 6])
})
test('R1 control: a hidden or replaced product read cannot unlock or overwrite the current list', async () => {
  const d = deferred(); const t = fixture('order/create', { 'api/product': { getProductDetail: () => d.promise } })
  t.page.setData({ stationId: 11, items: [{ productId: 5, quantity: 1 }] })
  const read = t.page.loadItemsProducts(); t.page.onHide(); d.resolve(ok({ id: 5, price: 20 })); await read
  assert.equal(t.page.data.productsLoading, false); assert.equal(t.page.data.products.length, 0); assert(t.page.data.productsError)
})
test('R2: real request timeout cannot change original quantity, note, pickup, body or key', async () => {
  const t = await barrel(); await t.page.onSubmitReturn(); const original = JSON.parse(JSON.stringify(t.page.data.pendingReturn))
  assert(t.page.data.returnError.includes('尚未确认')); assert.equal(t.state.errors[0].resultUnknown, undefined)
  t.page.onReturnQtyChange(event({ type: 'add' })); t.page.onReturnNoteInput({ detail: { value: '替换留言' } }); await t.page.onPickupMode()
  assert.equal(t.page.data.returnForm.quantity, 1); assert.equal(t.page.data.returnForm.note, original.body.note)
  await t.page.onSubmitReturn(); assert.equal(t.state.writes.length, 2)
  assert.deepEqual(t.state.writes[0], t.state.writes[1]); assert.deepEqual(t.page.data.pendingReturn, original)
})
test('R2 control: replay rejection preserves original, verified replay receipt clears only that request', async () => {
  const t = await barrel(); await t.page.onSubmitReturn(); const key = t.page.data.pendingReturn.body.idempotencyKey
  t.state.mode = 'rejected'; await t.page.onSubmitReturn()
  assert.equal(t.page.data.pendingReturn.body.idempotencyKey, key); assert.equal(t.state.errors.at(-1).businessRejected, true)
  t.state.mode = 'success'; await t.page.onSubmitReturn(); await tick()
  assert.equal(t.page.data.pendingReturn, null); assert.equal(t.state.writes.length, 3)
  assert(t.state.writes.every(body => body.idempotencyKey === key)); assert(t.wx.__calls.toast.some(v => v.icon === 'success'))
})
test('R2 control: a fresh explicit rejection unlocks editing without deleting another scope', async () => {
  const t = await barrel(); t.state.mode = 'rejected'; await t.page.onSubmitReturn()
  assert.equal(t.page.data.pendingReturn, null); t.page.onReturnQtyChange(event({ type: 'add' })); assert.equal(t.page.data.returnForm.quantity, 2)
})
test('R2 control: repeated taps while a real request is pending send one request', async () => {
  const t = await barrel(); t.state.mode = 'hold'; const work = t.page.onSubmitReturn()
  await t.page.onSubmitReturn(); assert.equal(t.state.writes.length, 1)
  t.state.held.fail({ errMsg: 'request:fail timeout' }); await work; assert(t.page.data.pendingReturn)
})
test('R2 control: another account and another station cannot display, reuse or erase the original request', async () => {
  const t = await barrel(); await t.page.onSubmitReturn(); const original = JSON.parse(JSON.stringify(t.page.data.pendingReturn))
  t.app.globalData.customerId = 8; await t.page.onShow()
  assert.equal(t.page.data.pendingReturn, null); assert.equal(t.page.data.showReturnModal, false); assert.equal(t.page.data.returnForm.note, '')
  t.app.globalData.customerId = 7; await t.page.onShow(); assert.deepEqual(t.page.data.pendingReturn, original)
  t.page.onAssetStationChange({ detail: { id: 22, name: 'B站' } }); await tick(); assert.equal(t.page.data.pendingReturn, null)
  t.page.onAssetStationChange({ detail: { id: 11, name: 'A站' } }); await tick(); assert.deepEqual(t.page.data.pendingReturn, original)
  assert.equal(t.state.writes.length, 1)
})
test('R2 control: hide/show never automatically replays and a late old receipt cannot clear recovery', async () => {
  const t = await barrel(); t.state.mode = 'hold'; const work = t.page.onSubmitReturn()
  const original = t.page.data.pendingReturn; t.page.onHide(); await t.page.onShow()
  t.state.held.success({ statusCode: 200, data: ok({ recordId: 91 }) }); await work
  assert.equal(t.state.writes.length, 1); assert.deepEqual(t.page.data.pendingReturn, original)
  assert(!t.wx.__calls.toast.some(v => v.icon === 'success'))
})
test('R2 control: legacy incomplete or unreadable storage is preserved and sends no request', async () => {
  const t = await barrel(); const legacy = { intent: '11:5:1:STORE:', key: 'original-legacy' }
  t.wx.setStorageSync('barrel-return-intent', legacy); await t.page.loadData(); await t.page.onSubmitReturn()
  assert.equal(t.state.writes.length, 0); assert.deepEqual(t.wx.getStorageSync('barrel-return-intent'), legacy)
  assert(t.page.data.returnRecoveryError); assert.equal(t.page.data.pendingReturn, null)
})
test('R2 control: failed persistence sends no request and retains the exact form', async () => {
  const t = await barrel(); const form = JSON.stringify(t.page.data.returnForm)
  t.wx.setStorageSync = () => { throw Object.assign(Error('保存失败'), { code: 77 }) }
  await t.page.onSubmitReturn(); assert.equal(t.state.writes.length, 0); assert.equal(JSON.stringify(t.page.data.returnForm), form)
})
test('R3: replay business rejection through real request preserves unknown refund dispute and metadata', async () => {
  let mode = 'timeout'; const bodies = [], errors = []
  const t = fixture('service/index', { 'api/feedback': {
    openRefundDispute: async body => { bodies.push({ ...body }); try { return await require('../../miniapp-user/api/feedback').openRefundDispute(body) }
      catch (error) { errors.push(error); throw error } },
    getRefundNotes: async () => { throw Error('退款记录不存在或无权访问') }
  } })
  t.wx.request = request => mode === 'timeout' ? request.fail({ errMsg: 'request:fail timeout' })
    : request.success({ statusCode: 200, data: mode === 'reject' ? { code: 1, message: '退款记录不存在或无权访问' }
      : ok({ id: 31, refundType: 'BARREL_RETURN', refundId: 7, action: 'OPEN' }) })
  t.page.onLoad({ refundType: 'BARREL_RETURN', refundId: '7' })
  t.page.setData({ refundReady: true, dispute: { canOpen: true, version: 0 }, disputeReason: '用户原异议 User' })
  const intent = require('../../miniapp-user/utils/exception-action-intent')
  await t.page.onOpenDispute(); const saved = intent.read('CUSTOMER:7', 'BARREL_RETURN:7', 'OPEN')
  mode = 'reject'; await t.page.onRetryDispute(); assert.deepEqual(intent.read('CUSTOMER:7', 'BARREL_RETURN:7', 'OPEN'), saved)
  assert.equal(t.page.data.pendingDispute, true); assert.equal(errors.at(-1).businessRejected, true)
  mode = 'success'; await t.page.onRetryDispute(); assert.equal(intent.read('CUSTOMER:7', 'BARREL_RETURN:7', 'OPEN'), null)
  assert(bodies.every(body => body.idempotencyKey === saved.idempotencyKey)); assert.equal(t.page.data.pendingDispute, false)
})
test('R3 control: fresh explicit dispute rejection can clear its new intent', async () => {
  const error = Object.assign(Error('当前不能提出异议'), { businessRejected: true, code: 1 })
  const t = fixture('service/index', { 'api/feedback': { openRefundDispute: async () => { throw error }, getRefundNotes: async () => { throw Error('无权访问') } } })
  t.page.onLoad({ refundType: 'BARREL_RETURN', refundId: '7' }); t.page.setData({ refundReady: true, dispute: { canOpen: true, version: 0 }, disputeReason: '原异议' })
  await t.page.onOpenDispute(); assert.equal(t.page.data.pendingDispute, false); assert.equal(error.code, 1); assert.equal(error.businessRejected, true)
})
test('R4: failed switch to B clears real rendered A products; same-scope retry can retain A', async () => {
  let failA = false
  const t = fixture('shop/index', { 'api/product': { getStationProducts: async id => { if (id === 12 || failA) throw Error('网络超时'); return ok([{ id: 5, name: 'A站专属商品', effectivePrice: 20 }]) } },
    'api/station': { getStationStatus: async () => ok({}) } }, { app: createApp({ getCartCount: () => 0 }) })
  t.wx.setStorageSync('stationSwitchNoticeDisabled', true); await t.page.loadData(); assert.equal(t.page.data.visibleProducts.length, 1)
  failA = true; await t.page.loadData(); assert.equal(t.page.data.visibleProducts[0].name, 'A站专属商品')
  t.page.setData({ stationList: [{ id: 11, name: 'A站', status: 1 }, { id: 12, name: 'B站', status: 1 }] })
  await t.page.onSelectStation(event({ id: 12 })); assert.equal(t.page.data.currentStationId, 12)
  assert.deepEqual(t.page.data.visibleProducts, []); assert.deepEqual(t.page.data.allProducts, [])
})
test('R5: template hide/show releases old loading, rereads options and retains exact input', async () => {
  const first = deferred(), second = deferred(); let reads = 0
  const t = fixture('template/index', { 'api/template': { getTemplates: async () => ok([]) },
    'api/product': { getStationProducts: () => ++reads === 1 ? first.promise : second.promise }, 'api/address': { getAddresses: async () => ok([{ id: 3 }]) } })
  await t.page.onShow(); t.page.onAddTemplate(); await tick(); t.page.onNameInput({ detail: { value: '用户原名称 User' } })
  const input = JSON.stringify(t.page.data.form); t.page.onHide(); assert.equal(t.page.data.formLoading, false)
  const shown = t.page.onShow(); await tick(); assert.equal(reads, 2)
  first.resolve(ok([{ id: 99, name: '旧结果' }])); await tick(); assert.equal(t.page.data.formLoading, true)
  second.resolve(ok([{ id: 5, name: '当前商品' }])); await shown
  assert.equal(t.page.data.formReady, true); assert.equal(t.page.data.formLoading, false)
  assert.equal(t.page.data.form.name, JSON.parse(input).name); assert.equal(t.page.data.products[0].id, 5)
})
test('R6: asset refresh immediately invalidates preview; failure blocks submission, successful reread restores it', async () => {
  const t = await barrel(); assert.equal(t.page.data.previewReady, true)
  t.state.failAssets = true; t.page.onPullDownRefresh(); assert.equal(t.page.data.previewReady, false); await tick()
  assert.equal(t.page.data.holdingsFailed, true); await t.page.onSubmitReturn(); assert.equal(t.state.writes.length, 0)
  assert.equal(t.page.data.returnForm.note, '客户原留言 User，不翻译')
  t.state.failAssets = false; await t.page.loadData(); assert.equal(t.page.data.previewReady, false)
  await t.page.refreshPreview(); t.state.mode = 'success'; await t.page.onSubmitReturn(); assert.equal(t.state.writes.length, 1)
})
test('R6 control: prior unknown replay remains exact even if the later asset refresh fails', async () => {
  const t = await barrel(); await t.page.onSubmitReturn(); t.state.failAssets = true; await t.page.loadData()
  await t.page.onSubmitReturn(); assert.equal(t.state.writes.length, 2); assert.deepEqual(t.state.writes[0], t.state.writes[1])
})
test('ticket defensive response branches throw original metadata rather than ReferenceError', async () => {
  const bad = { code: 500, resultUnknown: true, message: '原购买待核实' }, observed = []
  const t = fixture('ticket/index', { 'api/ticket': { purchaseTicket: async () => bad, getTicketPurchaseResult: async () => bad } })
  const saved = { customerId: 7, body: { stationId: 11, productId: 5, quantity: 1, idempotencyKey: 'original-ticket' } }
  t.page.restorePurchaseIntent = () => saved; t.page.savePurchaseIntent = () => {}; t.page.purchaseSummary = () => '原购买'
  const originalLog = console.error; console.error = (...args) => observed.push(args.at(-1))
  try { await t.page.onQueryPurchaseResult(); await t.page.submitPurchaseIntent(saved, true) } finally { console.error = originalLog }
  assert.equal(observed[0], bad); assert.equal(bad.code, 500); assert.equal(bad.resultUnknown, true)
  assert(!observed.some(value => value instanceof ReferenceError)); assert(t.page.data.purchaseRecoveryText.includes('查询失败'))
})
;(async () => {
  let failures = 0
  for (const entry of tests) try { await entry.run(); console.log('PASS ' + entry.name) } catch (error) { failures++; console.error('FAIL ' + entry.name, error.stack) }
  done(); if (failures) process.exitCode = 1
  else console.log('AQUAFLOW_SUITE_OK ' + tests.length)
  console.log('CUSTOMER_NIGHT_REVIEW_RESULT tests=' + tests.length + ' failed=' + failures)
})()
