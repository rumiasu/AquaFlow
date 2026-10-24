'use strict'
// Real Page handlers, isolated storage and a synthetic idempotent server. No network/database.
const assert = require('assert')
const { loadPage, createWx, createApp, armWatchdog } = require('./harness')
const done = armWatchdog(), tests = []
const test = (name, run) => tests.push({ name, run })
const flush = async () => { for (let i = 0; i < 40; i++) await Promise.resolve() }
const clone = value => JSON.parse(JSON.stringify(value))
function fixture(outcomes = ['unknown', 'success'], firstFault = 'normal', method = 2) {
  global.__wxConfig = { envVersion: 'develop' }
  const wx = createWx(), fault = { mode: firstFault, writes: 0 }
  wx.__modalAutoConfirm = false
  const app = createApp({ globalData: { isLogin: true, customerId: 7, tempStationId: 1 } })
  const read = wx.getStorageSync, write = wx.setStorageSync
  const intentKey = key => key === 'orderIntent.v1' || key.startsWith('orderRequest.v2:')
  wx.getStorageSync = key => {
    if (intentKey(key) && (fault.mode === 'read' || (fault.mode === 'readback' && fault.writes > 0))) throw Error('synthetic read failure')
    return read(key)
  }
  wx.setStorageSync = (key, value) => {
    if (intentKey(key)) {
      fault.writes++
      if (fault.mode === 'write') throw Error('synthetic quota')
      if (fault.mode === 'noop') return
      if (fault.mode === 'afterwrite') { write(key, value); throw Error('synthetic post-write failure') }
    }
    write(key, value)
  }
  const calls = [], orders = new Map(), payments = [], controls = { paymentFailure: false }
  let release
  const gate = new Promise(resolve => { release = resolve })
  const stubs = {
    'api/payment': { getQuote: async body => ({ data: { waterAmount: body.items[0].quantity * 20,
      totalAmount: body.items[0].quantity * 20, blocked: false, extraDeposit: 0, barrelPurchases: [],
      methods: [{ id: 2, name: '货到付款', enabled: true }, { id: 3, name: '水票支付', enabled: true }],
      defaultMethod: 2, allowOfflinePayment: true,
      ticketPay: body.paymentMethod === 3 ? { fullyCovered: true, coverQty: 1, coverAmount: 20, payableAmount: 0 } : null } }) },
    'api/order': { createOrder: async body => {
      calls.push(clone(body))
      const result = outcomes.shift() || 'success'
      if (result === 'reject') throw Object.assign(Error('synthetic closed station before idempotency lookup'), { businessRejected: true })
      if (result === 'productRejected') throw Object.assign(Error('商品已下架或停售: Synthetic'), { businessRejected: true })
      if (result === 'addressRejected') throw Object.assign(Error('地址不存在'), { businessRejected: true })
      if (result === 'conflict') throw Object.assign(Error('该幂等键已用于另一笔内容不同的下单请求，请重新发起'), { businessRejected: true })
      if (result === 'filteredConflict') throw Object.assign(Error('请求失败'), { businessRejected: true,
        originalMessage: '幂等请求 clientToken 已用于不同内容' })
      if (result === 'duplicate') throw Object.assign(Error('该请求已提交过（同一幂等键），请到订单列表查看结果'), { businessRejected: true })
      if (result === 'needConfirm') return { data: { needConfirm: true, shortages: [] } }
      if (!orders.has(body.idempotencyKey)) orders.set(body.idempotencyKey, 800 + orders.size)
      if (result === 'deferred') await gate
      if (result === 'timeout') throw Error('synthetic lost response after commit')
      if (result === 'unknown') return { data: {} }
      return { data: { orderId: orders.get(body.idempotencyKey), warnings: [] } }
    }, createPayment: async body => { payments.push(clone(body)); if (controls.paymentFailure) throw Error('synthetic payment failure'); return { data: { status: 2 } } },
    getOrderDetail: async id => ({ data: { id, paymentStatus: controls.paymentFailure ? 0 : 2 } }) }
  }
  function openPage(ownerApp = app) {
    const page = loadPage('miniapp-user/pages/order/create.js', { wx, app: ownerApp, stubs })
    page.setData({ stationId: 1, address: { id: 11 }, note: 'original note', loading: false, selectedMethod: method,
      products: [{ id: 5, name: 'Synthetic', quantity: 1, price: 20, category: 2 }] })
    return page
  }
  return { page: openPage(), openPage, app, wx, fault, calls, orders, payments, release, controls }
}

