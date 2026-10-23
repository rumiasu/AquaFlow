// Review evidence only. Exit 0 means the documented counterexamples reproduced,
// not that the product passed. Uses actual Page handlers with synthetic API stubs.
// No HTTP, database, login, payment, refund, native click or deployment is performed.
const assert = require('node:assert/strict')
const path = require('node:path')
const fs = require('node:fs')
const ROOT = path.resolve(__dirname, '../../..')
const { loadPage, createApp, createWx } = require(path.join(ROOT, 'tests/js/harness'))
const { parseWxml, renderElements } = require(path.join(ROOT, 'tests/js/wxml-tree'))
const ok = data => ({ code: 0, data })
const settle = () => new Promise(setImmediate)
const results = []
const customerDetail = 'miniapp-delivery/pages/station-mgmt/customers/detail/index.js'
const timer = setTimeout(() => { console.error('Review probes did not finish'); process.exit(1) }, 30000)

function manager() {
  return createApp({ isStationManager: () => true,
    globalData: { isLogin: true, userInfo: { staffId: 1, stationId: 1, role: 'STATION_MANAGER' } } })
}

function homeFixture(cart = { 10: 1 }) {
  const wx = createWx(), app = createApp({ getCart: () => cart })
  const addresses = [{ id: 12, detail: 'SYNTH default', isDefault: 1 }, { id: 13, detail: 'SYNTH chosen', isDefault: 0 }]
  const page = loadPage('miniapp-user/pages/home/index.js', { wx, app, stubs: {
    'api/product': { getStationProducts: async () => ok([{ id: 10, name: 'SYNTH water', category: 1, effectivePrice: 20, effectiveDeposit: 40 }]) },
    'api/order': { getOrders: async () => ok([]), getOrderDetail: async () => ok(null) },
    'api/address': { getAddresses: async () => ok(addresses) },
    'api/barrel': { getBarrelSummary: async () => ok({}), getBarrelSummaryByType: async () => ok([]) },
    'api/station': { getStationStatus: async () => ok({}) },
    'api/notification': { getUnreadNotifications: async () => ok([]) },
    'api/payment': { getQuote: async () => ok({ independentRights: true, barrelPurchases: [] }) }
  } })
  wx.setStorageSync('selectedStation', { id: 1, name: 'SYNTH station' })
  return { page, wx, addresses, cart }
}

