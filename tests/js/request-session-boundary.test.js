/** 执行真实 App/token/request；仅 wx transport 为替身。不连 HTTP/微信。 */
const assert = require('assert')
const path = require('path')
const { createWx, loadPage, armWatchdog } = require('./harness')
const ROOT = path.resolve(__dirname, '../..')
let passed = 0
const tests = []
const test = (name, run) => tests.push({ name, run })
const flush = async () => { for (let i = 0; i < 6; i++) await Promise.resolve() }
function setup() {
  const wx = createWx(), sent = []
  wx.request = o => sent.push(o)
  global.wx = wx
  global.__wxConfig = { envVersion: 'develop' }
  let app
  global.App = cfg => { app = cfg }
  global.getApp = () => app
  global.getCurrentPages = () => [{}]
  for (const f of ['miniapp-user/app.js', 'miniapp-user/utils/token.js', 'miniapp-user/utils/request.js', 'miniapp-user/api/ticket.js']) {
    delete require.cache[require.resolve(path.join(ROOT, f))]
  }
  require(path.join(ROOT, 'miniapp-user/app.js'))
  const { STORAGE_KEYS: K } = require(path.join(ROOT, 'miniapp-user/utils/storage-keys'))
  const request = require(path.join(ROOT, 'miniapp-user/utils/request'))
  const login = (id = 7, suffix = 'A') => {
    app.setLoginInfo('access-' + suffix, 'refresh-' + suffix, { customerId: id })
    wx.setStorageSync(K.CUSTOMER_ID, id)
  }
  login()
  return { wx, sent, app, request, login, K }
}
function success(o, data = {}) { o.success({ statusCode: 200, data: { code: 0, data } }) }
function expired(o) { o.success({ statusCode: 401, data: { code: 401 } }) }
function renewal(o, suffix = 'A2') { success(o, { accessToken: 'access-' + suffix, refreshToken: 'refresh-' + suffix }) }
function observed(promise) { return promise.then(value => ({ value }), error => ({ error })) }
function ticketPage(t) {
  t.wx.setStorageSync('selectedStation', { id: 1, name: 'Station' })
  const page = loadPage('miniapp-user/pages/ticket/index.js', { wx: t.wx, app: t.app, stubs: {
    'api/barrel': { getBarrelSummaryByType: async () => ({ code: 0, data: [] }) },
    'api/product': { getStationProducts: async () => ({ code: 0, data: [] }) },
    'api/station': { getPublicStations: async () => ({ code: 0, data: [] }) }
  } })
  page.data.currentStationId = 1
  Object.assign(page.data.buyForm, { productId: 5, productName: 'Water', quantity: 3, faceValue: 8 })
  return page
}

test('真实购票页和api封装：跨客户迟到401不重发，A凭据保留且B无旧意图', async () => {
  const t = setup(), page = ticketPage(t), pending = page.onBuySubmit()
  await flush(); assert.strictEqual(t.sent.length, 1)
  const original = t.sent[0].data.idempotencyKey
  t.login(8, 'B'); expired(t.sent[0]); await pending
  assert.strictEqual(t.sent.length, 1)
  assert.strictEqual(t.wx.getStorageSync(t.K.TICKET_PURCHASE_INTENT + '7').body.idempotencyKey, original)
  assert.strictEqual(t.wx.getStorageSync(t.K.TICKET_PURCHASE_INTENT + '8'), '')
})
test('真实购票页原款查询：A到B到A后旧已付响应不清原凭据', async () => {
  const t = setup(), page = ticketPage(t)
  const saved = { customerId: 7, body: { stationId: 1, productId: 5, quantity: 3, paymentMethod: 1, packageId: null, unifiedQty: null, idempotencyKey: 'original' } }
  t.wx.setStorageSync(t.K.TICKET_PURCHASE_INTENT + '7', saved)
  const pending = page.onQueryPurchaseResult(); t.login(8, 'B'); t.login(7, 'A')
  success(t.sent[0], { ...saved.body, paymentId: 900, status: 2, amount: 24 }); await pending
  assert.strictEqual(t.wx.getStorageSync(t.K.TICKET_PURCHASE_INTENT + '7').body.idempotencyKey, 'original')
  assert(!t.wx.__calls.toast.some(o => o.title === '购买成功，水票已到账'))
})
test('真实购票页终结：同客户退出重登后旧关闭回执不清原记录', async () => {
  const t = setup(), page = ticketPage(t)
  const saved = { customerId: 7, body: { stationId: 1, productId: 5, quantity: 3, paymentMethod: 1, packageId: null, unifiedQty: null, idempotencyKey: 'original' } }
  t.wx.setStorageSync(t.K.TICKET_PURCHASE_INTENT + '7', saved)
  const pending = page.closeOriginalPurchase(saved); t.app.clearLoginInfo(); t.login()
  success(t.sent[0], { closed: true, idempotencyKey: 'original' }); await pending
  assert.strictEqual(t.wx.getStorageSync(t.K.TICKET_PURCHASE_INTENT + '7').body.idempotencyKey, 'original')
})

