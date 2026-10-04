'use strict'
// Real Page methods with controlled API responses; no payment, database or visual acceptance.
const assert = require('assert')
const { loadPage, createWx, createApp, armWatchdog } = require('./harness')
const { STORAGE_KEYS } = require('../../miniapp-user/utils/storage-keys')
const done = armWatchdog()
const tests = []
const test = (name, run) => tests.push({ name, run })
const water = { id: 5, category: 1, name: '水', price: 20, deposit: 50 }
const independent = { data: { independentRights: true, missingRights: [{ productId: 5, quantity: 1 }], barrelPurchases: [{ productId: 5, quantity: 1, unitPrice: 50, amount: 50 }] } }
const flush = async () => { for (let i = 0; i < 12; i++) await Promise.resolve() }
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r }); return { resolve, promise } }
function home(handler = async () => independent) {
  const sent = [], wx = createWx(), app = createApp()
  const page = loadPage('miniapp-user/pages/home/index.js', { wx, app,
    stubs: { 'api/payment': { getQuote: body => { sent.push(body); return handler(body) } } } })
  page.setData({ products: [water], cart: { 5: 1 }, currentStationId: 1, address: { id: 12 } })
  return { page, wx, app, sent }
}
test('首页水款与本次新增押金分项展示，提示结算确认', async () => {
  const t = home(); await t.page.refreshDerived({})
  assert.equal(t.page.data.total.grandText, '70')
  assert.equal(t.page.data.total.depositText, '50')
  assert(t.page.data.total.detailText.includes('结算时确认'))
  assert(t.page.data.productsView[0].depositNote.includes('1'))
  assert.deepStrictEqual(t.sent[0].items, [{ productId: 5, quantity: 1 }])
  assert.equal(t.sent[0].stationId, 1); assert.equal(t.sent[0].addressId, 12)
})
test('服务端明确历史模式时保留缺桶押金预估', async () => {
  const t = home(async () => ({ data: { independentRights: false } }))
  await t.page.refreshDerived({}); assert.equal(t.page.data.total.grandText, '70')
  await t.page.refreshDerived({ 5: 1 }); assert.equal(t.page.data.total.grandText, '20')
})
test('未核实模式与报价失败不把押金混入，也不宣称无需押金', async () => {
  for (const response of [undefined, { data: {} }, { code: 1, data: { independentRights: false } }]) {
    const t = home(async () => response); await t.page.refreshDerived({})
    assert.equal(t.page.data.total.grandText, '20')
    assert(t.page.data.total.detailText.includes('未核实'))
    assert(!t.page.data.productsView[0].depositNote.includes('无需'))
  }
  const t = home(async () => { throw Error('网络异常') }); await t.page.refreshDerived({})
  assert(t.page.data.total.detailText.includes('未核实'))
})
test('容量占用以本次服务端结论为准，不用权益总数判断可用', async () => {
  const t = home(); await t.page.refreshDerived({ 5: 10 })
  assert(t.page.data.productsView[0].depositNote.includes('不足'))
  assert.equal(t.page.data.total.grandText, '70')
})
test('混合清单保持逐商品提示，非桶商品不凭押金字段收费', async () => {
  const t = home(); t.page.setData({ products: [water, { ...water, id: 6, category: 2 }], cart: { 5: 2, 6: 1 } })
  await t.page.refreshDerived({})
  assert.equal(t.page.data.total.grandText, '110')
  assert.equal(t.page.data.productsView[1].depositNote, '')
})
test('容量充足的复购不提示还需办理押金', async () => {
  const t = home(async () => ({ data: { independentRights: true, missingRights: [], barrelPurchases: [] } }))
  await t.page.refreshDerived({ 5: 1 })
  assert.equal(t.page.data.total.grandText, '20')
  assert(t.page.data.total.detailText.includes('无需加收桶押金'))
  assert(!t.page.data.total.detailText.includes('另行办理'))
})
test('纯非桶商品清单不出现无关的桶押金办理提示', async () => {
  const t = home(async () => ({ data: { independentRights: true, missingRights: [], barrelPurchases: [] } }))
  t.page.setData({ products: [{ ...water, category: 2 }] }); await t.page.refreshDerived({})
  assert.equal(t.page.data.total.grandText, '20')
  assert(!t.page.data.total.detailText.includes('桶押金'))
})
test('数量变化时迟到报价不能覆盖当前清单', async () => {
  const old = deferred(), t = home(body => body.items[0].quantity === 1 ? old.promise : Promise.resolve(independent))
  const first = t.page.refreshDerived({}); t.page.setData({ cart: { 5: 2 } })
  await t.page.refreshDerived({}); old.resolve({ data: { independentRights: false } }); await first
  assert.equal(t.page.data.total.grandText, '90')
})
test('切站与退出登录后迟到报价不能更新旧站价格', async () => {
  for (const change of ['station', 'session']) {
    const gate = deferred(), t = home(() => gate.promise), first = t.page.refreshDerived({})
    if (change === 'station') t.page.setData({ currentStationId: 2 })
    else t.app.globalData.isLogin = false
    gate.resolve({ data: { independentRights: false } }); await first
    assert.equal(t.page.data.total.grandText, '20')
  }
})
test('空清单不请求报价，清空后迟到响应无效', async () => {
  const gate = deferred(), t = home(() => gate.promise), first = t.page.refreshDerived({})
  t.page.setData({ cart: {} }); await t.page.refreshDerived({}); gate.resolve({ data: { independentRights: false } }); await first
  assert.equal(t.sent.length, 1); assert.equal(t.page.data.total.count, 0)
  assert.equal(t.page.data.total.grandText, '0')
})
function purchase(scenario = {}) {
  const wx = createWx(), app = createApp(), bodies = []
  wx.setStorageSync(STORAGE_KEYS.CUSTOMER_ID, 7)
  const page = loadPage('miniapp-user/pages/barrel/purchase.js', { wx, app, stubs: {
    'api/product': { getStationProducts: async () => ({ data: [water] }) },
    'api/barrel': { quoteBarrelRight: async () => ({ data: { amount: 50, stationName: '水站一' } }),
      getBarrelRightPurchases: async () => ({ data: [] }),
      purchaseBarrelRight: async body => {
        bodies.push(body); if (scenario.fail) throw Error('结果未知')
        const status = scenario.pending ? 'PENDING' : 'PAID'
        return { data: { status: scenario.pending ? 1 : 2, paymentId: 900, amount: 50 * body.quantity,
          purchase: { id: 81, customerId: 7, ...body, unitPrice: 50, amount: 50 * body.quantity,
            paymentId: 900, status, statusText: status === 'PAID' ? '押金已确认' : '待确认' } } }
      } }, 'utils/station': { resolveStationId: async () => 1 }
  } })
  return { page, wx, app, bodies, scenario }
}
test('首次新购已收款提示本次成功，不冒充查回旧款', async () => {
  const t = purchase(); await t.page.onLoad({ stationId: 1, productId: 5 }); await t.page.onPurchase()
  const modal = t.wx.__calls.modal.at(-1)
  assert.equal(modal.title, '桶押金已确认'); assert(!modal.content.includes('原押金'))
  assert(modal.content.includes('下一次送水')); assert.equal(t.wx.__calls.nav.length, 0)
})
test('新现金购买仍需实际收款确认，不显示已确认权益', async () => {
  const t = purchase({ pending: true }); await t.page.onLoad({ stationId: 1 }); await t.page.onPurchase()
  assert.equal(t.wx.__calls.modal.at(-1).title, '桶押金待确认')
  assert(t.wx.__calls.modal.at(-1).content.includes('确认实际收款后'))
})
test('响应丢失后恢复沿用原body与key，并明确查回原购买', async () => {
  const t = purchase({ fail: true }); await t.page.onLoad({ stationId: 1 }); await t.page.onPurchase()
  t.scenario.fail = false; await t.page.onRetryOriginal()
  assert.deepStrictEqual(t.bodies[1], t.bodies[0])
  assert.equal(t.wx.__calls.modal.at(-1).title, '原桶押金购买已查回')
})
test('原结算上下文原地保留，押金成功返回后重报容量和水款', async () => {
  const t = purchase(); let acquired = false, quoteCalls = 0
  const order = loadPage('miniapp-user/pages/order/create.js', { wx: t.wx, app: t.app, stubs: {
    'api/payment': { getQuote: async () => { quoteCalls++; return { data: { independentRights: true,
      waterAmount: 20, totalAmount: acquired ? 20 : 70, extraDeposit: acquired ? 0 : 50, extraDepositBuckets: acquired ? 0 : 1,
      methods: [{ id: 1, enabled: true, name: '模拟支付' }],
      barrelPurchases: acquired ? [] : [{ productId: 5, quantity: 1, unitPrice: 50, amount: 50 }], blocked: false, missingRights: acquired ? [] : [{ productId: 5, quantity: 1 }] } } } },
    'api/barrel': { getBarrelSummary: async () => ({ data: {} }), getBarrelSummaryByType: async () => ({ data: [] }) }
  } })
  order.setData({ stationId: 1, products: [{ ...water, quantity: 1 }], address: { id: 12 }, note: '放门口', selectedMethod: 1 })
  await order.refreshQuote(); order.onStandalonePurchase()
  assert(t.wx.__calls.nav.at(-1).url.includes('from=order'))
  await t.page.onLoad({ stationId: 1, productId: 5, from: 'order' })
  global.getCurrentPages = () => [{ ...order, route: 'pages/order/create' }, { route: 'pages/barrel/purchase' }]
  await t.page.onPurchase(); assert.equal(t.wx.__calls.modal.at(-1).confirmText, '继续订水')
  assert.equal(t.wx.__calls.nav.at(-1).type, 'navigateBack')
  acquired = true; order.onShow(); await flush()
  assert(quoteCalls >= 2); assert.equal(order.data.blocked, false)
  assert.equal(order.data.payableAmountText, '20.00'); assert.equal(order.data.stationId, 1)
  assert.equal(order.data.products[0].quantity, 1); assert.equal(order.data.address.id, 12)
  assert.equal(order.data.note, '放门口'); assert.equal(order.data.selectedMethod, 1)
})
test('原购买在别站，不自动返回不相符的订水页', async () => {
  const t = purchase(); await t.page.onLoad({ stationId: 1, from: 'order' })
  global.getCurrentPages = () => [{ route: 'pages/order/create', data: { stationId: 2 } }, {}]
  await t.page.onPurchase(); assert.equal(t.wx.__calls.nav.length, 0)
})
test('桶资产页进入只返回上一页，不强制跳订水或重置购物车', async () => {
  const t = purchase(); await t.page.onLoad({ stationId: 1 })
  assert.equal(t.page.data.fromOrder, false)
  global.getCurrentPages = () => [{ route: 'pages/barrel/index', data: { stationId: 1 } }, {}]
  await t.page.onPurchase(); assert.equal(t.wx.__calls.nav.length, 0)
  t.page.onBack(); assert.equal(t.wx.__calls.nav.at(-1).type, 'navigateBack')
})
test('成功弹窗打开后换会话，旧确认不能触发返回', async () => {
  const t = purchase(); await t.page.onLoad({ stationId: 1, from: 'order' })
  global.getCurrentPages = () => [{ route: 'pages/order/create', data: { stationId: 1 } }, {}]
  t.wx.__modalAutoConfirm = false; await t.page.onPurchase()
  const modal = t.wx.__calls.modal.at(-1); assert.equal(modal.confirmText, '继续订水')
  t.app.globalData.isLogin = false; modal.success({ confirm: true })
  assert.equal(t.wx.__calls.nav.length, 0)
})
test('两款水独立办第一款后，第二款随单押金仍须明确确认', async () => {
  let acquired = false, orders = 0
  const t = purchase(), order = loadPage('miniapp-user/pages/order/create.js', { wx: t.wx, app: t.app, stubs: {
    'api/payment': { getQuote: async () => ({ data: { independentRights: true,
      waterAmount: 60, totalAmount: acquired ? 160 : 210, extraDeposit: acquired ? 100 : 150, extraDepositBuckets: acquired ? 2 : 3,
      barrelPurchases: acquired ? [{ productId: 6, quantity: 2, unitPrice: 50, amount: 100 }] : [{ productId: 5, quantity: 1, unitPrice: 50, amount: 50 }, { productId: 6, quantity: 2, unitPrice: 50, amount: 100 }], methods: [{ id: 1, enabled: true, name: '模拟支付' }], blocked: false,
      missingRights: acquired ? [{ productId: 6, quantity: 2 }] : [{ productId: 5, quantity: 1 }, { productId: 6, quantity: 2 }] } }) },
    'api/order': { createOrder: async () => { orders++; return {} } },
    'api/barrel': { getBarrelSummary: async () => ({ data: {} }), getBarrelSummaryByType: async () => ({ data: [] }) }
  } })
  order.setData({ stationId: 1, products: [{ ...water, quantity: 1 }, { ...water, id: 6, quantity: 2 }], address: { id: 12 }, note: '放门口', selectedMethod: 1 })
  await order.refreshQuote(); order.onStandalonePurchase()
  await t.page.onLoad({ stationId: 1, productId: 5, from: 'order' })
  global.getCurrentPages = () => [{ ...order, route: 'pages/order/create' }, {}]
  await t.page.onPurchase(); acquired = true; order.onShow(); await flush()
  t.wx.__modalAutoConfirm = false
  assert.equal(order.data.blocked, false); assert.equal(order.data.payableAmountText, '160.00')
  assert.deepStrictEqual(order.data.missingRights, [{ productId: 6, quantity: 2 }])
  await order.onSubmit(); assert.equal(t.wx.__calls.modal.at(-1).title, '确认本次新增押金')
  assert.equal(orders, 0); assert.equal(order.data.products[1].quantity, 2)
  assert.equal(order.data.address.id, 12); assert.equal(order.data.note, '放门口')
})
;(async () => {
  let passed = 0, failed = 0
  for (const t of tests) { try { await t.run(); passed++; console.log('PASS ' + t.name) }
    catch (error) { failed++; console.error('FAIL ' + t.name + '\n' + error.stack) } }
  done(); if (failed) process.exitCode = 1
  else console.log('AQUAFLOW_SUITE_OK ' + passed)
})().catch(error => { done(); console.error(error); process.exitCode = 1 })
