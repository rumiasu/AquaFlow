'use strict'
const assert = require('assert'), fs = require('fs'), path = require('path')
const { loadPage, createWx, createApp, armWatchdog, ROOT } = require('./harness')
const { parseWxml, renderElements, validateConditionalSiblings } = require('./wxml-tree')
const { homeNavLayout, catalogState } = require('../../miniapp-user/pages/home/home-view-state')
const done = armWatchdog(), tests = [], test = (name, run) => tests.push({ name, run })
const deferred = () => { let resolve, reject; const promise = new Promise((ok, fail) => { resolve = ok; reject = fail }); return { promise, resolve, reject } }
const ok = data => ({ code: 0, data })
const water = { id: 10, name: '当前水站商品', category: 1, effectivePrice: 20, effectiveDeposit: 40 }
const station = id => ({ id, name: '水站' + id, status: 1 })
function fixture(overrides = {}) {
  const wx = createWx(), carts = { 1: { 10: 2 }, 2: { 20: 1 } }, calls = { products: [], rights: [], notifications: [], stations: [] }
  const app = createApp({ getCart: id => ({ ...(carts[id] || {}) }) })
  const page = loadPage('miniapp-user/pages/home/index.js', { wx, app, stubs: {
    'api/product': { getStationProducts: id => { calls.products.push(id); return overrides.products ? overrides.products(id) : Promise.resolve(ok([water])) } },
    'api/order': { getMyLatestStation: async () => ok(null), getOrders: overrides.orders || (async () => ok([])), getOrderDetail: async () => ok(null) },
    'api/address': { getAddresses: overrides.addresses || (async () => ok([{ id: 12, detail: '当前地址', isDefault: 1 }])) },
    'api/barrel': { getBarrelSummary: async () => ok({}), getBarrelSummaryByType: id => { calls.rights.push(id); return overrides.rights ? overrides.rights(id) : Promise.resolve(ok([])) } },
    'api/station': { getStationStatus: async () => ok({}), getPublicStations: () => { calls.stations.push(true); return overrides.stations ? overrides.stations() : Promise.resolve(ok([])) } },
    'api/notification': { getUnreadNotifications: () => { calls.notifications.push(true); return overrides.notifications ? overrides.notifications() : Promise.resolve(ok([])) }, markAllRead: async () => ok(null) },
    'api/payment': { getQuote: overrides.quote || (async () => ok({ independentRights: true, barrelPurchases: [] })) }
  } })
  wx.setStorageSync('selectedStation', station(1))
  page.setData({ isLogin: true, currentStationId: 1, currentStation: station(1), loading: false })
  return { page, wx, app, carts, calls }
}
for (const [status, statusText, expected] of [[1, '水站已受理，待配送', '水站已受理，待配送'], [2, '本单正在配送', '本单正在配送'], [2, undefined, '订单状态待核实']]) {
  test('current order title uses server text or a neutral fallback: ' + statusText, async () => {
    const t = fixture({ orders: async () => ok([{ id: 81, status, statusText }]) })
    await t.page.renderActiveShip({ id: 81, status, statusText }, 1)
    assert.equal(t.page.data.activeShip.title, expected)
  })
}
const tree = parseWxml(fs.readFileSync(path.join(ROOT, 'miniapp-user/pages/home/index.wxml'), 'utf8'))
const pageTree = { ...tree, children: tree.children.filter(n => n.tag !== 'template') }
const visible = page => renderElements(pageTree, page.data, { includeText: true })
const hasClass = (page, cls) => visible(page).some(n => n.className.split(/\s/).includes(cls))
const texts = page => visible(page).map(n => n.text || '').join('\n')

