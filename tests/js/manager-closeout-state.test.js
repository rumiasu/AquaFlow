// Actual page handlers, synthetic transport only; no GUI, HTTP or database.
const assert = require('assert')
const fs = require('fs'), path = require('path')
const { loadPage, createWx, createApp, ROOT } = require('./harness')
const EDIT = 'miniapp-delivery/pages/station-mgmt/customers/adjust/edit/index.js'
const LIST = 'miniapp-delivery/pages/station-mgmt/customers/adjust/index.js'
const PAYROLL = 'miniapp-delivery/pages/station-mgmt/payroll/index.js'
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
const turn = () => new Promise(resolve => setImmediate(resolve))
const event = (value, field) => ({ detail: { value }, currentTarget: { dataset: { field } } })
const idEvent = id => ({ currentTarget: { dataset: { id } } })
function fixture(file, api) {
  delete require.cache[path.join(ROOT, 'miniapp-delivery/utils/adjustment-customer.js')]
  const wx = createWx(), app = createApp({ globalData: { isLogin: true, userInfo: { staffId: 7, stationId: 1, role: 'STATION_MANAGER' } }, isStationManager: () => true })
  const stubs = file === PAYROLL ? { 'utils/request': api } : { 'api/station-mgmt': api }
  const page = loadPage(file, { wx, app, stubs })
  // Match WeChat's nested setData paths without changing the shared frozen harness.
  page.setData = patch => { for (const [key, value] of Object.entries(patch)) {
    const parts = key.split('.'); let obj = page.data
    for (let i = 0; i < parts.length - 1; i++) obj = obj[parts[i]]
    obj[parts[parts.length - 1]] = value
  } }
  if (file === EDIT) {
    page._customerSeq = 1
    page.setData({ customerId: 7, customerAssets: { customerId: 7, adjustmentEligible: true },
      option: page.data.typeOptions[0], typeIndex: 0, productList: [{ id: 3 }, { id: 4 }], productIndex: 0,
      form: { qty: '1', amount: '', unitPrice: '', reason: 'synthetic reason' }, clientToken: 'synthetic-intent' })
  }
  return { page, wx, app }
}
const previewResult = qty => ({ data: { before: { right: 0 }, after: { right: qty } } })
function payrollApi(read) {
  return { get: async url => {
    if (url.startsWith('/api/manager/payroll')) return read(url)
    return { data: url.includes('piece-rate') ? { rates: [] } : [] }
  } }
}
function realStaffApp() {
  const wx = createWx(), requests = []; let app
  wx.request = o => requests.push(o)
  global.__wxConfig = { envVersion: 'develop' }
  global.wx = wx; global.getCurrentPages = () => [{ route: 'pages/home/index' }]
  global.App = config => { app = config }
  const Module = require('module'), original = Module._load
  Module._load = function (name, parent, main) {
    if (name.endsWith('utils/request')) return { get: async () => ({ data: {} }) }
    return original.apply(this, arguments)
  }
  try { const file = path.join(ROOT, 'miniapp-delivery/app.js'); delete require.cache[file]; require(file) }
  finally { Module._load = original }
  global.getApp = () => app
  const login = (id = 7, role = 'STATION_MANAGER') => app.setLoginState({
    accessToken: 'synthetic-session-' + id, refreshToken: 'synthetic-renewal-' + id,
    staffId: id, role, stationId: 1, bindStatus: 'BOUND'
  })
  login()
  return { app, wx, requests, login }
}
const slip = { id: 1, staffId: 7, staffName: 'synthetic staff', periodStart: '2026-09-01', periodEnd: '2026-09-30' }
let passed = 0, failed = 0
const watchdog = setTimeout(() => { console.error('closeout suite timed out'); process.exit(1) }, 30000)
async function test(name, fn) { try { await fn(); passed++; console.log('PASS ' + name) } catch (e) { failed++; console.error('FAIL ' + name + ': ' + e.message) } }
;(async () => {
  await test('quantity changed during preview cannot authorize a different creation', async () => {
    const late = deferred(), writes = []
    const { page } = fixture(EDIT, { previewAdjustment: () => late.promise, createAdjustment: async body => { writes.push(body) } })
    const pending = page.onPreview(); page.onQtyInput(event('2')); late.resolve(previewResult(1)); await pending
    assert.equal(page.data.preview, null); page.onSubmit(); await turn(); assert.equal(writes.length, 0)
  })
  await test('changing back to the old value does not revive an obsolete preview', async () => {
    const late = deferred(), { page } = fixture(EDIT, { previewAdjustment: () => late.promise })
    const pending = page.onPreview(); page.onQtyInput(event('2')); page.onQtyInput(event('1'))
    late.resolve(previewResult(1)); await pending; assert.equal(page.data.preview, null)
  })
  for (const [name, change] of [
    ['reason', p => p.onReasonInput(event('new reason'))],
    ['product', p => p.onProductChange(event('1'))],
    ['type', p => p.onTypeChange(event('1'))],
    ['amount', p => p.onAmountInput(event('50'))],
    ['unit price', p => p.onUnitPriceInput(event('20'))],
    ['direction', p => p.onOverSign({ currentTarget: { dataset: { sign: -1 } } })],
    ['manual product', p => p.onManualProductInput(event('4'))],
    ['product mode', p => p.onToggleManualProduct()],
    ['evidence removal', p => p.onRemoveEvidence({ currentTarget: { dataset: { index: 0 } } })]
  ]) await test(name + ' invalidates the confirmed preview', async () => {
    const { page } = fixture(EDIT, { previewAdjustment: async () => previewResult(1) })
    page.setData({ evidenceList: ['synthetic-evidence'] }); await page.onPreview(); change(page)
    assert.equal(page.data.preview, null)
  })
  await test('failed new preview cannot leave an older successful preview usable', async () => {
    let fail = false
    const { page } = fixture(EDIT, { previewAdjustment: async () => { if (fail) throw Error('synthetic preview failure'); return previewResult(1) } })
    await page.onPreview(); fail = true; await page.onPreview(); assert.equal(page.data.preview, null)
  })
  await test('old failure cannot hide or unlock a newer in-flight preview', async () => {
    const old = deferred(), next = deferred(); let calls = 0, hides = 0
    const { page, wx } = fixture(EDIT, { previewAdjustment: () => (++calls === 1 ? old : next).promise })
    wx.hideLoading = () => { hides++ }
    const first = page.onPreview(); page.onQtyInput(event('2')); const second = page.onPreview()
    assert.equal(calls, 2); const before = hides; old.reject(Error('obsolete')); await first
    assert.equal(page.data.previewing, true); assert.equal(hides, before)
    next.resolve(previewResult(2)); await second; assert.equal(page.data.previewRows[0].after, '2')
  })
  await test('customer switch drops late preview and balances its loading indicator', async () => {
    const old = deferred(); let hides = 0
    const { page, wx } = fixture(EDIT, { previewAdjustment: () => old.promise, getCustomerAssets: async id => ({ data: { customerId: id, adjustmentEligible: true } }) })
    wx.hideLoading = () => { hides++ }
    const pending = page.onPreview(); await page.loadSelectedCustomer(8); old.resolve(previewResult(1)); await pending
    assert.equal(page.data.customerId, 8); assert.equal(page.data.preview, null); assert.ok(hides > 0)
  })
  for (const field of ['staffId', 'stationId']) await test(field + ' change drops a late preview', async () => {
    const late = deferred(), { page, app } = fixture(EDIT, { previewAdjustment: () => late.promise })
    const pending = page.onPreview(); app.globalData.userInfo[field]++; late.resolve(previewResult(1)); await pending
    assert.equal(page.data.preview, null)
  })
  await test('late modal confirmation cannot submit a changed-and-restored form', async () => {
    const writes = [], { page, wx } = fixture(EDIT, { previewAdjustment: async () => previewResult(1), createAdjustment: async b => { writes.push(b) } })
    wx.showModal = o => wx.__calls.modal.push(o)
    await page.onPreview(); page.onSubmit(); const modal = wx.__calls.modal[0]
    page.onQtyInput(event('2')); page.onQtyInput(event('1')); modal.success({ confirm: true }); await turn()
    assert.equal(writes.length, 0)
  })
  await test('repeat submit and duplicate modal callback issue only one creation', async () => {
    const pending = deferred(), writes = [], { page, wx } = fixture(EDIT, { previewAdjustment: async () => previewResult(1), createAdjustment: b => { writes.push(b); return pending.promise } })
    wx.showModal = o => wx.__calls.modal.push(o)
    await page.onPreview(); page.onSubmit(); page.onSubmit(); assert.equal(wx.__calls.modal.length, 1)
    wx.__calls.modal[0].success({ confirm: true }); wx.__calls.modal[0].success({ confirm: true }); page.onSubmit()
    assert.equal(writes.length, 1); pending.resolve({ data: { id: 990001 } }); await turn(); page.onSubmit()
    assert.equal(writes.length, 1); if (page.onUnload) page.onUnload()
  })
  await test('unchanged creation retry keeps its key but a newly edited intent gets a new key', async () => {
    const writes = [], { page } = fixture(EDIT, { previewAdjustment: async b => previewResult(b.qty), createAdjustment: async b => { writes.push(b); throw Error('synthetic response lost') } })
    await page.onPreview(); page.onSubmit(); await turn(); page.onSubmit(); await turn()
    assert.equal(writes.length, 2); assert.equal(writes[0].clientToken, writes[1].clientToken)
    page.onQtyInput(event('2')); await page.onPreview(); page.onSubmit(); await turn()
    assert.equal(writes.length, 3); assert.notEqual(writes[0].clientToken, writes[2].clientToken); assert.equal(writes[2].qty, 2)
  })
  await test('return rereads assets while keeping selection and drafts intact', async () => {
    let right = 1, calls = 0
    const { page } = fixture(LIST, { getCustomerAssets: async id => { calls++; return { data: { customerId: id, adjustmentEligible: true, rightBuckets: right } } }, listAdjustments: async () => ({ data: { list: [], total: 0 } }) })
    await page.loadSelectedCustomer(7); page.setData({ form: { qty: 'keep' }, clientToken: 'keep-token' }); right = 4
    await page.onShow(); await turn(); assert.equal(calls, 2); assert.equal(page.data.customerAssets.rightBuckets, 4)
    assert.equal(page.data.customerId, 7); assert.equal(page.data.form.qty, 'keep'); assert.equal(page.data.clientToken, 'keep-token')
  })
  await test('failed return asset refresh is visibly unverified, not old numbers', async () => {
    const { page } = fixture(LIST, { getCustomerAssets: async () => { throw Error('synthetic asset failure') }, listAdjustments: async () => ({ data: { list: [], total: 0 } }) })
    page.setData({ customerId: 7, customerAssets: { rightBuckets: 1 } }); await page.onShow(); await turn()
    assert.equal(page.data.customerId, 7); assert.equal(page.data.customerAssets, null); assert.ok(page.data.customerAssetsError)
    assert.equal(page.data.customerAssetsLoading, false)
  })
  await test('old asset refresh cannot replace a newly selected customer summary', async () => {
    const late = deferred(), { page } = fixture(LIST, { getCustomerAssets: id => id === 7 ? late.promise : Promise.resolve({ data: { customerId: id, adjustmentEligible: true, rightBuckets: 8 } }), listAdjustments: async () => ({ data: { list: [], total: 0 } }) })
    page.setData({ customerId: 7, customerAssets: { rightBuckets: 1 } }); const pending = page.onShow()
    await page.loadSelectedCustomer(8); late.resolve({ data: { customerId: 7, adjustmentEligible: true, rightBuckets: 70 } }); await pending; await turn()
    assert.equal(page.data.customerId, 8); assert.equal(page.data.customerAssets.rightBuckets, 8)
  })
  await test('asset refresh from an obsolete station identity cannot display numbers', async () => {
    const late = deferred(), { page, app } = fixture(LIST, { getCustomerAssets: () => late.promise, listAdjustments: async () => ({ data: { list: [], total: 0 } }) })
    page.setData({ customerId: 7 }); const pending = page.onShow(); app.globalData.userInfo.stationId = 2
    late.resolve({ data: { customerId: 7, adjustmentEligible: true, rightBuckets: 1 } }); await pending; await turn()
    assert.equal(page.data.customerAssets, null); assert.ok(page.data.customerAssetsError)
  })
  await test('payroll refresh failure survives keyword/date changes and clear filters', async () => {
    let fail = false
    const { page } = fixture(PAYROLL, payrollApi(async () => { if (fail) throw Error('synthetic payroll unavailable'); return { data: [slip] } }))
    await page.loadAll(); fail = true; await page.loadAll()
    page.onPayrollSearch(event('staff')); page.onPayrollFilterDate(event('2026-09-20', 'payrollStart')); page.onClearPayrollFilter()
    assert.ok(page.data.payrollListError.includes('synthetic payroll unavailable')); assert.ok(page.data.payrollScope.includes('上次'))
  })
  await test('invalid payroll dates do not retain mismatching rows or erase the read failure', async () => {
    const { page } = fixture(PAYROLL, payrollApi(async () => { throw Error('synthetic payroll unavailable') }))
    page.setData({ payrolls: [slip], filteredPayrolls: [slip] }); await page.loadAll()
    page.setData({ payrollStart: '2026-10-01', payrollEnd: '2026-09-01' }); page.applyPayrollFilters()
    assert.equal(page.data.filteredPayrolls.length, 0); assert.ok(page.data.payrollListError.includes('开始日期'))
    page.onClearPayrollFilter(); assert.ok(page.data.payrollListError.includes('synthetic payroll unavailable'))
  })
  await test('only successful payroll reread clears read failure and replaces old rows', async () => {
    const retry = deferred(); let state = 'ok'
    const { page } = fixture(PAYROLL, payrollApi(async () => { if (state === 'fail') throw Error('synthetic read failure'); if (state === 'retry') return retry.promise; return { data: [slip] } }))
    await page.loadAll(); state = 'fail'; await page.loadAll(); state = 'retry'; const pending = page.loadAll()
    page.onPayrollSearch(event('')); assert.ok(page.data.payrollListError.includes('synthetic read failure'))
    retry.resolve({ data: [{ ...slip, id: 2 }] }); await pending
    assert.equal(page.data.payrollListError, ''); assert.deepEqual(page.data.filteredPayrolls.map(p => p.id), [2])
  })
  await test('failed history continuation keeps the actual loaded range and retry boundary', async () => {
    let fail = false
    const rows = Array.from({ length: 100 }, (_, i) => ({ ...slip, id: 100 - i }))
    const { page } = fixture(PAYROLL, payrollApi(async () => { if (fail) throw Error('synthetic expansion failure'); return { data: rows } }))
    await page.loadAll(); fail = true; await page.onExpandPayrollHistory(); page.onPayrollSearch(event(''))
    assert.ok(page.data.payrollScope.includes('已加载的100张')); assert.equal(page.data.payrollBeforeId, 1)
    assert.ok(page.data.payrollMoreError.includes('synthetic expansion failure')); assert.equal(page.data.payrollHasMore, true)
  })
  await test('ticket configuration empty copy is gated by successful completed loading', () => {
    const text = fs.readFileSync(path.join(ROOT, 'miniapp-delivery/pages/station-mgmt/ticket-packages/index.wxml'), 'utf8')
    for (const rows of ['discounts', 'packages']) {
      const node = text.match(new RegExp('<view class="empty" wx:if="\\{\\{([^}]*!' + rows + '\\.length[^}]*)\\}\\}">'))
      assert.ok(node, rows); assert.ok(node[1].includes('!loading'), rows); assert.ok(node[1].includes('!packageLoadError'), rows)
    }
  })
  await test('old logout completion cannot clear a newer login', () => {
    const { app, wx, requests, login } = realStaffApp()
    app.logout(); login(8, 'DELIVERY'); requests[0].complete()
    assert.equal(app.globalData.userInfo.staffId, 8); assert.equal(app.globalData.isLogin, true)
    assert.equal(wx.__calls.nav.length, 0)
  })
  await test('explicit same-credential relogin is still a new logout generation', () => {
    const { app, requests, login } = realStaffApp()
    app.logout(); login(); requests[0].complete()
    assert.equal(app.globalData.isLogin, true)
  })
  await test('ordinary credential renewal does not prevent the requested current logout', () => {
    const { app, wx, requests } = realStaffApp()
    app.logout(); app.globalData.accessToken = 'synthetic-renewed'; wx.setStorageSync('accessToken', 'synthetic-renewed')
    requests[0].complete(); assert.equal(app.globalData.isLogin, false)
    assert.equal(app.globalData.accessToken, null); assert.equal(wx.__calls.nav.length, 1)
  })
  await test('current logout failure still clears local login and closes business stack', () => {
    const { app, wx, requests } = realStaffApp()
    app.logout(); requests[0].complete({ errMsg: 'request:fail synthetic' })
    assert.equal(app.globalData.isLogin, false); assert.equal(wx.__calls.nav[0].type, 'reLaunch')
    assert.equal(wx.__calls.nav[0].url, '/pages/login/index')
  })
  await test('duplicate logout and duplicate completion do not issue extra requests or redirects', () => {
    const { app, wx, requests } = realStaffApp()
    app.logout(); app.logout(); assert.equal(requests.length, 1)
    requests[0].complete(); requests[0].complete(); assert.equal(wx.__calls.nav.length, 1)
  })
  await test('obsolete logout cannot consume the newer logout attempt', () => {
    const { app, requests, login } = realStaffApp()
    app.logout(); login(8, 'DELIVERY'); app.logout(); requests[0].complete()
    assert.equal(app.globalData.isLogin, true); requests[1].complete(); assert.equal(app.globalData.isLogin, false)
  })
  await test('logout completion after explicit clear and reentry preserves the new session', () => {
    const { app, requests, login } = realStaffApp()
    app.logout(); app.clearLoginState(); login(8, 'DELIVERY'); requests[0].complete()
    assert.equal(app.globalData.userInfo.staffId, 8)
  })
  await test('staff login logs never contain temporary code, credentials or personal payload', async () => {
    const entries = [], log = console.log, warn = console.warn, error = console.error
    console.log = console.warn = console.error = (...args) => entries.push(JSON.stringify(args))
    const sample = { accessToken: 'synthetic-sensitive-access', refreshToken: 'synthetic-sensitive-renewal',
      nickname: 'synthetic-sensitive-name', phone: 'synthetic-sensitive-contact', _pendingOpenid: 'synthetic-sensitive-openid', staffId: 7, stationId: 1, role: 'STATION_MANAGER' }
    try {
      const wx = createWx(); wx.login = o => o.success({ code: 'synthetic-sensitive-code' })
      const app = createApp({ setLoginState: data => data })
      const page = loadPage('miniapp-delivery/pages/login/index.js', { wx, app, stubs: { 'api/auth': { wxLoginStaff: async () => ({ code: 0, data: sample }) } } })
      await page.onWxLogin(); const output = entries.join('\n')
      for (const value of Object.values(sample).filter(v => typeof v === 'string' && v.startsWith('synthetic-sensitive'))) assert.ok(!output.includes(value), 'login log contains sensitive fixture')
      assert.ok(!output.includes('synthetic-sensitive-code'), 'temporary code logged')
    } finally { console.log = log; console.warn = warn; console.error = error }
  })
  await test('both login pages log failure without dumping arbitrary server error objects', async () => {
    for (const file of ['miniapp-user/pages/login/index.js', 'miniapp-delivery/pages/login/index.js']) {
      const entries = [], error = console.error, wx = createWx()
      wx.login = o => o.success({ code: 'synthetic-code' })
      const failure = new Error('synthetic-private-error'); failure.detail = 'synthetic-private-body'
      console.error = (...args) => entries.push(JSON.stringify(args))
      try {
        const page = loadPage(file, { wx, app: createApp(), stubs: { 'api/auth': { wxLogin: async () => { throw failure }, wxLoginStaff: async () => { throw failure } } } })
        await page.onWxLogin(); assert.ok(!entries.join('\n').includes('synthetic-private'), file)
      } finally { console.error = error }
    }
  })
  await test('asset summary binds the actual numeric deposit field and marks missing as unverified', () => {
    const text = fs.readFileSync(path.join(ROOT, 'miniapp-delivery/utils/adjustment-customer.wxml'), 'utf8')
    assert.ok(!text.includes('depositBalanceText')); assert.ok(text.includes('customerAssets.depositBalance}}'))
    assert.ok(text.includes('未核对'))
  })
  await test('payment navigation title follows entry view, switch and return without clearing filters', async () => {
    const wx = createWx(), titles = []; wx.setNavigationBarTitle = o => titles.push(o.title)
    const page = loadPage('miniapp-delivery/pages/station-mgmt/payments/index.js', { wx,
      app: createApp({ globalData: { userInfo: { stationId: 1 } } }), stubs: { 'utils/request': { get: async () => ({ data: [] }) }, 'api/station-mgmt': { getPendingPayments: async () => ({ data: [] }) } } })
    page.onLoad({ view: 'ticketHistory', customerId: '7' }); assert.equal(titles.at(-1), '水票购买收款历史')
    page.setData({ startDate: '2026-10-01' }); await page.onShow(); assert.equal(page.data.customerId, 7)
    assert.equal(page.data.startDate, '2026-10-01'); assert.equal(titles.at(-1), '水票购买收款历史')
    await page.onSetView({ currentTarget: { dataset: { key: 'pending' } } }); assert.equal(titles.at(-1), '待确认收款')
    await page.onSetView({ currentTarget: { dataset: { key: 'ticketHistory' } } }); assert.equal(titles.at(-1), '水票购买收款历史')
  })
  clearTimeout(watchdog)
  console.log(`manager closeout state: ${passed} passed, ${failed} failed`)
  if (failed) process.exitCode = 1
  else console.log('AQUAFLOW_SUITE_OK ' + passed)
})().catch(e => { clearTimeout(watchdog); console.error(e.stack); process.exitCode = 1 })
