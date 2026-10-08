// 2026-10-06：真实入口重入 + 原生回调竞态。微信请求不可取消，旧回调不得释放新尝试。
const assert = require('assert')
const fs = require('fs')
const path = require('path')
const { loadPage, createWx, createApp, ROOT } = require('./harness')
const helper = path.join(ROOT, 'miniapp-delivery/utils/navigation.js')
let passed = 0, failed = 0
async function test(name, fn) {
  try { await fn(); passed++; console.log('PASS ' + name) }
  catch (e) { failed++; console.log('FAIL ' + name + ': ' + e.message) }
}
function fixture() {
  let pages = [{ route: 'pages/station-mgmt/index' }], session = 'manager:1', now = 0
  const calls = [], messages = [], timers = new Map(), cachedTabs = new Set(['pages/coordination/index'])
  let timerId = 0, homeCount = 0, repairs = 0, allowed = true
  const wx = {}
  for (const api of ['navigateTo', 'redirectTo', 'reLaunch', 'switchTab', 'navigateBack']) {
    wx[api] = o => calls.push({ api, o })
  }
  const env = { wx, pages: () => pages, session: () => session, allowed: () => allowed,
    setTimer(fn, ms) { const id = ++timerId; timers.set(id, { at: now + ms, fn }); return id },
    clearTimer(id) { timers.delete(id) }, feedback: o => messages.push(o),
    home: () => homeCount++, repairGuard: () => repairs++ }
  function ok(i = calls.length - 1, complete = true) {
    const { api, o } = calls[i]
    const route = o.url && o.url.split('?')[0].slice(1)
    if (api === 'reLaunch') { pages = [{ route }]; cachedTabs.clear() }
    else if (api === 'switchTab') { cachedTabs.add(route); pages = [{ route }] }
    else if (api === 'navigateTo') pages.push({ route })
    else if (api === 'redirectTo') pages[pages.length - 1] = { route }
    else if (api === 'navigateBack') pages.splice(pages.length - o.delta)
    const res = { errMsg: api + ':ok' }
    if (o.success) o.success(res)
    if (complete && o.complete) o.complete(res)
  }
  function error(i = calls.length - 1) {
    const { api, o } = calls[i], res = { errMsg: api + ':fail timeout' }
    if (o.fail) o.fail(res)
    if (o.complete) o.complete(res)
  }
  function advance(ms) {
    now += ms
    for (const [id, t] of [...timers]) if (t.at <= now) { timers.delete(id); t.fn() }
  }
  return { env, calls, messages, cachedTabs, ok, error, advance,
    setPages: p => { pages = p }, pages: () => pages,
    setSession: s => { session = s }, setAllowed: a => { allowed = a },
    homeCount: () => homeCount, repairs: () => repairs }
}
function setup() {
  assert.ok(fs.existsSync(helper), 'navigation helper not implemented yet')
  const f = fixture()
  f.nav = require(helper).createNavigator(f.env)
  return f
}
const ticket = '/pages/station-mgmt/ticket-packages/index?productId=3'
function realApp(wx, pages, get = async () => ({ data: {} })) {
  let app
  global.__wxConfig = { envVersion: 'develop' }
  global.wx = wx; global.getCurrentPages = () => pages; global.App = o => { app = o }
  const Module = require('module'), original = Module._load
  Module._load = function (s, p, main) {
    if (s.endsWith('utils/request')) return { get }
    return original.apply(this, arguments)
  }
  try {
    const file = path.join(ROOT, 'miniapp-delivery/app.js')
    delete require.cache[require.resolve(file)]; require(file)
  } finally { Module._load = original }
  global.getApp = () => app
  return app
}
;(async () => {
  await test('real manager menu sends one native navigation for five in-flight taps', () => {
    const wx = createWx(), raw = []; wx.navigateTo = o => raw.push(o)
    const page = loadPage('miniapp-delivery/pages/station-mgmt/index.js', { wx, app: createApp(), stubs: { 'utils/request': { get: async () => ({ data: {} }) } } })
    for (let i = 0; i < 5; i++) page.onNavigate({ currentTarget: { dataset: { url: ticket } } })
    assert.equal(raw.length, 1)
    assert.equal(raw[0].url, ticket)
    assert.equal(typeof raw[0].fail, 'function')
    raw[0].fail({ errMsg: 'navigateTo:fail timeout' })
    assert.equal(wx.__calls.modal.length, 1, 'failure must be visible and offer recovery')
  })
  await test('tab registry matches actual app.json', () => {
    const f = setup(), config = require(path.join(ROOT, 'miniapp-delivery/app.json'))
    assert.deepEqual(require(helper).TAB_PAGES, config.tabBar.list.map(t => '/' + t.pagePath))
    f.nav.open('/pages/coordination/index'); assert.equal(f.calls[0].api, 'switchTab')
  })
  await test('same destination and competing business taps are coalesced while native call pending', () => {
    const f = setup(); f.nav.open(ticket); f.nav.open(ticket); f.nav.open('/pages/mine/index')
    assert.equal(f.calls.length, 1); f.ok(); f.nav.open('/pages/mine/index'); assert.equal(f.calls.length, 2)
  })
  await test('failure releases navigation and retries only on explicit user action', () => {
    const f = setup(); f.nav.open(ticket); f.error(); assert.equal(f.calls.length, 1)
    assert.equal(f.messages.length, 1); assert.match(f.messages[0].message, /超时/)
    f.messages[0].retry(); assert.equal(f.calls.length, 2); assert.equal(f.calls[1].o.url, ticket)
    f.error(); assert.equal(f.calls.length, 2, 'no automatic retry loop')
  })
  await test('success without complete releases navigation', () => {
    const f = setup(); f.nav.open(ticket); f.ok(0, false); f.nav.open('/pages/mine/index'); assert.equal(f.calls.length, 2)
  })
  await test('complete-only failure releases once with feedback', () => {
    const f = setup(); f.nav.open(ticket); f.calls[0].o.complete({ errMsg: 'navigateTo:fail timeout' })
    assert.equal(f.messages.length, 1); f.nav.open(ticket); assert.equal(f.calls.length, 2)
  })
  await test('absent callback times out and late callbacks cannot unlock the next attempt', () => {
    const f = setup(); f.nav.open(ticket); f.advance(12000); assert.equal(f.messages.length, 1)
    f.messages[0].retry(); assert.equal(f.calls.length, 2)
    f.calls[0].o.success({ errMsg: 'navigateTo:ok' }); f.calls[0].o.complete({ errMsg: 'navigateTo:ok' })
    f.nav.open('/pages/mine/index'); assert.equal(f.calls.length, 2)
    f.error(1); assert.equal(f.messages.length, 2)
  })
  await test('synchronous SDK throw releases and reports failure', () => {
    const f = setup(); f.env.wx.navigateTo = () => { throw Error('native bridge unavailable') }
    f.nav.open(ticket); assert.equal(f.messages.length, 1)
    f.env.wx.navigateTo = o => f.calls.push({ api: 'navigateTo', o }); f.nav.open(ticket); assert.equal(f.calls.length, 1)
  })
  await test('reset into tab clears cached pages before switchTab and coalesces login auto-route', () => {
    const f = setup(); const opts = { mode: 'reset', guard: true }
    f.nav.open('/pages/home/index', opts); assert.equal(f.calls[0].api, 'reLaunch')
    assert.equal(f.calls[0].o.url, '/pages/login/index')
    f.nav.open('/pages/home/index', opts); assert.equal(f.calls.length, 1)
    f.ok(0); assert.equal(f.calls[1].api, 'switchTab'); assert.equal(f.cachedTabs.size, 0)
    // An old first-phase complete must not release the still-pending second phase.
    f.calls[0].o.complete({ errMsg: 'reLaunch:ok' }); f.nav.open(ticket); assert.equal(f.calls.length, 2)
    f.ok(1); assert.deepEqual(f.pages().map(p => p.route), ['pages/home/index'])
    assert.deepEqual([...f.cachedTabs], ['pages/home/index'])
  })
  await test('reset non-tab removes prior business stack and same completed guard is idempotent', () => {
    const f = setup(); f.setPages([{ route: 'pages/home/index' }, { route: 'pages/station-mgmt/index' }])
    f.nav.open('/pages/login/index', { mode: 'reset', guard: true }); f.ok()
    assert.deepEqual(f.pages().map(p => p.route), ['pages/login/index'])
    f.nav.open('/pages/login/index', { mode: 'reset', guard: true }); assert.equal(f.calls.length, 1)
  })
  await test('reuse returns to existing route with calculated delta; current route is a no-op', () => {
    const f = setup(); f.setPages([{ route: 'pages/home/index' }, { route: 'pages/station-mgmt/station-info/index' }, { route: 'pages/station-mgmt/index' }])
    f.nav.open('/pages/station-mgmt/station-info/index', { reuse: true })
    assert.equal(f.calls[0].api, 'navigateBack'); assert.equal(f.calls[0].o.delta, 1); f.ok()
    f.nav.open('/pages/station-mgmt/station-info/index', { reuse: true }); assert.equal(f.calls.length, 1)
  })
  await test('native back keeps stack semantics and double-back cannot pop twice', () => {
    const f = setup(); f.setPages([{ route: 'pages/home/index' }, { route: 'pages/station-mgmt/index' }])
    f.nav.back(1); f.nav.back(1); assert.equal(f.calls.length, 1); f.ok()
    assert.equal(f.pages().length, 1)
  })
  await test('failed native back can be retried without losing delta', () => {
    const f = setup(); f.setPages([{ route: 'pages/home/index' }, { route: 'pages/station-mgmt/index' }])
    f.nav.back(1); f.error(); f.messages[0].retry()
    assert.equal(f.calls.length, 2); assert.equal(f.calls[1].api, 'navigateBack'); assert.equal(f.calls[1].o.delta, 1)
    f.ok(1)
  })
  await test('query-bearing module entry preserves new filters rather than reusing stale page options', () => {
    const f = setup(); f.setPages([{ route: 'pages/home/index' }, { route: 'pages/station-mgmt/ticket-packages/index' }, { route: 'pages/station-mgmt/index' }])
    f.nav.open(ticket, { reuse: true }); assert.equal(f.calls[0].api, 'navigateTo'); assert.equal(f.calls[0].o.url, ticket)
    f.ok()
  })
  await test('guard redirect is queued during business navigation and runs when it settles', () => {
    const f = setup(); f.nav.open(ticket)
    f.setSession('expired'); f.nav.invalidate()
    f.nav.open('/pages/login/index', { mode: 'reset', guard: true })
    f.nav.open('/pages/login/index', { mode: 'reset', guard: true }); assert.equal(f.calls.length, 1)
    f.error(0); assert.equal(f.messages.length, 0, 'old identity must not receive feedback')
    assert.equal(f.calls.length, 2); assert.equal(f.calls[1].o.url, '/pages/login/index'); f.ok(1)
    assert.equal(f.pages().length, 1)
  })
  await test('latest role redirect wins over an obsolete queued guard', () => {
    const f = setup(); f.nav.open(ticket)
    f.nav.open('/pages/role-select/index', { mode: 'reset', guard: true })
    f.setSession('delivery:bound:2'); f.nav.invalidate()
    f.nav.open('/pages/home/index', { mode: 'reset', guard: true }); f.ok(0)
    assert.equal(f.calls[1].o.url, '/pages/login/index'); f.ok(1); f.ok(2)
    assert.equal(f.pages()[0].route, 'pages/home/index')
  })
  await test('owner unload cancels UI callbacks without letting late completion release a new lock', () => {
    const f = setup(), owner = {}; f.nav.open(ticket, { owner }); f.nav.dispose(owner)
    f.nav.open('/pages/mine/index'); assert.equal(f.calls.length, 2)
    f.error(0); f.nav.open(ticket); assert.equal(f.calls.length, 2); assert.equal(f.messages.length, 0)
    f.ok(1)
  })
  await test('removed source Page releases navigation even without an explicit unload hook', () => {
    const f = setup(), owner = { route: 'pages/home/index' }; f.setPages([owner])
    f.nav.open(ticket, { owner }); f.setPages([{ route: 'pages/station-mgmt/index' }])
    f.nav.open('/pages/mine/index'); assert.equal(f.calls.length, 2)
    f.error(0); assert.equal(f.messages.length, 0); f.nav.open(ticket); assert.equal(f.calls.length, 2); f.ok(1)
  })
  await test('manual return to hidden source releases its old attempt', () => {
    const f = setup(), owner = {}; f.nav.open(ticket, { owner }); f.nav.hide(owner); f.nav.show(owner)
    f.nav.open(ticket, { owner }); assert.equal(f.calls.length, 2)
    f.error(0); assert.equal(f.messages.length, 0); f.ok(1)
  })
  await test('old failure dialog cannot retry after role switch or owner destruction', () => {
    const f = setup(), owner = {}; f.nav.open(ticket, { owner }); f.error()
    f.setSession('delivery:2'); f.nav.invalidate(); f.messages[0].retry(); assert.equal(f.calls.length, 1)
    f.nav.open(ticket, { owner }); f.error(); f.nav.dispose(owner); f.messages[1].retry(); assert.equal(f.calls.length, 2)
  })
  await test('stale recovery dialog cannot steal navigation after a new successful attempt', () => {
    const f = setup(); f.nav.open(ticket); f.error(); const old = f.messages[0]
    f.nav.open('/pages/mine/index'); f.ok(); old.retry(); old.home()
    assert.equal(f.calls.length, 2); assert.equal(f.homeCount(), 0)
  })
  await test('leaving the owner hides its stale failure recovery actions', () => {
    const f = setup(), owner = {}; f.nav.open(ticket, { owner }); f.error()
    f.nav.hide(owner); f.nav.show(owner); f.messages[0].retry(); assert.equal(f.calls.length, 1)
  })
  await test('explicit home recovery invokes current role decision once', () => {
    const f = setup(); f.nav.open(ticket); f.error(); f.messages[0].home(); assert.equal(f.homeCount(), 1)
  })
  await test('business permission rechecked before retry; guard remains available', () => {
    const f = setup(); f.nav.open(ticket, { business: true }); f.error(); f.setAllowed(false)
    f.messages[0].retry(); assert.equal(f.calls.length, 1)
    f.nav.open('/pages/role-select/index', { guard: true, mode: 'reset' }); assert.equal(f.calls.length, 2)
  })
  await test('real login route uses reset bridge and does not send duplicate reLaunch to tab', () => {
    const wx = createWx(), raw = [], pages = [{ route: 'pages/login/index' }]
    for (const api of ['reLaunch', 'switchTab', 'redirectTo']) wx[api] = o => raw.push({ api, o })
    const app = realApp(wx, pages); app.setLoginState({ accessToken: 'synthetic', role: 'STATION_MANAGER', stationId: 1 })
    app.routeByRole(false); app.routeByRole(false)
    assert.equal(raw.length, 1); assert.equal(raw[0].api, 'reLaunch'); assert.equal(raw[0].o.url, '/pages/login/index')
    raw[0].o.success({ errMsg: 'reLaunch:ok' }); assert.equal(raw.length, 2)
    assert.equal(raw[1].api, 'switchTab'); assert.equal(raw[1].o.url, '/pages/home/index')
    raw[1].o.success({ errMsg: 'switchTab:ok' })
  })
  await test('real role guard still sends UNSELECTED, unbound and stationless manager to restricted flow', () => {
    for (const [u, target] of [
      [{ role: 'UNSELECTED' }, '/pages/role-select/index'],
      [{ role: 'DELIVERY', bindingStatus: 'UNBOUND', stationId: 1 }, '/pages/station-mgmt/apply-bind/index'],
      [{ role: 'STATION_MANAGER' }, '/pages/station-mgmt/create-station/index']
    ]) {
      const wx = createWx(), raw = []; wx.redirectTo = o => raw.push(o)
      const app = realApp(wx, [{ route: 'pages/station-mgmt/index' }]); app.setLoginState({ accessToken: 'synthetic', ...u })
      assert.equal(app.canAccessStationBusiness(), false); app.routeByRole(true)
      assert.equal(raw[0].url, target); raw[0].success({ errMsg: 'redirectTo:ok' })
    }
  })
  await test('real module menu keeps query-bearing target instead of navigating back to stale filters', () => {
    const wx = createWx(), raw = []; wx.navigateTo = o => raw.push(o)
    const page = loadPage('miniapp-delivery/pages/station-mgmt/index.js', { wx, app: createApp(), stubs: { 'utils/request': { get: async () => ({ data: {} }) } } })
    global.getCurrentPages = () => [{ route: 'pages/home/index' }, { route: 'pages/station-mgmt/ticket-packages/index' }, page]
    page.onNavigate({ currentTarget: { dataset: { url: ticket } } })
    assert.equal(raw.length, 1); assert.equal(raw[0].url, ticket); raw[0].success({ errMsg: 'navigateTo:ok' })
  })
  await test('credential renewal during reset does not strand a valid identity on login page', () => {
    const wx = createWx(), raw = []
    for (const api of ['reLaunch', 'switchTab']) wx[api] = o => raw.push({ api, o })
    const app = realApp(wx, [{ route: 'pages/login/index' }])
    app.setLoginState({ accessToken: 'synthetic-old', role: 'STATION_MANAGER', stationId: 1 })
    app.routeByRole(false); app.globalData.accessToken = 'synthetic-renewed'
    raw[0].o.success({ errMsg: 'reLaunch:ok' })
    assert.equal(raw.length, 2); assert.equal(raw[1].api, 'switchTab'); raw[1].o.success({ errMsg: 'switchTab:ok' })
  })
  await test('late binding status cannot overwrite a new staff identity', async () => {
    const wx = createWx(); let resolve
    const app = realApp(wx, [{ route: 'pages/bind-wait/index' }], () => new Promise(r => { resolve = r }))
    app.setLoginState({ accessToken: 'synthetic-old', role: 'STATION_MANAGER', stationId: 1 })
    const task = app.syncIdentity()
    app.setLoginState({ accessToken: 'synthetic-new', role: 'STATION_MANAGER', stationId: 2 })
    resolve({ data: { role: 'STATION_MANAGER', stationId: 1 } }); await task
    assert.equal(app.globalData.userInfo.stationId, 2)
  })
  await test('late original HTTP401 cannot clear a newer login or send it to login page', async () => {
    const wx = createWx(), requests = [], raw = []; wx.request = o => requests.push(o)
    wx.reLaunch = o => raw.push(o)
    const app = realApp(wx, [{ route: 'pages/station-mgmt/index' }])
    app.setLoginState({ accessToken: 'synthetic-old', role: 'STATION_MANAGER', stationId: 1 })
    const file = path.join(ROOT, 'miniapp-delivery/utils/request.js'); delete require.cache[require.resolve(file)]
    const task = require(file).get('/api/manager/pending-summary').catch(e => e)
    app.setLoginState({ accessToken: 'synthetic-new', role: 'DELIVERY', bindingStatus: 'BOUND', stationId: 2 })
    if (!requests[0]) throw await task
    requests[0].success({ statusCode: 401 })
    await task
    assert.equal(app.globalData.accessToken, 'synthetic-new'); assert.equal(app.globalData.isLogin, true); assert.equal(raw.length, 0)
  })
  await test('late failed refresh cannot clear a newer login or navigate away', async () => {
    const wx = createWx(), requests = [], raw = []; wx.request = o => requests.push(o); wx.reLaunch = o => raw.push(o)
    const app = realApp(wx, [{ route: 'pages/station-mgmt/index' }])
    app.setLoginState({ accessToken: 'synthetic-old', refreshToken: 'synthetic-refresh', role: 'STATION_MANAGER', stationId: 1 })
    const file = path.join(ROOT, 'miniapp-delivery/utils/request.js'); delete require.cache[require.resolve(file)]
    const task = require(file).get('/api/manager/pending-summary').catch(e => e)
    if (!requests[0]) throw await task
    requests[0].success({ statusCode: 401 }); assert.equal(requests.length, 2)
    app.setLoginState({ accessToken: 'synthetic-new', role: 'DELIVERY', bindingStatus: 'BOUND', stationId: 2 })
    requests[1].fail({ errMsg: 'request:fail timeout' }); if (requests[1].complete) requests[1].complete()
    await task
    assert.equal(app.globalData.accessToken, 'synthetic-new'); assert.equal(raw.length, 0)
  })
  console.log(`navigation recovery: ${passed} passed, ${failed} failed`)
  if (failed) process.exitCode = 1
  else console.log('AQUAFLOW_SUITE_OK ' + passed)
})()