test('迟到401跨客户不得刷新或重发旧购票', async () => {
  const t = setup()
  const result = observed(t.request.post('/tickets/purchase', { idempotencyKey: 'original-A', quantity: 3 }))
  t.login(8, 'B'); expired(t.sent[0])
  assert((await result).error.sessionChanged)
  assert.strictEqual(t.sent.length, 1)
  assert.strictEqual(t.app.globalData.accessToken, 'access-B')
})
for (const kind of ['success', 'business failure', 'network failure']) {
  test('旧刷新' + kind + '不得覆盖或清理新客户', async () => {
    const t = setup(), result = observed(t.request.post('/tickets/purchase', { key: 'A' }))
    expired(t.sent[0]); t.login(8, 'B')
    if (kind === 'success') renewal(t.sent[1])
    else if (kind === 'business failure') t.sent[1].success({ statusCode: 200, data: { code: 1 } })
    else t.sent[1].fail({ errMsg: 'request:fail' })
    assert((await result).error.sessionChanged)
    assert.strictEqual(t.sent.length, 2)
    assert.strictEqual(t.app.globalData.accessToken, 'access-B')
    assert.strictEqual(t.wx.getStorageSync(t.K.ACCESS_TOKEN), 'access-B')
    assert.strictEqual(t.wx.__calls.nav.length, 0)
  })
}
test('同客户退出重登且令牌相同，旧401也不能刷新', async () => {
  const t = setup(), result = observed(t.request.get('/tickets'))
  t.app.clearLoginInfo(); t.login(7, 'A'); expired(t.sent[0])
  assert((await result).error.sessionChanged)
  assert.strictEqual(t.sent.length, 1)
})
test('同客户重登，旧刷新不得写回', async () => {
  const t = setup(), result = observed(t.request.get('/tickets'))
  expired(t.sent[0]); t.app.clearLoginInfo(); t.login(7, 'A'); renewal(t.sent[1])
  assert((await result).error.sessionChanged)
  assert.strictEqual(t.app.globalData.accessToken, 'access-A')
})
test('退出后旧成功结果不能应用', async () => {
  const t = setup(), result = observed(t.request.get('/tickets'))
  t.app.clearLoginInfo(); success(t.sent[0], { paymentId: 12 })
  assert((await result).error.sessionChanged)
  assert.strictEqual(t.app.globalData.accessToken, null)
})
test('同周期401队列只刷新一次，原请求各重发一次', async () => {
  const t = setup()
  const body = { idempotencyKey: 'original-A', quantity: 3 }
  const a = t.request.post('/tickets/purchase', body), b = t.request.get('/tickets')
  body.quantity = 99
  expired(t.sent[0]); expired(t.sent[1])
  assert.strictEqual(t.sent.length, 3)
  assert.strictEqual(t.sent[2].data.refreshToken, 'refresh-A')
  renewal(t.sent[2]); await flush()
  assert.strictEqual(t.sent.length, 5)
  assert.strictEqual(t.sent[3].data.quantity, 3)
  assert.strictEqual(t.sent[3].header.Authorization, 'Bearer access-A2')
  success(t.sent[3]); success(t.sent[4]); await Promise.all([a, b])
})
test('旧队列不得与新客户刷新队列混用', async () => {
  const t = setup(), a = observed(t.request.get('/tickets')), b = observed(t.request.get('/ticket-records'))
  expired(t.sent[0]); expired(t.sent[1]); t.login(8, 'B')
  const c = t.request.get('/tickets'); expired(t.sent[3])
  assert.strictEqual(t.sent[4].data.refreshToken, 'refresh-B')
  renewal(t.sent[2], 'late-A'); renewal(t.sent[4], 'B2'); await flush()
  assert((await a).error.sessionChanged); assert((await b).error.sessionChanged)
  assert.strictEqual(t.sent.length, 6)
  assert.strictEqual(t.sent[5].header.Authorization, 'Bearer access-B2')
  success(t.sent[5]); await c
})
test('正常续期后的迟到旧401复用新令牌，最多重试一次', async () => {
  const t = setup(), a = t.request.get('/tickets'), b = observed(t.request.get('/ticket-records'))
  expired(t.sent[0]); renewal(t.sent[2]); await flush()
  success(t.sent[3]); await a
  expired(t.sent[1]); await flush(); assert.strictEqual(t.sent.length, 5)
  assert.strictEqual(t.sent[4].header.Authorization, 'Bearer access-A2')
  expired(t.sent[4]); assert((await b).error)
  assert.strictEqual(t.sent.length, 5)
})
for (const kind of ['success', 'business failure', 'network failure']) {
  test('启动校验旧刷新' + kind + '晚到不影响新登录', async () => {
    const t = setup(); t.app.validateToken(); expired(t.sent[0])
    t.login(8, 'B')
    if (kind === 'success') renewal(t.sent[1])
    else if (kind === 'business failure') t.sent[1].success({ statusCode: 200, data: { code: 1 } })
    else t.sent[1].fail({ errMsg: 'request:fail' })
    await flush()
    assert.strictEqual(t.app.globalData.accessToken, 'access-B')
    assert.strictEqual(t.app.globalData.isLogin, true)
    assert.strictEqual(t.wx.__calls.nav.length, 0)
  })
}
test('启动校验旧ME的401晚到不得使用新客户刷新凭据', async () => {
  const t = setup(); t.app.validateToken(); t.login(8, 'B'); expired(t.sent[0]); await flush()
  assert.strictEqual(t.sent.length, 1)
})
for (const kind of ['success', 'failure']) {
  test('启动旧ME' + kind + '晚到不能覆盖或清掉新资料', async () => {
    const t = setup(); t.app.validateToken(); t.login(8, 'B')
    if (kind === 'success') success(t.sent[0], { customerId: 7, name: 'old-A' })
    else t.sent[0].success({ statusCode: 200, data: { code: 1 } })
    await flush()
    assert.strictEqual(t.app.globalData.userInfo.customerId, 8)
    assert.strictEqual(t.app.globalData.accessToken, 'access-B')
    assert.strictEqual(t.wx.__calls.nav.length, 0)
  })
}
test('A到B到A新周期也拒绝旧A刷新与旧队列', async () => {
  const t = setup(), a = observed(t.request.get('/tickets')), b = observed(t.request.get('/ticket-records'))
  expired(t.sent[0]); expired(t.sent[1]); t.login(8, 'B'); t.login(7, 'A'); renewal(t.sent[2]); await flush()
  assert((await a).error.sessionChanged); assert((await b).error.sessionChanged)
  assert.strictEqual(t.app.globalData.accessToken, 'access-A')
  assert.strictEqual(t.sent.length, 3)
})
test('启动校验同客户退出重登相同令牌也隔离', async () => {
  const t = setup(); t.app.validateToken(); expired(t.sent[0]); t.app.clearLoginInfo(); t.login(); renewal(t.sent[1]); await flush()
  assert.strictEqual(t.app.globalData.accessToken, 'access-A')
  assert.strictEqual(t.wx.__calls.nav.length, 0)
})
test('启动刷新期间退出，晚到成功不得复活会话', async () => {
  const t = setup(); t.app.validateToken(); expired(t.sent[0]); t.app.clearLoginInfo(); renewal(t.sent[1]); await flush()
  assert.strictEqual(t.app.globalData.accessToken, null)
  assert.strictEqual(t.app.globalData.isLogin, false)
})
test('正常同周期启动刷新仍成功', async () => {
  const t = setup(); t.app.validateToken(); expired(t.sent[0]); renewal(t.sent[1]); await flush()
  assert.strictEqual(t.app.globalData.accessToken, 'access-A2')
  assert.strictEqual(t.wx.getStorageSync(t.K.REFRESH_TOKEN), 'refresh-A2')
  assert.strictEqual(t.app.globalData.isLogin, true)
})
test('续期后重发途中换客户，返回结果不能应用到新客户', async () => {
  const t = setup(), result = observed(t.request.post('/tickets/purchase', { key: 'A' }))
  expired(t.sent[0]); renewal(t.sent[1]); await flush(); t.login(8, 'B'); success(t.sent[2])
  assert((await result).error.sessionChanged)
})
test('旧错误上报确认弹窗不得在新客户名下提交', async () => {
  const t = setup(); t.wx.showModal = o => t.wx.__calls.modal.push(o)
  const result = observed(t.request.get('/tickets'))
  t.sent[0].success({ statusCode: 200, data: { code: 500, message: 'fake system error' } })
  await result; t.login(8, 'B'); t.wx.__calls.modal[0].success({ confirm: true })
  assert.strictEqual(t.sent.length, 1)
})

