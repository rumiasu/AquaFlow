'use strict'
const assert = require('assert'), fs = require('fs'), path = require('path')
const { loadPage, createWx, createApp, armWatchdog, ROOT } = require('./harness')
const { parseWxml, renderElements } = require('./wxml-tree')
const { WATER_PRODUCT_TEMPLATES: templates, TEMPLATE_IMAGE, templatesWithImages, matchingCatalogProducts } = require('../../miniapp-delivery/utils/water-product-templates')
const done = armWatchdog(), tests = [], test = (name, run) => tests.push({ name, run })
const event = id => ({ currentTarget: { dataset: { id } } })
function fixture(list = []) {
  const wx = createWx(), calls = [], state = {}, app = createApp({ globalData: { isLogin: true,
    userInfo: { staffId: 1, stationId: 11, role: 'STATION_MANAGER' } } })
  const api = {
    getCatalog: async () => ({ code: 0, data: list }), getMySubmissions: async () => ({ code: 0, data: [] }),
    getPresetImages: async () => state.getPresets ? state.getPresets() : ({ code: 0, data: state.presets || [] }),
    createMyProduct: async payload => { if (state.create) return state.create(payload); calls.push(['create', payload]); return { code: 0, data: 91 } },
    updateMyProduct: async (id, payload) => { calls.push(['update', id, payload]); return { code: 0 } },
    updateCatalogSetting: async (id, payload) => { calls.push(['setting', id, payload]); return { code: 0 } },
    inboundProducts: async (id, payload) => { calls.push(['inbound', id, payload]); return { code: 0 } },
    setCatalogStock: async (id, quantity) => { calls.push(['stock', id, quantity]); return { code: 0 } },
    selectCatalogProduct: async (id, payload) => { calls.push(['select', id, payload]); list.find(row => row.id === id).selected = true; return { code: 0 } }
  }
  let draftSerial = 0
  const page = loadPage('miniapp-delivery/pages/station-mgmt/products/index.js', { wx, app, stubs: {
    'config/api': { API: { GENERAL_UPLOAD: '/api/common/upload' }, getTransportConfig: () => ({ mode: 'cloudContainer', env: 'synthetic-env', service: 'synthetic-service' }) },
    'api/station-mgmt': api, 'api/business-rules': { recordLoss: async (id, payload) => { calls.push(['loss', id, payload]); return { data: { affectedOrders: [] } } } },
    'utils/upload-recovery-page': { wrapUploadRecovery: page => page },
    'utils/upload': {
      uploadDraft: (intent, fresh) => { if (fresh) draftSerial++; return 'synthetic-draft-' + draftSerial },
      chooseImages: options => { state.choose = options }, isUploadSessionCurrent: () => true,
      upload: async () => { calls.push(['upload']); return { code: 0, data: '/synthetic-photo', objectName: 'public/common/synthetic-photo.png' } }
    },
    'utils/request': { get: async () => ({ code: 0, data: [] }) }
  } })
  // Model native setData dotted paths; the shared lightweight harness only shallow-merges.
  page.setData = function (patch, callback) {
    for (const [key, value] of Object.entries(patch)) {
      const keys = key.split('.'); let target = this.data
      for (const part of keys.slice(0, -1)) target = target[part]
      target[keys.at(-1)] = value
    }
    if (callback) callback()
  }
  page.setData({ list }); page.loadPkgRows = async () => {}
  return { page, wx, calls, app, api, state }
}
const validBusiness = page => page.setData({ 'editForm.spec': 'SYNTH 已核实净容量', 'editForm.price': '12.50', 'editForm.deposit': '50', 'editForm.quantity': '0' })
const catalog = (extra = {}) => ({ id: 17, name: templates[0].name, brand: '普利思', spec: '18.5L', category: 1,
  status: 1, selected: false, ownerStationId: null, displayName: templates[0].name, ...extra })

