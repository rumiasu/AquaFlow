'use strict'

const assert = require('assert')
const fs = require('fs')
const path = require('path')
const { loadPage, createWx, createApp, armWatchdog } = require('./harness')

const prep = { ready: false, items: [{ productName: 'Water', shortage: 2 }] }
const order = (status, extra = {}) => ({ id: 55, status, items: [], stockPrep: prep, ...extra })
function newPage(read, complete = async () => ({ data: {} })) {
  return loadPage('miniapp-delivery/pages/order/complete.js', {
    wx: createWx(), app: createApp(),
    stubs: {
      'api/delivery': { getOrderDetail: read, completeOrder: complete },
      'utils/request': { get: async () => ({ data: {} }) }
    }
  })
}
function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
const baseline = process.argv.includes('--baseline')
async function withNavigationTimer(run) {
  const original = global.setTimeout
  const timers = []
  global.setTimeout = (fn, delay, ...args) => delay === 1500
    ? (timers.push(() => fn(...args)), 0) : original(fn, delay, ...args)
  try { await run(timers) } finally { global.setTimeout = original }
}
const markup = fs.readFileSync(path.resolve(__dirname, '../../miniapp-delivery/pages/order/complete.wxml'), 'utf8')
// Evaluate the actual prompt predicate for both the baseline reproduction and the fix.
const predicate = markup.match(/<view class="money-prep" wx:if="{{([^}]+)}}">/)[1]
function visible(page) {
  return !!Function('stockPrep', 'showStockPrepPrompt', `return (${predicate})`)(
    page.data.stockPrep, page.data.showStockPrepPrompt)
}
let passed = 0
async function test(name, run) {
  await run()
  passed++
  console.log('PASS ' + name)
}

