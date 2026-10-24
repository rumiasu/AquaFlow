const assert = require('assert'), fs = require('fs'), path = require('path')
const { loadPage, createWx, createApp, armWatchdog, ROOT } = require('./harness')
const { parseWxml, renderElements } = require('./wxml-tree')
const done = armWatchdog(15000), tests = []
const repo = process.env.AQUAFLOW_REPO || ROOT
const test = (name, run) => tests.push({ name, run })
const flush = async () => { for (let i = 0; i < 25; i++) await Promise.resolve() }
function fixture(method = 2, purchases = true) {
  const wx = createWx(), app = createApp(), orders = []
  wx.__modalAutoConfirm = false
  global.__wxConfig = { envVersion: 'develop' }
  const line = { productId: 5, productName: '清泉水', quantity: 1, unitPrice: 50, amount: 50 }
  let response = { data: { independentRights: true, waterAmount: 20, extraDeposit: purchases ? 50 : 0,
    extraDepositBuckets: purchases ? 1 : 0, totalAmount: purchases ? 70 : 20, blocked: false,
    barrelPurchases: purchases ? [line] : [], methods: [{ id: 1, name: '模拟微信支付', enabled: true }, { id: 2, name: '现金', enabled: true }],
    defaultMethod: method, wechatPay: { method: 1, enabled: true, simulated: true, label: '模拟微信支付' } } }
  const page = loadPage(path.join(repo, 'miniapp-user/pages/order/create.js'), { wx, app, stubs: {
    'api/payment': { getQuote: async () => response },
    'api/order': { getOrderDetail: async () => ({ data: { paymentStatus: 2 } }),
      createOrder: async body => { orders.push(body); return { data: { orderId: 81 } } },
      createPayment: async () => ({ data: { status: 2 } }) }
  } })
  page.setData({ stationId: 1, selectedMethod: method, address: { id: 12 },
    products: [{ id: 5, name: '清泉水', price: 20, deposit: 50, category: 1, quantity: 1 }], loading: false })
  return { page, wx, app, orders, response, setQuote: value => { response = value } }
}
test('cash plus new deposit uses one explicit combined consent, then creates the original purchase', async () => {
  const t = fixture(); await t.page.refreshQuote(); await t.page.onSubmit()
  assert.equal(t.page.data.showUnifiedConfirm, true); assert.equal(t.orders.length, 0)
  await t.page.onUnifiedConfirmOk(); await flush()
  assert.equal(t.wx.__calls.modal.length, 0, 'combined consent must not open a duplicate deposit authorization')
  assert.equal(t.orders.length, 1)
  assert.deepEqual(t.orders[0].barrelPurchases, [{ productId: 5, quantity: 1, unitPrice: 50 }])
})
test('combined view lists product, quantity, unit price, amount and explicit purchase consequence', async () => {
  const t = fixture(); await t.page.refreshQuote(); await t.page.onSubmit()
  const full = fs.readFileSync(path.join(repo, 'miniapp-user/pages/order/create.wxml'), 'utf8')
  const source = full.slice(full.lastIndexOf('<!-- ══ 综合确认'), full.lastIndexOf('</view>'))
  const text = renderElements(parseWxml(source), t.page.data, { includeText: true }).map(e => e.text).join('\n')
  for (const value of ['本次新增桶押金', '清泉水', '新增用桶权益 1 份', '单价 ¥50', '本次桶押金 ¥50', '即同意购买以上用桶权益', '收齐款项后生效']) assert.ok(text.includes(value), value)
})
test('cancelling combined consent never authorizes or creates a purchase', async () => {
  const t = fixture(); await t.page.refreshQuote(); await t.page.onSubmit(); t.page.onUnifiedConfirmCancel()
  assert.equal(t.page.data.barrelPurchaseConfirmed, false); assert.equal(t.orders.length, 0)
})
test('changed deposit quote invalidates the prior combined consent', async () => {
  const t = fixture(); await t.page.refreshQuote(); await t.page.onSubmit()
  const next = JSON.parse(JSON.stringify(t.response)); next.data.barrelPurchases[0].amount = 60; next.data.barrelPurchases[0].unitPrice = 60
  next.data.extraDeposit = 60; next.data.totalAmount = 80
  t.setQuote(next); await t.page.refreshQuote(); await t.page.onUnifiedConfirmOk()
  assert.equal(t.orders.length, 0); assert.equal(t.page.data.barrelPurchaseConfirmed, false)
})
test('standalone purchase confirmation remains explicit when no combined prompt is needed', async () => {
  const t = fixture(1); await t.page.refreshQuote(); await t.page.onSubmit()
  const modal = t.wx.__calls.modal.at(-1)
  assert.ok(modal.title.includes('本次新增桶押金'))
  for (const text of ['新增用桶权益 1 份', '单价 ¥50.00', '本次桶押金 ¥50.00']) assert.ok(modal.content.includes(text))
  assert.equal(t.orders.length, 0); modal.success({ confirm: true }); await flush(); assert.equal(t.orders.length, 1)
})
test('changed intent cannot use a stale standalone purchase callback', async () => {
  const t = fixture(1); await t.page.refreshQuote(); await t.page.onSubmit()
  const modal = t.wx.__calls.modal.at(-1); t.page.setData({ note: '新备注' })
  modal.success({ confirm: true }); await flush()
  assert.equal(t.page.data.barrelPurchaseConfirmed, false); assert.equal(t.orders.length, 0)
})
test('exact duplicate soft warnings are combined while distinct business warnings remain', async () => {
  const t = fixture(); await t.page.showOrderWarnings({ warnings: ['库存不足，请等水站安排', '库存不足，请等水站安排', '押金按本站规则退还'] })
  assert.equal(t.wx.__calls.modal.at(-1).content, '库存不足，请等水站安排\n押金按本站规则退还')
})
test('cash without new rights has no fabricated purchase authorization', async () => {
  const t = fixture(2, false); await t.page.refreshQuote(); await t.page.onSubmit(); await t.page.onUnifiedConfirmOk(); await flush()
  assert.equal(t.orders.length, 1); assert.deepEqual(t.orders[0].barrelPurchases, [])
  assert.equal(t.wx.__calls.modal.length, 0)
})
;(async () => {
  let failed = 0
  for (const t of tests) { try { await t.run(); console.log('PASS ' + t.name) } catch (err) { failed++; console.error('FAIL ' + t.name + ': ' + err.stack) } }
  done(); console.log('CHECKOUT_CLOSEOUT_RESULT ' + (tests.length - failed) + '/' + tests.length)
  if (failed) process.exitCode = 1; else console.log('AQUAFLOW_SUITE_OK ' + tests.length)
})().catch(err => { done(); console.error(err); process.exitCode = 1 })
