'use strict'
const assert = require('node:assert/strict')
const fs = require('node:fs'), path = require('node:path')
const { parseWxml, renderElements } = require('./wxml-tree')
const { pageFixture } = require('./page-recovery.test')
const { pageFixture: saveFixture, validForm, key, preview } = require('./product-image-persistence.test')
const { fixture, flush, observe, ok, ROOT } = require('./cloud-upload.mock.test')
const source = name => fs.readFileSync(path.join(ROOT, 'miniapp-delivery/pages/station-mgmt/products/index.' + name), 'utf8')
const tests = [], test = (name, run) => tests.push({ name, run })
function nodes(root, predicate) {
  const found = []
  function visit(node, ancestors) { if (predicate(node)) found.push({ node, ancestors }); for (const c of node.children || []) visit(c, [...ancestors, node]) }
  visit(root, []); return found
}
const hasClass = (n, c) => (n.attrs && n.attrs.class || '').split(/\s+/).includes(c)
const tree = () => parseWxml(source('wxml'))

test('editor and inventory sheets keep native scrollers free of touch blockers and footers outside bodies', () => {
  const root = tree(), sheets = nodes(root, n => hasClass(n, 'product-edit-modal'))
  assert.equal(root.children.find(n => n.tag === 'page-meta').attrs['page-style'], "{{showEdit || showSetting || showInventory || showRecords ? 'overflow: hidden;' : ''}}")
  assert.equal(sheets.length, 2)
  for (const { node, ancestors } of sheets) {
    assert(![...ancestors, node].some(n => n.attrs && 'catchtouchmove' in n.attrs))
    const body = node.children.find(n => n.tag === 'scroll-view')
    assert(body && 'scroll-y' in body.attrs)
    assert.equal(body.attrs.class.includes('product-edit-body'), true)
    const footer = node.children.find(n => hasClass(n, 'product-edit-footer'))
    assert(footer)
    if (hasClass(node, 'inventory-sheet')) {
      assert.equal(nodes(footer, n => n.tag === 'button').length, 2)
      const shown = renderElements({children:[footer]}, {saving:false,inboundBusy:false,stockOperationBusy:false}, {includeText:true}).filter(n=>n.tag==='button')
      assert.equal(shown.length, 2)
    } else {
      assert.equal(nodes(footer, n => n.tag === 'button').length, 3)
      for (const inactive of [false, true]) {
        const shown = renderElements({children:[footer]}, {productInactive:inactive,saving:false,inboundBusy:false,productCreationPending:false,productImageBusy:false,editForm:{},isAdd:false}, {includeText:true}).filter(n=>n.tag==='button')
        assert.equal(shown.length, inactive ? 1 : 2)
        if(inactive)assert(!shown.some(n=>n.attrs.bindtap==='onSaveEdit'))
      }
    }
    assert.equal(nodes(body, n => hasClass(n, 'product-edit-footer')).length, 0)
    const backdrop = nodes(root, n => hasClass(n, 'modal-mask') && (n.attrs['wx:if'] === node.attrs['wx:if'] || hasClass(n, 'product-editor-dismiss-area')))
    assert(backdrop.some(b => b.node.attrs.catchtouchmove === 'preventMove' && !b.ancestors.includes(node)))
  }
  const css = source('wxss')
  assert(/\.product-edit-modal\s*\{[^}]*position:fixed;[^}]*display:flex;[^}]*overflow:hidden;/s.test(css))
  assert(/\.product-edit-body\s*\{[^}]*flex:1;[^}]*min-height:0;[^}]*height:0;/s.test(css))
  assert(/\.product-edit-footer\s*\{[^}]*flex-shrink:0;[^}]*safe-area-inset-bottom/s.test(css))
})
test('product photos have no recovery list or human confirmation controls', () => {
  const root = tree()
  assert.equal(nodes(root, n => hasClass(n, 'product-upload-recovery')).length, 0)
  assert(!source('wxml').includes('onUploadRecover')); assert(!source('wxml').includes('查看待核实图片'))
  const f = pageFixture('product'); assert.equal(f.p.onUploadRecover, undefined)
})
test('empty image state shows one upload action and no contradictory change-image caption', () => {
  const f = pageFixture('product'); f.p.openAdd()
  const view = renderElements(tree(), { ...f.p.data, loading: false, showEdit: true }, { includeText: true })
  const buttons = view.filter(n => n.tag === 'button' && n.attrs.bindtap === 'uploadImage')
  assert.equal(buttons.length, 1); assert(buttons[0].text.includes('上传商品图片'))
  assert(!view.some(n => /点击换图/.test(n.text || '')))
})
test('five photo states expose upload actions without human verification', () => {
  const f = pageFixture('product'); f.p.openAdd()
  for (const state of ['empty', 'ready', 'selecting', 'uploading', 'failed']) {
    const busy = state === 'selecting' || state === 'uploading'
    const view = renderElements(tree(), { ...f.p.data, loading: false, productImageState: state, productImageBusy: busy, productImageCaseId: 'synthetic-case', productImageHint: '合成状态提示' }, { includeText: true })
    const actions = view.filter(n => n.tag === 'button' && n.className.split(' ').includes('product-image-action'))
    assert.equal(actions.length, busy ? 0 : 2, state)
    assert(view.some(n => n.className.split(' ').includes('product-image-status') && n.text === '合成状态提示'))
    assert(!view.some(n => n.tag === 'button' && n.attrs.bindtap === 'onUploadRecover'))
  }
})
for (const resized of [false, true]) test('short-screen keyboard footer geometry subtracts keyboard once, resize=' + resized, () => {
  const f = pageFixture('product'); let height = 568
  f.wx.getWindowInfo = () => ({ windowHeight: height }); f.p.captureProductEditorWindow()
  if (resized) height = 308
  f.p.onProductKeyboardHeightChange({ detail: { height: 260 } })
  const style = f.p.data.productEditSheetStyle
  const sheetHeight = Number(/height:(\d+)px/.exec(style)[1]), bottom = Number(/bottom:(\d+)px/.exec(style)[1])
  assert.equal(sheetHeight, 252); assert.equal(bottom, resized ? 0 : 260)
  assert(sheetHeight + bottom <= height)
  height = 568; f.p.onProductKeyboardHeightChange({ detail: { height: 0 } })
  assert(f.p.data.productEditSheetStyle.includes('height:512px'))
  const inputs = nodes(tree(), n => n.tag === 'input' && n.attrs['bindkeyboardheightchange'])
  // Assert the active controls, not the old count that included a separate retail-price input.
    for (const handler of ['onEditFieldInput', 'onPkgQtyInput', 'onPkgPriceInput', 'onStockInput']) {
      assert(inputs.some(n => n.node.attrs.bindinput === handler), 'missing editable control: ' + handler)
    }
    assert(!inputs.some(n => n.node.attrs['data-field'] === 'ticketPrice')); assert(inputs.every(({ node }) => node.attrs['adjust-position'] === '{{false}}' && node.attrs.bindblur === 'onProductKeyboardBlur'))
})
test('keyboard blur restores sheet height when native height-zero callback is missing', async () => {
  const f = pageFixture('product'); f.wx.getWindowInfo = () => ({ windowHeight: 568 }); f.p.openAdd()
  f.p.onProductKeyboardHeightChange({ detail: { height: 260 } })
  assert(f.p.data.productEditSheetStyle.includes('bottom:260px'))
  f.p.onProductKeyboardBlur()
  await new Promise(resolve => setTimeout(resolve, 220))
  assert(f.p.data.productEditSheetStyle.includes('height:512px'))
  assert(f.p.data.productEditSheetStyle.includes('bottom:0px'))
})
test('picker is busy immediately and cancellation releases product save guard', async () => {
  const f = pageFixture('product'); f.p.openAdd(); f.p.uploadImage()
  assert.equal(f.p.data.productImageState, 'selecting'); assert.equal(f.p.data.productImageBusy, true)
  const n = f.picks.length; f.p.uploadImage(); assert.equal(f.picks.length, n)
  f.picks[0].fail({ errMsg: 'chooseImage:fail cancel' }); await flush()
  assert.equal(f.p.data.productImageBusy, false); assert.equal(f.p.data.productImageState, 'empty')
})
test('saving while picker is open cannot submit a product', async () => {
  const f = saveFixture(); f.page.openAdd(); validForm(f.page); f.page.uploadImage()
  await f.page.onSaveEdit(); assert.equal(f.writes.length, 0)
})
test('failed photo retains local preview and old valid reference, then can be selected again', async () => {
  const f = pageFixture('product'); f.p.openAdd()
  f.p.setData({ 'editForm.imageUrl': preview, 'editForm.imageObjectName': key })
  f.p.uploadImage(); f.picks[0].success({ tempFilePaths: ['/synthetic.png'] }); await flush()
  assert.equal(f.p.data.productImagePreviewUrl, '/synthetic.png')
  f.read(); await flush(); f.calls[0].resolve({ statusCode: 200, data: { code: 1 } }); await flush()
  assert.equal(f.p.data.productImageState, 'failed'); assert.equal(f.p.data.editForm.imageObjectName, key)
  assert.equal(f.api.uploadCases().length, 0)
  f.p.uploadImage(); assert.equal(f.picks.length, 2); f.picks[1].fail({ errMsg: 'chooseImage:fail cancel' }); await flush()
  assert.equal(f.p.data.productImageState, 'failed', 'cancel does not turn an unuploaded preview into success')
})
test('late image receipt after closing and new product cannot overwrite new editor', async () => {
  const f = pageFixture('product'); f.p.openAdd(); f.p.uploadImage()
  f.picks[0].success({ tempFilePaths: ['/synthetic.png'] }); await flush(); f.read(); await flush()
  f.p.closeEdit(); f.p.openAdd(); f.p.setData({ 'editForm.name': '另一商品' })
  f.calls[0].resolve(ok(preview)); await flush()
  assert.equal(f.p.data.editForm.name, '另一商品'); assert.equal(f.p.data.editForm.imageUrl, '')
  assert.equal(f.p.data.editForm.imageObjectName, ''); assert.equal(f.p.data.productImagePreviewUrl, '')
})
test('failed photo offers direct retry and reselection without a modal', () => {
  const f = pageFixture('product'); f.p.openAdd()
  const view = renderElements(tree(), { ...f.p.data, loading: false, productImageState: 'failed', productImagePreviewUrl: '/synthetic.png' }, { includeText: true })
  assert(view.some(n => n.tag === 'button' && n.attrs.bindtap === 'onRetryProductImage'))
  assert(view.some(n => n.tag === 'button' && n.attrs.bindtap === 'uploadImage'))
  assert(!view.some(n => /待核实|继续原草稿|独立图片草稿/.test(n.text || '')))
})
test('confirmed upload without signed preview keeps local preview and saves only the actual key', async () => {
  const f = saveFixture(); f.page.openAdd(); validForm(f.page)
  f.state.uploadResult = { code: 0, data: null, objectName: key, uploadOutcome: 'stored', previewUnavailable: true }
  await f.page.uploadImage(); await f.state.picker.success({ tempFilePaths: ['/synthetic.png'], uploadSession: {} })
  assert.equal(f.page.data.productImageState, 'ready'); assert.equal(f.page.data.productImagePreviewUrl, '/synthetic.png')
  assert.equal(f.page.data.editForm.imageUrl, ''); assert.equal(f.page.data.editForm.imageObjectName, key)
  f.page.onDisplayImageError({ currentTarget: { dataset: { src: '/synthetic.png' } } })
  assert.equal(f.page.data.editForm.imageObjectName, key)
  await f.page.onSaveEdit(); assert.equal(f.writes.length, 1); assert.equal(f.writes[0].imageObjectName, key)
  assert(!JSON.stringify(f.writes[0]).includes('/synthetic.png'))
})
test('pre-send read failure is retryable, retains the chosen preview and confirmed previous image', async () => {
  const f = pageFixture('product'); f.p.openAdd(); f.p.setData({ 'editForm.imageUrl': preview, 'editForm.imageObjectName': key })
  f.p.uploadImage(); f.picks[0].success({ tempFilePaths: ['/synthetic.png'] }); await flush()
  f.stats[0].fail(); await flush()
  assert.equal(f.p.data.productImageState, 'failed'); assert.equal(f.p.data.productImagePreviewUrl, '/synthetic.png')
  assert.equal(f.p.data.editForm.imageObjectName, key); assert.equal(f.api.uploadCases().length, 0)
  f.p.uploadImage(); assert.equal(f.picks.length, 2)
})

