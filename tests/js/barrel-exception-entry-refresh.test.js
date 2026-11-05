'use strict'
// Actual page handlers and WXML, synthetic read results only; no live database or GUI claims.
const assert = require('assert'), fs = require('fs'), path = require('path')
const { loadPage, createWx, createApp, armWatchdog, ROOT } = require('./harness')
const { parseWxml, renderElements, validateConditionalSiblings } = require('./wxml-tree')
const route = 'miniapp-delivery/pages/station-mgmt/exceptions/index'
const read = p => fs.readFileSync(path.join(ROOT, p), 'utf8')
const ok = data => ({ code: 0, data })
const event = id => ({ currentTarget: { dataset: { id } } })
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
const pending = fields => ({ id: 11, orderId: 31, customerId: 7, pending: true, category: 'RETURN_SHORT', categoryText: '少回桶', statusText: '待处理', deliveryQty: 3, returnQty: 2, discrepancy: 1, ...fields })
function fixture(options = {}) {
  const wx = createWx(), app = createApp({ globalData: { isLogin: true, userInfo: { staffId: 8, stationId: 2, role: 'STATION_MANAGER' } } })
  app.isStationManager = () => app.globalData.userInfo.role === 'STATION_MANAGER'
  const state = { rows: [pending({ canReturnEmpty: true, handlingHint: '请按实际收到的空桶办理归还。' })], reads: [], writes: [], alerts: [] }
  const request = { get: async (url, query) => {
    state.reads.push({ url, query })
    if (options.get) return options.get(url, query, state)
    if (url.endsWith('/stats')) return ok({ totalCount: state.rows.length })
    if (/\/\d+$/.test(url)) return ok(state.rows.find(r => String(r.id) === url.split('/').at(-1)))
    return ok({ records: state.rows, total: state.rows.length })
  }, post: async (url, body) => { state.writes.push({ url, body }); return ok(null) } }
  const page = loadPage(route + '.js', { wx, app, stubs: { 'utils/request': request, 'api/station-mgmt': { getAlerts: options.alerts || (async () => ok(state.alerts)) } } })
  page.onLoad({})
  return { page, wx, app, state }
}
const tests = [], test = (name, run) => tests.push({ name, run })
test('actual return entry requires explicit capability and preserves original quantities', async () => {
  const f = fixture(); await f.page.onShow()
  assert.equal(f.page.data.items[0].canReturnEmpty, true)
  assert.equal(f.page.data.items[0].qtyText, '应回 3 / 实回 2 / 差 1')
  f.page.onReturnExceptionEmpty(event(11))
  assert.equal(f.wx.__calls.nav[0].url, '/pages/station-mgmt/customers/detail/index?id=7&action=return-empty')
  assert.equal(f.state.writes.length, 0)
})
test('missing, false and malformed capabilities never infer resolution from category or totals', async () => {
  const f = fixture()
  f.state.rows = [pending({ canReturnEmpty: undefined, currentOwedQty: 0 }), pending({ id: 12, canReturnEmpty: false }), pending({ id: 13, canReturnEmpty: 'true' }), pending({ id: 14, canReturnEmpty: true, customerId: '7&action=bad' })]
  await f.page.onShow()
  for (const row of f.page.data.items) {
    assert.equal(row.canReturnEmpty, false); assert.equal(row.pending, true)
    f.page.onReturnExceptionEmpty(event(row.id))
  }
  assert.equal(f.wx.__calls.nav.length, 0)
  f.page.onOpenResolution(event(11)); assert.equal(f.wx.__calls.nav[0].url, '/pages/case-handling/index?kind=EXCEPTION&id=11')
})
test('unproven or old-service short return offers neutral customer review without quantities or auto-open', async () => {
  const f = fixture()
  f.state.rows = [pending({ canReturnEmpty: undefined, currentOwedQty: 0 }), pending({ id: 12, canReturnEmpty: false, handlingHint: '原归还证据不足，请先核对。' })]
  await f.page.onShow()
  const nodes = renderElements(parseWxml(read(route + '.wxml')), f.page.data, { includeText: true })
  assert.equal(nodes.filter(n => n.text === '核对欠桶 / 还桶').length, 2)
  for (const row of f.page.data.items) {
    assert.equal(row.reviewOwedBarrels, true); assert.equal(row.canReturnEmpty, false); assert.equal(row.pending, true)
    f.page.onOpenExceptionCustomer(event(row.id))
  }
  assert.deepEqual(f.wx.__calls.nav.map(n => n.url), ['/pages/station-mgmt/customers/detail/index?id=7', '/pages/station-mgmt/customers/detail/index?id=7'])
  assert.equal(f.state.writes.length, 0)
})
test('neutral review uses server category code rather than translated text or inferred balance', async () => {
  const f = fixture()
  f.state.rows = [pending({ category: 'OTHER', categoryText: '少回桶', currentOwedQty: 9 }), pending({ id: 12, category: undefined }),
    pending({ id: 13, pending: false }), pending({ id: 14, canVerifyReturn: true }), pending({ id: 15, canReturnEmpty: true }),
    pending({ id: 16, customerId: '7&quantity=9' }), pending({ id: 17, category: 'RETURN_OVER' })]
  await f.page.onShow()
  assert(f.page.data.items.every(row => !row.reviewOwedBarrels))
  const nodes = renderElements(parseWxml(read(route + '.wxml')), f.page.data, { includeText: true })
  assert(!nodes.some(n => n.text === '核对欠桶 / 还桶'))
  assert.equal(nodes.filter(n => n.attrs.bindtap === 'onReturnExceptionEmpty').length, 1)
  assert(nodes.some(n => n.text === '办理还桶'))
})
test('neutral customer review retains manager, hidden-page and current-station guards', async () => {
  for (const change of ['role', 'hide', 'station']) {
    const f = fixture(); f.state.rows = [pending({ canReturnEmpty: false })]; await f.page.onShow()
    if (change === 'role') f.app.globalData.userInfo.role = 'DELIVERY'
    else if (change === 'hide') f.page.onHide()
    else f.app.globalData.userInfo.stationId = 3
    f.page.onOpenExceptionCustomer(event(11)); assert.equal(f.wx.__calls.nav.length, 0, change)
  }
})
test('historical return verification and terminal receipts use server flags without reopening', async () => {
  const f = fixture(); f.state.rows = [pending({ canVerifyReturn: true, handlingHint: '已找到归还记录，请核实后结案。' }), pending({ id: 12, pending: false, status: 'RETURNED', statusText: '欠桶已归还', canReturnEmpty: true, canVerifyReturn: true })]
  await f.page.onShow()
  assert.equal(f.page.data.items[0].canVerifyReturn, true)
  assert.equal(f.page.data.items[1].canReturnEmpty, false); assert.equal(f.page.data.items[1].canVerifyReturn, false)
  const nodes = renderElements(parseWxml(read(route + '.wxml')), f.page.data, { includeText: true })
  assert(nodes.some(n => n.text === '核实归还并结案')); assert(nodes.some(n => n.text === '查看办理记录'))
  assert(!nodes.some(n => n.attrs.bindtap === 'onReturnExceptionEmpty'))
})
test('other exception exposes processing and never offers a guessed empty-barrel operation', async () => {
  const f = fixture(); f.state.rows = [pending({ category: 'OTHER', categoryText: '其他', staffNote: '桶身划痕', discrepancy: 0 })]
  await f.page.onShow(); f.page.onReturnExceptionEmpty(event(11)); f.page.onOpenResolution(event(11))
  assert.equal(f.wx.__calls.nav.length, 1); assert(f.wx.__calls.nav[0].url.includes('case-handling'))
  const nodes = renderElements(parseWxml(read(route + '.wxml')), f.page.data, { includeText: true })
  assert(nodes.some(n => n.text === '处理异常')); assert(nodes.some(n => n.text === '桶身划痕'))
})
test('returning from customer handling rereads list, statistics and history', async () => {
  const f = fixture(); await f.page.onShow(); f.page.onReturnExceptionEmpty(event(11)); f.page.onHide()
  f.state.rows = [pending({ pending: false, canReturnEmpty: false, status: 'RETURNED', statusText: '欠桶已归还', managerNote: '按实际归还记录结清' })]
  f.state.alerts = [{ id: 1, title: '欠桶已归还', level: 'INFO' }]
  await f.page.onShow()
  assert.equal(f.page.data.items[0].pending, false); assert.equal(f.page.data.items[0].statusText, '欠桶已归还')
  assert.equal(f.page.data.visibleAlerts[0].title, '欠桶已归还')
  assert.equal(f.state.reads.filter(r => r.url.endsWith('/stats')).length, 2)
})
test('pre-navigation late list, overview and history cannot overwrite post-return facts', async () => {
  const oldList = deferred(), oldStats = deferred(), oldAlerts = deferred(); let listCalls = 0, statsCalls = 0, alertCalls = 0
  const f = fixture({ get: (url) => url.endsWith('/stats') ? (++statsCalls === 1 ? oldStats.promise : ok({ totalCount: 2 }))
    : (++listCalls === 1 ? oldList.promise : ok({ records: [pending({ pending: false, statusText: '欠桶已归还' })], total: 1 })),
  alerts: () => ++alertCalls === 1 ? oldAlerts.promise : ok([{ id: 2, title: '新结果', level: 'INFO' }]) })
  const first = f.page.onShow(); f.page.onHide(); await f.page.onShow()
  oldList.resolve(ok({ records: [pending()], total: 1 })); oldStats.resolve(ok({ totalCount: 99 })); oldAlerts.resolve(ok([{ id: 1, title: '旧待办', level: 'WARN' }]))
  await first
  assert.equal(f.page.data.items[0].pending, false); assert.equal(f.page.data.stats.totalCount, 2)
  assert.equal(f.page.data.visibleAlerts[0].title, '新结果'); assert.equal(f.page.data.loading, false)
})
test('hidden or switched staff cannot navigate and late reads cannot publish old station data', async () => {
  const d = deferred(), f = fixture({ get: () => d.promise })
  const readList = f.page.loadList(true); f.app.globalData.userInfo.stationId = 3
  d.resolve(ok({ records: [pending({ canReturnEmpty: true })], total: 1 })); await readList
  assert.equal(f.page.data.items.length, 0); f.page.onReturnExceptionEmpty(event(11)); assert.equal(f.wx.__calls.nav.length, 0)
  const g = fixture(); await g.page.onShow(); g.page.onHide(); g.page.onReturnExceptionEmpty(event(11)); g.page.onOpenResolution(event(11)); assert.equal(g.wx.__calls.nav.length, 0)
})
test('bound delivery role cannot read manager data or invoke handling', async () => {
  const f = fixture(); f.app.globalData.userInfo.role = 'DELIVERY'
  await f.page.onShow(); await f.page.load(); await f.page.loadList(true); await f.page.onIgnore(event(11))
  assert.equal(f.page.data.denied, true); assert.equal(f.state.reads.length, 0); assert.equal(f.state.writes.length, 0)
  assert.equal(f.wx.__calls.modal.length, 0)
})
test('failed refresh does not present stale processing links as current', async () => {
  let fail = false
  const f = fixture({ get: async url => { if (url.endsWith('/stats')) return ok({}); if (fail) throw Error('synthetic offline'); return ok({ records: [pending({ canReturnEmpty: true })], total: 1 }) } })
  await f.page.onShow(); fail = true; await f.page.onShow(); f.page.onReturnExceptionEmpty(event(11))
  assert.equal(f.wx.__calls.nav.length, 0); assert(f.page.data.listError); assert.equal(f.page.data.total, null)
})
test('RETURNED is a terminal original receipt, not a reason to retry compensation', async () => {
  const f = fixture(); f.state.rows = [pending({ pending: false, status: 'RETURNED', statusText: '欠桶已归还' })]
  f.page.setData({ uncertainId: 11 }); await f.page.onVerifyHandling()
  assert.equal(f.page.data.uncertainId, null); assert(f.page.data.actionNotice.includes('欠桶已归还')); assert.equal(f.state.writes.length, 0)
})
test('button markup is single-layer and retains contrast and phone touch sizes', async () => {
  const tree = parseWxml(read(route + '.wxml')); validateConditionalSiblings(tree)
  const walk = n => [n, ...(n.children || []).flatMap(walk)]
  for (const node of walk(tree).filter(n => ['onReturnExceptionEmpty', 'onOpenResolution', 'onOpenExceptionOrder', 'onOpenExceptionCustomer', 'onIgnore'].includes(n.attrs && n.attrs.bindtap))) {
    assert(!walk(node).slice(1).some(n => /af-action__face/.test(n.attrs && n.attrs.class || '')))
  }
  const css = read('miniapp-delivery/styles/actions.wxss'), pageCss = read(route + '.wxss')
  const token = name => css.match(new RegExp('--' + name + ':\\s*(#[0-9a-fA-F]{6})'))[1]
  const lum = hex => { const rgb = hex.slice(1).match(/../g).map(n => parseInt(n, 16) / 255).map(n => n <= .04045 ? n / 12.92 : ((n + .055) / 1.055) ** 2.4); return rgb[0] * .2126 + rgb[1] * .7152 + rgb[2] * .0722 }
  const contrast = (a, b) => (Math.max(lum(a), lum(b)) + .05) / (Math.min(lum(a), lum(b)) + .05)
  assert(contrast(token('af-primary'), token('af-on-primary')) >= 4.5)
  assert(contrast(token('af-primary'), token('af-surface')) >= 4.5)
  assert(css.includes('min-height:44px')); assert(pageCss.includes('min-height:48px')); assert(css.includes('white-space:normal'))
})
function caseFixture(fields = {}) {
  const wx = createWx(), app = createApp({ globalData: { isLogin: true, userInfo: { staffId: 8, stationId: 2, role: 'STATION_MANAGER' } } })
  const data = { kind: 'EXCEPTION', caseId: 11, version: 0, actions: [], canResolve: false, orderId: 31, ...fields }, sent = []
  const page = loadPage('miniapp-delivery/pages/case-handling/index.js', { wx, app, stubs: { 'utils/request': { get: async () => ok(data), post: async (url, body) => { sent.push({ url, body }); return ok({ kind: 'EXCEPTION', caseId: 11, version: body.expectedVersion + 1, action: 'VERIFY' }) } } } })
  page.onLoad({ kind: 'EXCEPTION', id: '11' }); return { page, wx, app, data, sent }
}
test('case detail exposes actual return only with explicit current capability and refreshes after return', async () => {
  const f = caseFixture({ customerId: 7, canReturnEmpty: true, handlingHint: '请先办理实际还桶。' }); await f.page.onShow(); f.page.onReturnEmpty()
  assert.equal(f.wx.__calls.nav[0].url, '/pages/station-mgmt/customers/detail/index?id=7&action=return-empty')
  f.page.onHide(); f.data.canReturnEmpty = false; f.data.statusText = '欠桶已归还'; await f.page.onShow(); f.page.onReturnEmpty()
  assert.equal(f.wx.__calls.nav.length, 1); assert.equal(f.page.data.statusText, '欠桶已归还')
  f.page.setData({ pending: true, canReturnEmpty: true }); f.page.onReturnEmpty(); assert.equal(f.wx.__calls.nav.length, 1)
})
test('historical evidenced return uses server resolution label and unchanged versioned command', async () => {
  const f = caseFixture({ canResolve: true, resolveActionText: '核实归还并结案', handlingHint: '请核对已有实际归还记录。' }); await f.page.onShow()
  f.page.setData({ reason: '已核对原归还记录与商品数量' }); await f.page.onResolve()
  assert.equal(f.sent[0].url, '/api/manager/exceptions/11/resolve'); assert.equal(f.sent[0].body.expectedVersion, 0)
  assert.equal(f.wx.__calls.modal[0].title, '核实归还并结案'); assert(!('amount' in f.sent[0].body))
})
test('old case response remains readable without inventing return access or resolution', async () => {
  const f = caseFixture({ canResolve: false }); await f.page.onShow(); f.page.onReturnEmpty(); f.page.setData({ reason: '不能推断结案' }); await f.page.onResolve()
  assert.equal(f.wx.__calls.nav.length, 0); assert.equal(f.sent.length, 0); assert.equal(f.page.data.customerId, null)
  const tree = parseWxml(read('miniapp-delivery/pages/case-handling/index.wxml')); validateConditionalSiblings(tree)
  const nodes = renderElements(tree, f.page.data, { includeText: true })
  assert(!nodes.some(n => n.attrs.bindtap === 'onReturnEmpty' || n.attrs.bindtap === 'onResolve'))
})
;(async () => {
  const done = armWatchdog(20000)
  try { for (const t of tests) { await t.run(); console.log('PASS ' + t.name) } console.log('AQUAFLOW_SUITE_OK ' + tests.length) }
  catch (e) { console.error(e); process.exitCode = 1 }
  finally { done() }
})()