async function reportPrompt(t, message = 'synthetic report error') {
  t.wx.showModal = o => t.wx.__calls.modal.push(o)
  const result = observed(t.request.get('/tickets'))
  t.sent[t.sent.length - 1].success({ statusCode: 200, data: { code: 500, message } })
  await result
}
for (const outcome of [
  { name: '业务拒绝', response: { statusCode: 200, data: { code: 1, message: '请重新登录后上报' } }, feedback: '请重新登录后上报' },
  { name: 'HTTP401', response: { statusCode: 401, data: null }, feedback: '登录已过期' },
  { name: 'HTTP500', response: { statusCode: 500, data: { code: 0 } }, feedback: 'HTTP 500' },
  { name: '业务500', response: { statusCode: 200, data: { code: 500, message: '水站暂时不可用' } }, feedback: '水站暂时不可用' },
  { name: '空响应', response: null, feedback: '数据不完整' },
  { name: '空body', response: { statusCode: 200, data: null }, feedback: '数据不完整' },
  { name: '网络超时', network: { errMsg: 'request:fail time out' }, feedback: '网络超时' }
]) {
  test('报障' + outcome.name + '准确反馈且同会话可再次明确确认上报，不递归', async () => {
    const t = setup(); await reportPrompt(t)
    t.wx.__calls.modal[0].success({ confirm: true })
    const report = t.sent[1]
    assert(report.url.endsWith('/feedback')); assert.strictEqual(report.header.Authorization, 'Bearer access-A')
    if (outcome.network) report.fail(outcome.network)
    else report.success(outcome.response)
    assert(!t.wx.__calls.toast.some(o => o.title.includes('已上报')))
    assert(t.wx.__calls.toast.some(o => o.title.includes(outcome.feedback)))
    assert.strictEqual(t.wx.__calls.modal.length, 1, '上报失败自身不能递归弹窗')
    await reportPrompt(t); assert.strictEqual(t.wx.__calls.modal.length, 2)
    assert.strictEqual(t.sent.length, 3, '失败后不得自动发送上报')
    t.wx.__calls.modal[1].success({ confirm: true }); assert.strictEqual(t.sent.length, 4)
    success(t.sent[3]); assert(t.wx.__calls.toast.some(o => o.title.includes('已上报')))
  })
}
for (const code of [0, 200]) {
  test('报障有效HTTP200/code' + code + '才完成，等待和完成期间同错误并发去重', async () => {
    const t = setup(); await reportPrompt(t); await reportPrompt(t)
    assert.strictEqual(t.wx.__calls.modal.length, 1)
    t.wx.__calls.modal[0].success({ confirm: true }); await reportPrompt(t)
    assert.strictEqual(t.wx.__calls.modal.length, 1)
    t.sent[2].success({ statusCode: 200, data: { code } })
    assert.strictEqual(t.wx.__calls.toast.filter(o => o.title.includes('已上报')).length, 1)
    await reportPrompt(t); assert.strictEqual(t.wx.__calls.modal.length, 1)
    assert.strictEqual(t.sent.filter(o => o.url.endsWith('/feedback')).length, 1)
  })
}
for (const outcome of ['success', 'business failure', 'network failure']) {
  test('报障提交后换会话，旧' + outcome + '不提示、不释放新会话去重；新客户可独立上报', async () => {
    const t = setup(); await reportPrompt(t); t.wx.__calls.modal[0].success({ confirm: true })
    const oldReport = t.sent[1]
    t.login(8, 'B'); await reportPrompt(t); assert.strictEqual(t.wx.__calls.modal.length, 2)
    t.wx.__calls.modal[1].success({ confirm: true })
    assert.strictEqual(t.sent[3].header.Authorization, 'Bearer access-B')
    if (outcome === 'success') success(oldReport)
    else if (outcome === 'business failure') oldReport.success({ statusCode: 200, data: { code: 1 } })
    else oldReport.fail({ errMsg: 'request:fail' })
    assert.strictEqual(t.wx.__calls.toast.length, 0)
    await reportPrompt(t); assert.strictEqual(t.wx.__calls.modal.length, 2)
    success(t.sent[3]); assert.strictEqual(t.wx.__calls.toast.length, 1)
  })
}
test('续期失败且存储删除抛错，队列仍逐个settle且不复用旧令牌', async () => {
  const t = setup(), a = observed(t.request.get('/tickets')), b = observed(t.request.get('/ticket-records'))
  expired(t.sent[0]); expired(t.sent[1]); t.wx.removeStorageSync = () => { throw new Error('storage failure') }
  t.sent[2].fail({ errMsg: 'request:fail' })
  assert((await a).error); assert((await b).error)
  assert.strictEqual(t.app.globalData.isLogin, false)
  const loggedOut = observed(t.request.get('/tickets'))
  assert.strictEqual(t.sent[3].header.Authorization, undefined)
  success(t.sent[3]); await loggedOut
})

