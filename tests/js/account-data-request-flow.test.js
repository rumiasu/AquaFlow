const assert = require('assert')
const { loadPage, createWx, createApp, armWatchdog } = require('./harness')
const done = armWatchdog(15000), tests = [], test = (name, run) => tests.push({ name, run }), tick = () => new Promise(resolve => setImmediate(resolve))
function setup(root, overrides = {}, enabled = true) {
  const wx = createWx(), app = createApp({ _loginGeneration: 1, globalData: { isLogin: true, customerId: 7, accessToken: 'synthetic-token', userInfo: { staffId: 7, role: 'DELIVERY', stationId: 1 } } })
  const calls = { submit: [], check: 0, mine: 0 }
  const api = { options: async () => ({ data: { intakeEnabled: enabled, closureCheckSupported: root === 'miniapp-user', notice: '仅测试提示', requestTypes: [{ value: 'CLOSURE', label: '账户注销请求' }] } }),
    mine: async () => { calls.mine++; return { data: { items: [], nextBeforeId: null } } },
    submit: async payload => { calls.submit.push({ ...payload }); return { data: { id: 10, status: 'SUBMITTED', notice: '仅登记申请' } } },
    closureCheck: async () => { calls.check++; return { data: { complete: false, clear: false, blockingItems: [] } } }, ...overrides }
  if (root === 'miniapp-delivery') delete api.closureCheck
  const page = loadPage(root + '/pages/mine/account-data/index.js', { wx, app, stubs: { 'api/account-data-requests': api } })
  return { page, app, calls, wx, api }
}
const select = t => { t.page.onType({ detail: { value: '0' } }); t.page.onNote({ detail: { value: '核实未结事项' } }) }
for (const root of ['miniapp-user', 'miniapp-delivery']) {
  test(root + ': closed intake cannot submit and only customer has a read-only check', async () => {
    const t = setup(root, {}, false); t.page.onShow(); await tick(); select(t); await t.page.onSubmit(); await t.page.onCheck()
    assert.equal(t.calls.submit.length, 0); assert.equal(t.calls.check, root === 'miniapp-user' ? 1 : 0)
    assert.equal(t.page.data.options.intakeEnabled, false)
  })
  test(root + ': unknown submission preserves original key and body; success is registration only', async () => {
    let tries = 0; const seen = [], t = setup(root, { submit: async payload => { seen.push({ ...payload }); if (++tries === 1) throw Error('network'); return { data: { id: 9, status: 'SUBMITTED', notice: '仅登记申请' } } } })
    t.page.onShow(); await tick(); select(t); await t.page.onSubmit(); assert.equal(t.page.data.pending, true)
    t.page.onNote({ detail: { value: 'different' } }); assert.equal(t.page.data.note, '核实未结事项'); await t.page.onSubmit()
    assert.deepStrictEqual(seen[0], seen[1]); assert.match(seen[0].idempotencyKey, /^[A-Za-z0-9._:-]{1,64}$/)
    assert.equal(t.page.data.pending, false); assert.equal(t.page.data.detail.request.status, 'SUBMITTED'); assert.equal(t.page.data.check, null)
  })
  test(root + ': hide/show does not leave a finished in-flight request locked', async () => {
    let resolve; const t = setup(root, { submit: () => new Promise(r => { resolve = r }) }); t.page.onShow(); await tick(); select(t)
    const request = t.page.onSubmit(); t.page.onHide(); t.page.onShow(); await tick(); assert.equal(t.page.data.submitting, true)
    resolve({ data: { id: 11 } }); await request; assert.equal(t.page.data.submitting, false); assert.equal(t.page.data.pending, true)
    assert.equal(t.page.data.detail, null) // Outcome from an earlier visible page is recovered by an explicit retry.
  })
  test(root + ': account switch cannot reveal late details or reuse another actor request', async () => {
    let resolve; const t = setup(root, { submit: () => new Promise(r => { resolve = r }) }); t.page.onShow(); await tick(); select(t)
    const request = t.page.onSubmit(); t.app._loginGeneration++; t.app.globalData.customerId = 8; t.app.globalData.accessToken = 'other-token'; t.app.globalData.userInfo.staffId = 8
    t.page.onShow(); await tick(); resolve({ data: { id: 12, note: 'old-person' } }); await request
    assert.equal(t.page.data.detail, null); assert.equal(t.page.data.pending, false); assert.equal(t.page.data.selectedIndex, -1); assert.equal(t.page._submission, null)
  })
  test(root + ': failed history load is visible and cannot claim an empty history', async () => {
    const t = setup(root, { mine: async () => { throw Error('network') } }); t.page.onShow(); await tick()
    assert.ok(t.page.data.error); assert.equal(t.page.data.loading, false); assert.deepStrictEqual(t.page.data.items, [])
    t.api.mine = async () => ({ data: { items: [{ id: 1, status: 'SUBMITTED' }], nextBeforeId: 1 } }); await t.page.load()
    assert.equal(t.page.data.error, ''); assert.equal(t.page.data.items.length, 1)
  })
  test(root + ': stale visible page cannot file an old intent with a new login', async () => {
    const t = setup(root); t.page.onShow(); await tick(); select(t)
    t.app._loginGeneration++; t.app.globalData.customerId = 8; t.app.globalData.userInfo.staffId = 8; t.app.globalData.accessToken = 'other-token'
    await t.page.onSubmit(); assert.equal(t.calls.submit.length, 0); assert.equal(t.page._submission, undefined)
  })
}
;(async () => { let failures = 0; for (const t of tests) { try { await t.run(); console.log('PASS ' + t.name) } catch (e) { failures++; console.error('FAIL ' + t.name, e) } }
  done(); if (failures) process.exitCode = 1; else { console.log('全部通过：' + tests.length + ' 项资料请求流程'); console.log('AQUAFLOW_SUITE_OK ' + tests.length) } })()
