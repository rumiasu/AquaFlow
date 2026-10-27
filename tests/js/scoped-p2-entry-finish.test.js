const assert = require('assert'), fs = require('fs'), path = require('path')
const R = process.env.AQUAFLOW_REPO || path.resolve(__dirname, '../..')
const { loadPage, createWx, createApp, armWatchdog } = require(R + '/tests/js/harness')
const { parseWxml, renderElements } = require(R + '/tests/js/wxml-tree')
const done = armWatchdog(30000), tests = [], test = (name, run) => tests.push({ name, run })
const ok = data => ({ code: 0, data }), event = id => ({ currentTarget: { dataset: { id } } })
const source = file => fs.readFileSync(R + '/' + file, 'utf8')
const elements = (file, data) => renderElements(parseWxml(source(file)), data, { includeText: true })
const A = { id: 11, name: '当前下单站' }, B = { id: 22, name: '历史资产站' }
test('delivered and cancelled tabs query their actual statuses and retain loaded-page scope', async () => {
  const requests = [], wx = createWx(), app = createApp({ globalData: { isLogin: true,
    userInfo: { staffId: 1, stationId: 11, role: 'STATION_MANAGER' } } })
  const page = loadPage('miniapp-delivery/pages/station-mgmt/orders/index.js', { wx, app, stubs: {
    'api/station-mgmt': { getOrders: async query => {
      requests.push(query)
      return ok(Array.from({ length: query.status === 3 && query.page === 1 ? 20 : 1 }, (_, index) => ({
        id: query.page * 100 + index, status: query.status, statusText: query.status === 3 ? '已送达' : '已取消' })))
    }, getCrossStationOrders: async () => ok({ count: 0 }) }
  } })
  assert(page.data.tabs.some(tab => tab.id === 3 && tab.name === '已送达'))
  assert(page.data.tabs.some(tab => tab.id === 5 && tab.name === '已取消'))
  page.setData({ customerId: 7, startDate: '2026-10-01', endDate: '2026-10-24' })
  await page.onTabChange(event(3)); await page.onLoadMore()
  assert.equal(requests[0].status, 3); assert.equal(requests[1].page, 2); assert.equal(page.data.list.length, 21)
  assert.equal(requests[0].customerId, 7); assert.equal(requests[1].createTimeEnd, '2026-10-24')
  const rendered = elements('miniapp-delivery/pages/station-mgmt/orders/index.wxml', page.data)
  assert(rendered.find(node => node.className === 'history-scope').text.includes('已加载 21 张'))
  assert.deepEqual(rendered.filter(node => node.className === 'tab-badge').map(node => node.text), ['21'])
  await page.onTabChange(event(5)); assert.equal(requests.at(-1).status, 5); assert.equal(requests.at(-1).page, 1)
  assert.equal(page.data.list.length, 1); assert.equal(page.data.list[0].statusText, '已取消')
})
function orders(read = async () => ok([]), guest = false) {
  const wx = createWx(), app = createApp({ globalData: { isLogin: !guest, customerId: guest ? null : 7 } })
  wx.__storage.set('selectedStation', A)
  const page = loadPage('miniapp-user/pages/order/list.js', { wx, app, stubs: { 'api/order': { getOrders: read } } })
  return { page, wx, app }
}
const orderView = t => elements('miniapp-user/pages/order/list.wxml', t.page.data)
const nextStep = view => view.some(node => node.attrs.bindtap === 'onGoOrderHome')
test('a successfully loaded empty order history offers ordering without changing the selected station', async () => {
  const t = orders(); await t.page.loadOrders(); assert(nextStep(orderView(t)))
  t.page.onGoOrderHome(); assert.deepEqual(t.wx.__calls.nav.at(-1), { type: 'switchTab', url: '/pages/home/index' })
  assert.equal(t.wx.__storage.get('selectedStation').id, 11); assert.equal(t.wx.__calls.storageSet.length, 0)
})
test('loading, failed, guest and unqueried histories do not show an empty-history ordering action', async () => {
  const fresh = orders(); assert(!nextStep(orderView(fresh)))
  const failed = orders(async () => { throw Error('合成读取失败') }); await failed.page.loadOrders()
  assert(!nextStep(orderView(failed))); failed.page.onGoOrderHome(); assert.equal(failed.wx.__calls.nav.length, 0)
  const guest = orders(undefined, true); await guest.page.loadOrders(); assert(!nextStep(orderView(guest)))
})
test('an empty filtered order category names the filter rather than claiming the customer has no orders', async () => {
  const t = orders(); t.page.setData({ currentTab: 3 }); await t.page.loadOrders()
  const text = orderView(t).find(node => node.className === 'local-empty')
  assert(text); const visible = orderView(t).map(node => node.text).join('\n')
  assert(visible.includes('当前分类暂无订单')); assert(!visible.includes('您没有订单'))
})
test('a queued empty-history action cannot navigate after the history acquires an order', async () => {
  const t = orders(); await t.page.loadOrders(); t.page.setData({ orders: [{ id: 1 }] })
  t.page.onGoOrderHome(); assert.equal(t.wx.__calls.nav.length, 0)
})
const summary = extra => ({ independentRights: true, rightBuckets: 0, availableRights: 0, owedBuckets: 0,
  heldBuckets: 0, occupiedBuckets: 0, pendingReturns: 0, storageBuckets: 0, depositBalance: 0, ...extra })
