'use strict'
// Real page/upload/store handlers with synthetic SDK, filesystem and page-container host contract.
// This is not a WeChat native rendering or hardware Back test.
const assert = require('node:assert/strict'), fs = require('node:fs'), path = require('node:path')
const { pageFixture } = require('./page-recovery.test')
const { fixture: saveFixture } = require('./extreme-product-save.test')
const { flush, ok, ROOT } = require('./cloud-upload.mock.test')
const { parseWxml, renderElements, validateConditionalSiblings } = require('./wxml-tree')
const dir = path.join(ROOT, 'miniapp-delivery/pages/station-mgmt/products')
const source = ext => fs.readFileSync(path.join(dir, 'index.' + ext), 'utf8')
const tree = () => parseWxml(source('wxml'))
const tests = [], test = (name, run) => tests.push({ name, run })
const key = 'public/common/synthetic.png', url = 'https://synthetic.invalid/photo.png?q-signature=synthetic'
const receipt = () => ({ statusCode: 200, data: { code: 0, data: url, objectName: key, uploadOutcome: 'stored', previewUnavailable: false } })
const defer = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
function find(root, predicate, ancestors = [], found = []) {
  if (predicate(root)) found.push({ node: root, ancestors })
  for (const child of root.children || []) find(child, predicate, [...ancestors, root], found)
  return found
}
const cls = (node, name) => (node.attrs && node.attrs.class || '').split(/\s+/).includes(name)
async function dispatch(f, file = '/synthetic.png') {
  const pick = f.picks.length, read = f.stats.length
  f.p.uploadImage(); assert.equal(f.picks.length, pick + 1)
  f.picks[pick].success({ tempFilePaths: [file] }); await flush()
  if (f.stats.length > read) { f.read(read); await flush() }
}

