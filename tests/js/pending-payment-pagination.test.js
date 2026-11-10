const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { loadPage, createApp, createWx, ROOT, armWatchdog } = require('./harness')
const { parseWxml, renderElements } = require('./wxml-tree')
const done = armWatchdog(30000)
const source = 'miniapp-delivery/pages/station-mgmt/payments/index.js'
const pageResponse = (items, nextCursor = null) => ({ code: 0, data: { items, nextCursor } })
const rows = (start, count) => Array.from({ length: count }, (_, i) => ({ id: start + i, orderId: (start + i) % 2 ? start + i : null, amount: 12 }))
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
const event = id => ({ currentTarget: { dataset: { id } } })
function fixture(read) {
  const wx = createWx(), calls = [], dialogs = [], writes = []
  const app = createApp({ _loginGeneration: 1, globalData: { isLogin: true, userInfo: { staffId: 8, stationId: 11, role: 'STATION_MANAGER' } } })
  const page = loadPage(source, { app, wx, stubs: {
    'api/station-mgmt': {
      getPendingPayments: async query => { calls.push(query); return read(query, calls.length) },
      confirmPayment: async id => { writes.push(id); return { code: 0 } }
    },
    'utils/business-dialog': { showBusinessModal: dialog => dialogs.push(dialog) }
  } })
  return { page, app, wx, calls, dialogs, writes }
}
let passed = 0
async function test(name, run) { await run(); passed++; console.log('PASS ' + name) }
;(async () => {
  await test('more than 200 mixed order and independent receipts are reachable in stable batches', async () => {
    const t = fixture(query => {
      const start = query.cursor ? Number(query.cursor) : 1
      const count = Math.min(50, 226 - start)
      return pageResponse(rows(start, count), start + count <= 225 ? String(start + count) : null)
    })
    await t.page.onShow()
    while (t.page.data.pendingHasMore) await t.page.onPendingMore()
    assert.deepEqual(t.page.data.list.map(row => row.id), Array.from({ length: 225 }, (_, i) => i + 1))
    assert.equal(t.calls.length, 5)
    assert(t.calls.every(query => query.pageSize === 50))
    assert(t.page.data.list.some(row => row.orderId === null))
  })
  await test('failed continuation retains rows and exact cursor for retry', async () => {
    let fail = true
    const t = fixture(query => {
      if (!query.cursor) return pageResponse(rows(1, 50), 'cursor-50')
      if (fail) throw new Error('合成网络失败')
      return pageResponse(rows(51, 2))
    })
    await t.page.onShow(); await t.page.onPendingMore()
    assert.equal(t.page.data.list.length, 50); assert.equal(t.page.data.pendingMoreError, '合成网络失败')
    assert.equal(t.page.data.pendingHasMore, true)
    fail = false; await t.page.onPendingMore()
    assert.equal(t.page.data.list.length, 52); assert.equal(t.page.data.pendingMoreError, '')
    assert.equal(t.calls[1].cursor, t.calls[2].cursor)
  })
  await test('continuation is single flight and old page cannot replace a fresh read', async () => {
    const next = deferred(), t = fixture((query, call) => call === 1 ? pageResponse(rows(1, 2), 'c2')
      : query.cursor ? next.promise : pageResponse(rows(90, 1)))
    await t.page.onShow(); const loading = t.page.onPendingMore(); await t.page.onPendingMore()
    assert.equal(t.calls.length, 2)
    await t.page.loadData(); next.resolve(pageResponse(rows(3, 2))); await loading
    assert.deepEqual(t.page.data.list.map(row => row.id), [90])
  })
  await test('successful confirmation removes only that row and keeps later-page continuation', async () => {
    const t = fixture(query => query.cursor ? pageResponse(rows(3, 2)) : pageResponse(rows(1, 2), 'c2'))
    await t.page.onShow(); t.page.onConfirm(event(1)); await t.dialogs[0].success({ confirm: true })
    assert.deepEqual(t.writes, [1]); assert.equal(t.calls.length, 1)
    assert.deepEqual(t.page.data.list.map(row => row.id), [2]); assert.equal(t.page._pendingCursor, 'c2')
    await t.page.onPendingMore(); assert.deepEqual(t.page.data.list.map(row => row.id), [2, 3, 4])
  })
  for (const [name, change] of [
    ['login generation', t => t.app._loginGeneration++],
    ['station', t => { t.app.globalData.userInfo.stationId = 22 }],
    ['owner', t => { t.app.globalData.userInfo = { ...t.app.globalData.userInfo } }],
    ['hide', t => t.page.onHide()]
  ]) await test('late continuation cannot survive changed ' + name, async () => {
    const next = deferred(), t = fixture(query => query.cursor ? next.promise : pageResponse(rows(1, 2), 'c2'))
    await t.page.onShow(); const loading = t.page.onPendingMore(); change(t)
    next.resolve(pageResponse(rows(3, 2))); await loading
    assert.deepEqual(t.page.data.list, []); assert.equal(t.page.data.pendingHasMore, false)
    if (name !== 'hide') assert(t.page.data.loadError.includes('重新加载'))
    t.page.onConfirm(event(3)); assert.equal(t.dialogs.length, 0)
  })
  await test('incomplete pages and repeated cursor keep retry without a false empty state', async () => {
    const t = fixture(query => query.cursor ? pageResponse(rows(3, 1), 'c2') : pageResponse(rows(1, 2), 'c2'))
    await t.page.onShow(); await t.page.onPendingMore()
    assert.equal(t.page.data.list.length, 2); assert(t.page.data.pendingMoreError)
    assert.equal(t.page._pendingCursor, 'c2')
    const bad = fixture(() => ({ code: 0, data: [] })); await bad.page.onShow()
    assert(bad.page.data.loadError); assert.deepEqual(bad.page.data.list, [])
  })
  await test('loaded count and recovery control are visible without inventing a total', async () => {
    const t = fixture(() => pageResponse(rows(1, 2), 'c2')); await t.page.onShow()
    t.page.setData({ pendingMoreError: '合成失败' })
    const tree = parseWxml(fs.readFileSync(path.join(ROOT, source.replace('.js', '.wxml')), 'utf8'))
    const elements = renderElements(tree, t.page.data, { includeText: true })
    assert(elements.some(node => node.text.includes('已加载 2 笔')))
    assert(elements.some(node => node.attrs.bindtap === 'onPendingMore'))
    assert(elements.some(node => node.text.includes('重试加载更多')))
  })
  console.log('AQUAFLOW_SUITE_OK ' + passed)
})().catch(error => { console.error(error); process.exitCode = 1 }).finally(done)
