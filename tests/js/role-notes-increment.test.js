'use strict'
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { loadPage, createApp, createWx, armWatchdog, ROOT } = require('./harness')
const { parseWxml, renderElements, validateConditionalSiblings } = require('./wxml-tree')
const tests = []
const test = (name, run) => tests.push({ name, run })
const source = rel => fs.readFileSync(path.join(ROOT, rel), 'utf8')
const tree = rel => parseWxml(source(rel))
function nodes(root, predicate) {
  const found = []
  const visit = n => { if (predicate(n)) found.push(n); (n.children || []).forEach(visit) }
  visit(root)
  return found
}
const hasClass = (n, name) => !!n.attrs && (n.attrs.class || '').split(/\s+/).includes(name)
function rendered(root, data) { return renderElements(root, data, { includeText: true }) }
function notePage() {
  const wx = createWx(), app = createApp()
  const page = loadPage('miniapp-user/pages/order/create.js', { wx, app })
  return { page, wx, app, tap: id => page.onQuickNoteTap({ currentTarget: { dataset: { id } } }) }
}
const edit = (page, value) => page.onNoteInput({ detail: { value } })
const selected = page => page.data.quickNotes.filter(n => n.selected).map(n => n.label)

test('three controlled chips toggle the same editable note without duplicate appends', () => {
  const t = notePage()
  assert.deepEqual(selected(t.page), [])
  t.tap(0); assert.equal(t.page.data.note, '放门口')
  t.tap(1); assert.equal(t.page.data.note, '放门口；到前电话')
  t.tap(2); assert.deepEqual(selected(t.page), ['放门口', '到前电话', '请勿按门铃'])
  t.tap(0); t.tap(1); t.tap(2)
  assert.equal(t.page.data.note, '')
  assert.deepEqual(selected(t.page), [])
})
test('free text and whitespace survive appending and cancelling a phrase', () => {
  const t = notePage(), original = '  侧门第二栋，找张先生  '
  edit(t.page, original); t.tap(0); t.tap(0)
  assert.equal(t.page.data.note, original)
  assert.equal(t.wx.__calls.nav.length, 0)
  assert.equal(t.wx.__calls.request.length, 0)
})
test('editing resynchronizes whole-clause selection; negative phrases are not selected', () => {
  const t = notePage()
  edit(t.page, '不要放门口；到前电话; 请勿按门铃 ')
  assert.deepEqual(selected(t.page), ['到前电话', '请勿按门铃'])
  t.tap(1); assert.equal(t.page.data.note, '不要放门口; 请勿按门铃 ')
  edit(t.page, '自由文字')
  assert.deepEqual(selected(t.page), [])
})
test('manual duplicate phrases are removed without changing the other clauses', () => {
  const t = notePage()
  edit(t.page, ' 放门口 ；右侧门;放门口；请先等候')
  t.tap('0')
  assert.equal(t.page.data.note, '右侧门；请先等候')
  assert.deepEqual(selected(t.page), [])
})
test('untrusted chip IDs cannot insert arbitrary note text', () => {
  const t = notePage(); edit(t.page, '原文')
  for (const id of ['3', '-1', '0.0', '00', '__proto__', null, undefined]) t.tap(id)
  assert.equal(t.page.data.note, '原文')
})
test('note additions respect the existing input budget without truncating free text', () => {
  const t = notePage(), original = '长'.repeat(138)
  edit(t.page, original); t.tap(0)
  assert.equal(t.page.data.note, original)
  assert.equal(t.wx.__calls.toast.at(-1).title, '备注已满，请先编辑')
  edit(t.page, '长'.repeat(145) + '；放门口'); t.tap(0)
  assert.equal(t.page.data.note, '长'.repeat(145))
})
test('chips do not edit an in-flight, uncertain, known-created, hidden or destroyed order', () => {
  for (const state of [{ submitting: true }, { submitState: 'creating' }, { submitState: 'unknown' }, { pendingOrderId: 73 }, { _orderNavigationHidden: true }, { _quoteDestroyed: true }]) {
    const t = notePage(); edit(t.page, '原文')
    Object.keys(state).forEach(k => k[0] === '_' ? t.page[k] = state[k] : t.page.data[k] = state[k])
    t.tap(0); assert.equal(t.page.data.note, '原文')
  }
})
test('route-provided notes remain editable and initialize the chips', () => {
  const t = notePage()
  for (const name of ['_restorePendingOrderFromStorage', '_restoreOrderRequest', 'loadItemsProducts', 'loadAddress', 'loadBarrel']) t.page[name] = () => {}
  t.page.onLoad({ stationId: '11', productId: '12', specialNote: encodeURIComponent('侧门；到前电话') })
  assert.equal(t.page.data.note, '侧门；到前电话')
  assert.deepEqual(selected(t.page), ['到前电话'])
  t.tap(1); assert.equal(t.page.data.note, '侧门')
})
test('reorder-provided notes initialize selection while preserving items and station', async () => {
  const t = notePage()
  for (const name of ['loadItemsProducts', 'loadAddress', 'loadBarrel']) t.page[name] = async () => {}
  await t.page._doLoadFromOrder({ customerNote: '请勿按门铃；右侧门', deliveryStationId: 21, items: [{ productId: 31, quantity: 3 }] })
  assert.deepEqual(selected(t.page), ['请勿按门铃'])
  assert.equal(t.page.data.stationId, 21)
  assert.deepEqual(t.page.data.items, [{ productId: 31, quantity: 3 }])
})
test('choosing notes does not quote, submit, pay, clear the cart or navigate', () => {
  const t = notePage()
  for (const name of ['refreshQuote', 'invalidateQuote', 'onSubmit', '_createOrder']) t.page[name] = () => { throw Error('unexpected ' + name) }
  t.app.clearCart = () => { throw Error('unexpected cart clear') }
  t.tap(0); t.tap(1)
  assert.equal(t.page.data.note, '放门口；到前电话')
  assert.equal(t.wx.__calls.request.length + t.wx.__calls.nav.length, 0)
  assert.equal(t.page.data.submitState, 'idle')
})
test('chip markup exposes selected state and retains the free input', () => {
  const t = notePage(); t.tap(1)
  const root = tree('miniapp-user/pages/order/create.wxml')
  const section = nodes(root, n => hasClass(n, 'quick-notes'))[0]
  const result = rendered({ children: [section] }, t.page.data)
  assert.equal(result.filter(n => n.attrs.bindtap === 'onQuickNoteTap').length, 3)
  assert.equal(result.filter(n => n.text === '✓').length, 1)
  const input = nodes(root, n => n.tag === 'input' && n.attrs.bindinput === 'onNoteInput')[0]
  assert.equal(input.attrs.value, '{{note}}')
  assert.equal(input.attrs.maxlength, '140')
})