for (const mode of ['read', 'write', 'noop', 'afterwrite', 'readback']) test('first-send ' + mode + ': zero requests until persistence verifies; recovery keeps one intent', async () => {
  const t = fixture(['unknown', 'success'], mode); await t.page.refreshQuote()
  await t.page._createOrder(false); await t.page._createOrder(false)
  assert.equal(t.calls.length, 0, 'first request requires a verified durable full snapshot')
  t.fault.mode = 'normal'; await t.page._createOrder(false); await t.page._createOrder(false)
  assert.equal(t.calls.length, 2); assert.deepStrictEqual(t.calls[1], t.calls[0]); assert.equal(t.orders.size, 1)
})

for (const outcome of ['timeout', 'unknown']) for (const mode of ['normal', 'read', 'write', 'noop', 'afterwrite']) {
  test(outcome + ' / later ' + mode + ': double retry uses original full body after quote clears and note/fees edit', async () => {
    const t = fixture([outcome, 'success']); await t.page.refreshQuote(); await t.page._createOrder(false)
    const original = clone(t.calls[0]); t.fault.mode = mode
    t.page.setData({ note: 'edited note', barrelPurchases: [{ productId: 5, quantity: 1, unitPrice: 50 }], extraDepositAmount: 50 })
    t.page.invalidateQuote(); t.page.onRetryUnknown(); t.page.onRetryUnknown(); await flush()
    assert.equal(t.calls.length, 2); assert.deepStrictEqual(t.calls[1], original); assert.equal(t.orders.size, 1)
    assert.equal(t.page.data.lastSubmittedOrder.fingerprint, require('../../miniapp-user/utils/orderIntent').fingerprint({
      customerId: 7, stationId: 1, addressId: 11, paymentMethod: 2, items: [{ productId: 5, quantity: 1 }] }))
    console.log('TRACE safe-retry ' + JSON.stringify({ outcome, mode, requests: t.calls.length, distinctOrders: t.orders.size }))
  })
}

for (const [name, edit] of [
  ['quantity', page => page.setData({ products: [{ ...page.data.products[0], quantity: 2 }] })],
  ['product', page => page.setData({ products: [{ ...page.data.products[0], id: 6 }] })],
  ['station', page => page.setData({ stationId: 2 })],
  ['address', page => page.setData({ address: { id: 12 } })],
  ['payment method', page => page.setData({ selectedMethod: 3 })]
]) test('unknown ' + name + ' edit blocks a new order; original content can still recover', async () => {
  const t = fixture(); await t.page.refreshQuote(); await t.page._createOrder(false)
  edit(t.page); await t.page.refreshQuote(); await t.page._createOrder(false)
  assert.equal(t.calls.length, 1); assert.equal(t.orders.size, 1)
  t.page.setData({ stationId: 1, address: { id: 11 }, selectedMethod: 2,
    products: [{ id: 5, name: 'Synthetic', quantity: 1, price: 20, category: 2 }] })
  await t.page._createOrder(false); assert.deepStrictEqual(t.calls[1], t.calls[0]); assert.equal(t.orders.size, 1)
})