test('真实onLaunch旧ME的401晚于页面成功续期，不重送旧refresh或退出登录', async () => {
  const t = setup(); t.app.onLaunch()
  const result = observed(t.request.get('/tickets'))
  expired(t.sent[1]); renewal(t.sent[2]); await flush()
  success(t.sent[3], []); assert((await result).value)
  expired(t.sent[0]); await flush()
  assert.strictEqual(t.sent.length, 4)
  assert.strictEqual(t.app.globalData.accessToken, 'access-A2')
  assert.strictEqual(t.wx.getStorageSync(t.K.REFRESH_TOKEN), 'refresh-A2')
  assert.strictEqual(t.app.globalData.isLogin, true)
  assert.strictEqual(t.wx.__calls.nav.length, 0)
})

for (const first of ['startup', 'page']) {
  for (const outcome of ['success', 'business failure', 'network failure']) {
    test('真实启动与页面同时401，' + first + '先发、' + outcome + '只共用一次刷新且队列结束', async () => {
      const t = setup(); t.app.onLaunch()
      let settled = 0
      const body = { idempotencyKey: 'original-A', quantity: 3 }
      const a = observed(t.request.post('/tickets/purchase', body)).then(r => { settled++; return r })
      const b = observed(t.request.get('/ticket-records')).then(r => { settled++; return r })
      body.quantity = 99
      expired(t.sent[first === 'startup' ? 0 : 1])
      expired(t.sent[first === 'startup' ? 1 : 0]); expired(t.sent[2])
      assert.strictEqual(t.sent.length, 4)
      assert.strictEqual(t.sent[3].data.refreshToken, 'refresh-A')
      if (outcome === 'success') {
        renewal(t.sent[3]); await flush()
        assert.strictEqual(t.sent.length, 6)
        assert.strictEqual(t.sent[4].data.idempotencyKey, 'original-A')
        assert.strictEqual(t.sent[4].data.quantity, 3)
        assert.strictEqual(t.sent[4].header.Authorization, 'Bearer access-A2')
        assert.strictEqual(t.sent[5].header.Authorization, 'Bearer access-A2')
        success(t.sent[4]); success(t.sent[5])
        assert((await a).value); assert((await b).value)
        assert.strictEqual(t.app.globalData.isLogin, true)
        assert.strictEqual(t.wx.getStorageSync(t.K.ACCESS_TOKEN), 'access-A2')
        assert.strictEqual(t.wx.__calls.nav.length, 0)
      } else {
        if (outcome === 'business failure') t.sent[3].success({ statusCode: 200, data: { code: 1 } })
        else t.sent[3].fail({ errMsg: 'request:fail timeout' })
        assert((await a).error); assert((await b).error); await flush()
        assert.strictEqual(t.sent.length, 4)
        assert.strictEqual(t.app.globalData.isLogin, false)
        assert.strictEqual(t.wx.__calls.nav.length, 1)
      }
      assert.strictEqual(settled, 2)
      assert.strictEqual(t.sent.filter(o => o.url.endsWith('/auth/refresh')).length, 1)
    })
  }
}

