const assert = require('assert'), fs = require('fs'), path = require('path')
const R = process.env.AQUAFLOW_REPO || path.resolve(__dirname, '../..')
const { loadPage, createWx, createApp, armWatchdog } = require(R + '/tests/js/harness')
const { parseWxml, renderElements } = require(R + '/tests/js/wxml-tree')
const done = armWatchdog(30000), tests = []
const test = (name, run) => tests.push({ name, run })
const copy = value => JSON.parse(JSON.stringify(value))
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r }); return { promise, resolve } }
const event = id => ({ currentTarget: { dataset: { id } } })
const row = (orderId, totalAmount, extra = {}) => ({ orderId, customerId: 7, totalAmount,
  status: 3, paymentStatus: 1, settlementStatus: 1, ...extra })
const view = (file, data) => renderElements(parseWxml(fs.readFileSync(R + '/' + file, 'utf8')), data, { includeText: true })
async function ledger(rows = [row(101, '0.10'), row(102, '0.20')]) {
  const wx = createWx(), calls = [], state = { rows, gate: null, fail: false, access: true,
    reads: [], postError: null, postResponse: undefined, failAfterPost: false }
  const app = createApp({ _loginGeneration: 1,
    globalData: { isLogin: true, accessToken: 'synthetic', userInfo: { staffId: 1, stationId: 11, role: 'STATION_MANAGER', bindStatus: 'APPROVED' } },
    canAccessStationBusiness: () => state.access })
  wx.showModal = modal => wx.__calls.modal.push(modal)
  const page = loadPage('miniapp-delivery/pages/station-mgmt/receivables/index.js', { wx, app, stubs: {
    'utils/request': { get: async url => {
      state.reads.push(url)
      if (state.fail === true || state.fail === 'overview' && !url.includes('/orders?')) throw Error('合成读取失败')
      return url.includes('/orders?') ? { data: copy(state.rows) }
        : { data: { customers: [{ customerId: 7, customerName: '合成客户', outstandingAmount: 0 }] } }
    }, post: async (url, body) => {
      calls.push({ url, body })
      if (state.failAfterPost) state.fail = state.failAfterPost
      if (state.postError) throw state.postError
      return state.postResponse !== undefined ? state.postResponse
        : state.gate ? state.gate.promise : { data: { settledCount: body.orderIds.length, settledAmount: '0.30' } }
    } }
  } })
  page.setData({ loading: false, customer: { customerId: 7, customerName: '合成客户' } })
  await page.loadOrders()
  const selectAll = () => page.onSelectAll()
  return { page, wx, app, calls, state, selectAll }
}
const prompt = t => t.wx.__calls.modal.find(m => m.title === '确认登记已收款')