;(async () => {
  const done = armWatchdog()
  if (baseline) {
    for (const status of [3, 4, 5]) {
      const page = newPage(async () => ({ data: order(status) }))
      await page.loadOrder(55)
      assert.strictEqual(visible(page), true, 'baseline must reproduce stale terminal prompt')
      assert(page.data.stockPrepText.includes('2'))
      console.log('REPRODUCED baseline terminal status=' + status + ' shows shortage and inventory link')
    }
    await withNavigationTimer(async () => {
      const page = newPage(async () => ({ data: order(2) }))
      await page.loadOrder(55)
      page.setData({ orderId: 55 })
      await page._doSubmit()
      assert.strictEqual(page.data.resultState, 'success')
      assert.strictEqual(visible(page), true)
      assert(page.data.stockPrepText.includes('2'))
      console.log('REPRODUCED baseline success retains shortage and inventory link')
    })
    console.log('AQUAFLOW_BASELINE_REPRO_OK 4')
    done()
    return
  }
  for (const status of [1, 2]) {
    await test('active shortage status=' + status, async () => {
      const page = newPage(async () => ({ data: order(status) }))
      await page.loadOrder(55)
      assert.strictEqual(visible(page), true)
      assert(page.data.stockPrepText.includes('2'))
      assert.deepStrictEqual(page.data.orderInfo.stockPrep, prep)
    })
  }
  for (const status of [3, 4, 5, null]) {
    await test('non-active status=' + status + ' preserves projection but hides prompt', async () => {
      const page = newPage(async () => ({ data: order(status) }))
      await page.loadOrder(55)
      assert.strictEqual(visible(page), false)
      assert.strictEqual(page.data.stockPrepText, '')
      assert.deepStrictEqual(page.data.stockPrep, prep)
      assert.deepStrictEqual(page.data.orderInfo.stockPrep, prep)
    })
  }
  await test('active refunded payment remains visible', async () => {
    const page = newPage(async () => ({ data: order(2, { paymentStatus: 3 }) }))
    await page.loadOrder(55)
    assert.strictEqual(visible(page), true)
  })
  for (const stockPrep of [null, { ready: true }]) {
    await test('missing or ready prep hides prompt', async () => {
      const page = newPage(async () => ({ data: order(2, { stockPrep }) }))
      await page.loadOrder(55)
      assert.strictEqual(visible(page), false)
      assert.strictEqual(page.data.stockPrepText, stockPrep ? '本单已备齐' : '')
    })
  }
  await test('first response after another session completes the order', async () => {
    const read = deferred()
    const page = newPage(() => read.promise)
    const pending = page.loadOrder(55)
    assert.strictEqual(visible(page), false)
    read.resolve({ data: order(4) })
    await pending
    assert.strictEqual(visible(page), false)
  })
  await test('reload clears prior shortage during load, then applies terminal state', async () => {
    const read = deferred()
    let calls = 0
    const page = newPage(() => ++calls === 1 ? Promise.resolve({ data: order(2) }) : read.promise)
    await page.loadOrder(55)
    assert.strictEqual(visible(page), true)
    const pending = page.loadOrder(55)
    assert.strictEqual(visible(page), false)
    assert.strictEqual(page.data.stockPrepText, '')
    read.resolve({ data: order(5) })
    await pending
    assert.strictEqual(visible(page), false)
  })
  await test('late active response cannot restore shortage after newer terminal response', async () => {
    const older = deferred(), newer = deferred()
    let calls = 0
    const page = newPage(() => ++calls === 1 ? older.promise : newer.promise)
    const first = page.loadOrder(55), second = page.loadOrder(55)
    newer.resolve({ data: order(3) })
    await second
    older.resolve({ data: order(2) })
    await first
    assert.strictEqual(page.data.orderInfo.status, 3)
    assert.strictEqual(visible(page), false)
  })
  for (const outcome of ['failure', 'empty']) {
    await test('reload ' + outcome + ' does not retain prior shortage', async () => {
      let calls = 0
      const page = newPage(async () => {
        if (++calls === 1) return { data: order(2) }
        if (outcome === 'failure') throw new Error('offline')
        return { data: null }
      })
      await page.loadOrder(55)
      assert.strictEqual(visible(page), true)
      await page.loadOrder(55)
      assert.strictEqual(visible(page), false)
      assert.strictEqual(page.data.stockPrepText, '')
    })
  }
  await test('success clears prompt before navigation and preserves audit projection after navigation failure', async () => {
    await withNavigationTimer(async timers => {
      const page = newPage(async () => ({ data: order(2) }))
      await page.loadOrder(55)
      page.setData({ orderId: 55 })
      assert.strictEqual(visible(page), true)
      let navAttempts = 0
      page.__wx.navigateBack = () => { navAttempts++ } // Simulate failed navigation: page remains open.
      await page._doSubmit()
      assert.strictEqual(page.data.resultState, 'success')
      assert.strictEqual(visible(page), false)
      assert.strictEqual(page.data.stockPrepText, '')
      assert.deepStrictEqual(page.data.stockPrep, prep)
      assert.deepStrictEqual(page.data.orderInfo.stockPrep, prep)
      assert.strictEqual(timers.length, 1)
      timers[0]()
      assert.strictEqual(navAttempts, 1)
      assert.strictEqual(visible(page), false)
      assert.strictEqual(page.data.resultState, 'success')
    })
  })
  for (const outcome of ['success', 'failure']) {
    await test('load ' + outcome + ' arriving after submit success cannot restore prompt or overwrite success', async () => {
      await withNavigationTimer(async () => {
        const submit = deferred(), stale = deferred()
        let reads = 0
        const page = newPage(() => ++reads === 1 ? Promise.resolve({ data: order(2) }) : stale.promise,
          () => submit.promise)
        await page.loadOrder(55)
        page.setData({ orderId: 55 })
        const pendingSubmit = page._doSubmit()
        const pendingLoad = page.loadOrder(55)
        submit.resolve({ data: {} })
        await pendingSubmit
        assert.strictEqual(page.data.resultState, 'success')
        const afterSuccess = JSON.stringify(page.data)
        const toastCount = page.__wx.__calls.toast.length
        if (outcome === 'success') stale.resolve({ data: order(2, { stockPrep: { ready: false, items: [] } }) })
        else stale.reject(new Error('offline'))
        await pendingLoad
        assert.strictEqual(JSON.stringify(page.data), afterSuccess)
        assert.strictEqual(page.__wx.__calls.toast.length, toastCount)
        assert.strictEqual(visible(page), false)
        assert.strictEqual(page.data.stockPrepText, '')
        assert.strictEqual(page.data.resultState, 'success')
      })
    })
  }
  console.log('AQUAFLOW_SUITE_OK ' + passed)
  done()
})().catch(err => { console.error(err.stack); process.exit(1) })
