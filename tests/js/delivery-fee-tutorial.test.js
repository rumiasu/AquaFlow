'use strict'
const assert = require('node:assert/strict'), fs = require('node:fs'), path = require('node:path')
const { loadPage, createWx, createApp, armWatchdog } = require('./harness')
const { parseWxml, renderElements, validateConditionalSiblings } = require('./wxml-tree')
const PAGE = 'miniapp-delivery/pages/station-mgmt/delivery-config/index'
const defaults = () => ({ minOrderBuckets: null, minOrderAmount: null, minOrderMode: 'WARN', minOrderFee: 0,
  deliveryRadiusM: null, overRadiusMode: 'WARN', remoteFee: 0, baseDeliveryFee: 0,
  freeDeliveryBuckets: null, freeDeliveryAmount: null, floorFreeLevel: 1, floorFeePerLevel: 0, floorFeeMode: 'PER_ORDER' })
const ev = (field, value) => ({ currentTarget: { dataset: { field, value } }, detail: { value } })
const section = key => ({ currentTarget: { dataset: { section: key } } })
function fixture(options = {}) {
  const wx = options.wx || createWx(), guards = [], calls = [], state = { config: defaults(), configured: false, ...options.state }
  wx.enableAlertBeforeUnload = o => guards.push({ enabled: true, ...o })
  wx.disableAlertBeforeUnload = () => guards.push({ enabled: false })
  wx.stopPullDownRefresh = () => {}
  const app = options.app || createApp({ _loginGeneration: 1, globalData: { isLogin: true, accessToken: 'synthetic',
    userInfo: { staffId: 9, stationId: 17, role: 'STATION_MANAGER', bindStatus: 'BOUND' } } })
  const p = loadPage(PAGE + '.js', { wx, app, stubs: { 'utils/request': {
    get: async url => {
      calls.push({ method: 'GET', url })
      if (url.includes('setup-guide')) return { code: 0, data: { items: [{ key: 'deliveryFee', label: '配送计费', level: 'P0', done: state.configured, route: '/pages/station-mgmt/delivery-config/index' }] } }
      if (state.readFail) throw Error('synthetic read failure')
      return { code: 0, data: { config: { ...state.config }, configured: state.configured, guidance: state.guidance } }
    },
    put: async (url, body) => {
      calls.push({ method: 'PUT', url, body })
      if (state.putPending) return state.putPending
      if (state.putFail) throw Error('synthetic save failure')
      if (state.rejectCode) return { code: 1, message: 'synthetic rejected' }
      if (!state.stale) { state.config = { ...body, floorFreeLevel: body.floorFreeLevel == null ? 1 : body.floorFreeLevel }; state.configured = true }
      if (state.failReadAfterPut) state.readFail = true
      return { code: 0 }
    }
  } } })
  const targets = []; p.scrollToFeeTarget = s => targets.push(s)
  return { p, wx, app, calls, state, guards, targets, puts: () => calls.filter(c => c.method === 'PUT') }
}
const cases = [], test = (name, run) => cases.push({ name, run })
const readMarkup = () => parseWxml(fs.readFileSync(path.resolve(__dirname, '../..', PAGE + '.wxml'), 'utf8'))

