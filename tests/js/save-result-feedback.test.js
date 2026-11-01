'use strict'
// 2026-11-01: execute real save handlers; a failed cache/read must not rewrite a confirmed write.
const assert = require('assert')
const { loadPage, createWx, createApp, armWatchdog } = require('./harness')
const finish = armWatchdog(30000)
const tests = []
const test = (name, run) => tests.push({ name, run })
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
function profile(side, api) {
  const wx = createWx(), app = createApp({ globalData: { isLogin: true, customerId: 7, userInfo: { staffId: 7, stationId: 3, role: 'STATION_MANAGER', nickname: '原名', phone: '13800000000' } } })
  let generation = 1
  const session = () => ({ epoch: generation, generation, loggedIn: true, customerId: app.globalData.customerId })
  const current = s => !!s && s.epoch === generation
  const p = loadPage(side + '/pages/mine/edit.js', { wx, app, stubs: {
    'api/auth': api,
    'utils/token': { captureSession: session, isCurrentSession: current },
    'utils/request': { captureSession: session, currentSession: current }
  } })
  p.onLoad()
  const enter = nickname => p.onNicknameInput({ detail: { value: nickname } })
  const error = () => p.data.saveError || p.data.error || ''
  return { p, wx, app, enter, error, switchActor() { generation++; app.globalData.customerId = 9; app.globalData.userInfo = { staffId: 9, stationId: 4, role: 'STATION_MANAGER', nickname: '新身份', phone: '' }; p.onShow() } }
}
for (const side of ['miniapp-user', 'miniapp-delivery']) {
  test(side + ' confirmed write survives USER_INFO cache failure', async () => {
    let writes = 0
    const x = profile(side, { updateProfile: async () => { writes++; return { code: 0 } } })
    x.enter('保存甲')
    const set = x.wx.setStorageSync
    x.wx.setStorageSync = (k, v) => { if (/user.?info/i.test(k)) throw Error('private device error'); set(k, v) }
    await x.p.onSubmit()
    assert.equal(writes, 1); assert.equal(x.p.data.submitting, false)
    assert.equal((x.p._original || x.p._saved).nickname, '保存甲')
    assert.equal(x.app.globalData.userInfo.nickname, '保存甲')
    assert.match(x.error(), /已保存/); assert.doesNotMatch(x.error(), /private device error|未能保存/)
    assert.equal(x.wx.__calls.nav.length, 0)
  })
  test(side + ' business rejection stays rejected and never reads as success', async () => {
    let reads = 0
    const x = profile(side, { updateProfile: async () => { throw Object.assign(Error('拒绝'), { businessRejected: true }) }, getMe: async () => { reads++; return {} } })
    x.enter('保存甲'); await x.p.onSubmit()
    assert.equal(reads, 0); assert.equal((x.p._original || x.p._saved).nickname, '原名')
    assert.match(x.error(), /未保存|未能保存/); assert.equal(x.p.data.submitting, false)
  })
  test(side + ' lost response can be confirmed by identity-bound GET', async () => {
    let writes = 0, reads = 0
    const x = profile(side, { updateProfile: async () => { writes++; throw Error('timeout') }, getMe: async () => { reads++; return { data: { customerId: 7, staffId: 7, nickname: '保存甲', phone: '13800000000' } } } })
    x.enter('保存甲'); await x.p.onSubmit()
    assert.equal(writes, 1); assert.equal(reads, 1)
    assert.equal((x.p._original || x.p._saved).nickname, '保存甲')
    assert.match(x.wx.__calls.toast.map(t => t.title).join(' ') + x.error(), /已核对|已保存|保存成功/)
  })
  test(side + ' read failure leaves outcome unknown and inputs intact', async () => {
    let writes = 0
    const x = profile(side, { updateProfile: async () => { writes++; throw Error('timeout') }, getMe: async () => { throw Error('offline') } })
    x.enter('保存甲'); await x.p.onSubmit()
    assert.equal(writes, 1); assert.equal(x.p.data.nickname, '保存甲')
    assert.match(x.error(), /未确认/); assert.doesNotMatch(x.error(), /未能保存|保存失败/)
    assert.equal(x.p.data.submitting, false)
  })
  test(side + ' wrong actor readback never confirms', async () => {
    const x = profile(side, { updateProfile: async () => { throw Error('timeout') }, getMe: async () => ({ data: { customerId: 99, staffId: 99, nickname: '保存甲', phone: '13800000000' } }) })
    x.enter('保存甲'); await x.p.onSubmit(); assert.match(x.error(), /未确认/)
    assert.equal((x.p._original || x.p._saved).nickname, '原名')
  })
  test(side + ' old actor response cannot unlock or mutate new actor save', async () => {
    const a = deferred(), b = deferred(); let calls = 0
    const x = profile(side, { updateProfile: () => (++calls === 1 ? a.promise : b.promise) })
    x.enter('旧身份甲'); const first = x.p.onSubmit()
    x.switchActor(); x.enter('新身份乙'); const second = x.p.onSubmit()
    assert.equal(calls, 2)
    a.resolve({ code: 0 }); await first
    assert.equal(x.p.data.submitting, true); assert.equal(x.app.globalData.userInfo.nickname, '新身份')
    b.resolve({ code: 0 }); await second
    assert.equal(x.app.globalData.userInfo.nickname, '新身份乙'); assert.equal(x.p.data.submitting, false)
  })
  test(side + ' late receipt does not clear newer input B', async () => {
    const a = deferred(), x = profile(side, { updateProfile: () => a.promise })
    x.enter('提交甲'); const promise = x.p.onSubmit()
    x.p.setData({ nickname: '后来乙' }); a.resolve({ code: 0 }); await promise
    assert.equal(x.p.data.nickname, '后来乙'); assert.equal((x.p._original || x.p._saved).nickname, '提交甲')
    assert.equal(x.wx.__calls.nav.length, 0)
  })
  test(side + ' blank optional phone retains existing server KEEP contract', async () => {
    const x = profile(side, { updateProfile: async () => ({ code: 0 }) })
    x.enter('保存甲'); x.p.onPhoneInput({ detail: { value: '' } }); await x.p.onSubmit()
    assert.equal(x.app.globalData.userInfo.phone, '13800000000')
    assert.equal((x.p._original || x.p._saved).phone, '13800000000')
  })
  test(side + ' unload makes late receipt inert', async () => {
    const a = deferred(), x = profile(side, { updateProfile: () => a.promise })
    x.enter('提交甲'); const pending = x.p.onSubmit(); x.p.onUnload(); a.resolve({ code: 0 }); await pending
    assert.equal(x.app.globalData.userInfo.nickname, '原名'); assert.equal(x.wx.__calls.nav.length, 0)
  })
}
test('customer confirmed write survives draft cleanup failure', async () => {
  const x = profile('miniapp-user', { updateProfile: async () => ({ code: 0 }) })
  x.enter('保存甲'); x.wx.removeStorageSync = () => { throw Error('local failure') }
  await x.p.onSubmit()
  assert.equal(x.p._original.nickname, '保存甲'); assert.equal(x.p._saveCompleted, true)
  assert.match(x.p.data.draftMessage, /已保存.*未确认清除/); assert.equal(x.p.data.submitting, false)
})
function payroll(api) {
  const wx = createWx(), app = createApp({ globalData: { isLogin: true, userInfo: { staffId: 7, stationId: 3, role: 'STATION_MANAGER' } } })
  const p = loadPage('miniapp-delivery/pages/station-mgmt/payroll/index.js', { wx, app, stubs: { 'utils/request': api } })
  p.setData({ loading: false, stationId: 3, isManager: true,
    rates: [{ id: 91, productId: 0, perBucketAmount: 2 }],
    rateForm: { productId: '0', perBucketAmount: '5', floorBonusPerLevel: '0', floorFreeLevel: '0' } })
  p.persistPayrollDraft = () => {}
  p.loadPreview = async () => {}
  return { p, wx, app }
}
test('piece rate rejected PUT never reports a saved write', async () => {
  let reads = 0
  const x = payroll({ put: async () => { throw Object.assign(Error('金额拒绝'), { businessRejected: true }) }, get: async () => { reads++; return {} } })
  await x.p.onSaveRate(); assert.equal(reads, 0)
  assert.doesNotMatch(x.wx.__calls.toast.map(t => t.title).join(' '), /已保存/)
  assert.equal(x.p.data.savingRate, false)
})
test('piece rate PUT success GET failure preserves write fact and old list', async () => {
  let writes = 0, reads = 0
  const x = payroll({ put: async () => { writes++; return { code: 0 } }, get: async () => { reads++; throw Error('unreadable') } })
  await x.p.onSaveRate()
  assert.equal(writes, 1); assert.equal(reads, 1); assert.equal(x.p.data.rates[0].perBucketAmount, 2)
  assert.match(x.p.data.rateSaveNotice, /已保存.*列表.*未刷新/)
  assert.equal(x.p.data.rateRefreshPending, true); assert.equal(x.p.data.savingRate, false)
  assert.doesNotMatch(x.wx.__calls.toast.map(t => t.title).join(' '), /保存失败|unreadable/)
})
test('piece rate refresh retry only reads, never repeats PUT', async () => {
  let writes = 0, reads = 0
  const x = payroll({ put: async () => { writes++; return { code: 0 } }, get: async () => { if (++reads === 1) throw Error('offline'); return { data: { rates: [{ id: 91, productId: 0, perBucketAmount: 5, floorBonusPerLevel: 0, floorFreeLevel: 0 }] } } } })
  await x.p.onSaveRate(); assert.equal(typeof x.p.onRetryRateRefresh, 'function')
  await x.p.onRetryRateRefresh()
  assert.equal(writes, 1); assert.equal(reads, 2); assert.equal(x.p.data.rateRefreshPending, false)
  assert.equal(x.p.data.rates[0].perBucketAmount, 5); assert.equal(x.p.data.savingRate, false)
})
test('piece rate successful list read and failed preview remain distinct', async () => {
  const x = payroll({ put: async () => ({ code: 0 }), get: async () => ({ data: { rates: [] } }) })
  x.p.loadPreview = async () => x.p.setData({ previewError: '合成读取失败' })
  await x.p.onSaveRate()
  assert.match(x.p.data.rateSaveNotice, /已保存.*预览.*未刷新/)
  assert.equal(x.p.data.rates.length, 0); assert.equal(x.p.data.rateRefreshPending, true)
})
test('piece rate normal path clears only saved product draft', async () => {
  const x = payroll({ put: async () => ({ code: 0 }), get: async () => ({ data: { rates: [] } }) })
  x.p._rateDrafts = new Map([['3:7:0', {}], ['3:7:9', { perBucketAmount: '8' }]])
  await x.p.onSaveRate()
  assert.equal(x.p._rateDrafts.has('3:7:0'), false); assert.equal(x.p._rateDrafts.has('3:7:9'), true)
  assert.match(x.p.data.rateSaveNotice, /已保存/); assert.equal(x.p.data.rateRefreshPending, false)
})
test('piece rate late receipt after hide cannot mutate list or drafts', async () => {
  const a = deferred(); let reads = 0
  const x = payroll({ put: () => a.promise, get: async () => { reads++; return { data: { rates: [] } } } })
  x.p._rateDrafts = new Map([['3:7:0', { perBucketAmount: '5' }]])
  const pending = x.p.onSaveRate(); x.p.onHide(); a.resolve({ code: 0 }); await pending
  assert.equal(reads, 0); assert.equal(x.p._rateDrafts.has('3:7:0'), true)
  assert.equal(x.p.data.rates.length, 1)
})
test('piece rate late receipt cannot affect new station flight', async () => {
  const a = deferred(), b = deferred(); let writes = 0
  const x = payroll({ put: () => (++writes === 1 ? a.promise : b.promise), get: async () => ({ data: { rates: [] } }) })
  const old = x.p.onSaveRate(); x.p.onHide(); x.app.globalData.userInfo = { staffId: 9, stationId: 4, role: 'STATION_MANAGER' }
  x.p._payrollGone = false; x.p.setData({ stationId: 4 }); const current = x.p.onSaveRate()
  assert.equal(writes, 2)
  a.resolve({ code: 0 }); await old; assert.equal(x.p.data.savingRate, true)
  b.resolve({ code: 0 }); await current; assert.equal(x.p.data.savingRate, false)
})
async function run() {
  let failed = 0
  for (const t of tests) { try { await t.run(); console.log('PASS ' + t.name) } catch (e) { failed++; console.error('FAIL ' + t.name + ': ' + e.stack) } }
  finish()
  if (failed) { console.error('FAILED ' + failed + '/' + tests.length); process.exitCode = 1 } else console.log('AQUAFLOW_SUITE_OK ' + tests.length)
}
run().catch(e => { finish(); console.error(e); process.exitCode = 1 })
