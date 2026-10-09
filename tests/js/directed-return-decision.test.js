const assert = require('assert')
const fs = require('fs')
const path = require('path')
const vm = require('vm')
const { loadPage, createWx, ROOT, armWatchdog } = require('./harness')
const { parseWxml, renderElements } = require('./wxml-tree')
const done = armWatchdog(30000)
const tests = []
const test = (name, run) => tests.push({ name, run })

function fixture(failure) {
  const calls = [], modals = [], wx = createWx()
  wx.showModal = options => modals.push(options)
  delete require.cache[require.resolve('../../miniapp-delivery/api/delivery')]
  const page = loadPage('miniapp-delivery/pages/coordination/index.js', { wx, stubs: {
    'utils/request': {
      get: async () => ({ data: [] }),
      post: async (url, body) => { calls.push({ url, body }); if (failure) throw new Error(failure) },
      put: async () => ({})
    }
  } })
  let refreshed = 0
  page.loadAllData = () => { refreshed++ }
  return { page, wx, calls, modals, refreshed: () => refreshed }
}
const event = (kind, requestId) => ({ currentTarget: { dataset: { id: 91, kind, requestId } } })

for (const [handler, action] of [['onApproveReturn', 'approve'], ['onRejectReturn', 'reject']]) {
  test(action + ' sends the first-round request from the open modal through the real API module', async () => {
    const t = fixture(), click = event('directed', 101)
    await t.page[handler](click)
    // Another device finishes round one and the list refreshes to round two before confirmation.
    click.currentTarget.dataset.requestId = 102
    t.page.data.lists.pending = [{ id: 91, transferPendingRequestId: 102 }]
    await t.modals[0].success({ confirm: true })
    assert.deepStrictEqual(t.calls, [{ url: '/api/delivery/orders/91/directed-return/' + action, body: { requestId: 101 } }])
    assert.equal(t.refreshed(), 1)
  })
  test(action + ' refreshes after a stale decision without success or an automatic retry', async () => {
    const t = fixture('指定退回申请已变化，请刷新后核实')
    await t.page[handler](event('directed', 101)); await t.modals[0].success({ confirm: true })
    assert.equal(t.calls.length, 1)
    assert.equal(t.refreshed(), 1)
    assert.ok(t.wx.__calls.toast.some(o => o.title.includes('刷新')))
    assert.ok(!t.wx.__calls.toast.some(o => o.icon === 'success'))
  })
  test(action + ' requires a request identifier before opening a directed modal', async () => {
    const t = fixture()
    await t.page[handler](event('directed', undefined))
    assert.equal(t.calls.length, 0); assert.equal(t.modals.length, 0); assert.equal(t.refreshed(), 1)
    assert.ok(t.wx.__calls.toast.some(o => o.title.includes('刷新')))
  })
  test(action + ' preserves the separate staff return contract and cancellation', async () => {
    const t = fixture()
    await t.page[handler](event('staff', undefined)); await t.modals[0].success({ confirm: false })
    assert.equal(t.calls.length, 0)
    await t.page[handler](event('staff', undefined)); await t.modals[1].success({ confirm: true })
    assert.equal(t.calls.length, 1)
    assert.ok(!t.calls[0].url.includes('directed-return'))
    assert.equal(t.calls[0].body, undefined)
  })
}
test('the rendered coordination buttons carry the inspected request identifier', () => {
  const t = fixture()
  t.page.data.isManager = true
  t.page.data.lists.pending = [{ id: 91, transferKind: 'directed', transferPending: true, transferPendingRequestId: 101 }]
  const tree = parseWxml(fs.readFileSync(path.join(ROOT, 'miniapp-delivery/pages/coordination/index.wxml'), 'utf8'))
  const buttons = renderElements(tree, t.page.data).filter(e => ['onApproveReturn', 'onRejectReturn'].includes(e.attrs.catchtap))
  assert.equal(buttons.length, 2)
  for (const button of buttons) {
    const binding = /^{{([\s\S]*)}}$/.exec(button.attrs['data-request-id'])
    assert.ok(binding, 'requestId must come from the rendered row')
    assert.equal(vm.runInNewContext(binding[1], { item: t.page.data.lists.pending[0] }), 101)
  }
})
;(async () => {
  let failed = 0
  for (const t of tests) {
    try { await t.run(); console.log('PASS ' + t.name) }
    catch (error) { failed++; console.error('FAIL ' + t.name + ': ' + error.message) }
  }
  if (failed) process.exitCode = 1
  else console.log('AQUAFLOW_SUITE_OK ' + tests.length)
  done()
})()