test('actual WXML has legal branches and reserves the whole menu navigation region', () => {
  validateConditionalSiblings(tree)
  const t = fixture(); t.wx.getWindowInfo = () => ({ statusBarHeight: 47, windowWidth: 390 })
  t.wx.getMenuButtonBoundingClientRect = () => ({ top: 51, left: 291, height: 32 })
  t.page.onLoad()
  assert.equal(t.page.data.navBarHeight, 40)
  assert.equal(t.page.data.statusBarHeight + t.page.data.navBarHeight, 87)
  assert(t.page.data.statusBarHeight + t.page.data.navBarHeight >= 83)
  assert(t.page.data.navContentWidth + 24 <= 291)
  const nodes = visible(t.page), nav = nodes.findIndex(n => n.className === 'home-nav')
  const hero = nodes.findIndex(n => n.className === 'hero')
  assert(nav >= 0 && hero > nav)
  assert.equal(nodes.find(n => n.className === 'home-nav-title').text, '送水到家')
})
test('navigation falls back on throwing, missing and malformed platform data', () => {
  assert.deepStrictEqual(homeNavLayout({ getWindowInfo() { throw Error('window unavailable') }, getSystemInfoSync: () => ({ statusBarHeight: 24, windowWidth: 320 }), getMenuButtonBoundingClientRect: () => ({ top: 0, left: 0, height: 0 }) }), { statusBarHeight: 24, navBarHeight: 44, navContentWidth: 180 })
  assert.equal(homeNavLayout({ getWindowInfo: () => null }).navBarHeight, 44)
  assert.equal(homeNavLayout({}).statusBarHeight, 44)
  const noStatus = homeNavLayout({ getWindowInfo: () => ({ statusBarHeight: 0, windowWidth: 320 }), getMenuButtonBoundingClientRect: () => ({ top: 28, height: 32, left: 221 }) })
  assert(noStatus.statusBarHeight + noStatus.navBarHeight >= 60)
  const badLeft = homeNavLayout({ getWindowInfo: () => ({ statusBarHeight: 0, windowWidth: 320 }), getMenuButtonBoundingClientRect: () => ({ top: 28, height: 32, left: 0 }) })
  assert(badLeft.navBarHeight >= 60); assert.equal(badLeft.navContentWidth, 180)
  assert.equal(homeNavLayout({ getWindowInfo: () => ({ safeArea: { top: 44 } }), getMenuButtonBoundingClientRect() { throw Error('menu unavailable') } }).statusBarHeight, 44)
})
test('catalog success-empty, error, malformed and available responses stay distinct', () => {
  assert.equal(catalogState(ok([])), 'empty'); assert.equal(catalogState(ok([water])), 'ready')
  for (const response of [null, { code: 1, data: [] }, ok(null), ok({}), { data: [water] }]) assert.equal(catalogState(response), 'error')
})
test('no station shows its own Chinese entry without address, fake products or checkout', () => {
  const t = fixture(); t.page.setData({ productsView: [water], state: 'ready', barrelVisible: true })
  t.page.showNoStation()
  assert(hasClass(t.page, 'station-welcome')); assert(texts(t.page).includes('先选择为你送水的水站'))
  for (const cls of ['addr', 'shop-sec', 'checkout']) assert(!hasClass(t.page, cls), cls)
  assert.equal(t.page.data.currentStationId, null); assert.deepStrictEqual(t.carts[1], { 10: 2 })
})
test('no-station entry remains visible while its selectable list is still loading', async () => {
  const gate = deferred(), t = fixture({ stations: () => gate.promise })
  t.wx.removeStorageSync('selectedStation')
  const read = t.page.checkStation(); await new Promise(r => setImmediate(r))
  assert.equal(t.page.data.state, 'noStation'); assert.equal(t.page.data.loading, false)
  assert(hasClass(t.page, 'station-welcome')); assert.equal(t.page.data.stationListLoading, true)
  gate.resolve(ok([])); await read
})
test('pending catalog is loading, never an empty-product conclusion or checkout', async () => {
  const gate = deferred(), t = fixture({ products: () => gate.promise })
  const read = t.page.loadData()
  assert.equal(t.page.data.catalogState, 'loading'); assert(hasClass(t.page, 'home-loading'))
  assert(!hasClass(t.page, 'catalog-state')); assert(!hasClass(t.page, 'checkout'))
  gate.resolve(ok([])); await read
  assert.equal(t.page.data.catalogState, 'empty'); assert(texts(t.page).includes('该水站暂无可订商品'))
  assert(!hasClass(t.page, 'checkout'))
})
test('failed catalog retains the saved cart and offers actual retry and station-switch actions', async () => {
  const t = fixture({ products: async () => { throw Error('offline') } }); await t.page.loadData()
  assert.equal(t.page.data.catalogState, 'error'); assert(texts(t.page).includes('商品暂时没加载出来'))
  assert(!texts(t.page).includes('该水站暂无可订商品')); assert(!hasClass(t.page, 'checkout'))
  assert.deepStrictEqual(t.carts[1], { 10: 2 })
  const buttons = visible(t.page).filter(n => n.tag === 'button')
  assert(buttons.some(n => n.text === '重新加载' && n.attrs.bindtap === 'onRetryHome'))
  assert(buttons.some(n => n.text === '切换水站' && n.attrs.bindtap === 'onOpenStationList'))
})
test('business failure and malformed catalog payload never masquerade as successful empty', async () => {
  for (const response of [{ code: 1, data: [] }, ok({})]) {
    const t = fixture({ products: async () => response }); await t.page.loadData()
    assert.equal(t.page.data.catalogState, 'error'); assert(!texts(t.page).includes('该水站暂无可订商品'))
  }
})
test('one retry in flight issues one overview read and restores existing quantities after recovery', async () => {
  let fail = true; const gate = deferred()
  const t = fixture({ products: async () => { if (fail) throw Error('offline'); return gate.promise } })
  await t.page.loadData(); fail = false
  const a = t.page.onRetryHome(), b = t.page.onRetryHome()
  assert.deepStrictEqual(t.calls.products, [1, 1])
  gate.resolve(ok([water])); await Promise.all([a, b])
  assert.equal(t.page.data.catalogState, 'ready'); assert.equal(t.page.data.total.count, 2)
  assert(hasClass(t.page, 'checkout')); assert.equal(t.page.data.loadError, '')
  assert.equal(t.page._homeRetry, null)
})
test('opening or retrying a pending station list does not issue duplicate requests', async () => {
  const gate = deferred(), t = fixture({ stations: () => gate.promise })
  const first = t.page.onOpenStationList(); t.page.onOpenStationList(); t.page.onRetryStationList()
  assert.equal(t.calls.stations.length, 1); gate.resolve(ok([])); await first
})
test('a late station A catalog cannot overwrite station B state, product or loading completion', async () => {
  const old = deferred(), fresh = deferred()
  const t = fixture({ products: id => id === 1 ? old.promise : fresh.promise })
  const a = t.page.loadData(); t.page.setData({ currentStationId: 2, currentStation: station(2) })
  const b = t.page.loadData(); old.resolve(ok([water])); await a
  assert.equal(t.page.data.loading, true); assert.equal(t.page.data.catalogState, 'loading')
  fresh.resolve(ok([{ ...water, id: 20, name: '新站商品' }])); await b
  assert.equal(t.page.data.products[0].id, 20); assert.equal(t.page.data.catalogState, 'ready')
})
for (const change of ['hide', 'identity', 'unload']) test('late overview cannot repaint after ' + change, async () => {
  const gate = deferred(), t = fixture({ products: () => gate.promise }); const read = t.page.loadData()
  if (change === 'hide') t.page.onHide(); else if (change === 'unload') t.page.onUnload(); else t.app.globalData.customerId = 8
  const before = JSON.stringify(t.page.data); gate.resolve(ok([water])); await read
  assert.equal(JSON.stringify(t.page.data), before)
})
test('short recovery strip hides details until expanded and never places long errors in navigation', () => {
  const t = fixture(); t.page._noteLoadError('桶资产暂未加载。', 'overview')
  assert(texts(t.page).includes('部分信息暂未加载')); assert(!texts(t.page).includes('桶资产暂未加载。'))
  t.page.onToggleLoadDetails(); assert(texts(t.page).includes('桶资产暂未加载。'))
  t.page._noteLoadError('', 'overview'); assert(!hasClass(t.page, 'home-feedback'))
})
test('notification-only failure leaves products and checkout usable and disappears after its retry', async () => {
  let fail = true; const t = fixture({ notifications: async () => { if (fail) throw Error('offline'); return ok([]) } })
  await t.page.loadData(); await t.page.checkNotifications()
  assert.equal(t.page.data.catalogState, 'ready'); assert(hasClass(t.page, 'checkout'))
  assert.equal(t.page.data.loadIssueDetails.length, 1); assert.equal(t.page.data.loadIssueDetails[0].key, 'notifications')
  fail = false; await t.page.checkNotifications(); assert.equal(t.page.data.loadError, '')
})
test('asset read failure is explicit beside selected cart and never called a verified balance', async () => {
  const t = fixture({ rights: async () => { throw Error('offline') } }); await t.page.loadData()
  assert(t.page.data.purchaseHint.includes('桶资产暂未核实')); assert(texts(t.page).includes('桶资产暂未核实'))
  assert.equal(t.page.data.total.count, 2); assert.deepStrictEqual(t.carts[1], { 10: 2 })
})
test('unverified home quote keeps the amount caveat and confirmation destination without claiming payment', async () => {
  const t = fixture({ quote: async () => { throw Error('offline') } }); await t.page.loadData()
  assert.equal(t.page.data.homeQuoteState, 'error'); assert(texts(t.page).includes('费用暂未核实'))
  assert(t.page.data.total.detailText.includes('未核实'))
  t.page.onSubmit(); assert(t.wx.__calls.nav[0].url.startsWith('/pages/order/create?'))
  assert(!t.wx.__calls.nav[0].url.includes('pay=true'))
})
test('ready, failed, successful-empty and ready transitions never lose the saved selection or reuse an old footer', async () => {
  let phase = 0
  const t = fixture({ products: async () => { if (phase === 1) throw Error('offline'); return ok(phase === 2 ? [] : [water]) } })
  for (phase = 0; phase < 4; phase++) {
    await t.page.loadData()
    assert.equal(t.page.data.catalogState, ['ready', 'error', 'empty', 'ready'][phase])
    assert.equal(hasClass(t.page, 'checkout'), phase === 0 || phase === 3)
    assert.deepStrictEqual(t.carts[1], { 10: 2 })
  }
  assert.equal(t.page.data.total.count, 2); assert.equal(t.page.data.loadError, '')
})
test('a queued checkout tap cannot use previous products during loading, failure, empty or missing station', () => {
  for (const status of ['loading', 'error', 'empty', 'noStation']) {
    const t = fixture(); t.page.setData({ catalogState: status, loading: status === 'loading',
      currentStationId: status === 'noStation' ? null : 1, products: [water], total: { count: 2 }, address: { id: 12 } })
    t.page.onSubmit(); assert.equal(t.wx.__calls.nav.length, 0, status)
    assert.equal(t.wx.__calls.toast.length, 1, status)
  }
})

