'use strict'
const assert = require('node:assert/strict'), fs = require('node:fs'), path = require('node:path')
const { loadPage, createWx, createApp, armWatchdog, ROOT } = require('./harness')
const { fixture: uploadFixture, flush, ok } = require('./cloud-upload.mock.test')
const { parseWxml, renderElements } = require('./wxml-tree')
const { pageFixture: uploadPage, unknownFromPage, event: recoveryEvent, image: recoveryImage } = require('./page-recovery.test')
const sourceRoot = process.env.EXTREME_BASELINE_ROOT || ROOT
const EDIT = 'miniapp-delivery/pages/station-mgmt/customers/adjust/edit/index.js'
const MAP = 'miniapp-delivery/pages/station-mgmt/station-info/index.js'
const tests = [], test = (name, run) => tests.push({ name, run }), done = armWatchdog(30000)
const tick = () => new Promise(resolve => setImmediate(resolve))
const event = value => ({ detail: { value }, currentTarget: { dataset: { index: 0 } } })
function deferred() { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
function nestedData(page) {
  page.setData = (values, callback) => {
    for (const [name, value] of Object.entries(values)) {
      const parts = name.split('.'); let at = page.data
      for (const part of parts.slice(0, -1)) at = at[part] || (at[part] = {})
      at[parts.at(-1)] = value
    }
    if (callback) callback()
  }
}
function financial(options = {}) {
  const mutate = (name, source) => {
    const file = path.join(sourceRoot, 'miniapp-delivery/utils/' + name + '.js')
    return sourceRoot !== ROOT && name === 'upload-recovery-page' ? fs.readFileSync(file, 'utf8') : source
  }
  const f = uploadFixture('cloudContainer', 'miniapp-delivery', mutate)
  const financialStorage = new Map(), get = f.wx.getStorageSync.bind(f.wx), set = f.wx.setStorageSync.bind(f.wx)
  f.wx.getStorageSync = key => key.startsWith('aq_delivery_adjustmentCreate_v1:') ? financialStorage.get(key) || '' : get(key)
  f.wx.setStorageSync = (key, value) => key.startsWith('aq_delivery_adjustmentCreate_v1:') ? financialStorage.set(key, value) : set(key, value)
  f.wx.removeStorageSync = key => { if (key.startsWith('aq_delivery_adjustmentCreate_v1:')) financialStorage.delete(key) }
  Object.assign(f.app, { canAccessStationBusiness: () => true, isStationManager: () => true, routeByRole() {} })
  const modals = [], writes = [], records = new Map()
  Object.assign(f.wx, { showModal: value => modals.push(value), showLoading() {}, hideLoading() {}, navigateBack() {}, redirectTo() {} })
  const api = { getCatalog: async () => ({ code: 0, data: [] }), getCustomerAssets: async id => ({ code: 0, data: { customerId: id, adjustmentEligible: true } }),
    previewAdjustment: async () => ({ code: 0, data: { before: { deposit: 0 }, after: { deposit: 10 } } }),
    createAdjustment: async body => {
      writes.push(JSON.parse(JSON.stringify(body)))
      const accept = () => {
        if (!records.has(body.clientToken)) records.set(body.clientToken, { id: 71 + records.size, customerId: 42, status: 'PENDING' })
        return { code: 0, data: records.get(body.clientToken) }
      }
      return options.send ? options.send({ body, accept, number: writes.length }) : accept()
    } }
  function pageOf(app = f.app) {
    const customerModule = path.join(sourceRoot, 'miniapp-delivery/utils/adjustment-customer.js')
    delete require.cache[customerModule]
    const page = loadPage(path.join(sourceRoot, EDIT), { app, wx: f.wx,
      stubs: { 'api/station-mgmt': api, 'utils/upload': f.api, 'utils/upload-recovery-page': f.load('upload-recovery-page') } })
    nestedData(page)
    page.setData({ customerId: 42, customerAssets: { customerId: 42, adjustmentEligible: true }, typeIndex: 3,
      option: page.data.typeOptions[3], form: { qty: '', amount: '10', unitPrice: '', reason: '合成核对' }, clientToken: 'ADJ-original' })
    page._customerSeq = 1
    return page
  }
  const page = pageOf()
  return { ...f, page, pageOf, modals, writes, records, financialStorage }
}
async function oldCaseAndCurrentEvidence(t) {
  t.page.onChooseEvidence(); t.picks[0].success({ tempFilePaths: ['/synthetic.png'] }); await flush()
  t.read(); await flush(); t.calls[0].resolve({ statusCode: 200, data: { code: 1 } }); await flush()
  const oldCase = t.page.data.uploadRecoveryCases[0]
  t.page.onUploadNewDraft(); t.modals[0].success({ confirm: true }); await flush()
  t.page.onChooseEvidence(); t.picks[1].success({ tempFilePaths: ['/second.png'] }); await flush()
  t.read(t.stats.length - 1); await flush(); t.calls[1].resolve(ok('/synthetic-confirmed-evidence')); await flush()
  t.page.refreshUploadRecovery(); assert.equal(t.page.data.uploadRecoveryCases.length, 1)
  const tree = parseWxml(fs.readFileSync(path.join(sourceRoot, EDIT.replace('.js', '.wxml')), 'utf8'))
  t.page.onToggleUploadRecovery()
  assert.ok(renderElements(tree, t.page.data).some(value => value.attrs.bindtap === 'onUploadNewDraft'))
  return oldCase
}
async function submit(t) {
  await t.page.onPreview(); t.page.onSubmit(); const modal = t.modals.at(-1)
  assert.ok(modal); modal.success({ confirm: true }); await tick()
}

test('visible old-image recovery cannot clear evidence or rotate financial key while creation is in flight', async () => {
  const d = deferred(), t = financial({ send: ({ accept }) => { accept(); return d.promise } })
  const old = await oldCaseAndCurrentEvidence(t); await submit(t)
  assert.equal(t.writes.length, 1); assert.equal(t.page.data.submitting, true)
  const token = t.page.data.clientToken, evidence = t.page.data.evidenceList.join(','), count = t.modals.length
  t.page.onUploadNewDraft(); t.page.onUploadResumeDraft({ currentTarget: { dataset: { caseid: old.id } } })
  const opened = t.modals.length !== count
  if (opened) t.modals.at(-1).success({ confirm: true })
  const after = { token: t.page.data.clientToken, evidence: t.page.data.evidenceList.join(',') }
  d.reject(Object.assign(Error('合成超时'), { requestMayHaveOccurred: true })); await tick()
  assert.equal(opened, false); assert.deepEqual(after, { token, evidence })
  assert.equal(t.records.size, 1); assert.equal(t.page.data.adjustmentSubmitUnknown, true)
})
test('image draft confirmation opened earlier rechecks the financial lock before switching', async () => {
  const d = deferred(), t = financial({ send: ({ accept }) => { accept(); return d.promise } })
  await oldCaseAndCurrentEvidence(t); t.page.onUploadNewDraft(); const oldModal = t.modals.at(-1)
  await submit(t); const draft = t.page._uploadDraftId, token = t.page.data.clientToken
  oldModal.success({ confirm: true }); assert.equal(t.page._uploadDraftId, draft); assert.equal(t.page.data.clientToken, token)
  d.reject(Object.assign(Error('timeout'), { requestMayHaveOccurred: true })); await tick()
})
test('unknown financial intent locks form/evidence and retries exact original request under the same key', async () => {
  const t = financial({ send: ({ number, accept }) => { const response = accept(); if (number === 1) throw Object.assign(Error('timeout'), { requestMayHaveOccurred: true }); return response } })
  t.page.setData({ evidenceList: ['/synthetic-evidence'] }); await submit(t)
  assert.equal(t.page.data.adjustmentSubmitUnknown, true)
  t.page.onAmountInput(event('99')); t.page.onRemoveEvidence(event(0)); t.page.onUploadNewDraft()
  assert.equal(t.page.data.form.amount, '10'); assert.equal(t.page.data.evidenceList[0], '/synthetic-evidence')
  t.page.onSubmit(); const retry = t.modals.at(-1); assert.equal(retry.confirmText, '重试原单')
  await retry.success({ confirm: true }); await tick(); clearTimeout(t.page._returnTimer)
  assert.deepEqual(t.writes[1], t.writes[0]); assert.equal(t.records.size, 1); assert.equal(t.page._createdAdjustment, true)
})
test('unknown financial request restores across return and does not become a fresh request', async () => {
  const t = financial({ send: ({ number, accept }) => { const response = accept(); if (number === 1) throw Object.assign(Error('timeout'), { requestMayHaveOccurred: true }); return response } })
  await submit(t); t.page.onUnload()
  const next = t.pageOf(); next.onShow(); assert.equal(next.data.adjustmentSubmitUnknown, true)
  next.onSubmit(); await t.modals.at(-1).success({ confirm: true }); await tick()
  assert.deepEqual(t.writes[1], t.writes[0]); assert.equal(t.records.size, 1)
})
test('definite financial rejection unlocks editing without leaving an unknown intent', async () => {
  const t = financial({ send: () => { throw Object.assign(Error('确定拒绝'), { businessRejected: true }) } })
  await submit(t); assert.equal(!!t.page.data.adjustmentSubmitUnknown, false)
  t.page.onAmountInput(event('11')); assert.equal(t.page.data.form.amount, '11')
})
test('malformed creation receipt keeps the original intent and never announces success', async () => {
  const t = financial({ send: () => ({ code: 0, data: {} }) }); await submit(t)
  assert.equal(t.page.data.adjustmentSubmitUnknown, true); assert.equal(!!t.page._createdAdjustment, false)
})
test('storage failure before financial dispatch sends nothing', async () => {
  const t = financial(); t.wx.setStorageSync = () => { throw Error('storage unavailable') }
  await submit(t); assert.equal(t.writes.length, 0)
})
test('other actor cannot retry another actor financial original', async () => {
  const t = financial({ send: ({ accept }) => { accept(); throw Object.assign(Error('timeout'), { requestMayHaveOccurred: true }) } })
  await submit(t); t.app.globalData.userInfo.staffId++
  const next = t.pageOf(); next.onShow(); assert.equal(!!next.data.adjustmentSubmitUnknown, false); assert.equal(t.writes.length, 1)
})
test('financial in-flight memory lock survives lost storage and keeps an unknown original on write failure', async () => {
  const d = deferred(), t = financial({ send: ({ accept }) => { accept(); return d.promise } })
  await submit(t); t.financialStorage.clear(); const token = t.page.data.clientToken
  t.page.onAmountInput(event('99')); assert.equal(t.page.data.form.amount, '10')
  t.wx.setStorageSync = () => { throw Error('storage unavailable') }
  d.reject(Object.assign(Error('timeout'), { requestMayHaveOccurred: true })); await tick()
  assert.equal(t.page.data.adjustmentSubmitUnknown, true); assert.equal(t.page.data.clientToken, token)
  t.page.onSubmit(); assert.equal(t.modals.at(-1).confirmText, '重试原单')
  await t.modals.at(-1).success({ confirm: true }); await tick(); assert.equal(t.writes.length, 1)
})
test('ordinary product photo failure permits reselection without changing the financial recovery contract', async () => {
  const t = uploadPage('product'); t.p.openAdd(); t.p.uploadImage(); t.picks[0].success({ tempFilePaths: ['/synthetic.png'] }); await flush()
  t.read(); await flush(); t.calls[0].resolve({ statusCode: 200, data: { code: 1 } }); await flush()
  assert.equal(t.p.data.productImageState, 'failed'); assert.equal(t.api.uploadCases().length, 0)
  t.p.uploadImage(); assert.equal(t.picks.length, 2); assert.equal(t.modals.length, 0)
  t.picks[1].fail({ errMsg: 'chooseImage:fail cancel' }); await flush()
})
test('ordinary order image recovery still verifies by GET and never repeats upload', async () => {
  const t = uploadPage('complete'), id = await unknownFromPage(t)
  const read = t.p.onUploadRecover(recoveryEvent(id)); await flush(); t.calls[1].resolve(ok([recoveryImage])); await flush()
  t.actions[0].success({ tapIndex: 0 }); await read; await flush()
  const confirm = t.p.onUploadConfirmSelected(); await flush(); t.modals[0].success({ confirm: true }); await flush()
  t.calls[2].resolve(ok([recoveryImage])); await confirm
  assert.equal(t.p.data.uploadCaseIds.length, 0); assert.equal(t.calls.filter(value => value.options.method === 'POST').length, 1)
})

function mapFixture(options = {}) {
  const wx = createWx(), picks = [], modals = [], writes = [], reads = []
  wx.chooseLocation = value => picks.push(value); wx.showModal = value => modals.push(value)
  const app = createApp({ _loginGeneration: 1, globalData: { isLogin: true, accessToken: 'synthetic',
    userInfo: { staffId: 7, stationId: 1, role: 'STATION_MANAGER', bindStatus: 'BOUND' } } })
  const origin = { id: 1, name: '合成水站', phone: 'synthetic', address: '', status: 1 }
  const page = loadPage(path.join(sourceRoot, MAP), { wx, app, stubs: { 'api/station-mgmt': {
    getMyStation: async () => { reads.push(1); return { code: 0, data: origin } },
    updateStationCoordinates: async (...args) => { writes.push(['coordinates', ...args]); if (options.coordinates) await options.coordinates(); return { code: 0 } },
    updateStation: async (...args) => { writes.push(['station', ...args]); return { code: 0 } }
  } } })
  nestedData(page)
  page.setData({ loading: false, origin, form: { name: origin.name, phone: origin.phone, address: '' } })
  return { page, wx, app, picks, modals, writes, reads }
}
const point = { latitude: 30, longitude: 120, name: '合成地点', address: '合成地址' }
for (const change of ['staff', 'station', 'role', 'generation', 'origin', 'unload']) test('stale map callback after ' + change + ' sends no write', async () => {
  const t = mapFixture(); t.page.onPickCoordinates(); assert.equal(t.picks.length, 1)
  if (change === 'staff') t.app.globalData.userInfo.staffId++
  if (change === 'station') t.app.globalData.userInfo.stationId++
  if (change === 'role') t.app.globalData.userInfo.role = 'DELIVERY'
  if (change === 'generation') t.app._loginGeneration++
  if (change === 'origin') t.page.data.origin = { id: 2 }
  if (change === 'unload' && t.page.onUnload) t.page.onUnload()
  await t.picks[0].success(point); assert.equal(t.writes.length, 0)
})
test('normal map hide/show and token renewal retain valid selection and preserve entered form', async () => {
  const t = mapFixture(); t.page.onPickCoordinates(); if (t.page.onHide) t.page.onHide()
  t.app.globalData.accessToken = 'renewed'; t.app.globalData.userInfo = { ...t.app.globalData.userInfo }
  await t.page.onShow(); assert.equal(t.reads.length, 0)
  await t.picks[0].success(point)
  // D24 contract: coordinates save independently; the suggested address remains an explicit form draft.
  assert.equal(t.writes.length, 1); assert.equal(t.writes[0][0], 'coordinates')
  assert.equal(t.page.data.form.address, point.name + point.address)
  assert.equal(t.page.data.origin.address, ''); assert.equal(t.page.data.infoDirty, true)
})
test('duplicate native map callback writes coordinates once', async () => {
  const t = mapFixture(); t.page.setData({ 'form.address': '手填详细地址' }); t.page.onPickCoordinates()
  await t.picks[0].success(point); await t.picks[0].success(point)
  assert.equal(t.writes.length, 1)
})
test('superseded map cycle cannot save the older point', async () => {
  const t = mapFixture(); t.page.setData({ 'form.address': '手填地址' }); t.page.onPickCoordinates()
  t.picks[0].fail({ errMsg: 'cancel' }); t.page.onPickCoordinates(); assert.equal(t.picks.length, 2)
  await t.picks[0].success(point); assert.equal(t.writes.length, 0)
  await t.picks[1].success(point); assert.equal(t.writes.length, 1)
})
test('identity change while coordinate write is in flight prevents automatic address write', async () => {
  const d = deferred(), t = mapFixture({ coordinates: () => d.promise }); t.page.onPickCoordinates()
  const pending = t.picks[0].success(point); t.app.globalData.userInfo.staffId++
  d.resolve(); await pending; assert.equal(t.writes.length, 1)
})
test('old clear-coordinate confirmation cannot write under a new station', async () => {
  const t = mapFixture(); t.page.onClearCoordinates(); t.app.globalData.userInfo.stationId = 2
  await t.modals[0].success({ confirm: true }); assert.equal(t.writes.length, 0)
})
;(async () => {
  let failed = 0
  for (const entry of tests) { try { await entry.run(); console.log('PASS ' + entry.name) } catch (error) { failed++; console.error('FAIL ' + entry.name + ': ' + error.message) } }
  console.log('RESULT ' + (tests.length - failed) + '/' + tests.length)
  done(); if (failed) process.exitCode = 1; else console.log('AQUAFLOW_SUITE_OK ' + tests.length)
})().catch(error => { done(); console.error(error); process.exitCode = 1 })
