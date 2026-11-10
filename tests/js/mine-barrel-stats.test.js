'use strict'
const assert = require('node:assert/strict'), fs = require('node:fs'), path = require('node:path')
const { loadPage, createApp, createWx, armWatchdog, ROOT } = require('./harness')
const { parseWxml, renderElements } = require('./wxml-tree')
const done = armWatchdog(), tests = [], test = (name, run) => tests.push({ name, run })
const tree = parseWxml(fs.readFileSync(path.join(ROOT, 'miniapp-delivery/pages/mine/index.wxml'), 'utf8'))
const ok = data => ({ code: 0, data })
const bucket = qty => ({ deliveryBucketQty: qty, deliveryBucketQtyKnown: true, deliveryBucketUnknownOrders: 0 })
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r }); return { promise, resolve } }
function fixture(manager = false) {
  const wx = createWx(), calls = []
  const app = createApp({ _loginGeneration: 1, globalData: { isLogin: true,
    userInfo: { staffId: 7, role: manager ? 'STATION_MANAGER' : 'DELIVERY', stationId: 3, bindStatus: 'BOUND' } } })
  const source = {
    delivery: async () => ok({ deliveringCount: 2, completedCount: 3, deliveringBarrels: 8, completedBarrels: 12, returnBarrels: 4 }),
    profit: async () => ok({ orderCount: 2, ...bucket(8), totalIncome: 100, costComplete: false })
  }
  const page = loadPage('miniapp-delivery/pages/mine/index.js', { wx, app, stubs: {
    'api/delivery': { getTodayStats: () => { calls.push('delivery'); return source.delivery() } },
    'utils/request': { get: url => {
      calls.push(url)
      if (url.includes('gross-profit')) return source.profit()
      if (url.includes('owed-barrels')) return Promise.resolve(ok([]))
      return Promise.resolve(ok({ items: [{ key: 'barrelException', count: 0 }] }))
    } }
  } })
  page.setData({ isLogin: true, canAccessBusiness: true, isManager: manager, userInfo: app.globalData.userInfo })
  const elements = () => renderElements(tree, page.data, { includeText: true })
  return { page, app, wx, calls, source, elements,
    values: () => elements().filter(n => n.className.split(/\s+/).includes('stats-value')).map(n => n.text) }
}