test('receivables require the actual customer, order amounts and exact selected total before POST', async () => {
  const t = await ledger(); t.selectAll(); const work = t.page.onSettle(), modal = prompt(t)
  assert(modal, 'must confirm before writing'); assert.equal(t.calls.length, 0)
  assert(modal.content.includes('合成客户') && modal.content.includes('#7'))
  for (const text of ['订单 #101：¥0.10', '订单 #102：¥0.20', '所选 2 单，合计 ¥0.30', '已实际收到', '销掉这些应收账', '不会代客户付款']) assert(modal.content.includes(text), text)
  assert.equal(t.page.data.selectedAmountText, '¥0.30')
  const visible = view('miniapp-delivery/pages/station-mgmt/receivables/index.wxml', t.page.data).map(e => e.text).join('\n')
  assert(visible.includes('所选合计'))
  assert(view('miniapp-delivery/pages/station-mgmt/receivables/index.wxml', t.page.data).some(e => e.className === 'settlement-amount' && e.text === '¥0.30'), 'the exact selected total remains visible in its amount slot')
  modal.success({ confirm: true }); await work
  assert.deepEqual(t.calls, [{ url: '/api/manager/receivables/settle', body: { customerId: 7, orderIds: [101, 102] } }])
  assert.equal(t.page.data.settling, false)
})
test('cancel preserves choices, opens no write, and permits a fresh confirmation', async () => {
  const t = await ledger(); t.selectAll(); let work = t.page.onSettle()
  assert(prompt(t)); prompt(t).success({ confirm: false }); await work
  assert.equal(t.calls.length, 0); assert.equal(t.page.data.selectedCount, 2); assert.equal(t.page.data.settling, false)
  work = t.page.onSettle(); t.wx.__calls.modal.at(-1).success({ confirm: false }); await work
  assert.equal(t.wx.__calls.modal.length, 2)
})
test('confirmation and pending write share one lock; repeated native callbacks post once', async () => {
  const t = await ledger(); t.selectAll(); t.state.gate = deferred()
  const work = t.page.onSettle(); await t.page.onSettle(); assert.equal(t.wx.__calls.modal.length, 1)
  const modal = prompt(t); assert(modal); modal.success({ confirm: true }); modal.success({ confirm: true })
  await Promise.resolve(); assert.equal(t.calls.length, 1); await t.page.onSettle(); assert.equal(t.calls.length, 1)
  t.state.gate.resolve({ data: { settledCount: 2, settledAmount: '0.30' } }); await work
  assert.equal(t.page.data.settling, false)
})
test('native confirmation failure releases the lock without writing', async () => {
  const t = await ledger(); t.selectAll(); const work = t.page.onSettle(); assert(prompt(t))
  prompt(t).fail(); await work; assert.equal(t.calls.length, 0); assert.equal(t.page.data.settling, false)
})
for (const amount of [null, undefined, '', ' ', true, false, 'invalid', '-1', '1.001', '1e2', '99999999999999999999']) {
  test('unknown or invalid selected amount does not become zero: ' + String(amount), async () => {
    const t = await ledger([row(101, amount)]); t.selectAll(); await t.page.onSettle()
    assert.equal(t.calls.length, 0); assert.equal(t.wx.__calls.modal.length, 0)
    assert.equal(t.page.data.selectedAmountText, '金额待核实')
    assert.equal(t.page.data.orders[0].amountText, '金额待核实')
  })
}
test('explicit zero remains a valid known amount', async () => {
  const t = await ledger([row(101, 0)]); t.selectAll(); const work = t.page.onSettle(); assert(prompt(t))
  assert(prompt(t).content.includes('合计 ¥0.00')); prompt(t).success({ confirm: true }); await work; assert.equal(t.calls.length, 1)
})
test('repeated identical order IDs count and total only once', async () => {
  const t = await ledger([row(101, '0.10'), row(101, '0.10'), row(102, '0.20')]); t.selectAll()
  const work = t.page.onSettle(); assert(prompt(t)); assert(prompt(t).content.includes('所选 2 单，合计 ¥0.30'))
  prompt(t).success({ confirm: true }); await work; assert.deepEqual(t.calls[0].body.orderIds, [101, 102])
})
test('paid, settled and cancelled rows are excluded from selecting all and direct row toggles', async () => {
  const t = await ledger([row(101, '0.10'), row(102, 100, { paymentStatus: 2 }), row(103, 200, { settlementStatus: 2 }), row(104, 300, { status: 5 })])
  for (const id of [102, 103, 104]) t.page.onToggleOrder(event(id))
  t.selectAll(); assert.equal(t.page.data.selectedCount, 1)
  const work = t.page.onSettle(); assert(prompt(t)); assert(prompt(t).content.includes('所选 1 单，合计 ¥0.10'))
  prompt(t).success({ confirm: true }); await work; assert.deepEqual(t.calls[0].body.orderIds, [101])
})
test('unsafe aggregate or mismatched customer cannot open a confirmation', async () => {
  for (const rows of [[row(101, '50000000000000.00'), row(102, '50000000000000.00')], [row(101, 1, { customerId: 8 })], [row(101, 1), row(101, 2)]]) {
    const t = await ledger(rows); t.selectAll(); await t.page.onSettle(); assert.equal(t.calls.length, 0); assert.equal(t.wx.__calls.modal.length, 0)
  }
})
const invalidations = {
  staff: t => { t.app.globalData.userInfo.staffId = 2 },
  role: t => { t.app.globalData.userInfo.role = 'DELIVERY' },
  station: t => { t.app.globalData.userInfo.stationId = 22 },
  generation: t => { t.app._loginGeneration++ },
  logout: t => { t.app.globalData.isLogin = false },
  permission: t => { t.state.access = false },
  customer: t => { t.page.data.customer.customerId = 8 },
  name: t => { t.page.data.customer.customerName = '另一个客户名称' },
  amount: t => { t.page.data.orders[0].totalAmount = '10.00' },
  paid: t => { t.page.data.orders[0].paymentStatus = 2 },
  settled: t => { t.page.data.orders[0].settlementStatus = 2 },
  cancelled: t => { t.page.data.orders[0].status = 5 },
  selection: t => { t.page.onToggleOrder(event(101)) },
  'selection changed back': t => { t.page.onToggleOrder(event(101)); t.page.onToggleOrder(event(101)) },
  filter: t => { t.page.onFilterOverdue() },
  'filter changed back': t => { t.page.onFilterOverdue(); t.page.onFilterAll() },
  'overview reread': t => t.page.load(),
  'orders reread': t => t.page.loadOrders(),
  'failed reread': t => { t.state.fail = true; return t.page.loadOrders() },
  back: t => t.page.onBack(),
  hide: t => t.page.onHide(),
  unload: t => t.page.onUnload()
}
for (const [name, invalidate] of Object.entries(invalidations)) test('an old confirmation cannot submit after ' + name, async () => {
  const t = await ledger(); t.selectAll(); const work = t.page.onSettle(), modal = prompt(t); assert(modal)
  await invalidate(t); modal.success({ confirm: true }); await work; assert.equal(t.calls.length, 0)
  if (!t.page._unloaded) assert.equal(t.page.data.settling, false)
})
test('ordinary token renewal keeps the same manager confirmation valid', async () => {
  const t = await ledger(); t.selectAll(); const work = t.page.onSettle(); assert(prompt(t))
  t.app.globalData.accessToken = 'renewed'; prompt(t).success({ confirm: true }); await work; assert.equal(t.calls.length, 1)
})
test('new identity cannot reuse rows read for the earlier manager', async () => {
  const t = await ledger(); t.selectAll(); t.app.globalData.userInfo.stationId = 22
  await t.page.onSettle(); assert.equal(t.calls.length, 0); assert.equal(t.wx.__calls.modal.length, 0)
})
test('unknown registration outcomes and malformed receipts clear selection and verify by GET without another POST', async () => {
  for (const metadata of [{ requestMayHaveOccurred: true }, { resultUnknown: true }, { malformedResponse: true },
    { statusCode: 503 }, { statusCode: 200, backendCode: 500 }, null]) {
    const t = await ledger(); t.selectAll()
    if (metadata) t.state.postError = Object.assign(Error('合成回应未确认'), metadata)
    else t.state.postResponse = { data: {} }
    const work = t.page.onSettle(); assert(prompt(t)); t.state.rows = []
    prompt(t).success({ confirm: true }); await work
    const modal = t.wx.__calls.modal.find(m => m.title === '登记结果待核实')
    assert(modal, JSON.stringify(metadata)); assert(modal.content.includes('只登记已收到的钱'))
    assert(modal.content.includes('请勿再次向客户收款')); assert(modal.content.includes('不会自动重发登记'))
    assert(!t.wx.__calls.modal.some(m => ['核销未完成', '核销完成'].includes(m.title)))
    assert.equal(t.calls.length, 1); assert.equal(t.page.data.selectedCount, 0); assert.deepEqual(t.page.data.orders, [])
    assert(t.state.reads.includes('/api/manager/receivables'))
    assert.equal(t.state.reads.filter(url => url.includes('/orders?')).length, 2)
    await t.page.onSettle(); assert.equal(t.calls.length, 1)
  }
})
test('unknown registration followed by failed GET leaves a visible blocking error and no checked old orders', async () => {
  for (const failure of [true, 'overview']) {
    const t = await ledger(); t.selectAll(); t.state.postError = Object.assign(Error('合成超时'), { requestMayHaveOccurred: true })
    t.state.failAfterPost = failure
    const work = t.page.onSettle(); assert(prompt(t)); prompt(t).success({ confirm: true }); await work
    assert(t.page.data.ordersError.includes('登记结果仍待核实')); assert(t.page.data.ordersError.includes('请勿重复登记'))
    assert(t.page.data.ordersError.includes('不要再次向客户收款')); assert.equal(t.page.data.selectedCount, 0)
    assert(t.page.data.orders.every(order => !order.checked)); assert.equal(t.page.data.settling, false)
    t.page.onSelectAll(); await t.page.onSettle(); assert.equal(t.calls.length, 1)
  }
})
test('an explicit business rejection retains its reason and selection without claiming an unknown result', async () => {
  const t = await ledger(); t.selectAll(); t.state.postError = Object.assign(Error('订单已取消，不得核销'), { businessRejected: true, backendCode: 1, statusCode: 200 })
  const work = t.page.onSettle(); assert(prompt(t)); prompt(t).success({ confirm: true }); await work
  const modal = t.wx.__calls.modal.find(m => m.title === '核销未完成')
  assert(modal); assert.equal(modal.content, '订单已取消，不得核销')
  assert(!t.wx.__calls.modal.some(m => m.title === '登记结果待核实'))
  assert.equal(t.page.data.selectedCount, 2); assert.equal(t.state.reads.length, 1); assert.equal(t.calls.length, 1)
})

