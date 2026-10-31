'use strict'
// Real handlers, synthetic APIs and native callbacks only; no HTTP, payments or database.
const assert = require('node:assert/strict')
const { loadPage, createWx, createApp, armWatchdog } = require('./harness')
const tests = [], test = (name, run) => tests.push({ name, run })
const copy = value => JSON.parse(JSON.stringify(value))
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
const flush = async () => { for (let i = 0; i < 40; i++) await Promise.resolve() }
const event = (id, value) => ({ currentTarget: { dataset: { id, field: id } }, detail: { value } })
function nativeSetData(page) {
  page.setData = function (patch, callback) {
    for (const [key, value] of Object.entries(patch)) {
      const parts = key.replace(/\[(\d+)\]/g, '.$1').split('.'); let target = this.data
      for (const part of parts.slice(0, -1)) target = target[part]
      target[parts.at(-1)] = value
    }
    if (callback) callback()
  }
}
function productFixture() {
  const wx = createWx(), app = createApp({ globalData: { isLogin: true,
    userInfo: { staffId: 1, stationId: 11, role: 'STATION_MANAGER' } } })
  const product = { id: 17, selected: true, ownerStationId: 11, name: 'Synthetic water', displayName: 'Synthetic water',
    category: 1, price: 12, deposit: 50, quantity: 10, enabled: 1, ticketEnabled: 1, ticketPrice: 0 }
  const state = { rows: [{ id: 31, productId: 17, qty: 10, price: '88.00', unitPrice: '8.80', title: '合成家庭套餐', sort: 7, status: 1 }] }
  const writes = [], calls = { reads: 0, edits: 0 }
  const get = async url => {
    if (url.includes('sale-by-station')) return { code: 0, data: [product] }
    if (url.includes('ticket-packages/manage')) { calls.reads++; return state.read ? state.read() : { code: 0, data: copy(state.rows) } }
    return { code: 0, data: [] }
  }
  const post = async (url, body) => {
    writes.push(copy(body)); if (state.write) return state.write(body)
    const previous = state.rows.find(row => body.id ? row.id === body.id : row.qty === body.qty)
    const saved = { ...previous, ...body, id: previous ? previous.id : 32,
      title: body.title == null ? null : body.title, sort: body.sort == null ? 0 : body.sort,
      unitPrice: (Number(body.price) / body.qty).toFixed(2) }
    state.rows = state.rows.filter(row => row.id !== saved.id).concat(saved)
    return { code: 0, data: copy(saved) }
  }
  const page = loadPage('miniapp-delivery/pages/station-mgmt/products/index.js', { wx, app, stubs: {
    'config/api': { API: {}, getTransportConfig: () => ({ mode: 'https', baseUrl: 'https://synthetic.invalid' }) },
    'api/station-mgmt': { updateMyProduct: async () => { calls.edits++; return { code: 0 } },
      updateCatalogSetting: async () => { calls.edits++; return { code: 0 } },
      getCatalog: async () => ({ code: 0, data: [product] }), getMySubmissions: async () => ({ code: 0, data: [] }) },
    'utils/request': { get, post, del: async url => { writes.push({ delete: url }); state.rows = []; return { code: 0 } } },
    'utils/upload-recovery-page': { wrapUploadRecovery: x => x },
    'utils/upload': { uploadDraft: () => 'synthetic', isUploadSessionCurrent: () => true }
  } })
  nativeSetData(page); page.refreshModuleSetup = async () => {}; page.loadWaterTemplateImages = async () => {}
  async function open(platform = false) {
    page.onOpenSetting({ currentTarget: { dataset: { item: { ...product, ownerStationId: platform ? null : 11 } } } }); await flush()
  }
  function visit() { page.onOpenTicketPackages(); page.onHide() }
  async function returnToParent() { page.onShow(); await flush() }
  function child() { return loadPage('miniapp-delivery/pages/station-mgmt/ticket-packages/index.js', { wx, app,
    stubs: { 'utils/request': { get, post, del: async () => ({ code: 0 }) } } }) }
  return { page, wx, app, product, state, writes, calls, open, visit, returnToParent, child }
}
test('unchanged tiers are never rewritten by either product save path', async () => {
  for (const platform of [false, true]) {
    const t = productFixture(); await t.open(platform)
    t.page.setData({ 'editForm.description': '合成商品草稿' }); await t.page.onSaveEdit()
    assert.equal(t.calls.edits, 1); assert.equal(t.writes.length, 0)
    assert.equal(t.state.rows[0].title, '合成家庭套餐'); assert.equal(t.state.rows[0].sort, 7)
  }
})
test('an explicit tier price or shelf edit sends the original title and sort', async () => {
  for (const field of ['price', 'onShelf']) {
    const t = productFixture(); await t.open()
    t.page.setData({ ['pkgRows[0].' + field]: field === 'price' ? '91.25' : false }); await t.page.onSaveEdit()
    assert.equal(t.writes.length, 1); assert.equal(t.writes[0].title, '合成家庭套餐'); assert.equal(t.writes[0].sort, 7)
    assert.equal(t.writes[0].price, field === 'price' ? 91.25 : 88); assert.equal(t.writes[0].status, field === 'onShelf' ? 0 : 1)
  }
})
test('actual child save survives returning and saving unrelated parent product text', async () => {
  const t = productFixture(); await t.open(); t.page.setData({ 'editForm.description': '未保存商品说明' }); t.visit()
  const child = t.child(); child.setData({ stationId: 11, products: [t.product] }); await child.onPickProduct(event(17))
  child.onQtyChange(event('', '10')); child.onInput(event('price', '95.00')); child.onInput(event('title', '合成新套餐')); child.onInput(event('sort', '9'))
  await child.onSave(); await t.returnToParent()
  assert.equal(t.page.data.pkgRows[0].price, '95'); assert.equal(t.page.data.editForm.description, '未保存商品说明')
  await t.page.onSaveEdit(); assert.equal(t.writes.length, 1)
  assert.equal(t.state.rows[0].price, 95); assert.equal(t.state.rows[0].title, '合成新套餐'); assert.equal(t.state.rows[0].sort, 9)
})
for (const keep of [false, true]) test('conflicting return explicitly ' + (keep ? 'keeps local tier edits with fresh metadata' : 'uses fresh tiers without losing product draft'), async () => {
  const t = productFixture(); await t.open(); t.page.setData({ 'pkgRows[0].price': '90.00', 'editForm.description': '保留商品草稿' })
  let modal; t.wx.showModal = options => { modal = options }; t.visit()
  t.state.rows[0] = { ...t.state.rows[0], price: '95.00', title: '子页新名', sort: 9 }
  await t.returnToParent(); assert.equal(t.page.data.pkgLoading, true); assert.equal(t.writes.length, 0)
  assert.equal(modal.title, '核对水票档位'); modal.success({ confirm: !keep }); modal.success({ confirm: keep }); await flush()
  assert.equal(t.page.data.pkgRows[0].price, keep ? '90.00' : '95.00'); assert.equal(t.page.data.editForm.description, '保留商品草稿')
  await t.page.onSaveEdit(); assert.equal(t.writes.length, keep ? 1 : 0)
  assert.equal(t.state.rows[0].title, '子页新名'); assert.equal(t.state.rows[0].sort, 9)
})
test('failed return read retains both drafts and retry reconciles before allowing save', async () => {
  const t = productFixture(); await t.open(); t.page.setData({ 'editForm.description': '商品草稿', 'pkgRows[0].price': '90.00' }); t.visit()
  t.state.read = async () => { throw Error('合成读取失败') }; await t.returnToParent()
  assert.ok(t.page.data.pkgError); assert.equal(t.page.data.pkgRows[0].price, '90.00'); await t.page.onSaveEdit(); assert.equal(t.calls.edits, 0)
  t.state.read = null; await t.page.onRetryPkgRows(); assert.equal(t.page.data.pkgError, ''); assert.equal(t.page.data.pkgRows[0].price, '90.00')
  assert.equal(t.page.data.editForm.description, '商品草稿')
})
test('failed tier write preserves the exact metadata and edited draft for retry', async () => {
  const t = productFixture(); await t.open(); t.page.setData({ 'pkgRows[0].price': '90.00' })
  t.state.write = async () => { throw Object.assign(Error('合成保存拒绝'), { businessRejected: true }) }; await t.page.onSaveEdit()
  assert.equal(t.page.data.showEdit, true); assert.equal(t.page.data.pkgRows[0].price, '90.00'); assert.equal(t.page.data.pkgRows[0].title, '合成家庭套餐')
  t.state.write = null; await t.page.onSaveEdit(); assert.equal(t.state.rows[0].price, 90); assert.equal(t.state.rows[0].sort, 7)
})
test('return conflict callback after hide cannot replace the preserved draft', async () => {
  const t = productFixture(); await t.open(); t.page.setData({ 'pkgRows[0].price': '90.00' }); let modal; t.wx.showModal = o => { modal = o }
  t.visit(); t.state.rows[0].price = '95.00'; await t.returnToParent(); t.page.onHide(); modal.success({ confirm: true }); await flush()
  assert.equal(t.page.data.pkgRows[0].price, '90.00'); assert.equal(t.writes.length, 0)
})
test('failed conflict prompt preserves drafts and exposes a retry instead of unlocking save', async () => {
  const t = productFixture(); await t.open(); t.page.setData({ 'pkgRows[0].price': '90.00' }); t.wx.showModal = o => o.fail()
  t.visit(); t.state.rows[0].price = '95.00'; await t.returnToParent()
  assert.equal(t.page.data.pkgLoading, false); assert.ok(t.page.data.pkgError); assert.equal(t.page.data.pkgRows[0].price, '90.00')
  await t.page.onSaveEdit(); assert.equal(t.writes.length, 0)
})
test('a later package refresh wins and a different actor cannot apply the old read', async () => {
  const t = productFixture(); await t.open(); const old = deferred(); t.state.read = () => old.promise
  const reading = t.page.loadPkgRows(17, { reconcile: true }); t.state.read = null; t.state.rows[0].price = '96.00'
  await t.page.loadPkgRows(17, { reconcile: true }); old.resolve({ code: 0, data: [] }); await reading
  assert.equal(t.page.data.pkgRows[0].price, '96.00')
  const actorRead = deferred(); t.state.read = () => actorRead.promise; const pending = t.page.loadPkgRows(17)
  t.app.globalData.userInfo.staffId = 2; actorRead.resolve({ code: 0, data: [] }); await pending
  assert.equal(t.page.data.pkgRows[0].price, '96.00')
})
test('returning deleted or new tiers does not resurrect unchanged rows or discard a new local draft', async () => {
  const t = productFixture(); await t.open(); t.page.onAddPkgRow(); t.page.setData({ 'pkgRows[1].price': '150.00' }); t.visit()
  t.state.rows = []; t.wx.__modalAutoConfirm = false; await t.returnToParent()
  assert.equal(t.page.data.pkgRows.length, 1); assert.equal(t.page.data.pkgRows[0].price, '150.00'); assert.equal(t.page.data.pkgRows[0].id, null)
})
function deliveryFixture() {
  const wx = createWx(), wait = deferred(), writes = [], timers = []
  const app = createApp({ globalData: { isLogin: true, userInfo: { staffId: 1, stationId: 11, role: 'DELIVERY' } } })
  const order = { id: 55, status: 2, needCollect: false, payState: 'PAID', payStateText: '已付款', totalAmount: 40, items: [] }
  const page = loadPage('miniapp-delivery/pages/order/complete.js', { wx, app, stubs: {
    'api/delivery': { getOrderDetail: async () => ({ code: 0, data: order }), completeOrder: (id, body) => { writes.push({ id, body }); return wait.promise } },
    'utils/upload-recovery-page': { wrapUploadRecovery: x => x }
  } })
  page.setData({ orderId: 55 }); const realTimeout = global.setTimeout
  return { page, wx, app, wait, writes, timers, async start() {
    await page.loadOrder(55)
    global.setTimeout = (callback, delay, ...args) => delay === 1500 ? (timers.push(callback), 0) : realTimeout(callback, delay, ...args)
    await page.onConfirmComplete()
  }, restore() { global.setTimeout = realTimeout } }
}
test('normal delivery success remains single flight and returns from its own page', async () => {
  const t = deliveryFixture(); try {
    await t.start(); await t.page.onConfirmComplete(); assert.equal(t.writes.length, 1)
    t.wait.resolve({ code: 0 }); await flush(); assert.equal(t.page.data.resultState, 'success'); assert.equal(t.page.data.submittingComplete, false)
    t.timers[0](); assert.deepEqual(t.wx.__calls.nav.at(-1), { type: 'navigateBack', delta: 2 })
  } finally { t.restore() }
})
for (const leave of ['onHide', 'onUnload']) test('late delivery success after ' + leave + ' cannot toast or navigate elsewhere', async () => {
  const t = deliveryFixture(); try {
    await t.start(); t.page[leave](); const notices = t.wx.__calls.toast.length, loading = t.wx.__calls.loading.length
    t.wait.resolve({ code: 0 }); await flush()
    assert.equal(t.wx.__calls.toast.length, notices); assert.equal(t.timers.length, 0); assert.equal(t.wx.__calls.nav.length, 0)
    assert.equal(t.wx.__calls.loading.length, loading)
    if (leave === 'onHide') { t.page.onShow(); assert.equal(t.page.data.resultState, 'success'); t.page.onVerifyOriginalOrder(); assert.ok(t.wx.__calls.nav.at(-1).url.endsWith('id=55')) }
  } finally { t.restore() }
})
test('a successful delivery timer is invalid after leaving or changing order/identity', async () => {
  for (const change of [t => t.page.onHide(), t => t.page.setData({ orderId: 56 }), t => { t.app.globalData.userInfo.staffId = 2 }]) {
    const t = deliveryFixture(); try { await t.start(); t.wait.resolve({ code: 0 }); await flush(); change(t); t.timers[0](); assert.equal(t.wx.__calls.nav.length, 0) } finally { t.restore() }
  }
})
for (const uncertain of [false, true]) test('late delivery ' + (uncertain ? 'unknown result' : 'business rejection') + ' is reconciled only on returning to the original page', async () => {
  const t = deliveryFixture(); try {
    await t.start(); t.page.onHide(); const modals = t.wx.__calls.modal.length
    t.wait.reject(Object.assign(Error('合成回执'), uncertain ? { requestMayHaveOccurred: true } : { businessRejected: true, requestMayHaveOccurred: false })); await flush()
    assert.equal(t.wx.__calls.modal.length, modals); t.page.onShow(); assert.equal(t.page.data.resultState, uncertain ? 'unknown' : 'failed')
    assert.ok(t.page.data.unknownHint); assert.equal(t.page.data.submittingComplete, false); assert.equal(t.writes.length, 1)
  } finally { t.restore() }
})
test('returning before the delivery receipt keeps the original request locked then reconciles without navigation', async () => {
  const t = deliveryFixture(); try {
    await t.start(); t.page.onHide(); t.page.onShow(); await t.page.onConfirmComplete(); assert.equal(t.writes.length, 1)
    t.wait.resolve({ code: 0 }); await flush(); assert.equal(t.page.data.resultState, 'success'); assert.equal(t.timers.length, 0)
  } finally { t.restore() }
})
test('an old delivery receipt cannot update a new identity or order', async () => {
  for (const change of [t => { t.app.globalData.userInfo.staffId = 2 }, t => t.page.setData({ orderId: 56 })]) {
    const t = deliveryFixture(); try {
      await t.start(); t.page.onHide(); change(t); t.page.onShow(); const snapshot = JSON.stringify(t.page.data)
      t.wait.resolve({ code: 0 }); await flush(); assert.equal(JSON.stringify(t.page.data), snapshot); assert.equal(t.timers.length, 0)
    } finally { t.restore() }
  }
})
function ticketFixture() {
  const wx = createWx(), reads = [], waits = []
  wx.setStorageSync('selectedStation', { id: 1, name: 'Synthetic station' })
  const app = createApp({ globalData: { isLogin: true, customerId: 7, accessToken: 'synthetic' } })
  const product = { id: 5, name: 'Synthetic water', category: 1, ticketEnabled: 1, effectiveTicketPrice: 8 }
  const page = loadPage('miniapp-user/pages/ticket/index.js', { wx, app, stubs: {
    'api/ticket': { getTicketAccounts: async () => ({ code: 0, data: [] }), getTicketRecords: async () => ({ code: 0, data: [] }),
      getTicketPackages: () => { const wait = deferred(); waits.push(wait); reads.push(5); return wait.promise },
      getTicketPurchaseResult: async () => ({ code: 0, data: null }) },
    'api/product': { getStationProducts: async () => ({ code: 0, data: [product] }) }
  } })
  nativeSetData(page)
  return { page, wx, app, reads, waits, response: { code: 0, data: [{ id: 77, packageId: 77, source: 'CUSTOM', qty: 10, price: '80.00', unitPrice: '8.00' }] },
    async start() { page.onLoad({}); await page.onShow(); page.onShowPurchase(); page.onBuyProductSelect(event(5)) } }
}
test('a suspended ticket read releases its lock, preserves choice and exposes a new lifecycle retry', async () => {
  const t = ticketFixture(); await t.start(); t.page.onHide(); await t.page.onShow()
  assert.equal(t.page.data.showPurchase, true); assert.equal(t.page.data.buyForm.productId, 5)
  assert.equal(t.page.data.packagesLoading, false); assert.ok(t.page.data.packagesError)
  const retry = t.page.onRetryPackages(); t.page.onRetryPackages(); assert.equal(t.reads.length, 2)
  t.waits[1].resolve(t.response); await retry; assert.equal(t.page.data.packagesLoaded, true); assert.equal(t.page.data.packagesError, '')
  t.waits[0].resolve({ code: 0, data: [] }); await flush(); assert.equal(t.page.data.buyPackages.length, 1)
})
test('old ticket success or failure cannot unlock or overwrite a newer pending retry', async () => {
  for (const failed of [false, true]) {
    const t = ticketFixture(); await t.start(); t.page.onHide(); await t.page.onShow(); const retry = t.page.onRetryPackages()
    if (failed) t.waits[0].reject(Error('旧失败')); else t.waits[0].resolve(t.response)
    await flush(); assert.equal(t.page.data.packagesLoading, true); assert.equal(t.page.data.packagesError, '')
    t.waits[1].resolve(t.response); await retry; assert.equal(t.page.data.packagesLoading, false)
  }
})
test('ticket retry failure preserves the product and allows a subsequent successful empty read', async () => {
  const t = ticketFixture(); await t.start(); t.page.onHide(); await t.page.onShow(); const retry = t.page.onRetryPackages()
  t.waits[1].reject(Error('合成失败')); await retry; assert.ok(t.page.data.packagesError); assert.equal(t.page.data.buyForm.productId, 5)
  const next = t.page.onRetryPackages(); t.waits[2].resolve({ code: 0, data: [] }); await next
  assert.equal(t.page.data.packagesLoaded, true); assert.equal(t.page.data.packagesLoading, false); assert.equal(t.page.data.packagesError, '')
  t.waits[0].resolve(t.response); await flush(); assert.deepEqual(t.page.data.buyPackages, [])
})
test('normal ticket reads still finish once and closing then reopening cannot revive an old response', async () => {
  const t = ticketFixture(); await t.start(); t.waits[0].resolve(t.response); await flush()
  assert.equal(t.page.data.packagesLoaded, true); t.page.onClosePurchase(); t.page.onShowPurchase(); t.page.onBuyProductSelect(event(5))
  t.page.onClosePurchase(); t.waits[1].resolve(t.response); await flush(); assert.equal(t.page.data.showPurchase, false); assert.deepEqual(t.page.data.buyPackages, [])
})
;(async () => {
  const done = armWatchdog(30000)
  try { for (const t of tests) { await t.run(); console.log('PASS ' + t.name) } console.log('AQUAFLOW_SUITE_OK ' + tests.length) }
  finally { done() }
})().catch(error => { console.error(error); process.exitCode = 1 })
