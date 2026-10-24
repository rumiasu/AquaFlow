'use strict'
const assert = require('node:assert/strict'), fs = require('node:fs'), path = require('node:path')
const { fixture: homeFixture, visible, texts } = require('./customer-home-recovery-view.test')
const { pageFixture } = require('./page-recovery.test')
const { fixture: transportFixture, flush, observe, ROOT } = require('./cloud-upload.mock.test')
const { WATER_PRODUCT_TEMPLATES } = require('../../miniapp-delivery/utils/water-product-templates')
const { uploadDiagnostic, diagnosticText } = require('../../miniapp-delivery/utils/upload-diagnostic')
const { parseWxml, renderElements, validateConditionalSiblings } = require('./wxml-tree')
const tests = [], test = (name, run) => tests.push({ name, run })
const productTree = parseWxml(fs.readFileSync(path.join(ROOT, 'miniapp-delivery/pages/station-mgmt/products/index.wxml'), 'utf8'))
const productNodes = f => renderElements(productTree, f.p.data, { includeText: true })
function product() {
  const f = pageFixture('product'), original = f.p.setData
  f.p.setData = function (patch, callback) { original.call(this, patch); if (callback) callback() }
  f.p.setData({ loading: false, waterTemplates: WATER_PRODUCT_TEMPLATES, tab: 'mine', keyword: '原筛选', category: 1, quick: 'lowStock' })
  return f
}
test('templates require an explicit creation entry and preserve the active catalog filters', () => {
  validateConditionalSiblings(productTree)
  const f = product(), scroll = []; f.wx.pageScrollTo = o => scroll.push(o)
  assert(!productNodes(f).some(n => n.className === 'water-templates'))
  assert(productNodes(f).some(n => n.attrs.bindtap === 'onToggleWaterTemplates' && n.text === '从模板新建'))
  f.p.onToggleWaterTemplates()
  assert(productNodes(f).some(n => n.className === 'water-templates'))
  assert.equal(scroll.length, 1); assert.equal(scroll[0].selector, '.water-templates')
  for (const [key, value] of Object.entries({ tab: 'mine', keyword: '原筛选', category: 1, quick: 'lowStock' })) assert.equal(f.p.data[key], value)
  f.p.onToggleWaterTemplates(); assert.equal(f.p.data.showWaterTemplates, false)
  f.p.setData({ loadError: '目录尚未加载' }); f.p.onToggleWaterTemplates(); assert.equal(f.p.data.showWaterTemplates, false)
})
test('product action targets stay with their own details and the stock filter does not show an HTML entity', () => {
  const find = (node, predicate) => predicate(node) ? node : (node.children || []).map(child => find(child, predicate)).find(Boolean)
  const card = find(productTree, n => n.attrs && n.attrs.class === 'card')
  const info = find(card, n => n.attrs && n.attrs.class === 'info')
  assert(find(info, n => n.attrs && n.attrs.class === 'action'))
  const f = product(), nodes = productNodes(f)
  assert(nodes.some(n => n.text === '库存少于20' && n.attrs['data-value'] === 'lowStock'))
  assert(!nodes.some(n => /&lt;/.test(n.text || '')))
})
for (const message of ['connect ECONNREFUSED 127.0.0.1:8080', 'request:fail timeout', '查询失败 GET /api/stations/public HTTP 503']) {
  test('station read failure stays Chinese without changing the true-empty branch: ' + message, async () => {
    const f = homeFixture({ stations: async () => { throw Error(message) } }); f.page.setData({ showStationList: true })
    await f.page.loadStationList()
    assert.match(f.page.data.stationListError, /水站列表暂时无法加载/)
    assert(!/ECONN|request:fail|GET|HTTP|\/api\//.test(texts(f.page)))
    assert.equal(f.page.data.stationListLoading, false); assert.equal(f.page.data.stationList.length, 0)
    assert(!texts(f.page).includes('暂无可用水站'))
  })
}
test('malformed station payload is a read error; a successful empty array is genuinely empty', async () => {
  for (const data of [null, {}, 'bad payload']) {
    const f = homeFixture({ stations: async () => ({ code: 0, data }) }); await f.page.loadStationList()
    assert.equal(f.page.data.stationListError, '水站列表暂时无法加载，请稍后重试')
  }
  const f = homeFixture({ stations: async () => ({ code: 0, data: [] }) }); await f.page.loadStationList()
  assert.equal(f.page.data.stationListError, ''); assert.equal(f.page.data.stationListLoading, false)
})
test('useful Chinese station explanation is retained, while old list rows are removed', async () => {
  const f = homeFixture({ stations: async () => ({ code: 1, message: '水站正在维护，请稍后重试' }) })
  f.page.setData({ stationList: [{ id: 9, name: '旧列表' }] }); await f.page.loadStationList()
  assert.equal(f.page.data.stationListError, '水站正在维护，请稍后重试'); assert.equal(f.page.data.stationList.length, 0)
})
for (const side of ['miniapp-user', 'miniapp-delivery']) test(side + ' keeps only the numeric SDK code when a cloud request fails', async () => {
  const f = transportFixture('cloudContainer', side)
  const request = f.load('request'), result = observe(request.request({ url: '/api/common/upload', method: 'POST' }))
  await flush(); f.calls[0].reject({ errCode: -501000, errMsg: 'synthetic token=secret https://signed.invalid/x?signature=secret' }); await flush()
  assert.equal(result.error.cloudErrorCode, -501000); assert.equal(result.error.transportPhase, 'call')
  assert.equal(result.error.requestMayHaveOccurred, true)
  assert(!/signed\.invalid|token=secret/.test(JSON.stringify(result.error)))
})
test('copied upload diagnostics whitelist fields and never copy paths, identities, messages or URLs', () => {
  const d = uploadDiagnostic({ statusCode: 200, backendCode: 1, uploadDiagnosticCode: 'UPLOAD_STORAGE_UNCONFIRMED',
    uploadOutcomeUnknown: true, cloudErrorCode: -100, originalMessage: 'secret https://signed.invalid', path: '/local/photo.jpg', token: 'secret' })
  const text = diagnosticText(d)
  assert.match(text, /http=200 backend=1 code=UPLOAD_STORAGE_UNCONFIRMED/)
  assert(!/secret|signed\.invalid|photo\.jpg/.test(text))
  assert(!diagnosticText({ ...d, uploadDiagnosticCode: 'secret', cloudErrorCode: 'secret', statusCode: 'secret' }).includes('secret'))
})
test('failed upload offers copy only after failure, retains diagnostics when editor resumes, then clears on success', async () => {
  const f = product(), copied = []; f.wx.setClipboardData = o => { copied.push(o.data); o.success() }; f.p.openAdd()
  const pick = async () => { f.p.uploadImage(); f.picks.at(-1).success({ tempFilePaths: ['/synthetic.png'] }); await flush(); f.read(f.stats.length - 1); await flush() }
  assert(!productNodes(f).some(n => n.attrs.bindtap === 'onCopyProductImageProblem'))
  await pick(); f.calls[0].resolve({ statusCode: 200, data: { code: 1, uploadDiagnosticCode: 'UPLOAD_STORAGE_UNCONFIRMED', uploadOutcome: 'unknown' } }); await flush()
  assert.equal(f.p.data.productImageState, 'failed'); f.p.onCopyProductImageProblem()
  assert.match(copied[0], /UPLOAD_STORAGE_UNCONFIRMED/)
  f.p.closeEdit(); f.p.onResumeProductEditor(); f.p.onCopyProductImageProblem(); assert.equal(copied[1], copied[0])
  const retry = f.p.onRetryProductImage(); await flush(); f.read(f.stats.length - 1); await flush()
  f.calls[1].resolve({ statusCode: 200, data: { code: 0, data: 'https://synthetic.invalid/photo.png', objectName: 'public/common/synthetic.png' } }); await retry; await flush()
  assert.equal(f.p.data.productImageState, 'ready'); f.p.onCopyProductImageProblem(); assert.equal(copied.length, 2)
  assert(!productNodes(f).some(n => n.attrs.bindtap === 'onCopyProductImageProblem'))
})
;(async () => {
  const timer = setTimeout(() => { console.error('PHONE_CATALOG_HOME_TIMEOUT'); process.exit(1) }, 30000); let failed = 0
  try {
    for (const t of tests) { try { await t.run(); console.log('PASS ' + t.name) } catch (error) { failed++; console.error('FAIL ' + t.name + ': ' + error.stack) } }
    if (failed) process.exitCode = 1; else console.log('AQUAFLOW_SUITE_OK ' + tests.length)
  } finally { clearTimeout(timer) }
})().catch(error => { console.error(error); process.exitCode = 1 })