test('a genuine English station name is displayed verbatim through the actual restore handler', async () => {
  const t = fixture(); t.wx.setStorageSync('selectedStation', { id: 1, name: 'SYNTH Task5 Scenario Station' })
  t.page.loadData = async () => {}; await t.page.checkStation()
  assert(texts(t.page).includes('SYNTH Task5 Scenario Station'))
})

test('notification failure remains visible through pending retry and another failure, clearing only on confirmed success', async () => {
  let phase = 0, gate = deferred()
  const t = fixture({ notifications: () => phase === 0 ? Promise.reject(Error('offline')) : gate.promise })
  await t.page.loadData(); await t.page.checkNotifications()
  assert(t.page.data.loadError); assert.equal(t.page.data.loadImpactSummary, '提醒暂未核实')
  phase = 1; const retry = t.page.checkNotifications()
  assert(t.page.data.loadError); assert.equal(t.page.data.notificationsLoading, true)
  assert(texts(t.page).includes('正在重试')); assert(texts(t.page).includes('提醒暂未核实'))
  gate.reject(Error('still offline')); await retry
  assert(t.page.data.loadError); assert.equal(t.page.data.notificationsLoading, false)
  gate = deferred(); const recovered = t.page.checkNotifications()
  assert(t.page.data.loadError); gate.resolve(ok([])); await recovered
  assert.equal(t.page.data.loadError, ''); assert.equal(t.page.data.loadImpactSummary, '')
})
test('same-session onShow retains failed reminder while its new read is pending', async () => {
  let fail = true; const gate = deferred()
  const t = fixture({ notifications: () => fail ? Promise.reject(Error('offline')) : gate.promise })
  await t.page.loadData(); await t.page.checkNotifications(); fail = false
  const previous = t.page.data.loadError
  t.page.checkStation = async () => {}; t.page.onShow()
  assert.equal(t.page.data.loadError, previous); assert.equal(t.page.data.notificationsLoading, true)
  assert(texts(t.page).includes('提醒暂未核实')); assert(texts(t.page).includes('正在重试'))
  gate.resolve(ok([])); await new Promise(r => setImmediate(r)); assert.equal(t.page.data.loadError, '')
})
test('account switch clears the old reminder and ignores its late failure while the new read is pending', async () => {
  let phase = 0; const old = deferred(), fresh = deferred()
  const t = fixture({ notifications: () => phase === 0 ? Promise.reject(Error('offline')) : phase === 1 ? old.promise : fresh.promise })
  await t.page.loadData(); await t.page.checkNotifications()
  phase = 1; const previous = t.page.checkNotifications()
  t.app.globalData.customerId = 8; phase = 2; t.page.checkStation = async () => {}; t.page.onShow()
  assert.equal(t.page.data.loadError, ''); assert.equal(t.page.data.loadImpactSummary, '')
  old.reject(Error('old account offline')); await previous
  assert.equal(t.page.data.loadError, ''); assert.equal(t.page.data.notificationsLoading, true)
  fresh.resolve(ok([])); await new Promise(r => setImmediate(r)); assert.equal(t.page.data.loadError, '')
})
test('catalog failure stays visible during retry, after another failure, and clears only after successful recovery', async () => {
  let phase = 0, gate = deferred()
  const t = fixture({ products: () => phase === 0 ? Promise.reject(Error('offline')) : gate.promise })
  await t.page.loadData(); const previous = t.page.data.loadError
  phase = 1; const retry = t.page.onRetryHome()
  assert.equal(t.page.data.loadError, previous); assert(texts(t.page).includes('商品暂未加载'))
  assert(texts(t.page).includes('正在重试')); gate.reject(Error('still offline')); await retry
  assert(t.page.data.loadError); assert.equal(t.page.data.catalogState, 'error')
  gate = deferred(); const recovered = t.page.onRetryHome(); assert(t.page.data.loadError)
  gate.resolve(ok([water])); await recovered; assert.equal(t.page.data.loadError, '')
})
test('order failure with available products and failed reminder states the visible order impact without expanding details', async () => {
  const t = fixture({ orders: async () => { throw Error('orders offline') }, notifications: async () => { throw Error('reminders offline') } })
  await Promise.all([t.page.loadData(), t.page.checkNotifications()])
  assert.equal(t.page.data.catalogState, 'ready'); assert(hasClass(t.page, 'checkout'))
  assert.equal(t.page.data.activeShip, null); assert.equal(t.page.data.showLoadDetails, false)
  assert.equal(t.page.data.loadImpactSummary, '订单状态与提醒暂未核实')
  assert(texts(t.page).includes('订单状态与提醒暂未核实'))
  assert(!texts(t.page).includes('已有信息仍可查看'))
})
test('unavailable whole catalog has a specific visible recovery impact rather than claiming information is available', async () => {
  const t = fixture({ products: async () => { throw Error('catalog offline') } }); await t.page.loadData()
  assert.equal(t.page.data.loadImpactSummary, '商品暂未加载，请重试后选水')
  assert(texts(t.page).includes('商品暂未加载，请重试后选水')); assert(!hasClass(t.page, 'checkout'))
  assert(!texts(t.page).includes('已有信息仍可查看'))
})