async function run() {
  const originalRandom = Math.random
  let randomIndex = 0
  Math.random = () => [0.11, 0.22, 0.33, 0.44][randomIndex++ % 4]
  try {
    const writes = [], releases = []
    const p = loadPage(customerDetail, { app: manager(), stubs: { 'api/station-mgmt': {
      returnEmptyBuckets: async (...args) => { writes.push(args); return new Promise(resolve => releases.push(resolve)) },
      getCustomerAssets: async () => ok({ barrels: [], tickets: [], records: [] })
    } } })
    p.setData({ id: 7, returnItems: [{ productId: 5, qty: 1 }], showReturnEmpty: true })
    const first = p.onSubmitReturnEmpty(), second = p.onSubmitReturnEmpty()
    assert.equal(writes.length, 2)
    assert.notEqual(writes[0][2], writes[1][2])
    results.push({ issue: 'WB-01', case: 'double tap while first response is pending', requests: 2, differentTokens: true })
    releases.forEach(resolve => resolve(ok({ changes: [{ productId: 5 }] })))
    await Promise.all([first, second])

    const retryWrites = []
    const retry = loadPage(customerDetail, { app: manager(), stubs: { 'api/station-mgmt': {
      returnEmptyBuckets: async (...args) => { retryWrites.push(args); throw Object.assign(Error('SYNTH timeout'), { requestMayHaveOccurred: true }) }
    } } })
    retry.setData({ id: 7, returnItems: [{ productId: 5, qty: 1 }], showReturnEmpty: true })
    await retry.onSubmitReturnEmpty(); await retry.onSubmitReturnEmpty()
    assert.equal(retryWrites.length, 2)
    assert.notEqual(retryWrites[0][2], retryWrites[1][2])
    assert.equal(retry.data.showReturnEmpty, true)
    assert.equal(retry.data.returnSubmitting, false)
    results.push({ issue: 'WB-01', case: 'retry after an uncertain response', requests: 2, differentTokens: true, modalOpen: true, submitting: false })
  } finally { Math.random = originalRandom }

  const reads = { profile: 0, assets: 0, privileges: 0 }
  const serverAssets = { heldBuckets: 2, actualBuckets: 2, barrels: [], tickets: [], records: [] }
  const stale = loadPage(customerDetail, { app: manager(), stubs: {
    'api/station-mgmt': {
      getCustomerProfile: async () => { reads.profile++; return ok({}) },
      getCustomerAssets: async () => { reads.assets++; return ok(structuredClone(serverAssets)) }
    },
    'utils/request': { get: async () => { reads.privileges++; return ok({}) } }
  } })
  stale.onLoad({ id: 7 }); await settle()
  serverAssets.actualBuckets = 3
  if (typeof stale.onShow === 'function') await stale.onShow()
  assert.equal(stale.data.assets.actualBuckets, 2)
  assert.equal(reads.assets, 1)
  results.push({ issue: 'WB-02', case: 'return after a child changes assets', onShowDefined: typeof stale.onShow === 'function', reads, displayed: 2, serverFixture: 3 })

  const failed = loadPage(customerDetail, { app: manager(), stubs: {
    'api/station-mgmt': { getCustomerProfile: async () => { throw Error('SYNTH profile unavailable') }, getCustomerAssets: async () => ok(structuredClone(serverAssets)) },
    'utils/request': { get: async () => ok({}) }
  } })
  failed.onLoad({ id: 7 }); await settle()
  assert(failed.data.assets)
  const tree = parseWxml(fs.readFileSync(path.join(ROOT, customerDetail.replace(/\.js$/, '.wxml')), 'utf8'))
  const nodes = renderElements(tree, failed.data, { includeText: true })
  assert(!nodes.some(node => node.text === '本站资产'))
  results.push({ issue: 'WB-03', case: 'profile fails, assets succeed', assetsLoaded: true, assetsVisibleInWxmlProjection: false, nativeRendering: false })

  const address = homeFixture()
  address.wx.setStorageSync('selectedAddress', address.addresses[1])
  address.page.onShow()
  assert.equal(address.page.data.address.id, 13)
  await settle()
  assert.equal(address.page.data.address.id, 12)
  address.page.onSubmit()
  const query = new URL('https://synthetic.invalid' + address.wx.__calls.nav.at(-1).url).searchParams
  assert.equal(query.get('addressId'), '12')
  results.push({ issue: 'WB-04', case: 'non-default address selected before returning home', selected: 13, afterRefresh: 12, checkoutAddressId: 12 })

  const report = { range: '7d', label: '近7天', summary: { orderCount: 3, grossAmount: 120, paidAmount: 120, newCustomers: 1, cancelledOrders: 0, barrelsIn: 0 },
    trend: [{ label: '10/23', orders: 3, amount: 120 }], statusDistribution: [], payMethodDistribution: [], topProducts: [], topCustomers: [], staffPerformance: [], hourDistribution: [] }
  const dashboard = loadPage('miniapp-delivery/pages/station-mgmt/dashboard/index.js', { app: manager(), stubs: { 'api/station-mgmt': { getDashboardReport: async () => ok(structuredClone(report)) } } })
  dashboard.getCanvas = () => {} // Inspect actual chart data; do not fabricate pixels.
  await dashboard.loadData()
  dashboard.onMetricChange({ currentTarget: { dataset: { key: 'amount' } } })
  assert.equal(dashboard.data.trendMetric, 'amount')
  assert.deepEqual(dashboard.data.trendChart.values, [3])
  assert.equal(dashboard.data.trendTitle, '近7天订单量趋势')
  results.push({ issue: 'WB-05', case: 'switch trend from order count to amount', orders: 3, amount: 120, selectedMetric: 'amount', chartValues: dashboard.data.trendChart.values, title: dashboard.data.trendTitle })

  const missing = homeFixture({ 10: 1, 11: 1 })
  missing.page.onShow(); await settle()
  const items = missing.page.collectCartItems()
  assert.equal(missing.cart[11], 1)
  assert.equal(missing.page.data.total.count, 1)
  assert.deepEqual(items, [{ productId: 10, quantity: 1 }])
  results.push({ issue: 'WB-07', case: 'one restored SKU is absent from the current catalog', savedQuantity: 2, displayedQuantity: 1, missingSkuStillSaved: true, checkoutItems: items, note: 'No claim that storage was deleted or an order was submitted.' })

  let feedbackWrites = 0
  const feedback = loadPage('miniapp-delivery/pages/report/index.js', { app: manager(), stubs: { 'api/feedback': {
    getMyFeedbacks: async () => ok([]),
    submitFeedback: async () => { feedbackWrites++; throw Object.assign(Error('SYNTH timeout'), { requestMayHaveOccurred: true }) }
  } } })
  feedback.onLoad(); await feedback.onShow()
  feedback.setData({ content: 'SYNTH ordinary feedback' })
  await feedback.onSubmit(); await settle()
  await feedback.onSubmit(); await settle()
  assert.equal(feedbackWrites, 2)
  assert.equal(feedback.data.submitting, false)
  results.push({ issue: 'WB-08', case: 'known ordinary-feedback uncertain response followed by retry', requests: 2, submitting: false, scope: 'non-financial ordinary feedback; inherited D01' })

  const evidence = {
    sourceCommit: '6e2055f5235e84657f9807f0c0eb7857a2c1ea18',
    meaning: 'Exit 0 = documented counterexamples reproduced, not product acceptance.',
    boundary: 'Actual Page handlers; synthetic API stubs; one WXML condition projection. No HTTP, DB, native rendering, login, money movement or deployment.',
    result: 'reproduced', probeCount: results.length, probes: results
  }
  process.stdout.write(JSON.stringify(evidence, null, 2) + '\n')
  if (process.argv[2]) fs.writeFileSync(path.resolve(process.argv[2]), JSON.stringify(evidence, null, 2) + '\n')
}

run().catch(error => { console.error(error); process.exitCode = 1 }).finally(() => clearTimeout(timer))