const holding = extra => ({ productId: 5, productName: '合成桶装水', independentRights: true, assetQty: 3,
  heldTotalQty: 3, inTransitQty: 0, occupiedQty: 1, owedQty: 0, storageQty: 2, availableRights: 1, deposit: 50, ...extra })
async function barrel(options = {}) {
  const wx = createWx(), app = createApp(), requests = [], state = { fail: !!options.fail, pickerOpens: 0 }
  if (!options.stationless) wx.__storage.set('selectedStation', A)
  delete require.cache[require.resolve(R + '/miniapp-user/utils/asset-view-station')]
  const page = loadPage('miniapp-user/pages/barrel/index.js', { wx, app, stubs: {
    'api/asset-stations': { getAssetStation: async () => ok(B) },
    'api/barrel': {
      getBarrelSummary: async station => { requests.push(station); if (state.fail) throw Error('合成读取失败'); return ok(summary(options.summary)) },
      getBarrelSummaryByType: async () => { if (state.fail) throw Error('合成读取失败'); return ok(options.holdings || []) },
      getBarrelRecords: async () => ok([])
    }
  } })
  page.selectComponent = id => { assert.equal(id, '#asset-station-view'); return { openPicker() { state.pickerOpens++ } } }
  page.onLoad(options.stationless ? {} : { stationId: 22 }); await page.onShow()
  return { page, wx, app, requests, state }
}
// 微信允许空对象路径绑定；投影器对 null 更严格，仅补空对象以读取可见按钮，不补任何数量。
const barrelView = t => elements('miniapp-user/pages/barrel/index.wxml', { ...t.page.data, summary: t.page.data.summary || {} })
test('true-empty barrel actions preserve the legal viewed asset station and the distinct order station', async () => {
  const t = await barrel(); const view = barrelView(t)
  assert(nextStep(view)); assert(view.some(node => node.attrs.bindtap === 'onChooseAssetStation'))
  assert(view.map(node => node.text).join('\n').includes('订水使用当前下单水站（当前下单站）'))
  t.page.onChooseAssetStation(); assert.equal(t.state.pickerOpens, 1)
  t.page.onGoOrderHome(); assert.equal(t.wx.__calls.nav.at(-1).url, '/pages/home/index')
  assert.equal(t.page.data.viewStationId, 22); assert.equal(t.wx.__storage.get('selectedStation').id, 11)
  assert.equal(t.wx.__calls.storageSet.length, 0); assert.deepEqual(t.requests, [22])
})
test('missing asset station opens only the asset selector and does not claim a loaded empty account', async () => {
  const t = await barrel({ stationless: true }), view = barrelView(t)
  assert(view.some(node => node.attrs.bindtap === 'onChooseAssetStation')); assert(!nextStep(view))
  assert.equal(t.page.data.viewStationId, null); assert.equal(t.requests.length, 0)
  t.page.onChooseAssetStation(); assert.equal(t.state.pickerOpens, 1); assert.equal(t.wx.__calls.storageSet.length, 0)
})
test('failed or partial barrel reads do not present an ordering action as a successful empty account', async () => {
  for (const options of [{ fail: true }, { holdings: [holding({ storageQty: undefined })] }]) {
    const t = await barrel(options); assert(t.page.data.holdingsFailed); assert(!nextStep(barrelView(t)))
    t.page.onGoOrderHome(); assert.equal(t.wx.__calls.nav.length, 0)
  }
})
test('known storage or unclaimed quantity is visible before expanding detail and is not described as extra returned physical barrels', async () => {
  const t = await barrel({ holdings: [holding()] })
  assert.equal(t.page.data.expandedProductId, undefined)
  const text = barrelView(t).map(node => node.text).join('\n')
  assert(text.includes('暂存或待领取 2 个（账面记录，非实物盘点）'))
  assert(!text.includes('您多还的桶')); assert(!text.includes('账面桶数 1 个'))
  t.page.onToggleBarrelDetail(event(5))
  assert.equal(barrelView(t).filter(node => (node.text || '').includes('暂存或待领取 2 个')).length, 1)
})
test('missing storage quantity never becomes a displayed zero; genuine zero remains a known zero in source data', async () => {
  const missing = await barrel({ holdings: [holding({ storageQty: null })] })
  assert(missing.page.data.holdingsFailed); assert.deepEqual(missing.page.data.customerBarrelAsset, [])
  assert(!barrelView(missing).map(node => node.text).join('\n').includes('暂存或待领取 0'))
  const zero = await barrel({ holdings: [holding({ storageQty: 0 })] })
  assert.equal(zero.page.data.customerBarrelAsset[0].storageQty, 0); assert.equal(zero.page.data.holdingsFailed, false)
})
test('legacy summary uses a neutral storage or unclaimed label rather than claiming physically returned barrels', async () => {
  const t = await barrel({ summary: { independentRights: false, storageBuckets: 2 }, holdings: [holding({ independentRights: false })] })
  const text = barrelView(t).map(node => node.text).join('\n')
  assert(text.includes('暂存或待领取记录：2 个，实际交接请向资产水站确认')); assert(!text.includes('您多还的桶'))
})
test('only the scoped primary actions and native selections adopt the established primary token', () => {
  const cssFiles = ['pages/address/edit.wxss', 'pages/address/list.wxss', 'pages/notice/index.wxss', 'pages/mine/company.wxss', 'pages/exception/list/list.wxss']
  for (const file of cssFiles) { const css = source('miniapp-user/' + file); assert(!/#1264d1/i.test(css), file); assert(css.includes('background: var(--primary-color)')) }
  const primary = source('miniapp-user/styles/variable.wxss').match(/--primary-color:\s*(#[\da-f]+)/i)[1]
  assert.equal(JSON.parse(source('miniapp-user/app.json')).tabBar.selectedColor.toUpperCase(), primary.toUpperCase())
  assert(source('miniapp-delivery/pages/settings/index.wxml').includes('bindchange="onToggleReminder" color="' + primary + '"'))
  assert(source('miniapp-user/pages/barrel/index.wxml').includes('name="barrel" size="40rpx" color="#1677FF"'))
})
;(async () => {
  let passed = 0
  for (const { name, run } of tests) {
    try { await run(); passed++; console.log('PASS ' + name) }
    catch (error) { process.exitCode = 1; console.error('FAIL ' + name + '\n' + error.stack) }
  }
  console.log('SCOPED_P2_ENTRY_RESULT ' + passed + '/' + tests.length)
  if (passed === tests.length) console.log('AQUAFLOW_SUITE_OK ' + passed)
  done()
})().catch(error => { console.error(error); done(); process.exitCode = 1 })