for (const pair of [
  ['access-A2', 'refresh-A2'],
  ['access-A', 'refresh-A2'],
  ['access-A', 'refresh-A']
]) {
  for (const outcome of ['success', 'business failure', 'network failure']) {
    test('同周期后取凭据' + pair.join('/') + '后，旧刷新' + outcome + '不覆盖不清理', async () => {
      const t = setup(); t.app.onLaunch()
      const result = observed(t.request.post('/tickets/purchase', { idempotencyKey: 'original-A', quantity: 3 }))
      expired(t.sent[0]); expired(t.sent[1]); assert.strictEqual(t.sent.length, 3)
      const token = require(path.join(ROOT, 'miniapp-user/utils/token'))
      const old = token.captureSession()
      assert(token.acceptRefreshedTokens(old, pair[0], pair[1]))
      assert.strictEqual(token.captureSession().epoch, old.epoch)
      assert.strictEqual(token.isCurrentSession(old), true)
      assert.strictEqual(token.isCurrentCredentials(old), false)
      if (outcome === 'success') renewal(t.sent[2], 'late-old-result')
      else if (outcome === 'business failure') t.sent[2].success({ statusCode: 200, data: { code: 1 } })
      else t.sent[2].fail({ errMsg: 'request:fail timeout' })
      await flush()
      assert.strictEqual(t.sent.length, 4)
      assert.strictEqual(t.sent[3].header.Authorization, 'Bearer ' + pair[0])
      assert.strictEqual(t.sent[3].data.idempotencyKey, 'original-A')
      assert.strictEqual(t.app.globalData.accessToken, pair[0])
      assert.strictEqual(t.wx.getStorageSync(t.K.REFRESH_TOKEN), pair[1])
      assert.strictEqual(t.app.globalData.isLogin, true)
      assert.strictEqual(t.wx.__calls.nav.length, 0)
      assert.strictEqual(token.acceptRefreshedTokens(old, 'stale-access', 'stale-refresh'), false)
      success(t.sent[3]); assert((await result).value)
    })
  }
}

