// Actual report Page / drawing data only; Canvas lookup is stubbed, no pixels or HTTP.
const assert = require('node:assert/strict')
const { loadPage, createApp, armWatchdog } = require('./harness')
const done = armWatchdog(30000), tests = [], test = (name, run) => tests.push({ name, run })
const event = key => ({ currentTarget: { dataset: { key } } })
const ok = data => ({ code: 0, data })
function report(range = '7d', orders = 3, amount = 120) {
  return { range, label: range === '7d' ? '近7天' : '近30天', summary: { deliveryBucketQty: 8, orderCount: orders, grossAmount: amount, paidAmount: amount, newCustomers: 0, cancelledOrders: 0, barrelsIn: 0 },
    trend: [{ label: '10/22', orders, amount }, { label: '10/23', orders: 0, amount: 0 }],
    statusDistribution: [], payMethodDistribution: [], topProducts: [], topCustomers: [], staffPerformance: [], hourDistribution: [{ label: '08', orders }] }
}
function fixture(read = async range => ok(report(range))) {
  const page = loadPage('miniapp-delivery/pages/station-mgmt/dashboard/index.js', {
    app: createApp(), stubs: { 'api/station-mgmt': { getDashboardReport: read } }
  })
  const draws = []
  page.getCanvas = id => { draws.push({ id, loading: page.data.loading, metric: page.data.trendMetric,
    values: structuredClone(page.data.trendChart && page.data.trendChart.values), title: page.data.trendTitle }) }
  return { page, draws }
}
test('WB-05 order count 3 / amount 120 switch uses the selected values and title, then switches back', async () => {
  const t = fixture(); await t.page.loadData()
  assert.deepEqual(t.page.data.trendChart.values, [3, 0])
  t.page.onMetricChange(event('amount'))
  assert.deepEqual(t.page.data.trendChart.values, [120, 0]); assert.equal(t.page.data.trendChart.max, 120)
  assert(t.page.data.trendTitle.includes('订单金额')); assert.equal(t.draws.at(-1).metric, 'amount')
  t.page.onMetricChange(event('orders'))
  assert.deepEqual(t.page.data.trendChart.values, [3, 0]); assert(t.page.data.trendTitle.includes('订单量'))
})
test('a new range preserves selected metric while replacing values from the new report', async () => {
  const t = fixture(async range => ok(report(range, range === '30d' ? 9 : 3, range === '30d' ? 999 : 120)))
  await t.page.loadData(); t.page.onMetricChange(event('amount'))
  await t.page.onRangeChange(event('30d')); await new Promise(setImmediate)
  assert.equal(t.page.data.range, '30d'); assert.deepEqual(t.page.data.trendChart.values, [999, 0])
  assert.equal(t.page.data.trendMetric, 'amount'); assert(t.page.data.trendTitle.includes('近30天订单金额'))
})
test('zero amount remains zero instead of falling back to order count', async () => {
  const t = fixture(async () => ok(report('7d', 3, 0))); await t.page.loadData()
  t.page.onMetricChange(event('amount')); assert.deepEqual(t.page.data.trendChart.values, [0, 0])
})
test('today stays on hourly order distribution and ignores a stale amount tap', async () => {
  const t = fixture(async () => ok(report('today'))); await t.page.loadData()
  t.page.onMetricChange(event('amount'))
  assert.equal(t.page.data.trendMetric, 'orders'); assert.equal(t.page.data.metricSwitchable, false)
  assert.deepEqual(t.page.data.trendChart.values, [3]); assert.equal(t.page.data.trendTitle, '今日下单时段分布')
})
test('initial draws start after the canvas host is visible in the page condition', async () => {
  const t = fixture(); await t.page.loadData(); assert(t.draws.length)
  assert(t.draws.every(draw => draw.loading === false))
})
test('unknown metric and taps while loading or failed never authorize stale report drawing', async () => {
  const t = fixture(); await t.page.loadData(); const count = t.draws.length
  t.page.onMetricChange(event('profit')); assert.equal(t.draws.length, count)
  t.page.setData({ loading: true }); t.page.onMetricChange(event('amount')); assert.equal(t.draws.length, count)
  t.page.setData({ loading: false, report: null }); t.page.onMetricChange(event('amount')); assert.equal(t.draws.length, count)
})
test('a delayed metric setData callback cannot prepare an already replaced report', async () => {
  const t = fixture(); await t.page.loadData()
  const set = t.page.setData; let callback
  t.page.setData = (patch, cb) => { set(patch); callback = cb }
  t.page.onMetricChange(event('amount')); const count = t.draws.length
  t.page.data.report = null; callback()
  assert.equal(t.draws.length, count)
})
test('old range response finishing last cannot change the latest report or metric values', async () => {
  let resolve
  const old = new Promise(r => { resolve = r }), t = fixture(range => range === '7d' ? old : Promise.resolve(ok(report('30d', 9, 999))))
  const first = t.page.loadData(); t.page.setData({ range: '30d', trendMetric: 'amount' }); await t.page.loadData()
  resolve(ok(report('7d'))); await first
  assert.deepEqual(t.page.data.trendChart.values, [999, 0]); assert(t.page.data.trendTitle.includes('近30天'))
})
test('delivery bucket card leads while staff performance keeps order and completion facts', async () => {
  const r = report()
  r.staffPerformance = [{ staffId: 1, staffName: '张师傅', totalOrders: 4, completedOrders: 3, completeRate: 75, deliveryBucketQty: 8, amount: 120 }]
  const t = fixture(async () => ok(r)); await t.page.loadData()
  assert.deepEqual(t.page.data.report.metricCards.map(card => card.key), ['deliveryBucketQty', 'orderCount', 'grossAmount', 'paidAmount', 'newCustomers', 'cancelledOrders'])
  assert.deepEqual(t.page.data.report.metricCards.map(card => card.label), ['配送桶数', '订单数', '订单金额', '订单已付款', '新客户', '取消单'])
  assert(!t.page.data.report.metricCards.some(card => card.label === '新增桶'))
  t.page.setData({ analysisExpanded: true })
  const fs = require('node:fs'), path = require('node:path'), { parseWxml, renderElements } = require('./wxml-tree')
  const tree = parseWxml(fs.readFileSync(path.resolve(__dirname, '../../miniapp-delivery/pages/station-mgmt/dashboard/index.wxml'), 'utf8'))
  const text = renderElements(tree, t.page.data, { includeText: true }).map(n => n.text || '').join(' ')
  assert(text.includes('8 桶 · 4 单')); assert(text.includes('完成 3 单 · 完成率 75%'))
})
test('successful zero amount with orders never becomes an empty comforting state', async () => {
  const t = fixture(async () => ok(report('today', 3, 0))); await t.page.loadData()
  assert.equal(t.page.data.report.hasData, true)
  assert.equal(t.page.data.report.metricCards[2].label, '订单金额')
  assert.equal(t.page.data.report.metricCards[2].valueText, '¥0.00')
})
test('successful empty today and historical ranges receive different quiet empty copy', async () => {
  const t = fixture(async range => ok(report(range, 0, 0)))
  t.page.setData({ range: 'today' }); await t.page.loadData()
  assert.equal(t.page.data.report.hasData, false)
  assert.equal(t.page.data.report.emptyTitle, '截至当前暂无订单')
  assert.equal(t.page.data.report.emptyCare, '可切换时间范围查看。')
  t.page.setData({ range: '7d' }); await t.page.loadData()
  assert(t.page.data.report.emptyTitle.includes('所选周期'))
})
test('failed or incomplete report keeps retry state and never produces a comforting zero', async () => {
  for (const response of [{ code: 1, message: '读取失败' }, ok({ summary: { orderCount: 0 } })]) {
    const t = fixture(async () => response); await t.page.loadData()
    assert.equal(t.page.data.report, null); assert(t.page.data.error); assert.equal(t.page.data.loading, false)
  }
})
test('comparison period is shown once and zero-baseline wording uses no invented percentage', async () => {
  const r = report('today', 2, 5)
  r.comparisonNote = '截至 08:30 · 对比昨日同一时刻'
  r.compare = { grossAmount: { previous: 0, unit: '元', deltaText: '新增', deltaDir: 'up' } }
  const t = fixture(async () => ok(r)); await t.page.loadData()
  assert.equal(t.page.data.report.comparisonNote, r.comparisonNote)
  assert.equal(t.page.data.report.metricCards[2].deltaText, '新增')
  const fs = require('node:fs'), path = require('node:path'), { parseWxml, renderElements } = require('./wxml-tree')
  const tree = parseWxml(fs.readFileSync(path.resolve(__dirname, '../../miniapp-delivery/pages/station-mgmt/dashboard/index.wxml'), 'utf8'))
  const text = renderElements(tree, t.page.data, { includeText: true }).map(n => n.text || '').join(' ')
  assert.equal((text.match(/昨日/g) || []).length, 1)
  assert.equal(renderElements(tree, t.page.data).filter(n => n.attrs.class === 'delta-prev').length, 6)
  assert.equal(t.page.data.report.metricCards[2].previousText, '¥0.00')
})
test('quiet icons stay inside confirmed-empty conditions and reuse registered line icons', () => {
  const fs = require('node:fs'), path = require('node:path'), root = path.resolve(__dirname, '../..')
  for (const relative of ['miniapp-delivery/pages/home/index.wxml', 'miniapp-delivery/pages/station-mgmt/orders/index.wxml']) {
    const source = fs.readFileSync(path.join(root, relative), 'utf8')
    const empties = [...source.matchAll(/<view class="(?:empty-state|empty)" wx:if="([^"]+)">([\s\S]*?)<\/view>/g)]
    assert(empties.length > 0)
    for (const [, condition, body] of empties) {
      assert(condition.includes('!loading'))
      assert(condition.includes('readReady.') || condition.includes('!listError'))
      assert(body.includes('<icon name="order"'))
      // 2026-11-08: the order-list empty state now offers a filter action instead of decorative copy.
      if (relative.includes('/station-mgmt/orders/')) {
        assert(body.includes('bindtap="onToggleFilters"'))
        assert(body.includes('wx:if="{{!filtersExpanded}}"'))
        assert(body.includes('调整筛选'))
      } else {
        assert(body.includes('care-note'))
        if (condition.includes('readReady.assigned')) {
          assert(body.includes('class="care-note" wx:if="{{readReady.unpaid && deliveredUnpaidOrders.length === 0}}"'))
        }
      }
    }
    const config = JSON.parse(fs.readFileSync(path.join(root, relative.replace('.wxml', '.json')), 'utf8'))
    assert(config.usingComponents.icon.endsWith('components/Icon/index'))
  }
})
test('real mine template shows caring copy only for confirmed manager zero orders, never failed reads or delivery role', () => {
  const fs = require('node:fs'), path = require('node:path'), { parseWxml, renderElements } = require('./wxml-tree')
  const source = fs.readFileSync(path.resolve(__dirname, '../../miniapp-delivery/pages/mine/index.wxml'), 'utf8')
  const page = loadPage('miniapp-delivery/pages/mine/index.js')
  const tree = parseWxml(source), base = { ...page.data, userInfo: {}, displayImageErrors: {}, isLogin: true, canAccessBusiness: true, isManager: true, managerStatsLoading: false, managerStatsError: '', todayProfit: { orderCount: 0, readFailed: false }, risk: {} }
  const visible = extra => renderElements(tree, { ...base, ...extra }, { includeText: true }).some(n => n.text && n.text.includes('今日暂无订单，趁空喝口水吧。'))
  assert.equal(visible({}), true)
  for (const extra of [{ managerStatsLoading: true }, { managerStatsError: '失败' }, { todayProfit: { orderCount: 0, readFailed: true } }, { todayProfit: { orderCount: 1, readFailed: false } }, { isManager: false }]) assert.equal(visible(extra), false)
})
test('scope is collapsed by default and repeated toggles preserve all server notes verbatim', async () => {
  const fs = require('node:fs'), path = require('node:path'), { parseWxml, renderElements } = require('./wxml-tree')
  const r = report(), notes = { orderAmountNote: '订单金额按创建时间统计，不扣售后退款。', scopeNote: '外派金额归接单站，不计入本站。', paidAmountNote: '已付款订单金额不等于本站实收现金，未兑水的预购票款不在内。' }
  Object.assign(r, notes)
  const t = fixture(async () => ok(r)); await t.page.loadData()
  const tree = parseWxml(fs.readFileSync(path.resolve(__dirname, '../../miniapp-delivery/pages/station-mgmt/dashboard/index.wxml'), 'utf8'))
  const visible = () => renderElements(tree, t.page.data, { includeText: true })
  const texts = () => visible().map(n => n.text || '').join(' ')
  for (let cycle = 0; cycle < 3; cycle++) {
    assert.equal(t.page.data.scopeExpanded, false)
    assert(!texts().includes('订单已付款 ≠ 实收现金'))
    for (const note of Object.values(notes)) assert(!texts().includes(note))
    assert(visible().some(n => n.tag === 'button' && n.attrs.bindtap === 'onToggleScope'))
    t.page.onToggleScope()
    for (const [key, note] of Object.entries(notes)) { assert.equal(t.page.data.report[key], note); assert(texts().includes(note)) }
    t.page.onToggleScope()
  }
  t.page.setData({ report: { ...r, orderAmountNote: '', scopeNote: '', paidAmountNote: '' } })
  assert(!visible().some(n => n.attrs.bindtap === 'onToggleScope'))
})
test('explicit unknown summary and staff preserve the report and neutral WXML facts', async () => {
  const r = report()
  Object.assign(r.summary, { deliveryBucketQty: null, deliveryBucketQtyKnown: false, deliveryBucketUnknownOrders: 1 })
  r.staffPerformance = [{ totalOrders: 3, completedOrders: 2, completeRate: 67, staffName: '张师傅', deliveryBucketQty: null, deliveryBucketQtyKnown: false, deliveryBucketUnknownOrders: 1 }]
  r.compare = { deliveryBucketQty: { current: null, previous: 4, known: false, unit: '桶', deltaDir: 'flat', good: null, deltaText: '历史数据不完整' } }
  const t = fixture(async () => ok(r)); await t.page.loadData()
  assert.equal(t.page.data.error, ''); assert(t.page.data.report)
  const card = t.page.data.report.metricCards[0]
  assert.equal(card.valueText, '待核实'); assert.equal(card.previousText, '4 桶')
  assert.equal(card.good, null); assert.equal(card.deltaDir, 'flat')
  assert.equal(t.page.data.report.metricCards[1].valueText, '3')
  t.page.setData({ analysisExpanded: true })
  const fs = require('node:fs'), path = require('node:path'), { parseWxml, renderElements } = require('./wxml-tree')
  const tree = parseWxml(fs.readFileSync(path.resolve(__dirname, '../../miniapp-delivery/pages/station-mgmt/dashboard/index.wxml'), 'utf8'))
  const text = renderElements(tree, t.page.data, { includeText: true }).map(n => n.text || '').join(' ')
  assert(text.includes('桶数待核实 · 3 单')); assert(text.includes('待核实'))
  assert(!text.includes('0 桶 · 3 单'))
})
test('previous unknown stays unknown independently while explicit current zero stays zero', async () => {
  for (const current of [0, null]) {
    const r = report()
    Object.assign(r.summary, { deliveryBucketQty: current, deliveryBucketQtyKnown: current !== null, deliveryBucketUnknownOrders: current === null ? 1 : 0 })
    r.compare = { deliveryBucketQty: { current, previous: null, known: false, unit: '桶', deltaDir: 'flat', deltaText: '历史数据不完整' } }
    const t = fixture(async () => ok(r)); await t.page.loadData()
    assert.equal(t.page.data.error, '')
    const c = t.page.data.report.metricCards[0]
    assert.equal(c.valueText, current === null ? '待核实' : '0'); assert.equal(c.previousText, '待核实')
    assert.equal(c.deltaDir, 'flat'); assert.equal(c.good, null)
  }
})
test('missing malformed and contradictory bucket facts reject incomplete reports', async () => {
  const invalid = [
    {}, { deliveryBucketQty: null }, { deliveryBucketQty: null, deliveryBucketQtyKnown: false, deliveryBucketUnknownOrders: 0 },
    { deliveryBucketQty: null, deliveryBucketQtyKnown: true, deliveryBucketUnknownOrders: 1 },
    { deliveryBucketQty: 0, deliveryBucketQtyKnown: false, deliveryBucketUnknownOrders: 1 },
    { deliveryBucketQty: 0, deliveryBucketQtyKnown: true, deliveryBucketUnknownOrders: 1 },
    ...[true, false, ' ', '', -1, 1.5, Number.MAX_SAFE_INTEGER + 1, [], {}].map(deliveryBucketQty => ({ deliveryBucketQty }))
  ]
  for (const fields of invalid) for (const target of ['summary', 'staff']) {
    const r = report()
    if (target === 'summary') { delete r.summary.deliveryBucketQty; Object.assign(r.summary, fields) }
    else r.staffPerformance = [{ totalOrders: 1, completedOrders: 0, completeRate: 0, ...fields }]
    const t = fixture(async () => ok(r)); await t.page.loadData()
    assert.equal(t.page.data.report, null, JSON.stringify({ target, fields })); assert(t.page.data.error)
  }
})
test('contradictory bucket comparison never creates a performance judgment', async () => {
  for (const change of [{ known: true }, { previous: undefined }, { deltaDir: 'down' }, { good: false }, { current: 9 }, { previous: ' ' }]) {
    const r = report()
    r.compare = { deliveryBucketQty: { current: 8, previous: null, known: false, unit: '桶', deltaDir: 'flat', good: null, deltaText: '历史数据不完整', ...change } }
    const t = fixture(async () => ok(r)); await t.page.loadData()
    assert.equal(t.page.data.report, null); assert(t.page.data.error)
  }
})
;(async () => {
  let failed = 0
  for (const t of tests) { try { await t.run(); console.log('PASS ' + t.name) } catch (e) { failed++; console.error('FAIL ' + t.name + ': ' + e.stack) } }
  done(); if (failed) process.exitCode = 1; else console.log('AQUAFLOW_SUITE_OK ' + tests.length)
})().catch(e => { done(); console.error(e); process.exitCode = 1 })
