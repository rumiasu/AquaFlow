const assert = require('assert')
const fs = require('fs')
const path = require('path')
const { loadPage, createWx, createApp, armWatchdog, ROOT } = require('./harness')
const done = armWatchdog(30000)
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
const dataset = (id, delta) => ({ currentTarget: { dataset: { id, delta } } })
const choice = id => ({ detail: { id, item: { id, name: 'untrusted' } } })
const products = [
  { id: 1, name: '甲水', spec: '大桶', salePrice: 0, imageUrl: 'a.png', availableQty: 8 },
  { id: 2, name: '乙水', spec: '小桶', salePrice: null, price: null, availableQty: 3 },
  { id: 3, name: '丙水', salePrice: false },
  { id: null, name: '编号待核实' }
]

async function run() {
  const wx = createWx()
  const app = createApp({ _loginGeneration: 1, globalData: { userInfo: { staffId: 8, role: 'STATION_MANAGER', stationId: 1 } } })
  let read = async () => ({ data: products }), quoteRead = deferred()
  const page = loadPage('miniapp-delivery/pages/station-mgmt/place-order/index.js', { app, wx, stubs: {
    'utils/request': { get: (...args) => read(...args), post: () => quoteRead.promise }
  } })
  page.setData({ stationId: 1, customer: { id: 7 }, addressId: 3 })
  page._pageOwner = JSON.stringify([1, 8, 'STATION_MANAGER', 1])
  await page.loadProducts()
  assert.equal(page.data.selectedProductId, 1)
  assert.equal(page.data.productOptions[0].priceText, '参考价 ¥0')
  assert.equal(page.data.productOptions[1].priceText, '参考价待核实')
  assert.equal(page.data.productOptions[2].priceText, '参考价待核实')
  assert.equal(page.data.productOptions[3].disabled, true)
  assert.equal(page.data.productOptions[1].image, undefined)
  assert.deepEqual(page.data.cartLines, [])

  page.onSelectProduct(choice(2))
  assert.equal(page.data.activeProduct.name, '乙水')
  assert.deepEqual(page.data.cartLines, [])
  page.onQty(dataset(2, 1)); page.onQty(dataset(2, 1))
  assert.equal(page.data.activeProduct.selectedQty, 2)
  page.onSelectProduct(choice(1)); page.onQty(dataset(1, 1))
  assert.equal(page.data.activeProduct.selectedQty, 1)
  assert.deepEqual(page.data.cartLines.map(x => [x.productId, x.quantity]), [[2, 2], [1, 1]])
  page.setData({ quote: { totalAmount: 5 } })
  page.onSelectProduct(choice(2))
  assert.equal(page.data.quote.totalAmount, 5)
  assert.equal(page.data.activeProduct.selectedQty, 2)
  page.onQty(dataset(1, -1))
  assert.equal(page.data.quote, null)
  assert.deepEqual(page.data.cartLines.map(x => [x.productId, x.quantity]), [[2, 2]])
  assert.equal(page.data.activeProduct.selectedQty, 2)
  console.log('PASS selection is independent from quantities; both controls share the same cart')

  const pending = page.onQuote()
  page.onQty(dataset(2, 1))
  quoteRead.resolve({ data: { totalAmount: 1, methods: [] } })
  await pending
  assert.equal(page.data.quote, null)
  assert.equal(page.data.activeProduct.selectedQty, 3)
  console.log('PASS quantity change invalidates an in-flight quote')

  page.setData({ unknownOrder: true })
  page.onSelectProduct(choice(1)); page.onQty(dataset(2, 1))
  assert.equal(page.data.selectedProductId, 2); assert.equal(page.data.activeProduct.selectedQty, 3)
  page.setData({ unknownOrder: false, productError: '读取失败' })
  page.onSelectProduct(choice(1)); page.onQty(dataset(2, 1))
  assert.equal(page.data.selectedProductId, 2); assert.equal(page.data.activeProduct.selectedQty, 3)
  page.setData({ productError: '' }); app.globalData.userInfo.stationId = 2
  page.onSelectProduct(choice(1)); page.onQty(dataset(2, 1))
  assert.equal(page.data.selectedProductId, 2); assert.equal(page.data.activeProduct.selectedQty, 3)
  console.log('PASS unknown request, failed catalog and changed station block edits')

  const late = deferred(); app.globalData.userInfo.stationId = 1; read = () => late.promise
  const old = page.loadProducts(); app.globalData.userInfo.stationId = 2
  late.resolve({ data: [{ id: 9, name: '旧站商品' }] }); await old
  assert.equal(page.data.products.some(p => p.id === 9), false)
  read = async () => ({ data: [{ id: 4, name: '新站商品', price: 2 }] })
  page.setData({ stationId: 2, cartLines: [], selectedProductId: '' })
  await page.loadProducts(); assert.equal(page.data.activeProduct.id, 4); assert.equal(page.data.productOptions[0].name, '新站商品')
  console.log('PASS late catalog response from previous station cannot populate the new context')

  const template = fs.readFileSync(path.join(ROOT, 'miniapp-delivery/pages/station-mgmt/place-order/index.wxml'), 'utf8')
  assert(template.includes('<product-selector') && template.includes('bind:change="onSelectProduct"'))
  assert(template.includes('data-id="{{activeProduct.id}}"') && template.includes('data-id="{{item.productId}}"'))
  console.log('PASS selector and both quantity entry points are bound')
  console.log('AQUAFLOW_SUITE_OK 5'); done()
}
run().catch(err => { console.error(err); done(); process.exitCode = 1 })