for (const outcome of ['success', 'business failure']) {
  test('同周期后取凭据后，旧启动ME' + outcome + '不覆盖当前资料或清理登录', async () => {
    const t = setup(); t.app.onLaunch()
    const token = require(path.join(ROOT, 'miniapp-user/utils/token'))
    assert(token.acceptRefreshedTokens(token.captureSession(), 'access-A2', 'refresh-A2'))
    t.app.globalData.userInfo = { customerId: 7, name: 'current-A' }
    if (outcome === 'success') success(t.sent[0], { customerId: 7, name: 'old-A' })
    else t.sent[0].success({ statusCode: 200, data: { code: 1 } })
    await flush()
    assert.strictEqual(t.sent.length, 1)
    assert.strictEqual(t.app.globalData.userInfo.name, 'current-A')
    assert.strictEqual(t.app.globalData.accessToken, 'access-A2')
    assert.strictEqual(t.wx.__calls.nav.length, 0)
  })
}

test('tryRefresh捕获旧凭据后再调用时复用同周期新令牌，不发送旧刷新', async () => {
  const t = setup(), token = require(path.join(ROOT, 'miniapp-user/utils/token'))
  const old = token.captureSession()
  assert(token.acceptRefreshedTokens(old, 'access-A2', 'refresh-A2'))
  await t.app.tryRefresh(old)
  assert.strictEqual(t.sent.length, 0)
  assert.strictEqual(t.app.globalData.accessToken, 'access-A2')
})