function employee(role = 'DELIVERY', isLogin = true) {
  const app = createApp({ globalData: { isLogin, userInfo: { staffId: 8, stationId: 11, role, bindStatus: 'BOUND' } }, isStationManager: () => ['manager', 'STATION_MANAGER', 'MANAGER'].includes(role) })
  const wx = createWx()
  const page = loadPage('miniapp-delivery/pages/home/index.js', { wx, app })
  page.loadStationStatus = async () => ({ code: 0, data: { stationId: 11, stationName: '合成水站' } })
  return { page, app, wx }
}
test('home role labels use known identity and clear on hide', async () => {
  for (const [role, label] of [['STATION_MANAGER', '站长'], ['manager', '站长'], ['MANAGER', '站长'], ['DELIVERY', '配送员'], ['delivery', '配送员'], ['UNKNOWN', ''], ['', '']]) {
    const t = employee(role)
    await t.page.loadHomeStationIdentity(11)
    assert.equal(t.page.data.homeRoleLabel, label)
    assert.equal(t.page.data.homeStationLabel, '当前水站：合成水站')
    t.page.onHide(); assert.equal(t.page.data.homeRoleLabel, '')
  }
  const t = employee('STATION_MANAGER', false)
  await t.page.loadHomeStationIdentity(11)
  assert.equal(t.page.data.homeRoleLabel, '')
})
test('identity changes cannot restore a stale station response or role after hiding', async () => {
  const t = employee('STATION_MANAGER'); let resolve
  t.page.loadStationStatus = () => new Promise(done => { resolve = done })
  const reading = t.page.loadHomeStationIdentity(11)
  t.app.globalData.userInfo = { staffId: 9, stationId: 22, role: 'DELIVERY' }
  t.page.onHide()
  resolve({ code: 0, data: { stationId: 11, stationName: '旧水站' } }); await reading
  assert.equal(t.page.data.homeRoleLabel, '')
  assert.equal(t.page.data.homeStationLabel, '当前水站：名称未核实')
})
const mineTree = tree('miniapp-delivery/pages/mine/index.wxml')
function mine(role, nickname = '', bindStatus = 'BOUND') {
  const app = createApp({ globalData: { isLogin: true, userInfo: { staffId: 8, role, stationId: 11, nickname, bindStatus } }, isStationManager: () => role === 'STATION_MANAGER' })
  const page = loadPage('miniapp-delivery/pages/mine/index.js', { app })
  page.loadStats = () => {}; page.loadRoleData = () => {}
  page.checkLogin()
  return { page, app }
}
function mineSection(name, data) {
  return rendered({ children: nodes(mineTree, n => hasClass(n, name)) }, data)
}
test('nickname fallback identifies a manager correctly and avoids duplicate role badges', () => {
  const header = nodes(mineTree, n => hasClass(n, 'user-nick-wrap'))[0]
  for (const role of ['STATION_MANAGER', 'DELIVERY', 'UNKNOWN']) {
    const t = mine(role), result = rendered({ children: [header] }, t.page.data)
    assert.equal(result[1].text, role === 'STATION_MANAGER' ? '站长' : role === 'DELIVERY' ? '配送员' : '身份待核实')
    assert.equal(result.filter(n => n.className.includes('role-chip')).length, 0)
  }
  const t = mine('DELIVERY', '林师傅')
  assert.equal(rendered({ children: [header] }, t.page.data).filter(n => n.className.includes('role-chip')).length, 1)
  t.page.setData({ userInfo: { nickname: '配送员' } })
  assert.equal(rendered({ children: [header] }, t.page.data).filter(n => n.className.includes('role-chip')).length, 0)
})
test('personal stats order uses existing personal fields and unknown values stay unknown', () => {
  const t = mine('DELIVERY')
  t.page.setData({ statsReady: true, todayStats: { completedCount: 12, deliveringCount: 3, returnBarrels: 7 } })
  let result = mineSection('stats-card', t.page.data)
  assert.deepEqual(result.filter(n => n.className.includes('stats-value')).map(n => n.text), ['3', '12', '7'])
  assert.deepEqual(result.filter(n => n.className === 'stats-label').map(n => n.text), ['配送中', '今日送达', '回桶数'])
  t.page.setData({ statsReady: false })
  result = mineSection('stats-card', t.page.data)
  assert.deepEqual(result.filter(n => n.className.includes('stats-value')).map(n => n.text), ['待核实', '待核实', '待核实'])
})
test('manager summary preserves income/cost and barrel uncertainty instead of showing fake zero', () => {
  const t = mine('STATION_MANAGER')
  t.page.setData({ managerStatsLoading: false, todayProfit: { metricLabel: '今日收入', netProfitText: '268.00', orderCount: 6 }, risk: { barrelExceptionFailed: true, owedCustomerFailed: true } })
  const result = mineSection('stats-card', t.page.data)
  assert(result.some(n => n.text === '本站概况'))
  assert(result.some(n => n.text === '今日收入'))
  assert(result.some(n => n.text === '268.00'))
  // Density layout places the risk label/value in separate elements. Verify
  // the actual owed-barrel entry and its state, not the previous joined phrase.
  const owed = nodes(mineTree, n => (n.attrs || {}).catchtap === 'onGoOwedBarrels')[0]
  assert(owed)
  const owedRows = () => rendered({ children: [owed] }, t.page.data)
  assert(owedRows().some(n => n.text === '欠桶'))
  assert(owedRows().some(n => n.className.includes('mine-risk-count') && n.text === '待核实'))
  t.page.setData({ risk: { barrelExceptionCount: 0, owedCustomerCount: 0, barrelExceptionFailed: false, owedCustomerFailed: false } })
  assert.equal(owedRows().length, 0, 'confirmed zero must not occupy the summary')
  t.page.setData({ risk: { barrelExceptionCount: 0, owedCustomerCount: 3, barrelExceptionFailed: false, owedCustomerFailed: false } })
  assert(owedRows().some(n => n.className.includes('mine-risk-count') && n.text.includes('3')))
  assert(!result.some(n => n.text === '今日净利'))
})
test('personal shortcuts remain accessible to both roles and precede station administration', () => {
  for (const role of ['DELIVERY', 'STATION_MANAGER']) {
    const t = mine(role), result = mineSection('personal-shortcuts', t.page.data)
    assert.deepEqual(result.filter(n => n.attrs.bindtap).map(n => n.attrs.bindtap), ['onMyEarnings', 'onBarrelRecords', 'onHistory'])
    assert.equal(result.some(n => n.text === '我的配送'), role === 'STATION_MANAGER')
    t.page.setData({ canAccessBusiness: false })
    assert.equal(mineSection('personal-shortcuts', t.page.data).length, 0)
  }
  const text = source('miniapp-delivery/pages/mine/index.wxml')
  assert(text.indexOf('personal-shortcuts') < text.indexOf('<!-- 站长：水站摘要'))
  assert.equal(nodes(mineTree, n => n.attrs && n.attrs.bindtap === 'onBarrelRecords').length, 1)
})
test('workbench stays manager-only with original handler; salary/barrel/history routes remain', () => {
  const entry = nodes(mineTree, n => n.attrs && n.attrs.bindtap === 'onGoStationMgmt')
  assert.equal(entry.length, 1)
  const delivery = mine('DELIVERY'), manager = mine('STATION_MANAGER')
  assert.equal(rendered({ children: entry }, delivery.page.data).length, 0)
  assert(rendered({ children: entry }, manager.page.data).length > 0)
  delivery.page.onGoStationMgmt(); assert.equal(delivery.page.__wx.__calls.nav.length, 0)
  manager.page.onMyEarnings(); manager.page.onBarrelRecords(); manager.page.onHistory()
  assert.deepEqual(manager.page.__wx.__calls.nav.map(n => n.url), ['/pages/my-earnings/index', '/pages/barrel-records/index', '/pages/history/index'])
})
test('all binding states retain their individual action and only BOUND gets compact presentation', () => {
  const root = nodes(mineTree, n => (n.attrs || {}).class?.startsWith('bind-action-card'))[0]
  for (const [status, handler] of [['BOUND', 'onApplyUnbind'], ['PENDING_UNBIND', 'onWithdrawUnbind'], ['PENDING', 'onWithdrawUnbind'], ['REJECTED', 'onLogin'], ['UNBOUND', 'onLogin']]) {
    const t = mine('DELIVERY', '', status), result = rendered({ children: [root] }, t.page.data)
    assert.deepEqual(result.filter(n => n.tag === 'button').map(n => n.attrs.bindtap), [handler])
    assert.equal(result[0].className.includes('bind-action-compact'), status === 'BOUND')
  }
})
test('contact tools keep full phone, catchtap, and the existing phone priority; completion stays primary', () => {
  const root = tree('miniapp-delivery/pages/home/index.wxml')
  const contacts = nodes(root, n => (n.attrs || {}).catchtap === 'onCallCustomer')
  assert.equal(contacts.length, 5)
  assert.deepEqual(contacts.map(n => n.attrs['data-phone']), [
    '{{item.customerPhone || item.receiverPhone}}', '{{item.customerPhone || item.receiverPhone}}',
    '{{item.customerPhone || item.receiverPhone}}', '{{item.receiverPhone || item.customerPhone}}', '{{item.receiverPhone}}'
  ])
  for (const n of contacts) {
    const result = rendered({ children: [n] }, { item: { customerPhone: '010-1234567890123456789', receiverPhone: '999' } })
    assert(result.some(n => n.text === '联系'))
    const expected = n.attrs['data-phone'].startsWith('{{item.customerPhone') ? '010-1234567890123456789' : '999'
    assert(result.some(n => n.text === expected))
    assert(result.some(n => n.tag === 'icon' && n.attrs.name === 'phone'))
  }
  const complete = nodes(root, n => (n.attrs || {}).catchtap === 'onCompleteOrder')
  assert.equal(complete.length, 1)
  assert(complete[0].attrs.class.includes('af-action--final'))
  assert(!source('miniapp-delivery/pages/home/index.wxml').includes('我的待送'))
})
test('changed page markup has balanced tags and valid conditional chains', () => {
  for (const rel of ['miniapp-user/pages/order/create.wxml', 'miniapp-delivery/pages/home/index.wxml', 'miniapp-delivery/pages/mine/index.wxml']) validateConditionalSiblings(tree(rel))
})

;(async () => {
  const done = armWatchdog(20000); let failed = 0
  for (const { name, run } of tests) {
    try { await run(); console.log('PASS ' + name) } catch (e) { failed++; console.error('FAIL ' + name + '\n' + e.stack) }
  }
  done()
  if (failed) process.exitCode = 1
  else console.log('AQUAFLOW_SUITE_OK ' + tests.length)
})()