for (const mode of ['normal', 'read', 'write', 'noop']) test('unload/reopen / ' + mode + ': full body survives same-process Page recreation', async () => {
  const t = fixture(); await t.page.refreshQuote(); await t.page._createOrder(false)
  t.page.onUnload(); t.fault.mode = mode
  const next = t.openPage(); next.setData({ note: 'new page note', extraDepositAmount: 999 })
  await next.refreshQuote()
  await next._createOrder(false)
  assert.equal(t.calls.length, 2); assert.deepStrictEqual(t.calls[1], t.calls[0]); assert.equal(t.orders.size, 1)
})

test('cold module/app restoration reads persisted full snapshot, not current note', async () => {
  const t = fixture(); await t.page.refreshQuote(); await t.page._createOrder(false); t.page.onUnload()
  delete require.cache[require.resolve('../../miniapp-user/utils/orderIntent')]
  const nextApp = createApp({ globalData: { isLogin: true, customerId: 7, tempStationId: 1 } })
  const next = t.openPage(nextApp); next.setData({ note: 'cold new note' }); await next.refreshQuote(); await next._createOrder(false)
  assert.deepStrictEqual(t.calls[1], t.calls[0]); assert.equal(t.orders.size, 1)
})

test('cold unknown storage read failure sends nothing; it does not become an empty record', async () => {
  const t = fixture(); await t.page.refreshQuote(); await t.page._createOrder(false); t.page.onUnload()
  delete require.cache[require.resolve('../../miniapp-user/utils/orderIntent')]
  t.fault.mode = 'read'
  const next = t.openPage(createApp({ globalData: { isLogin: true, customerId: 7, tempStationId: 1 } }))
  await next.refreshQuote(); await next._createOrder(false); assert.equal(t.calls.length, 1)
})

test('known order in memory wins over stale/broken disk and recent receipt write failure', async () => {
  const t = fixture(['success']); await t.page.refreshQuote()
  const write = t.wx.setStorageSync
  t.wx.setStorageSync = (key, value) => { if (key === 'recentOrder.v1') throw Error('synthetic recent receipt quota'); return write(key, value) }
  await t.page._createOrder(false); t.page.onUnload()
  t.wx.__storage.set('orderRequest.v2:7', { broken: true })
  const next = t.openPage(); await next.refreshQuote(); await next._createOrder(false)
  assert.equal(t.calls.length, 1); assert.equal(next.data.lastSubmittedOrder.orderId, 800)
})

test('in-flight shared lock survives Page unload/reopen and double clicks', async () => {
  const t = fixture(['deferred']); await t.page.refreshQuote()
  const first = t.page._createOrder(false); t.page.onUnload()
  const next = t.openPage(); await next.refreshQuote(); await next._createOrder(false); next.onRetryUnknown()
  assert.equal(t.calls.length, 1); t.release(); await first
  await next._createOrder(false); assert.equal(t.calls.length, 1); assert.equal(t.payments.length, 0)
})

for (const change of ['unload', 'logout/relogin', 'A-B-A']) test('late known response after ' + change + ' records original receipt but does not pay or repaint', async () => {
  const t = fixture(['deferred'], 'normal', 3); await t.page.refreshQuote()
  const first = t.page._createOrder(false)
  if (change === 'unload') t.page.onUnload()
  else {
    const token = require('../../miniapp-user/utils/token')
    if (change === 'A-B-A') { t.app.globalData.customerId = 8; token.beginSession(); token.captureSession(); t.app.globalData.customerId = 7 }
    token.beginSession()
  }
  let writes = 0; t.page.setData = () => writes++
  t.release(); await first
  assert.equal(t.payments.length, 0); assert.equal(writes, 0)
})

test('late failure modal from old login cannot retry under the new login', async () => {
  const t = fixture(['timeout']); await t.page.refreshQuote(); await t.page._createOrder(false)
  const modal = t.wx.__calls.modal.at(-1); require('../../miniapp-user/utils/token').beginSession()
  modal.success({ confirm: true }); await flush(); assert.equal(t.calls.length, 1)
})

