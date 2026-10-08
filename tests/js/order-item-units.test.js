const assert = require('assert'), fs = require('fs'), path = require('path')
const { loadPage, createWx, createApp, armWatchdog, ROOT } = require('./harness')
const done = armWatchdog(30000), tests = []
const test = (name, run) => tests.push({ name, run })
const helper = side => require(path.join(ROOT, side, 'utils/order-item-view.js'))
const mixed = [{ id: 1, productId: 10, productNameSnapshot: 'water', quantity: 3, barrelItem: true },
  { id: 2, productId: 11, productNameSnapshot: 'other', quantity: 2, barrelItem: false }]
function deliveryPage(file, extra = {}) {
  const wx = createWx(), app = createApp({ globalData: { isLogin: true, userInfo: { role: 'DELIVERY', stationId: 1 } } })
  const order = { id: 20, status: 2, stationId: 1, items: mixed, ...extra }
  const page = loadPage(file, { wx, app, stubs: {
    'api/delivery': { getOrderDetail: async () => ({ code: 0, data: order }) },
    'utils/request': { get: async () => ({ code: 0, data: [] }) }
  } })
  return { page, order }
}
for (const side of ['miniapp-user', 'miniapp-delivery']) {
  test(side + ' uses only actual category or barrel scope and defaults to pieces', () => {
    const { itemUnit } = helper(side)
    assert.equal(itemUnit({ category: 1 }), '桶'); assert.equal(itemUnit({ category: '2' }), '瓶'); assert.equal(itemUnit({ category: 3 }), '台')
    assert.equal(itemUnit({ barrelItem: true }), '桶'); assert.equal(itemUnit({ barrelItem: false }), '件')
    for (const row of [null, {}, { category: 99 }, { category: true }, { category: [1] }, { productNameSnapshot: '桶装水饮水机瓶' }, { barrelItem: 'true' }]) assert.equal(itemUnit(row), '件')
  })
  test(side + ' mixed detail mapping preserves original business fields without mutating inputs', () => {
    const rows = JSON.parse(JSON.stringify(mixed)), result = helper(side).withItemUnits(rows)
    assert.deepStrictEqual(result.map(x => x.quantityUnit), ['桶', '件'])
    assert.equal(rows[0].quantityUnit, undefined); assert.equal(result[0].quantity, 3); assert.equal(result[0].productId, 10)
  })
  test(side + ' authoritative server summary keeps its text and needs no detail request', () => {
    const summary = helper(side).orderSummary({ itemSummary: '水 3桶，其他 2件', quantity: 5, deliveryBucketQty: 3 })
    assert.equal(summary.text, '水 3桶，其他 2件'); assert.equal(summary.meta, '')
  })
  test(side + ' fallback mixed total is pieces rather than total barrels', () => {
    const summary = helper(side).orderSummary({ firstProductName: '水', quantity: 5, deliveryBucketQty: 3 })
    assert.equal(summary.text, '水'); assert.equal(summary.meta, '等 5 件')
  })
  test(side + ' actual detail rows can form a complete mixed summary', () => {
    const summary = helper(side).orderSummary({ items: mixed, quantity: 5 })
    assert.equal(summary.text, 'water 3桶，other 2件'); assert.equal(summary.meta, '')
  })
}
test('employee detail uses barrel scope for each row and keeps unknown stock shortages neutral', async () => {
  const t = deliveryPage('miniapp-delivery/pages/order/detail.js', { stockPrep: { ready: false, items: [{ productId: 11, productName: 'other', shortage: 2 }] } })
  await t.page.loadOrderDetail(20)
  assert.deepStrictEqual(t.page.data.order.items.map(x => x.quantityUnit), ['桶', '件'])
  assert.ok(t.page.data.order.stockPrepText.includes('2 件')); assert.ok(!t.page.data.order.stockPrepText.includes('2 桶'))
  const wxml = fs.readFileSync(path.join(ROOT, 'miniapp-delivery/pages/order/detail.wxml'), 'utf8')
  assert.ok(wxml.includes('{{it.quantityUnit}}')); assert.ok(!wxml.includes('{{it.quantity}} 桶'))
})
test('completion goods use the same real barrel scope while return obligations stay unchanged', async () => {
  const t = deliveryPage('miniapp-delivery/pages/order/complete.js')
  await t.page.loadOrder(20)
  assert.deepStrictEqual(t.page.data.deliveryItems.map(x => x.qtyText), ['3 桶', '2 件'])
  assert.equal(t.page.data.items.length, 1); assert.equal(t.page.data.items[0].expected, 0)
})
test('customer detail preserves source barrel scope before projecting display rows', async () => {
  const page = loadPage('miniapp-user/pages/order/detail.js', { stubs: {
    'api/order': { getOrderDetail: async () => ({ code: 0, data: { id: 20, items: mixed } }) },
    'api/product': { getProductDetail: async () => ({ code: 0, data: {} }) },
    'api/orderImage': { getOrderImages: async () => ({ code: 0, data: [] }) },
    'api/station': { getStationPublicPhone: async () => ({ code: 0, data: {} }) }
  } })
  await page.loadOrder(20); assert.deepStrictEqual(page.data.items.map(x => x.quantityUnit), ['桶', '件'])
  const wxml = fs.readFileSync(path.join(ROOT, 'miniapp-user/pages/order/detail.wxml'), 'utf8'); assert.ok(wxml.includes('{{item.quantityUnit}}'))
})
test('customer list component uses the shared unit mapping for its item fallback', () => {
  let config; global.Component = value => { config = value }
  const file = path.join(ROOT, 'miniapp-user/components/OrderCard/index.js'); delete require.cache[require.resolve(file)]; require(file)
  const instance = { data: { order: { items: mixed } }, setData(patch) { Object.assign(this.data, patch) } }
  config.observers.order.call(instance, instance.data.order)
  assert.deepStrictEqual(instance.data.displayItems.map(x => x.quantityUnit), ['桶', '件'])
  const wxml = fs.readFileSync(path.join(ROOT, 'miniapp-user/components/OrderCard/index.wxml'), 'utf8'); assert.ok(wxml.includes('{{itm.quantityUnit}}'))
})
test('two independently packaged miniapps keep the small unit helper identical', () => {
  assert.equal(fs.readFileSync(path.join(ROOT, 'miniapp-user/utils/order-item-view.js'), 'utf8'), fs.readFileSync(path.join(ROOT, 'miniapp-delivery/utils/order-item-view.js'), 'utf8'))
})
;(async () => {
  let passed = 0, failed = 0
  for (const t of tests) { try { await t.run(); passed++; console.log('PASS ' + t.name) } catch (e) { failed++; console.error('FAIL ' + t.name + ' [' + e.name + ']') } }
  console.log(`order item units: ${passed} passed, ${failed} failed`)
  if (failed) process.exitCode = 1; else console.log('AQUAFLOW_SUITE_OK ' + passed)
})().catch(() => { process.exitCode = 1 }).finally(done)
