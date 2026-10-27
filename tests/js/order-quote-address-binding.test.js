'use strict'
// Real Page handlers, synthetic deferred APIs only: no database, credentials or payment SDK.
const assert = require('assert')
const { loadPage, createWx, createApp, armWatchdog } = require('./harness')
const done = armWatchdog(), tests = []
const test = (name, run) => tests.push({ name, run })
const flush = async () => { for (let i = 0; i < 30; i++) await Promise.resolve() }
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
const A = { id: 21, floor: 1, hasElevator: false }
const B = { id: 22, floor: 5, hasElevator: false }
const quote = (body, extra = {}) => ({ code: 0, data: {
  waterAmount: 20, floorFee: body.addressId === B.id ? 8 : 0,
  totalAmount: body.addressId === B.id ? 28 : 20, blocked: false,
  methods: [{ id: 1, name: '微信支付', enabled: true }, { id: 2, name: '货到付款', enabled: true }, { id: 3, name: '水票支付', enabled: true }],
  defaultMethod: 3, allowOfflinePayment: true, wechatPay: { enabled: true, simulated: true,
    simulationAuthorization: { version: 1, scope: 'LOCAL_TEST', customerId: 7 } },
  ticketPay: body.paymentMethod === 3 ? { fullyCovered: true, coverQty: 1, coverAmount: 20, payableAmount: 0 } : null,
  ...extra
} })
function fixture(options = {}) {
  global.__wxConfig = { envVersion: 'develop' }
  const wx = createWx(), app = createApp({ globalData: { isLogin: true, customerId: 7, tempStationId: 1 } })
  const calls = { quotes: [], addresses: 0, orders: [], payments: [] }
  const page = loadPage('miniapp-user/pages/order/create.js', { wx, app, stubs: {
    'api/payment': { getQuote: body => { calls.quotes.push(body); return options.quote ? options.quote(body, calls.quotes.length) : Promise.resolve(quote(body)) } },
    'api/address': { getAddresses: () => { calls.addresses++; return options.addresses ? options.addresses(calls.addresses) : Promise.resolve({ code: 0, data: [A] }) } },
    'api/product': { getProductDetail: async () => ({ code: 0, data: { id: 5, name: '合成商品', price: 20, category: 1 } }) },
    'api/barrel': { getBarrelSummary: async () => ({ data: {} }), getBarrelSummaryByType: async () => ({ data: [] }) },
    'api/station': { getStationStatus: async () => ({ data: {} }) },
    'api/order': { createOrder: async body => { calls.orders.push(body); return options.order ? options.order(body) : { data: { orderId: 901, warnings: options.warnings || [] } } },
      createPayment: async body => { calls.payments.push(body); return { data: { status: 2 } } },
      getOrderDetail: async () => ({ data: { id: 901, paymentStatus: 2 } }) }
  } })
  page.setData({ stationId: 1, products: [{ id: 5, name: '合成商品', quantity: 1, price: 20, category: 1 }],
    address: A, selectedMethod: options.method || 1, loading: false })
  const select = address => { require('../../miniapp-user/utils/storage').storage.set('selectedAddress', address); page.onShow() }
  return { page, wx, app, calls, select }
}
function blocked(t) {
  assert.equal(t.page.data.quoteReady, false, 'other-address quote must not be ready')
  assert.equal(t.page._finalSubmissionAllowed(), false, 'other-address quote must not authorize submission')
  assert.equal(t.calls.orders.length, 0); assert.equal(t.calls.payments.length, 0)
}
test('address refresh in flight: product load cannot retain a previous-address quote', async () => {
  const gate = deferred(), t = fixture({ addresses: () => gate.promise })
  t.page.onLoad({ stationId: '1', productId: '5' }); t.page.onShow(); await flush()
  blocked(t)
  assert.equal(t.calls.quotes.length, 0, 'must wait for the requested address')
  gate.resolve({ code: 0, data: [B] }); await flush()
  assert.equal(t.calls.quotes.at(-1).addressId, B.id); assert.equal(t.page.data.totalAmount, 28)
  assert.equal(t.page.data.quoteReady, true); assert.equal(t.calls.orders.length, 0)
})
test('fresh page with null address: slow initial load resumes with the actual address and floor fee', async () => {
  const gate = deferred(), t = fixture({ addresses: () => gate.promise })
  t.page.setData({ products: [], address: null, loading: true })
  t.page.onLoad({ stationId: '1', productId: '5' }); t.page.onShow(); await flush()
  const earlyReady = t.page.data.quoteReady, earlyRequests = t.calls.quotes.length
  gate.resolve({ code: 0, data: [B] }); await flush()
  const displayed = t.page.data.totalAmountText
  await t.page.onSubmit()
  console.log('TRACE initial-slow ' + JSON.stringify({ earlyReady, earlyRequests, displayed,
    quoteAddressIds: t.calls.quotes.map(b => b.addressId || null), orderAddressIds: t.calls.orders.map(b => b.addressId) }))
  assert.equal(earlyReady, false, 'product-first no-address quote must not become ready'); assert.equal(earlyRequests, 0)
  assert.equal(t.page.data.address.id, B.id); assert.equal(t.page.data.floorFee, 8)
  assert.equal(t.page.data.totalAmountText, '28.00'); assert.equal(t.page.data.quoteReady, true)
})
test('selecting B applies B before issuing the return-page quote', async () => {
  const t = fixture(); await t.page.refreshQuote(); t.select(B); await flush()
  const displayed = t.page.data.totalAmountText
  await t.page.onSubmit()
  console.log('TRACE selected-address ' + JSON.stringify({ displayed,
    quoteAddressIds: t.calls.quotes.map(b => b.addressId || null), orderAddressIds: t.calls.orders.map(b => b.addressId) }))
  assert.equal(t.calls.quotes.at(-1).addressId, B.id, 'return-page request must carry new address')
  assert.equal(t.page.data.address.id, B.id); assert.equal(t.page.data.totalAmount, 28)
})
test('late A success while B is pending cannot display or authorize A totals under B', async () => {
  const old = deferred(), fresh = deferred(), t = fixture({ quote: (_, n) => n === 1 ? old.promise : fresh.promise })
  const first = t.page.refreshQuote(); t.select(B)
  old.resolve(quote({ addressId: A.id })); await first; await flush()
  blocked(t); assert.notEqual(t.page.data.totalAmountText, '20.00')
  assert.equal(t.calls.quotes.at(-1).addressId, B.id)
  fresh.resolve(quote({ addressId: B.id })); await flush()
  assert.equal(t.page.data.quoteReady, true); assert.equal(t.page.data.totalAmount, 28)
})
test('late A response cannot overwrite successful B quote', async () => {
  for (const fails of [false, true]) {
    const old = deferred(), t = fixture({ quote: (body, n) => n === 1 ? old.promise : Promise.resolve(quote(body)) })
    const first = t.page.refreshQuote(); t.select(B); await flush()
    if (fails) old.reject(Error('synthetic late A failure')); else old.resolve(quote({ addressId: A.id }))
    await first; assert.equal(t.page.data.totalAmount, 28); assert.equal(t.page.data.quoteReady, true)
  }
})
test('address-list response cannot replace a newer explicit address selection', async () => {
  const old = deferred(), t = fixture({ addresses: () => old.promise })
  const first = t.page.loadAddress(); t.select(B); await flush()
  old.resolve({ code: 0, data: [A] }); await first
  assert.equal(t.page.data.address.id, B.id); assert.equal(t.page.data.totalAmount, 28)
})
test('editing same address id invalidates readiness and confirmation while replacement quote waits', async () => {
  const gate = deferred(), t = fixture({ quote: (body, n) => n === 1 ? Promise.resolve(quote(body)) : gate.promise })
  await t.page.refreshQuote(); t.select({ ...A, floor: 6 }); blocked(t)
  assert.notEqual(t.page.data.totalAmountText, '20.00')
  gate.resolve(quote({ addressId: A.id }, { floorFee: 10, totalAmount: 30 })); await flush()
  assert.equal(t.page.data.totalAmount, 30); assert.equal(t.page.data.quoteReady, true)
})
test('hidden page rejects in-flight quote and resumes only through a fresh onShow request', async () => {
  const old = deferred(), t = fixture({ quote: (body, n) => n === 1 ? old.promise : Promise.resolve(quote(body)) })
  const first = t.page.refreshQuote(); t.page.onHide()
  old.resolve(quote({ addressId: A.id })); await first; blocked(t)
  await t.page.refreshQuote(); assert.equal(t.calls.quotes.length, 1)
  t.page.onShow(); await flush(); assert.equal(t.calls.quotes.length, 2); assert.equal(t.page.data.quoteReady, true)
})
test('hidden initial address response is ignored; reopening reloads address before quoting', async () => {
  const old = deferred(), t = fixture({ addresses: n => n === 1 ? old.promise : Promise.resolve({ data: [B] }) })
  t.page.setData({ address: null }); const first = t.page.loadAddress(); t.page.onHide()
  old.resolve({ data: [A] }); await first; assert.equal(t.page.data.address, null); blocked(t)
  t.page.onShow(); await flush(); assert.equal(t.page.data.address.id, B.id); assert.equal(t.page.data.totalAmount, 28)
})
test('unloaded page ignores late address and quote; new instance has no inherited quote authorization', async () => {
  const old = deferred(), t = fixture({ addresses: () => old.promise })
  t.page.setData({ address: null }); const first = t.page.loadAddress(); t.page.onUnload()
  old.resolve({ data: [A] }); await first; assert.equal(t.page.data.address, null); blocked(t)
  const next = fixture(); blocked(next); await next.page.refreshQuote(); assert.equal(next.page.data.quoteReady, true)
})
test('empty or failed address refresh cannot keep a previously verified quote submittable', async () => {
  for (const response of [{ data: [] }, { code: 1, data: [A] }, new Error('synthetic address failure')]) {
    const t = fixture({ addresses: async () => { if (response instanceof Error) throw response; return response } })
    await t.page.refreshQuote(); await t.page.loadAddress(); blocked(t)
    await t.page.refreshQuote(); blocked(t)
  }
})
test('late address response from a previous login cannot start a quote in the new session', async () => {
  const old = deferred(), t = fixture({ addresses: n => n === 1 ? old.promise : Promise.resolve({ data: [B] }) })
  t.page.setData({ address: null }); const first = t.page.loadAddress()
  require('../../miniapp-user/utils/token').beginSession()
  old.resolve({ data: [A] }); await first; assert.equal(t.page.data.address, null); blocked(t)
  t.page.onShow(); await flush(); assert.equal(t.page.data.address.id, B.id); assert.equal(t.page.data.totalAmount, 28)
})
test('cash confirmation from A cannot submit B until B quote is ready and user confirms again', async () => {
  const gate = deferred(), t = fixture({ method: 2, quote: (body, n) => n === 1 ? Promise.resolve(quote(body)) : gate.promise })
  await t.page.refreshQuote(); await t.page.onSubmit(); assert.equal(t.page.data.showUnifiedConfirm, true)
  const oldProof = t.page._unifiedConfirmation; t.select(B)
  await t.page._finishConfirmedOrder(oldProof); blocked(t)
  gate.resolve(quote({ addressId: B.id })); await flush()
  await t.page.onSubmit(); await t.page.onUnifiedConfirmOk()
  assert.equal(t.calls.orders.length, 1); assert.equal(t.calls.orders[0].addressId, B.id)
  assert.equal(t.calls.payments.length, 0)
})
test('boolean readiness alone cannot authorize address-mismatched cash or WeChat submission', async () => {
  for (const method of [1, 2]) {
    const t = fixture({ method }); await t.page.refreshQuote()
    t.page.setData({ address: B }); t.wx.__modalAutoConfirm = false
    assert.equal(t.page._finalSubmissionAllowed(), false, 'must bind quote to its original address')
    await t.page._createOrder(false); assert.equal(t.calls.orders.length, 0, 'last create entry must also bind address')
    await t.page.onSubmit(); assert.equal(t.calls.orders.length, 0); assert.equal(t.calls.payments.length, 0)
  }
})
test('ticket submission awaiting A quote cannot continue under B, even after B quote succeeds', async () => {
  const old = deferred(), t = fixture({ method: 3, quote: (body, n) => n === 2 ? old.promise : Promise.resolve(quote(body)) })
  await t.page.refreshQuote(); const submit = t.page.onSubmit(); t.select(B); await flush()
  old.resolve(quote({ addressId: A.id, paymentMethod: 3 })); await submit
  assert.equal(t.page.data.totalAmount, 28); assert.equal(t.calls.orders.length, 0); assert.equal(t.calls.payments.length, 0)
  await t.page.onSubmit(); assert.equal(t.calls.orders.length, 1); assert.equal(t.calls.orders[0].addressId, B.id)
})
test('server payment-method fallback finishes loading and still requires an explicit submit', async () => {
  const t = fixture({ method: 3, quote: async body => quote(body, { methods: [{ id: 1, name: '微信支付', enabled: true }], defaultMethod: 1 }) })
  await t.page.refreshQuote(); assert.equal(t.page.data.selectedMethod, 1); assert.equal(t.page.data.quoteLoading, false)
  assert.equal(t.calls.orders.length, 0); await t.page.onSubmit(); assert.equal(t.calls.orders.length, 1)
})
test('hide during an already submitted order preserves its original synthetic channel policy', async () => {
  for (const method of [1, 2, 3]) {
    const gate = deferred(), t = fixture({ method, order: () => gate.promise })
    await t.page.refreshQuote(); const submit = t.page._createOrder(false); t.page.onHide()
    gate.resolve({ data: { orderId: 901, warnings: [] } }); await submit
    assert.equal(t.calls.orders.length, 1); assert.equal(t.calls.payments.length, method === 2 ? 0 : 1)
    assert.equal(t.page.data.quoteReady, false)
  }
})
test('blocked ticket quote does not submit; explicit WeChat switch quotes B before any synthetic payment', async () => {
  const t = fixture({ method: 3, quote: async body => quote(body, body.paymentMethod === 3 ? { blocked: true, blockReason: '合成水票不足' } : {}) })
  t.page.setData({ address: B }); await t.page.refreshQuote(); await t.page.onSubmit()
  assert.equal(t.calls.orders.length, 0); assert.equal(t.calls.payments.length, 0)
  t.page.onSwitchPayMethod({ currentTarget: { dataset: { id: 1 } } }); await flush()
  assert.equal(t.page.data.payableAmountText, '28.00'); assert.equal(t.calls.orders.length, 0)
})
test('order warnings remain post-create information; synthetic callback completes before payment', async () => {
  const t = fixture({ warnings: ['合成楼层加收费提示'] }); t.page.setData({ address: B })
  await t.page.refreshQuote(); await t.page.onSubmit()
  assert.equal(t.calls.orders.length, 1); assert.equal(t.calls.orders[0].addressId, B.id)
  assert.equal(t.wx.__calls.modal.at(-1).title, '下单成功，请注意')
  assert.equal(t.wx.__calls.modal.at(-1).showCancel, false); assert.equal(t.calls.payments.length, 1)
})
;(async () => {
  let passed = 0, failed = 0
  for (const item of tests) {
    try { await item.run(); passed++; console.log('PASS ' + item.name) }
    catch (e) { failed++; console.error('FAIL ' + item.name + ': ' + e.message) }
  }
  console.log(`quote address binding: ${passed} passed, ${failed} failed`)
  if (failed) process.exitCode = 1; else console.log('AQUAFLOW_SUITE_OK ' + passed)
})().catch(e => { console.error(e.message); process.exitCode = 1 }).finally(done)
