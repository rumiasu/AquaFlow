'use strict'
const assert = require('node:assert/strict')
const { loadPage, createWx, createApp, armWatchdog } = require('./harness')

const PAGE = 'miniapp-delivery/pages/station-mgmt/delivery-config/index.js'
const CONFIG = '/api/manager/delivery-config'
const CONFLICT = '配送配置已被其他会话修改，请重新加载后再保存'
const defaults = () => ({ minOrderBuckets: null, minOrderAmount: null, minOrderMode: 'WARN', minOrderFee: 0,
  deliveryRadiusM: null, overRadiusMode: 'WARN', remoteFee: 0, baseDeliveryFee: 0,
  freeDeliveryBuckets: null, freeDeliveryAmount: null, floorFreeLevel: 1, floorFeePerLevel: 0, floorFeeMode: 'PER_ORDER' })
const input = value => ({ currentTarget: { dataset: { field: 'baseDeliveryFee' } }, detail: { value } })
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r }); return { resolve, promise } }
function server(configured = false) {
  const state = { configured, config: defaults() }
  const snapshot = () => JSON.stringify([state.configured, state.config])
  return { state, snapshot,
    read: () => ({ code: 0, data: { configured: state.configured, config: { ...state.config }, snapshot: snapshot() } }),
    save: body => {
      if (body.expectedSnapshot !== snapshot()) throw Error(CONFLICT)
      const { expectedSnapshot, ...config } = body
      state.config = { ...config }; state.configured = true
      return { code: 0, data: { configured: true, config: { ...config }, snapshot: snapshot() } }
    }
  }
}
function fixture(remote = server(), options = {}) {
  const wx = options.wx || createWx(), calls = []
  wx.stopPullDownRefresh = () => {}
  const app = options.app || createApp({ globalData: { isLogin: true, accessToken: 'synthetic',
    userInfo: { staffId: 9, stationId: 17, role: 'STATION_MANAGER', bindStatus: 'BOUND' } } })
  const page = loadPage(PAGE, { wx, app, stubs: { 'utils/request': {
    get: async url => {
      calls.push({ method: 'GET', url })
      return url === CONFIG ? (options.read ? options.read() : remote.read()) : { code: 0, data: { items: [] } }
    },
    put: async (url, body) => {
      calls.push({ method: 'PUT', url, body })
      return options.save ? options.save(body) : remote.save(body)
    }
  } } })
  const activate = () => { global.wx = wx; global.getApp = () => app }
  return { page, wx, app, remote, calls, activate, puts: () => calls.filter(c => c.method === 'PUT') }
}
const cases = [], test = (name, run) => cases.push({ name, run })