test('three evidence-backed water types carry identity data and no commercial defaults', () => {
  assert.strictEqual(templates.length, 3)
  assert.deepStrictEqual(templates.map(row => row.waterType), ['饮用纯净水', '饮用天然泉水', '饮用天然矿泉水'])
  for (const item of templates) {
    assert.strictEqual(item.brand, '普利思'); assert.strictEqual(item.spec, ''); assert.strictEqual(item.unit, '桶')
    assert(item.sourceUrl.startsWith('https://www.pulisi.com/product/'))
    for (const key of ['price', 'deposit', 'quantity', 'enabled', 'ticketPrice']) assert(!Object.hasOwn(item, key))
  }
})
for (const template of templates) test(template.id + ': choosing pre-fills an editable draft without writes or uploads', () => {
  const t = fixture(); t.page.onUseWaterTemplate(event(template.id))
  const form = t.page.data.editForm
  assert.strictEqual(form.name, template.name); assert.strictEqual(form.brand, template.brand)
  assert.strictEqual(form.spec, ''); assert.strictEqual(form.imageUrl, TEMPLATE_IMAGE)
  for (const key of ['price', 'deposit', 'quantity']) assert.strictEqual(form[key], '')
  assert.strictEqual(form.enabled, false); assert.strictEqual(form.ticketEnabled, false)
  assert.strictEqual(t.calls.length, 0); assert.strictEqual(t.page.data.showEdit, true)
})
test('edited identity/spec and confirmed zero stock save through existing station API, off shelf', async () => {
  const t = fixture(); t.page.onUseWaterTemplate(event(templates[0].id)); validBusiness(t.page)
  t.page.onEditFieldInput({ currentTarget: { dataset: { field: 'spec' } }, detail: { value: '按实物核对的规格' } })
  t.page.onEditFieldInput({ currentTarget: { dataset: { field: 'name' } }, detail: { value: '本站核实名称' } })
  await t.page.onSaveEdit()
  assert.strictEqual(t.calls.length, 1, JSON.stringify({ form: t.page.data.editForm, toasts: t.wx.__calls.toast })); const payload = t.calls[0][1]
  assert.strictEqual(payload.spec, '按实物核对的规格'); assert.strictEqual(payload.name, '本站核实名称')
  assert.strictEqual(payload.imageUrl, TEMPLATE_IMAGE); assert.strictEqual(payload.quantity, 0)
  assert.strictEqual(payload.price, 12.5); assert.strictEqual(payload.deposit, 50)
  assert.strictEqual(payload.enabled, 0); assert.strictEqual(payload.ticketEnabled, 0)
  assert(payload.description.includes('以实物标签为准')); assert(!('stationId' in payload))
})
for (const [field, value] of [['price', ''], ['price', '0'], ['price', '-1'], ['price', '12junk'],
  ['deposit', ''], ['deposit', '0'], ['quantity', ''], ['quantity', '-1'], ['quantity', '1.5'], ['spec', ''], ['spec', ' ']]) {
  test('template save rejects unconfirmed or malformed ' + field + '=' + value, async () => {
    const t = fixture(); t.page.onUseWaterTemplate(event(templates[0].id)); validBusiness(t.page)
    t.page.setData({ ['editForm.' + field]: value }); await t.page.onSaveEdit()
    assert.strictEqual(t.calls.length, 0); assert(t.wx.__calls.toast.length); assert.strictEqual(t.page.data.showEdit, true)
  })
}
test('unconfirmed template capacity never guesses even a selected public SKU', () => {
  const t = fixture([catalog({ selected: true })]); t.page.onUseWaterTemplate(event(templates[0].id))
  assert.strictEqual(t.page.data.showEdit, true); assert.strictEqual(t.page.data.editForm.spec, '')
  assert.strictEqual(t.calls.length, 0)
})
test('unconfirmed template capacity creates only an editable draft without selecting public SKU', async () => {
  const t = fixture([catalog()]); await t.page.onUseWaterTemplate(event(templates[0].id))
  assert.deepStrictEqual(t.calls, []); assert.strictEqual(t.page.data.showEdit, true)
  assert.strictEqual(t.page.data.editForm.spec, ''); assert.strictEqual(t.page.data.editForm.enabled, false)
})
test('even with an exact public SKU, explicit edit opens a station draft with editable capacity', () => {
  const t = fixture([catalog()]); t.page.onEditWaterTemplate(event(templates[0].id))
  assert.strictEqual(t.calls.length, 0); assert.strictEqual(t.page.data.showEdit, true)
  t.page.onEditFieldInput({ currentTarget: { dataset: { field: 'spec' } }, detail: { value: '实物新规格' } })
  assert.strictEqual(t.page.data.editForm.spec, '实物新规格'); assert.strictEqual(t.page.data.editForm.price, '')
})
test('different capacity, water type, owner, status or category never guesses a public SKU match', () => {
  for (const extra of [{ spec: '18.9L' }, { name: templates[1].name }, { brand: '其它品牌' },
    { ownerStationId: 11 }, { status: 0 }, { category: 2 }]) {
    assert.deepStrictEqual(matchingCatalogProducts({ ...templates[0], spec: '18.5L' }, [catalog(extra)]), [])
    const t = fixture([catalog(extra)]); t.page.onUseWaterTemplate(event(templates[0].id))
    assert.strictEqual(t.page.data.showEdit, true); assert.strictEqual(t.calls.length, 0)
  }
})
test('public duplicates do not turn an unknown-capacity reference into a guessed SKU selection', () => {
  const t = fixture([catalog(), catalog({ id: 18 })]); t.page.onUseWaterTemplate(event(templates[0].id))
  assert.strictEqual(t.page.data.showEdit, true); assert.strictEqual(t.calls.length, 0)
  assert.strictEqual(t.page.data.editForm.spec, '')
})

