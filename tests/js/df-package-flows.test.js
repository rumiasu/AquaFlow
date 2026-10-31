'use strict'
// Real D-F page handlers with synthetic data/storage. No HTTP, real financial actions or UI renderer.
const assert = require('node:assert/strict'), fs = require('node:fs'), path = require('node:path')
const { loadPage, createWx, createApp, armWatchdog, ROOT } = require('./harness')
const { parseWxml, renderElements, validateConditionalSiblings } = require('./wxml-tree')
const { fixture: productFixture } = require('./extreme-product-save.test')
const tests = [], test = (name, run) => tests.push({ name, run })
const tap = (id, extra = {}) => ({ currentTarget: { dataset: { id, ...extra } } })
const input = (field, value) => ({ currentTarget: { dataset: { field } }, detail: { value } })
const file = route => 'miniapp-delivery/pages/' + route + '/index'
const tree = route => parseWxml(fs.readFileSync(path.join(ROOT, file(route) + '.wxml'), 'utf8'))
const view = (route, data) => renderElements(tree(route), data, { includeText: true })
function actor() { return createApp({ _loginGeneration: 1, globalData: { isLogin: true, userInfo: { staffId: 8, stationId: 3, role: 'STATION_MANAGER', bindStatus: 'BOUND' } } }) }
function mount(route, stubs = {}, wx = createWx(), app = actor()) {
  const p = loadPage(file(route) + '.js', { wx, app, stubs })
  p.setData = (patch, cb) => {
    for (const [key, value] of Object.entries(patch)) {
      const parts = key.replace(/\[(\d+)\]/g, '.$1').split('.'); let obj = p.data
      for (const part of parts.slice(0, -1)) obj = obj[part] || (obj[part] = {})
      obj[parts.at(-1)] = value
    }
    if (cb) cb()
  }
  return { p, wx, app }
}
function product(id) { return { id, selected: true, ownerStationId: 11, status: 1, name: 'Synthetic ' + id, category: 1, quantity: 10 } }
function inventory() {
  const t = productFixture(); t.page.setData({ list: [product(17), product(18)] })
  t.open = id => t.page.onOpenInventory(tap(id, { item: product(id) }))
  return t
}