test('save before successful read sends no request', async () => {
  const t = fixture(); await t.page.onSave()
  assert.equal(t.puts().length, 0); assert.match(t.page.data.saveError, /重新读取/)
})
test('missing snapshot and failed load cannot save even through a direct handler call', async () => {
  for (const read of [() => ({ code: 0, data: { configured: false, config: defaults() } }),
    () => { throw Error('synthetic offline') }]) {
    const t = fixture(server(), { read }); await t.page.load(); await t.page.onSave()
    assert.equal(t.page.data.configReady, false); assert.equal(t.puts().length, 0)
  }
})
test('normal edit sends the loaded snapshot and readback keeps ordinary save behavior', async () => {
  const t = fixture(server(true)); await t.page.load(); const initial = t.page.data.configSnapshot
  t.page.onInput(input('3.50')); await t.page.onSave()
  assert.equal(t.puts()[0].body.expectedSnapshot, initial)
  assert.equal(t.remote.state.config.baseDeliveryFee, 3.5)
  assert.equal(t.page.data.hasUnsavedChanges, false); assert.equal(t.page.data.saveError, '')
  assert.equal(t.page.data.configSnapshot, t.remote.snapshot())
})
test('two unconfigured pages cannot let stale defaults overwrite the first saved fees', async () => {
  const remote = server(), a = fixture(remote), b = fixture(remote)
  a.activate(); await a.page.load(); b.activate(); await b.page.load()
  const absentSnapshot = b.page.data.configSnapshot
  a.activate(); a.page.onInput(input('7')); await a.page.onSave()
  b.activate(); await b.page.onShow(); await b.page.onSave()
  assert.equal(b.puts()[0].body.expectedSnapshot, absentSnapshot)
  assert.equal(remote.state.config.baseDeliveryFee, 7)
  assert.equal(b.page.data.form.baseDeliveryFee, '0'); assert.equal(b.page.data.configConflict, true)
  assert.match(b.page.data.saveError, /其他会话/)
  assert.equal(b.wx.__calls.toast.filter(t => t.icon === 'success').length, 0)
  await b.page.onSave(); assert.equal(b.puts().length, 1, 'a conflict must not automatically retry')
})
test('existing configured pages reject stale edits without replacing the draft', async () => {
  const remote = server(true), a = fixture(remote), b = fixture(remote)
  a.activate(); await a.page.load(); b.activate(); await b.page.load(); b.page.onInput(input('9'))
  a.activate(); a.page.onInput(input('4')); await a.page.onSave()
  b.activate(); await b.page.onSave()
  assert.equal(remote.state.config.baseDeliveryFee, 4); assert.equal(b.page.data.form.baseDeliveryFee, '9')
  assert.equal(b.page.data.configConflict, true)
})
test('restoring a stale local draft preserves its old snapshot instead of borrowing the new one', async () => {
  const remote = server(true), t = fixture(remote); await t.page.load(); t.page.onInput(input('9'))
  const oldSnapshot = t.page.data.configSnapshot; t.page.onUnload()
  remote.state.config.baseDeliveryFee = 4
  const again = fixture(remote, { wx: t.wx, app: t.app }); await again.page.load(); await again.page.onSave()
  assert.equal(again.page.data.form.baseDeliveryFee, '9'); assert.equal(again.page.data.configSnapshot, oldSnapshot)
  assert.equal(again.page.data.configConflict, true); assert.equal(again.puts().length, 0)
  assert.equal(remote.state.config.baseDeliveryFee, 4)
})
test('absent-to-present defaults conflict even when all business values are still identical', async () => {
  const remote = server(), t = fixture(remote); await t.page.load(); const absentSnapshot = t.page.data.configSnapshot
  t.page.onUnload(); remote.state.configured = true
  const again = fixture(remote, { wx: t.wx, app: t.app }); await again.page.load(); await again.page.onSave()
  assert.equal(again.page.data.configSnapshot, absentSnapshot)
  assert.equal(again.page.data.configConflict, true); assert.equal(again.puts().length, 0)
})
test('legacy drafts without a snapshot require rereading before save', async () => {
  const t = fixture(); await t.page.load(); t.page.onInput(input('9'))
  const key = t.page.feeDraftKey(), draft = t.wx.getStorageSync(key); delete draft.snapshot
  t.wx.setStorageSync(key, draft)
  const again = fixture(t.remote, { wx: t.wx, app: t.app }); await again.page.load(); await again.page.onSave()
  assert.equal(again.page.data.form.baseDeliveryFee, '9'); assert.equal(again.page.data.configSnapshot, '')
  assert.equal(again.page.data.configConflict, true); assert.equal(again.puts().length, 0)
})
test('mismatched readback cannot pair the submitted draft with a newer snapshot', async () => {
  const remote = server(true), t = fixture(remote, { save: body => {
    const receipt = remote.save(body); remote.state.config.baseDeliveryFee = 5; return receipt
  } })
  await t.page.load(); const initial = t.page.data.configSnapshot
  t.page.onInput(input('9')); await t.page.onSave(); await t.page.onSave()
  assert.equal(t.page.data.form.baseDeliveryFee, '9'); assert.equal(t.page.data.configSnapshot, initial)
  assert.equal(t.page.data.configConflict, true); assert.equal(t.puts().length, 1)
  assert.equal(remote.state.config.baseDeliveryFee, 5)
})
test('explicit reload can be cancelled; confirming replaces both draft and snapshot', async () => {
  const remote = server(true), t = fixture(remote); await t.page.load(); t.page.onInput(input('9'))
  remote.state.config.baseDeliveryFee = 4; await t.page.onSave()
  t.wx.__modalAutoConfirm = false; t.page.onReloadLatest()
  assert.equal(t.page.data.form.baseDeliveryFee, '9'); assert.equal(t.page.data.configConflict, true)
  await t.wx.__calls.modal.at(-1).success({ confirm: true })
  assert.equal(t.page.data.form.baseDeliveryFee, '4'); assert.equal(t.page.data.configSnapshot, remote.snapshot())
  assert.equal(t.page.data.configConflict, false); assert.equal(t.page.data.hasUnsavedChanges, false)
  assert.equal(t.wx.getStorageSync(t.page.feeDraftKey()), '')
  t.page.onInput(input('6')); await t.page.onSave(); assert.equal(remote.state.config.baseDeliveryFee, 6)
})
test('out-of-order responses cannot separate snapshot from its displayed configuration', async () => {
  const remote = server(true), gates = [deferred(), deferred()]; let reads = 0
  const t = fixture(remote, { read: () => gates[reads++].promise })
  const first = t.page.load(), second = t.page.load(), old = remote.read()
  remote.state.config.baseDeliveryFee = 8; const latest = remote.read()
  gates[1].resolve(latest); await second; gates[0].resolve(old); await first
  assert.equal(t.page.data.form.baseDeliveryFee, '8'); assert.equal(t.page.data.configSnapshot, latest.data.snapshot)
})
test('switching station cannot submit the previous station form', async () => {
  const t = fixture(); await t.page.load(); t.app.globalData.userInfo.stationId = 18
  await t.page.onSave(); assert.equal(t.puts().length, 0)
})

;(async () => {
  const done = armWatchdog(); let failed = 0
  try {
    for (const t of cases) {
      try { await t.run(); console.log('PASS ' + t.name) }
      catch (error) { failed++; console.error('FAIL ' + t.name + ': ' + error.stack) }
    }
    if (failed) process.exitCode = 1
    else console.log('AQUAFLOW_SUITE_OK ' + cases.length)
  } finally { done() }
})()