test('brand photo preview and persistent object key survive template creation separately', async () => {
  const t = fixture(), objectName = 'catalog/synthetic-original.png', path = 'https://images.example.test/photo.png?synthetic=1'
  t.state.presets = [{ key: templates[0].imagePresetKey, objectName, path }]
  await t.page.loadWaterTemplateImages(); t.page.onUseWaterTemplate(event(templates[0].id))
  assert.strictEqual(t.page.data.editForm.imageUrl, path)
  assert.strictEqual(t.page.data.editForm.imageObjectName, objectName)
  assert.strictEqual(t.page.data.editForm.spec, '')
  validBusiness(t.page); await t.page.onSaveEdit()
  assert.strictEqual(t.calls.length, 1); assert.strictEqual(t.calls[0][1].imageObjectName, objectName)
  assert.strictEqual(t.calls[0][1].enabled, 0)
})
const verifiedPulisiPhotos = [
  ['catalog-sample-20261023-pulisi-purified-87bf702c165d.png', 'https://www.pulisi.com/data/upload/image/20201207/1607307675540169.png', 'pulisi-mineral.webp'],
  ['catalog-sample-20261023-pulisi-spring-f576447ab103.png', 'https://www.pulisi.com/data/upload/image/20200928/1601258020255537.png', 'pulisi-spring.webp'],
  ['catalog-sample-20261023-pulisi-mineral-cef12b0ef29a.png', 'https://www.pulisi.com/data/upload/image/20200928/1601257945414406.png', 'pulisi-pure.webp']
]
for (const [index, [objectName, photo, legacyFile]] of verifiedPulisiPhotos.entries()) {
  test(templates[index].id + ': permanent original renders in public catalog and template without losing the stored key', async () => {
    const t = fixture([catalog({ id: index + 4, name: templates[index].name, imageUrl: photo, imageObjectName: objectName })])
    await t.page.loadData()
    const tree = parseWxml(fs.readFileSync(path.join(ROOT, 'miniapp-delivery/pages/station-mgmt/products/index.wxml'), 'utf8'))
    const rendered = renderElements(tree, { ...t.page.data, uploadRecoveryCases: [] }, { includeText: true })
    assert.strictEqual(t.page.data.viewList[0].imageUrl, photo)
    // WXML投影保留属性绑定原文；同时钉住目录状态与可见图片节点，不当作原生图片加载。
    assert(rendered.some(row => row.tag === 'image' && row.attrs.src === '{{item.imageUrl}}' && row.attrs.mode === 'aspectFit'))
    assert(!rendered.some(row => row.text === '暂无图片'))
    t.state.presets = [{ key: templates[index].imagePresetKey, objectName, path: photo }]
    await t.page.loadWaterTemplateImages(); t.page.onUseWaterTemplate(event(templates[index].id))
    assert.strictEqual(t.page.data.editForm.imageUrl, photo)
    validBusiness(t.page); await t.page.onSaveEdit()
    assert.strictEqual(t.calls.length, 1); assert.strictEqual(t.calls[0][1].imageObjectName, objectName)
    assert.strictEqual(t.calls[0][1].imageUrl, photo)
  })
  test(templates[index].id + ': both packages retain the identical valid legacy brand asset', () => {
    const a = fs.readFileSync(path.join(ROOT, 'miniapp-user/assets/product', legacyFile))
    const b = fs.readFileSync(path.join(ROOT, 'miniapp-delivery/assets/product', legacyFile))
    assert(a.equals(b)); assert.strictEqual(a.toString('ascii', 0, 4), 'RIFF')
    assert.strictEqual(a.toString('ascii', 8, 12), 'WEBP')
  })
}
test('a same-name station custom product keeps its own missing image instead of borrowing the public template', async () => {
  const t = fixture([catalog({ ownerStationId: 11, imageUrl: null, imageObjectName: null })])
  t.state.presets = [{ key: templates[0].imagePresetKey, objectName: verifiedPulisiPhotos[0][0], path: verifiedPulisiPhotos[0][1] }]
  await t.page.loadData(); await t.page.loadWaterTemplateImages()
  assert.strictEqual(t.page.data.viewList[0].imageUrl, null)
  const tree = parseWxml(fs.readFileSync(path.join(ROOT, 'miniapp-delivery/pages/station-mgmt/products/index.wxml'), 'utf8'))
  const rendered = renderElements(tree, { ...t.page.data, uploadRecoveryCases: [] }, { includeText: true })
  assert(rendered.some(row => row.text === '暂无图片'))
  assert.strictEqual(t.calls.length, 0)
})
test('missing signed preview keeps a labelled fallback and never turns a URL into an object key', () => {
  const rows = templatesWithImages([{ key: templates[0].imagePresetKey, objectName: 'catalog/real-key.png', path: null },
    { key: templates[1].imagePresetKey, objectName: 'https://images.example.test/not-a-key', path: 'https://images.example.test/photo.png' }])
  assert.strictEqual(rows[0].imageUrl, TEMPLATE_IMAGE); assert.strictEqual(rows[0].imageObjectName, 'catalog/real-key.png')
  assert.strictEqual(rows[1].imageUrl, TEMPLATE_IMAGE); assert.strictEqual(rows[1].imageObjectName, '')
  const offlinePhoto = templatesWithImages([{ key: templates[0].imagePresetKey, objectName: 'catalog/real-key.png', path: '/assets/product/pulisi-mineral.webp' }])[0]
  assert.strictEqual(offlinePhoto.imageUrl, '/assets/product/pulisi-mineral.webp')
  assert.strictEqual(offlinePhoto.imageObjectName, 'catalog/real-key.png')
})
test('late preset response updates cards without replacing the active form image', async () => {
  const t = fixture(); let resolve
  t.state.getPresets = () => new Promise(r => { resolve = r })
  const pending = t.page.loadWaterTemplateImages(); t.page.onUseWaterTemplate(event(templates[0].id))
  t.page.setData({ 'editForm.imageUrl': '/synthetic-user-photo', 'editForm.imageObjectName': 'public/user-photo.png' })
  resolve({ code: 0, data: [{ key: templates[0].imagePresetKey, objectName: 'catalog/template.png', path: 'https://images.example.test/template.png' }] })
  await pending
  assert.strictEqual(t.page.data.editForm.imageUrl, '/synthetic-user-photo')
  assert.strictEqual(t.page.data.editForm.imageObjectName, 'public/user-photo.png')
})
test('preset picker preserves an explicit COS key instead of only a temporary preview URL', () => {
  const t = fixture(); t.page.openAdd()
  t.page.onPickPresetImage({ currentTarget: { dataset: { path: 'https://images.example.test/preview.png', objectName: 'catalog/photo.png' } } })
  assert.strictEqual(t.page.data.editForm.imageObjectName, 'catalog/photo.png')
  assert.strictEqual(t.page.data.editForm.imageUrl, 'https://images.example.test/preview.png')
})
test('unknown template and unbound context create no draft or write', () => {
  const t = fixture(); t.page.onUseWaterTemplate(event('forged-template'))
  assert.strictEqual(t.page.data.showEdit, false)
  t.app.canAccessStationBusiness = () => false; t.page.onUseWaterTemplate(event(templates[0].id))
  assert.strictEqual(t.page.data.showEdit, false); assert.strictEqual(t.calls.length, 0)
})
test('ordinary add/edit clears template identity and preserves existing editable description', () => {
  const t = fixture(); t.page.onUseWaterTemplate(event(templates[0].id)); t.page.openAdd()
  assert.strictEqual(t.page.data.editTemplateId, ''); assert.strictEqual(t.page.data.editForm.name, '')
  t.page.openEdit({ currentTarget: { dataset: { item: catalog({ ownerStationId: 11, description: '本站原说明' }) } } })
  assert.strictEqual(t.page.data.editTemplateId, ''); assert.strictEqual(t.page.data.editForm.description, '本站原说明')
})
test('repeated save while original creation is pending sends only once', async () => {
  const t = fixture(); t.page.onUseWaterTemplate(event(templates[0].id)); validBusiness(t.page)
  let resolve; t.state.create = payload => { t.calls.push(['create', payload]); return new Promise(r => { resolve = r }) }
  const first = t.page.onSaveEdit(); await t.page.onSaveEdit(); assert.strictEqual(t.calls.length, 1)
  resolve({ code: 0, data: 91 }); await first
})
for (const mode of ['add', 'edit']) for (const [field, value] of [['price', '12junk'], ['deposit', '50oops'], ['quantity', '1.5'], ['ticketPrice', '3junk']]) {
  test('ordinary ' + mode + ' rejects lossy conversion of ' + field, async () => {
    const t = fixture()
    if (mode === 'add') t.page.openAdd()
    else t.page.openEdit({ currentTarget: { dataset: { item: catalog({ ownerStationId: 11, price: 12, deposit: 50, quantity: 0 }) } } })
    t.page.setData({ 'editForm.name': 'SYNTH product', 'editForm.price': '12', 'editForm.deposit': '50', 'editForm.quantity': '0',
      'editForm.ticketEnabled': field === 'ticketPrice', ['editForm.' + field]: value })
    await t.page.onSaveEdit()
    assert.strictEqual(t.calls.length, 0); assert.strictEqual(t.page.data.showEdit, true)
  })
}
test('malformed station override cannot become a different valid price through blur and save', async () => {
  const t = fixture()
  t.page.setData({ setting: { id: 17, category: 1, salePrice: '12junk', depositPrice: '', ticketPrice: '' } })
  t.page.onPriceBlur({ currentTarget: { dataset: { field: 'salePrice' } } })
  assert.strictEqual(t.page.data.setting.salePrice, '12junk')
  await t.page.onSaveSetting(); assert.strictEqual(t.calls.length, 0)
})
for (const field of ['salePrice', 'depositPrice', 'ticketPrice']) test('station settings reject malformed ' + field + ' before writing', async () => {
  const t = fixture(); t.page.setData({ setting: { id: 17, category: 1, salePrice: '', depositPrice: '', ticketPrice: '', [field]: '12junk' } })
  await t.page.onSaveSetting(); assert.strictEqual(t.calls.length, 0)
})
test('explicit zero station prices still clear overrides without changing shelf policy', async () => {
  const t = fixture(); t.page.setData({ setting: { id: 17, category: 1, salePrice: '0', depositPrice: '0', ticketPrice: '0' } })
  await t.page.onSaveSetting()
  assert.deepStrictEqual(t.calls, [['setting', 17, { enabled: 0, ticketEnabled: 0, priorityDisplay: 0, salePrice: 0, depositPrice: 0, ticketPrice: 0 }]])
})
for (const mode of ['in', 'check', 'loss']) test('stock ' + mode + ' rejects fractional quantity instead of truncating', async () => {
  const t = fixture(); t.page.setData({ stationId: 11, stockMode: mode, stockInput: '1.5', setting: { id: 17, quantity: 10 } })
  t.page.onSubmitStock(); await Promise.resolve(); assert.strictEqual(t.calls.length, 0)
})
test('busy picker blocks template switching; closing and reopening isolates its late callback', async () => {
  const t = fixture(); t.page.onUseWaterTemplate(event(templates[0].id)); const oldDraft = t.page._uploadDraftId
  await t.page.uploadImage(); const oldChoice = t.state.choose
  t.page.onUseWaterTemplate(event(templates[1].id))
  assert.strictEqual(t.page._uploadDraftId, oldDraft)
  assert.strictEqual(t.page.data.editForm.name, templates[0].name)
  t.page.closeEdit(); t.page.onUseWaterTemplate(event(templates[1].id))
  assert.notStrictEqual(t.page._uploadDraftId, oldDraft)
  await oldChoice.success({ tempFilePaths: ['synthetic-old-photo'], uploadSession: {} })
  assert.strictEqual(t.calls.length, 0); assert.strictEqual(t.page.data.editForm.name, templates[1].name)
  assert.strictEqual(t.page.data.editForm.imageUrl, TEMPLATE_IMAGE)
})
test('current template can still explicitly replace its neutral illustration by normal upload', async () => {
  const t = fixture(); t.page.onUseWaterTemplate(event(templates[0].id)); await t.page.uploadImage()
  await t.state.choose.success({ tempFilePaths: ['synthetic-current-photo'], uploadSession: {} })
  assert.deepStrictEqual(t.calls, [['upload']]); assert.strictEqual(t.page.data.editForm.imageUrl, '/synthetic-photo')
})
test('both miniapp packages contain identical compact original neutral artwork', () => {
  const a = fs.readFileSync(path.join(ROOT, 'miniapp-user', TEMPLATE_IMAGE)), b = fs.readFileSync(path.join(ROOT, 'miniapp-delivery', TEMPLATE_IMAGE))
  assert(a.equals(b)); assert.strictEqual(a.toString('ascii', 0, 4), 'RIFF')
  assert.strictEqual(a.toString('ascii', 8, 12), 'WEBP'); assert(a.length > 1000 && a.length < 50000)
})
for (const file of ['pages/home/index.wxml', 'pages/shop/index.wxml', 'pages/order/create.wxml', 'pages/order/detail.wxml']) {
  test(file + ': generic image is honestly labelled, real product photos keep their current view', () => {
    const tree = parseWxml(fs.readFileSync(path.join(ROOT, 'miniapp-user', file), 'utf8'))
    const find = node => node.attrs && node.attrs.class === 'product-image-note' ? node : (node.children || []).map(find).find(Boolean)
    const note = find(tree); assert(note)
    const render = imageUrl => renderElements({ tag: '#root', attrs: {}, children: [note] }, { item: { imageUrl } }, { includeText: true })
    assert(render(TEMPLATE_IMAGE).some(row => row.text.includes('示意图')))
    assert.strictEqual(render('/assets/product/real-photo.webp').length, 0)
  })
}
test('manager template cards expose actual handlers and the editable field/notice', () => {
  const t = fixture(), tree = parseWxml(fs.readFileSync(path.join(ROOT, 'miniapp-delivery/pages/station-mgmt/products/index.wxml'), 'utf8'))
  const rows = renderElements(tree, { ...t.page.data, loading: false, showWaterTemplates: true, uploadRecoveryCases: [] }, { includeText: true })
  assert.strictEqual(rows.filter(row => row.attrs.bindtap === 'onUseWaterTemplate').length, 3)
  t.page.onUseWaterTemplate(event(templates[0].id))
  const edit = renderElements(tree, { ...t.page.data, uploadRecoveryCases: [] }, { includeText: true })
  assert(edit.some(row => row.attrs['data-field'] === 'spec' && row.attrs.bindinput === 'onEditFieldInput'))
  assert(edit.some(row => row.text.includes('以实物标签为准')))
})
;(async () => {
  let failed = 0
  for (const t of tests) { try { await t.run(); console.log('PASS ' + t.name) } catch (e) { failed++; console.error('FAIL ' + t.name + ': ' + e.stack) } }
  done(); if (failed) process.exitCode = 1; else console.log('AQUAFLOW_SUITE_OK ' + tests.length)
})().catch(e => { done(); console.error(e); process.exitCode = 1 })
