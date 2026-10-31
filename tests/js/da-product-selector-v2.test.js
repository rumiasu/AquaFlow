const assert = require('assert'), fs = require('fs'), path = require('path'), vm = require('vm')
const { loadPage, createApp, createWx, armWatchdog, ROOT } = require('./harness')
const { parseWxml, renderElements } = require('./wxml-tree')
const done = armWatchdog(15000), tick = () => new Promise(resolve => setImmediate(resolve))
const app = () => createApp({ _loginGeneration: 1, globalData: { isLogin: true, accessToken: 'synthetic', userInfo: { staffId: 5, stationId: 1, role: 'STATION_MANAGER' } } })
async function main() {
  const wx = createWx(), a = app()
  let rows = [
    { id: 11, name: '清泉', spec: '18.9L', imageUrl: '/test-a.png', selected: true, enabled: 1, pricingConfirmed: 1, salePrice: 0, price: 0, effectivePrice: 0 },
    { id: 12, name: '山泉', spec: '桶', selected: true, enabled: 1, pricingConfirmed: 0, price: 20, effectivePrice: 20 },
    { id: 13, name: '未启用', selected: true, enabled: 0, pricingConfirmed: 1, price: 3, effectivePrice: 3 },
    { id: 14, name: '缺价格', selected: true, enabled: 1, pricingConfirmed: 1, price: null, salePrice: null, effectivePrice: 0 }
  ]
  const api = { getCatalog: async () => ({ data: rows }) }
  const page = loadPage('miniapp-delivery/pages/station-mgmt/customers/adjust/edit/index.js', { wx, app: a, stubs: { 'api/station-mgmt': api } })
  page._productSession = { app: a, generation: 1, stationId: 1, identity: JSON.stringify([true, 5, 1, 'STATION_MANAGER']), actor: JSON.stringify([5,1]) }
  await page.loadProducts()
  assert.deepStrictEqual(page.data.productList.map(p => p.id), [11,12,14])
  assert.equal(page.data.productList[0].priceText, '本站售价 ¥0.00'); assert.equal(page.data.productList[0].spec, '18.9L'); assert.equal(page.data.productList[0].image, '/test-a.png')
  assert.equal(page.data.productList[1].priceText, ''); assert.equal(page.data.productList[2].priceText, '')
  page.setData({ preview: { old: true }, previewRows: [{ label: 'old' }] }); page.onProductSelect({ detail: { id: 12 } })
  assert.equal(page.data.productIndex, 1); assert.equal(page.data.preview, null)
  page.setData({ customerId: 8, customerAssets: { adjustmentEligible: true }, option: { value: 'BARREL_GRANT', needProduct: true, needQty: true }, form: { qty: '2', amount: '', unitPrice: '', reason: '核对' } })
  const payload = page.buildPayload().payload; assert.equal(payload.productId, 12); assert.equal(payload.qty, 2); assert.equal(payload.price, undefined)
  page.setData({ preview: { preserved: true }, productSelectorExpanded: true }); page.onProductSelect({ detail: { id: 12 } }); assert.equal(page.data.preview.preserved, true); assert.equal(page.data.productSelectorExpanded, false)
  page.onProductSelect({ detail: { id: 999 } }); assert.equal(page.data.productIndex, 1)
  rows = [rows[1], rows[0], rows[3]]; await page.loadProducts(); assert.equal(page.data.productList[page.data.productIndex].id, 12)
  a._loginGeneration++; page.onProductSelect({ detail: { id: 11 } }); assert.equal(page.data.productList[page.data.productIndex].id, 12)
  page.onShow(); await tick(); assert.equal(page.data.productIndex, -1)
  console.log('PASS D20 selection uses current catalog, preserves exact zero and isolates sessions')

  let definition
  const file = path.join(ROOT, 'miniapp-delivery/components/ProductSelector/index.js')
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), { Component: value => { definition = value } })
  const events = [], component = { data: { items: [
    { id: 1, name: '缺图', priceText: '', image: '' }, { id: 2, name: '零价', priceText: '¥0.00', image: '/a' }, { id: 3, name: '停用', disabled: true }
  ], selectedId: 2, disabled: false }, setData(patch) { for (const [key,value] of Object.entries(patch)) { const m = /^viewItems\[(\d+)\]\.image$/.exec(key); if (m) this.data.viewItems[Number(m[1])].image = value; else this.data[key] = value } }, triggerEvent(name, detail) { events.push({ name, detail }) } }
  for (const [name, fn] of Object.entries(definition.methods)) component[name] = fn.bind(component)
  component.syncItems(); assert.equal(component.data.viewItems[0].priceText, '价格待确认'); assert.equal(component.data.viewItems[1].priceText, '¥0.00'); assert.equal(component.data.viewItems[1].selected, true)
  component.onSelect({ currentTarget: { dataset: { index: 2 } } }); assert.equal(events.length, 0)
  component.onSelect({ currentTarget: { dataset: { index: 1 } } }); assert.equal(events.length, 1); assert.equal(events[0].detail.id, 2)
  component.onImageError({ currentTarget: { dataset: { index: 1 } } }); assert.equal(component.data.viewItems[1].image, '')
  console.log('PASS F ProductSelector handles unknown, zero, disabled and image failure')
  const tree = parseWxml(fs.readFileSync(path.join(ROOT, 'miniapp-delivery/pages/station-mgmt/customers/adjust/edit/index.wxml'), 'utf8'))
  const visible = data => renderElements(tree, Object.assign({}, page.data, { option: { needProduct: true }, denied: false, manualProduct: false, productLoading: false, productError: '', productSelectorExpanded: false }, data))
  assert.equal(visible({ productList: [{ id: 1 }] }).filter(e => e.tag === 'product-selector').length, 1)
  const many = [1,2,3,4,5].map(id => ({ id }))
  assert.equal(visible({ productList: many }).filter(e => e.tag === 'product-selector').length, 0)
  assert.equal(visible({ productList: many, productSelectorExpanded: true }).filter(e => e.tag === 'product-selector').length, 1)
  assert.equal(visible({ productList: many, manualProduct: true }).filter(e => e.tag === 'product-selector').length, 0)
  console.log('PASS D20 compact list and explicit manual mode are distinct')
  done(); console.log('AQUAFLOW_SUITE_OK 3')
}
main().catch(error => { done(); console.error(error); process.exitCode = 1 })
