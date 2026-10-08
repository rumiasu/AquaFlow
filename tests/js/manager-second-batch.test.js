const assert = require('assert'), path = require('path'), fs = require('fs')
const { loadPage: actualLoad, createWx, createApp, ROOT } = require('./harness')
function loadPage(...args) {
  delete require.cache[path.join(ROOT, 'miniapp-delivery/utils/station-history-customer.js')]
  return actualLoad(...args)
}
const app = () => createApp({ globalData: { userInfo: { stationId: 1 } } })
const ev = (field, value) => ({ currentTarget: { dataset: { field, id: value, key: value } }, detail: { value } })
const defer = () => { let resolve; const promise = new Promise(r => { resolve = r }); return { promise, resolve } }
let passed = 0, failed = 0
const timer = setTimeout(() => process.exit(1), 30000)
async function test(name, fn) { try { await fn(); passed++; console.log('PASS ' + name) } catch (e) { failed++; console.error('FAIL ' + name + ': ' + e.message) } }
;(async () => {
  await test('product settings retain the exact product in tier navigation', () => {
    const wx = createWx(), page = loadPage('miniapp-delivery/pages/station-mgmt/products/index.js', { wx, app: app() })
    page.setData({ setting: { id: 42 } }); page.onOpenTicketPackages()
    assert.ok(wx.__calls.nav[0].url.endsWith('?productId=42'))
  })
  function tiers(get) {
    return loadPage('miniapp-delivery/pages/station-mgmt/ticket-packages/index.js', { wx: createWx(), app: app(), stubs: { 'utils/request': { get } } })
  }
  const products = [{ id: 41, name: '同名水', spec: '18.9L', imageUrl: 'synthetic-image' }, { id: 42, name: '同名水', spec: '500ml', brand: '测试' }]
  await test('tier entry selects the product from URL and preserves server unit price', async () => {
    const requests = [], page = tiers(async url => { requests.push(url); return { data: url.includes('sale-by-station') ? products : [{ id: 7, qty: 10, price: 83, unitPrice: 8.3, status: 1 }] } })
    page.onLoad({ productId: '42' }); page.setData({ stationId: 1 }); await page.loadProducts()
    assert.equal(page.data.productId, '42'); assert.equal(page.data.productSpec, '500ml')
    assert.ok(requests.some(u => u.endsWith('productId=42')))
    assert.equal(page.data.packages[0].unitPrice, 8.3)
  })
  await test('search separates same-name products by spec and keeps station discount separate', async () => {
    const page = tiers(async () => ({ data: products }))
    await page.loadProducts(); page.onProductSearch(ev('', '500ml'))
    assert.deepEqual(page.data.visibleProducts.map(p => p.id), ['UNIFIED', 42])
  })
  await test('failed image becomes a stable fallback rather than retrying a broken URL', async () => {
    const page = tiers(async () => ({ data: products })); await page.loadProducts()
    page.onProductImageError(ev('', 41))
    assert.equal(page.data.products.find(p => p.id === 41).imageFailed, true)
    assert.equal(page.data.visibleProducts.find(p => p.id === 41).imageFailed, true)
  })
  await test('unavailable inherited product fails clearly rather than editing a different product', async () => {
    const page = tiers(async () => ({ data: products })); page.onLoad({ productId: '999' }); await page.loadProducts()
    assert.equal(page.data.productId, ''); assert.ok(page.data.productLoadError)
  })
  await test('late tier response cannot overwrite newly selected product', async () => {
    const old = defer(), page = tiers(async url => url.includes('productId=41') ? old.promise : { data: [{ id: 42, qty: 20 }] })
    page.setData({ products }); const first = page.onPickProduct(ev('', 41))
    await page.onPickProduct(ev('', 42)); old.resolve({ data: [{ id: 41, qty: 10 }] }); await first
    assert.equal(page.data.productId, '42'); assert.equal(page.data.packages[0].id, 42)
  })
  function orders(getOrders, get) {
    const request = { get: get || (async () => ({ data: [] })) }
    return loadPage('miniapp-delivery/pages/station-mgmt/orders/index.js', { wx: createWx(), app: app(), stubs: { 'api/station-mgmt': { getOrders, getCrossStationOrders: async () => ({ data: { count: 0 } }) }, 'utils/request': request, './request': request } })
  }
  await test('orders send customer/date/page together and include end day without client timezone conversion', async () => {
    const q = [], page = orders(async query => { q.push(query); return { data: q.length === 1 ? Array.from({ length: 20 }, (_, i) => ({ id: i + 1 })) : [{ id: 21 }] } })
    page.setData({ customerId: 7, startDate: '2026-10-01', endDate: '2026-10-06' })
    await page.loadData(); await page.onLoadMore()
    assert.equal(q[0].customerId, 7); assert.equal(q[0].createTimeEnd, '2026-10-06'); assert.equal(q[0].pageSize, 20)
    assert.equal(q[1].page, 2); assert.equal(page.data.list.length, 21); assert.equal(page.data.hasMore, false)
  })
  await test('changing order date resets the page and invalid ranges send no query', async () => {
    const q = [], page = orders(async query => { q.push(query); return { data: [] } })
    page.setData({ page: 4 }); await page.onHistoryDateChange(ev('startDate', '2026-10-01'))
    assert.equal(q[0].page, 1)
    await page.onHistoryDateChange(ev('endDate', '2026-09-30'))
    assert.equal(q.length, 1); assert.ok(page.data.listError)
  })
  await test('late old order query cannot replace new customer results', async () => {
    const old = defer(), page = orders(async q => q.customerId === 7 ? old.promise : { data: [{ id: 8 }] })
    page.setData({ customerId: 7 }); const first = page.loadData(); page.setData({ customerId: 8 }); await page.loadData()
    old.resolve({ data: [{ id: 7 }] }); await first; assert.deepEqual(page.data.list.map(o => o.id), [8])
  })
  await test('history customer search includes order-only customers instead of adjustment-only binding filter', async () => {
    const page = orders(async () => ({ data: [] }), async () => ({ data: [{ id: 7, name: '老客户', adjustmentEligible: false }] }))
    await page.searchHistoryCustomers(); assert.equal(page.data.customerResults.length, 1)
    await page.onSelectHistoryCustomer(ev('', 7)); assert.equal(page.data.customerId, 7)
  })
  function payments(get, confirm) {
    return loadPage('miniapp-delivery/pages/station-mgmt/payments/index.js', { wx: createWx(), app: app(), stubs: { 'utils/request': { get }, 'api/station-mgmt': { getPendingPayments: async () => ({ data: [] }), confirmPayment: confirm || (async () => ({})) } } })
  }
  await test('ticket history separates purchase payments from deposits, water orders and balance', async () => {
    const page = payments(async () => ({ data: [{ id: 1, ticketQty: 10, amount: 80, statusText: '已付款' }, { id: 2, amount: 30 }, { id: 3, orderId: 99, ticketQty: 10, amount: 80 }] }))
    page.onLoad({ view: 'ticketHistory' }); await page.loadData()
    assert.deepEqual(page.data.list.map(r => r.id), [1]); assert.equal(page.data.list[0].statusText, '已付款')
    assert.ok(page.data.historyScope.includes('最近200'))
  })
  await test('customer history uses existing scoped customer endpoint and date filters actual returned history', async () => {
    const calls = [], page = payments(async (url, q) => { calls.push({ url, q }); return { data: [{ id: 1, ticketQty: 10, createTime: '2026-09-01 10:00:00' }, { id: 2, ticketQty: 20, createTime: '2026-10-06 23:59:59' }] } })
    page.onLoad({ view: 'ticketHistory', customerId: '7' }); await page.loadData()
    assert.equal(calls[0].url, '/api/payments/customer/7'); assert.equal(calls[0].q.stationId, 1)
    page.onPaymentHistoryDate(ev('startDate', '2026-10-06')); page.onPaymentHistoryDate(ev('endDate', '2026-10-06'))
    assert.deepEqual(page.data.list.map(r => r.id), [2]); assert.ok(page.data.historyScope.includes('该客户本站全部'))
  })
  await test('history can never confirm collection through a stale action', async () => {
    let writes = 0; const page = payments(async () => ({ data: [{ id: 1, ticketQty: 10 }] }), async () => { writes++ })
    page.onLoad({ view: 'ticketHistory' }); await page.loadData(); page.onConfirm(ev('', 1))
    assert.equal(writes, 0)
  })
  await test('payroll filtering is bounded and uses overlapping inclusive settlement periods', () => {
    const page = loadPage('miniapp-delivery/pages/station-mgmt/payroll/index.js', { wx: createWx(), app: app(), stubs: { 'utils/request': {} } })
    page.setData({ payrolls: [{ id: 1, staffId: 7, staffName: '甲', periodStart: '2026-10-01', periodEnd: '2026-10-06' }, { id: 2, staffId: 8, staffName: '乙', periodStart: '2026-09-01', periodEnd: '2026-09-30' }], payrollKeyword: '甲', payrollStart: '2026-10-06', payrollEnd: '2026-10-06' })
    page.applyPayrollFilters(); assert.deepEqual(page.data.filteredPayrolls.map(p => p.id), [1]); assert.ok(page.data.payrollScope.includes('已加载的2张'))
  })
  await test('ticket history does not expose confirm buttons and spec/image fallback are actual WXML bindings', () => {
    const s = fs.readFileSync(path.join(ROOT, 'miniapp-delivery/pages/station-mgmt/ticket-packages/index.wxml'), 'utf8')
    assert.ok(s.includes('item.spec')); assert.ok(s.includes('binderror="onProductImageError"')); assert.ok(s.includes('thumb-fallback'))
    const pay = fs.readFileSync(path.join(ROOT, 'miniapp-delivery/pages/station-mgmt/payments/index.wxml'), 'utf8')
    assert.ok(pay.includes('class="card-actions" wx:if="{{view === \'pending\'}}"'))
  })
  console.log(`second batch: ${passed} passed, ${failed} failed`)
  if (failed) process.exitCode = 1; else console.log('AQUAFLOW_SUITE_OK ' + passed)
})().catch(e => { console.error(e); process.exitCode = 1 }).finally(() => clearTimeout(timer))
