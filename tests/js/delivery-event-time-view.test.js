const assert = require('assert')
const fs = require('fs')
const path = require('path')
const { loadPage, createWx, createApp, ROOT, armWatchdog } = require('./harness')
const done = armWatchdog(10000)
const repo = process.env.AQUAFLOW_REPO || ROOT
const tests = []
const test = (name, run) => tests.push({ name, run })
function fixture(rows) {
  const wx = createWx()
  const app = createApp({ globalData: { isLogin: true, userInfo: { staffId: 8, role: 'DELIVERY', stationId: 1 } } })
  const page = loadPage(path.join(repo, 'miniapp-delivery/pages/barrel-records/index.js'), {
    wx, app, stubs: { 'api/delivery': { getBarrelRecords: async () => ({ code: 0, data: rows }) } }
  })
  return { page, wx, app }
}
test('history displays immutable delivery event instead of later collection update', async () => {
  const t = fixture([{ id: 1, returnBucketQty: 3, barrelDiscrepancy: 0,
    deliveryTime: '2026-10-20T12:00:00', updateTime: '2026-10-24T12:00:00' }])
  await t.page.loadData()
  assert.ok(t.page.data.records[0].timeText.startsWith('2026-10-20'))
})
test('legacy missing event date is explicit, never replaced with mutable updateTime', async () => {
  const t = fixture([{ id: 1, returnBucketQty: 0, barrelDiscrepancy: 0, updateTime: '2026-10-24T12:00:00' }])
  await t.page.loadData()
  assert.equal(t.page.data.records[0].timeText, '送达日期待核实')
  assert.equal(t.page.data.totalDeliveries, 1)
  assert.equal(t.page.data.totalReturn, 0)
})
test('explicit null date keeps valid zero-return history', async () => {
  const t = fixture([{ id: 1, returnBucketQty: 0, barrelDiscrepancy: 0, deliveryTime: null }])
  await t.page.loadData()
  assert.equal(t.page.data.records.length, 1)
  assert.equal(t.page.data.records[0].timeText, '送达日期待核实')
})
test('malformed delivery event is a read error instead of invented date or empty history', async () => {
  const t = fixture([{ id: 1, returnBucketQty: 2, barrelDiscrepancy: 0, deliveryTime: 'invalid-date' }])
  await t.page.loadData()
  assert.equal(t.page.data.loaded, false)
  assert.ok(t.page.data.error.includes('不完整'))
})
test('mine names physical delivery and conditionally exposes unverified historical dates', () => {
  const wxml = fs.readFileSync(path.join(repo, 'miniapp-delivery/pages/mine/index.wxml'), 'utf8')
  assert.ok(wxml.includes('今日送达'))
  assert.ok(wxml.includes('statsReady && todayStats.deliveryTimeUnverifiedCount > 0'))
  assert.ok(wxml.includes('送达日期待核实，未计入今日'))
})
;(async () => {
  let failed = 0
  for (const t of tests) {
    try { await t.run(); console.log('PASS ' + t.name) }
    catch (err) { failed++; console.error('FAIL ' + t.name + ': ' + err.message) }
  }
  done()
  console.log('AQUAFLOW_SUITE_OK ' + (tests.length - failed) + '/' + tests.length)
  if (failed) process.exitCode = 1
})().catch(err => { done(); console.error(err); process.exitCode = 1 })
