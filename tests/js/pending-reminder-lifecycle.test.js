'use strict'
// Actual reminder/App methods with synthetic deferred reads; no network, device or DB.
const assert = require('assert'), fs = require('fs'), path = require('path'), Module = require('module')
const { createWx, armWatchdog, ROOT } = require('./harness')
const done = armWatchdog(), tests = [], test = (name, run) => tests.push({ name, run })
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r }); return { promise, resolve } }
function compile(relative, stubs) {
  const filename = path.join(ROOT, relative), mod = new Module(filename, module)
  mod.filename = filename; mod.paths = Module._nodeModulePaths(path.dirname(filename))
  const original = mod.require.bind(mod)
  mod.require = name => Object.prototype.hasOwnProperty.call(stubs, name) ? stubs[name] : original(name)
  mod._compile(fs.readFileSync(filename, 'utf8'), filename)
  return mod.exports
}
function fixture() {
  const reads = [], wx = createWx(), bar = { setDot(on) { this.on = on } }
  global.wx = wx; global.getCurrentPages = () => [{ getTabBar: () => bar }]
  const reminder = compile('miniapp-delivery/utils/pending-reminder.js', { './request': { get() {
    const read = deferred(); reads.push(read); return read.promise
  } } })
  let app, identityResponse
  global.App = definition => { app = definition }
  const navigation = { sessionKey: () => JSON.stringify(app.globalData.userInfo), invalidate() {} }
  compile('miniapp-delivery/app.js', {
    './utils/pending-reminder': reminder,
    './utils/navigation': navigation, './utils/request': { get: async () => identityResponse },
    './utils/transport': { send() {} }
  })
  global.getApp = () => app
  const login = (staffId = 1) => app.setLoginState({ staffId, stationId: staffId,
    role: 'STATION_MANAGER', accessToken: 'synthetic-access', refreshToken: 'synthetic-refresh' })
  login()
  const summary = n => ({ code: 0, data: { complete: true, p0Total: n,
    items: [...Object.keys(reminder.BUSINESS_PENDING_ROUTES), 'refundDispute'].map(key => ({
      key, label: '合成待办', level: 'P1', count: 0, available: true
    })) } })
  return { wx, app, reminder, reads, bar, login, summary,
    identity(data) { identityResponse = { code: 0, data }; return app.syncIdentity() } }
}
for (const older of [0, 1]) test('latest reminder query wins: old=' + older, async () => {
  const t = fixture(), a = t.reminder.syncPendingReminder(), b = t.reminder.syncPendingReminder()
  t.reads[1].resolve(t.summary(1 - older)); await b
  t.reads[0].resolve(t.summary(older)); await a
  assert.strictEqual(t.reminder.readDotFlag(), !older)
  assert.strictEqual(t.bar.on, !older)
})
for (const transition of ['logout', 'different login', 'same login']) test(transition + ' immediately clears old reminder', () => {
  const t = fixture(); t.reminder.syncTabBarDot(true)
  if (transition === 'logout') t.app.clearLoginState()
  else t.login(transition === 'same login' ? 1 : 2)
  assert.strictEqual(t.reminder.readDotFlag(), false)
  assert.strictEqual(t.bar.on, false)
})
test('login boundary invalidates an already resolved request continuation', async () => {
  const t = fixture(), task = t.reminder.syncPendingReminder()
  t.reads[0].resolve(t.summary(1)); t.login(2); await task
  assert.strictEqual(t.reminder.readDotFlag(), false)
})
test('failed newer query preserves confirmed reminder and blocks older clearing', async () => {
  const t = fixture(); t.reminder.syncTabBarDot(true)
  const a = t.reminder.syncPendingReminder(), b = t.reminder.syncPendingReminder()
  t.reads[1].resolve({ code: 1 }); await b
  t.reads[0].resolve(t.summary(0)); await a
  assert.strictEqual(t.reminder.readDotFlag(), true)
})
test('disabling then enabling reminders does not revive an older query', async () => {
  const t = fixture(), old = t.reminder.syncPendingReminder()
  t.reminder.setReminderEnabled(false); t.reminder.setReminderEnabled(true)
  t.reads[1].resolve(t.summary(0)); await Promise.resolve(); await Promise.resolve()
  t.reads[0].resolve(t.summary(1)); await old
  assert.strictEqual(t.reminder.readDotFlag(), false)
})
test('ordinary credential renewal preserves the current query', async () => {
  const t = fixture(), task = t.reminder.syncPendingReminder()
  t.app.globalData.accessToken = 'synthetic-renewed'
  t.reads[0].resolve(t.summary(1)); await task
  assert.strictEqual(t.reminder.readDotFlag(), true)
})
for (const stationId of [null, 2]) test('verified binding change clears old station reminder: ' + stationId, async () => {
  const t = fixture(); t.reminder.syncTabBarDot(true)
  const task = t.reminder.syncPendingReminder()
  await t.identity({ role: 'STATION_MANAGER', stationId })
  assert.strictEqual(t.reminder.readDotFlag(), false)
  t.reads[0].resolve(t.summary(1)); await task
  assert.strictEqual(t.reminder.readDotFlag(), false)
})
test('same binding refresh retains the current reminder', async () => {
  const t = fixture(); t.reminder.syncTabBarDot(true)
  await t.identity({ role: 'STATION_MANAGER', stationId: 1 })
  assert.strictEqual(t.reminder.readDotFlag(), true)
})
test('new page result invalidates older application reminder query', async () => {
  const t = fixture(), old = t.reminder.syncPendingReminder()
  t.reminder.applyRedDot(t.summary(1).data)
  t.reads[0].resolve(t.summary(0)); await old
  assert.strictEqual(t.reminder.readDotFlag(), true)
})
;(async () => {
  let failed = 0
  for (const t of tests) { try { await t.run(); console.log('PASS ' + t.name) } catch (e) {
    failed++; console.error('FAIL ' + t.name + ': ' + e.message)
  } }
  done(); if (failed) process.exitCode = 1; else console.log('AQUAFLOW_SUITE_OK ' + tests.length)
})().catch(e => { done(); console.error(e); process.exitCode = 1 })
