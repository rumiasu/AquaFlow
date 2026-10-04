'use strict'
// Execute Page methods with isolated API/WeChat callbacks; no real payment or UI acceptance.
const assert = require('assert')
const fs = require('fs'), path = require('path')
const { loadPage, createWx, createApp, armWatchdog, ROOT } = require('./harness')
const done = armWatchdog(), tests = []
const test = (name, run) => tests.push({ name, run })
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
const validQuote = () => ({ data: { waterAmount: 20, totalAmount: 20, blocked: false,
  methods: [{ id: 3, name: '水票支付', enabled: true }], defaultMethod: 3,
  ticketPay: { fullyCovered: true, coverQty: 1, coverAmount: 20, payableAmount: 0 } } })
function order(handler = async () => validQuote(), scenario = {}) {
  const wx = createWx(), app = createApp(), calls = { quote: [], orders: [], payments: [] }
  const page = loadPage('miniapp-user/pages/order/create.js', { wx, app, stubs: {
    'api/payment': { getQuote: body => { calls.quote.push(body); return handler(body, calls.quote.length) } },
    'api/order': { createOrder: async body => { calls.orders.push(body);
      return { data: scenario.unknown && calls.orders.length === 1 ? {} : { orderId: 81 } } },
      createPayment: async body => { calls.payments.push(body); return { data: { status: 2 } } },
      getOrderDetail: async () => ({ data: { id: 81, paymentStatus: 2 } }) }
  } })
  page.setData({ products: [{ id: 5, quantity: 1, price: 20, category: 1 }], stationId: 1,
    address: { id: 12 }, selectedMethod: 3, loading: false })
  return { page, wx, app, calls }
}
test('空支付列表明确失败、不造水票、不发建单和付款', async () => {
  const t = order(async () => ({ data: { ...validQuote().data, methods: [] } }))
  await t.page.refreshQuote(); assert.equal(t.page.data.payMethods.length, 0)
  assert(t.page.data.quoteError); assert.equal(t.page.data.blocked, true)
  t.wx.__modalAutoConfirm = false; await t.page.onSubmit()
  assert.equal(t.calls.orders.length, 0); assert.equal(t.calls.payments.length, 0)
})
test('未知支付ID99进入失败重试态，零建单；合法报价恢复后可提交', async () => {
  const unknown = { id: 99, name: '未知渠道夹具', enabled: true }
  for (const methods of [[unknown], [...validQuote().data.methods, unknown],
    [{ ...unknown, enabled: false }, ...validQuote().data.methods]]) {
    let failing = true
    const t = order(async () => failing ? ({ data: { ...validQuote().data, methods, defaultMethod: 99 } }) : validQuote())
    await t.page.refreshQuote()
    assert.equal(t.page.data.quoteReady, false); assert.equal(t.page.data.blocked, true)
    assert(t.page.data.quoteError); assert.deepStrictEqual(t.page.data.payMethods, [])
    t.wx.__modalAutoConfirm = false; await t.page.onSubmit()
    assert.equal(t.calls.orders.length, 0); assert.equal(t.calls.payments.length, 0)
    failing = false; await t.page.onRetryQuote()
    assert.equal(t.page.data.quoteReady, true); assert.equal(t.page.data.quoteError, '')
    assert.equal(t.page.data.selectedMethod, 3); assert.equal(t.calls.orders.length, 0)
    await t.page.onSubmit(); assert.equal(t.calls.orders.length, 1); assert.equal(t.calls.payments.length, 1)
  }
})
test('缺失/畸形列表、失败响应及无可用方式不能被解释成报价成功', async () => {
  for (const data of [null, {}, { ...validQuote().data, methods: null },
    { ...validQuote().data, methods: [null] }, { ...validQuote().data, methods: [{ id: 3, name: '水票', enabled: false }] }]) {
    const t = order(async () => ({ data })); await t.page.refreshQuote()
    assert(t.page.data.quoteError); assert.equal(t.page.data.quoteReady, false)
  }
  const t = order(async () => ({ code: 1, data: validQuote().data })); await t.page.refreshQuote()
  assert(t.page.data.quoteError)
})
test('请求失败清掉旧支付能力且保持阻断，可重试恢复后提交一笔', async () => {
  let failing = false; const t = order(async () => { if (failing) throw Error('网络断开'); return validQuote() })
  await t.page.refreshQuote(); failing = true; await t.page.refreshQuote()
  assert(t.page.data.quoteError); assert.equal(t.page.data.payMethods.length, 0)
  assert.equal(t.page.data.quoteReady, false)
  t.wx.__modalAutoConfirm = false; await t.page.onSubmit(); assert.equal(t.calls.orders.length, 0)
  failing = false; await t.page.onRetryQuote(); assert.equal(t.page.data.quoteError, '')
  assert.equal(t.page.data.quoteReady, true); await t.page.onSubmit()
  assert.equal(t.calls.orders.length, 1); assert.equal(t.calls.payments.length, 1)
})
test('报价从未确认时点击只引导核实，不建单', async () => {
  const t = order(); t.wx.__modalAutoConfirm = false; await t.page.onSubmit()
  assert.equal(t.calls.orders.length, 0)
  assert.equal(t.wx.__calls.modal.at(-1).confirmText, '重试报价')
})
test('刷新中点击不能建单，报价完成后需再次明确提交', async () => {
  const gate = deferred(), t = order(() => gate.promise)
  const refresh = t.page.refreshQuote(), submit = t.page.onSubmit()
  const loading = t.page.data.quoteLoading, before = t.calls.orders.length
  gate.resolve(validQuote()); await Promise.all([refresh, submit])
  assert.equal(loading, true); assert.equal(before, 0)
  assert.equal(t.page.data.quoteLoading, false); assert.equal(t.calls.orders.length, 0)
})
test('水票提交等待报价失败后立即停下，不靠抛错或下一次点击阻断', async () => {
  const t = order(async (_, n) => { if (n > 1) throw Error('提交前报价超时'); return validQuote() })
  await t.page.refreshQuote(); await t.page.onSubmit()
  assert.equal(t.calls.orders.length, 0); assert.equal(t.calls.payments.length, 0)
  assert(t.page.data.quoteError); assert.equal(t.page.data.submitting, false)
})
test('提交前容量/配送被服务端阻断时，不创建消费单', async () => {
  const t = order(async (_, n) => n === 1 ? validQuote() : ({ data: { ...validQuote().data, blocked: true, blockReason: '容量已占用' } }))
  await t.page.refreshQuote(); await t.page.onSubmit(); assert.equal(t.calls.orders.length, 0)
})
test('已打开的资产/缺货确认不能绕过后来失败的报价，未知原请求仍可恢复', async () => {
  for (const handler of ['onAssetConfirmOk', 'onShortageAgree']) {
    const t = order(async (_, n) => n === 1 ? validQuote() : ({ data: { ...validQuote().data, methods: [] } }))
    await t.page.refreshQuote(); t.page.setData({ assetReadAgreed: true, showAssetConfirm: true, showShortageConfirm: true })
    await t.page.refreshQuote(); t.page[handler](); assert.equal(t.calls.orders.length, 0)
  }
  // 先真实执行有效报价后的建单请求，模拟丢失订单号，再验证失败报价下恢复同一原键。
  const t = order(async (_, n) => n === 1 ? validQuote() : ({ data: { ...validQuote().data, methods: [] } }), { unknown: true })
  await t.page.refreshQuote(); await t.page._createOrder(false)
  assert.equal(t.page.data.submitState, 'unknown'); assert.equal(t.calls.orders.length, 1)
  await t.page.refreshQuote(); t.page.onRetryUnknown()
  for (let i = 0; i < 12; i++) await Promise.resolve()
  assert.equal(t.calls.orders.length, 2)
  assert.equal(t.calls.orders[0].idempotencyKey, t.calls.orders[1].idempotencyKey)
})
test('迟到成功不解除较新失败，较新成功也不被迟到失败覆盖', async () => {
  for (const oldFails of [false, true]) {
    const gate = deferred(), t = order((_, n) => n === 1 ? gate.promise
      : oldFails ? validQuote() : Promise.reject(Error('较新失败')))
    const old = t.page.refreshQuote(); await t.page.refreshQuote()
    if (oldFails) gate.reject(Error('迟到失败')); else gate.resolve(validQuote())
    await old; assert.equal(!!t.page.data.quoteError, !oldFails)
    assert.equal(t.page.data.quoteReady, oldFails)
  }
})
test('旧会话迟到报价不能解锁提交', async () => {
  const gate = deferred(), t = order(() => gate.promise), refresh = t.page.refreshQuote()
  t.app.globalData.isLogin = false; gate.resolve(validQuote()); await refresh
  assert.equal(t.page.data.quoteReady, false)
})
const simulated = { method: 1, enabled: true, simulated: true, label: '模拟微信支付（点击即成功，仅联调期开启）' }
function barrel(capability, onlineAvailable = true) {
  const state = { capability, onlineAvailable }
  const wx = createWx(), calls = [], page = loadPage('miniapp-user/pages/barrel/purchase.js', { wx, stubs: {
    'api/product': { getStationProducts: async () => ({ data: [{ id: 5, category: 1 }] }) },
    'api/barrel': { quoteBarrelRight: async () => ({ data: { amount: 50, stationName: '本站', onlineAvailable: state.onlineAvailable, wechatPay: state.capability } }),
      getBarrelRightPurchases: async () => ({ data: [] }), purchaseBarrelRight: async body => { calls.push(body); throw Error('测试停止于请求') } },
    'utils/station': { resolveStationId: async () => 1 }
  } })
  return { page, wx, calls, state }
}
test('独立押金在线标签来自服务端能力，明确展示模拟', async () => {
  const t = barrel(simulated); await t.page.onLoad({ stationId: 1 })
  assert.equal(t.page.data.onlinePayEnabled, true); assert.equal(t.page.data.onlinePayLabel, simulated.label)
  t.page.onMethod({ currentTarget: { dataset: { method: 1 } } }); await t.page.onPurchase()
  assert.equal(t.calls.length, 1); assert.equal(t.calls[0].paymentMethod, 1)
})
test('前端原样展示服务端非模拟标签，未调用任何原生真实支付', async () => {
  const channel = { ...simulated, simulated: false, label: '渠道展示夹具' }, t = barrel(channel)
  await t.page.onLoad({ stationId: 1 }); assert.equal(t.page.data.onlinePayLabel, channel.label)
  assert.equal(t.calls.length, 0)
})
test('旧服务只给可用开关、标签缺失或能力不完整时禁在线，不影响现金报价', async () => {
  for (const cap of [undefined, {}, { ...simulated, label: '' }, { ...simulated, label: '  ' },
    { ...simulated, enabled: false }, { ...simulated, simulated: undefined }, { ...simulated, method: 3 }]) {
    const t = barrel(cap); await t.page.onLoad({ stationId: 1 })
    assert.equal(t.page.data.onlinePayEnabled, false); assert(t.page.data.quote)
    t.page.onMethod({ currentTarget: { dataset: { method: 1 } } }); assert.equal(t.page.data.paymentMethod, 2)
    t.page.setData({ paymentMethod: 1 }); await t.page.onPurchase(); assert.equal(t.calls.length, 0)
  }
})
test('在线能力关闭时现金仍可登记，在线请求不会凭旧标签放行', async () => {
  const t = barrel(simulated, false); await t.page.onLoad({ stationId: 1 })
  assert.equal(t.page.data.onlinePayEnabled, false); await t.page.onPurchase()
  assert.equal(t.calls.length, 1); assert.equal(t.calls[0].paymentMethod, 2)
})
test('已发出的未知在线请求在能力标签缺失后仍恢复原body和key', async () => {
  const t = barrel(simulated); await t.page.onLoad({ stationId: 1 })
  t.page.onMethod({ currentTarget: { dataset: { method: 1 } } }); await t.page.onPurchase()
  assert.equal(t.calls.length, 1); t.state.capability = undefined
  await t.page.refreshQuote(); assert.equal(t.page.data.onlinePayEnabled, false)
  await t.page.onRetryOriginal(); assert.equal(t.calls.length, 2)
  assert.deepStrictEqual(t.calls[1], t.calls[0])
})
test('服务端默认指向不可用项时，仅选择实际下发的可用方式', async () => {
  const t = order(async () => ({ data: { ...validQuote().data, defaultMethod: 1,
    methods: [{ id: 1, name: '未开通渠道', enabled: false }, { id: 3, name: '水票支付', enabled: true }] } }))
  t.page.setData({ selectedMethod: 1 }); await t.page.refreshQuote()
  assert.equal(t.page.data.selectedMethod, 3); assert.equal(t.page.data.payMethods.length, 2)
})
test('提交前报价改变支付方式，需客户重新确认，不自动改方式并付款', async () => {
  const t = order(async (_, n) => n === 1 ? validQuote() : ({ data: { ...validQuote().data,
    methods: [{ id: 1, name: '联调渠道夹具', enabled: true }], defaultMethod: 1, wechatPay: simulated } }))
  await t.page.refreshQuote(); await t.page.onSubmit()
  assert.equal(t.page.data.selectedMethod, 1); assert.equal(t.calls.orders.length, 0)
  await t.page.onSubmit(); assert.equal(t.calls.orders.length, 1); assert.equal(t.calls.payments.length, 1)
})
test('微信页面实际绑定能力标签与重试动作，不再写死支付名', async () => {
  const purchase = fs.readFileSync(path.join(ROOT, 'miniapp-user/pages/barrel/purchase.wxml'), 'utf8').replace(/<!--[\s\S]*?-->/g, '')
  assert(purchase.includes('{{onlinePayLabel}}')); assert(!purchase.includes('}}微信支付</button>'))
  const create = fs.readFileSync(path.join(ROOT, 'miniapp-user/pages/order/create.wxml'), 'utf8')
  assert(create.includes('bindtap="onRetryQuote"'))
})
;(async () => { let passed = 0, failed = 0
  for (const t of tests) { try { await t.run(); passed++; console.log('PASS ' + t.name) }
    catch (error) { failed++; console.error('FAIL ' + t.name + '\n' + error.stack) } }
  done(); if (failed) process.exitCode = 1; else console.log('AQUAFLOW_SUITE_OK ' + passed)
})().catch(error => { done(); console.error(error); process.exitCode = 1 })
