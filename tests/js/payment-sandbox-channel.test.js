const assert = require('assert')
const { fixture, localChannel, deferred, flush } = require('./payment-sandbox-harness')
const tests = [], test = (name, run) => tests.push({ name, run })
const sandbox = () => ({ ...localChannel(), simulationAuthorization: { version: 1, scope: 'PAYMENT_SANDBOX', customerId: 7,
  environmentId: 'synthetic-env', serviceId: 'synthetic-service', bindingId: 'synthetic-binding-001' } })
const entry = (t, kind, body) => kind === 'order'
  ? t.load('miniapp-user/api/order.js').createPayment(body)
  : kind === 'ticket' ? t.load('miniapp-user/api/ticket.js').purchaseTicket(body)
    : t.load('miniapp-user/api/barrel.js').purchaseBarrelRight(body)
const body = () => ({ orderId: 42, productId: 5, quantity: 1, stationId: 3, paymentMethod: 1, idempotencyKey: 'original-synthetic-key' })
for (const kind of ['order', 'ticket', 'barrel']) {
  test(kind + ': authorized trial uses one common capability before the original write', async () => {
    const t = fixture({ version: 'trial', channel: sandbox() }), input = body()
    await entry(t, kind, input)
    assert.deepStrictEqual(t.reads, ['/api/payments/capabilities']); assert.equal(t.posts.length, 1)
    assert.equal(t.posts[0].data.idempotencyKey, input.idempotencyKey); assert.equal(t.posts[0].data.quantity, 1)
  })
  test(kind + ': release rejects a simulated response even with a matching binding', async () => {
    const t = fixture({ version: 'release', channel: sandbox() })
    await assert.rejects(entry(t, kind, body())); assert.equal(t.posts.length, 0)
  })
  test(kind + ': trial label alone and another customer cannot authorize simulation', async () => {
    for (const channel of [localChannel(), { ...sandbox(), simulationAuthorization: undefined },
      { ...sandbox(), simulationAuthorization: { ...sandbox().simulationAuthorization, customerId: 8 } }]) {
      const t = fixture({ version: 'trial', channel }); await assert.rejects(entry(t, kind, body())); assert.equal(t.posts.length, 0)
    }
  })
  test(kind + ': wrong service, environment or binding never writes', async () => {
    for (const key of ['environmentId', 'serviceId', 'bindingId']) {
      const t = fixture({ version: 'trial', channel: sandbox(), binding: { [key]: 'another-target' } })
      await assert.rejects(entry(t, kind, body())); assert.equal(t.posts.length, 0)
    }
  })
  test(kind + ': capability failure, corrupt envelope and disabled channel never write', async () => {
    for (const result of [null, { code: 1 }, { code: 0, data: {} }, { code: 0, data: { wechatPay: { ...sandbox(), enabled: false } } }]) {
      const t = fixture({ version: 'trial', read: async () => result })
      await assert.rejects(entry(t, kind, body())); assert.equal(t.posts.length, 0)
    }
  })
  test(kind + ': session/target changes during preflight cannot dispatch an old intent', async () => {
    for (const changed of ['session', 'target']) {
      const gate = deferred(), t = fixture({ version: 'trial', read: () => gate.promise })
      const task = entry(t, kind, body()); await flush()
      if (changed === 'session') t.nextSession(); else t.route({ mode: 'cloudContainer', env: 'synthetic-env', service: 'another-service' })
      gate.resolve({ code: 0, data: { wechatPay: sandbox() } }); await assert.rejects(task); assert.equal(t.posts.length, 0)
    }
  })
}
test('develop local test still requires explicit backend customer authorization', async () => {
  const t = fixture(); await entry(t, 'order', body()); assert.equal(t.posts.length, 1)
  t.customer(8); await assert.rejects(entry(t, 'barrel', body())); assert.equal(t.posts.length, 1)
})
test('future real channel remains usable in release without simulation authority', async () => {
  const t = fixture({ version: 'release', channel: { method: 1, enabled: true, simulated: false, label: '微信支付' } })
  await entry(t, 'order', body()); assert.equal(t.posts.length, 1)
})
test('20 concurrent order clicks share capability and original payment request', async () => {
  const gate = deferred(), t = fixture({ version: 'trial', channel: sandbox(), post: () => gate.promise })
  const tasks = Array.from({ length: 20 }, () => entry(t, 'order', body())); await flush()
  assert(tasks.every(p => p === tasks[0])); assert.equal(t.reads.length, 1); assert.equal(t.posts.length, 1)
  gate.resolve({ code: 0, data: { id: 12, status: 2 } }); await Promise.all(tasks)
})
test('release never reports a simulated receipt as successful', () => {
  const t = fixture({ version: 'release' }), notify = t.load('miniapp-user/utils/pay.js').notifyPayResult
  assert.equal(notify({ status: 2, simulated: true }), false); assert.equal(t.toast[0].icon, 'none')
})
test('ticket panel displays the authorized channel label and ignores a closed panel late result', async () => {
  const { loadPage } = require('./harness'), gate = deferred()
  let next = Promise.resolve(sandbox())
  global.__wxConfig = { envVersion: 'trial' }
  const page = loadPage('miniapp-user/pages/ticket/index.js', { stubs: { 'utils/payment-channel': { readOnlineChannel: () => next } } })
  page.setData({ currentStationId: 1 }); page.onShowPurchase(); await flush()
  assert.equal(page.data.buyMethods[0].name, sandbox().label); assert(page.data.buyMethods[0].desc.includes('不会扣款'))
  next = gate.promise; page.onShowPurchase(); page.onClosePurchase(); const before = JSON.stringify(page.data.buyMethods)
  gate.resolve(sandbox()); await flush(); assert.equal(JSON.stringify(page.data.buyMethods), before)
})
test('release ticket receipt cannot clear the original intent as a successful simulated purchase', () => {
  const { loadPage } = require('./harness')
  global.__wxConfig = { envVersion: 'release' }
  const page = loadPage('miniapp-user/pages/ticket/index.js'); let cleared = 0
  page.validPurchaseResult = () => true; page.clearPurchaseIntent = () => { cleared++ }
  assert.throws(() => page.applyPurchaseResult({ customerId: 7 }, { status: 2, simulated: true }))
  assert.equal(cleared, 0)
})
;(async () => { for (const t of tests) { await t.run(); console.log('PASS ' + t.name) }
  console.log('AQUAFLOW_SUITE_OK ' + tests.length)
})().catch(error => { console.error(error); process.exitCode = 1 })