test('D22 quantity/cost/mode follow the product, including cost-only and explicit zero drafts', () => {
  for (const cost of ['', '0', '19.90']) {
    const t = inventory(); t.open(17); t.page.onStockCostInput({ detail: { value: cost } })
    if (cost === '') t.page.onStockInput({ detail: { value: '5' } })
    t.page.closeInventory(); t.open(18)
    assert.equal(t.page.data.stockCostInput, ''); assert.equal(t.page.data.stockInput, '')
    t.page.closeInventory(); t.open(17)
    assert.equal(t.page.data.stockCostInput, cost)
    if (cost === '') assert.equal(t.page.data.stockInput, '5')
    assert.equal(t.calls.length, 0)
  }
})
test('D22 changing identity cannot inherit an old product cost', () => {
  const t = inventory(); t.open(17); t.page.onStockCostInput({ detail: { value: '23' } }); t.page.closeInventory()
  t.app.globalData.userInfo.staffId = 99; t.open(17)
  assert.equal(t.page.data.stockCostInput, ''); assert.equal(t.calls.length, 0)
})
test('D22 explicitly clearing the resumed fields cannot resurrect an old cost', () => {
  const t = inventory(); t.open(17); t.page.onStockCostInput({ detail: { value: '20' } }); t.page.closeInventory(); t.open(17)
  t.page.onStockCostInput({ detail: { value: '' } }); t.page.closeInventory(); t.open(17)
  assert.equal(t.page.data.stockCostInput, ''); assert.equal(t.calls.length, 0)
})
test('D44 cache clear preserves all request identities, drafts, preferences and unknown keys', () => {
  const t = mount('settings'); const keys = ['accessToken', 'refreshToken', 'userInfo', 'inventory-inbound-intent:any', 'payroll-adjust:any', 'refund-recovery:any', 'account-data-intent:customer:7', 'aquaflow:ui-draft:v1:any', 'todoReminderEnabled', 'future-business-key']
  keys.forEach(key => t.wx.setStorageSync(key, key === 'todoReminderEnabled' ? false : { proof: key }))
  t.wx.setStorageSync('stationName', 'Old cached name'); t.p.onClearCache()
  for (const key of keys) assert(t.wx.__storage.has(key), key)
  assert.equal(t.wx.getStorageSync('todoReminderEnabled'), false); assert(!t.wx.__storage.has('stationName'))
})
function barrels(rows, fail = false) {
  return mount('barrel-records', { 'api/delivery': { getBarrelRecords: async () => { if (fail) throw Error('synthetic read failed'); return { code: 0, data: rows } } } })
}
test('D39 entirely unknown quantity totals remain unknown, and never say extra zero', async () => {
  const t = barrels([{ id: 1, returnBucketQty: null, barrelDiscrepancy: null }]); await t.p.loadData()
  assert.equal(t.p.data.totalReturnText, '未提供'); assert.equal(t.p.data.totalShortText, '未提供'); assert.equal(t.p.data.totalExtraText, '未提供')
  const texts = view('barrel-records', t.p.data).map(n => n.text || '').join('|')
  assert(texts.includes('回收数量未提供')); assert(!texts.includes('多回 0'))
})
test('D39 known zero and opposite differences keep separate known totals', async () => {
  const t = barrels([{ id: 1, returnBucketQty: 0, barrelDiscrepancy: 0 }, { id: 2, returnBucketQty: 2, barrelDiscrepancy: 3 }, { id: 3, returnBucketQty: null, barrelDiscrepancy: -3 }]); await t.p.loadData()
  assert.equal(t.p.data.totalReturnText, '2'); assert.equal(t.p.data.totalShortText, '3'); assert.equal(t.p.data.totalExtraText, '3')
  assert.equal(t.p.data.totalDiscrepancy, 0); assert.equal(t.p.data.unknownReturn, 1); assert(!t.p.data.records[0].hasDiscrepancy)
})
test('D39 failed reads are an error rather than a successful empty list', async () => {
  const t = barrels([], true); await t.p.loadData(); assert(!t.p.data.loaded); assert(t.p.data.error)
  assert(!view('barrel-records', t.p.data).some(n => n.text === '暂无空桶回收记录'))
})
function station(shared) {
  const wx = shared ? shared.wx : createWx(), app = shared ? shared.app : actor(), writes = []
  let pick
  wx.chooseLocation = options => { pick = options }
  const t = mount('station-mgmt/station-info', {
    'config/api': { getTransportConfig: () => ({ mode: 'https', baseUrl: 'https://synthetic.invalid' }) },
    'api/station-mgmt': { getMyStation: async () => ({ code: 0, data: { id: 3, name: '原站名', phone: '123', address: '', status: 1, lat: null, lng: null } }),
      updateStationProfile: async (id, body) => { writes.push(['profile', id, body]); return { code: 0, data: { id, name: body.name || '原站名', phone: body.phone || '123', address: body.address, lat: body.coordinateMode === 'SET' ? body.lat : null, lng: body.coordinateMode === 'SET' ? body.lng : null } } } }
  }, wx, app)
  t.p.refreshModuleSetup = () => {}; return { ...t, writes, pick: () => pick }
}
test('D24 map stages position and one profile save persists address and details together', async () => {
  const t = station(); await t.p.onShow(); t.p.onNameInput({ detail: { value: '未保存的新站名' } }); t.p.onPhoneInput({ detail: { value: '999' } })
  t.p.onPickCoordinates(); await t.pick().success({ latitude: 20, longitude: 110, name: '选点', address: '地址' })
  assert.equal(t.writes.length, 0); assert.equal(t.p.data.form.name, '未保存的新站名'); assert.equal(t.p.data.form.phone, '999')
  assert.equal(t.p.data.form.address, '地址 选点'); assert(t.p.data.infoDirty)
  await t.p.onSave(); assert.equal(t.writes.length, 1); assert.equal(t.writes[0][0], 'profile')
  assert.equal(t.writes[0][2].coordinateMode, 'SET'); assert.equal(t.writes[0][2].lat, 20); assert.equal(t.writes[0][2].lng, 110)
})
test('D24 old drafts require explicit restoration and survive page destruction', async () => {
  const t = station(); await t.p.onShow(); t.p.onNameInput({ detail: { value: '保留的站名' } }); t.p.onUnload()
  const next = station(t); await next.p.onShow(); assert.equal(next.p.data.form.name, '原站名'); assert(next.p.data.infoDraftAvailable)
  next.p.onRestoreInfoDraft(); assert.equal(next.p.data.form.name, '保留的站名'); assert(next.p.data.infoDirty); assert.equal(next.writes.length, 0)
})
test('D24 map cancel and late response after account switch save nothing', async () => {
  const t = station(); await t.p.onShow(); t.p.onPickCoordinates(); t.pick().fail({ errMsg: 'chooseLocation:fail cancel' }); assert.equal(t.writes.length, 0)
  t.p.onPickCoordinates(); const old = t.pick(); t.app.globalData.userInfo.staffId = 66
  await old.success({ latitude: 20, longitude: 110, address: 'Late' }); assert.equal(t.writes.length, 0)
})
test('D24 storage failure is visible and never promises a safely saved draft', async () => {
  const t = station(); await t.p.onShow(); t.wx.setStorageSync = () => { throw Error('synthetic storage unavailable') }
  t.p.onNameInput({ detail: { value: '输入' } }); assert(t.p.data.infoDraftStatus.includes('尚未确认保存'))
})
function exceptions() {
  const modals = [], calls = []
  const t = mount('station-mgmt/exceptions', { 'utils/business-dialog': { showBusinessModal: options => modals.push(options) },
    'utils/request': { get: async () => ({ code: 0, data: { suggestedCashAmount: '10.00', suggestedTicketQty: 2, adjustProductId: 7 } }), post: async (url, body) => { calls.push([url, body]); return { code: 0 } } } })
  t.p.load = () => {}; return { ...t, modals, calls }
}
const turn = () => new Promise(resolve => setImmediate(resolve))
test('D34 ignore is single flight, requires reason, and cancel writes nothing', async () => {
  const t = exceptions(); const first = t.p.onIgnore(tap(1)); await t.p.onIgnore(tap(1)); assert.equal(t.modals.length, 1)
  t.modals[0].success({ confirm: false }); await first; assert.equal(t.calls.length, 0)
  const second = t.p.onIgnore(tap(1)); t.modals[1].success({ confirm: true, content: '核对无异常' }); t.modals[1].success({ confirm: true, content: '重复回调' }); await second
  assert.equal(t.calls.length, 1); assert.equal(t.calls[0][1].managerNote, '核对无异常')
})
test('D34 approval has one confirmation with real destination, and keeps handle then execute', async () => {
  const t = exceptions(); const pending = t.p.onApproveSuggested(tap(1)); await turn(); assert.equal(t.modals.length, 1)
  const modal = t.modals[0]; assert(modal.details.some(row => row.value.includes('增加押金账户余额'))); assert.equal(modal.initialValue, '')
  modal.success({ confirm: true, content: '已核对' }); await pending
  assert.equal(t.calls.length, 2); assert(t.calls[0][0].endsWith('/handle')); assert(t.calls[1][0].endsWith('/execute'))
})
test('D34 note draft is scoped to the original exception and late confirmations cannot write', async () => {
  const t = exceptions(); t.p._exceptionApprovalKey = JSON.stringify([t.p.exceptionContext(), '1']); t.p._exceptionApprovalNote = '属于第一单'
  const pending = t.p.onApproveSuggested(tap(2)); await turn(); assert.equal(t.modals[0].initialValue, '')
  t.p.onHide(); t.modals[0].success({ confirm: true, content: '迟到' }); await pending; assert.equal(t.calls.length, 0)
})
test('D34 v1 unsupported editable fallback cancels and cannot send a business command', async () => {
  const calls = []
  const t = mount('station-mgmt/exceptions', { 'utils/request': { get: async () => ({ data: { suggestedCashAmount: 10 } }), post: async (...args) => calls.push(args) } })
  t.p.load = () => {}; await t.p.onApproveSuggested(tap(1)); assert.equal(calls.length, 0); assert.equal(t.wx.__calls.modal.length, 0)
})
function inter() {
  const modals = [], calls = [], app = actor(); app.circular = app
  const t = mount('station-mgmt/inter-station', { 'utils/business-dialog': { showBusinessModal: options => modals.push(options) }, 'api/station-mgmt': { settleInterStation: async (...args) => { calls.push(args); return { code: 0 } } } }, createWx(), app)
  t.p._ledgerSeq = 1; t.p._ledgerReadSession = t.p.aftercareSession(); t.p.setData({ loaded: true, loading: false, items: [{ orderId: 5, canSettle: true, amount: '0.00', _amountText: '0.00', fromStationId: 3, toStationId: 4 }] }); t.p.loadData = async () => {}
  return { ...t, modals, calls }
}
test('D28 one v1 confirmation preserves zero and ignores duplicate callbacks', async () => {
  const t = inter(); t.p.onSettle(tap(5)); assert.equal(t.modals.length, 1); assert(t.modals[0].details.some(row => row.value === '¥0.00'))
  await t.modals[0].success({ confirm: true, content: '凭据' }); await t.modals[0].success({ confirm: true, content: '重复' })
  assert.equal(t.calls.length, 1); assert.deepEqual(t.calls[0], [5, '凭据'])
})
test('D28 cancel or leaving before confirmation produces no settlement', async () => {
  for (const leave of [false, true]) {
    const t = inter(); t.p.onSettle(tap(5)); if (leave) t.p.onHide()
    await t.modals[0].success({ confirm: leave, content: '原输入' }); assert.equal(t.calls.length, 0)
  }
})
test('D28 refreshed error, changed identity or changed amount invalidates an open v1 confirmation', async () => {
  for (const change of [t => t.p.setData({ ledgerError: '核对失败' }), t => { t.app.globalData.userInfo.staffId = 99 }, t => { t.p.data.items[0].amount = '1.00' }, t => { t.p._ledgerSeq++ }]) {
    const t = inter(); t.p.onSettle(tap(5)); assert.equal(t.modals.length, 1); change(t)
    await t.modals[0].success({ confirm: true, content: '凭据' }); assert.equal(t.calls.length, 0)
  }
})
test('D28 absent or malformed amounts never open a v1 confirmation', () => {
  for (const amount of [null, '', ' ', 'unknown', undefined]) { const t = inter(); t.p.data.items[0].amount = amount; t.p.onSettle(tap(5)); assert.equal(t.modals.length, 0); assert.equal(t.calls.length, 0) }
})
function payroll(shared) {
  const writes = []; const t = mount('station-mgmt/payroll', { 'config/api': { getTransportConfig: () => ({ mode: 'https', baseUrl: 'https://synthetic.invalid' }) }, 'utils/request': { get: async () => ({ data: [] }), post: async (url, body) => { writes.push([url, body]); return { code: 0 } } } }, shared && shared.wx, shared && shared.app)
  t.p.setData({ stationId: 3, isManager: true, loading: false }); t.p.loadAll = async () => {}; t.p.loadPreview = async () => {}; return { ...t, writes }
}
test('D29 positive input plus chosen deduction preserves the existing signed amount contract', async () => {
  const t = payroll(); t.p.setData({ 'adjustForm.staffId': 7 }); t.p.onAdjustDirection(tap(null, { direction: 'deduct' })); t.p.onAdjustMagnitude({ detail: { value: '12.50' } })
  assert.equal(t.p.data.adjustForm.amount, '-12.50'); await t.p.onAdjust(); assert.equal(t.writes[0][1].amount, -12.5)
})
test('D29 product rate switches and record close preserve separate page drafts without writes', () => {
  const t = payroll(); t.p.onRateProduct(tap(7)); t.p.onRateInput(input('perBucketAmount', '1.23')); t.p.onRateProduct(tap(8)); assert.equal(t.p.data.rateForm.perBucketAmount, '')
  t.p.onRateProduct(tap(7)); assert.equal(t.p.data.rateForm.perBucketAmount, '1.23')
  t.p.setData({ items: [{ id: 9, name: '补贴', enabled: true }], record: { itemId: 9, staffId: 7, amount: '8', note: '草稿' } }); t.p.onRecordClose(); t.p.onRecordOpen(tap(9)); assert.equal(t.p.data.record.note, '草稿'); assert.equal(t.writes.length, 0)
})
test('D29 durable input draft needs explicit restoration and keeps zero strings', () => {
  const t = payroll(); t.p.setData({ 'adjustForm.staffId': 7 }); t.p.onAdjustMagnitude({ detail: { value: '0' } })
  const next = payroll(t)
  next.p.setData({ stationId: 3, isManager: true, loading: false }); next.p.offerPayrollDraft()
  assert(next.p.data.payrollDraftAvailable, JSON.stringify({ first: t.p.data.payrollDraftStatus, next: next.p.data.payrollDraftStatus, keys: [...t.wx.__storage.keys()] })); assert.equal(next.p.data.adjustForm.amount, '')
  next.p.onRestorePayrollDraft(); assert.equal(next.p.data.adjustForm.amount, '0'); assert.equal(next.p.data.adjustMagnitude, '0')
})
test('D29 original unknown request takes precedence over an ordinary input draft', () => {
  const t = payroll(); t.p.setData({ 'adjustForm.staffId': 7 }); t.p.onAdjustMagnitude({ detail: { value: '12' } })
  const intent = require(path.join(ROOT, 'miniapp-delivery/utils/payroll-adjust-intent'))
  intent.prepare('3:8', { staffId: 7, amount: -5, note: '原请求' }); t.p.restoreAdjustmentIntent(); t.p.offerPayrollDraft()
  assert.equal(t.p.data.adjustForm.amount, '-5'); assert.equal(t.p.data.payrollDraftAvailable, false)
})
test('D30 direct edit fills existing values and cancelling replacement preserves new input', () => {
  const t = mount('station-mgmt/ticket-packages'); t.p.canMutateTiers = () => true; t.wx.pageScrollTo = () => {}
  t.p.setData({ packages: [{ id: 1, qty: 10, price: '99', title: '十张', sort: 2 }], isUnified: false }); t.p.onEditTier(tap(1)); assert.equal(t.p.data.form.qty, '10'); assert.equal(t.p.data.form.price, '99')
  t.p.onInput(input('price', '88')); t.wx.__modalAutoConfirm = false; t.p.onEditTier(tap(1)); assert.equal(t.p.data.form.price, '88')
})
test('D14 category changes retain local handling drafts and unknown counts stay explicit', () => {
  const t = mount('station-mgmt/business-waiting'); t.p.setData({ refusalReason: '尚未提交', ready: {}, counts: {} }); t.p.onWaitingSection(tap(null, { key: 'returns' })); assert.equal(t.p.data.refusalReason, '尚未提交')
  const texts = view('station-mgmt/business-waiting', t.p.data).map(n => n.text || '').join('|'); assert(texts.includes('待核实'))
})
test('D15 analysis expansion keeps the report and redraws charts only when opened', () => {
  const t = mount('station-mgmt/dashboard'); let charts = 0; const report = { hasData: true, range: 'today' }; t.p.setData({ report }); t.p.prepareCharts = r => { assert.equal(r, report); charts++ }
  t.p.onToggleAnalysis(); t.p.onToggleAnalysis(); assert.equal(charts, 1); assert.equal(t.p.data.report, report)
})
test('D25 status summaries are read-only and retain a single navigation strip', () => {
  const source = tree('station-mgmt/delivery-config'); validateConditionalSiblings(source)
  const t = mount('station-mgmt/delivery-config'); t.p.setData({ configReady: true, loading: false, configured: true, ruleSummaries: [{ key: 'minOrder', label: '起送条件', text: '不限', stateText: '已保存' }] })
  const nodes = renderElements(source, t.p.data, { includeText: true }), summary = nodes.find(n => n.attrs.class === 'fee-rule-summary')
  assert.equal(summary.tag, 'view'); assert(!summary.attrs.bindtap); assert.equal(nodes.filter(n => n.attrs.class === 'fee-section-nav').length, 1)
})
test('D31 missing help never falls back to quantity times current purchase price', () => {
  const t = mount('station-mgmt/gross-profit'); t.p.onShowHelp(); const note = t.wx.__calls.modal.at(-1).content; assert(!note.includes('卖出数量 × 当前')); assert(note.includes('成本不全'))
})
test('D36 customer precedes amount and historical status does not use pending coloring', () => {
  const t = mount('station-mgmt/payments'); const nodes = view('station-mgmt/payments', { ...t.p.data, view: 'ticketHistory', list: [{ id: 1, customerName: '长客户名', amountText: '0.00', statusText: '已确认' }] })
  assert(nodes.findIndex(n => n.attrs.class === 'payment-person') < nodes.findIndex(n => n.attrs.class === 'amount'))
  assert(nodes.some(n => n.attrs.class === 'status history-status')); assert(!nodes.some(n => n.attrs.class === 'status pending'))
})
test('D40 period and all-time unsettled ranges remain explicitly separate', () => {
  const t = mount('my-earnings'), texts = view('my-earnings', { ...t.p.data, unsettledTotalText: '-12.00', periodTotalText: '0.00' }).map(n => n.text || '').join('|')
  assert(texts.includes('全部未结记录')); assert(texts.includes('-12.00')); assert(texts.includes('区间收益'))
})
test('D32 progress expansion only changes visibility and keeps current refund facts', () => {
  const t = mount('station-mgmt/barrel-return'); const record = { id: 1, refundProgress: { paid: 0, remaining: 10 } }; t.p.setData({ selected: record }); t.p.onToggleReturnProgress(); assert.equal(t.p.data.selected, record); assert(t.p.data.returnProgressExpanded)
})
;(async () => { const done = armWatchdog(); let failed = 0; try {
  for (const t of tests) { try { await t.run(); console.log('PASS ' + t.name) } catch (e) { failed++; console.error('FAIL ' + t.name + '\n' + e.stack) } }
  if (failed) process.exitCode = 1; else console.log('AQUAFLOW_SUITE_OK ' + tests.length)
} finally { done() } })()
