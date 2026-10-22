'use strict'
const assert = require('assert')
const { loadPage, createWx, createApp, armWatchdog } = require('./harness')
const done = armWatchdog()
const app = () => createApp({ globalData: { stationId: 11, userInfo: { stationId: 11, staffId: 1, role: 'STATION_MANAGER' } } })
function staff(read, applications = async () => ({ code: 0, data: [] })) {
  return loadPage('miniapp-delivery/pages/station-mgmt/staff/index.js', { wx: createWx(), app: app(), stubs: {
    'api/delivery': { getStaffList: read }, 'api/station-mgmt': {},
    'utils/request': { get: applications, post: async () => { throw new Error('unexpected write') } }
  } })
}
function products(read) {
  return loadPage('miniapp-delivery/pages/station-mgmt/products/index.js', { wx: createWx(), app: app(), stubs: {
    'api/station-mgmt': { getCatalog: read, getMySubmissions: async () => ({ code: 0, data: [] }) },
    'utils/upload-recovery-page': { wrapUploadRecovery: page => page }
  } })
}
const tests = [], test = (name, run) => tests.push({ name, run })
test('staff request failure cannot become a successful empty staff list', async () => {
  const p = staff(async () => { throw new Error('SYNTH unavailable') })
  await p.loadData()
  assert.strictEqual(p.data.listReady, false)
  assert.strictEqual(p.data.listLoading, false)
  assert.strictEqual(p.data.listError, 'SYNTH unavailable')
})
test('staff actual empty response is ready and distinct from request failure', async () => {
  const p = staff(async () => ({ code: 0, data: [] }))
  await p.loadData()
  assert.strictEqual(p.data.listReady, true)
  assert.strictEqual(p.data.listError, '')
  assert.strictEqual(p.data.list.length, 0)
})
test('staff business failure and malformed response remain unverified', async () => {
  for (const response of [{ code: 1, data: [] }, { code: 0, data: null }]) {
    const p = staff(async () => response)
    await p.loadData()
    assert.strictEqual(p.data.listReady, false)
    assert.ok(p.data.listError)
  }
})
test('applications failure is retryable and a true empty retry becomes ready', async () => {
  let fail = true
  const p = staff(async () => ({ code: 0, data: [] }), async () => {
    if (fail) throw new Error('SYNTH applications unavailable')
    return { code: 0, data: [] }
  })
  await p.loadApplications()
  assert.strictEqual(p.data.applicationsReady, false)
  assert.ok(p.data.applicationsError)
  assert.strictEqual(p.data.applicationsLoading, false)
  fail = false
  await p.loadApplications()
  assert.strictEqual(p.data.applicationsReady, true)
  assert.strictEqual(p.data.applicationsError, '')
})
test('catalog request failure exposes retry and does not claim an empty catalog', async () => {
  const p = products(async () => { throw new Error('SYNTH catalog unavailable') })
  await p.loadData()
  assert.strictEqual(p.data.loading, false)
  assert.strictEqual(p.data.loadError, 'SYNTH catalog unavailable')
})
test('catalog business and malformed failures differ from actual empty retry', async () => {
  let response = { code: 1, data: [] }
  const p = products(async () => response)
  await p.loadData(); assert.ok(p.data.loadError)
  response = { code: 0, data: null }
  await p.loadData(); assert.ok(p.data.loadError)
  response = { code: 0, data: [] }
  await p.loadData()
  assert.strictEqual(p.data.loadError, '')
  assert.strictEqual(p.data.loading, false)
  assert.strictEqual(p.data.viewList.length, 0)
})
test('raw record expansion is presentation-only and reversible', () => {
  const p = loadPage('miniapp-delivery/pages/station-mgmt/customers/adjust/detail/index.js', { wx: createWx(), app: app() })
  assert.strictEqual(p.data.rawExpanded, false)
  p.onToggleRaw(); assert.strictEqual(p.data.rawExpanded, true)
  p.onToggleRaw(); assert.strictEqual(p.data.rawExpanded, false)
})
;(async () => {
  for (const t of tests) { await t.run(); console.log('PASS ' + t.name) }
  console.log('AQUAFLOW_SUITE_OK ' + tests.length)
})().catch(e => { console.error(e); process.exitCode = 1 }).finally(done)