async function sendReceipt(body, options = {}) {
  const f = fixture(), result = observe(f.api.upload(f.args({ intent: { kind: 'product-image', id: 42 }, ...options })))
  await flush(); f.read(); await flush(); f.calls[0].resolve({ statusCode: 200, data: body }); await flush()
  return { f, result }
}
test('confirmed stored product key survives unavailable preview, without an unknown marker', async () => {
  const { f, result } = await sendReceipt({ code: 0, data: null, objectName: key, uploadOutcome: 'stored', previewUnavailable: true })
  assert(result.value && !result.error); assert.equal(f.api.uploadCases().length, 0)
})
for (const body of [
  { code: 0, data: null, objectName: key },
  { code: 0, data: null, objectName: preview, uploadOutcome: 'stored', previewUnavailable: true },
  { code: 0, data: preview },
  { code: 1, uploadOutcome: 'rejected', uploadDiagnosticCode: 'NOT_A_TRUSTED_CODE' },
  { code: 1, uploadOutcome: 'unknown', uploadDiagnosticCode: 'UPLOAD_STORAGE_UNCONFIRMED' }
]) test('unverifiable product receipt fails without a recovery task: ' + JSON.stringify(body), async () => {
  const { f, result } = await sendReceipt(body)
  assert(result.error && /上传失败/.test(result.error.message)); assert.equal(f.api.uploadCases().length, 0)
})
test('explicit pre-PUT rejection releases upload marker and exposes only fixed diagnostic code', async () => {
  const { f, result } = await sendReceipt({ code: 1, message: '图片上传暂不可用', uploadOutcome: 'rejected', uploadDiagnosticCode: 'UPLOAD_STORAGE_UNAVAILABLE' })
  assert(result.error && !result.error.uploadOutcomeUnknown && result.error.uploadRejected)
  assert.equal(result.error.uploadDiagnosticCode, 'UPLOAD_STORAGE_UNAVAILABLE'); assert.equal(f.api.uploadCases().length, 0)
})
test('preview-unavailable receipt cannot satisfy legacy evidence URL contract', async () => {
  const { f, result } = await sendReceipt({ code: 0, data: null, objectName: key, uploadOutcome: 'stored', previewUnavailable: true }, { intent: { kind: 'customer-adjustment-evidence', id: 42 } })
  assert(result.error && result.error.uploadOutcomeUnknown); assert.equal(f.api.uploadCases().length, 1)
})
async function main() {
  const results = [], timer = setTimeout(() => { console.error('PRODUCT_SHEET_TIMEOUT'); process.exit(1) }, 30000)
  try {
    for (const t of tests) { try { await t.run(); results.push({ name: t.name, pass: true }); console.log('PASS ' + t.name) } catch (e) { results.push({ name: t.name, pass: false, error: e.message }); console.log('FAIL ' + t.name + ': ' + e.message) } }
    if (process.env.REVIEW_OUTPUT) fs.writeFileSync(process.env.REVIEW_OUTPUT, JSON.stringify(results, null, 2))
    if (results.some(r => !r.pass)) process.exitCode = 1
    else console.log('AQUAFLOW_SUITE_OK ' + results.length)
  } finally { clearTimeout(timer) }
}
if (require.main === module) main().catch(e => { console.error(e); process.exitCode = 1 })