test('four tutorial tasks live in launch preparation with known default meanings', async () => {
  const t = fixture(); await t.p.load(); const tree = readMarkup(); validateConditionalSiblings(tree)
  const nodes = renderElements(tree, t.p.data, { includeText: true })
  assert.equal(nodes.filter(n => n.attrs.class === 'fee-rule-summary').length, 4)
  assert(nodes.some(n => n.text === '上线准备')); assert(!nodes.some(n => n.text === '本页规则'))
  assert.equal(t.p.data.form.minOrderBuckets, '0'); assert.equal(t.p.data.thresholdEnabled.minOrderBuckets, false)
  assert.equal(t.p.data.form.freeDeliveryBuckets, ''); assert.equal(t.p.data.form.freeDeliveryAmount, '')
  assert(t.p.data.ruleSummaries.every(s => s.stateText === '默认值待确认'))
  assert.equal(t.puts().length, 0); assert(t.guards.at(-1).enabled)
})
test('multi-digit typing stays put and explicit completion retains text and expands next section', async () => {
  const t = fixture(); await t.p.load()
  for (const text of ['1', '12', '120']) { t.p.onInput(ev('minOrderBuckets', text)); assert.equal(t.p.data.expandedSection, 'minOrder') }
  t.p.onFeeFieldConfirm(ev('minOrderBuckets', '120'))
  assert.equal(t.p.data.expandedSection, 'range'); assert.equal(t.p.data.form.minOrderBuckets, '120')
  assert.equal(t.p.data.configured, false); assert.equal(t.puts().length, 0)
})
test('completion validates the whole section and never jumps on partial or invalid input', async () => {
  for (const value of ['', '1.', '1.5', '-1', 'Infinity', '999999999999999999999']) {
    const t = fixture(); await t.p.load(); t.p.onInput(ev('minOrderBuckets', value)); t.p.onCompleteSection(section('minOrder'))
    assert.equal(t.p.data.expandedSection, 'minOrder'); assert.equal(t.p.data.fieldError, 'minOrderBuckets'); assert.equal(t.puts().length, 0)
  }
  const t = fixture(); await t.p.load(); t.p.onPickMode(ev('minOrderMode', 'FEE')); t.p.onCompleteSection(section('minOrder'))
  assert.equal(t.p.data.fieldError, 'minOrderFee'); assert.equal(t.p.data.expandedSection, 'minOrder')
})
test('unlimited selector keeps numeric draft and never marks setup configured', async () => {
  const t = fixture(); await t.p.load(); t.p.onInput(ev('minOrderBuckets', '12'))
  for (const enabled of ['off', 'on', 'off']) t.p.onThresholdToggle({ currentTarget: { dataset: { field: 'minOrderBuckets', enabled } } })
  assert.equal(t.p.data.form.minOrderBuckets, '12'); assert.equal(t.p.data.configured, false); assert.equal(t.puts().length, 0)
  await t.p.onSave(); assert.equal(t.puts()[0].body.minOrderBuckets, null)
  assert.equal(t.p.data.form.minOrderBuckets, '12'); assert.equal(t.p.data.hasUnsavedChanges, false)
})
test('zero free-delivery threshold stays effective while disabled threshold sends null', async () => {
  const t = fixture(); await t.p.load(); t.p.onInput(ev('freeDeliveryBuckets', '0')); await t.p.onSave()
  assert.equal(t.puts()[0].body.freeDeliveryBuckets, 0); assert.equal(t.puts()[0].body.freeDeliveryAmount, null)
  assert(t.p.data.ruleSummaries[2].text.includes('所有桶装订单都达到'))
})
test('one-click suggestion replaces the disabled zero placeholder, activates its field and still needs save', async () => {
  const t=fixture({state:{configured:true,config:{...defaults(),minOrderBuckets:2,baseDeliveryFee:5},guidance:{nonBarrelOnShelf:true,requiredAmountFields:['minOrderAmount'],cheapestWaterPrice:10,cheapestPrice:10,cheapestPriceBasis:'本站已上架桶装水最低价',suggestedMinOrderAmount:20}}})
  await t.p.load(); assert.equal(t.p.data.form.minOrderAmount,'0'); assert.equal(t.p.data.thresholdEnabled.minOrderAmount,false)
  t.p.onFillSuggested(); assert.equal(t.p.data.form.minOrderAmount,'20'); assert.equal(t.p.data.thresholdEnabled.minOrderAmount,true)
  assert.equal(t.puts().length,0); assert.equal(t.state.config.minOrderAmount,null)
  await t.p.onSave(); assert.equal(t.puts()[0].body.minOrderAmount,20); assert.equal(t.puts()[0].body.baseDeliveryFee,5)
})
test('saved unlimited amounts remain null while explicit zero thresholds survive reload and save', async () => {
  const unlimited=fixture({state:{configured:true,config:{...defaults(),minOrderBuckets:2,baseDeliveryFee:5}}})
  await unlimited.p.load(); await unlimited.p.onSave()
  assert.equal(unlimited.puts()[0].body.minOrderAmount,null); assert.equal(unlimited.puts()[0].body.minOrderMode,'WARN')
  assert.equal(unlimited.p.data.configured,true); assert.equal(unlimited.p.data.hasUnsavedChanges,false)
  const zero=fixture({state:{configured:true,config:{...defaults(),minOrderBuckets:0,minOrderAmount:0}}})
  await zero.p.load(); assert.equal(zero.p.data.thresholdEnabled.minOrderBuckets,true); assert.equal(zero.p.data.thresholdEnabled.minOrderAmount,true)
  await zero.p.onSave(); assert.equal(zero.puts()[0].body.minOrderBuckets,0); assert.equal(zero.puts()[0].body.minOrderAmount,0)
})
test('four explicit default completions still require one manual save', async () => {
  const t = fixture(); await t.p.load()
  for (const key of ['minOrder', 'range', 'delivery', 'floor']) t.p.onCompleteSection(section(key))
  assert.equal(Object.values(t.p.data.completedSections).filter(Boolean).length, 4)
  assert.equal(t.p.data.configured, false); assert.equal(t.puts().length, 0)
  await t.p.onSave(); assert.equal(t.puts().length, 1); assert.equal(t.p.data.configured, true)
  assert(t.p.data.ruleSummaries.every(s => s.stateText === '已保存')); assert.equal(t.guards.at(-1).enabled, false)
})

