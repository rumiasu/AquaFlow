'use strict'
const assert = require('assert')
const { loadPage, createWx, createApp } = require('./harness')
const time = '2026-10-02T10:00:00'
const row = id => ({ id, createTime: time })
const page = (ids, more, id) => ({ code: 0, data: { orders: ids.map(row), hasMore: more, ...(more ? { nextBeforeTime: time, nextBeforeId: id } : {}) } })
function setup(handler) {
  const wx = createWx(), calls = []
  const app = createApp({ globalData: { userInfo: { staffId: 8, role: 'DELIVERY', stationId: 2, bindStatus: 'BOUND' } }, canAccessStationBusiness: () => true })
  return { app, calls, page: loadPage('miniapp-delivery/pages/history/index.js', { wx, app, stubs: { 'api/delivery': { getDeliveryHistory: args => { calls.push(args); return handler(args) } } } }) }
}
function deferred() { let resolve; const promise = new Promise(r => { resolve = r }); return { promise, resolve } }
async function main() {
  let n = 0
  const t = setup(() => ++n === 1 ? page([4, 3], true, 3) : page([2, 1], false))
  await t.page.onShow(); assert.deepStrictEqual(t.calls[0], { pageSize: 20 }); assert.equal(t.page.data.hasMore, true)
  await t.page.onLoadMore(); assert.deepStrictEqual(t.calls[1], { pageSize: 20, beforeTime: time, beforeId: 3 })
  assert.deepStrictEqual(t.page.data.orders.map(x => x.id), [4, 3, 2, 1]); assert.equal(t.page.data.hasMore, false)
  await t.page.onLoadMore(); assert.equal(t.calls.length, 2)
  let fail = false
  const retry = setup(() => { if (fail) throw Error('network'); return page([3], true, 3) })
  await retry.page.onShow(); fail = true; await retry.page.onLoadMore()
  assert.equal(retry.page.data.orders[0].id, 3); assert(retry.page.data.moreError); assert(retry.page.data.hasMore)
  fail = false; await retry.page.onLoadMore(); assert(retry.page.data.moreError) // repeated cursor rejected
  const legacy = setup(() => ({ code: 0, data: [row(2), row(1)] }))
  await legacy.page.onShow(); assert.equal(legacy.page.data.orders.length, 2); assert.equal(legacy.page.data.hasMore, false)
  const gate = deferred(); let count = 0
  const race = setup(() => ++count === 1 ? page([9], true, 9) : count === 2 ? gate.promise : page([7], false))
  await race.page.onShow(); const old = race.page.onLoadMore(); await race.page.onRetry(); gate.resolve(page([8], false)); await old
  assert.deepStrictEqual(race.page.data.orders.map(x => x.id), [7]); assert.equal(race.page.data.loadingMore, false)
  const bad = setup(() => ({ code: 0, data: { orders: [row(1)], hasMore: true, nextBeforeId: 1 } }))
  await bad.page.onShow(); assert.equal(bad.page.data.loaded, false); assert(bad.page.data.error)
  console.log('AQUAFLOW_SUITE_OK 1 delivery-history-pagination')
}
main().catch(e => { console.error(e); process.exitCode = 1 })