test('真实购票重发结果未知时，启动旧401不清购买意图或重发新编号', async () => {
  const t = setup(); t.app.onLaunch()
  const page = ticketPage(t), pending = page.onBuySubmit()
  await flush()
  const original = t.sent[1].data.idempotencyKey
  expired(t.sent[1]); renewal(t.sent[2]); await flush()
  expired(t.sent[0]); await flush()
  assert.strictEqual(t.sent.length, 4)
  assert.strictEqual(t.sent[3].data.idempotencyKey, original)
  t.sent[3].fail({ errMsg: 'request:fail timeout' }); await pending
  assert.strictEqual(t.wx.getStorageSync(t.K.TICKET_PURCHASE_INTENT + '7').body.idempotencyKey, original)
  assert.strictEqual(t.app.globalData.accessToken, 'access-A2')
  assert.strictEqual(t.app.globalData.isLogin, true)
  assert.strictEqual(t.wx.__calls.nav.length, 0)
})

test('旧凭据刷新结束不移除新版本在途刷新，等待者各只重发一次', async () => {
  const t = setup(), token = require(path.join(ROOT, 'miniapp-user/utils/token'))
  const a = observed(t.request.get('/tickets'))
  expired(t.sent[0])
  assert(token.acceptRefreshedTokens(token.captureSession(), 'access-A2', 'refresh-A2'))
  const b = observed(t.request.get('/ticket-records')); expired(t.sent[2])
  assert.strictEqual(t.sent[3].data.refreshToken, 'refresh-A2')
  t.sent[1].fail({ errMsg: 'request:fail timeout' }); await flush()
  const c = observed(t.request.get('/tickets')); expired(t.sent[5])
  assert.strictEqual(t.sent.length, 6)
  renewal(t.sent[3], 'A3'); await flush()
  assert.strictEqual(t.sent.length, 8)
  assert.strictEqual(t.sent[4].header.Authorization, 'Bearer access-A2')
  assert.strictEqual(t.sent[6].header.Authorization, 'Bearer access-A3')
  assert.strictEqual(t.sent[7].header.Authorization, 'Bearer access-A3')
  success(t.sent[4]); success(t.sent[6]); success(t.sent[7])
  assert((await a).value); assert((await b).value); assert((await c).value)
  assert.strictEqual(t.sent.filter(o => o.url.endsWith('/auth/refresh')).length, 2)
  assert.strictEqual(t.app.globalData.accessToken, 'access-A3')
  assert.strictEqual(t.wx.__calls.nav.length, 0)
})

test('启动与页面共用刷新失败且删除存储抛错，全部等待者仍结束', async () => {
  const t = setup(); t.app.onLaunch()
  const a = observed(t.request.get('/tickets')), b = observed(t.request.get('/ticket-records'))
  expired(t.sent[0]); expired(t.sent[1]); expired(t.sent[2])
  t.wx.removeStorageSync = () => { throw new Error('storage failure') }
  t.sent[3].fail({ errMsg: 'request:fail timeout' })
  assert((await a).error); assert((await b).error); await flush()
  assert.strictEqual(t.sent.length, 4)
  assert.strictEqual(t.app.globalData.isLogin, false)
  assert.strictEqual(t.wx.__calls.nav.length, 1)
})
;(async () => {
  const done = armWatchdog(30000)
  for (const t of tests) {
    await t.run(); passed++; console.log('PASS ' + t.name)
  }
  done(); console.log('AQUAFLOW_SUITE_OK ' + passed)
})().catch(error => { console.error(error); process.exitCode = 1 })
