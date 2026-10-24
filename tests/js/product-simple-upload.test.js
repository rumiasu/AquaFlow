'use strict'
const assert = require('node:assert/strict'), fs = require('node:fs'), path = require('node:path')
const { pageFixture } = require('./page-recovery.test')
const { pageFixture: saveFixture, validForm } = require('./product-image-persistence.test')
const { fixture, flush, observe, ROOT } = require('./cloud-upload.mock.test')
const { parseWxml, renderElements } = require('./wxml-tree')
const tests = [], test = (name, run) => tests.push({ name, run })
const key = 'public/common/synthetic.png', preview = 'https://synthetic.invalid/product.png?q-signature=synthetic'
const receipt = (objectName = key) => ({ statusCode: 200, data: { code: 0, data: preview, objectName, uploadOutcome: 'stored' } })
const args = (f, extra = {}) => f.args({ intent: { kind: 'product-image', id: 42 }, ...extra })
async function pick(f, file = '/synthetic.png') {
  const p = f.picks.length, r = f.stats.length
  f.p.uploadImage(); assert.equal(f.picks.length, p + 1)
  f.picks[p].success({ tempFilePaths: [file] }); await flush(); f.read(r); await flush()
}
function seedOldRecord(f) {
  const store = f.load('upload-state'), owner = store.owner(f.config.getTransportConfig(), f.load('request').captureSession())
  const draftId = store.draft(owner, 'product-image', 42)
  const intent = JSON.stringify([owner, JSON.stringify(['/api/common/upload', 'product-image', 1, 42, draftId])])
  store.addCase(owner, intent, { id: store.id('upload-case'), fingerprint: 'a'.repeat(64), url: '/api/common/upload', fields: {}, kind: 'product-image', entityId: 42, draftId, createdAt: Date.now() })
  return f.storage.get(store.KEY)
}
function existing(f) { f.p.loadPkgRows = async () => {}; f.p.openEdit({ currentTarget: { dataset: { item: { id: 42, category: 1, name: '合成原商品', price: 12, imageUrl: preview, imageObjectName: key } } } }) }