test('reviewing the last section alone never claims all four were reviewed', async () => {
  const t = fixture(); await t.p.load(); t.p.onCompleteSection(section('floor'))
  assert.equal(t.p.data.completedSections.floor, true)
  assert(!t.p.data.draftNotice.includes('四项已核对')); assert(t.p.data.draftNotice.includes('其余项目'))
  assert.equal(t.puts().length, 0); assert.equal(t.p.data.configured, false)
})
test('successful changed save requires matching readback then removes local draft', async () => {
  const t = fixture(); await t.p.load(); t.p.onInput(ev('baseDeliveryFee', '3.50')); await t.p.onSave()
  assert.equal(t.p.data.hasUnsavedChanges, false); assert.equal(t.p.data.saveError, '')
  assert.equal(t.wx.getStorageSync(t.p.feeDraftKey()), ''); assert.equal(t.p.data.form.baseDeliveryFee, '3.5')
})
test('failed save retains full draft, pending launch state and exit guard', async () => {
  const t = fixture({ state: { putFail: true } }); await t.p.load(); t.p.onInput(ev('baseDeliveryFee', '3.50')); await t.p.onSave()
  assert.equal(t.p.data.form.baseDeliveryFee, '3.50'); assert.equal(t.p.data.configured, false)
  assert(t.p.data.saveError); assert(t.guards.at(-1).enabled); assert(t.wx.getStorageSync(t.p.feeDraftKey()).form)
})
test('nonzero business receipt never reads back as saved', async () => {
  const t = fixture({ state: { rejectCode: true } }); await t.p.load(); await t.p.onSave()
  assert.equal(t.calls.filter(c => c.method === 'GET' && c.url.includes('delivery-config')).length, 1)
  assert.equal(t.p.data.configured, false); assert.equal(t.p.data.saveError, 'synthetic rejected')
})
test('failed readback after accepted save keeps the visible editable draft and pending state', async () => {
  const t = fixture({ state: { failReadAfterPut: true } }); await t.p.load(); t.p.onInput(ev('baseDeliveryFee', '8.50')); await t.p.onSave()
  assert.equal(t.p.data.form.baseDeliveryFee, '8.50'); assert.equal(t.p.data.configReady, true); assert.equal(t.p.data.configError, '')
  assert(t.p.data.saveError.includes('回读未能确认')); assert(t.p.data.hasUnsavedChanges); assert(t.guards.at(-1).enabled)
})
test('mismatched readback cannot pretend the submitted values were saved', async () => {
  const t = fixture({ state: { stale: true, configured: true } }); await t.p.load(); t.p.onInput(ev('baseDeliveryFee', '9')); await t.p.onSave()
  assert(t.p.data.saveError.includes('不一致')); assert.equal(t.p.data.form.baseDeliveryFee, '9'); assert(t.p.data.hasUnsavedChanges)
})
test('hide, show and pull preserve input, mode, unlimited state and selected section', async () => {
  const t = fixture({ state: { configured: true } }); await t.p.load(); t.p.onInput(ev('remoteFee', '6.25'))
  t.p.onPickMode(ev('overRadiusMode', 'FEE')); t.p.onInput(ev('minOrderBuckets', '8')); t.p.onThresholdToggle({ currentTarget: { dataset: { field: 'minOrderBuckets', enabled: 'off' } } })
  t.p.onSectionTap(section('range')); const before = JSON.stringify([t.p.data.form, t.p.data.thresholdEnabled]); t.p.onHide(); await t.p.onShow(); await t.p.onPullDownRefresh()
  assert.equal(JSON.stringify([t.p.data.form, t.p.data.thresholdEnabled]), before); assert.equal(t.p.data.expandedSection, 'range')
})
test('cancelled exit preserves every field and makes no navigation', async () => {
  const t = fixture(); await t.p.load(); t.p.onInput(ev('baseDeliveryFee', '4.25')); t.wx.__modalAutoConfirm = false
  const before = JSON.stringify(t.p.data.form); t.p.onExitFee(); assert.equal(JSON.stringify(t.p.data.form), before)
  assert.equal(t.wx.__calls.nav.length, 0); assert.equal(t.wx.__calls.modal.length, 1); assert(t.guards.at(-1).enabled)
})
test('confirmed exit and reentry restore only the same staff and station draft', async () => {
  const t = fixture(); await t.p.load(); t.p.onInput(ev('baseDeliveryFee', '4.25')); t.p.onSectionTap(section('floor')); t.p.onExitFee(); t.p.onUnload()
  assert.equal(t.wx.__calls.nav.at(-1).type, 'navigateBack')
  const again = fixture({ wx: t.wx, app: t.app }); await again.p.load()
  assert.equal(again.p.data.form.baseDeliveryFee, '4.25'); assert.equal(again.p.data.expandedSection, 'floor'); assert(again.p.data.hasUnsavedChanges)
  again.app.globalData.userInfo.stationId = 18; const other = fixture({ wx: t.wx, app: again.app }); await other.p.load()
  assert.equal(other.p.data.form.baseDeliveryFee, '0'); assert.equal(other.p.data.hasUnsavedChanges, false)
})
test('return navigation failure rearms unsaved guard', async () => {
  const t = fixture(); await t.p.load(); t.wx.navigateBack = o => o.fail(); t.p.onExitFee(); assert(t.guards.at(-1).enabled)
})
test('local storage failure is visible and cannot count as server save', async () => {
  const t = fixture(); await t.p.load(); t.wx.setStorageSync = () => { throw Error('synthetic full') }; t.p.onInput(ev('baseDeliveryFee', '7'))
  assert(t.p.data.draftStorageError.includes('暂存失败')); assert.equal(t.p.data.configured, false); assert.equal(t.puts().length, 0)
})
test('changed saved baseline is called out when restoring older local draft', async () => {
  const t = fixture({ state: { configured: true } }); await t.p.load(); t.p.onInput(ev('baseDeliveryFee', '4')); t.p.onUnload()
  const again = fixture({ wx: t.wx, app: t.app, state: { configured: true, config: { ...defaults(), baseDeliveryFee: 2 } } }); await again.p.load()
  assert.equal(again.p.data.form.baseDeliveryFee, '4'); assert(again.p.data.draftNotice.includes('已保存规则有变化'))
})
test('late save completion after unload or login generation change does not repaint', async () => {
  for (const kind of ['unload', 'login']) {
    let resolve; const pending = new Promise(r => { resolve = r }), t = fixture({ state: { putPending: pending } }); await t.p.load()
    const saving = t.p.onSave(); if (kind === 'unload') t.p.onUnload(); else t.app._loginGeneration++
    const before = JSON.stringify(t.p.data); resolve({ code: 0 }); await saving; assert.equal(JSON.stringify(t.p.data), before)
    assert.equal(t.wx.__calls.toast.filter(o => o.icon === 'success').length, 0)
  }
})
test('each editing indicator is nested inside its title row and threshold input stays left of selector', () => {
  const tree = readMarkup(), sections = [], walk = n => { if (n.attrs && n.attrs.class === 'card-title-row fee-section-heading') {
    const buttons = n.children.filter(c => c.tag === 'button' && c.attrs.bindtap === 'onSectionTap')
    assert.equal(buttons.length, 1)
    const button = buttons[0], section = button.attrs['data-section']
    assert(['minOrder', 'range', 'delivery', 'floor'].includes(section))
    sections.push(section)
    assert((button.attrs.class || '').split(/\s+/).includes('b3-on-section-tap'))
    const text = JSON.stringify(button)
    assert(text.includes('expandedSection') && text.includes(section) && text.includes('正在编辑') && text.includes('展开编辑'))
  } if (n.attrs && n.attrs.class === 'fee-threshold-row') {
    const children = n.children.filter(c => c.tag !== '#text'); assert.equal(children[0].tag, 'input'); assert.equal(children[1].attrs.bindtap, 'onThresholdToggle')
  } for (const c of n.children || []) walk(c) }; walk(tree)
  assert.deepEqual(sections.sort(), ['delivery', 'floor', 'minOrder', 'range'])
})
module.exports = { fixture, ev, section, defaults }
if (require.main === module) (async () => { const done = armWatchdog(); let failed = 0
  try { for (const t of cases) { try { await t.run(); console.log('PASS ' + t.name) } catch (e) { failed++; console.error('FAIL ' + t.name + ': ' + e.stack) } }
    if (failed) process.exitCode = 1; else console.log('AQUAFLOW_SUITE_OK ' + cases.length)
  } finally { done() }
})()
