const assert = require('assert')
const { loadPage, createWx, createApp, armWatchdog } = require('./harness')
const done = armWatchdog(30000)
const tests = [], test = (name, run) => tests.push({ name, run })
const flush = async () => { for (let i = 0; i < 10; i++) await Promise.resolve() }
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
function clock() {
  let now = 0, next = 0
  const tasks = new Map()
  return { setTimeout(fn, delay) { const id = ++next; tasks.set(id, { fn, at: now + delay }); return id },
    clearTimeout(id) { tasks.delete(id) },
    async advance(ms) { now += ms; for (const [id, task] of [...tasks]) if (task.at <= now) { tasks.delete(id); task.fn() }; await flush() } }
}
const quote = qty => ({ data: { waterAmount: qty * 20, totalAmount: qty * 20, blocked: false,
  methods: [{ id: 3, name: '水票支付', enabled: true }], defaultMethod: 3,
  ticketPay: { fullyCovered: true, coverQty: qty, coverAmount: qty * 20, payableAmount: 0 } } })
function fixture(handler = async body => quote(body.items[0].quantity)) {
  const wx = createWx(), app = createApp(), calls = { quotes: [], orders: [] }
  const page = loadPage('miniapp-user/pages/order/create.js', { wx, app, stubs: {
    'api/payment': { getQuote: body => { calls.quotes.push(body); return handler(body) } },
    'api/order': { createOrder: async body => { calls.orders.push(body); return { data: { orderId: 1 } } } }
  } })
  page.setData({ products: [{ id: 5, quantity: 1, price: 20, category: 1 }], stationId: 1,
    address: { id: 12 }, selectedMethod: 3, loading: false, quoteReady: true, quoteLoading: false, blocked: false })
  const input = value => page.onQtyInput({ currentTarget: { dataset: { index: 0 } }, detail: { value: String(value) } })
  return { page, app, wx, calls, input }
}
test('rapid input coalesces to the latest quantity and blocks submission immediately', async timer => {
  const t = fixture(); t.input(2); t.input(20); t.input(201)
  assert.equal(t.calls.quotes.length, 0); assert.equal(t.page.data.quoteReady, false); assert.equal(t.page.data.blocked, true)
  await t.page.onSubmit(); assert.equal(t.calls.orders.length, 0)
  await timer.advance(299); assert.equal(t.calls.quotes.length, 0)
  await timer.advance(1); assert.equal(t.calls.quotes.length, 1); assert.equal(t.calls.quotes[0].items[0].quantity, 201)
  assert.equal(t.page.data.quoteReady, true); assert.equal(t.calls.orders.length, 0)
})
test('rapid plus/minus presses also share one quote', async timer => {
  const t = fixture(), event = type => ({ currentTarget: { dataset: { index: 0, type } } })
  t.page.onQtyChange(event('add')); t.page.onQtyChange(event('add')); t.page.onQtyChange(event('minus'))
  assert.equal(t.calls.quotes.length, 0); await timer.advance(300)
  assert.equal(t.calls.quotes.length, 1); assert.equal(t.calls.quotes[0].items[0].quantity, 2)
})
test('an older in-flight quote cannot restore readiness during debounce', async timer => {
  const old = deferred(); let count = 0
  const t = fixture(body => ++count === 1 ? old.promise : Promise.resolve(quote(body.items[0].quantity)))
  const first = t.page.refreshQuote(); t.input(3); assert.equal(t.calls.quotes.length, 1)
  old.resolve(quote(1)); await first
  assert.equal(t.page.data.quoteReady, false); assert.equal(t.page.data.quoteLoading, true)
  await timer.advance(300); assert.equal(t.page.data.quoteReady, true); assert.equal(t.page.data.totalAmount, 60)
})
test('manual retry cancels the timer and checks the latest quantity immediately', async timer => {
  const t = fixture(); t.input(3); await t.page.onRetryQuote()
  assert.equal(t.calls.quotes.length, 1); assert.equal(t.calls.quotes[0].items[0].quantity, 3)
  await timer.advance(300); assert.equal(t.calls.quotes.length, 1); assert.equal(t.page.data.quoteReady, true)
})
test('failed debounced quote keeps submission blocked and clears loading', async timer => {
  const t = fixture(async () => { throw new Error('synthetic quote failure') }); t.input(3)
  await timer.advance(300); assert.equal(t.calls.quotes.length, 1); assert.equal(t.page.data.quoteReady, false)
  assert.equal(t.page.data.quoteLoading, false); assert.ok(t.page.data.quoteError); assert.equal(t.page.data.blocked, true)
})
test('unload clears scheduled quote without issuing another request', async timer => {
  const t = fixture(); t.input(3); t.page.onUnload(); await timer.advance(300); assert.equal(t.calls.quotes.length, 0)
})
test('unload also invalidates already issued quote callbacks', async () => {
  const old = deferred(), t = fixture(() => old.promise), pending = t.page.refreshQuote()
  t.page.onUnload(); old.resolve(quote(1)); await pending; assert.equal(t.page.data.quoteReady, false)
})
test('relogin before the timer cannot price the previous customer intent under the new login', async timer => {
  const t = fixture(); t.input(3); require('../../miniapp-user/utils/token').beginSession()
  await timer.advance(300); assert.equal(t.calls.quotes.length, 0); assert.equal(t.page.data.quoteReady, false)
  assert.equal(t.page.data.quoteLoading, false); assert.equal(t.page.data.blocked, true)
})
;(async () => {
  let passed = 0, failed = 0
  for (const item of tests) {
    const timer = clock(), oldSet = global.setTimeout, oldClear = global.clearTimeout
    global.setTimeout = timer.setTimeout; global.clearTimeout = timer.clearTimeout
    try { await item.run(timer); passed++; console.log('PASS ' + item.name) }
    catch (e) { failed++; console.error('FAIL ' + item.name + ' [' + e.name + ']') }
    finally { global.setTimeout = oldSet; global.clearTimeout = oldClear }
  }
  console.log(`order quote debounce: ${passed} passed, ${failed} failed`)
  if (failed) process.exitCode = 1; else console.log('AQUAFLOW_SUITE_OK ' + passed)
})().catch(() => { console.error('quote debounce suite interrupted'); process.exitCode = 1 }).finally(done)
