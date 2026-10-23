// Actual report Page / drawing data only; Canvas lookup is stubbed, no pixels or HTTP.
const assert = require('node:assert/strict')
const { loadPage, createApp, armWatchdog } = require('./harness')
const done = armWatchdog(30000), tests = [], test = (name, run) => tests.push({ name, run })
const event = key => ({ currentTarget: { dataset: { key } } })
const ok = data => ({ code: 0, data })
function report(range = '7d', orders = 3, amount = 120) {
  return { range, label: range === '7d' ? '近7天' : '近30天', summary: { orderCount: orders, grossAmount: amount, paidAmount: amount, newCustomers: 0, cancelledOrders: 0, barrelsIn: 0 },
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
  assert(t.page.data.trendTitle.includes('营业额')); assert.equal(t.draws.at(-1).metric, 'amount')
  t.page.onMetricChange(event('orders'))
  assert.deepEqual(t.page.data.trendChart.values, [3, 0]); assert(t.page.data.trendTitle.includes('订单量'))
})
test('a new range preserves selected metric while replacing values from the new report', async () => {
  const t = fixture(async range => ok(report(range, range === '30d' ? 9 : 3, range === '30d' ? 999 : 120)))
  await t.page.loadData(); t.page.onMetricChange(event('amount'))
  await t.page.onRangeChange(event('30d')); await new Promise(setImmediate)
  assert.equal(t.page.data.range, '30d'); assert.deepEqual(t.page.data.trendChart.values, [999, 0])
  assert.equal(t.page.data.trendMetric, 'amount'); assert(t.page.data.trendTitle.includes('近30天营业额'))
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
;(async () => {
  let failed = 0
  for (const t of tests) { try { await t.run(); console.log('PASS ' + t.name) } catch (e) { failed++; console.error('FAIL ' + t.name + ': ' + e.stack) } }
  done(); if (failed) process.exitCode = 1; else console.log('AQUAFLOW_SUITE_OK ' + tests.length)
})().catch(e => { done(); console.error(e); process.exitCode = 1 })