test('WB-04 selected non-default address survives actual onShow refresh and checkout uses its fresh record', async () => {
  const t = fixture({ addresses: async () => ok([{ id: 12, detail: '默认地址', isDefault: 1 }, { id: 13, detail: '更新后的已选地址', isDefault: 0 }]) })
  t.wx.setStorageSync('selectedAddress', { id: 13, detail: '选择时的旧地址' })
  t.page.onShow(); await new Promise(setImmediate)
  assert.equal(t.page.data.address.id, 13); assert.equal(t.page.data.address.detail, '更新后的已选地址')
  t.page.onSubmit()
  const query = new URL('https://synthetic.invalid' + t.wx.__calls.nav.at(-1).url).searchParams
  assert.equal(query.get('addressId'), '13')
  assert.equal(query.get('addressDetail'), '更新后的已选地址')
})
test('removed selected address remains unselected across retries and cannot silently fall back to another default', async () => {
  const t = fixture(); t.wx.setStorageSync('selectedAddress', { id: 13 })
  t.page.onShow(); await new Promise(setImmediate)
  assert.equal(t.page.data.address, null); assert(t.page.data.addressHint.includes('重新选择'))
  await t.page.loadData(); assert.equal(t.page.data.address, null)
  t.page.onSubmit()
  assert(t.wx.__calls.modal.at(-1).content.includes('先前选择的地址已不可用'))
  assert(!t.wx.__calls.nav.some(n => n.url.includes('/pages/order/create')))
})
test('successful empty address list clears a prior selected address rather than retaining an unavailable record', async () => {
  const t = fixture({ addresses: async () => ok([]) }); t.wx.setStorageSync('selectedAddress', { id: 13 })
  t.page.onShow(); await new Promise(setImmediate)
  assert.equal(t.page.data.address, null); assert(t.page.data.addressHint.includes('不可用'))
})
test('address read failure preserves an explicit selection with a visible read-failure boundary', async () => {
  const t = fixture({ addresses: async () => { throw Error('SYNTH address offline') } })
  t.wx.setStorageSync('selectedAddress', { id: 13, detail: '本次选择' })
  t.page.onShow(); await new Promise(setImmediate)
  assert.equal(t.page.data.address.id, 13); assert(t.page.data.loadError.includes('配送地址'))
})
test('a new customer does not inherit the previous explicit address choice', async () => {
  let rows = [{ id: 12, isDefault: 1 }, { id: 13 }]
  const t = fixture({ addresses: async () => ok(rows) }); t.wx.setStorageSync('selectedAddress', { id: 13 })
  t.page.onShow(); await new Promise(setImmediate); assert.equal(t.page.data.address.id, 13)
  t.app.globalData.customerId = 8; rows = [{ id: 21, isDefault: 1 }]
  t.page.onShow(); await new Promise(setImmediate)
  assert.equal(t.page.data.address.id, 21); assert.equal(t.page._homeChosenAddressId, null)
})
test('an old address response cannot overwrite a newer explicit choice', async () => {
  const old = deferred(), latest = deferred(); let calls = 0
  const t = fixture({ addresses: () => ++calls === 1 ? old.promise : latest.promise })
  t.wx.setStorageSync('selectedAddress', { id: 13 }); t.page.onShow()
  t.wx.setStorageSync('selectedAddress', { id: 14 }); t.page.onShow()
  latest.resolve(ok([{ id: 12, isDefault: 1 }, { id: 14 }]))
  await new Promise(setImmediate)
  old.resolve(ok([{ id: 12, isDefault: 1 }, { id: 13 }]))
  await new Promise(setImmediate); assert.equal(t.page.data.address.id, 14)
})

