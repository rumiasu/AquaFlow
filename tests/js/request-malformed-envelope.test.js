/** Actual request/API/customer App/token; wx callbacks are invoked on a later event-loop turn, no network. */
const assert = require('assert')
const fs = require('fs')
const path = require('path')
const Module = require('module')
const { createWx, armWatchdog } = require('./harness')
const ROOT = path.resolve(__dirname, '../..')
const sourceRoot = process.env.AQUAFLOW_REQUEST_SOURCE_ROOT ? path.resolve(process.env.AQUAFLOW_REQUEST_SOURCE_ROOT) : ROOT
const tests = [], results = [], observations = []
const test = (name, run) => tests.push({ name, run })
const advance = async () => { await new Promise(resolve => setImmediate(resolve)); for (let i = 0; i < 10; i++) await Promise.resolve() }
let currentName = ''
function observed(promise) {
  const state = { settled: false }
  promise.then(value => Object.assign(state, { settled: true, value }), error => Object.assign(state, { settled: true, error }))
  return state
}
function setup(side) {
  const wx = createWx(), sent = []
  wx.request = options => sent.push(options)
  wx.__modalAutoConfirm = false
  global.wx = wx
  global.__wxConfig = { envVersion: 'develop' }
  global.getCurrentPages = () => [{}]
  let app = { globalData: { accessToken: 'access-old', refreshToken: 'refresh-old', userInfo: { staffId: 7, role: 'DELIVERY', stationId: 1 }, isLogin: true } }
  global.getApp = () => app
  const files = [side + '/utils/request.js', side + '/api/' + (side === 'miniapp-user' ? 'ticket.js' : 'delivery.js')]
  if (side === 'miniapp-user') files.push(side + '/app.js', side + '/utils/token.js')
  for (const file of files) delete require.cache[require.resolve(path.join(ROOT, file))]
  // Override only the two authorized sources when replaying saved red bytes. Dependencies stay actual modules.
  const file = path.join(ROOT, side, 'utils/request.js')
  const wrapper = new Module(file, module)
  wrapper.filename = file; wrapper.paths = Module._nodeModulePaths(path.dirname(file))
  require.cache[file] = wrapper
  wrapper._compile(fs.readFileSync(path.join(sourceRoot, side, 'utils/request.js'), 'utf8'), file)
  wrapper.loaded = true
  if (side === 'miniapp-user') {
    global.App = definition => { app = definition }
    require(path.join(ROOT, side, 'app.js'))
    app.setLoginInfo('access-old', 'refresh-old', { customerId: 7 })
  }
  const { STORAGE_KEYS: K } = require(path.join(ROOT, side, 'utils/storage-keys'))
  wx.setStorageSync(K.ACCESS_TOKEN, 'access-old'); wx.setStorageSync(K.REFRESH_TOKEN, 'refresh-old')
  if (side === 'miniapp-user') wx.setStorageSync(K.CUSTOMER_ID, 7)
  const api = require(path.join(ROOT, side, 'api', side === 'miniapp-user' ? 'ticket.js' : 'delivery.js'))
  return { wx, sent, app, K, request: wrapper.exports, api, side }
}
async function success(call, response) {
  let thrown = null
  await new Promise(resolve => setImmediate(() => {
    try { call.success(response) } catch (error) { thrown = error }
    finally { if (call.complete) call.complete(); resolve() }
  }))
  await advance()
  return thrown
}
async function failure(call, error) {
  let thrown = null
  await new Promise(resolve => setImmediate(() => {
    try { call.fail(error) } catch (caught) { thrown = caught }
    finally { if (call.complete) call.complete(); resolve() }
  }))
  await advance()
  return thrown
}
function record(state, thrown) {
  observations.push({ name: currentName, callbackThrown: thrown ? thrown.name + ': ' + thrown.message : null,
    settled: state.settled, rejected: !!state.error, errorMessage: state.error && state.error.message })
  assert.strictEqual(thrown, null, 'delayed platform callback must not throw outside Promise executor')
  assert.strictEqual(state.settled, true, 'request must settle after callback and microtasks')
}
function rejected(state, thrown) {
  record(state, thrown)
  assert(state.error instanceof Error)
  assert.strictEqual(typeof state.error.message, 'string')
  assert(state.error.message.trim())
  assert(!/\[object Object\]|undefined|null|refresh failed/.test(state.error.message), 'error must be readable')
}
const body = () => ({ stationId: 1, productId: 5, quantity: 3, paymentMethod: 1, idempotencyKey: 'original-purchase' })
const envelope = data => ({ statusCode: 200, data })
const renewed = () => envelope({ code: 0, data: { accessToken: 'access-next', refreshToken: 'refresh-next' } })
async function retried(t, state) {
  assert.strictEqual(await success(t.sent[0], { statusCode: 401, data: undefined }), null)
  assert.strictEqual(t.sent.length, 2, 'HTTP401 with no body must refresh')
  assert.strictEqual(t.sent[1].data.refreshToken, 'refresh-old')
  assert.strictEqual(await success(t.sent[1], renewed()), null)
  assert.strictEqual(t.sent.length, 3)
  assert.deepStrictEqual(t.sent[2].data, body(), 'retry must preserve original body/key')
  assert.strictEqual(t.sent[2].header.Authorization, 'Bearer access-next')
  assert.strictEqual(state.settled, false)
  return t.sent[2]
}
const malformed = [
  ['null', null], ['undefined', undefined], ['array', []], ['string', 'broken'], ['missing-code', {}],
  ['string-code', { code: '0' }], ['fraction-code', { code: 0.5 }], ['null-code', { code: null }],
  ['boolean-code', { code: false }]
]
for (const side of ['miniapp-delivery', 'miniapp-user']) {
  for (const phase of ['first', 'retry']) {
    for (const [label, value] of malformed) {
      test(side + ' ' + phase + ' rejects delayed malformed ' + label, async () => {
        const t = setup(side), state = observed(t.request.post('/probe/purchase', body()))
        const call = phase === 'retry' ? await retried(t, state) : t.sent[0]
        rejected(state, await success(call, envelope(value)))
      })
    }
    for (const code of [0, 200]) test(side + ' ' + phase + ' keeps success code ' + code + ' with optional message', async () => {
      const t = setup(side), state = observed(t.request.post('/probe/purchase', body()))
      const call = phase === 'retry' ? await retried(t, state) : t.sent[0]
      const data = { code, data: null }
      record(state, await success(call, envelope(data)))
      assert.strictEqual(state.value, data)
    })
    for (const message of ['业务拒绝，请核实原购买', undefined, '']) test(side + ' ' + phase + ' rejects business code1 message=' + String(message), async () => {
      const t = setup(side), state = observed(t.request.post('/probe/purchase', body()))
      const call = phase === 'retry' ? await retried(t, state) : t.sent[0]
      rejected(state, await success(call, envelope({ code: 1, message })))
      if (message) assert.strictEqual(state.error.message, message)
    })
    for (const [code, message] of [[0, {}], [200, null], [1, {}], [500, {}], [1, null], [500, 42], [1, '   ']]) {
      test(side + ' ' + phase + ' message metadata compatibility code=' + code + ' message=' + JSON.stringify(message), async () => {
        const t = setup(side), state = observed(t.request.post('/probe/purchase', body()))
        const call = phase === 'retry' ? await retried(t, state) : t.sent[0]
        const thrown = await success(call, envelope({ code, message, data: { present: true } }))
        if (code === 0 || code === 200) { record(state, thrown); assert(state.value.data.present) }
        else { rejected(state, thrown); assert.strictEqual(state.error.message, '请求失败') }
      })
    }
    test(side + ' ' + phase + ' network timeout is readable', async () => {
      const t = setup(side), state = observed(t.request.post('/probe/purchase', body()))
      const call = phase === 'retry' ? await retried(t, state) : t.sent[0]
      rejected(state, await failure(call, { errMsg: 'request:fail time out' }))
    })
    test(side + ' ' + phase + ' HTTP503 with no body settles', async () => {
      const t = setup(side), state = observed(t.request.post('/probe/purchase', body()))
      const call = phase === 'retry' ? await retried(t, state) : t.sent[0]
      rejected(state, await success(call, { statusCode: 503, data: undefined }))
    })
  }
  for (const [label, value] of malformed) test(side + ' refresh rejects delayed malformed ' + label, async () => {
    const t = setup(side), state = observed(t.request.get('/probe/read'))
    assert.strictEqual(await success(t.sent[0], { statusCode: 401 }), null)
    rejected(state, await success(t.sent[1], envelope(value)))
    assert.strictEqual(t.sent.length, 2, 'bad refresh must not replay original')
  })
  const badTokens = [undefined, null, [], 'broken', {}, { accessToken: 42 }, { accessToken: {} },
    { accessToken: ' ' }, { accessToken: 'access-next', refreshToken: {} }]
  for (let i = 0; i < badTokens.length; i++) test(side + ' refresh rejects malformed token payload ' + i, async () => {
    const t = setup(side), state = observed(t.request.get('/probe/read'))
    assert.strictEqual(await success(t.sent[0], { statusCode: 401 }), null)
    rejected(state, await success(t.sent[1], envelope({ code: 0, data: badTokens[i] })))
    assert([null, 'access-old'].includes(t.app.globalData.accessToken), 'invalid credential must not be installed')
    assert.strictEqual(t.sent.length, 2)
  })
  test(side + ' refresh keeps optional refresh token fallback', async () => {
    const t = setup(side), state = observed(t.request.post('/probe/purchase', body()))
    assert.strictEqual(await success(t.sent[0], { statusCode: 401 }), null)
    assert.strictEqual(await success(t.sent[1], envelope({ code: 0, data: { accessToken: 'access-next' } })), null)
    assert.strictEqual(t.sent.length, 3)
    assert.strictEqual(t.app.globalData.refreshToken, 'refresh-old')
    record(state, await success(t.sent[2], envelope({ code: 0, data: {} })))
  })
  test(side + ' repeated HTTP401 with no body rejects without another refresh', async () => {
    const t = setup(side), state = observed(t.request.post('/probe/purchase', body()))
    const call = await retried(t, state)
    rejected(state, await success(call, { statusCode: 401 }))
    assert.strictEqual(t.sent.length, 3)
  })
  test(side + ' HTTP200 code401 still refreshes and preserves body', async () => {
    const t = setup(side), state = observed(t.request.post('/probe/purchase', body()))
    assert.strictEqual(await success(t.sent[0], envelope({ code: 401 })), null)
    assert.strictEqual(await success(t.sent[1], renewed()), null)
    assert.deepStrictEqual(t.sent[2].data, body())
    record(state, await success(t.sent[2], envelope({ code: 200, data: {} })))
  })
  test(side + ' actual API exposes readable delayed malformed rejection', async () => {
    const t = setup(side)
    const state = observed(side === 'miniapp-user' ? t.api.purchaseTicket(body()) : t.api.getDeliveryHistory())
    rejected(state, await success(t.sent[0], envelope(null)))
  })
}
test('employee malformed refresh rejects every queued request and permits next cycle', async () => {
  const t = setup('miniapp-delivery'), a = observed(t.request.get('/probe/a')), b = observed(t.request.get('/probe/b'))
  await success(t.sent[0], { statusCode: 401 }); await success(t.sent[1], { statusCode: 401 })
  assert.strictEqual(t.sent.length, 3)
  const thrown = await success(t.sent[2], envelope({ code: 0, data: undefined }))
  rejected(a, thrown); rejected(b, thrown)
  t.app.globalData.accessToken = 'access-new'; t.wx.setStorageSync(t.K.REFRESH_TOKEN, 'refresh-new')
  const c = observed(t.request.get('/probe/c'))
  await success(t.sent[3], { statusCode: 401 })
  assert.strictEqual(t.sent.length, 5, 'complete clears refreshing marker for next cycle')
  await success(t.sent[4], renewed()); record(c, await success(t.sent[5], envelope({ code: 0 })))
})
test('customer stale first malformed response cannot touch a new login', async () => {
  const t = setup('miniapp-user'), state = observed(t.request.get('/probe/read'))
  t.app.setLoginInfo('access-B', 'refresh-B', { customerId: 8 }); t.wx.setStorageSync(t.K.CUSTOMER_ID, 8)
  rejected(state, await success(t.sent[0], envelope(null)))
  assert(state.error.sessionChanged); assert.strictEqual(t.app.globalData.accessToken, 'access-B')
  assert.strictEqual(t.sent.length, 1); assert.strictEqual(t.wx.__calls.nav.length, 0)
})
test('customer stale malformed refresh cannot clear new same-session credentials and replays original body', async () => {
  const t = setup('miniapp-user'), original = body(), state = observed(t.request.post('/probe/purchase', original))
  original.quantity = 99
  await success(t.sent[0], { statusCode: 401 })
  const token = require('../../miniapp-user/utils/token')
  assert(token.acceptRefreshedTokens(token.captureSession(), 'access-new', 'refresh-new'))
  assert.strictEqual(await success(t.sent[1], envelope(null)), null)
  assert.strictEqual(t.sent.length, 3)
  assert.deepStrictEqual(t.sent[2].data, body()); assert.strictEqual(t.sent[2].header.Authorization, 'Bearer access-new')
  record(state, await success(t.sent[2], envelope({ code: 0, data: {} })))
  assert.strictEqual(t.app.globalData.refreshToken, 'refresh-new'); assert.strictEqual(t.wx.__calls.nav.length, 0)
})
async function main() {
  const done = armWatchdog(120000)
  let passed = 0
  for (const t of tests) {
    currentName = t.name
    try { await t.run(); passed++; results.push({ name: t.name, passed: true }) }
    catch (error) { results.push({ name: t.name, passed: false, error: error.stack }); console.error('FAIL ' + t.name + '\n' + error.stack) }
  }
  done()
  if (process.env.AQUAFLOW_ENVELOPE_EVIDENCE_DIR) {
    const dir = path.resolve(process.env.AQUAFLOW_ENVELOPE_EVIDENCE_DIR)
    fs.mkdirSync(dir, { recursive: true })
    fs.writeFileSync(path.join(dir, 'results.json'), JSON.stringify({ sourceRoot, total: tests.length, passed, failed: tests.length - passed, results, observations }, null, 2) + '\n')
  }
  if (passed !== tests.length) { console.error('AQUAFLOW_ENVELOPE_FAILED ' + (tests.length - passed)); process.exitCode = 1 }
  else console.log('AQUAFLOW_SUITE_OK ' + passed)
}
main().catch(error => { console.error(error.stack); process.exitCode = 1 })