function template(options = {}) {
  const wx = createWx(), calls = []
  const app = createApp({ globalData: { isLogin: options.guest !== true, customerId: options.guest ? null : 7 } })
  const page = loadPage('miniapp-user/pages/template/index.js', { wx, app, stubs: {
    'utils/station': { resolveStationId: async () => 11 },
    'api/template': { getTemplates: async () => { if (options.error) throw Error(options.error); return { code: 0, data: [] } } },
    'api/product': { getStationProducts: async () => { calls.push('products'); return { data: [] } } },
    'api/address': { getAddresses: async () => { calls.push('addresses'); return { data: [] } } }
  } })
  return { page, wx, app, calls }
}
const templateActions = t => view('miniapp-user/pages/template/index.wxml', t.page.data).map(e => e.attrs.bindtap).filter(Boolean)
test('guest template has login as its only action and cannot open a new-order form', async () => {
  const t = template({ guest: true }); await t.page.onShow(); const actions = templateActions(t)
  assert(actions.includes('onLogin')); assert(!actions.includes('onRetry')); assert(!actions.includes('onAddTemplate'))
  t.page.onAddTemplate(); assert.equal(t.page.data.showEditModal, false); assert.equal(t.calls.length, 0)
})
test('permission denial hides template creation; an expired login offers login alone', async () => {
  for (const error of ['无权查看本站常用订水配置', '请先登录']) {
    const t = template({ error }); await t.page.onShow(); const actions = templateActions(t)
    assert(!actions.includes('onAddTemplate')); t.page.onAddTemplate(); assert.equal(t.page.data.showEditModal, false)
    if (error === '请先登录') { assert(actions.includes('onLogin')); assert(!actions.includes('onRetry')) }
  }
})
test('signed-in true empty and transient read failure retain normal creation and retry behavior', async () => {
  for (const error of ['', '配置暂未加载']) {
    const t = template({ error }); await t.page.onShow(); const actions = templateActions(t)
    assert(actions.includes('onAddTemplate')); assert(!actions.includes('onLogin'))
    if (error) assert(actions.includes('onRetry'))
    t.page.onAddTemplate(); assert.equal(t.page.data.showEditModal, true)
  }
})
test('logout between template display and queued creation tap cannot open the form', async () => {
  const t = template(); await t.page.onShow(); t.app.globalData.isLogin = false; t.page.onAddTemplate()
  assert.equal(t.page.data.showEditModal, false); assert.equal(t.calls.length, 0)
})
test('barrel guest shows login without the ineffective reload action', async () => {
  const wx = createWx(), app = createApp({ globalData: { isLogin: false } }), session = { loggedIn: false, customerId: null }
  const page = loadPage('miniapp-user/pages/barrel/index.js', { wx, app, stubs: {
    'utils/asset-view-station': { beginRead: () => ({ stationId: null, session }) }
  } })
  await page.loadData()
  const root = parseWxml(fs.readFileSync(R + '/miniapp-user/pages/barrel/index.wxml', 'utf8')), nodes = []
  const visit = node => { if (node.attrs && (node.attrs.bindtap === 'onLogin' || node.attrs.class === 'load-error' && node.attrs['wx:if'] === '{{loadError}}')) nodes.push(node); else for (const child of node.children || []) visit(child) }
  visit(root)
  const actions = renderElements({ tag: '#root', children: nodes }, page.data).map(e => e.attrs.bindtap).filter(Boolean)
  assert(actions.includes('onLogin')); assert(!actions.includes('onRetryAssets'))
})
test('template fixed action and content reserve the bottom safe area', () => {
  const css = fs.readFileSync(R + '/miniapp-user/pages/template/index.wxss', 'utf8')
  assert(/\.add-btn\s*\{[^}]*bottom:\s*calc\([^;]*env\(safe-area-inset-bottom\)/.test(css))
  assert(css.includes('padding-bottom: calc(152rpx + env(safe-area-inset-bottom))'))
})

;(async () => {
  let passed = 0
  for (const { name, run } of tests) {
    try { await run(); passed++; console.log('PASS ' + name) }
    catch (error) { process.exitCode = 1; console.error('FAIL ' + name + '\n' + error.stack) }
  }
  console.log('SCOPED_RECEIVABLE_LOGIN_RESULT ' + passed + '/' + tests.length)
  if (passed === tests.length) console.log('AQUAFLOW_SUITE_OK ' + passed)
  done()
})().catch(error => { console.error(error); done(); process.exitCode = 1 })
