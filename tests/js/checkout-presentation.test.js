'use strict'
const assert = require('assert')
const { loadPage, createWx, armWatchdog } = require('./harness')
const done = armWatchdog(), tests = []
const test = (name, run) => tests.push({ name, run })
const reason = '水票不能支付新增押金，请改选付款方式、或先独立办理押金；已有容量被占用时也可等待释放'
const quote = () => ({ data: {
  waterAmount: 40, extraDeposit: 100, extraDepositBuckets: 2, totalAmount: 140,
  independentRights: true, barrelPurchases: [{ productId: 5, quantity: 2, unitPrice: 50, amount: 100 }],
  methods: [{ id: 1, name: '微信支付', desc: '模拟支付（点击即成功，仅联调开启）', enabled: true },
    { id: 3, name: '水票支付', enabled: true }], defaultMethod: 3,
  blocked: true, blockReason: reason, warnings: [reason],
  ticketPay: { fullyCovered: false, hint: reason, reason: 'DEPOSIT', payableAmount: 140 }
} })
function fixture(handler = async () => quote(), spec) {
  const wx = createWx(), calls = { orders: 0, payments: 0 }
  const page = loadPage('miniapp-user/pages/order/create.js', { wx, stubs: {
    'api/product': { getProductDetail: async () => ({ data: { id: 5, name: '清泉桶装水', spec, category: 1, price: 20, deposit: 50 } }) },
    'api/payment': { getQuote: handler },
    'api/order': { createOrder: async () => { calls.orders++; return { data: { orderId: 81 } } },
      createPayment: async () => { calls.payments++; return { data: { status: 2 } } } }
  } })
  page.setData({ items: [{ productId: 5, quantity: 2 }], products: [{ id: 5, quantity: 2, category: 1, price: 20 }],
    stationId: 1, address: { id: 12 }, selectedMethod: 3, loading: false })
  return { page, wx, calls }
}
test('规格为空或为占位字符串时不显示，保留原始商品字段', async () => {
  for (const spec of [null, undefined, '', ' ', 'null', ' NULL ', 'undefined']) {
    const t = fixture(undefined, spec)
    // Isolate product loading; these callbacks are covered by the quote flow suites.
    t.page.loadStationStatus = () => {}; t.page.syncBarrelSummary = () => {}; t.page.refreshQuote = () => {}
    await t.page.loadItemsProducts()
    assert.strictEqual(t.page.data.products[0].specText, '')
    assert.strictEqual(t.page.data.products[0].spec, spec)
    assert.strictEqual(t.page.data.products[0].quantity, 2)
  }
})
test('真实规格正常展示且不改变价格和数量', async () => {
  const t = fixture(undefined, ' 18.9L / 桶 ')
  t.page.loadStationStatus = () => {}; t.page.syncBarrelSummary = () => {}; t.page.refreshQuote = () => {}
  await t.page.loadItemsProducts()
  assert.strictEqual(t.page.data.products[0].specText, '18.9L / 桶')
  assert.strictEqual(t.page.data.products[0].price, 20)
  assert.strictEqual(t.page.data.products[0].subtotalText, '40.00')
})
test('同一水票限制只展示一次，阻断、换付款动作和两笔金额均保留', async () => {
  const t = fixture(); await t.page.refreshQuote()
  assert.deepStrictEqual(t.page.data.checkoutWarnings, [reason])
  assert.strictEqual(t.page.data.blocked, true); assert.strictEqual(t.page.data.blockReason, reason)
  assert.strictEqual(t.page.data.ticketShortfallHint, reason)
  assert.strictEqual(t.page.data.ticketAltMethod, 1); assert.strictEqual(t.page.data.ticketAltLabel, '改用微信支付')
  assert.strictEqual(t.page.data.ticketCanBuy, false)
  assert.strictEqual(t.page.data.totalWaterCostText, '40.00')
  assert.strictEqual(t.page.data.extraDepositAmountText, '100.00')
  assert.strictEqual(t.page.data.payableAmountText, '140.00')
  assert.deepStrictEqual(t.page.data.barrelPurchases, quote().data.barrelPurchases)
  assert.strictEqual(t.page.data.payMethods[0].desc, quote().data.methods[0].desc)
  await t.page.onSubmit(); assert.strictEqual(t.calls.orders, 0); assert.strictEqual(t.calls.payments, 0)
})
test('不同告知均展示，去买水票只在余额不足时提供', async () => {
  const t = fixture(async () => {
    const res = quote(); res.data.warnings = [reason, '本次楼层信息未填', '本次楼层信息未填']
    res.data.ticketPay.hint = '水票余额不足'; res.data.ticketPay.reason = 'INSUFFICIENT'
    return res
  })
  await t.page.refreshQuote()
  assert.deepStrictEqual(t.page.data.checkoutWarnings, [reason, '本次楼层信息未填', '水票余额不足'])
  assert.strictEqual(t.page.data.ticketCanBuy, true)
})
test('重新报价时不残留上一轮提示，失败仍阻断，恢复后清掉失效限制', async () => {
  let resolve, failing = false, pending = false
  const t = fixture(() => {
    if (pending) return new Promise(r => { resolve = r })
    if (failing) throw Error('网络断开')
    const res = quote(); res.data.blocked = false; res.data.warnings = []; res.data.ticketPay = null
    return Promise.resolve(res)
  })
  t.page.setData({ checkoutWarnings: [reason] }); pending = true
  const work = t.page.refreshQuote(); assert.deepStrictEqual(t.page.data.checkoutWarnings, [])
  resolve(quote()); await work; assert.deepStrictEqual(t.page.data.checkoutWarnings, [reason])
  pending = false; failing = true; await t.page.refreshQuote()
  assert.strictEqual(t.page.data.quoteReady, false); assert.strictEqual(t.page.data.blocked, true)
  assert(!t.page.data.checkoutWarnings.includes(reason))
  failing = false; t.page.setData({ selectedMethod: 1 }); await t.page.refreshQuote()
  assert.deepStrictEqual(t.page.data.checkoutWarnings, []); assert.strictEqual(t.page.data.blocked, false)
})
;(async () => {
  let failed = 0
  for (const t of tests) {
    try { await t.run(); console.log('PASS ' + t.name) }
    catch (e) { failed++; console.error('FAIL ' + t.name, e.stack) }
  }
  done()
  if (failed) process.exitCode = 1
  else console.log('AQUAFLOW_SUITE_OK ' + tests.length)
})()
