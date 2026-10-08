'use strict'
// Executes the two production Pages; API reads are controlled, shared display utility is real.
const assert = require('assert')
const { loadPage, createWx, createApp, armWatchdog } = require('./harness')
const done = armWatchdog()
let passed = 0
const defer = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
const row = (id, items) => ({ id, firstProductName: '第一款水', quantity: 5, items, receiverName: '收件快照', createTime: '2026-10-07T10:00:00', updateTime: '2026-10-07T10:00:00' })
const mixed = [
  { id: 11, productNameSnapshot: '长名称桶装水'.repeat(12), specSnapshot: '18.9L', quantity: 3, category: 1, imageUrl: '/images/product.png' },
  { id: 12, productNameSnapshot: '瓶装水', specSnapshot: '500ml', quantity: 1, category: '2' },
  { id: 13, productNameSnapshot: '饮水机', quantity: 1, category: 3 }
]
function setup(pageName, handler) {
  const fn = pageName === 'history' ? 'getDeliveryHistory' : 'getTransferRecords'
  const state = { allowed: true, calls: 0, stops: 0, redirects: 0 }
  const wx = createWx()
  wx.stopPullDownRefresh = () => state.stops++
  const app = createApp({ _loginGeneration: 1, globalData: { userInfo: { staffId: 8, role: 'DELIVERY', stationId: 2, bindStatus: 'BOUND' } },
    canAccessStationBusiness: () => state.allowed, routeByRole: () => state.redirects++ })
  const page = loadPage(`miniapp-delivery/pages/${pageName}/index.js`, { wx, app, stubs: {
    'api/delivery': { [fn]: () => { state.calls++; return handler() } }
  } })
  page.onLoad()
  return { page, app, wx, state }
}
async function test(label, fn) { await fn(); passed++; console.log('PASS ' + label) }
async function main() {
  for (const name of ['history', 'transfer']) {
    await test(name + ' every mixed item uses its own quantity, category, snapshot and image', async () => {
      const input = row(1, mixed), before = JSON.stringify(input)
      const t = setup(name, () => ({ code: 0, data: [input] })); await t.page.onShow()
      const goods = t.page.data.orders[0].productItems
      assert.deepStrictEqual(goods.map(x => x.quantityText), ['× 3 桶', '× 1 瓶', '× 1 台'])
      assert.equal(goods[0].name, mixed[0].productNameSnapshot); assert.equal(goods[0].spec, '18.9L')
      assert.equal(goods[0].imageUrl, '/images/product.png'); assert.equal(t.state.calls, 1)
      assert.equal(JSON.stringify(input), before)
    })
    await test(name + ' single item and unknown category never guess from name or spec', async () => {
      const t = setup(name, () => ({ code: 0, data: [row(1, [mixed[1]]), row(2, [
        { productNameSnapshot: '桶装字样', specSnapshot: '18.9L', quantity: 2, category: 99 },
        { quantity: null }, { quantity: 'bad' }, { quantity: 0, barrelItem: true }
      ])] })); await t.page.onShow()
      assert.equal(t.page.data.orders[0].productItems[0].quantityText, '× 1 瓶')
      assert.deepStrictEqual(t.page.data.orders[1].productItems.map(x => x.quantityText), ['× 2 件', '数量待核对', '数量待核对', '× 0 桶'])
      assert.equal(t.page.data.orders[1].productItems[1].name, '商品')
    })
    await test(name + ' old and empty item data use neutral order fallback', async () => {
      const t = setup(name, () => ({ code: 0, data: [row(1), row(2, []), row(3, [null, []]), { ...row(4), itemSummary: '历史水 2桶，饮水机 1台' }] })); await t.page.onShow()
      const rows = t.page.data.orders
      assert.equal(rows[0].productFallback.meta, '等 5 件')
      assert.equal(rows[0].productFallback.text, '第一款水')
      assert.equal(rows[1].productItems.length, 0); assert.equal(rows[2].productItems.length, 0)
      assert.equal(rows[3].productFallback.text, '历史水 2桶，饮水机 1台')
      assert(!rows[0].productFallback.text.includes('×'))
    })
    await test(name + ' image failure only changes matching item, late old image cannot erase refreshed image', async () => {
      let url = '/old.png'
      const t = setup(name, () => ({ code: 0, data: [row(1, [{ ...mixed[0], imageUrl: url }])] })); await t.page.onShow()
      const event = src => ({ currentTarget: { dataset: { orderId: 1, itemIndex: 0, src } } })
      t.page.onProductImageError(event('/old.png')); assert.equal(t.page.data.orders[0].productItems[0].imageUrl, '')
      url = '/new.png'; await t.page.onRetry(); t.page.onProductImageError(event('/old.png'))
      assert.equal(t.page.data.orders[0].productItems[0].imageUrl, '/new.png')
    })
    await test(name + ' full response preserves ordering/count and refresh replaces rather than appends', async () => {
      let data = Array.from({ length: 60 }, (_, i) => row(60 - i, mixed))
      const t = setup(name, () => ({ code: 0, data })); await t.page.onShow()
      assert.equal(t.page.data.orders.length, 60); assert.equal(t.page.data.orders[59].id, 1)
      assert(t.page.data.orders.every(x => x.productItems.length === 3)); assert.equal(t.state.calls, 1)
      data = [row(90, [mixed[1]])]; await t.page.onPullDownRefresh()
      assert.equal(t.page.data.orders.length, 1); assert.equal(t.page.data.orders[0].id, 90)
      assert.equal(t.state.stops, 1); assert.equal(t.state.calls, 2)
    })
    await test(name + ' refresh failure preserves prior summaries then valid empty is distinct', async () => {
      let mode = 'ok'
      const t = setup(name, () => { if (mode === 'fail') throw Error('断网'); return { code: 0, data: mode === 'empty' ? [] : [row(1, mixed)] } })
      await t.page.onShow(); const previous = JSON.stringify(t.page.data.orders); mode = 'fail'; await t.page.onRetry()
      assert.equal(JSON.stringify(t.page.data.orders), previous); assert.match(t.page.data.error, /刷新失败/)
      mode = 'empty'; await t.page.onRetry(); assert.equal(t.page.data.orders.length, 0)
      assert.equal(t.page.data.error, ''); assert.equal(t.page.data.loaded, true)
    })
    await test(name + ' overlapping reads ignore stale data and leave current loading intact', async () => {
      const old = defer(), current = defer(); let n = 0
      const t = setup(name, () => ++n === 1 ? old.promise : current.promise)
      const a = t.page.onShow(), b = t.page.onRetry(); old.resolve({ code: 0, data: [row(1, mixed)] }); await a
      assert.equal(t.page.data.loading, true); assert.equal(t.page.data.orders.length, 0)
      current.resolve({ code: 0, data: [row(2, [mixed[1]])] }); await b
      assert.equal(t.page.data.orders[0].id, 2); assert.equal(t.page.data.loading, false)
    })
    await test(name + ' same-identity new login invalidates old result but token refresh does not', async () => {
      const old = defer(), t = setup(name, () => old.promise), a = t.page.onShow()
      t.app._loginGeneration++; old.resolve({ code: 0, data: [row(1, mixed)] }); await a
      assert.equal(t.page.data.orders.length, 0); assert.match(t.page.data.error, /身份或水站/)
      const fresh = defer(), u = setup(name, () => fresh.promise), b = u.page.onShow()
      u.app.globalData.token = 'refreshed-token'; fresh.resolve({ code: 0, data: [row(2, mixed)] }); await b
      assert.equal(u.page.data.orders[0].id, 2)
    })
    await test(name + ' role guard and unload block reads, results and image callbacks', async () => {
      const pending = defer(), t = setup(name, () => pending.promise), a = t.page.onShow()
      t.page.onUnload(); pending.resolve({ code: 0, data: [row(1, mixed)] }); await a; await t.page.onRetry()
      assert.equal(t.page.data.orders.length, 0); assert.equal(t.state.calls, 1)
      const u = setup(name, () => ({ code: 0, data: [row(2, mixed)] })); u.state.allowed = false; await u.page.onShow()
      assert.equal(u.state.calls, 0); assert.equal(u.state.redirects, 1)
    })
    await test(name + ' malformed envelope remains error, order tap still uses original id', async () => {
      const t = setup(name, () => ({ code: 0, data: [row(4, mixed)] })); await t.page.onShow()
      t.page.onOrderTap({ currentTarget: { dataset: { id: 4 } } })
      assert.equal(t.wx.__calls.nav[0].url, '/pages/order/detail?id=4')
      const u = setup(name, () => ({ code: 0, data: {} })); await u.page.onShow()
      assert.equal(u.page.data.loaded, false); assert(u.page.data.error); assert.equal(u.page.data.loading, false)
    })
  }
  done(); console.log(`AQUAFLOW_SUITE_OK ${passed}`)
}
main().catch(error => { done(); console.error(error); process.exitCode = 1 })
