const assert = require('node:assert/strict')
const fs = require('node:fs')
const { loadPage, createWx, createApp, armWatchdog } = require('./harness')
const { fixture: uploadFixture, flush, observe } = require('./cloud-upload.mock.test')
const { WATER_PRODUCT_TEMPLATES } = require('../../miniapp-delivery/utils/water-product-templates')
const key = 'public/common/synthetic-product.png'
const preview = 'https://synthetic.invalid/public/common/synthetic-product.png?q-signature=synthetic'
const tests = [], test = (name, run) => tests.push({ name, run })

function pageFixture() {
  const wx = createWx(), state = {}, writes = [], app = createApp({ globalData: { isLogin: true,
    userInfo: { staffId: 1, stationId: 11, role: 'STATION_MANAGER' } } })
  const page = loadPage('miniapp-delivery/pages/station-mgmt/products/index.js', { wx, app, stubs: {
    'api/station-mgmt': {
      createMyProduct: async body => { writes.push(body); return { code: 0, data: 42 } },
      updateMyProduct: async (id, body) => { writes.push(body); return { code: 0 } }
    },
    'utils/upload-recovery-page': { wrapUploadRecovery: p => p },
    'utils/upload': {
      uploadDraft: () => 'synthetic-draft', isUploadSessionCurrent: () => true,
      chooseImages: options => { state.picker = options },
      upload: async () => state.uploadResult || ({ code: 0, data: preview, objectName: key })
    }
  } })
  page.setData = function (patch, cb) {
    for (const [path, value] of Object.entries(patch)) {
      const parts = path.split('.'); let at = this.data
      for (const part of parts.slice(0, -1)) at = at[part]
      at[parts.at(-1)] = value
    }
    if (cb) cb()
  }
  page.loadPkgRows = async () => {}
  page.loadData = async () => {}
  return { page, writes, state }
}
function validForm(page) { page.setData({ 'editForm.name': '合成商品', 'editForm.price': '12', 'editForm.deposit': '50' }) }

