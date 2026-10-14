'use strict'
const assert = require('assert'), fs = require('fs'), path = require('path')
const { loadPage, createWx, createApp, armWatchdog, ROOT } = require('./harness')
const { parseWxml, renderElements } = require('./wxml-tree')
const tests = [], test = (name, run) => tests.push({ name, run }), done = armWatchdog()
const pending = () => { let resolve; const promise = new Promise(r => { resolve = r }); return { promise, resolve } }
const order = extra => ({ id: 55, orderNo: '合成原订单55', status: 2, needCollect: false, totalAmount: 40,
  items: [{ id: 501, quantity: 2, productName: '合成水', barrelItem: true, suggestedReturnQty: 2 }], ...extra })
function fixture(read = async () => ({ data: order() })) {
  const calls = [], page = loadPage('miniapp-delivery/pages/order/complete.js', { wx: createWx(), app: createApp(), stubs: {
    'api/delivery': { getOrderDetail: read, completeOrder: async (id, body) => { calls.push({ id, body }); return { code: 0, data: {} } } },
    'utils/request': { get: async () => ({ data: {} }) }
  } })
  page.setData({ orderId: 55 })
  return { page, calls }
}
function label(t) {
  const tree = parseWxml(fs.readFileSync(path.join(ROOT, 'miniapp-delivery/pages/order/complete.wxml'), 'utf8'))
  const find = n => n.attrs && n.attrs.class === 'head-card' ? n : (n.children || []).map(find).find(Boolean)
  return renderElements({ tag: '#root', attrs: {}, children: [find(tree)] }, t.page.data, { includeText: true })
    .filter(n => n.tag === 'text' && n.text.startsWith('原订单：')).map(n => n.text)
}
test('normal idle confirmation shows the actual loaded order number before submission', async () => {
  const t = fixture(); await t.page.loadOrder(55)
  assert.strictEqual(t.page.data.resultState, 'idle'); assert.deepStrictEqual(label(t), ['原订单：合成原订单55'])
  assert.strictEqual(t.calls.length, 0)
})
test('absent order number uses the original response ID, with an honest fallback if both absent', async () => {
  for (const extra of [{ orderNo: null }, { orderNo: '' }, { orderNo: undefined }]) {
    const t = fixture(async () => ({ data: order(extra) })); await t.page.loadOrder(55)
    assert.deepStrictEqual(label(t), ['原订单：#55'])
  }
  const t = fixture(async () => ({ data: order({ orderNo: null, id: null }) })); await t.page.loadOrder(55)
  assert.deepStrictEqual(label(t), ['原订单：编号未加载'])
})
test('loading and failed reread never show the retained old order number as current', async () => {
  let fail = false; const slow = pending()
  const t = fixture(() => fail ? slow.promise : Promise.resolve({ data: order() }))
  await t.page.loadOrder(55); fail = true; const reread = t.page.loadOrder(55)
  assert.deepStrictEqual(label(t), [])
  slow.resolve({ data: null }); await reread; assert.deepStrictEqual(label(t), [])
})
test('latest order read wins; late previous order cannot replace its visible number', async () => {
  const a = pending(), t = fixture(id => id === 55 ? a.promise : Promise.resolve({ data: order({ id: 66, orderNo: '合成原订单66' }) }))
  const old = t.page.loadOrder(55); t.page.setData({ orderId: 66 }); await t.page.loadOrder(66)
  a.resolve({ data: order() }); await old
  assert.deepStrictEqual(label(t), ['原订单：合成原订单66'])
})
for (const state of ['submitting', 'failed', 'unknown', 'success']) {
  test(state + ' retains the same original order identity when loaded', async () => {
    const t = fixture(); await t.page.loadOrder(55); t.page.setData({ resultState: state })
    assert.deepStrictEqual(label(t), ['原订单：合成原订单55'])
  })
}
test('display does not alter amounts, barrel defaults or the original completion payload', async () => {
  const t = fixture(); await t.page.loadOrder(55)
  assert.strictEqual(t.page.data.orderInfo.totalAmount, 40); assert.strictEqual(t.page.data.items[0].actual, 2)
  const timer = global.setTimeout
  global.setTimeout = (fn, delay, ...args) => delay === 1500 ? 0 : timer(fn, delay, ...args)
  try { await t.page._doSubmit() } finally { global.setTimeout = timer }
  assert.deepStrictEqual(t.calls, [{ id: 55, body: { itemReturns: [{ orderItemId: 501, productName: '合成水', expected: 2,
    actual: 2, reasons: [] }], note: '', collected: true, reportedFloor: null } }])
})
;(async () => {
  let failed = 0
  for (const t of tests) { try { await t.run(); console.log('PASS ' + t.name) } catch (e) { failed++; console.error('FAIL ' + t.name + ': ' + e.message) } }
  done(); if (failed) process.exitCode = 1; else console.log('AQUAFLOW_SUITE_OK ' + tests.length)
})().catch(e => { done(); console.error(e); process.exitCode = 1 })
