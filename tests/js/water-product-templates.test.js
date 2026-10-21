'use strict'
const assert = require('assert'), fs = require('fs'), path = require('path')
const { loadPage, createWx, createApp, armWatchdog, ROOT } = require('./harness')
const { parseWxml, renderElements } = require('./wxml-tree')
const { WATER_PRODUCT_TEMPLATES: templates, TEMPLATE_IMAGE, matchingCatalogProducts } = require('../../miniapp-delivery/utils/water-product-templates')
const done = armWatchdog(), tests = [], test = (name, run) => tests.push({ name, run })
const event = id => ({ currentTarget: { dataset: { id } } })
function fixture(list = []) {
  const wx = createWx(), calls = [], state = {}, app = createApp({ globalData: { isLogin: true,
    userInfo: { staffId: 1, stationId: 11, role: 'STATION_MANAGER' } } })
  const api = {
    getCatalog: async () => ({ code: 0, data: list }), getMySubmissions: async () => ({ code: 0, data: [] }),
    createMyProduct: async payload => { if (state.create) return state.create(payload); calls.push(['create', payload]); return { code: 0, data: 91 } },
    updateMyProduct: async (id, payload) => { calls.push(['update', id, payload]); return { code: 0 } },
    selectCatalogProduct: async (id, payload) => { calls.push(['select', id, payload]); list.find(row => row.id === id).selected = true; return { code: 0 } }
  }
  let draftSerial = 0
  const page = loadPage('miniapp-delivery/pages/station-mgmt/products/index.js', { wx, app, stubs: {
    'api/station-mgmt': api, 'utils/upload-recovery-page': { wrapUploadRecovery: page => page },
    'utils/upload': {
      uploadDraft: (intent, fresh) => { if (fresh) draftSerial++; return 'synthetic-draft-' + draftSerial },
      chooseImages: options => { state.choose = options }, isUploadSessionCurrent: () => true,
      upload: async () => { calls.push(['upload']); return { code: 0, data: '/synthetic-photo' } }
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
const validBusiness = page => page.setData({ 'editForm.price': '12.50', 'editForm.deposit': '50', 'editForm.quantity': '0' })
const catalog = (extra = {}) => ({ id: 17, name: templates[0].name, brand: '普利思', spec: '18.5L', category: 1,
  status: 1, selected: false, ownerStationId: null, displayName: templates[0].name, ...extra })

test('three evidence-backed water types carry identity data and no commercial defaults', () => {
  assert.strictEqual(templates.length, 3)
  assert.deepStrictEqual(templates.map(row => row.waterType), ['饮用纯净水', '饮用天然泉水', '饮用天然矿泉水'])
  for (const item of templates) {
    assert.strictEqual(item.brand, '普利思'); assert.strictEqual(item.spec, '18.5L'); assert.strictEqual(item.unit, '桶')
    assert(item.sourceUrl.startsWith('https://www.pulisi.com/product/'))
    for (const key of ['price', 'deposit', 'quantity', 'enabled', 'ticketPrice']) assert(!Object.hasOwn(item, key))
  }
})
for (const template of templates) test(template.id + ': choosing pre-fills an editable draft without writes or uploads', () => {
  const t = fixture(); t.page.onUseWaterTemplate(event(template.id))
  const form = t.page.data.editForm
  assert.strictEqual(form.name, template.name); assert.strictEqual(form.brand, template.brand)
  assert.strictEqual(form.spec, '18.5L'); assert.strictEqual(form.imageUrl, TEMPLATE_IMAGE)
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
  assert(payload.description.includes('示意图')); assert(!('stationId' in payload))
})
for (const [field, value] of [['price', ''], ['price', '0'], ['price', '-1'], ['price', '12junk'],
  ['deposit', ''], ['deposit', '0'], ['quantity', ''], ['quantity', '-1'], ['quantity', '1.5'], ['spec', ' ']]) {
  test('template save rejects unconfirmed or malformed ' + field + '=' + value, async () => {
    const t = fixture(); t.page.onUseWaterTemplate(event(templates[0].id)); validBusiness(t.page)
    t.page.setData({ ['editForm.' + field]: value }); await t.page.onSaveEdit()
    assert.strictEqual(t.calls.length, 0); assert(t.wx.__calls.toast.length); assert.strictEqual(t.page.data.showEdit, true)
  })
}
test('matching selected public SKU opens its existing settings without duplicate product', () => {
  const t = fixture([catalog({ selected: true })]); t.page.onUseWaterTemplate(event(templates[0].id))
  assert.strictEqual(t.page.data.setting.id, 17); assert.strictEqual(t.page.data.showSetting, true)
  assert.strictEqual(t.page.data.showEdit, false); assert.strictEqual(t.calls.length, 0)
})
test('matching public SKU is selected off shelf then opens station settings', async () => {
  const t = fixture([catalog()]); await t.page.onUseWaterTemplate(event(templates[0].id))
  assert.deepStrictEqual(t.calls, [['select', 17, { enabled: 0 }]])
  assert.strictEqual(t.page.data.showSetting, true); assert.strictEqual(t.page.data.setting.id, 17)
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
    assert.deepStrictEqual(matchingCatalogProducts(templates[0], [catalog(extra)]), [])
    const t = fixture([catalog(extra)]); t.page.onUseWaterTemplate(event(templates[0].id))
    assert.strictEqual(t.page.data.showEdit, true); assert.strictEqual(t.calls.length, 0)
  }
})
test('ambiguous public duplicates require choosing from current catalog instead of guessing or creating', () => {
  const t = fixture([catalog(), catalog({ id: 18 })]); t.page.onUseWaterTemplate(event(templates[0].id))
  assert.strictEqual(t.page.data.showEdit, false); assert.strictEqual(t.calls.length, 0)
  assert.strictEqual(t.page.data.keyword, '普利思'); assert.strictEqual(t.page.data.viewList.length, 2)
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
test('switching templates preserves the old upload draft and blocks its late picker callback', async () => {
  const t = fixture(); t.page.onUseWaterTemplate(event(templates[0].id)); const oldDraft = t.page._uploadDraftId
  await t.page.uploadImage(); const oldChoice = t.state.choose
  t.page.onUseWaterTemplate(event(templates[1].id))
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
  const rows = renderElements(tree, { ...t.page.data, uploadRecoveryCases: [] }, { includeText: true })
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
