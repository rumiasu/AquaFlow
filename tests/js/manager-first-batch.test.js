// Synthetic page-handler regression: no HTTP, DB, payment or native GUI claims.
const assert = require('assert')
const fs = require('fs'), path = require('path'), Module = require('module')
const { loadPage: loadActualPage, createWx, createApp, ROOT } = require('./harness')
function loadPage(...args) {
  delete require.cache[path.join(ROOT, 'miniapp-delivery/utils/adjustment-customer.js')]
  return loadActualPage(...args)
}
const fixture = path.join(ROOT, 'tests/js/order-create-flow.test.js')
const helper = new Module(fixture, module)
helper.filename = fixture
helper.paths = Module._nodeModulePaths(path.dirname(fixture))
helper._compile(fs.readFileSync(fixture, 'utf8').split('(async () => {')[0] + '\nmodule.exports={newPage};', fixture)
const { newPage } = helper.exports
const manager = () => createApp({ canAccessStationBusiness: () => true, isStationManager: () => true })
const event = id => ({ currentTarget: { dataset: { id } } })
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r }); return { promise, resolve } }
let passed = 0, failed = 0
async function test(name, fn) {
  try { await fn(); passed++; console.log('PASS ' + name) }
  catch (e) { failed++; console.error('FAIL ' + name + ': ' + e.message) }
}
const timer = setTimeout(() => { console.error('suite did not complete'); process.exit(1) }, 30000)
;(async () => {
  await test('first notice cannot be bypassed by unified confirmation', async () => {
    const { page, calls } = newPage({ method: 2 })
    page.setData({ firstStationAsset: true, assetReadAgreed: false })
    await page.onSubmit(); await page.onUnifiedConfirmOk()
    assert.equal(calls.createOrder.length, 0)
    assert.equal(page.data.assetConfirmed, false)
  })
  await test('invalidated quote refreshes without creating and needs another explicit confirmation', async () => {
    const { page, calls } = newPage({ method: 2, quote: { methods: [{ id: 2, name: '货到付款', enabled: true }], defaultMethod: 2 } })
    await page.onSubmit()
    page.setData({ quoteReady: false, quoteError: '报价失效' })
    await page.onUnifiedConfirmOk()
    assert.equal(calls.createOrder.length, 0)
    assert.equal(calls.getQuote.length, 1)
    await page.onSubmit()
    assert.equal(calls.createOrder.length, 0)
    await page.onUnifiedConfirmOk()
    assert.equal(calls.createOrder.length, 1)
  })
  await test('changed address invalidates visible confirmation even if quoteReady remains true', async () => {
    const { page, calls } = newPage({ method: 2 })
    await page.onSubmit(); page.setData({ address: { id: 12 } })
    await page.onUnifiedConfirmOk()
    assert.equal(calls.createOrder.length, 0)
    assert.equal(calls.getQuote.length, 1)
  })
  await test('cancel and stale confirmation never acknowledge or create', async () => {
    const { page, calls } = newPage({ method: 2 })
    page.setData({ hasInTransitBarrels: true, extraDepositBuckets: 1 })
    await page.onSubmit(); page.onUnifiedConfirmCancel(); await page.onUnifiedConfirmOk()
    assert.equal(calls.createOrder.length, 0)
    assert.equal(page.data.inTransitReminderAck, false)
  })
  await test('combined cash/in-transit confirmation is acknowledged only once and creates once', async () => {
    const { page, calls } = newPage({ method: 2 })
    page.setData({ hasInTransitBarrels: true, extraDepositBuckets: 1 })
    await page.onSubmit(); await Promise.all([page.onUnifiedConfirmOk(), page.onUnifiedConfirmOk()])
    assert.equal(calls.createOrder.length, 1)
    assert.equal(page.data.inTransitReminderAck, true)
  })
  await test('server shortage confirmation retains original idempotency key', async () => {
    const { page, calls } = newPage({ method: 2, createOrder: [{ data: { needConfirm: true, shortages: [{ productId: 5, requested: 2, stock: 0 }] } }, { data: { orderId: 101 } }] })
    await page._createOrder(false); await page.onUnifiedConfirmOk()
    assert.equal(calls.createOrder.length, 2)
    assert.equal(calls.createOrder[0].idempotencyKey, calls.createOrder[1].idempotencyKey)
    assert.equal(calls.createOrder[1].confirmShortage, true)
  })
  await test('ledger new adjustment opens a customer-selectable editor without preselection', async () => {
    const wx = createWx(), page = loadPage('miniapp-delivery/pages/station-mgmt/customers/adjust/index.js', { wx, app: manager(), stubs: { 'api/station-mgmt': {} } })
    page.onCreate()
    assert.equal(wx.__calls.nav.length, 1)
    assert.ok(wx.__calls.nav[0].url.includes('/adjust/edit/index'))
  })
  await test('customer selection excludes unbound candidates and switching clears old preview', async () => {
    const page = loadPage('miniapp-delivery/pages/station-mgmt/customers/adjust/edit/index.js', { wx: createWx(), app: manager(), stubs: { 'api/station-mgmt': {
      searchAdjustmentCustomers: async () => ({ data: [{ id: 7, name: '甲', phone: '测试联系', adjustmentEligible: true }, { id: 9, name: '未绑定', adjustmentEligible: false }] }),
      getCustomerAssets: async id => ({ data: { customerId: id, customerName: '甲', phone: '测试联系', adjustmentEligible: true, ticketQuantity: 3, depositBalance: 90 } }),
      getCatalog: async () => ({ data: [] })
    } } })
    await page.searchCustomers()
    assert.deepEqual(page.data.customerResults.map(c => c.id), [7])
    page.setData({ preview: { stale: true }, previewRows: [{ stale: true }], previewExtra: { stale: true }, customerId: 8, clientToken: 'old' })
    await page.onSelectCustomer(event(7))
    assert.equal(page.data.customerId, 7)
    assert.equal(page.data.preview, null)
    assert.deepEqual(page.data.previewRows, [])
    assert.notEqual(page.data.clientToken, 'old')
    assert.equal(page.data.customerAssets.ticketQuantity, 3)
  })
  await test('failed search is an error, not an empty successful result', async () => {
    const page = loadPage('miniapp-delivery/pages/station-mgmt/customers/adjust/edit/index.js', { wx: createWx(), app: manager(), stubs: { 'api/station-mgmt': { searchAdjustmentCustomers: async () => { throw new Error('搜索失败') } } } })
    await page.searchCustomers()
    assert.ok(page.data.customerSearchError)
    assert.equal(page.data.customerSearchLoading, false)
  })
  await test('menu counts use pending summary categories and backend business total', async () => {
    const page = loadPage('miniapp-delivery/pages/station-mgmt/index.js', { wx: createWx(), app: manager(), stubs: { 'utils/request': { get: async () => ({ data: { businessWaitingTotal: 7, items: [{ key: 'overdueReceivable', available: true, count: 2 }, { key: 'barrelReturn', available: true, count: 0 }] } }) } } })
    await page.loadBadgeCounts()
    assert.equal(page.data.receivablesBadgeCount, 2)
    assert.equal(page.data.businessWaitingBadgeCount, 7)
    assert.equal(page.data.barrelReturnBadgeCount, 0)
  })
  await test('failed or incomplete counts remain unknown rather than zero', async () => {
    const page = loadPage('miniapp-delivery/pages/station-mgmt/index.js', { wx: createWx(), app: manager(), stubs: { 'utils/request': { get: async () => { throw new Error('断网') } } } })
    page.setData({ receivablesBadgeCount: 3, businessWaitingBadgeCount: 5, barrelReturnBadgeCount: 2 })
    await page.loadBadgeCounts()
    assert.equal(page.data.receivablesBadgeCount, null)
    assert.equal(page.data.businessWaitingBadgeCount, null)
    assert.equal(page.data.barrelReturnBadgeCount, null)
    assert.ok(page.data.badgeError)
  })
  await test('server pending filter reaches an old pending exception beyond first fifty', async () => {
    const queries = [], all = Array.from({ length: 51 }, (_, i) => ({ id: i + 1, pending: i === 50 }))
    const page = loadPage('miniapp-delivery/pages/station-mgmt/exceptions/index.js', { wx: createWx(), app: manager(), stubs: { 'utils/request': { get: async (url, query) => {
      if (url.endsWith('/stats')) return { data: {} }
      queries.push(query); const rows = query.status === 'STAFF_RECORDED' ? all.filter(x => x.pending) : all
      return { data: { records: rows.slice((query.page - 1) * query.size, query.page * query.size), total: rows.length } }
    } }, 'api/station-mgmt': { getAlerts: async () => ({ data: [] }) } } })
    await page.load(); assert.equal(page.data.items.length, 50)
    await page.onLoadMore(); assert.equal(page.data.items.length, 51)
    await page.onTogglePending()
    assert.equal(queries.at(-1).page, 1)
    assert.equal(queries.at(-1).status, 'STAFF_RECORDED')
    assert.deepEqual(page.data.items.map(x => x.id), [51])
    assert.equal(page.data.total, 1)
    assert.equal(page.data.hasMore, false)
  })
  await test('duplicate load-more taps request one page and end stops requests', async () => {
    const wait = deferred(); let requests = 0
    const page = loadPage('miniapp-delivery/pages/station-mgmt/exceptions/index.js', { wx: createWx(), app: manager(), stubs: { 'utils/request': { get: async () => { requests++; return wait.promise } }, 'api/station-mgmt': {} } })
    page.setData({ loading: false, page: 1, items: [{ id: 1 }], hasMore: true })
    const first = page.onLoadMore(); const second = page.onLoadMore()
    wait.resolve({ data: { records: [{ id: 2 }], total: 2 } })
    await Promise.all([first, second]); await page.onLoadMore()
    assert.equal(requests, 1)
    assert.equal(page.data.items.length, 2)
  })
  await test('partial menu summaries expose missing categories as unknown', async () => {
    const page = loadPage('miniapp-delivery/pages/station-mgmt/index.js', { wx: createWx(), app: manager(), stubs: { 'utils/request': { get: async () => ({ data: { items: [{ key: 'barrelReturn', available: true, count: 0 }] } }) } } })
    await page.loadBadgeCounts()
    assert.equal(page.data.barrelReturnBadgeCount, 0)
    assert.equal(page.data.receivablesBadgeCount, null)
    assert.equal(page.data.businessWaitingBadgeCount, null)
    assert.ok(page.data.badgeError)
  })
  await test('late all-status response cannot replace a pending-only query', async () => {
    const old = deferred()
    const page = loadPage('miniapp-delivery/pages/station-mgmt/exceptions/index.js', { wx: createWx(), app: manager(), stubs: { 'utils/request': { get: async (url, q) => q.status ? { data: { records: [{ id: 51, pending: true }], total: 1 } } : old.promise }, 'api/station-mgmt': {} } })
    const first = page.loadList(true)
    await page.onTogglePending()
    old.resolve({ data: { records: [{ id: 1, pending: false }], total: 51 } })
    await first
    assert.deepEqual(page.data.items.map(it => it.id), [51])
    assert.equal(page.data.total, 1)
    assert.equal(page.data.loading, false)
  })
  await test('failed exception reset retains an explicit error and unknown total', async () => {
    const page = loadPage('miniapp-delivery/pages/station-mgmt/exceptions/index.js', { wx: createWx(), app: manager(), stubs: { 'utils/request': { get: async () => { throw new Error('列表断网') } }, 'api/station-mgmt': {} } })
    await page.loadList(true)
    assert.ok(page.data.listError)
    assert.equal(page.data.total, null)
    assert.equal(page.data.loading, false)
  })
  await test('preselected customer must pass server binding eligibility again', async () => {
    const page = loadPage('miniapp-delivery/pages/station-mgmt/customers/adjust/edit/index.js', { wx: createWx(), app: manager(), stubs: { 'api/station-mgmt': { getCustomerAssets: async id => ({ data: { customerId: id, adjustmentEligible: false } }) } } })
    await page.loadSelectedCustomer(7)
    assert.equal(page.data.customerAssets, null)
    assert.ok(page.data.customerAssetsError.includes('绑定'))
    assert.ok(page.buildPayload().error)
  })
  await test('late preview from old customer stays cleared after selecting another customer', async () => {
    const old = deferred()
    const page = loadPage('miniapp-delivery/pages/station-mgmt/customers/adjust/edit/index.js', { wx: createWx(), app: manager(), stubs: { 'api/station-mgmt': {
      previewAdjustment: () => old.promise,
      getCustomerAssets: async id => ({ data: { customerId: id, adjustmentEligible: true } })
    } } })
    page.setData({ customerId: 7, customerAssets: { adjustmentEligible: true }, option: page.data.typeOptions.find(it => it.value === 'TICKET_GRANT'), productIndex: 0, productList: [{ id: 5 }], form: { qty: '1', reason: 'synthetic' }, customerResults: [{ id: 8, adjustmentEligible: true }] })
    const preview = page.onPreview()
    await page.onSelectCustomer(event(8))
    old.resolve({ data: { before: { ticket: 0 }, after: { ticket: 1 } } })
    await preview
    assert.equal(page.data.customerId, 8)
    assert.equal(page.data.preview, null)
    assert.deepEqual(page.data.previewRows, [])
  })
  await test('customer-detail fixed adjustment entry is outside barrel and empty-state branches', async () => {
    const s = fs.readFileSync(path.join(ROOT, 'miniapp-delivery/pages/station-mgmt/customers/detail/index.wxml'), 'utf8')
    const head = s.slice(s.indexOf('本站资产'), s.indexOf('asset-loading'))
    assert.ok(head.includes('bindtap="onOpenAdjust"'))
    assert.ok(!head.includes('assets.barrels.length'))
  })
  console.log(`manager first batch: ${passed} passed, ${failed} failed`)
  if (failed) process.exitCode = 1
  else console.log('AQUAFLOW_SUITE_OK ' + passed)
})().catch(e => { console.error(e); process.exitCode = 1 }).finally(() => clearTimeout(timer))
