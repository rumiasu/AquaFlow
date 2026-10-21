'use strict'
// Actual coordination Page with deferred synthetic reads; no HTTP/DB/native screen.
const assert = require('assert')
const { loadPage, createWx, createApp, armWatchdog } = require('./harness')
const reminder = require('../../miniapp-delivery/utils/pending-reminder')
const done = armWatchdog(), tests = [], test = (name, run) => tests.push({ name, run })
const pending = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
const summary = n => ({ code: 0, data: { complete: true, p0Total: n,
  items: [...Object.keys(reminder.BUSINESS_PENDING_ROUTES), 'refundDispute'].map(key => ({
    key, label: '合成待办', level: 'P1', available: true, count: key === 'returnRefund' ? n : 0, amount: key === 'returnRefund' ? n * 10 : null
  })) } })
function fixture() {
  const wx = createWx(), reads = []
  const app = createApp({ _loginGeneration: 1, globalData: { isLogin: true, accessToken: 'synthetic-only',
    userInfo: { staffId: 1, stationId: 11, role: 'STATION_MANAGER', bindStatus: 'BOUND' } } })
  const page = loadPage('miniapp-delivery/pages/coordination/index.js', { wx, app, stubs: {
    'utils/request': { get: () => { const read = pending(); reads.push(read); return read.promise } },
    'utils/pending-reminder': reminder,
    'utils/navigation': { show() {}, hide() {}, dispose() {} }, 'utils/tabbar': { syncTabBar() {} },
    'api/delivery': {}, 'behaviors/stationNavbar': {}
  } })
  const complete = async n => { const task = page.loadTodo(); reads.at(-1).resolve(summary(n)); await task }
  return { page, app, wx, reads, complete,
    get dots() { return wx.__calls.storageSet.filter(row => row.k === reminder.DOT_FLAG_KEY).map(row => row.v) } }
}
for (const field of ['stationId', 'staffId', 'role', 'bindStatus', 'generation', 'logout']) {
  test(field + ' change clears old financial responsibility before the new read completes', async () => {
    const t = fixture(); await t.complete(7)
    if (field === 'generation') t.app._loginGeneration++
    else if (field === 'logout') t.app.globalData.isLogin = false
    else t.app.globalData.userInfo[field] = field === 'role' ? 'DELIVERY' : field === 'bindStatus' ? 'UNBOUND' : 22
    const count = t.reads.length, task = t.page.loadTodo()
    assert.strictEqual(t.page.data.todo, null)
    if (t.reads.length > count) t.reads.at(-1).reject(Error('synthetic unavailable'))
    await task; assert.strictEqual(t.page.data.todo, null)
  })
}
test('same-context failure retains its own numbers and retry can replace them', async () => {
  const t = fixture(); await t.complete(7); const original = t.page.data.todo
  const failed = t.page.loadTodo(); t.reads.at(-1).reject(Error('synthetic offline')); await failed
  assert.strictEqual(t.page.data.todo, original); assert(t.page.data.todoError); assert.strictEqual(t.page.data.todoLoading, false)
  await t.complete(0); assert.deepStrictEqual(t.page.data.todo.items, []); assert.strictEqual(t.page.data.todoError, '')
})
for (const outcome of ['resolve', 'reject']) test('identity changes during pending ' + outcome + ' cannot retain or rewrite old card', async () => {
  const t = fixture(); await t.complete(7)
  const task = t.page.loadTodo(); t.app.globalData.userInfo.stationId = 22
  if (outcome === 'resolve') t.reads.at(-1).resolve(summary(99)); else t.reads.at(-1).reject(Error('session changed'))
  await task; assert.strictEqual(t.page.data.todo, null); assert.strictEqual(t.page.data.todoLoading, false)
  assert.deepStrictEqual(t.dots, [true])
})
for (const leave of ['onHide', 'onUnload']) for (const outcome of ['resolve', 'reject']) {
  test(leave + ' rejects late ' + outcome + ' and releases loading', async () => {
    const t = fixture(); const task = t.page.loadTodo(); t.page[leave]()
    assert.strictEqual(t.page.data.todoLoading, false)
    if (outcome === 'resolve') t.reads[0].resolve(summary(99)); else t.reads[0].reject(Error('synthetic late'))
    await task; assert.strictEqual(t.page.data.todo, null); assert.strictEqual(t.page.data.todoError, '')
    assert.deepStrictEqual(t.dots, [])
  })
}
test('newer current request survives older completion without loading or error overwrite', async () => {
  const t = fixture(), old = t.page.loadTodo(), current = t.page.loadTodo()
  t.reads[0].reject(Error('synthetic old')); await old; assert.strictEqual(t.page.data.todoLoading, true)
  t.reads[1].resolve(summary(2)); await current
  assert.strictEqual(t.page.data.todo.items[0].count, 2); assert.strictEqual(t.page.data.todoError, '')
})
test('ordinary token renewal preserves current card and pending response', async () => {
  const t = fixture(); await t.complete(7); const original = t.page.data.todo
  const task = t.page.loadTodo(); t.app.globalData.accessToken = 'synthetic-renewed'
  assert.strictEqual(t.page.data.todo, original); t.reads.at(-1).resolve(summary(3)); await task
  assert.strictEqual(t.page.data.todo.items[0].count, 3)
})
test('returning to same manager scope starts a usable new read after hide', async () => {
  const t = fixture(); await t.complete(7); const old = t.page.loadTodo(); t.page.onHide()
  t.page.loadStationStatus = () => {}; t.page.loadStationPending = () => {}; t.page.loadAllData = () => {}
  t.page.onShow(); const latest = t.reads.at(-1)
  latest.resolve(summary(3)); await Promise.resolve(); await Promise.resolve()
  t.reads[1].resolve(summary(99)); await old
  assert.strictEqual(t.page.data.todo.items[0].count, 3); assert.strictEqual(t.page.data.todoLoading, false)
})
;(async () => {
  let failed = 0
  for (const t of tests) { try { await t.run(); console.log('PASS ' + t.name) } catch (e) { failed++; console.error('FAIL ' + t.name + ': ' + e.message) } }
  done(); if (failed) process.exitCode = 1; else console.log('AQUAFLOW_SUITE_OK ' + tests.length)
})().catch(e => { done(); console.error(e); process.exitCode = 1 })
