const assert = require('assert')
const Module = require('module')
const path = require('path')
const { loadPage, createWx, createApp, armWatchdog } = require('./harness')
const reminder = require('../../miniapp-delivery/utils/pending-reminder')
let passed = 0
const done = armWatchdog()
async function test(name, fn) { await fn(); passed++; console.log('  OK ' + name) }
const response = data => ({ code: 0, data })
const event = (id, action) => ({ currentTarget: { dataset: { id, action } } })
const managerApp = () => createApp({ globalData: { userInfo: { stationId: 1, role: 'STATION_MANAGER' } } })
// 独立固定旧 PendingItem 目录，复现旧应用响应；不从页面白名单生成缺键样本。
const LEGACY_PENDING_ITEMS = [
  ['pendingAssign', '待分配订单', 'P0'], ['pendingTransfer', '转单请求', 'P0'],
  ['customerCancel', '客户取消申请', 'P0'], ['stationCancel', '站内取消申请', 'P0'],
  ['directedIncoming', '指定外派待确认', 'P0'], ['barrelReturn', '待审退桶', 'P0'],
  ['pendingPayment', '待确认收款', 'P1'], ['overdueReceivable', '逾期应收', 'P1'],
  ['staffBinding', '员工绑定申请', 'P1'], ['enterpriseApply', '企业身份待审', 'P1'],
  ['draftPayroll', '待确认结算单', 'P1'], ['interStationUnsettled', '站间未结清', 'P1'],
  ['poolClaimable', '抢单池可抢', 'P2'], ['costNotFilled', '未填成本', 'P2'],
  ['barrelException', '待处理桶异常', 'P2'], ['operationAlert', '运营告警', 'P2']
].map(([key, label, level]) => ({ key, label, level, count: 0, amount: null }))
const BUSINESS_PENDING_ITEMS = [
  ['waitingStock', '缺货待补', 'P0'], ['returnRefund', '已收桶待退款', 'P1'],
  ['recoverySend', '返还款待交付', 'P1'], ['recoveryReceive', '返还款待确认', 'P1'],
  ['barrelHandover', '净桶交接待确认', 'P1'], ['barrelDispute', '净桶争议待协商', 'P1']
].map(([key, label, level]) => ({ key, label, level, count: 0, amount: null }))
function completeSummary(counts = {}, p0Total = 0) {
  return { complete: true, p0Total, items: [...LEGACY_PENDING_ITEMS, ...BUSINESS_PENDING_ITEMS].map(it => ({
    ...it, available: true, count: Object.prototype.hasOwnProperty.call(counts, it.key) ? counts[it.key] : 0
  })) }
}
function emptyWaiting() { return { schemaAvailable: true, limit: 200, counts: { waitingStock: 0, returnsTotal: 0, returnRefund: 0, recoveriesTotal: 0, recoverySend: 0, recoveryReceive: 0, barrelsTotal: 0, barrelHandover: 0, barrelDispute: 0 }, stock: [], returns: [], recoveries: [], barrels: [] } }
function setup(extra = {}) {
  const state = Object.assign({ waiting: emptyWaiting(), waitingFail: false, refusalFail: false, calls: [], reminders: 0 }, extra)
  const wx = createWx(), app = managerApp()
  wx.showModal = opt => { wx.__calls.modal.push(opt); wx.pendingModal = opt }
  wx.showActionSheet = opt => { wx.pendingSheet = opt }
  const api = {
    getWaiting: async () => { state.calls.push('read'); if (state.waitingFail) throw Error('暂时无法读取'); return response(state.waiting) },
    getRefusals: async () => { if (state.refusalFail) throw Error('拒付读取失败'); return response([]) },
    getTicketExitBatches: async () => response([]),
    getRecoveries: async () => { throw Error('不能用历史截断列表作待办') },
    getBarrelBalances: async () => { throw Error('不能用历史截断列表作待办') },
    recoverySent: async id => { state.calls.push(['sent', id]); if (state.actionFail) throw Error('交付未确认'); state.waiting.recoveries = []; state.waiting.counts.recoveriesTotal = 0 },
    recoveryReceived: async id => { state.calls.push(['received', id]); state.waiting.recoveries = []; state.waiting.counts.recoveriesTotal = 0 },
    barrelReceived: async id => { state.calls.push(['barrels', id]); state.waiting.barrels = []; state.waiting.counts.barrelsTotal = 0 },
    proposeBarrels: async (id, body) => { state.calls.push(['proposal', id, body]); state.waiting.barrels = []; state.waiting.counts.barrelsTotal = 0 },
    agreeBarrels: async id => { state.calls.push(['agree', id]); state.waiting.barrels[0].nextAction = 'barrels' },
    confirmFreeze: async id => state.calls.push(['freeze', id])
  }
  const page = loadPage('miniapp-delivery/pages/station-mgmt/business-waiting/index.js', { wx, app, stubs: {
    'api/business-rules': api,
    'utils/pending-reminder': { syncPendingReminder: async () => { state.reminders++ } }
  } })
  return { page, wx, state, api, app }
}
function home(state) {
  return loadPage('miniapp-delivery/pages/coordination/index.js', { wx: createWx(), app: managerApp(), stubs: {
    'utils/request': { get: async () => { if (state.fail) throw Error('offline'); return state.pending || state.res } },
    'utils/pending-reminder': reminder,
    'api/delivery': {}, 'behaviors/stationNavbar': {}
  } })
}
function dotWrites(page) { return page.__wx.__calls.storageSet.filter(it => it.k === reminder.DOT_FLAG_KEY).map(it => it.v) }
const flushReminder = async () => { for (let i = 0; i < 12; i++) await Promise.resolve() }
function employeeApp(state) {
  const wx = createWx(), root = path.resolve(__dirname, '../..')
  const get = async () => { state.reads = (state.reads || 0) + 1; if (state.fail) throw Error('offline'); return state.res }
  let app
  global.wx = wx; global.__wxConfig = { envVersion: 'develop' }
  global.App = config => { app = config }; global.getApp = () => app
  global.getCurrentPages = () => [{ route: 'pages/coordination/index' }]
  for (const file of ['miniapp-delivery/app.js', 'miniapp-delivery/utils/pending-reminder.js']) delete require.cache[require.resolve(path.join(root, file))]
  const originalLoad = Module._load
  Module._load = function (request, parent, isMain) {
    if (parent && /miniapp-delivery[\\/](app\.js|utils[\\/]pending-reminder\.js)$/.test(parent.filename) &&
        /^(\.\/request|\.\/utils\/request)$/.test(request)) return { get }
    return originalLoad.apply(this, arguments)
  }
  let shared
  try {
    require(path.join(root, 'miniapp-delivery/app.js'))
    shared = require(path.join(root, 'miniapp-delivery/utils/pending-reminder'))
  } finally { Module._load = originalLoad }
  app.setLoginState({ accessToken: 'test-access', refreshToken: 'test-refresh', staffId: 1, role: 'STATION_MANAGER', stationId: 1 })
  const page = loadPage('miniapp-delivery/pages/coordination/index.js', { wx, app, stubs: {
    'utils/request': { get }, 'api/delivery': {}, 'behaviors/stationNavbar': {}
  } })
  return { app, wx, shared, page }
}
async function main() {
  const incomplete = completeSummary(); incomplete.complete = false
  Object.assign(incomplete.items.find(it => it.key === 'returnRefund'), { available: false, count: null })
  for (const [name, bad] of [
    ['complete=false且数量unknown', incomplete],
    ['旧16遗漏complete', { p0Total: 0, items: LEGACY_PENDING_ITEMS.map(it => ({ ...it })) }],
    ['旧16却complete=true', { complete: true, p0Total: 0, items: LEGACY_PENDING_ITEMS.map(it => ({ ...it, available: true })) }]
  ]) {
    await test('B2真实App.onShow不以' + name + '清旧红点，合法重试才更新', async () => {
      const state = { res: response(completeSummary({ waitingStock: 1 }, 1)) }, t = employeeApp(state)
      await t.page.loadTodo(); const previous = t.page.data.todo
      assert.equal(t.wx.getStorageSync(reminder.DOT_FLAG_KEY), true)
      state.res = response(bad); t.app.onShow(); await flushReminder()
      assert.equal(t.wx.getStorageSync(reminder.DOT_FLAG_KEY), true, '未知启动响应不得清旧红点')
      assert.deepStrictEqual(dotWrites(t.page), [true])
      await t.page.loadTodo(); assert.match(t.page.data.todoError, /未.*核对/)
      assert.strictEqual(t.page.data.todo, previous); assert.equal(t.wx.getStorageSync(reminder.DOT_FLAG_KEY), true)
      assert.equal(await t.shared.syncPendingReminder(), null, '未核对响应不能作为成功读取结果返回')
      state.res = response(completeSummary()); t.app.onShow(); await flushReminder()
      assert.equal(t.wx.getStorageSync(reminder.DOT_FLAG_KEY), false)
      await t.page.onRetryTodo(); assert.equal(t.page.data.todoError, ''); assert.equal(t.page.data.todo.items.length, 0)
      state.res = response(completeSummary({ waitingStock: 2 }, 1)); t.app.onShow(); await flushReminder()
      assert.equal(t.wx.getStorageSync(reminder.DOT_FLAG_KEY), true)
    })
  }
  await test('B2应用网络/业务失败保留红点，关闭提醒及配送员会话仍主动清理', async () => {
    const state = { res: response(completeSummary({ waitingStock: 1 }, 1)) }, t = employeeApp(state)
    await t.page.loadTodo(); state.fail = true; t.app.onShow(); await flushReminder()
    assert.equal(t.wx.getStorageSync(reminder.DOT_FLAG_KEY), true)
    state.fail = false; state.res = { code: 1, message: '待办未核对', data: completeSummary() }
    t.app.onShow(); await flushReminder(); assert.equal(t.wx.getStorageSync(reminder.DOT_FLAG_KEY), true)
    t.shared.setReminderEnabled(false); assert.equal(t.wx.getStorageSync(reminder.DOT_FLAG_KEY), false)
    state.res = response(incomplete); t.shared.setReminderEnabled(true); await flushReminder()
    assert.equal(t.wx.getStorageSync(reminder.DOT_FLAG_KEY), false)
    state.res = response(completeSummary({ waitingStock: 1 }, 1)); t.app.onShow(); await flushReminder()
    assert.equal(t.wx.getStorageSync(reminder.DOT_FLAG_KEY), true)
    const reads = state.reads
    t.app.setLoginState({ accessToken: 'test-access', refreshToken: 'test-refresh', staffId: 1, role: 'DELIVERY', stationId: 1, bindStatus: 'BOUND' })
    t.app.onShow(); await flushReminder()
    assert.equal(t.wx.getStorageSync(reminder.DOT_FLAG_KEY), false); assert.equal(state.reads, reads)
  })
  await test('B2未知正数也不得替换已核对的无红点状态', async () => {
    const state = { res: response(completeSummary()) }, t = employeeApp(state)
    await t.page.loadTodo(); assert.equal(t.wx.getStorageSync(reminder.DOT_FLAG_KEY), false)
    const bad = completeSummary({ waitingStock: 1 }, 1); bad.complete = false
    state.res = response(bad); t.app.onShow(); await flushReminder()
    t.shared.applyRedDot(bad)
    assert.equal(t.wx.getStorageSync(reminder.DOT_FLAG_KEY), false); assert.deepStrictEqual(dotWrites(t.page), [false])
  })
  await test('首页六项责任可见，逐项跳入正确办理分区，零数移出', async () => {
    const state = { res: response(completeSummary(Object.fromEntries(BUSINESS_PENDING_ITEMS.map(it => [it.key, 1])), 1)) }
    const page = home(state); await page.loadTodo()
    assert.equal(page.data.todo.items.length, 6)
    assert.deepStrictEqual(dotWrites(page), [true])
    for (const key of Object.keys(reminder.BUSINESS_PENDING_ROUTES)) {
      page.onTodoTap({ currentTarget: { dataset: { key } } })
      assert.equal(page.__wx.__calls.nav.at(-1).url, reminder.BUSINESS_PENDING_ROUTES[key])
    }
    state.res.data.items.forEach(it => { it.count = 0 }); state.res.data.p0Total = 0
    await page.loadTodo(); assert.equal(page.data.todo.items.length, 0)
    assert.equal(page.data.todoError, '')
    assert.deepStrictEqual(dotWrites(page), [true, false])
  })
  for (const [name, data] of [
    ['旧16项目录且遗漏complete', { p0Total: 0, items: LEGACY_PENDING_ITEMS.map(it => ({ ...it })) }],
    ['缺六个新键却complete=true', { p0Total: 0, complete: true, items: LEGACY_PENDING_ITEMS.map(it => ({ ...it, available: true })) }],
    ['新责任unavailable/null且遗漏complete', { p0Total: 0, items: completeSummary().items.map(it => it.key === 'returnRefund' ? { ...it, available: false, count: null } : it) }]
  ]) {
    await test('首页独立复现：' + name + '必须常驻未核对并保留旧数', async () => {
      const fresh = home({ res: response(data) }); await fresh.loadTodo()
      assert.match(fresh.data.todoError, /未.*核对/); assert.equal(fresh.data.todoLoading, false)
      assert.equal(fresh.data.todo, null, '首次失败不能制造成功空态')
      const state = { res: response(completeSummary({ returnRefund: 3, pendingAssign: 1 }, 1)) }, page = home(state)
      await page.loadTodo(); const previous = page.data.todo
      state.res = response(data); await page.loadTodo()
      assert.strictEqual(page.data.todo, previous, '未核对响应不能替换已有数字')
      assert.match(page.data.todoError, /未.*核对/); assert.deepStrictEqual(dotWrites(page), [true])
      state.res = response(completeSummary()); await page.onRetryTodo()
      assert.equal(page.data.todoError, ''); assert.deepStrictEqual(page.data.todo.items, [])
    })
  }
  const invalidSummaries = [
    ['重复新责任键', d => d.items.push({ ...d.items.find(it => it.key === 'returnRefund'), count: 0 })],
    ['重复既有键', d => d.items.push({ ...d.items[0] })],
    ['负数', d => { d.items.find(it => it.key === 'waitingStock').count = -1 }],
    ['非整数', d => { d.items.find(it => it.key === 'returnRefund').count = 0.5 }],
    ['字符串数字', d => { d.items.find(it => it.key === 'recoverySend').count = '1' }],
    ['字符串零', d => { d.items.find(it => it.key === 'recoveryReceive').count = '0' }],
    ['缺失count', d => { delete d.items.find(it => it.key === 'barrelDispute').count }],
    ['null数量却available=true', d => { d.items.find(it => it.key === 'barrelHandover').count = null }],
    ['遗漏available', d => { delete d.items.find(it => it.key === 'returnRefund').available }],
    ['字符串available', d => { d.items.find(it => it.key === 'returnRefund').available = 'true' }],
    ['unknown却complete=true', d => { Object.assign(d.items.find(it => it.key === 'returnRefund'), { available: false, count: null }) }],
    ['遗漏complete', d => { delete d.complete }],
    ['字符串complete', d => { d.complete = 'true' }],
    ['complete=false', d => { d.complete = false }],
    ['遗漏p0Total', d => { delete d.p0Total }],
    ['字符串p0Total', d => { d.p0Total = '0' }],
    ['负p0Total', d => { d.p0Total = -1 }],
    ['非整数p0Total', d => { d.p0Total = 0.5 }],
    ['缺失级别', d => { delete d.items.find(it => it.key === 'returnRefund').level }],
    ['空标签', d => { d.items.find(it => it.key === 'returnRefund').label = ' ' }],
    ['坏目录行', d => { d.items.push(null) }],
    ['既有键坏数量', d => { d.items[0].count = '0' }]
  ]
  for (const [name, mutate] of invalidSummaries) {
    await test('首页协议拒绝' + name + '且重试恢复', async () => {
      const state = { res: response(completeSummary({ returnRefund: 2, pendingAssign: 1 }, 1)) }, page = home(state)
      await page.loadTodo(); const previous = page.data.todo
      const invalid = completeSummary(); mutate(invalid); state.res = response(invalid)
      await page.loadTodo(); assert.match(page.data.todoError, /未.*核对/)
      assert.strictEqual(page.data.todo, previous); assert.equal(page.data.todoLoading, false)
      reminder.applyRedDot(invalid)
      assert.deepStrictEqual(dotWrites(page), [true], '失败响应不能清除已核对的红点')
      state.res = response(completeSummary()); await page.onRetryTodo()
      assert.equal(page.data.todoError, ''); assert.equal(page.data.todo.items.length, 0)
    })
  }
  for (const key of BUSINESS_PENDING_ITEMS.map(it => it.key)) {
    await test('首页独立要求新键唯一存在：' + key, async () => {
      const d = completeSummary(); d.items = d.items.filter(it => it.key !== key)
      const page = home({ res: response(d) }); await page.loadTodo()
      assert.match(page.data.todoError, /未.*核对/); assert.equal(page.data.todo, null)
    })
  }
  await test('完整响应仍透传页签P0红点，P1可见；级别覆盖按下发值显示', async () => {
    const state = { res: response(completeSummary({ pendingAssign: 4, returnRefund: 2 }, 1)) }, page = home(state)
    await page.loadTodo(); assert.deepStrictEqual(page.data.todo.items.map(it => it.key), ['returnRefund'])
    assert.deepStrictEqual(dotWrites(page), [true]); assert.equal(page.data.todoError, '')
    const d = completeSummary({ waitingStock: 2 }); d.items.find(it => it.key === 'waitingStock').level = 'P1'
    state.res = response(d); await page.onRetryTodo()
    assert.equal(page.data.todo.items[0].level, 'P1'); assert.deepStrictEqual(dotWrites(page), [true, false])
  })
  await test('只有已收桶待退款：原编号穿透、原页退款后计数消除', async () => {
    const waiting = emptyWaiting(); waiting.returns = [{ recordId: 77, nextAction: 'refund', refundAmount: 30 }]; waiting.counts.returnsTotal = waiting.counts.returnRefund = 1
    const t = setup({ waiting }); await t.page.onShow(); t.page.onReturns(event(77))
    assert.equal(t.wx.__calls.nav.at(-1).url, '/pages/station-mgmt/barrel-return/index?recordId=77')
    let finished = false, detailReads = 0, historyReads = 0, statusCall
    const wx = createWx()
    const recordPage = loadPage('miniapp-delivery/pages/station-mgmt/barrel-return/index.js', { wx, app: managerApp(), stubs: {
      'utils/pending-reminder': { getPendingReturnRecord: async id => { detailReads++; assert.equal(id, '77'); return response({ id: 77, type: 2, status: finished ? 3 : 2, depositRefund: 30, returnDetail: { status: finished ? 'REFUNDED' : 'RECEIVED' } }) }, syncPendingReminder: async () => {} },
      'api/station-mgmt': { getAllBarrelRecords: async () => { historyReads++; throw Error('原记录已被历史上限挤出') }, getRefundUndelivered: async () => response({ records: [], count: 0 }), updateBarrelRecordStatus: async (id, status, body) => { statusCall = [id, status, body]; finished = true; waiting.returns = []; waiting.counts.returnsTotal = waiting.counts.returnRefund = 0; return response({}) } }
    } })
    recordPage.onLoad({ recordId: '77' }); await recordPage.loadData()
    assert.equal(recordPage.data.list[0].id, 77); assert.equal(historyReads, 0)
    await recordPage.submitRefund(77, 'CASH'); await recordPage.loadData()
    assert.deepEqual(statusCall, [77, 3, { refundChannel: 'CASH' }]); assert.equal(recordPage.data.list[0].returnDetail.status, 'REFUNDED')
    assert(detailReads >= 2); await t.page.loadData(); assert.equal(t.page.data.counts.returns, 0); assert.equal(t.page.data.returns.length, 0)
  })
  await test('缺货补齐后同源列表和总数同时消除', async () => {
    const t = setup(); t.state.waiting.stock = [{ orderId: 9, shortageQty: 2 }]; t.state.waiting.counts.waitingStock = 1
    await t.page.loadData(); t.page.onOrder(event(9)); assert.equal(t.wx.__calls.nav.at(-1).url, '/pages/order/detail?id=9')
    t.state.waiting.stock = []; t.state.waiting.counts.waitingStock = 0; await t.page.onRetry()
    assert.equal(t.page.data.counts.stock, 0); assert.equal(t.page.data.stock.length, 0)
  })
  for (const action of ['sent', 'received', 'barrels']) {
    await test(action + '完成后本方责任消除并刷新提醒', async () => {
      const t = setup(), kind = action === 'barrels' ? 'barrels' : 'recoveries'
      t.state.waiting[kind] = [{ orderId: 8, nextAction: action, responsibleStationId: 1 }]; t.state.waiting.counts[kind + 'Total'] = 1
      await t.page.loadData(); await t.page.action(event('8', action)); await t.wx.pendingModal.success({ confirm: true, content: '实际交付凭据' })
      assert(t.state.calls.some(c => Array.isArray(c) && c[0] === action)); assert.equal(t.page.data[kind].length, 0)
      assert.equal(t.page.data.counts[kind], 0); assert.equal(t.state.reminders, 1)
    })
  }
  await test('交付站不能代收到站确认，编造记录也不提交', async () => {
    const t = setup(); t.state.waiting.recoveries = [{ orderId: 8, nextAction: 'sent' }]; t.state.waiting.counts.recoveriesTotal = 1
    await t.page.loadData(); await t.page.action(event(8, 'received')); await t.page.action(event(999, 'sent'))
    assert.equal(t.wx.__calls.modal.length, 0); assert(!t.state.calls.some(Array.isArray))
  })
  await test('争议方案交接：归属方提出后消除本方待办，履约方同意后才交接', async () => {
    const t = setup(); t.state.waiting.barrels = [{ orderId: 8, nextAction: 'proposal' }]; t.state.waiting.counts.barrelsTotal = 1
    await t.page.loadData(); t.page.onProposal(event(8)); t.wx.pendingSheet.success({ tapIndex: 0 }); await t.wx.pendingModal.success({ confirm: true, content: '双方核实同型桶来源' })
    assert.equal(t.state.calls.find(c => Array.isArray(c) && c[0] === 'proposal')[2].barrelMode, 'RETURN_EMPTY'); assert.equal(t.page.data.barrels.length, 0)
    const target = setup(); target.state.waiting.barrels = [{ orderId: 8, nextAction: 'agree', resolutionNote: '归属站方案' }]; target.state.waiting.counts.barrelsTotal = 1
    await target.page.loadData(); await target.page.action(event(8, 'agree')); await target.wx.pendingModal.success({ confirm: true })
    assert.equal(target.page.data.barrels[0].nextAction, 'barrels')
  })
  await test('截断列表的两行不能把真实251笔计为2笔', async () => {
    const t = setup(); t.state.waiting.recoveries = [{ orderId: 8 }, { orderId: 9 }]; t.state.waiting.counts.recoveriesTotal = 251
    await t.page.loadData(); assert.equal(t.page.data.counts.recoveries, 251); assert.equal(t.page.data.recoveries.length, 2); assert.equal(t.page.data.ready.recoveries, true)
  })
  await test('首次读取失败不成为已清空，部分成功保留真实可读分区，重试恢复', async () => {
    const t = setup({ waitingFail: true }); await t.page.loadData()
    assert.equal(t.page.data.ready.returns, false); assert.equal(t.page.data.ready.tickets, true); assert(t.page.data.error)
    t.state.waitingFail = false; await t.page.onRetry(); assert.equal(t.page.data.ready.returns, true); assert.equal(t.page.data.error, '')
    t.state.refusalFail = true; await t.page.onRetry(); assert.equal(t.page.data.ready.stock, true); assert.equal(t.page.data.ready.refusals, false)
  })
  await test('后续读取失败保留旧记录但不允许旧记录办理', async () => {
    const t = setup(); t.state.waiting.recoveries = [{ orderId: 8, nextAction: 'sent' }]; t.state.waiting.counts.recoveriesTotal = 1
    await t.page.loadData(); t.state.waitingFail = true; await t.page.loadData(); assert.equal(t.page.data.recoveries.length, 1)
    await t.page.action(event(8, 'sent')); assert.equal(t.wx.__calls.modal.length, 0); assert.equal(t.page.data.ready.recoveries, false)
  })
  await test('业务拒绝和缺失数量都不作为空态，办理失败不消除责任', async () => {
    const t = setup(); t.api.getWaiting = async () => ({ code: 1, data: emptyWaiting() }); await t.page.loadData(); assert.equal(t.page.data.ready.stock, false)
    t.api.getWaiting = async () => response(Object.assign(emptyWaiting(), { counts: {} })); await t.page.loadData(); assert.equal(t.page.data.ready.returns, false)
    const action = setup({ actionFail: true }); action.state.waiting.recoveries = [{ orderId: 8, nextAction: 'sent' }]; action.state.waiting.counts.recoveriesTotal = 1
    await action.page.loadData(); await action.page.action(event(8, 'sent')); await action.wx.pendingModal.success({ confirm: true, content: '凭据' })
    assert.equal(action.page.data.recoveries.length, 1); assert.equal(action.state.reminders, 0); assert.equal(action.wx.__calls.toast.at(-1).icon, 'none')
  })
  await test('首页失败可重试并保留旧数，缺失结构明确显示未核对', async () => {
    const state = { res: response(completeSummary({ returnRefund: 1 })) }, p = home(state)
    await p.loadTodo(); state.fail = true; await p.loadTodo(); assert(p.data.todoError); assert.equal(p.data.todo.items[0].count, 1)
    state.fail = false; state.res.data.complete = false
    Object.assign(state.res.data.items.find(it => it.key === 'returnRefund'), { count: null, available: false })
    await p.onRetryTodo(); assert(p.data.todoError); assert.equal(p.data.todo.items[0].count, 1)
    let release; state.pending = new Promise(resolve => { release = resolve })
    // 正在重试时保留未核对提示，成功响应到达之后才清除。
    const retry = p.onRetryTodo(); assert.equal(p.data.todoLoading, true); assert(p.data.todoError)
    assert.equal(p.data.todo.items[0].count, 1)
    release(response(completeSummary())); await retry
    assert.equal(p.data.todoError, ''); assert.equal(p.data.todo.items.length, 0)
  })
  await test('旧的慢响应不能覆盖较新的核对结果', async () => {
    const t = setup(); let release
    t.api.getWaiting = () => new Promise(r => { release = r }); const first = t.page.loadData()
    const current = emptyWaiting(); current.stock = [{ orderId: 10 }]; current.counts.waitingStock = 1
    t.api.getWaiting = async () => response(current); await t.page.loadData(); release(response(emptyWaiting())); await first
    assert.equal(t.page.data.stock[0].orderId, 10); assert.equal(t.page.data.loading, false)
  })
  await test('配送员不读取站长清单；P1不点红点而P0覆盖照常点亮', async () => {
    const t = setup(); t.app.globalData.userInfo.role = 'DELIVERY'; await t.page.onShow(); assert.equal(t.state.calls.length, 0)
    global.wx = createWx(); reminder.applyRedDot(completeSummary({ returnRefund: 1 })); assert.equal(wx.getStorageSync(reminder.DOT_FLAG_KEY), false)
    reminder.applyRedDot(completeSummary({ waitingStock: 1 }, 1)); assert.equal(wx.getStorageSync(reminder.DOT_FLAG_KEY), true)
  })
  await test('原申请定位读取失败可重试且不退回无关历史列表', async () => {
    let failed = true, history = 0
    const p = loadPage('miniapp-delivery/pages/station-mgmt/barrel-return/index.js', { wx: createWx(), app: managerApp(), stubs: {
      'utils/pending-reminder': { getPendingReturnRecord: async () => { if (failed) throw Error('offline'); return response({ id: 77, type: 2, status: 2 }) }, syncPendingReminder: async () => {} },
      'api/station-mgmt': { getAllBarrelRecords: async () => { history++; return response([]) } }
    } })
    p.onLoad({ recordId: '77' }); await p.loadData(); assert.equal(p.data.recordsReady, false); assert(p.data.loadError)
    failed = false; await p.onRetry(); assert.equal(p.data.list[0].id, 77); assert.equal(p.data.loadError, ''); assert.equal(history, 0)
  })
  done(); console.log('AQUAFLOW_SUITE_OK ' + passed)
}
main().catch(err => { done(); console.error(err); process.exitCode = 1 })
