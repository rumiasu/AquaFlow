const assert = require('assert'), fs = require('fs'), path = require('path')
const { loadPage, createWx, createApp, armWatchdog, ROOT } = require('./harness')
const { parseWxml, renderElements } = require('./wxml-tree')
const done = armWatchdog(30000)
const pagePath = 'miniapp-delivery/pages/station-mgmt/orders/index'
const tree = parseWxml(fs.readFileSync(path.join(ROOT, pagePath + '.wxml'), 'utf8'))
const event = id => ({ currentTarget: { dataset: { id } } })
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
const row = (id = 71, status = 2) => ({ id, status, statusText: '合成状态', stationId: 22,
  deliveryStationId: 11, receiverName: '合成收货人', addressSnapshot: '合成送货地址', totalAmount: 20 })
const crossResponse = (orders = [row()]) => ({ data: { count: orders.length, orders, amountTotal: 20 } })

function fixture(options = {}) {
  delete require.cache[path.join(ROOT, 'miniapp-delivery/utils/station-history-customer.js')]
  const wx = createWx(), app = createApp({ _loginGeneration: 1,
    globalData: { isLogin: true, userInfo: { staffId: 5, role: 'STATION_MANAGER', stationId: 11 } } })
  app.isStationManager = () => ['STATION_MANAGER', 'MANAGER'].includes(
    String(app.globalData.userInfo?.staffRole || app.globalData.userInfo?.role || '').toUpperCase())
  app.canAccessStationBusiness = () => !!app.globalData.userInfo?.stationId
  const reads = [], request = { get: options.getCustomers || (async () => ({ data: [] })) }
  const page = loadPage(pagePath + '.js', { wx, app, stubs: {
    'api/station-mgmt': {
      getOrders: options.getOrders || (async () => ({ data: [] })),
      getCrossStationOrders: (...args) => { reads.push(args); return options.getCross ? options.getCross() : Promise.resolve(crossResponse()) }
    }, 'utils/request': request, './request': request
  } })
  return { page, wx, app, reads }
}
async function loaded(options) {
  const t = fixture(options); await t.page.loadData(); t.page.onToggleCrossStation(); return t
}
function findPath(node, predicate, ancestors = []) {
  const current = ancestors.concat(node)
  if (predicate(node)) return current
  for (const child of node.children || []) { const found = findPath(child, predicate, current); if (found) return found }
  return null
}
const hasClass = name => node => (node.attrs?.class || '').split(/\s+/).includes(name)
// Project WXML ancestry, dataset binding and catchtap/bindtap propagation into the real handlers.
function tap(page, sourcePath, order = row()) {
  const called = []
  for (const node of [...sourcePath].reverse()) {
    const attrs = node.attrs || {}, handler = attrs.catchtap || attrs.bindtap
    if (handler) {
      const binding = { tag: node.tag, attrs: 'data-id' in attrs ? { 'data-id': attrs['data-id'] } : {}, children: [] }
      const element = renderElements({ children: [binding] }, { ...page.data, item: order })[0]
      assert.strictEqual(typeof page[handler], 'function', handler)
      called.push(handler); page[handler](event(element.id))
    }
    if ('catchtap' in attrs) break
  }
  return called
}
let passed = 0, failed = 0
async function test(name, fn) {
  try { await fn(); passed++; console.log('PASS ' + name) }
  catch (err) { failed++; console.error('FAIL ' + name + ': ' + err.stack) }
}
;(async () => {
  await test('authorized inbound orders use the registered employee detail route with only the order id', async () => {
    const orders = [1, 2, 3, 4].map(status => row(70 + status, status))
    const t = await loaded({ getCross: async () => crossResponse(orders) })
    for (const order of orders) t.page.onCrossStationOrderTap(event(order.id))
    assert.deepStrictEqual(t.wx.__calls.nav.map(call => call.url), orders.map(order => '/pages/order/detail?id=' + order.id))
    assert.ok(JSON.parse(fs.readFileSync(path.join(ROOT, 'miniapp-delivery/app.json'), 'utf8')).pages.includes('pages/order/detail'))
    assert.deepStrictEqual(t.reads, [[]], 'cross-station scope must remain supplied by the authenticated server')
  })
  await test('rendered row and nested details stop at catchtap without collapsing the summary', async () => {
    for (const target of ['cross-row', 'customer-name', 'cross-row-addr', 'cross-row-link']) {
      const t = await loaded(), crossPath = findPath(tree, hasClass('cross-row'))
      const targetPath = target === 'cross-row' ? crossPath : findPath(crossPath.at(-1), hasClass(target), crossPath.slice(0, -1))
      assert.ok(renderElements(tree, t.page.data).some(node => node.className === 'cross-row' && node.id === 71))
      assert.deepStrictEqual(tap(t.page, targetPath), ['onCrossStationOrderTap'])
      assert.deepStrictEqual(t.wx.__calls.nav, [{ type: 'navigateTo', url: '/pages/order/detail?id=71' }])
      assert.strictEqual(t.page.data.crossExpanded, true)
    }
  })
  await test('summary toggle still expands and collapses without opening an order', async () => {
    const t = fixture(); await t.page.loadData()
    const header = findPath(tree, hasClass('cross-head'))
    assert.deepStrictEqual(tap(t.page, header), ['onToggleCrossStation']); assert.strictEqual(t.page.data.crossExpanded, true)
    tap(t.page, header); assert.strictEqual(t.page.data.crossExpanded, false)
    assert.strictEqual(renderElements(tree, t.page.data).filter(node => node.className === 'cross-row').length, 0)
    t.page.onCrossStationOrderTap(event(71)); assert.strictEqual(t.wx.__calls.nav.length, 0)
  })
  await test('invalid or unlisted ids cannot create detail navigation', async () => {
    const t = await loaded()
    for (const id of [undefined, null, '', 0, -1, '071', '71&stationId=22', '71/../72', 72]) t.page.onCrossStationOrderTap(event(id))
    assert.strictEqual(t.wx.__calls.nav.length, 0)
  })
  await test('changed account, login generation, station, role, binding and login state invalidate old row taps', async () => {
    const changes = [
      t => { t.app.globalData.userInfo = { ...t.app.globalData.userInfo } },
      t => { t.app._loginGeneration++ },
      t => { t.app.globalData.userInfo.stationId = 22 },
      t => { t.app.globalData.userInfo.staffRole = 'DELIVERY' },
      t => { t.app.canAccessStationBusiness = () => false },
      t => { t.app.globalData.isLogin = false },
      t => { t.app.globalData.userInfo = null },
      t => { t.page.setData({ customerId: 99 }) },
      t => { t.page.onHide() }
    ]
    for (const change of changes) { const t = await loaded(); change(t); t.page.onCrossStationOrderTap(event(71)); assert.strictEqual(t.wx.__calls.nav.length, 0) }
  })
  await test('legacy manager aliases still follow the application role check', async () => {
    const t = fixture(); t.app.globalData.userInfo.role = 'manager'
    await t.page.loadData(); t.page.onToggleCrossStation(); t.page.onCrossStationOrderTap(event('71'))
    assert.strictEqual(t.wx.__calls.nav[0].url, '/pages/order/detail?id=71')
  })
  await test('late cross-station reads cannot restore a prior login or station', async () => {
    for (const change of [t => { t.app._loginGeneration++ }, t => { t.app.globalData.userInfo.stationId = 22 }]) {
      const old = deferred(), t = fixture({ getCross: () => old.promise })
      const pending = t.page.loadData(); change(t); old.resolve(crossResponse()); await pending
      t.page.onToggleCrossStation(); t.page.onCrossStationOrderTap(event(71))
      assert.strictEqual(t.page.data.crossStation, null); assert.strictEqual(t.wx.__calls.nav.length, 0)
    }
  })
  await test('unload preserves inherited invalidation for late customer, owned-order and cross-station reads', async () => {
    const customers = deferred(), orders = deferred(), cross = deferred()
    const t = fixture({ getCustomers: () => customers.promise, getOrders: () => orders.promise, getCross: () => cross.promise })
    const searching = t.page.searchHistoryCustomers(), loading = t.page.loadData()
    t.page.onUnload()
    customers.resolve({ data: [{ id: 8, name: '迟到客户' }] }); orders.resolve({ data: [{ id: 88 }] }); cross.resolve(crossResponse())
    await Promise.all([searching, loading])
    assert.deepStrictEqual(t.page.data.customerResults, []); assert.deepStrictEqual(t.page.data.list, [])
    assert.strictEqual(t.page.data.crossStation, null); assert.strictEqual(t.page._paymentsSeq, 1)
    t.page.onCrossStationOrderTap(event(71)); assert.strictEqual(t.wx.__calls.nav.length, 0)
  })
  await test('refresh failure removes the old authorization snapshot and a successful retry restores navigation', async () => {
    let call = 0
    const t = await loaded({ getCross: async () => { if (++call === 2) throw new Error('合成读取失败'); return crossResponse() } })
    await t.page.loadData(); t.page.onCrossStationOrderTap(event(71)); assert.strictEqual(t.wx.__calls.nav.length, 0)
    assert.strictEqual(t.page.data.crossState, 'error')
    await t.page.onRetryCrossStation(); t.page.onCrossStationOrderTap(event(71)); assert.strictEqual(t.wx.__calls.nav.length, 1)
  })
  await test('own-list failure and pagination do not block a successfully read cross-station order', async () => {
    const t = await loaded({ getOrders: async () => { throw new Error('合成本站读取失败') } })
    t.page.onCrossStationOrderTap(event(71)); assert.strictEqual(t.wx.__calls.nav.length, 1)
    const u = await loaded({ getOrders: async () => ({ data: Array.from({ length: 20 }, (_, index) => ({ id: index + 100 })) }) })
    await u.page.onLoadMore(); u.page.onCrossStationOrderTap(event(71)); assert.strictEqual(u.wx.__calls.nav.length, 1)
  })
  await test('own-station row keeps its existing detail handler', async () => {
    const t = await loaded({ getOrders: async () => ({ data: [row(81)] }) })
    assert.deepStrictEqual(tap(t.page, findPath(tree, hasClass('order-card')), row(81)), ['onOrderTap'])
    assert.strictEqual(t.wx.__calls.nav[0].url, '/pages/order/detail?id=81')
  })
  done()
  if (failed) process.exitCode = 1
  else console.log('AQUAFLOW_SUITE_OK ' + passed)
})().catch(err => { done(); console.error(err); process.exitCode = 1 })
