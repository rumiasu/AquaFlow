const assert = require('assert'), fs = require('fs'), path = require('path'), os = require('os')
const { spawnSync } = require('child_process')
const { loadPage, createWx, createApp, armWatchdog, ROOT } = require('./harness')
const done = armWatchdog(30000)
let passed = 0
function test(name, run) { run(); passed++; console.log('PASS ' + name) }
function platform(side) {
  const wx = createWx(), config = JSON.parse(fs.readFileSync(path.join(ROOT, side, 'app.json'), 'utf8'))
  const routes = new Set(config.pages.map(p => '/' + p))
  const tabs = new Set(config.tabBar.list.map(p => '/' + p.pagePath))
  for (const api of ['navigateTo', 'redirectTo', 'switchTab', 'reLaunch']) {
    wx[api] = o => {
      const route = o.url.split('?')[0]
      assert(routes.has(route), 'unregistered native route: ' + o.url)
      if (api === 'switchTab') assert(tabs.has(route), 'switchTab target must be a tab')
      if (api === 'navigateTo' || api === 'redirectTo') assert(!tabs.has(route), 'ordinary navigation cannot open a tab')
      wx.__calls.nav.push({ type: api, url: o.url })
      const result = { errMsg: api + ':ok' }
      if (o.success) o.success(result)
      if (o.complete) o.complete(result)
    }
  }
  wx.setNavigationBarTitle = o => { wx.title = o.title }
  return wx
}
function assertPullWiring(pageConfig, appConfig, handler, label) {
  const window = appConfig.window || {}
  const enabled = Object.prototype.hasOwnProperty.call(pageConfig, 'enablePullDownRefresh')
    ? pageConfig.enablePullDownRefresh
    : (window.enablePullDownRefresh === undefined ? false : window.enablePullDownRefresh)
  assert.equal(typeof enabled, 'boolean', 'native pull flag must be boolean: ' + label)
  assert.strictEqual(enabled, typeof handler === 'function', 'native pull refresh mismatch: ' + label)
}
function main() {
  global.__wxConfig = { envVersion: 'develop' }
  const refreshPages = [
    'miniapp-delivery/pages/barrel-records/index',
    'miniapp-delivery/pages/history/index',
    'miniapp-delivery/pages/transfer/index',
    'miniapp-delivery/pages/station-mgmt/owed-barrels/index',
    'miniapp-user/pages/template/index'
  ]
  for (const file of refreshPages) test(file + ' enables native pull refresh and has a real handler', () => {
    assert.equal(JSON.parse(fs.readFileSync(path.join(ROOT, file + '.json'), 'utf8')).enablePullDownRefresh, true)
    const page = loadPage(file + '.js', { wx: createWx(), app: createApp(), stubs: {
      'api/delivery': {}, 'api/station-mgmt': {}
    } })
    assert.equal(typeof page.onPullDownRefresh, 'function')
  })
  for (const side of ['miniapp-user', 'miniapp-delivery']) test(side + ' matches native refresh configuration for every registered Page', () => {
    const appConfig = JSON.parse(fs.readFileSync(path.join(ROOT, side, 'app.json'), 'utf8'))
    for (const route of appConfig.pages) {
      const configFile = path.join(ROOT, side, route + '.json')
      const pageConfig = fs.existsSync(configFile) ? JSON.parse(fs.readFileSync(configFile, 'utf8')) : {}
      const wx = createWx()
      wx.getAccountInfoSync = () => ({ miniProgram: { envVersion: 'develop' } })
      // Load the real Page and Behavior methods, but do not invoke lifecycle hooks or APIs.
      const page = loadPage(side + '/' + route + '.js', { wx, app: createApp() })
      assertPullWiring(pageConfig, appConfig, page.onPullDownRefresh, side + '/' + route)
    }
  })
  test('native refresh gate rejects enabled configuration without a handler', () => {
    assert.throws(() => assertPullWiring({ enablePullDownRefresh: true }, {}, undefined, 'negative-enabled'), /native pull refresh mismatch/)
  })
  test('native refresh gate rejects a disabled flag hiding an available handler', () => {
    assert.throws(() => assertPullWiring({ enablePullDownRefresh: false }, {}, () => {}, 'negative-disabled'), /native pull refresh mismatch/)
  })
  test('native refresh gate respects the app default and an explicit page override', () => {
    assertPullWiring({}, { window: { enablePullDownRefresh: true } }, () => {}, 'global-enabled')
    assertPullWiring({ enablePullDownRefresh: false }, { window: { enablePullDownRefresh: true } }, undefined, 'page-disabled')
    assertPullWiring({}, {}, undefined, 'default-disabled')
  })
  for (const type of ['user', 'privacy', 'unknown']) test('customer login opens actual ' + type + ' agreement page', () => {
    const wx = platform('miniapp-user'), app = createApp()
    const login = loadPage('miniapp-user/pages/login/index.js', { wx, app, stubs: { 'api/auth': {} } })
    login.onOpenAgreement({ currentTarget: { dataset: { type } } })
    const expected = type === 'privacy' ? 'privacy' : 'user', nav = wx.__calls.nav[0]
    assert.deepEqual(nav, { type: 'navigateTo', url: '/pages/mine/agreement/index?type=' + expected })
    const agreement = loadPage('miniapp-user' + nav.url.split('?')[0] + '.js', { wx, app })
    agreement.onLoad({ type: expected })
    assert.equal(agreement.data.doc.navTitle, expected === 'privacy' ? '隐私政策' : '用户协议')
    assert.equal(wx.title, agreement.data.doc.navTitle)
    // This checks page wiring; replacing the draft with reviewed content must remain possible.
    assert.equal(typeof agreement.data.doc.updatedAt, 'string')
    assert(agreement.data.doc.sections.length > 0)
    assert(agreement.data.doc.sections.every(s => typeof s.heading === 'string' && typeof s.body === 'string'))
  })
  const cases = [
    [{ role: 'UNSELECTED', staffId: -9, needSelectRole: true }, '/pages/role-select/index'],
    [{ role: 'STATION_MANAGER', staffId: 9, stationId: null }, '/pages/station-mgmt/create-station/index'],
    [{ role: 'DELIVERY', staffId: 9, bindingStatus: 'UNBOUND', stationId: null }, '/pages/station-mgmt/apply-bind/index'],
    [{ role: 'DELIVERY', staffId: 9, bindingStatus: 'PENDING', stationId: null }, '/pages/bind-wait/index'],
    [{ role: 'DELIVERY', staffId: 9, bindingStatus: 'PENDING_UNBIND', stationId: 2 }, '/pages/bind-wait/index'],
    [{ role: 'DELIVERY', staffId: 9, bindingStatus: 'BOUND', stationId: 2 }, '/pages/home/index'],
    [{ role: 'STATION_MANAGER', staffId: 9, stationId: 2 }, '/pages/home/index']
  ]
  for (const [identity, target] of cases) test('actual employee login routes ' + identity.role + '/' + (identity.bindingStatus || identity.stationId || 'new'), () => {
    const wx = platform('miniapp-delivery'); let app
    global.wx = wx; global.getApp = () => app; global.getCurrentPages = () => []
    global.App = config => { app = config }
    const file = path.join(ROOT, 'miniapp-delivery/app.js'); delete require.cache[require.resolve(file)]; require(file)
    const login = loadPage('miniapp-delivery/pages/login/index.js', { wx, app, stubs: { 'api/auth': {} } })
    login.handleLoginSuccess({ accessToken: 'synthetic-access', refreshToken: 'synthetic-refresh', ...identity })
    const expected = target === '/pages/home/index'
      ? [{ type: 'reLaunch', url: '/pages/login/index' }, { type: 'switchTab', url: target }]
      : [{ type: 'reLaunch', url: target }]
    assert.deepEqual(wx.__calls.nav, expected)
    assert.equal(app.globalData.isLogin, true)
    assert.equal(app.globalData.userInfo.role, identity.role)
  })
  test('the original WXML gate rejects a missing handler in an isolated fixture', () => {
    const fixture = fs.mkdtempSync(path.join(os.tmpdir(), 'aquaflow-handler-negative-'))
    fs.copyFileSync(path.join(ROOT, 'audit_wxml_handlers.py'), path.join(fixture, 'audit_wxml_handlers.py'))
    const pages = path.join(fixture, 'miniapp-user/pages/probe'); fs.mkdirSync(pages, { recursive: true })
    fs.writeFileSync(path.join(pages, 'index.js'), 'Page({\n  data: {}\n})\n')
    fs.writeFileSync(path.join(pages, 'index.wxml'), '<button bindtap="onAbsent">probe</button>')
    const python = process.env.PYTHON || (fs.existsSync('D:/agent/python/python.exe') ? 'D:/agent/python/python.exe' : 'python3')
    const result = spawnSync(python, [path.join(fixture, 'audit_wxml_handlers.py')], {
      cwd: fixture, encoding: 'utf8', timeout: 15000, windowsHide: true,
      env: { ...process.env, PYTHONIOENCODING: 'utf-8' }
    })
    assert.ifError(result.error); assert.equal(result.status, 1); assert.match(result.stdout, /onAbsent/)
  })
  done(); console.log('platform wiring: ' + passed + ' passed'); console.log('AQUAFLOW_SUITE_OK ' + passed)
}
try { main() } catch (error) { done(); console.error(error); process.exitCode = 1 }
