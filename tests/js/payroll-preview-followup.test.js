const assert = require('node:assert/strict')
const path = require('node:path')
const { createWx, createApp, loadPage } = require('./harness')
const tap = id => ({ currentTarget: { dataset: { id } } })
const input = (field, value) => ({ currentTarget: { dataset: { field } }, detail: { value } })
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
const tests = [], test = (name, run) => tests.push({ name, run })
function app() { return createApp({ globalData: { isLogin: true, userInfo: { staffId: 1, role: 'STATION_MANAGER', stationId: 9 } } }) }
function nativeSetData(page) {
  page.setData = (patch, cb) => {
    for (const [key, value] of Object.entries(patch)) {
      const parts = key.replace(/\[(\d+)\]/g, '.$1').split('.')
      let target = page.data
      for (const part of parts.slice(0, -1)) target = target[part] || (target[part] = {})
      target[parts.at(-1)] = value
    }
    if (cb) cb()
  }
}
function payroll() {
  const wx = createWx(), a = app(), writes = [], reads = [], state = { preview: null, error: null, pending: null }
  state.preview = { staffId: 7, staffName: '合成配送员', periodStart: '2026-09-01', periodEnd: '2026-09-30', complete: true,
    canSettle: true, previewToken: 'synthetic-token', payableAmount: 12.34, recordedAmount: 12.34, unpaidAmount: 12.34, paidAmount: 0, settleAmount: 12.34, earnings: [], statusText: '本期应发' }
  const get = async url => {
    reads.push(url)
    if (url.includes('/payroll/preview')) { if (state.error) throw state.error; return state.pending || { data: state.preview } }
    if (url.includes('/piece-rate')) return { data: { rates: [] } }
    if (url.includes('/payroll')) return { data: [] }
    if (url.includes('/earnings')) return { data: { earnings: [], itemSummary: [], unsettledTotal: 0 } }
    if (url === '/api/manager/staff') return { data: [{ id: 7, name: '合成配送员' }] }
    return { data: [] }
  }
  const write = async (url, body) => { writes.push({ url, body }); if(state.writeError)throw state.writeError; return { data: {} } }
  const page = loadPage('miniapp-delivery/pages/station-mgmt/payroll/index.js', { wx, app: a, stubs: { 'utils/request': { get, post: write, put: write } } })
  nativeSetData(page)
  page.setData({ stationId: 9, isManager: true, loaded: true, loading: false, staffList: [{ id: 7, name: '合成配送员' }],
    genForm: { staffId: 7, periodStart: '2026-09-01', periodEnd: '2026-09-30', note: '' } })
  return { page, wx, a, writes, reads, state }
}
function staffFixture() {
  delete require.cache[require.resolve(path.resolve(__dirname, '../../miniapp-delivery/utils/staff-payroll-followup.js'))]
  const wx = createWx(), a = app(), state = { guide: { items: [{ key: 'staffPayroll', done: false, label: '员工工资结构', levelText: '建议', why: '缺少默认单价，应发待核实', route: '/pages/station-mgmt/payroll/index' }] }, failure: false, guideCalls: 0, creates: 0, posts: [] }
  const page = loadPage('miniapp-delivery/pages/station-mgmt/staff/index.js', { wx, app: a, stubs: {
    'api/delivery': {}, 'api/station-mgmt': { createStaff: async () => { state.creates++; if (state.failure) throw new Error('合成失败'); return { code: 0 } } },
    'setup-reminder': { SEEN_KEY:'setupGuideSeenKeys', fetchSetupGuide: async () => { state.guideCalls++; return state.guide } },
    'utils/request': { post: async url => { state.posts.push(url); if (state.failure) throw new Error('合成失败'); return { code: 0 } } }
  } })
  page.loadData = async () => {}
  page.setData({ formName: '合成员工', formPhone: '13800000000', showModal: true })
  return { page, wx, a, state }
}
test('opening payroll shows a read-only current-period preview without creating a payroll', async () => {
  const f = payroll(); await f.page.loadAll(); assert.equal(f.page.data.tab, 'settle'); assert(f.reads.some(url => url.includes('/payroll/preview')))
  assert.equal(f.writes.length, 0); assert.equal(f.page.data.preview.payableText, '12.34')
})
test('changing period immediately clears old amount and ignores earlier slow response', async () => {
  const f = payroll(); await f.page.loadPreview(); const slow = deferred(); f.state.pending = slow.promise
  const old = f.page.loadPreview(); assert.equal(f.page.data.preview, null); assert.equal(f.page.data.previewLoading, true)
  f.state.pending = null; f.state.preview = { ...f.state.preview, previewToken: 'new', payableAmount: 21 }
  await f.page.onGenDate(input('periodEnd', '2026-09-29')); slow.resolve({ data: { ...f.state.preview, previewToken: 'old', payableAmount: 999 } }); await old
  assert.equal(f.page.data.preview.previewToken, 'new'); assert.equal(f.page.data.preview.payableText, '21.00')
})
test('failed preview has retry state without stale amount or false zero', async () => {
  const f = payroll(); await f.page.loadPreview(); f.state.error = new Error('合成读取失败'); await f.page.loadPreview()
  assert.equal(f.page.data.preview, null); assert.equal(f.page.data.previewError, '合成读取失败'); assert.equal(f.writes.length, 0)
  f.state.error = null; await f.page.loadPreview(); assert.equal(f.page.data.previewError, '')
})
test('missing rule retains recorded wages but never presents an invented zero payable', async () => {
  const f = payroll(); Object.assign(f.state.preview, { complete: false, missingRule: true, payableAmount: null, canSettle: false })
  await f.page.loadPreview(); assert.equal(f.page.data.preview.payableText, '待核实'); f.page.onSettle(); assert.equal(f.wx.__calls.modal.length, 0)
  f.page.onGoRates(); assert.equal(f.page.data.tab, 'rate')
})
test('unknown payroll status retains recorded total and hides unverified paid/unpaid amounts', async () => {
  const f = payroll(); Object.assign(f.state.preview,{complete:false,canSettle:false,payableAmount:null,statusKnown:false})
  await f.page.loadPreview(); assert.equal(f.page.data.preview.recordedText,'12.34'); assert.equal(f.page.data.preview.paidText,'待核实'); assert.equal(f.page.data.preview.unpaidText,'待核实')
})
test('invalid period does not read or settle wages', async () => {
  const f = payroll(); await f.page.onGenDate(input('periodStart', '2026-10-01')); assert(f.page.data.previewError); assert.equal(f.reads.length, 0)
})
test('late preview after identity change or hide never paints old payroll', async () => {
  for (const hide of [true, false]) {
    const f = payroll(), slow = deferred(); f.state.pending = slow.promise; const call = f.page.loadPreview()
    if (hide) f.page.onHide(); else { f.a.globalData.userInfo.stationId = 22; f.page.clearPayrollHistory() }
    slow.resolve({ data: f.state.preview }); await call; assert.equal(f.page.data.preview, null)
  }
})
test('settlement clearly confirms employee period amount and cancel makes no mutation', async () => {
  const f = payroll(); await f.page.loadPreview(); f.wx.__modalAutoConfirm = false; f.page.onSettle()
  const m = f.wx.__calls.modal[0]; assert(m.content.includes('合成配送员')); assert(m.content.includes('2026-09-01 至 2026-09-30')); assert(m.content.includes('12.34')); assert(m.content.includes('实际付清工资'))
  assert.equal(f.writes.length, 0); assert.equal(f.page.data.generating, false)
})
test('settlement duplicate clicks and native callbacks issue exactly one registration', async () => {
  const f = payroll(); await f.page.loadPreview(); f.wx.showModal = m => f.wx.__calls.modal.push(m)
  f.page.onSettle(); f.page.onSettle(); assert.equal(f.wx.__calls.modal.length, 1)
  const m = f.wx.__calls.modal[0]; await Promise.all([m.success({ confirm: true }), m.success({ confirm: true })])
  assert.equal(f.writes.length, 1); assert.equal(f.writes[0].url, '/api/manager/payroll/settle'); assert.equal(f.writes[0].body.paidOffline, true)
})
test('changed selection while confirmation is open invalidates the old settlement', async () => {
  const f = payroll(); await f.page.loadPreview(); f.wx.showModal = m => f.wx.__calls.modal.push(m); f.page.onSettle()
  f.page.invalidatePreview(); await f.wx.__calls.modal[0].success({ confirm: true }); assert.equal(f.writes.length, 0); assert.equal(f.page.data.generating, false)
})
test('empty salary amount and newly enabled floor bonus require explicit values', async () => {
  const f = payroll(); f.page.setData({ rateForm: { productId: '0', perBucketAmount: '', floorBonusPerLevel: '', floorFreeLevel: '' } })
  await f.page.onSaveRate(); assert.equal(f.writes.length, 0)
  f.page.setData({ 'rateForm.perBucketAmount': '0', 'rateForm.floorBonusPerLevel': '2' }); await f.page.onSaveRate(); assert.equal(f.writes.length, 0)
  f.page.setData({ 'rateForm.floorFreeLevel': '0' }); await f.page.onSaveRate(); assert.equal(f.writes.length, 1); assert.equal(f.writes[0].body.floorFreeLevel, 0)
})
test('recording an item refreshes current payable and settlement is blocked while recording', async () => {
  const f=payroll(); await f.page.loadPreview(); f.page.setData({recording:true}); f.page.onSettle(); assert.equal(f.wx.__calls.modal.length,0)
  f.page.setData({recording:false,items:[{id:8,enabled:true}],record:{staffId:7,itemId:8,amount:'10',note:'合成补贴'}})
  f.state.preview={...f.state.preview,payableAmount:22.34,unpaidAmount:22.34,settleAmount:22.34,recordedAmount:22.34,previewToken:'after-item'}
  await f.page.onRecordSubmit(); assert.equal(f.page.data.preview.previewToken,'after-item'); assert.equal(f.page.data.preview.payableText,'22.34')
})
test('unknown adjustment receipt invalidates old payable and rereads before further settlement', async () => {
  const f=payroll(); await f.page.loadPreview(); f.state.writeError=new Error('合成响应丢失'); f.page.setData({adjustForm:{staffId:7,amount:'10',note:'补录'}})
  f.state.preview={...f.state.preview,payableAmount:22.34,previewToken:'after-unknown'}
  await f.page.onAdjust(); assert.equal(f.page.data.preview.previewToken,'after-unknown'); assert.equal(f.page.data.adjusting,false)
})
test('new employee success prompts salary setup on first use with a later option', async () => {
  const f = staffFixture(); f.wx.__modalAutoConfirm = false; await f.page.onSubmitAdd()
  assert.equal(f.state.creates, 1); assert.equal(f.state.guideCalls, 1); assert.equal(f.wx.__calls.modal.length, 1)
  assert.equal(f.wx.__calls.modal[0].cancelText, '稍后填写'); assert(f.page.data.payrollFollowup); assert.equal(f.page.data.showModal, false)
  f.page.onConfigurePayroll(); assert.equal(f.wx.__calls.nav[0].url, '/pages/station-mgmt/payroll/index?tab=rate')
})
test('failed employee creation never triggers salary setup', async () => {
  const f = staffFixture(); f.state.failure = true; await f.page.onSubmitAdd(); assert.equal(f.state.guideCalls, 0); assert.equal(f.wx.__calls.modal.length, 0); assert.equal(f.page.data.showModal, true)
})
test('configured salary structure does not repeat setup prompt after success', async () => {
  const f = staffFixture(); f.state.guide.items[0].done = true; await f.page.onSubmitAdd(); assert.equal(f.wx.__calls.modal.length, 0); assert.equal(f.page.data.payrollFollowup, null)
})
test('returning home after salary followup does not repeat it or suppress unrelated new setup tasks', async () => {
  const f=staffFixture(); f.wx.__modalAutoConfirm=false; f.wx.setStorageSync('setupGuideBaselineDone',true); f.wx.setStorageSync('setupGuideSeenKeys',['existing'])
  await f.page.onSubmitAdd(); assert.equal(f.wx.__calls.modal.length,1); assert.deepEqual(f.wx.getStorageSync('setupGuideSeenKeys'),['existing','staffPayroll'])
  const module={exports:{}}, source=require('node:fs').readFileSync(path.resolve(__dirname,'../../miniapp-delivery/utils/setup-reminder.js'),'utf8')
  const guide={pendingCount:1,items:f.state.guide.items}
  require('node:vm').runInNewContext(source,{module,wx:f.wx,getApp:()=>f.a,console,require:()=>({getSetupGuide:async()=>({code:0,data:guide})})})
  await module.exports.checkSetupReminder(); assert.equal(f.wx.__calls.modal.length,1)
  guide.items=guide.items.concat({key:'newOtherTask',done:false,label:'其他新配置'}); guide.pendingCount=2
  await module.exports.checkSetupReminder(); assert.equal(f.wx.__calls.modal.length,2); assert(f.wx.__calls.modal[1].content.includes('其他新配置'))
})
test('followup read failure is retryable without recreating employee', async () => {
  const f = staffFixture(); const guide = f.state.guide; f.state.guide = null; await f.page.onSubmitAdd(); assert(f.page.data.payrollFollowupError); assert.equal(f.state.creates, 1)
  f.state.guide = guide; await f.page.onRetryPayrollFollowup(); assert.equal(f.state.creates, 1); assert.equal(f.state.guideCalls, 2); assert.equal(f.page.data.payrollFollowupError, '')
})
test('returning after successful configuration clears entry without reopening modal', async () => {
  const f = staffFixture(); f.wx.__modalAutoConfirm = false; await f.page.onSubmitAdd(); f.page.onHide(); f.state.guide.items[0].done = true
  f.page.onShow(); await new Promise(resolve => setImmediate(resolve)); assert.equal(f.page.data.payrollFollowup, null); assert.equal(f.wx.__calls.modal.length, 1)
})
test('hiding during followup read resumes a quiet check on return without recreating employee', async () => {
  const f=staffFixture(), slow=deferred(), helper=require(path.resolve(__dirname,'../../miniapp-delivery/utils/staff-payroll-followup.js'))
  f.state.guide=slow.promise; const request=f.page.onSubmitAdd(); await new Promise(resolve=>setImmediate(resolve)); f.page.onHide()
  slow.resolve({items:[{key:'staffPayroll',done:false,label:'员工工资结构'}]}); await request
  assert.equal(f.wx.__calls.modal.length,0); f.state.guide={items:[{key:'staffPayroll',done:true}]}; f.page.onShow(); await new Promise(resolve=>setImmediate(resolve))
  assert.equal(f.page.data.payrollFollowupLoading,false); assert.equal(f.state.creates,1); assert.equal(f.wx.__calls.modal.length,0)
})
test('existing employee-page visits never force new followup prompts', async () => {
  const f = staffFixture(); f.page.onShow(); await new Promise(resolve => setImmediate(resolve)); assert.equal(f.state.guideCalls, 0); assert.equal(f.wx.__calls.modal.length, 0)
  f.page.onPayrollSettings(); assert.equal(f.wx.__calls.nav[0].url, '/pages/station-mgmt/payroll/index?tab=rate')
})
test('successful binding uses the same followup, unbinding and failed binding do not', async () => {
  for (const [isUnbind, failure] of [[false, false], [true, false], [false, true]]) {
    const f = staffFixture(); f.state.failure = failure; f.page.setData({ applications: [{ id: 8, isUnbind, _loading: false }] })
    await f.page.onApproveApply(tap(8)); assert.equal(f.state.guideCalls, !isUnbind && !failure ? 1 : 0)
  }
})
test('duplicate successful-create taps do not create employees twice', async () => {
  const f = staffFixture(); await Promise.all([f.page.onSubmitAdd(), f.page.onSubmitAdd()]); assert.equal(f.state.creates, 1); assert.equal(f.wx.__calls.modal.length, 1)
})
;(async () => {
  const watchdog = setTimeout(() => { console.error('PAYROLL_PREVIEW_TEST_TIMEOUT'); process.exit(1) }, 25000)
  let failed = 0
  for (const t of tests) { try { await t.run(); console.log('PASS ' + t.name) } catch (e) { failed++; console.error('FAIL ' + t.name + '\n' + e.stack) } }
  clearTimeout(watchdog); console.log('AQUAFLOW_SUITE_OK ' + (tests.length - failed) + '/' + tests.length); if (failed) process.exitCode = 1
})()
