const assert = require('assert')
const fs = require('fs')
const path = require('path')
const vm = require('vm')
const ROOT = path.resolve(__dirname, '../..')
const sandboxHarness = require('./payment-sandbox-harness')
const flush = sandboxHarness.flush
function deferred() { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
function fixture() {
  const pending = []
  const t = sandboxHarness.fixture({ post: () => { const d = deferred(); pending.push(d); return d.promise } })
  return { pay: t.load('miniapp-user/api/order.js').createPayment, calls: t.posts, pending, nextSession: t.nextSession,
    trial: () => t.version('trial'), real: () => t.channel({ method: 1, enabled: true, simulated: false, label: '微信支付' }) }
}
let passed = 0
async function test(name, run) { await run(); passed++; console.log('PASS ' + name) }
async function main() {
  await test('20 clicks across callers share the original request and confirmed receipt', async () => {
    const t = fixture(), requests = Array.from({ length: 20 }, () => t.pay({ orderId: 42, paymentMethod: 1 }))
    assert(requests.every(p => p === requests[0])); await flush(); assert.equal(t.calls.length, 1)
    assert.equal(t.calls[0].data.orderId, 42)
    const result = { data: { id: 12, status: 2, simulated: true } }; t.pending[0].resolve(result)
    assert((await Promise.all(requests)).every(r => r === result))
  })
  await test('timeout and failure release the flight but retry the same order', async () => {
    const t = fixture(), first = t.pay({ orderId: 42, paymentMethod: 1 })
    await flush()
    t.pending[0].reject(new Error('timeout')); await assert.rejects(first, /timeout/)
    const retry = t.pay({ orderId: 42, paymentMethod: 1 })
    await flush()
    assert.equal(t.calls.length, 2); assert.equal(t.calls[1].data.orderId, 42)
    t.pending[1].resolve({ data: { id: 12, status: 2 } }); assert.equal((await retry).data.id, 12)
  })
  await test('late old-session completion cannot erase the new-session flight', async () => {
    const t = fixture(), old = t.pay({ orderId: 42, paymentMethod: 1 }); await flush(); t.nextSession()
    const current = t.pay({ orderId: 42, paymentMethod: 1 }); assert.notStrictEqual(old, current)
    await flush()
    t.pending[0].resolve({ data: { status: 1 } }); await old
    assert.strictEqual(t.pay({ orderId: 42, paymentMethod: 1 }), current)
    t.pending[1].resolve({ data: { status: 2 } }); await current; assert.equal(t.calls.length, 2)
  })
  await test('different orders remain independent and successful repeats can query the original server intent', async () => {
    const t = fixture(), a = t.pay({ orderId: 1 }), b = t.pay({ orderId: 2 }); assert.notStrictEqual(a, b)
    t.pending[0].resolve({ data: { id: 11 } }); t.pending[1].resolve({ data: { id: 22 } }); await Promise.all([a, b])
    const repeat = t.pay({ orderId: 1 }); assert.equal(t.calls[2].data.orderId, 1)
    t.pending[2].resolve({ data: { id: 11 } }); assert.equal((await repeat).data.id, 11)
  })
  await test('explicit simulated trial is blocked locally; method=1 alone is not a fake-success trigger', async () => {
    const t = fixture(); t.trial(); await assert.rejects(t.pay({ orderId: 42, paymentMethod: 1 }, { simulatedWechatPay: true }))
    assert.equal(t.calls.length, 0)
    t.real(); const normal = t.pay({ orderId: 42, paymentMethod: 1 }); await flush(); t.pending[0].resolve({ data: { status: 1 } })
    assert.equal((await normal).data.status, 1)
  })
  await test('only backend PAID can report success and simulated receipt remains visibly simulated', () => {
    const toast = [], modal = [], context = { module: { exports: {} }, require: () => ({ isReleaseEnv: () => false }), wx: { showToast: x => toast.push(x), showModal: x => modal.push(x) } }
    vm.runInNewContext(fs.readFileSync(path.join(ROOT, 'miniapp-user/utils/pay.js'), 'utf8'), context)
    const notify = context.module.exports.notifyPayResult
    assert.equal(notify({ status: 1, simulated: true }), false); assert.equal(toast.length, 0); assert.equal(modal.length, 1)
    assert.equal(notify({ status: 2, simulated: true, simulationResultText: '模拟支付成功（未扣款）' }), true)
    assert.equal(toast[0].title, '模拟支付成功（未扣款）'); assert.equal(toast[0].icon, 'none')
    notify({ status: 2 }); assert.equal(toast[1].title, '支付成功')
    assert.equal(notify({ status: 4 }), false)
  })
  console.log('AQUAFLOW_SUITE_OK ' + passed)
}
main().catch(error => { console.error(error); process.exitCode = 1 })