test('delivery cards show server barrel quantities and preserve their task destinations', async () => {
  const t = fixture(); await t.page.loadStats(false)
  assert.deepEqual(t.values(), ['8 桶', '12 桶', '4 桶']); assert.deepEqual(t.calls, ['delivery'])
  for (const tab of ['delivering', 'completed']) {
    t.page.onOpenTaskStat({ currentTarget: { dataset: { tab } } })
    assert.equal(t.wx.__calls.nav.at(-1).url, '/pages/home/index')
    assert.equal(t.app.globalData.mineDeliveryTabIntent.tab, tab)
  }
})
test('manager shows creation-period barrel quantity without calling personal delivery stats', async () => {
  const t = fixture(true); await t.page.loadStats(true)
  assert.deepEqual(t.values(), ['¥100.00', '8 桶'])
  assert(t.elements().some(n => n.text === '今日下单桶数')); assert(!t.calls.includes('delivery'))
  assert(t.calls.find(u => /gross-profit\?from=\d{4}-\d{2}-\d{2}&to=\d{4}-\d{2}-\d{2}/.test(u)))
  t.page.onGoTodayProfit(); assert.equal(t.wx.__calls.nav.at(-1).url, '/pages/station-mgmt/gross-profit/index?range=today')
  const bucketCard = t.elements().find(n => n.attrs?.bindtap === 'onGoTodayBuckets')
  assert(bucketCard)
  t.page[bucketCard.attrs.bindtap]()
  assert.equal(t.wx.__calls.nav.at(-1).url, '/pages/station-mgmt/dashboard/index?range=today')
})
test('old server counts never masquerade as verified bucket snapshots', async () => {
  const worker = fixture(); worker.source.delivery = async () => ok({ deliveringCount: 5, completedCount: 9, returnBarrels: 0 })
  await worker.page.loadStats(false); assert.deepEqual(worker.values(), ['待核实', '待核实', '0 桶']); assert(worker.page.data.statsError)
  const manager = fixture(true); manager.source.profit = async () => ok({ orderCount: 9, barrelCount: 7, totalIncome: 100, costComplete: false })
  await manager.page.loadStats(true); assert.deepEqual(manager.values(), ['¥100.00', '待核实']); assert(manager.page.data.managerStatsError)
})
test('zero barrels remain zero and do not imply zero orders for non-barrel sales', async () => {
  const t = fixture(true)
  for (const orderCount of [1, 0]) {
    t.source.profit = async () => ok({ orderCount, ...bucket(0), totalIncome: 0, costComplete: false })
    await t.page.loadStats(true); assert.deepEqual(t.values(), ['¥0.00', '0 桶'])
    assert.equal(t.elements().some(n => n.text.includes('今日暂无订单')), orderCount === 0)
  }
  const worker = fixture(); worker.source.delivery = async () => ok({ deliveringBarrels: 0, completedBarrels: 0, returnBarrels: 0 })
  await worker.page.loadStats(false); assert.deepEqual(worker.values(), ['0 桶', '0 桶', '0 桶']); assert.equal(worker.page.data.statsError, '')
})
test('missing and invalid quantities stay unknown rather than being rounded or converted to zero', async () => {
  for (const value of [undefined, null, '', -1, 1.5, 'invalid', Number.MAX_SAFE_INTEGER + 1]) {
    const t = fixture(); t.source.delivery = async () => ok({ deliveringBarrels: value, completedBarrels: value, returnBarrels: value })
    await t.page.loadStats(false); assert.deepEqual(t.values(), ['待核实', '待核实', '待核实']); assert(t.page.data.statsError)
    assert.equal(t.page._decorateProfit({ orderCount: 1, ...bucket(value), totalIncome: 20 }).barrelCountText, '待核实')
  }
})
test('confirmed historical gaps show unknown without a failed-read toast or retry', async () => {
  const t = fixture(true)
  t.source.profit = async () => ok({
    orderCount: 2, totalIncome: 100, barrelCount: 8,
    deliveryBucketQty: null, deliveryBucketQtyKnown: false, deliveryBucketUnknownOrders: 1
  })
  await t.page.loadStats(true)
  assert.deepEqual(t.values(), ['¥100.00', '待核实'])
  assert.equal(t.calls.length, 3)
  assert.equal(t.page.data.managerStatsError, '')
  assert.deepEqual(t.wx.__calls.toast, [])
  assert(!t.elements().some(node => node.attrs?.bindtap === 'onRetryStats'))
})
test('failed profit request still shows retry and recovers verified buckets', async () => {
  const t = fixture(true)
  t.source.profit = async () => { throw Error('合成断网') }
  await t.page.loadStats(true)
  assert.deepEqual(t.values(), ['待核实', '待核实'])
  assert(t.page.data.managerStatsError)
  assert(t.elements().some(node => node.attrs?.bindtap === 'onRetryStats'))
  assert(t.wx.__calls.toast.some(toast => toast.title === '部分经营数据没加载出来'))
  t.source.profit = async () => ok({ orderCount: 1, totalIncome: 20, ...bucket(0) })
  await t.page.onRetryStats()
  assert.deepEqual(t.values(), ['¥20.00', '0 桶'])
  assert.equal(t.page.data.managerStatsError, '')
})
test('failed reads hide old totals and retry restores confirmed barrel quantities', async () => {
  const t = fixture(); await t.page.loadStats(false)
  t.source.delivery = async () => { throw Error('合成断网') }
  await t.page.loadStats(false); assert.deepEqual(t.values(), ['待核实', '待核实', '待核实']); assert(t.page.data.statsError)
  t.source.delivery = async () => ok({ deliveringBarrels: 2, completedBarrels: 5, returnBarrels: 1 })
  await t.page.onRetryStats(); assert.deepEqual(t.values(), ['2 桶', '5 桶', '1 桶']); assert.equal(t.page.data.statsError, '')
})
test('late quantities cannot replace a new read or cross a changed staff session', async () => {
  for (const switchStaff of [false, true]) {
    const t = fixture(), slow = deferred(); t.source.delivery = () => slow.promise
    const pending = t.page.loadStats(false)
    if (switchStaff) { t.app._loginGeneration++; t.app.globalData.userInfo.staffId = 9 }
    t.source.delivery = async () => ok({ deliveringBarrels: 1, completedBarrels: 2, returnBarrels: 3 })
    await t.page.loadStats(false); slow.resolve(ok({ deliveringBarrels: 90, completedBarrels: 90, returnBarrels: 90 })); await pending
    assert.deepEqual(t.values(), ['1 桶', '2 桶', '3 桶'])
  }
})
test('loading, failure, and success have distinct visible states', async () => {
  const t = fixture(); let reject
  t.source.delivery = () => new Promise((resolve, fail) => { reject = fail })
  const pending = t.page.loadStats(false)
  assert.deepEqual(t.values(), ['加载中…', '加载中…', '加载中…'])
  reject(Error('合成断网')); await pending
  assert.deepEqual(t.values(), ['待核实', '待核实', '待核实']); assert(t.page.data.statsError)
  t.source.delivery = async () => ok({ deliveringBarrels: 0, completedBarrels: 1, returnBarrels: 2 })
  await t.page.onRetryStats(); assert.deepEqual(t.values(), ['0 桶', '1 桶', '2 桶'])
})
test('manager overview shows loading while reads are pending, then verified values', async () => {
  const t = fixture(true), slow = deferred(); t.source.profit = () => slow.promise
  const pending = t.page.loadStats(true)
  assert.deepEqual(t.values(), ['加载中…', '加载中…'])
  slow.resolve(ok({ orderCount: 1, ...bucket(3), totalIncome: 15, costComplete: false }))
  await pending; assert.deepEqual(t.values(), ['¥15.00', '3 桶'])
})
;(async () => {
  for (const t of tests) { await t.run(); console.log('PASS ' + t.name) }
  done(); console.log('AQUAFLOW_SUITE_OK ' + tests.length)
})().catch(error => { done(); console.error(error); process.exitCode = 1 })
