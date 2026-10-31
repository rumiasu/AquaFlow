const assert = require('assert'), path = require('path'), fs = require('fs')
const { loadPage, createWx, createApp, armWatchdog, ROOT } = require('./harness')
const done = armWatchdog(30000), tests = [], test = (name, run) => tests.push({ name, run })
const event = (id, value) => ({ currentTarget: { dataset: { id, field: id, direction: id } }, detail: { value } })
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
const flush = async () => { for (let i = 0; i < 15; i++) await Promise.resolve() }
const route = 'miniapp-delivery/pages/station-mgmt/ticket-packages/index'
const products = [{ id: 41, name: '同名水', spec: '18.9L', brand: '清泉', imageUrl: 'synthetic.jpg' },
  { id: 42, name: '同名水', spec: '500ml', brand: '溪水' }]
function fixture(options = {}) {
  const wx = createWx(), app = createApp({ globalData: { userInfo: { staffId: 7, stationId: 1, role: 'STATION_MANAGER' } } })
  const calls = { get: [], post: [], del: [], keyboard: 0, sheets: [] }
  wx.hideKeyboard = () => { calls.keyboard++ }
  wx.showActionSheet = o => { calls.sheets.push(o) }
  const page = loadPage(route + '.js', { wx, app, stubs: { 'utils/request': {
    get: async url => { calls.get.push(url); return options.get ? options.get(url) : { data: url.includes('sale-by-station') ? products : url.includes('presets') ? { presets: [] } : [] } },
    post: async (url, body) => { calls.post.push({ url, body }); return options.post ? options.post(url, body) : { data: { unitPrice: 8.33, discountPerMille: 955 } } },
    del: async url => { calls.del.push(url); return options.del ? options.del(url) : {} }
  } } })
  page.setData({ stationId: 1, products, visibleProducts: products })
  return { page, wx, app, calls }
}
async function selected(options) { const t = fixture(options); await t.page.onPickProduct(event(41)); return t }
function edit(page, qty = '10', price = '83.27') { page.onQtyChange(event('', qty)); page.onInput(event('price', price)) }
function chooseSwitch(t, id, index) {
  t.page.requestProductSwitch(id)
  const sheet = t.calls.sheets[t.calls.sheets.length - 1]
  assert(sheet); sheet.success({ tapIndex: index }); sheet.complete()
}
test('catalog contains real products and searches brand/spec without a fake station-discount product', async () => {
  const t = fixture(); await t.page.loadProducts(); t.page.onProductSearch(event('', '溪水'))
  assert.deepStrictEqual(t.page.data.products.map(p => p.id), [41, 42])
  assert.deepStrictEqual(t.page.data.visibleProducts.map(p => p.id), [42])
  t.page.onProductSearch(event('', '没有的规格')); assert.equal(t.page.data.visibleProducts.length, 0)
})
test('opening and canceling product choice leaves the selected product, tiers and draft unchanged', async () => {
  const t = await selected(); edit(t.page); t.page.setData({ packages: [{ id: 71 }] })
  t.page.onOpenProductPicker(); t.page.onChooseProduct(event(42)); t.page.onCloseProductPicker()
  assert.equal(t.page.data.productId, '41'); assert.equal(t.page.data.form.price, '83.27')
  assert.equal(t.page.data.packages[0].id, 71); assert.equal(t.page.data.showProductPicker, false)
  t.page.onOpenProductPicker(); assert.equal(t.page.data.pendingProductId, '41'); assert.ok(t.calls.keyboard >= 3)
})
test('confirm selects the pending product only once and never performs a write', async () => {
  const t = await selected(); t.page.onOpenProductPicker(); t.page.onChooseProduct(event(42))
  assert.equal(t.page.data.productId, '41'); await t.page.onConfirmProduct()
  assert.equal(t.page.data.productId, '42'); assert.equal(t.page.data.showProductPicker, false)
  assert.equal(t.calls.post.length, 0); assert.equal(t.calls.del.length, 0)
})
test('dirty switch waits for a preserve/discard choice; cancel does not alter the draft', async () => {
  const t = await selected(); edit(t.page); t.page.onOpenProductPicker(); t.page.onChooseProduct(event(42))
  t.page.onConfirmProduct(); t.page.onConfirmProduct()
  assert.equal(t.calls.sheets.length, 1); assert.equal(t.page.data.productId, '41')
  assert.deepStrictEqual(t.calls.sheets[0].itemList, ['保留草稿并切换', '放弃草稿并切换'])
  t.calls.sheets[0].complete(); assert.equal(t.page.data.form.qty, '10'); assert.equal(t.page.data.showProductPicker, true)
})
test('preserved drafts are isolated by product and station setting and restore exactly on switching back', async () => {
  const t = await selected(); edit(t.page, '23', '192.51'); chooseSwitch(t, 42, 0); await flush()
  assert.equal(t.page.data.form.qty, ''); assert.equal(t.page.data.form.price, '')
  edit(t.page, '7', '55.43'); chooseSwitch(t, 'UNIFIED', 0); await flush()
  assert.equal(t.page.data.isUnified, true); assert.equal(t.page.data.form.qty, '')
  t.page.onInput(event('discount', '9.55')); t.page.onQtyChange(event('', '20'))
  chooseSwitch(t, 41, 0); await flush()
  assert.equal(t.page.data.form.qty, '23'); assert.equal(t.page.data.form.price, '192.51'); assert.equal(t.page.data.form.discount, '')
  chooseSwitch(t, 42, 0); await flush(); assert.equal(t.page.data.form.qty, '7'); assert.equal(t.page.data.form.price, '55.43')
})
test('discard clears only the departed draft and switching back starts blank', async () => {
  const t = await selected(); edit(t.page); chooseSwitch(t, 42, 1); await flush()
  await t.page.requestProductSwitch(41); assert.equal(t.page.data.form.qty, ''); assert.equal(t.page.data.form.price, '')
})
test('stale switch confirmation after hide cannot change current context', async () => {
  const t = await selected(); edit(t.page); t.page.requestProductSwitch(42)
  t.page.onHide(); t.calls.sheets[0].success({ tapIndex: 1 }); t.calls.sheets[0].complete()
  assert.equal(t.page.data.productId, '41'); assert.equal(t.page.data.form.qty, '10'); assert.equal(t.page.data.showProductPicker, false)
})
test('returning from history preserves unsaved input while rereading the same product', async () => {
  const t = await selected(); edit(t.page, '17', '141.59'); t.page.onHide(); t.page.onShow(); await flush()
  assert.equal(t.page.data.productId, '41'); assert.equal(t.page.data.form.qty, '17'); assert.equal(t.page.data.form.price, '141.59')
  assert.equal(t.calls.post.length, 0)
})
test('changing station or staff clears in-page drafts rather than transferring prices to another account', async () => {
  for (const change of [{ stationId: 2 }, { staffId: 8 }]) {
    const t = await selected(); t.page.onShow(); await flush(); edit(t.page)
    t.page.onHide(); Object.assign(t.app.globalData.userInfo, change); t.page.onShow(); await flush()
    assert.equal(t.page.data.productId, ''); assert.equal(t.page.data.form.qty, ''); assert.equal(t.page.data.form.price, '')
  }
})
test('switching clears old tiers immediately and a late earlier response cannot replace new tiers', async () => {
  const old = deferred(), fresh = deferred(); const t = fixture({ get: url => url.includes('productId=41') ? old.promise : fresh.promise })
  const first = t.page.onPickProduct(event(41)); t.page.setData({ packages: [{ id: 71 }] })
  const next = t.page.onPickProduct(event(42)); assert.deepStrictEqual(t.page.data.packages, [])
  fresh.resolve({ data: [{ id: 72, qty: 20, price: '166.66', unitPrice: '8.33' }] }); await next
  old.resolve({ data: [{ id: 71 }] }); await first
  assert.equal(t.page.data.productId, '42'); assert.equal(t.page.data.packages[0].id, 72)
  assert.equal(t.page.data.packages[0].price, '166.66'); assert.equal(t.page.data.packages[0].unitPrice, '8.33')
})
test('product loading cannot unlock a newer tier read still in flight', async () => {
  const product = deferred(), tier = deferred(); const t = fixture({ get: url => url.includes('sale-by-station') ? product.promise : tier.promise })
  const listing = t.page.loadProducts(); const selection = t.page.onPickProduct(event(41))
  product.resolve({ data: products }); await flush()
  assert.equal(t.page.data.loading, true); assert.equal(t.page.data.productsLoading, true)
  tier.resolve({ data: [] }); await Promise.all([listing, selection]); assert.equal(t.page.data.loading, false)
})
test('read failure and malformed results stay errors; a successful empty reread clears the error', async () => {
  let mode = 'fail'; const t = await selected({ get: async () => { if (mode === 'fail') throw Error('档位暂时读不到'); return { data: mode === 'bad' ? {} : [] } } })
  assert.equal(t.page.data.packageLoadError, '档位暂时读不到'); assert.equal(t.page.data.loading, false)
  mode = 'bad'; await t.page.loadPackages(); assert.ok(t.page.data.packageLoadError)
  mode = 'empty'; await t.page.loadPackages(); assert.equal(t.page.data.packageLoadError, ''); assert.deepStrictEqual(t.page.data.packages, [])
})
test('unavailable former product keeps its draft, clears rows and requires choosing another product', async () => {
  const t = await selected({ get: async url => ({ data: url.includes('sale-by-station') ? [products[1]] : [] }) })
  edit(t.page); t.page.setData({ packages: [{ id: 71 }] }); await t.page.loadProducts()
  assert.equal(t.page.data.form.price, '83.27'); assert.equal(t.page.data.packages.length, 0); assert.ok(t.page.data.productSelectionError)
  await t.page.onSave(); assert.equal(t.calls.post.length, 0)
  await t.page.loadPackages(); await t.page.onSave(); assert.equal(t.calls.post.length, 0)
  chooseSwitch(t, 42, 0); await flush(); assert.equal(t.page.data.productSelectionError, ''); assert.equal(t.page.data.productId, '42')
})
test('station discount can be edited independently when the product catalog failed', async () => {
  const t = fixture({ get: async url => { if (url.includes('sale-by-station')) throw Error('商品未读到'); return { data: [] } } })
  await t.page.loadProducts(); assert.ok(t.page.data.productLoadError)
  await t.page.onOpenUnified(); t.page.onQtyChange(event('', '10')); t.page.onInput(event('discount', '9.5'))
  await t.page.onSave(); assert.equal(t.calls.post.length, 1); assert.equal(t.calls.post[0].url, '/api/ticket-discounts')
})
test('late read after unload or actor change cannot overwrite visible data', async () => {
  for (const change of [t => t.page.onUnload(), t => { t.app.globalData.userInfo.staffId = 8 }]) {
    const wait = deferred(), t = fixture({ get: () => wait.promise }), read = t.page.onPickProduct(event(41))
    change(t); wait.resolve({ data: [{ id: 99 }] }); await read; assert.deepStrictEqual(t.page.data.packages, [])
  }
})
test('empty, fractional, signed, exponent and over-limit ticket quantities never write', async () => {
  for (const raw of ['', '0', '-1', '1.5', '1e2', '5001', '9999999999', ' 10']) {
    const t = await selected(); edit(t.page, raw); await t.page.onSave()
    assert.equal(t.calls.post.length, 0, raw); assert.ok(t.page.data.qtyError, raw)
    assert.equal(t.page.data.form.qty, raw, 'invalid draft must not be silently clamped')
  }
})
test('saving sends the existing product/qty/price contract, keeps cents and trusts the returned unit price', async () => {
  const t = await selected(); edit(t.page, '10', '83.27'); await t.page.onSave()
  assert.deepStrictEqual(t.calls.post, [{ url: '/api/ticket-packages', body: { productId: 41, qty: 10, price: 83.27, title: null, sort: 0, status: 1 } }])
  assert.equal(t.page.data.form.qty, ''); assert.equal(t.page.data.formDirty, false)
  assert.ok(t.wx.__calls.toast.some(o => o.title.includes('8.33')))
})
test('station discount stays on its existing endpoint and never sends a money field', async () => {
  const t = fixture(); await t.page.onOpenUnified(); t.page.onQtyChange(event('', '5000')); t.page.onInput(event('discount', '9.55'))
  await t.page.onSave(); assert.deepStrictEqual(t.calls.post[0], { url: '/api/ticket-discounts', body: { qty: 5000, discountPerMille: 955, title: null, sort: 0, status: 1 } })
})
test('presets fill only a draft with no automatic save', async () => {
  const t = fixture(); await t.page.onOpenUnified(); t.page.setData({ presets: [{ qty: 10, discountPerMille: 955, title: '建议档' }] })
  t.page.onApplyPreset(event(10)); assert.equal(t.page.data.form.qty, '10'); assert.equal(t.page.data.form.discount, '9.55')
  assert.equal(t.page.data.formDirty, true); assert.equal(t.calls.post.length, 0)
})
test('save failure preserves the original draft for correction or retry', async () => {
  const t = await selected({ post: async () => { throw Error('保存未完成') } }); edit(t.page); await t.page.onSave()
  assert.equal(t.page.data.saving, false); assert.equal(t.page.data.form.qty, '10'); assert.equal(t.page.data.form.price, '83.27')
})
test('in-flight save blocks repeated saves, scope switches and synthetic input events', async () => {
  const wait = deferred(), t = await selected({ post: () => wait.promise }); edit(t.page)
  const save = t.page.onSave(); await t.page.onSave(); t.page.onOpenProductPicker(); t.page.onOpenUnified(); edit(t.page, '30', '1.00')
  assert.equal(t.calls.post.length, 1); assert.equal(t.page.data.showProductPicker, false); assert.equal(t.page.data.productId, '41')
  assert.equal(t.page.data.form.qty, '10'); wait.resolve({ data: { unitPrice: 8.327 } }); await save
})
test('returning while a write is in flight rereads current tiers after it settles without issuing another write', async () => {
  const wait = deferred(); let written = false
  const t = await selected({ post: () => wait.promise, get: async url => ({ data: url.includes('sale-by-station') ? products
    : url.includes('presets') ? { presets: [] } : written ? [{ id: 71, qty: 10, price: '83.27', unitPrice: '8.33' }] : [] }) })
  edit(t.page); const saving = t.page.onSave(); t.page.onHide(); t.page.onShow(); assert.equal(t.page.data.saving, true)
  written = true; wait.resolve({ data: { unitPrice: 8.33 } }); await saving; await flush()
  assert.equal(t.page.data.saving, false); assert.equal(t.page.data.packages[0].id, 71); assert.equal(t.calls.post.length, 1)
})
test('status changes are single-flight, retain original row money and preserve an unsaved draft', async () => {
  const wait = deferred(), t = await selected({ post: () => wait.promise }); edit(t.page)
  t.page.setData({ packages: [{ id: 71, productId: 41, qty: 10, price: '83.27', title: '原档', sort: 2, status: 1 }] })
  const action = t.page.onToggleStatus(event(71)); await t.page.onToggleStatus(event(71)); t.page.onDelete(event(71))
  assert.equal(t.calls.post.length, 1); assert.equal(t.wx.__calls.modal.length, 0)
  assert.equal(t.calls.post[0].body.price, '83.27'); assert.equal(t.calls.post[0].body.status, 0)
  wait.resolve({}); await action; assert.equal(t.page.data.form.price, '83.27'); assert.equal(t.page.data.saving, false)
})
test('delete confirmation locks context, cancel unlocks, and old confirmation after hide performs no deletion', async () => {
  const t = await selected(); t.page.setData({ packages: [{ id: 71 }] }); t.wx.__modalAutoConfirm = false
  t.page.onDelete(event(71)); assert.equal(t.calls.del.length, 0); assert.equal(t.page.data.saving, false)
  let modal; t.wx.showModal = o => { modal = o }; t.page.onDelete(event(71)); t.page.onDelete(event(71))
  assert.equal(t.page.data.saving, true); t.page.onOpenUnified(); assert.equal(t.page.data.isUnified, false)
  t.page.onHide(); await modal.success({ confirm: true }); assert.equal(t.calls.del.length, 0); assert.equal(t.page.data.saving, false)
})
test('duplicate native delete callbacks send one request for the captured target', async () => {
  const wait = deferred(), t = await selected({ del: () => wait.promise }); t.page.setData({ packages: [{ id: 71 }] })
  let modal; t.wx.showModal = o => { modal = o }; t.page.onDelete(event(71))
  const first = modal.success({ confirm: true }); await modal.success({ confirm: true })
  assert.deepStrictEqual(t.calls.del, ['/api/ticket-packages/71'])
  wait.resolve({}); await first; assert.equal(t.page.data.saving, false)
})
test('image failure updates both sheet row and selected summary without retrying the broken image', async () => {
  const t = await selected(); t.page.onProductImageError(event('41'))
  assert.equal(t.page.data.productImageFailed, true); assert.equal(t.page.data.visibleProducts[0].imageFailed, true)
})
function stepper(extra = {}) {
  let config; global.Component = c => { config = c }
  const file = path.join(ROOT, 'miniapp-delivery/components/IntegerStepper/index.js')
  delete require.cache[file]; require(file)
  const events = [], control = { data: { ...Object.fromEntries(Object.entries(config.properties).map(([key, p]) => [key, p.value])), ...config.data, ...extra },
    setData(patch) { Object.assign(this.data, patch) }, triggerEvent(name, detail) { events.push({ name, detail }) } }
  for (const [name, fn] of Object.entries(config.methods)) control[name] = fn.bind(control)
  control.syncInput(control.data.value)
  return { control, events, setProperty(patch) { Object.assign(control.data, patch); config.observers['value, min, max, step, disabled'].call(control) } }
}
test('blank quantity stays blank until explicit input or plus; plus starts at the minimum', () => {
  const t = stepper(); assert.equal(t.control.data.inputValue, ''); assert.equal(t.events.length, 0)
  assert.equal(t.control.data.minusDisabled, true); t.control.onStep(event('add'))
  assert.equal(t.control.data.inputValue, '1'); assert.deepStrictEqual(t.events[0], { name: 'change', detail: { value: '1' } })
})
test('stepper honors both integer boundaries and never emits an out-of-range value', () => {
  const t = stepper({ value: '1' }); t.control.onStep(event('minus')); assert.equal(t.events.length, 0)
  t.setProperty({ value: '5000' }); assert.equal(t.control.data.plusDisabled, true); t.control.onStep(event('add')); assert.equal(t.events.length, 0)
  t.control.onStep(event('minus')); assert.equal(t.events[0].detail.value, '4999')
})
test('invalid manual text remains editable, is reported next to the control and does not enable stepping', () => {
  const t = stepper({ value: '10' }); t.control.onInput(event('', '5001'))
  assert.equal(t.control.data.inputValue, '5001'); assert.ok(t.control.data.inputError)
  assert.equal(t.control.data.plusDisabled, true); assert.equal(t.control.data.minusDisabled, true)
  t.control.onStep(event('minus')); assert.equal(t.events.length, 1)
  t.control.onInput(event('', '42')); assert.equal(t.control.data.inputError, ''); t.control.onStep(event('add'))
  assert.equal(t.events[2].detail.value, '43')
})
test('disabled input cannot emit changes and property updates refresh boundaries and configured step', () => {
  const t = stepper({ value: '10', disabled: true }); t.control.onInput(event('', '20')); t.control.onStep(event('add'))
  assert.equal(t.events.length, 0); assert.equal(t.control.data.inputValue, '10')
  t.setProperty({ disabled: false, value: '3', max: 5, step: 2 }); t.control.onStep(event('add'))
  assert.equal(t.events[0].detail.value, '5'); assert.equal(t.control.data.plusDisabled, true)
})
test('Done explicitly closes the keyboard and does not save or emit an extra quantity', () => {
  let hidden = 0; global.wx = { hideKeyboard: () => { hidden++ } }; const t = stepper({ value: '10' }); t.control.onConfirm()
  assert.equal(hidden, 1); assert.equal(t.events.length, 0)
})
test('native sheet, independent scroll area and safe footer are wired; exact money inputs remain', () => {
  const wxml = fs.readFileSync(path.join(ROOT, route + '.wxml'), 'utf8'), css = fs.readFileSync(path.join(ROOT, route + '.wxss'), 'utf8')
  assert.equal((wxml.match(/<page-container\b/g) || []).length, 1); assert.ok(wxml.includes('bindbeforeleave="onCloseProductPicker"'))
  assert.ok(wxml.includes('<scroll-view class="picker-list"')); assert.ok(wxml.includes('bindtap="onConfirmProduct"'))
  assert.ok(wxml.includes('type="digit" value="{{form.price}}"')); assert.ok(wxml.includes('type="digit" value="{{form.discount}}"'))
  assert.ok(wxml.includes('placeholder="如 10"')); assert.ok(!wxml.includes('当前：{{productName}}')); assert.ok(!wxml.includes('page-meta'))
  assert.ok(css.includes('env(safe-area-inset-bottom)')); assert.ok(css.includes('.picker-list { flex: 1; height: 0; min-height: 0; }'))
})
;(async () => {
  let passed = 0
  for (const t of tests) { await t.run(); passed++; console.log('PASS ' + t.name) }
  console.log('AQUAFLOW_SUITE_OK ' + passed)
})().catch(e => { console.error(e); process.exitCode = 1 }).finally(done)