for (const change of ['unload', 'logout/relogin']) test('payment retry confirmation after ' + change + ' cannot send another payment', async () => {
  const t = fixture(['success'], 'normal', 3)
  t.controls.paymentFailure = true
  const modals = []; t.wx.showModal = modal => modals.push(modal)
  await t.page.refreshQuote(); const first = t.page._createOrder(false); await flush()
  assert.equal(t.payments.length, 1); assert.equal(modals.length, 1)
  if (change === 'unload') t.page.onUnload()
  else require('../../miniapp-user/utils/token').beginSession()
  let writes = 0; t.page.setData = () => writes++
  modals[0].success({ confirm: true }); modals[0].complete(); await first
  assert.equal(t.payments.length, 1); assert.equal(writes, 0)
})

test('same-session token renewal preserves a pending write and its authorized payment', async () => {
  const t = fixture(['deferred'], 'normal', 3); await t.page.refreshQuote()
  const first = t.page._createOrder(false)
  const token = require('../../miniapp-user/utils/token'), session = token.captureSession()
  assert.equal(token.acceptRefreshedTokens(session, 'synthetic-renewed-access', 'synthetic-renewed-refresh'), true)
  t.release(); await first
  assert.equal(t.calls.length, 1); assert.equal(t.payments.length, 1)
})

test('unknown does not expire after the recent UI window', async () => {
  const t = fixture(); await t.page.refreshQuote(); await t.page._createOrder(false)
  t.page._originalOrderRequest.at = Date.now() - 24 * 60 * 60 * 1000
  t.page.setData({ address: { id: 12 } }); await t.page.refreshQuote(); await t.page._createOrder(false)
  assert.equal(t.calls.length, 1); assert.equal(t.page._originalOrderRequest.phase, 'unknown')
})

test('business rejection after unknown retains original key/body/stage and blocks edited basket', async () => {
  const t = fixture(['unknown', 'reject', 'success']); await t.page.refreshQuote(); await t.page._createOrder(false)
  await t.page._createOrder(false)
  assert.equal(t.page._originalOrderRequest.phase, 'unknown')
  t.page.setData({ address: { id: 12 } }); await t.page.refreshQuote(); await t.page._createOrder(false)
  assert.equal(t.calls.length, 2)
  t.page.setData({ address: { id: 11 } }); await t.page._createOrder(false)
  assert.deepStrictEqual(t.calls[2], t.calls[0]); assert.equal(t.orders.size, 1)
})

for (const [outcome, edit] of [
  ['productRejected', page => page.setData({ products: [{ ...page.data.products[0], id: 6 }] })],
  ['addressRejected', page => page.setData({ address: { id: 12 } })],
  ['reject', page => page.setData({ products: [{ ...page.data.products[0], quantity: 2 }] })]
]) test('first definite ' + outcome + ' permits corrected fresh submission after new quote', async () => {
  const t = fixture([outcome, 'success']); await t.page.refreshQuote(); await t.page._createOrder(false)
  assert.equal(t.calls.length, 1); assert.equal(t.orders.size, 0)
  assert.equal(t.page._originalOrderRequest.phase, 'prepared')
  assert.equal(t.page.data.submitState, 'idle'); assert.equal(t.page.data.unknownResultText, '')
  edit(t.page); await t.page.refreshQuote(); await t.page._createOrder(false)
  assert.equal(t.calls.length, 2); assert.equal(t.orders.size, 1)
  assert.notEqual(t.calls[1].idempotencyKey, t.calls[0].idempotencyKey)
})

test('first definite rejection remains editable after unload/reopen', async () => {
  const t = fixture(['productRejected', 'success']); await t.page.refreshQuote(); await t.page._createOrder(false)
  t.page.onUnload(); const next = t.openPage()
  next.setData({ products: [{ ...next.data.products[0], id: 6 }] }); await next.refreshQuote(); await next._createOrder(false)
  assert.equal(t.calls.length, 2); assert.equal(t.orders.size, 1)
})