test('new uploaded product saves the actual object key separately from preview URL', async () => {
  const t = pageFixture(); t.page.openAdd(); validForm(t.page)
  await t.page.uploadImage(); await t.state.picker.success({ tempFilePaths: ['/synthetic.png'], uploadSession: {} })
  assert.equal(t.page.data.editForm.imageUrl, preview)
  await t.page.onSaveEdit()
  assert.equal(t.writes.length, 1); assert.equal(t.writes[0].imageObjectName, key)
  assert.notEqual(t.writes[0].imageObjectName, preview)
})
test('editing existing COS product retains its stored key while saving other fields', async () => {
  const t = pageFixture(); t.page.openEdit({ currentTarget: { dataset: { item: { id: 42, category: 1,
    name: '合成已有商品', price: 12, deposit: 50, imageObjectName: key, imageUrl: preview } } } })
  t.page.setData({ 'editForm.price': '13' }); await t.page.onSaveEdit()
  assert.equal(t.writes.length, 1); assert.equal(t.writes[0].imageObjectName, key)
})
test('choosing a package preset clears the previous COS key before saving', async () => {
  const t = pageFixture(); t.page.openAdd(); validForm(t.page)
  t.page.setData({ 'editForm.imageObjectName': key, 'editForm.imageUrl': preview })
  t.page.onPickPresetImage({ currentTarget: { dataset: { path: '/assets/product/synthetic.webp' } } })
  await t.page.onSaveEdit(); assert.equal(t.writes[0].imageObjectName, '')
  assert.equal(t.writes[0].imageUrl, '/assets/product/synthetic.webp')
})
test('new and template drafts never inherit the previous image key', () => {
  const t = pageFixture(); t.page.openAdd(); t.page.setData({ 'editForm.imageObjectName': key })
  t.page.openWaterTemplateDraft(WATER_PRODUCT_TEMPLATES[0]); assert(!t.page.data.editForm.imageObjectName)
  t.page.setData({ 'editForm.imageObjectName': key }); t.page.openAdd(); assert(!t.page.data.editForm.imageObjectName)
})
test('explicit independent image draft clears both image fields without borrowing the old key', async () => {
  const { pageFixture: actualPageFixture } = require('./page-recovery.test')
  const f = actualPageFixture('product')
  f.p.setData({ 'editForm.imageObjectName': key, 'editForm.imageUrl': preview })
  f.p.onUploadNewDraft(); f.modals[0].success({ confirm: true }); await flush()
  assert.equal(f.p.data.editForm.imageObjectName, ''); assert.equal(f.p.data.editForm.imageUrl, '')
})
test('old service URL-only receipt preserves the existing photo, explains unknown result and blocks reupload', async () => {
  const { pageFixture: actualPageFixture } = require('./page-recovery.test')
  const f = actualPageFixture('product')
  f.p.setData({ isAdd: false, editId: 42, 'editForm.imageObjectName': key, 'editForm.imageUrl': preview })
  f.p.uploadImage(); f.picks[0].success({ tempFilePaths: ['/synthetic.png'] })
  await flush(); f.read(); await flush()
  f.calls[0].resolve({ statusCode: 200, data: { code: 0, data: 'https://synthetic.invalid/new-preview-only.png' } })
  await flush()
  assert.equal(f.p.data.editForm.imageObjectName, key); assert.equal(f.p.data.editForm.imageUrl, preview)
  assert.equal(f.p.data.uploading, false); assert.equal(f.p.data.uploadCaseIds.length, 1)
  assert(f.toast.some(row => row.title === '图片暂未确认，所选预览已保留'))
  assert.equal(f.p.data.productImageState, 'unknown')
  assert.equal(f.p.data.productImagePreviewUrl, '/synthetic.png')
  assert(!f.toast.some(row => row.title === '图片上传成功'))
  const pickerCount = f.picks.length
  f.p.uploadImage(); await flush()
  assert.equal(f.picks.length, pickerCount, 'unknown current image is blocked before opening another picker')
  assert.equal(f.calls.length, 1); assert.equal(f.api.uploadCases().length, 1)
  assert.equal(f.p.data.editForm.imageObjectName, key); assert.equal(f.p.data.editForm.imageUrl, preview)
})
test('product receipt with true key remains usable and releases its upload marker', async () => {
  const f = uploadFixture(), r = observe(f.api.upload(f.args())); await flush(); f.read(); await flush()
  f.calls[0].resolve({ statusCode: 200, data: { code: 0, data: preview, objectName: key } }); await flush()
  assert(r.value); assert.equal(r.value.objectName, key); assert.equal(f.api.uploadCases().length, 0)
})
for (const invalid of [undefined, '', preview, 'cloud://synthetic/product.png', '/public/common/a.png', '../a.png']) {
  test('missing or non-object product key remains unknown and blocks repeat: ' + String(invalid), async () => {
    const f = uploadFixture(), r = observe(f.api.upload(f.args())); await flush(); f.read(); await flush()
    f.calls[0].resolve({ statusCode: 200, data: { code: 0, data: preview, objectName: invalid } }); await flush()
    assert(r.error && r.error.uploadOutcomeUnknown); assert.equal(f.api.uploadCases().length, 1)
    const again = observe(f.api.upload(f.args({ filePath: '/second.png' }))); await flush()
    assert(again.error && again.error.uploadOutcomeUnknown); assert.equal(f.calls.length, 1)
  })
}
test('legacy general evidence consumers still accept URL data without object metadata', async () => {
  const f = uploadFixture(), r = observe(f.api.upload(f.args({ intent: { kind: 'customer-adjustment-evidence', id: 42 } })))
  await flush(); f.read(); await flush()
  f.calls[0].resolve({ statusCode: 200, data: { code: 0, data: preview } }); await flush()
  assert.equal(r.value.data, preview); assert.equal(f.api.uploadCases().length, 0)
})

async function main() {
  const done = armWatchdog(), results = []
  try {
    for (const t of tests) {
      try { await t.run(); results.push({ name: t.name, pass: true }); console.log('PASS ' + t.name) }
      catch (err) { results.push({ name: t.name, pass: false, error: err.message }); console.log('FAIL ' + t.name + ': ' + err.message) }
    }
    if (process.env.REVIEW_OUTPUT) fs.writeFileSync(process.env.REVIEW_OUTPUT, JSON.stringify(results, null, 2))
    console.log('PRODUCT_IMAGE_CHECK ' + results.filter(r => r.pass).length + '/' + results.length)
    if (results.some(r => !r.pass)) process.exitCode = 1
    else console.log('AQUAFLOW_SUITE_OK ' + results.length)
  } finally { done() }
}
if (require.main === module) main().catch(err => { console.error(err); process.exitCode = 1 })
module.exports = { pageFixture, validForm, key, preview }