test('legacy unknown product record is never shown, never blocks uploading, and is not deleted', async () => {
  const f = pageFixture('product'), raw = seedOldRecord(f); existing(f)
  await pick(f); assert.equal(f.calls.length, 1)
  f.calls[0].resolve(receipt()); await flush()
  assert.equal(f.p.data.productImageState, 'ready'); assert.equal(f.storage.get('aq_delivery_uploadRecovery_v1'), raw)
  assert.equal(f.api.uploadCases().length, 0)
  assert.equal(f.p.onUploadRecover, undefined); assert.equal(f.p.onUploadNewDraft, undefined)
})
test('corrupt legacy upload registry cannot turn a photo into a recovery task or block a new image', async () => {
  const f = pageFixture('product'); f.storage.set('aq_delivery_uploadRecovery_v1', 'synthetic-invalid-legacy-metadata')
  f.p.openAdd(); await pick(f); f.calls[0].resolve(receipt()); await flush()
  assert.equal(f.p.data.productImageState, 'ready')
  assert.equal(f.storage.get('aq_delivery_uploadRecovery_v1'), 'synthetic-invalid-legacy-metadata')
})
test('local recovery storage failure does not block uploading a replaceable product photo', async () => {
  const f = pageFixture('product'); f.p.openAdd(); f.wx.setStorageSync = () => { throw Error('synthetic storage failure') }
  await pick(f); assert.equal(f.calls.length, 1); f.calls[0].resolve(receipt()); await flush()
  assert.equal(f.p.data.editForm.imageObjectName, key)
})
for (const reason of ['timeout', 'business', 'http500', 'incomplete']) test(reason + ' is a failed photo with a single explicit manual retry', async () => {
  const f = pageFixture('product'); f.p.openAdd(); f.p.setData({ 'editForm.name': '合成未保存资料' }); await pick(f)
  if (reason === 'timeout') f.timers[0].fn()
  else if (reason === 'business') f.calls[0].resolve({ statusCode: 200, data: { code: 1, uploadOutcome: 'unknown', uploadDiagnosticCode: 'UPLOAD_STORAGE_UNCONFIRMED' } })
  else if (reason === 'http500') f.calls[0].resolve({ statusCode: 500 })
  else f.calls[0].resolve({ statusCode: 200, data: { code: 0, data: preview } })
  await flush(); assert.equal(f.p.data.productImageState, 'failed'); assert.equal(f.p.data.productImagePreviewUrl, '/synthetic.png')
  assert.equal(f.calls.length, 1, 'no automatic retransmission'); assert.equal(f.p.data.editForm.imageObjectName, '')
  const pending = f.p.onRetryProductImage(); f.p.onRetryProductImage(); await flush(); f.read(1); await flush()
  assert.equal(f.calls.length, 2); assert.equal(f.picks.length, 1); assert.equal(f.modals.length, 0)
  f.calls[1].resolve(receipt()); await pending; await flush()
  assert.equal(f.p.data.productImageState, 'ready'); assert.equal(f.p.data.editForm.name, '合成未保存资料')
  assert.equal(f.api.uploadCases().length, 0)
  assert(!f.toast.some(t => /待核实|核实图片|原草稿/.test(t.title)))
})
test('late response to timed-out image A cannot overwrite a newer selection B', async () => {
  const f = pageFixture('product'); f.p.openAdd(); await pick(f)
  f.timers[0].fn(); await flush(); await pick(f, '/second.png')
  f.calls[0].resolve(receipt('public/common/old.png')); await flush()
  assert.equal(f.p.data.productImageState, 'uploading'); assert.equal(f.p.data.editForm.imageObjectName, '')
  f.calls[1].resolve(receipt('public/common/new.png')); await flush()
  assert.equal(f.p.data.editForm.imageObjectName, 'public/common/new.png'); assert.equal(f.p.data.productImagePreviewUrl, '/second.png')
})
test('manual retry after an expired local path fails locally, retains form and allows reselection', async () => {
  const f = pageFixture('product'); f.p.openAdd(); await pick(f); f.calls[0].reject(Error('synthetic failure')); await flush()
  const pending = f.p.onRetryProductImage(); await flush(); f.stats[1].fail(); await pending; await flush()
  assert.equal(f.p.data.productImageState, 'failed'); assert.equal(f.calls.length, 1)
  await pick(f, '/second.png'); f.calls[1].resolve(receipt()); await flush()
  assert.equal(f.p.data.productImageState, 'ready')
})
test('closing during upload and reopening after failure restores a failed local preview, not success', async () => {
  const f = pageFixture('product'); existing(f); await pick(f)
  f.p.closeEdit(); f.calls[0].reject(Error('synthetic response lost')); await flush(); f.p.onResumeProductEditor()
  assert.equal(f.p.data.productImagePreviewUrl, '/synthetic.png'); assert.equal(f.p.data.productImageState, 'failed')
  assert.equal(f.p.data.editForm.imageObjectName, key)
  const pending = f.p.onRetryProductImage(); await flush(); f.read(1); await flush(); f.calls[1].resolve(receipt()); await pending
  assert.equal(f.p.data.productImageState, 'ready')
})
for (const hasOld of [false, true]) test('failed new preview is excluded when saving product, old image=' + hasOld, async () => {
  const f = saveFixture(); f.page.openAdd(); validForm(f.page)
  if (hasOld) f.page.setData({ 'editForm.imageUrl': preview, 'editForm.imageObjectName': key })
  f.state.uploadResult = { code: 1 }; f.page.uploadImage()
  await f.state.picker.success({ tempFilePaths: ['/synthetic.png'], uploadSession: {} })
  assert.equal(f.page.data.productImageState, 'failed'); await f.page.onSaveEdit()
  assert.equal(f.writes.length, 1); assert.equal(f.writes[0].imageObjectName, hasOld ? key : '')
  assert.notEqual(f.writes[0].imageUrl, '/synthetic.png')
})
test('stored key without a preview remains a verified server resource, not a failed photo', async () => {
  const f = fixture(), r = observe(f.api.upload(args(f))); await flush(); f.read(); await flush()
  f.calls[0].resolve({ statusCode: 200, data: { code: 0, data: null, objectName: key, uploadOutcome: 'stored', previewUnavailable: true } }); await flush()
  assert(r.value && !r.error); assert.equal(r.value.objectName, key); assert.equal(f.api.uploadCases().length, 0)
})
test('concurrent same-file product uploads coalesce and confirmed bytes reuse their receipt', async () => {
  const f = fixture(), a = f.api.upload(args(f)), b = f.api.upload(args(f)); assert.equal(a, b)
  await flush(); f.read(); await flush(); assert.equal(f.calls.length, 1); f.calls[0].resolve(receipt()); await a
  const r = observe(f.api.upload(args(f))); await flush(); f.read(1); await flush()
  assert.equal(r.value.objectName, key); assert.equal(f.calls.length, 1)
})
test('the photo region exposes only progress/success/failure and direct actions', () => {
  const f = pageFixture('product'); f.p.openAdd()
  const root = parseWxml(fs.readFileSync(path.join(ROOT, 'miniapp-delivery/pages/station-mgmt/products/index.wxml'), 'utf8'))
  for (const state of ['empty', 'selecting', 'uploading', 'ready', 'failed']) {
    const nodes = renderElements(root, { ...f.p.data, loading: false, productImageState: state, productImagePreviewUrl: '/synthetic.png', productImageBusy: ['selecting','uploading'].includes(state) }, { includeText: true })
    assert(!nodes.some(n => /图片待核实|上传结果待核实|原图片.*核实|独立图片草稿|继续原草稿/.test(n.text || '')))
    assert(!nodes.some(n => ['onUploadRecover','onUploadNewDraft','onUploadResumeDraft'].includes(n.attrs.bindtap)))
  }
})

async function main() {
  const result = [], timer = setTimeout(() => { console.error('PRODUCT_SIMPLE_TIMEOUT'); process.exit(1) }, 30000)
  try {
    for (const t of tests) { try { await t.run(); result.push({ name: t.name, pass: true }); console.log('PASS ' + t.name) }
      catch (error) { result.push({ name: t.name, pass: false, error: error.message }); console.log('FAIL ' + t.name + ': ' + error.message) } }
    if (process.env.REVIEW_OUTPUT) fs.writeFileSync(process.env.REVIEW_OUTPUT, JSON.stringify(result, null, 2))
    if (result.some(r => !r.pass)) process.exitCode = 1
    else console.log('AQUAFLOW_SUITE_OK ' + result.length)
  } finally { clearTimeout(timer) }
}
if (require.main === module) main().catch(error => { console.error(error); process.exitCode = 1 })