for (const outcome of ['productRejected', 'addressRejected', 'conflict', 'duplicate']) {
  test('historical unknown replay / ' + outcome + ' never releases its original snapshot', async () => {
    const t = fixture(['unknown', outcome, 'success']); await t.page.refreshQuote(); await t.page._createOrder(false)
    await t.page._createOrder(false); assert.equal(t.page._originalOrderRequest.phase, 'unknown')
    t.page.setData({ products: [{ ...t.page.data.products[0], id: 6 }] }); await t.page.refreshQuote(); await t.page._createOrder(false)
    assert.equal(t.calls.length, 2); assert.equal(t.orders.size, 1)
    t.page.setData({ products: [{ ...t.page.data.products[0], id: 5 }] }); await t.page._createOrder(false)
    assert.deepStrictEqual(t.calls[2], t.calls[0]); assert.equal(t.orders.size, 1)
  })
}

for (const outcome of ['conflict', 'duplicate', 'filteredConflict']) test('first ambiguous ' + outcome + ' stays unknown despite businessRejected flag', async () => {
  const t = fixture([outcome, 'success']); await t.page.refreshQuote(); await t.page._createOrder(false)
  assert.equal(t.page._originalOrderRequest.phase, 'unknown')
  t.page.setData({ products: [{ ...t.page.data.products[0], id: 6 }] }); await t.page.refreshQuote(); await t.page._createOrder(false)
  assert.equal(t.calls.length, 1)
})

test('needConfirm proves no order, but only explicit shortage agreement sends the confirmed snapshot', async () => {
  const t = fixture(['needConfirm', 'success']); await t.page.refreshQuote(); await t.page._createOrder(false)
  assert.equal(t.orders.size, 0); assert.equal(t.page._originalOrderRequest.phase, 'prepared')
  await t.page.onUnifiedConfirmOk()
  assert.equal(t.calls.length, 2); assert.equal(t.calls[1].idempotencyKey, t.calls[0].idempotencyKey)
  assert.equal(t.calls[1].confirmShortage, true); assert.equal(t.orders.size, 1)
})

test('known old receipt does not claim a cart edited during the request; real new intent gets a new key', async () => {
  const t = fixture(['deferred', 'success']); await t.page.refreshQuote()
  const first = t.page._createOrder(false); t.page.setData({ products: [{ ...t.page.data.products[0], quantity: 2 }] })
  await t.page.refreshQuote(); t.release(); await first
  assert.equal(t.page._pendingOrderForCurrentCart(), null)
  await t.page._createOrder(false); assert.notEqual(t.calls[1].idempotencyKey, t.calls[0].idempotencyKey); assert.equal(t.orders.size, 2)
})

test('known same-cart receipt navigates for ten minutes; a later true new request gets a new key', async () => {
  const t = fixture(['success', 'success']); await t.page.refreshQuote(); await t.page._createOrder(false)
  await t.page._createOrder(false); assert.equal(t.calls.length, 1)
  t.page._originalOrderRequest.at = Date.now() - 11 * 60 * 1000
  t.page.setData({ lastSubmittedOrder: { ...t.page.data.lastSubmittedOrder, at: t.page._originalOrderRequest.at } })
  await t.page._createOrder(false); assert.notEqual(t.calls[1].idempotencyKey, t.calls[0].idempotencyKey); assert.equal(t.orders.size, 2)
})

;(async () => {
  let failed = 0
  for (const item of tests) {
    try { await item.run(); console.log('PASS ' + item.name) }
    catch (error) { failed++; console.error('FAIL ' + item.name + ': ' + error.message) }
  }
  console.log('AUDIT_STORAGE_RESULT ' + JSON.stringify({ passed: tests.length - failed, failed, total: tests.length }))
  if (failed) process.exitCode = 1
  else console.log('AQUAFLOW_SUITE_OK ' + tests.length)
})().catch(error => { console.error(error); process.exitCode = 1 }).finally(done)