test('three confirmed uploads and re-entry never leave a recovery record', async () => {
  const storage = new Map(), f = pageFixture('product', storage)
  for (let i = 0; i < 3; i++) {
    f.p.openAdd(); await dispatch(f); f.calls[i].resolve(receipt()); await flush()
    assert.equal(f.api.uploadCases().length, 0)
    assert.equal(f.p.data.productImageState, 'ready')
    f.p.closeEdit(); f.p.onResumeProductEditor()
    assert.equal(f.p.data.editForm.imageObjectName, key); assert.equal(f.p.data.productImageState, 'ready')
  }
  const next = pageFixture('product', storage); next.p.openAdd()
  assert.equal(next.api.uploadCases().length, 0)
})
test('repeated picker selection of the same bytes reuses confirmed receipt without a new POST', async () => {
  const f = pageFixture('product'); f.p.openAdd(); await dispatch(f)
  f.calls[0].resolve(receipt()); await flush()
  await dispatch(f)
  assert.equal(f.calls.length, 1); assert.equal(f.api.uploadCases().length, 0)
  assert.equal(f.p.data.productImageBusy, false); assert.equal(f.p.data.editForm.imageObjectName, key)
})
test('explicit pre-upload rejection leaves no record and can be retried manually', async () => {
  const f = pageFixture('product'); f.p.openAdd(); await dispatch(f)
  f.calls[0].resolve({ statusCode: 200, data: { code: 1, uploadOutcome: 'rejected', uploadDiagnosticCode: 'UPLOAD_STORAGE_UNAVAILABLE' } }); await flush()
  assert.equal(f.api.uploadCases().length, 0); assert.equal(f.p.data.productImageState, 'failed')
  await dispatch(f); assert.equal(f.calls.length, 2)
  f.calls[1].resolve(receipt()); await flush(); assert.equal(f.api.uploadCases().length, 0)
})
test('failed upload retries directly without asking the user to verify or open a new draft', async () => {
  const f = pageFixture('product'); f.p.openAdd(); await dispatch(f)
  f.calls[0].reject(Error('synthetic timeout')); await flush()
  assert.equal(f.p.data.productImageState, 'failed')
  const pending = f.p.onRetryProductImage(); await flush(); f.read(1); await flush()
  assert.equal(f.calls.length, 2); assert.equal(f.modals.length, 0); assert.equal(f.picks.length, 1)
  f.calls[1].resolve(receipt()); await pending
  assert.equal(f.p.data.productImageState, 'ready'); assert.equal(f.api.uploadCases().length, 0)
})
test('three failed attempts expose no recovery list or manual receipt workflow', async () => {
  const f = pageFixture('product')
  for (let i = 0; i < 3; i++) { f.p.openAdd(); await dispatch(f); f.calls.at(-1).reject(Error('synthetic timeout')); await flush() }
  assert.equal(f.api.uploadCases().length, 0)
  const rendered = renderElements(tree(), { ...f.p.data, loading: false }, { includeText: true })
  assert.equal(rendered.filter(n => ['onUploadResumeDraft','onUploadRecover','onUploadNewDraft'].includes(n.attrs.bindtap)).length, 0)
  assert(!rendered.some(n => /图片待核实|次上传暂未确认|原图片.*核实/.test(n.text || '')))
  assert.equal(f.storage.get('aq_delivery_uploadRecovery_v1'), undefined)
})
test('successful late receipt after close clears marker and resumes the confirmed photo', async () => {
  const f = pageFixture('product'); f.p.openAdd(); f.p.setData({ 'editForm.name': '合成未保存资料' }); await dispatch(f)
  f.p.closeEdit(); assert.equal(f.p.data.showEdit, false)
  f.p.onResumeProductEditor(); assert.equal(f.p.data.showEdit, false, 'do not resume an unfinished picker task')
  f.calls[0].resolve(receipt()); await flush()
  assert.equal(f.api.uploadCases().length, 0); assert.equal(f.api.uploadCases().length, 0)
  f.p.onResumeProductEditor()
  assert.equal(f.p.data.editForm.name, '合成未保存资料'); assert.equal(f.p.data.editForm.imageObjectName, key)
  assert.equal(f.p.data.productImageState, 'ready')
})
test('close and resume retain failed preview and form while allowing a manual retry', async () => {
  const f = pageFixture('product'); f.p.openAdd(); f.p.setData({ 'editForm.name': '合成草稿', pkgRows: [{ qty: '3', price: '30' }] })
  await dispatch(f); f.calls[0].reject(Error('synthetic timeout')); await flush()
  const draft = f.p._uploadDraftId
  f.p.closeEdit(); f.p.onResumeProductEditor()
  assert.equal(f.p._uploadDraftId, draft); assert.equal(f.p.data.editForm.name, '合成草稿')
  assert.equal(f.p.data.pkgRows[0].qty, '3'); assert.equal(f.p.data.productImagePreviewUrl, '/synthetic.png')
  assert.equal(f.p.data.productImageState, 'failed')
  const pending = f.p.onRetryProductImage(); await flush(); f.read(1); await flush()
  f.calls[1].resolve(receipt()); await pending
  assert.equal(f.calls.length, 2); assert.equal(f.p.data.productImageState, 'ready')
})
test('a changed account cannot resume a previous editor or expose its photo', async () => {
  const f = pageFixture('product'); f.p.openAdd(); f.p.closeEdit(); f.switchAccount(); f.p.onResumeProductEditor()
  assert.equal(f.p.data.showEdit, false); assert.equal(f.p.data.productEditorResume, false)
  assert.equal(f.api.uploadCases().length, 0)
})
test('late native close after an account switch cannot save the old form under the new owner', () => {
  const f = pageFixture('product'); f.p.openAdd(); f.p.setData({ 'editForm.name': '合成原账号资料' })
  f.switchAccount(); f.p.onProductEditorBeforeLeave(); f.p.onResumeProductEditor()
  assert.equal(f.p.data.showEdit, false); assert.equal(f.p.data.productEditorResume, false)
  assert.equal(f.p.data.editForm.name, undefined)
})
test('one native container owns both tasks and preserves the original two-page stack on first Back', () => {
  const root = tree(), containers = find(root, n => n.tag === 'page-container')
  assert.equal(containers.length, 1)
  const node = containers[0].node
  assert.equal(node.attrs.show, '{{showEdit || showInventory || showRecords}}'); assert(!('wx:if' in node.attrs))
  assert.equal(node.attrs['close-on-slide-down'], '{{false}}')
  const f = pageFixture('product'), stack = ['pages/home/index', 'pages/station-mgmt/products/index']
  f.p.openAdd()
  // Model the documented host rule: Back closes a visible container; a subsequent Back pops a page.
  const back = () => { if (f.p.data.showEdit) f.p[node.attrs.bindbeforeleave](); else stack.pop() }
  back(); assert.equal(f.p.data.showEdit, false); assert.equal(stack.length, 2)
  assert.equal(f.p.data.productEditorResume, true); assert.equal(f.routes.length, 0)
  back(); assert.equal(stack.length, 1)
})
test('close button and dismiss area stay outside scroll handling; touching the form cannot close it', () => {
  const root = tree(); validateConditionalSiblings(root)
  const button = find(root, n => n.tag === 'button' && n.attrs.bindtap === 'closeEdit')[0]
  assert.equal(button.node.tag, 'button'); assert.equal(button.node.attrs.bindtap, 'closeEdit')
  const body = find(root, n => cls(n, 'product-edit-body'))
  for (const { node, ancestors } of body) {
    assert.equal(node.tag, 'scroll-view')
    assert(!ancestors.some(n => n.attrs && ('catchtouchmove' in n.attrs || n.attrs.bindtap === 'closeEdit')))
  }
  const sheet = button.ancestors.find(n => cls(n, 'product-edit-modal'))
  assert.equal(sheet.attrs.catchtap, 'stopPropagation')
  assert(find(root, n => cls(n, 'product-editor-dismiss-area')).every(({ node, ancestors }) => ['closeEdit', 'closeInventory'].includes(node.attrs.bindtap) && !ancestors.includes(sheet)))
  const picker = find(root, n => cls(n, 'product-preset-mask'))[0]
  assert(picker.ancestors.some(n => n.tag === 'page-container'), 'gallery must share the native layer rather than sit behind it')
})
for (const resize of [false, true]) test('custom editor reserves 56px close strip and reachable footer, resize=' + resize, () => {
  const f = pageFixture('product'); let height = 568
  f.wx.getWindowInfo = () => ({ windowHeight: height }); f.p.openAdd()
  if (resize) height = 308
  f.p.onProductKeyboardHeightChange({ detail: { height: 260 } })
  const style = f.p.data.productEditSheetStyle, h = Number(/height:(\d+)px/.exec(style)[1]), bottom = Number(/bottom:(\d+)px/.exec(style)[1])
  assert.equal(h, 252); assert.equal(height - h - bottom, 56)
  assert.equal(bottom, resize ? 0 : 260); assert(h + bottom <= height)
})
test('successful create removes the resumable editor instead of offering completed input again', async () => {
  const f = saveFixture(); f.openAdd(); f.page.closeEdit(); f.page.onResumeProductEditor()
  await f.page.onSaveEdit()
  assert.equal(f.calls.filter(c => c[0] === 'create').length, 1)
  assert.equal(f.page.data.showEdit, false); assert.equal(f.page.data.productEditorResume, false)
  f.page.onResumeProductEditor(); assert.equal(f.page.data.showEdit, false)
})
test('native Back during save keeps the original write lock until the receipt finishes', async () => {
  const f = saveFixture(), gate = defer(); f.openAdd(); f.state.create = () => gate.promise
  const pending = f.page.onSaveEdit(); f.page.onProductEditorBeforeLeave()
  assert.equal(f.page.data.showEdit, false); assert.equal(f.page.data.saving, true)
  f.page.onResumeProductEditor(); f.page.openAdd(); await f.page.onSaveEdit()
  assert.equal(f.calls.filter(c => c[0] === 'create').length, 1)
  gate.resolve({ code: 0, data: 91 }); await pending
  assert.equal(f.packages.length, 1); assert.equal(f.packages[0].productId, 91)
  assert.equal(f.page.data.productEditorResume, false); assert.equal(f.page.data.saving, false)
})
test('native Back with unknown create retains its original recovery and never sends a second create', async () => {
  const f = saveFixture(), gate = defer(); f.openAdd(); f.state.create = () => gate.promise
  const pending = f.page.onSaveEdit(); f.page.onProductEditorBeforeLeave()
  gate.reject(Object.assign(Error('synthetic timeout'), { requestMayHaveOccurred: true })); await pending
  f.page.onResumeProductEditor(); await f.page.onSaveEdit()
  assert.equal(f.page.data.productCreationRecovery.phase, 'create-unknown')
  assert.equal(f.calls.filter(c => c[0] === 'create').length, 1)
})

async function main() {
  const results = [], timer = setTimeout(() => { console.error('PRODUCT_PHONE_TIMEOUT'); process.exit(1) }, 30000)
  try {
    for (const t of tests) { try { await t.run(); results.push({ name: t.name, pass: true }); console.log('PASS ' + t.name) }
      catch (error) { results.push({ name: t.name, pass: false, error: error.message }); console.log('FAIL ' + t.name + ': ' + error.message) } }
    if (process.env.REVIEW_OUTPUT) fs.writeFileSync(process.env.REVIEW_OUTPUT, JSON.stringify(results, null, 2))
    if (results.some(r => !r.pass)) process.exitCode = 1
    else console.log('AQUAFLOW_SUITE_OK ' + results.length)
  } finally { clearTimeout(timer) }
}
if (require.main === module) main().catch(error => { console.error(error); process.exitCode = 1 })