test('WB-07 partially restored cart discloses an unavailable SKU while preserving both original quantities', async () => {
  const t = fixture(); t.carts[1][11] = 1; await t.page.loadData()
  assert.equal(t.page.data.unavailableCartCount, 1); assert(texts(t.page).includes('1 种已选商品暂不可订'))
  assert.equal(t.carts[1][11], 1); assert.equal(t.page.data.total.count, 2)
  assert.deepEqual(t.page.collectCartItems(), [{ productId: 10, quantity: 2 }])
  assert(visible(t.page).some(n => n.tag === 'button' && n.text === '重新核对商品' && n.attrs.bindtap === 'onRetryHome'))
})
test('unavailable cart hint clears and original quantity returns when the catalog includes that SKU again', async () => {
  let rows = [water]
  const t = fixture({ products: async () => ok(rows) }); t.carts[1][11] = 1
  await t.page.loadData(); rows = [water, { ...water, id: 11 }]; await t.page.loadData()
  assert.equal(t.page.data.unavailableCartCount, 0); assert.equal(t.page.data.total.count, 3)
  assert.equal(t.page.data.productsView.find(p => p.id === 11).qty, 1)
})
test('whole catalog failure cannot label saved cart products as unavailable', async () => {
  const t = fixture({ products: async () => { throw Error('SYNTH catalog offline') } })
  t.carts[1][11] = 1; await t.page.loadData()
  assert.equal(t.page.data.unavailableCartCount, 0); assert(!texts(t.page).includes('种已选商品暂不可订'))
  assert.equal(t.carts[1][11], 1)
})
test('WB-06 list photo failure activates the existing fallback without changing cart or quoting again', async () => {
  let quotes = 0
  const t = fixture({ products: async () => ok([{ ...water, imageUrl: 'SYNTH-broken' }]), quote: async () => { quotes++; return ok({}) } })
  await t.page.loadData(); const before = quotes
  t.page.onHomeProductImageError({ currentTarget: { dataset: { id: 10, url: 'SYNTH-broken' } } })
  assert.equal(t.page.data.productsView[0].imageUrl, ''); assert(hasClass(t.page, 'prod-img-empty'))
  assert.equal(t.page.data.total.count, 2); assert.equal(t.carts[1][10], 2); assert.equal(quotes, before)
})
test('old photo error cannot clear a different current URL and fresh catalog loading permits retry', async () => {
  const t = fixture({ products: async () => ok([{ ...water, imageUrl: 'SYNTH-current' }]) }); await t.page.loadData()
  t.page.onHomeProductImageError({ currentTarget: { dataset: { id: 10, url: 'SYNTH-old' } } })
  assert.equal(t.page.data.productsView[0].imageUrl, 'SYNTH-current')
  t.page.onHomeProductImageError({ currentTarget: { dataset: { id: 10, url: 'SYNTH-current' } } })
  await t.page.loadData(); assert.equal(t.page.data.productsView[0].imageUrl, 'SYNTH-current')
})
test('repeat photo failure preserves the original order identity, items and action', () => {
  const t = fixture(); const items = [{ productId: 10, quantity: 2 }]
  t.page.setData({ againOrder: { id: 101, img: 'SYNTH-broken', items } })
  t.page.onHomeProductImageError({ currentTarget: { dataset: { kind: 'again', id: 99, url: 'SYNTH-broken' } } })
  assert.equal(t.page.data.againOrder.img, 'SYNTH-broken')
  t.page.onHomeProductImageError({ currentTarget: { dataset: { kind: 'again', id: 101, url: 'SYNTH-broken' } } })
  assert.equal(t.page.data.againOrder.img, ''); assert.deepEqual(t.page.data.againOrder.items, items)
  t.page.onAgainTap(); assert(t.wx.__calls.nav.at(-1).url.includes('reorderId=101'))
})

async function run() {
  let failed = 0
  try {
    for (const t of tests) { try { await t.run(); console.log('PASS ' + t.name) } catch (e) { failed++; console.error('FAIL ' + t.name + ': ' + e.stack) } }
    if (failed) process.exitCode = 1
    else console.log('AQUAFLOW_SUITE_OK ' + tests.length)
  } finally { done() }
}
if (require.main === module) run().catch(e => { console.error(e); process.exitCode = 1 })
else { done(); module.exports = { fixture, water, station, tree, pageTree, visible, texts } }
