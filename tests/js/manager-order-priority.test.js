const assert = require('assert'), fs = require('fs'), path = require('path')
const { loadPage, createWx, createApp, armWatchdog, ROOT } = require('./harness')
const { parseWxml, renderElements } = require('./wxml-tree')
const done = armWatchdog(30000)
const pagePath = 'miniapp-delivery/pages/station-mgmt/orders/index'
const tree = parseWxml(fs.readFileSync(path.join(ROOT, pagePath + '.wxml'), 'utf8'))
const own = extra => ({ id: 41, stationId: 11, deliveryStationId: 11, status: 1,
  statusText: '待配送', deliveryStaffId: null, receiverName: '很长的客户名称用于小屏检查',
  addressSnapshot: '很长的送达地址用于小屏换行检查', itemSummary: '纯净水 2桶，矿泉水 3瓶',
  totalAmount: 98, needCollect: true, collectAmount: 18, payStateText: '待收款',
  payMethodText: '水票抵水费 + 现金付其他费用', ...extra })
function fixture(read = async () => ({ data: [own()] })) {
  const wx = createWx(), app = createApp({ _loginGeneration: 1, globalData: {
    isLogin: true, userInfo: { staffId: 5, role: 'STATION_MANAGER', stationId: 11 } } })
  const requests = []
  const page = loadPage(pagePath + '.js', { wx, app, stubs: {
    'api/station-mgmt': { getOrders: q => { requests.push(q); return read(q) },
      getCrossStationOrders: async () => ({ data: { count: 1, amountTotal: 98, pending: 0,
        delivering: 1, completed: 0, orders: [own({ id: 52, stationId: 22, status: 3,
          statusText: '已送达', receiverName: '合成收货人' })] } }) },
    'utils/request': { get: async () => ({ data: [] }) }
  } })
  return { page, wx, app, requests }
}
const elements = data => renderElements(tree, data, { includeText: true })
const dispatchEvent = id => ({ currentTarget: { dataset: { id } } })
function dispatchQueue({ app, wx }, pending, transfers, tracking) {
  const empty = async () => ({ code: 0, data: [] })
  const scrolls = [], writes = []
  wx.pageScrollTo = options => scrolls.push(options)
  wx.setStorageSync('delivery-coordination-guide-v2', true)
  const queue = loadPage('miniapp-delivery/pages/coordination/index.js', { app, wx, stubs: {
    'utils/request': { get: url => url.endsWith('/station-pending') ? pending() : (transfers || empty)(), post: (...args) => writes.push(args) },
    'api/delivery': { getStaffList: empty, getPoolOrders: empty, getDispatchTracking: tracking || empty,
      getDirectedIncoming: empty, getPendingApprovals: async () => ({ code: 0, data: { customer: [], station: [] } }) }
  } })
  queue.loadStationStatus = () => {}
  queue.loadStationPending = () => {}
  queue.loadTodo = () => {}
  queue.setData({ activeTab: 'approval', lists: { ...queue.data.lists, pending: [own()] } })
  return { queue, scrolls, writes }
}
let passed = 0, failed = 0
async function test(name, fn) {
  try { await fn(); passed++; console.log('PASS ' + name) }
  catch (e) { failed++; console.error('FAIL ' + name + ': ' + e.stack) }
}
;(async () => {
  await test('first read prioritizes pending orders; all history remains reachable', async () => {
    const t = fixture(); await t.page.loadData()
    assert.strictEqual(t.requests[0].status, 1)
    assert.strictEqual(t.page.data.tabs.at(-1).id, 0)
    await t.page.onTabChange({ currentTarget: { dataset: { id: 0 } } })
    assert.strictEqual(t.requests[1].status, null)
  })
  await test('loaded-page badge does not masquerade as a complete order count', async () => {
    const t = fixture(async () => ({ data: Array.from({ length: 20 }, (_, i) => own({ id: i + 1 })) }))
    await t.page.loadData()
    assert.strictEqual(t.page.data.hasMore, true)
    assert(elements(t.page.data).some(n => n.className === 'tab-badge' && n.text === '20+'))
  })
  await test('the coordination all-orders shortcut opens without a status filter on the first read', async () => {
    const wx = createWx()
    const coordination = loadPage('miniapp-delivery/pages/coordination/index.js', { wx, app: createApp() })
    coordination.onOpenOrders()
    const url = new URL(wx.__calls.nav[0].url, 'https://miniapp.invalid')
    assert.strictEqual(url.pathname, '/pages/station-mgmt/orders/index')
    const t = fixture()
    t.page.onLoad(Object.fromEntries(url.searchParams))
    await t.page.onShow()
    assert.strictEqual(t.requests[0].status, null)
  })
  await test('assignment hint never claims another station can be assigned locally', async () => {
    const t = fixture(async () => ({ data: [own(), own({ id: 42, deliveryStaffId: 7 }),
      own({ id: 43, deliveryStationId: 22 }), own({ id: 44, deliveryStationId: null }),
      own({ id: 45, status: 4, deliveryStaffId: null })] }))
    await t.page.loadData()
    assert.deepStrictEqual(t.page.data.list.map(r => r.assignmentText),
      ['尚未安排配送员', '已安排配送员', '', '', ''])
  })
  await test('payment facts separate onsite collection from order amount', async () => {
    const t = fixture(); await t.page.loadData()
    const rendered = elements(t.page.data).map(n => n.text || '').join('\n')
    assert(rendered.includes('付款：待收款'))
    assert(rendered.includes('水票抵水费 + 现金付其他费用'))
    assert(rendered.includes('订单金额'))
    assert(rendered.includes('98'))
    assert(rendered.includes('现场待收 ¥18'))
    assert(!rendered.includes('现场待收 ¥98'))
    assert(!rendered.includes('实收 ¥98'))
    assert(!rendered.includes('应收 ¥98'))
  })
  await test('no onsite collection amount is inferred from an order total', async () => {
    const t = fixture(async () => ({ data: [own({ needCollect: false, collectAmount: 98,
      payStateText: '已付款' }), own({ id: 42, needCollect: true, collectAmount: null }),
      own({ id: 43, needCollect: true, collectAmount: 0 })] }))
    await t.page.loadData()
    assert(!elements(t.page.data).some(n => n.className === 'order-collect'))
  })
  await test('action routes use workbench tab and collection page, not duplicate list actions', async () => {
    const t = fixture(); t.page.onOpenDispatchWork(); t.page.onOpenCollectionWork()
    assert.deepStrictEqual(t.wx.__calls.nav, [
      { type: 'switchTab', url: '/pages/coordination/index' },
      { type: 'navigateTo', url: '/pages/station-mgmt/payments/index' }
    ])
  })
  await test('row shortcut picks the pending tab, clears stale rows and pins only the fresh matching order', async () => {
    const t = fixture(async () => ({ data: [own(), own({ id: 42, deliveryStaffId: 7 }),
      own({ id: 43, deliveryStationId: 22 }), own({ id: 44, status: 3 })] }))
    await t.page.loadData()
    const shortcuts = elements(t.page.data).filter(n => n.attrs.catchtap === 'onOpenDispatchWork')
    assert.deepStrictEqual(shortcuts.map(n => n.id), [41])
    for (const id of [42, 43, 44, 99, '41&stationId=22']) t.page.onOpenDispatchWork(dispatchEvent(id))
    assert.strictEqual(t.wx.__calls.nav.length, 0)
    t.page.onOpenDispatchWork(dispatchEvent(shortcuts[0].id))
    let resolvePending
    const { queue, scrolls, writes } = dispatchQueue(t, () => new Promise(resolve => { resolvePending = resolve }))
    const read = queue.onShow()
    assert.strictEqual(queue.data.activeTab, 'pending')
    assert.deepStrictEqual(queue.data.lists.pending, [])
    assert.strictEqual(t.app.globalData.orderDispatchIntent, undefined)
    resolvePending({ code: 0, data: [own({ id: 70 }), own(), own({ id: 72 })] })
    await read
    assert.deepStrictEqual(queue.data.lists.pending.map(row => row.id), [41, 70, 72])
    assert.strictEqual(queue.data.dispatchFocusId, '41')
    assert.strictEqual(queue.data.showAssignModal, false)
    assert.deepStrictEqual(writes, [])
    assert.deepStrictEqual(scrolls, [{ selector: '#pending-dispatch-queue', offsetTop: -96, duration: 0 }])
  })
  await test('fresh absence differs from a failed queue read and retry can find the same target', async () => {
    const t = fixture(); await t.page.loadData()
    t.page.onOpenDispatchWork(dispatchEvent(41))
    let next = { code: 1, message: '合成读取失败' }
    const { queue, scrolls, writes } = dispatchQueue(t, async () => next)
    await queue.onShow()
    assert.strictEqual(queue.data.dispatchFocusMessage, '暂未查到订单 #41，请重新加载')
    next = { code: 0, data: [own({ id: 70 })] }
    await queue.onRetryVisibleData()
    assert.strictEqual(queue.data.dispatchFocusMessage, '订单 #41 当前不在待分配清单')
    assert.strictEqual(queue.data.dispatchFocusId, '')
    next = { code: 0, data: [own({ id: 70 }), own()] }
    await queue.onRetryVisibleData()
    assert.strictEqual(queue.data.dispatchFocusId, '41')
    assert.strictEqual(queue.data.dispatchFocusMessage, '')
    assert.strictEqual(scrolls.length, 1)
    assert.deepStrictEqual(writes, [])
  })
  await test('a fresh transfer keeps its own actions when located from the order list', async () => {
    const t = fixture(); await t.page.loadData()
    t.page.onOpenDispatchWork(dispatchEvent(41))
    const { queue, writes } = dispatchQueue(t, async () => ({ code: 0, data: [own({ id: 70 })] }),
      async () => ({ code: 0, data: [own({ transferPendingKind: 'DIRECTED' })] }))
    await queue.onShow()
    assert.strictEqual(queue.data.lists.pending[0].id, 41)
    assert.strictEqual(queue.data.lists.pending[0].transferKind, 'directed')
    assert.strictEqual(queue.data.showAssignModal, false)
    assert.deepStrictEqual(writes, [])
  })
  await test('a changed login, changed station or expired switch cannot focus the previous order', async () => {
    for (const change of [
      t => t.app._loginGeneration++,
      t => { t.app.globalData.userInfo.stationId = 22 },
      t => { t.app.globalData.orderDispatchIntent.expiresAt = 0 }
    ]) {
      const t = fixture(); await t.page.loadData()
      t.page.onOpenDispatchWork(dispatchEvent(41))
      change(t)
      const { queue, scrolls } = dispatchQueue(t, async () => ({ code: 0, data: [own({ id: 70 }), own()] }))
      await queue.onShow()
      assert.strictEqual(queue.data.activeTab, 'approval')
      assert.deepStrictEqual(queue.data.lists.pending.map(row => row.id), [70, 41])
      assert.strictEqual(queue.data.dispatchFocusId, '')
      assert.deepStrictEqual(scrolls, [])
      assert.strictEqual(t.app.globalData.orderDispatchIntent, undefined)
    }
  })
  await test('failed native switch clears its intent and makes retry visible', async () => {
    const t = fixture(); await t.page.loadData()
    t.wx.switchTab = options => options.fail()
    t.page.onOpenDispatchWork(dispatchEvent(41))
    assert.strictEqual(t.app.globalData.orderDispatchIntent, undefined)
    assert(t.wx.__calls.toast.some(n => n.title === '待分配暂时打不开，请重试'))
  })
  await test('the original station opens the correct outgoing queue while local rows still open details', async () => {
    for (const [dispatchKind, subtab, key] of [['POOL', 'pool', 'dispatchPool'], ['DIRECTED', 'directed', 'dispatchDirected']]) {
      const outgoing = own({ id: 42, deliveryStationId: 22, deliveryStaffId: 7, status: 2, dispatchKind })
      const recalled = own({ id: 70, dispatchKind })
      const t = fixture(async () => ({ data: [own(), outgoing] }))
      await t.page.loadData()
      assert(elements(t.page.data).some(node => node.text === '查看外派进度 ›'))
      t.page.onOrderTap(dispatchEvent(42))
      assert.strictEqual(t.app.globalData.orderDispatchIntent.queue, 'dispatch')
      assert.deepStrictEqual(t.wx.__calls.nav, [{ type: 'switchTab', url: '/pages/coordination/index' }])
      const { queue, scrolls, writes } = dispatchQueue(t, async () => ({ code: 0, data: [] }), undefined,
        async () => ({ code: 0, data: [recalled, outgoing] }))
      await queue.onShow()
      assert.strictEqual(queue.data.activeTab, 'dispatch')
      assert.strictEqual(queue.data.dispatchTab, subtab)
      assert.deepStrictEqual(queue.data.lists[key].map(row => row.id), [42, 70])
      assert.strictEqual(queue.data.dispatchFocusId, '42')
      assert.deepStrictEqual(scrolls, [{ selector: '#outgoing-dispatch-queue', offsetTop: -96, duration: 0 }])
      queue.onDispatchTrackingTap(dispatchEvent(42))
      assert.strictEqual(t.wx.__calls.nav.length, 1)
      queue.onDispatchTrackingTap(dispatchEvent(70))
      assert.deepStrictEqual(t.wx.__calls.nav.at(-1), { type: 'navigateTo', url: '/pages/order/detail?id=70' })
      t.page.onOrderTap(dispatchEvent(41))
      assert.deepStrictEqual(t.wx.__calls.nav.at(-1), { type: 'navigateTo', url: '/pages/order/detail?id=41' })
      assert.deepStrictEqual(writes, [])
    }
  })
  await test('the original station follows an outgoing order to its pending directed return', async () => {
    const outgoing = own({ id: 42, deliveryStationId: 22, status: 2 })
    const t = fixture(async () => ({ data: [outgoing] }))
    await t.page.loadData()
    t.page.onOrderTap(dispatchEvent(42))
    const { queue, scrolls, writes } = dispatchQueue(t,
      async () => ({ code: 0, data: [own({ id: 70 })] }),
      async () => ({ code: 0, data: [{ ...outgoing, transferPendingKind: 'DIRECTED' }] }))
    await queue.onShow()
    assert.strictEqual(queue.data.activeTab, 'pending')
    assert.strictEqual(queue.data.lists.pending[0].id, 42)
    assert.strictEqual(queue.data.lists.pending[0].transferPending, true)
    assert.deepStrictEqual(scrolls, [{ selector: '#pending-dispatch-queue', offsetTop: -96, duration: 0 }])
    assert.strictEqual(queue.data.showAssignModal, false)
    assert.deepStrictEqual(writes, [])
  })
  await test('outgoing tracking distinguishes an absent row from a failed read', async () => {
    const t = fixture(async () => ({ data: [own({ id: 42, deliveryStationId: 22, status: 2 })] }))
    await t.page.loadData()
    t.page.onOrderTap(dispatchEvent(42))
    let tracking = { code: 1, message: '合成外派读取失败' }
    const { queue } = dispatchQueue(t, async () => ({ code: 0, data: [] }), undefined, async () => tracking)
    await queue.onShow()
    assert.strictEqual(queue.data.dispatchFocusMessage, '暂未查到订单 #42，请重新加载')
    tracking = { code: 0, data: [] }
    await queue.onRetryVisibleData()
    assert.strictEqual(queue.data.dispatchFocusMessage, '订单 #42 当前不在外派清单')
    assert.strictEqual(queue.data.dispatchFocusId, '')
  })
  await test('current-station card precedes cross-station summary and cross count labels include delivered', async () => {
    const t = fixture(); await t.page.loadData()
    const rendered = elements(t.page.data)
    assert(rendered.findIndex(n => n.className === 'order-card') < rendered.findIndex(n =>
      (n.className || '').includes('cross-card')))
    const visible = rendered.map(n => n.text || '').join('\n')
    assert(visible.includes('配送中/已送达'))
    assert(visible.includes('订单金额合计'))
  })
  await test('own-list failure does not masquerade as an empty order list', async () => {
    const t = fixture(async () => { throw new Error('合成读取失败') }); await t.page.loadData()
    assert.strictEqual(t.page.data.listError, '合成读取失败')
    assert(!elements(t.page.data).some(n => n.className === 'empty'))
  })
  done()
  if (failed) process.exitCode = 1
  else console.log('AQUAFLOW_SUITE_OK ' + passed)
})().catch(e => { done(); console.error(e); process.exitCode = 1 })
