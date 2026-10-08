'use strict'
const assert = require('assert')
const { loadPage, createWx, createApp, armWatchdog } = require('./harness')
const token = require('../../miniapp-user/utils/token')
const done = armWatchdog()
let passed = 0
async function test(name, fn) { await fn(); passed++; console.log('PASS ' + name) }
function deferred() { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
const envelope = data => ({ code: 0, data })
const record = (id = 12, extra = {}) => ({ id, amount: '60.00', createTime: '2026-10-07T09:30:00',
  methodText: '微信', statusText: '已付款', status: 2, purposeText: '独立押金', ...extra })
function setup(handler = () => envelope([record()]), realRequest = false) {
  const wx = createWx(), state = { calls: 0, stops: 0 }
  wx.stopPullDownRefresh = () => state.stops++
  wx.getAccountInfoSync = () => ({ miniProgram: { envVersion: 'develop' } })
  const app = createApp({ globalData: { isLogin: true, customerId: 7, accessToken: 'customer-seven', refreshToken: 'refresh-seven' } })
  const page = loadPage('miniapp-user/pages/payment/records.js', { wx, app,
    stubs: realRequest ? {} : { 'api/payment': { getPaymentsByCustomer: () => { state.calls++; return handler() } } } })
  token.beginSession()
  return { page, wx, app, state }
}
async function main() {
  global.__wxConfig = { envVersion: 'develop' }
  await test('first failure persists without falsely confirming empty; retry succeeds', async () => {
    let fail = true
    const t = setup(() => { if (fail) throw Error('网络暂不可用'); return envelope([record()]) })
    await t.page.onShow()
    assert.equal(t.page.data.loaded, false); assert.equal(t.page.data.loading, false)
    assert.match(t.page.data.error, /网络暂不可用/); assert.deepEqual(t.page.data.records, [])
    assert.equal(t.wx.__calls.toast.length, 0)
    fail = false; await t.page.onRetry()
    assert.equal(t.page.data.loaded, true); assert.equal(t.page.data.error, '')
    assert.equal(t.page.data.records[0].purposeText, '独立押金'); assert.equal(t.state.calls, 2)
  })
  await test('only successful array response confirms true empty', async () => {
    const t = setup(() => envelope([])); await t.page.onShow()
    assert.equal(t.page.data.loaded, true); assert.equal(t.page.data.error, ''); assert.deepEqual(t.page.data.records, [])
  })
  await test('refresh failure retains prior signed amount and marks stale results', async () => {
    let fail = false
    const t = setup(() => { if (fail) throw Error('刷新断网'); return envelope([record(5, { amount: '-12.50' })]) })
    await t.page.onShow(); const previous = JSON.stringify(t.page.data.records)
    fail = true; await t.page.onRetry()
    assert.equal(JSON.stringify(t.page.data.records), previous); assert.equal(t.page.data.loaded, true)
    assert.equal(t.page.data.records[0].amountText, '-12.50'); assert.match(t.page.data.error, /刷新失败.*上次成功/)
  })
  await test('prior empty success remains explicitly stale on failed refresh', async () => {
    let count = 0; const t = setup(() => { if (++count === 1) return envelope([]); throw Error('刷新断网') })
    await t.page.onShow(); await t.page.onRetry(); assert.equal(t.page.data.loaded, true)
    assert.match(t.page.data.error, /上次成功/); assert.deepEqual(t.page.data.records, [])
  })
  await test('refresh shows prior rows until a current successful result replaces them', async () => {
    const gate = deferred(); let count = 0
    const t = setup(() => ++count === 1 ? envelope([record(1)]) : gate.promise)
    await t.page.onShow(); const loading = t.page.onRetry()
    assert.equal(t.page.data.loading, true); assert.equal(t.page.data.records[0].id, 1)
    gate.resolve(envelope([])); await loading
    assert.equal(t.page.data.loaded, true); assert.equal(t.page.data.loading, false); assert.deepEqual(t.page.data.records, [])
  })
  await test('malformed and rejected envelopes fail rather than becoming empty', async () => {
    for (const response of [null, {}, { code: 1, message: '无权读取', data: [] }, envelope(null), envelope({}),
      envelope([null]), envelope([{}]), envelope([record(1, { amount: null })]),
      envelope([record(1, { amount: '' })]), envelope([record(1, { amount: 'not-money' })]),
      envelope([record(1, { createTime: 'not-time' })])]) {
      const t = setup(() => response); await t.page.onShow()
      assert.equal(t.page.data.loaded, false); assert(t.page.data.error); assert.equal(t.page.data.loading, false)
    }
  })
  await test('zero amount and missing optional time stay displayable without inventing facts', async () => {
    const t = setup(() => envelope([record(1, { amount: 0, createTime: null })])); await t.page.onShow()
    assert.equal(t.page.data.loaded, true); assert.equal(t.page.data.records[0].amountText, '0.00')
  })
  await test('server purpose and status are preserved; missing purpose is neutral despite notes/amount', async () => {
    const t = setup(() => envelope([record(1, { purposeText: '水票购买', statusText: '原渠道退款处理中' }),
      record(2, { purposeText: null, note: '购票', barrelDeposit: 60 }),
      record(3, { purposeText: undefined, orderId: 99 }), record(4, { purposeText: '' })]))
    await t.page.onShow(); assert.equal(t.page.data.records[0].purposeText, '水票购买')
    assert.equal(t.page.data.records[0].statusText, '原渠道退款处理中')
    for (const r of t.page.data.records.slice(1)) assert.equal(r.purposeText, '支付记录')
  })
  for (const fail of [false, true]) await test('late old ' + (fail ? 'failure' : 'success') + ' cannot stop newer pending load', async () => {
    const old = deferred(), current = deferred(); let count = 0
    const t = setup(() => ++count === 1 ? old.promise : current.promise)
    const a = t.page.onShow(), b = t.page.onRetry()
    if (fail) old.reject(Error('旧失败')); else old.resolve(envelope([record(90)]))
    await a; assert.equal(t.page.data.loading, true); assert.equal(t.page.data.error, '')
    current.resolve(envelope([record(7)])); await b
    assert.equal(t.page.data.loading, false); assert.equal(t.page.data.records[0].id, 7)
  })
  await test('late success cannot replace completed newer result', async () => {
    const old = deferred(); let count = 0; const t = setup(() => ++count === 1 ? old.promise : envelope([record(8)]))
    const a = t.page.onShow(); await t.page.onRetry(); const snapshot = JSON.stringify(t.page.data)
    old.resolve(envelope([record(99)])); await a; assert.equal(JSON.stringify(t.page.data), snapshot)
  })
  await test('customer switch clears old rows before a failed new query', async () => {
    let fail = false; const t = setup(() => { if (fail) throw Error('新客户查询失败'); return envelope([record()]) })
    await t.page.onShow(); t.app.globalData.customerId = 8; fail = true; await t.page.onShow()
    assert.equal(t.page.data.loaded, false); assert.deepEqual(t.page.data.records, []); assert.match(t.page.data.error, /新客户/)
  })
  await test('same customer logout and relogin clears prior cache', async () => {
    const t = setup(); await t.page.onShow(); token.beginSession()
    t.page.onHide(); const load = t.page.onShow()
    assert.deepEqual(t.page.data.records, []); await load
    assert.equal(t.page.data.loaded, true)
  })
  await test('session changed during response clears stale money and ends loading', async () => {
    const gate = deferred(), t = setup(() => gate.promise), load = t.page.onShow()
    t.app.globalData.customerId = 8; gate.resolve(envelope([record()])); await load
    assert.equal(t.page.data.loaded, false); assert.equal(t.page.data.loading, false)
    assert.deepEqual(t.page.data.records, []); assert.match(t.page.data.error, /登录身份已变化/)
  })
  await test('normal credential refresh preserves the current request session', async () => {
    const gate = deferred(), t = setup(() => gate.promise), load = t.page.onShow()
    assert(token.acceptRefreshedTokens(token.captureSession(), 'renewed', 'renewed-refresh'))
    gate.resolve(envelope([record()])); await load; assert.equal(t.page.data.loaded, true)
  })
  await test('hidden late failure is ignored and returning page performs fresh read', async () => {
    const old = deferred(); let count = 0; const t = setup(() => ++count === 1 ? old.promise : envelope([record(8)]))
    const a = t.page.onShow(); t.page.onHide(); const snapshot = JSON.stringify(t.page.data)
    old.reject(Error('旧页面断网')); await a; assert.equal(JSON.stringify(t.page.data), snapshot)
    await t.page.onShow(); assert.equal(t.page.data.records[0].id, 8)
  })
  await test('unload blocks late results and subsequent retries', async () => {
    const gate = deferred(), t = setup(() => gate.promise), load = t.page.onShow()
    t.page.onUnload(); const snapshot = JSON.stringify(t.page.data); gate.resolve(envelope([record()]))
    await load; await t.page.onRetry(); assert.equal(JSON.stringify(t.page.data), snapshot); assert.equal(t.state.calls, 1)
  })
  await test('logged-out query never sends and pull refresh always stops', async () => {
    const t = setup(); t.app.globalData.isLogin = false; await t.page.onPullDownRefresh()
    assert.equal(t.state.calls, 0); assert.equal(t.state.stops, 1); assert.equal(t.page.data.loaded, false)
    assert.match(t.page.data.error, /登录/)
  })
  await test('pull refresh stops after failure and success', async () => {
    let fail = true; const t = setup(() => { if (fail) throw Error('弱网'); return envelope([]) })
    await t.page.onPullDownRefresh(); assert.equal(t.state.stops, 1)
    fail = false; await t.page.onPullDownRefresh(); assert.equal(t.state.stops, 2); assert.equal(t.page.data.loaded, true)
  })
  await test('older overlapping pull completion cannot stop the current pull animation', async () => {
    const old = deferred(), current = deferred(); let count = 0
    const t = setup(() => ++count === 1 ? old.promise : current.promise)
    const a = t.page.onPullDownRefresh(), b = t.page.onPullDownRefresh()
    old.resolve(envelope([record(1)])); await a
    assert.equal(t.state.stops, 0); assert.equal(t.page.data.loading, true)
    current.resolve(envelope([record(2)])); await b
    assert.equal(t.state.stops, 1); assert.equal(t.page.data.loading, false)
    assert.equal(t.page.data.records[0].id, 2)
  })
  await test('newer pull completing first stops once and older failure cannot stop again', async () => {
    const old = deferred(), current = deferred(); let count = 0
    const t = setup(() => ++count === 1 ? old.promise : current.promise)
    const a = t.page.onPullDownRefresh(), b = t.page.onPullDownRefresh()
    current.resolve(envelope([record(2)])); await b; assert.equal(t.state.stops, 1)
    old.reject(Error('旧请求超时')); await a; assert.equal(t.state.stops, 1)
    assert.equal(t.page.data.records[0].id, 2); assert.equal(t.page.data.error, '')
  })
  await test('hide ends the active pull and old completion cannot stop a later pull after show', async () => {
    const old = deferred(), current = deferred(); let count = 0
    const t = setup(() => ++count === 1 ? old.promise : count === 2 ? envelope([]) : current.promise)
    const a = t.page.onPullDownRefresh(); t.page.onHide(); assert.equal(t.state.stops, 1)
    await t.page.onShow(); const b = t.page.onPullDownRefresh()
    old.resolve(envelope([record(1)])); await a; assert.equal(t.state.stops, 1)
    assert.equal(t.page.data.loading, true)
    current.resolve(envelope([record(3)])); await b; assert.equal(t.state.stops, 2)
    assert.equal(t.page.data.records[0].id, 3)
  })
  await test('unload closes active pull once and ignores late completion or later pull calls', async () => {
    const gate = deferred(), t = setup(() => gate.promise), load = t.page.onPullDownRefresh()
    t.page.onUnload(); assert.equal(t.state.stops, 1)
    gate.reject(Error('卸载后超时')); await load; await t.page.onPullDownRefresh()
    assert.equal(t.state.stops, 1); assert.equal(t.state.calls, 1)
  })
  await test('real payment API/request wrapper turns business error into persistent page failure', async () => {
    const t = setup(undefined, true)
    t.wx.request = options => { t.wx.__calls.request.push(options); options.success({ statusCode: 200, data: { code: 1, message: '仅限本人查询' } }) }
    await t.page.onShow(); assert.match(t.wx.__calls.request[0].url, /\/api\/payments\/by-customer$/)
    assert.equal(t.wx.__calls.request[0].header.Authorization, 'Bearer customer-seven')
    assert.equal(t.page.data.loaded, false); assert.match(t.page.data.error, /仅限本人/)
  })
  console.log('AQUAFLOW_SUITE_OK ' + passed)
}
main().then(() => done(), err => { done(); console.error(err); process.exitCode = 1 })
