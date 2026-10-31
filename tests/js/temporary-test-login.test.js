const assert = require('assert'), fs = require('fs'), path = require('path'), Module = require('module')
const { loadPage, createWx, createApp, armWatchdog, ROOT } = require('./harness')
const done = armWatchdog(20000), tests = [], test = (name, run) => tests.push({ name, run })
const tick = () => new Promise(resolve => setImmediate(resolve))
function fixture(root, version, answer) {
  if (arguments.length < 2) version = 'trial'
  const wx = createWx(), calls = []
  global.__wxConfig = { envVersion: version }
  const app = createApp({ globalData: { isLogin: false, userInfo: null },
    setLoginInfo(...args) { calls.push(['setLoginInfo', ...args]) },
    setLoginState(data) { calls.push(['setLoginState', data]); return data },
    routeByRole() { calls.push(['routeByRole']) } })
  const data = root === 'miniapp-user'
    ? { accessToken: 'fixture-access', refreshToken: 'fixture-refresh', userType: 'customer', customerId: 11, nickname: 'fixture' }
    : { accessToken: 'fixture-access', refreshToken: 'fixture-refresh', userType: 'staff', staffId: 12, role: 'manager', staffRole: 'STATION_MANAGER', stationId: 5, bindingStatus: 'BOUND' }
  const page = loadPage(root + '/pages/login/index.js', { wx, app, stubs: {
    'api/agreements': { current: async () => { throw Error('fixture offline') } },
    'api/auth': { testLogin: async (...args) => { calls.push(['testLogin', ...args]); return answer ? answer(...args) : { code: 0, data } },
      wxLogin: async () => ({ code: 1 }), wxLoginStaff: async () => ({ code: 1 }) }
  } })
  page.onLoad()
  return { page, wx, app, calls, data }
}
for (const root of ['miniapp-user', 'miniapp-delivery']) {
  test(root + ': only develop/trial show the form; release/unknown fail closed', async () => {
    for (const version of ['develop', 'trial', 'release', 'unknown', undefined]) {
      const t = fixture(root, version); assert.equal(t.page.data.showTestLogin, ['develop', 'trial'].includes(version))
      t.page.setData({ testAccount: 'fixture-account', testPassword: 'fixture-password' })
      if (!['develop', 'trial'].includes(version)) { await t.page.onTestLogin(); assert.equal(t.calls.length, 0) }
    }
  })
  test(root + ': fixed account/password only, password cleared and normal login state used', async () => {
    const t = fixture(root); t.page.onTestAccountInput({ detail: { value: ' fixture-account ' } })
    t.page.onTestPasswordInput({ detail: { value: ' fixture-password ' } }); const flight = t.page.onTestLogin()
    assert.equal(t.page.data.testPassword, ''); await flight
    assert.deepStrictEqual(t.calls[0], ['testLogin', 'fixture-account', ' fixture-password '])
    assert.equal(t.calls[0].length, 3); assert.equal(t.page.data.loading, false)
    assert.ok(t.calls.some(c => c[0] === (root === 'miniapp-user' ? 'setLoginInfo' : 'setLoginState')))
    const storage = JSON.stringify(t.wx.__calls.storage || []); assert.ok(!storage.includes('fixture-password'))
  })
  test(root + ': empty credentials do not send a request', async () => {
    const t = fixture(root); await t.page.onTestLogin(); assert.equal(t.calls.length, 0)
    t.page.setData({ testAccount: 'fixture-account' }); await t.page.onTestLogin(); assert.equal(t.calls.length, 0)
  })
  test(root + ': wrong password/business rejection keeps logout state', async () => {
    const t = fixture(root, 'trial', () => ({ code: 1, message: '测试账号或密码错误' }))
    t.page.setData({ testAccount: 'fixture-account', testPassword: 'wrong' }); await t.page.onTestLogin()
    assert.equal(t.calls.length, 1); assert.equal(t.page._loginComplete, undefined)
    assert.equal(t.page.data.testPassword, ''); assert.equal(t.page.data.loading, false)
  })
  test(root + ': opposite app identity is not stored', async () => {
    const t = fixture(root, 'trial', () => ({ code: 0, data: { accessToken: 'fixture-access', userType: root === 'miniapp-user' ? 'staff' : 'customer' } }))
    t.page.setData({ testAccount: 'fixture-account', testPassword: 'fixture-password' }); await t.page.onTestLogin()
    assert.equal(t.calls.length, 1); assert.equal(t.page._loginComplete, undefined)
  })
  for (const leave of ['onHide', 'onUnload']) test(root + ': single flight and ' + leave + ' discard late auth', async () => {
    let resolve; const t = fixture(root, 'trial', () => new Promise(r => { resolve = r }))
    t.page.setData({ testAccount: 'fixture-account', testPassword: 'fixture-password' })
    const first = t.page.onTestLogin(); await t.page.onTestLogin(); assert.equal(t.calls.length, 1)
    t.page[leave](); resolve({ code: 0, data: t.data }); await first
    assert.equal(t.calls.length, 1); assert.equal(t.page.data.testPassword, ''); assert.equal(t.page.data.loading, false)
  })
  test(root + ': actual API helper refuses release and sends no role/id fields', async () => {
    const filename = path.join(ROOT, root, 'api/auth.js'), source = fs.readFileSync(filename, 'utf8'), calls = []
    const original = Module._load
    try {
      delete require.cache[filename]
      Module._load = function (request, parent) {
        if (parent && parent.filename === filename && request === '../utils/request') return { post: (...args) => { calls.push(args); return Promise.resolve({ code: 1 }) } }
        return original.apply(this, arguments)
      }
      const api = require(filename)
      global.__wxConfig = { envVersion: 'release' }; assert.throws(() => api.testLogin('fixture', 'unit-pass')); assert.equal(calls.length, 0)
      global.__wxConfig = { envVersion: 'trial' }; await api.testLogin('fixture', 'unit-pass')
      assert.deepStrictEqual(calls, [['/api/auth/test-login', { account: 'fixture', password: 'unit-pass' }]])
      assert.ok(!source.includes('passwordHash:'))
    } finally { Module._load = original; delete require.cache[filename] }
  })
}
;(async () => { let failed = 0; for (const t of tests) { try { await t.run(); console.log('PASS ' + t.name) } catch (error) { failed++; console.error('FAIL ' + t.name, error) } }
  done(); if (failed) process.exitCode = 1; else console.log('AQUAFLOW_SUITE_OK ' + tests.length) })()
