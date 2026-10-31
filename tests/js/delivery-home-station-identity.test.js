'use strict'
// Actual Page + shared status behavior; memory APIs and bounded WXML projection.
// No native render, SDK, real network or business writes are exercised.
const assert = require('assert'), fs = require('fs'), path = require('path')
const { loadPage, createWx, createApp, armWatchdog, ROOT } = require('./harness')
const { parseWxml, renderElements } = require('./wxml-tree')
const tests = [], test = (name, run) => tests.push({ name, run }), done = armWatchdog()
const pending = () => { let resolve; const promise = new Promise(r => { resolve = r }); return { promise, resolve } }
const response = (name = '合成绑定站甲', id = 11) => ({ code: 0, data: { stationId: id,
  stationName: name, statusText: '合成状态', note: '', customerHint: '' } })
function fixture(read = async () => response(), options = {}) {
  const wx = createWx(), calls = { status: [], lists: [], routes: [] }
  const app = createApp({ _loginGeneration: 1, _allowed: options.allowed !== false,
    canAccessStationBusiness() { return this._allowed }, routeByRole: x => calls.routes.push(x),
    globalData: { isLogin: true, accessToken: 'synthetic-token', userInfo: { staffId: 1,
      stationId: 11, role: 'DELIVERY', bindStatus: 'BOUND', ...options.user } } })
  const api = {}
  for (const method of ['getAssignedToMe', 'getDeliveringOrders', 'getCompletedToday', 'getDeliveredUnpaid', 'getTransferList', 'getPendingOrders']) {
    api[method] = async () => { calls.lists.push(method); return { code: 0, data: [] } }
  }
  delete require.cache[require.resolve(path.join(ROOT, 'miniapp-delivery/behaviors/stationNavbar'))]
  const page = loadPage('miniapp-delivery/pages/home/index.js', { wx, app, stubs: {
    'api/delivery': api,
    'utils/request': { get: url => { calls.status.push(url); return read(url) } },
    'utils/tabbar': { syncTabBar() {} },
    'utils/setup-reminder': { checkSetupReminder: async () => null }
  } })
  return { page, wx, app, calls }
}
function cue(t) {
  const wxml = fs.readFileSync(path.join(ROOT, 'miniapp-delivery/pages/home/index.wxml'), 'utf8')
  const root = parseWxml(wxml)
  const find = n => n.attrs && n.attrs.class === 'home-station-identity' ? n :
    (n.children || []).map(find).find(Boolean)
  const line = find(root); assert.ok(line, 'home must contain visible station identity text')
  return renderElements({ tag: '#root', attrs: {}, children: [line] }, t.page.data, { includeText: true })
    .filter(n => n.tag === 'text' && n.className === 'home-station-name').map(n => n.text)
}
test('delivery home exposes its real bound name through the existing single status request', async () => {
  const t = fixture(); await t.page.loadHomeStationIdentity(11)
  assert.deepStrictEqual(cue(t), ['当前水站：合成绑定站甲'])
  assert.deepStrictEqual(t.calls.status, ['/api/stations/11/status'])
  assert.strictEqual(t.page.data.stationStatusText, '合成状态')
})
test('missing/blank/malformed and inherited names never invent a station identity', async () => {
  for (const data of [{ stationId: 11 }, { stationId: 11, stationName: ' ' }, { stationId: 11, stationName: {} },
    Object.create({ stationId: 11, stationName: '继承名' })]) {
    const t = fixture(async () => ({ code: 0, data })); await t.page.loadHomeStationIdentity(11)
    assert.deepStrictEqual(cue(t), ['当前水站：名称未核实'])
  }
})
test('wrong returned station and business failure cannot reveal another station name', async () => {
  for (const value of [response('其它站', 22), { code: 1, data: response('伪名称').data }]) {
    const t = fixture(async () => value); await t.page.loadHomeStationIdentity(11)
    assert.deepStrictEqual(cue(t), ['当前水站：名称未核实'])
  }
})
test('failure clears a previously confirmed name and does not borrow cached station data', async () => {
  let fail = false
  const t = fixture(async () => { if (fail) throw Error('synthetic offline'); return response() })
  t.app.globalData.userInfo.stationName = '其它站缓存'; t.wx.setStorageSync('stationName', '另一个缓存')
  await t.page.loadHomeStationIdentity(11); fail = true; await t.page.loadHomeStationIdentity(11)
  assert.deepStrictEqual(cue(t), ['当前水站：名称未核实'])
})
test('no bound station uses the honest unbound cue and makes no station request', async () => {
  const t = fixture(undefined, { user: { stationId: null }, allowed: false })
  await t.page.loadHomeStationIdentity(null)
  assert.deepStrictEqual(cue(t), ['未绑定水站']); assert.deepStrictEqual(t.calls.status, [])
})
test('unapproved context or foreign requested ID cannot start station reads', async () => {
  const t = fixture(undefined, { allowed: false }); await t.page.loadHomeStationIdentity(11)
  assert.strictEqual(t.calls.status.length, 0)
  const other = fixture(); await other.page.loadHomeStationIdentity(22)
  assert.strictEqual(other.calls.status.length, 0)
})
test('onShow retains delivery list scope and unauthorized routing', async () => {
  const t = fixture(); t.page.onShow()
  await Promise.resolve(); await Promise.resolve(); await Promise.resolve()
  assert.deepStrictEqual(t.calls.lists, ['getAssignedToMe', 'getDeliveringOrders', 'getCompletedToday', 'getDeliveredUnpaid', 'getTransferList'])
  assert.strictEqual(t.page.data.isManager, false)
  assert.deepStrictEqual(cue(t), ['当前水站：合成绑定站甲'])
  const denied = fixture(undefined, { allowed: false, user: { stationId: null } }); denied.page.onShow()
  assert.deepStrictEqual(denied.calls.routes, [true]); assert.strictEqual(denied.calls.lists.length, 0)
})
test('manager delivery page still requests original pending orders without filtering them', async () => {
  const t = fixture(undefined, { user: { role: 'STATION_MANAGER' } }); await t.page.loadData(true)
  assert.ok(t.calls.lists.includes('getPendingOrders')); assert.strictEqual(t.page.data.onlyAssigned, false)
})
test('newer same-station lookup wins over late older name', async () => {
  const a = pending(), b = pending(); let n = 0
  const t = fixture(() => ++n === 1 ? a.promise : b.promise)
  const old = t.page.loadHomeStationIdentity(11), latest = t.page.loadHomeStationIdentity(11)
  b.resolve(response('合成新名称')); await latest; a.resolve(response('合成旧名称')); await old
  assert.deepStrictEqual(cue(t), ['当前水站：合成新名称'])
})
for (const field of ['staffId', 'stationId', 'role', 'bindStatus', 'loginGeneration']) {
  test(field + ' change invalidates a late name', async () => {
    const d = pending(), t = fixture(() => d.promise), task = t.page.loadHomeStationIdentity(11)
    if (field === 'loginGeneration') t.app._loginGeneration++
    else t.app.globalData.userInfo[field] = 'changed'
    d.resolve(response('旧身份站名')); await task
    assert.deepStrictEqual(cue(t), ['当前水站：名称未核实'])
  })
}
for (const leave of ['onHide', 'onUnload']) {
  test(leave + ' clears confirmed name and blocks a late read', async () => {
    const d = pending(), t = fixture(() => d.promise), task = t.page.loadHomeStationIdentity(11)
    t.page[leave](); d.resolve(response()); await task
    assert.deepStrictEqual(cue(t), ['当前水站：名称未核实'])
  })
}
test('ordinary token renewal retains the same bound station identity read', async () => {
  const d = pending(), t = fixture(() => d.promise), task = t.page.loadHomeStationIdentity(11)
  t.app.globalData.accessToken = 'synthetic-renewed'; d.resolve(response()); await task
  assert.deepStrictEqual(cue(t), ['当前水站：合成绑定站甲'])
})
;(async () => {
  let failed = 0
  for (const t of tests) { try { await t.run(); console.log('PASS ' + t.name) } catch (e) { failed++; console.error('FAIL ' + t.name + ': ' + e.message) } }
  done(); if (failed) process.exitCode = 1; else console.log('AQUAFLOW_SUITE_OK ' + tests.length)
})().catch(e => { done(); console.error(e); process.exitCode = 1 })
