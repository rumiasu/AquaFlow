// F-55：执行真实独立押金页面，历史凭据不能被当前商品/报价失败挡住。
const assert = require('assert')
const fs = require('fs')
const path = require('path')
const { loadPage, createWx, createApp, armWatchdog, ROOT } = require('./harness')
const { STORAGE_KEYS } = require('../../miniapp-user/utils/storage-keys')
const done = armWatchdog()
let passed = 0
async function test(name, fn) { await fn(); passed++; console.log('  ✓ ' + name) }
const pending = () => ({ id: 81, quantity: 2, amount: 60, status: 'PENDING', statusText: '待付款' })
const goods = () => [{ id: 5, category: 1, name: '桶装水' }, { id: 6, category: 1, name: '另一款水' }]
function deferred() { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
function setup(scenario = {}) {
  const wx = createWx(), calls = { products: [], quotes: [], history: [], purchase: [], withdraw: [] }
  wx.setStorageSync(STORAGE_KEYS.CUSTOMER_ID, 7)
  const page = loadPage('miniapp-user/pages/barrel/purchase.js', { wx, app: createApp({ globalData: { isLogin: true } }), stubs: {
    'utils/station': { resolveStationId: async () => 1 },
    'api/product': { getStationProducts: async station => {
      calls.products.push(station)
      return scenario.products ? scenario.products(station) : { data: goods() }
    } },
    'api/barrel': {
      quoteBarrelRight: async (...args) => { calls.quotes.push(args); return scenario.quote ? scenario.quote(...args) : { data: { amount: 30, stationName: '购买站' } } },
      getBarrelRightPurchases: async station => { calls.history.push(station); return scenario.history ? scenario.history(station) : { data: [pending()] } },
      purchaseBarrelRight: async body => { calls.purchase.push(body); return { data: { status: 1 } } },
      withdrawBarrelRightPurchase: async id => { calls.withdraw.push(id); return scenario.withdraw ? scenario.withdraw(id) : {} }
    }
  } })
  return { page, wx, calls }
}
function event(id = 81) { return { currentTarget: { dataset: { id } } } }
function deferModal(t) { t.wx.showModal = opt => { t.wx.__calls.modal.push(opt) } }
async function main() {
  await test('商品全部下架后仍读取原站历史且不请求报价', async () => {
    const t = setup({ products: async () => ({ data: [] }) }); await t.page.onLoad({ stationId: 9 })
    assert.deepStrictEqual(t.calls.history, [9]); assert.equal(t.page.data.purchases[0].id, 81)
    assert.equal(t.page.data.productsLoaded, true); assert.equal(t.page.data.productsError, '')
    assert.equal(t.page.data.purchasesLoaded, true); assert.equal(t.calls.quotes.length, 0)
    await t.page.onPurchase(); assert.equal(t.calls.purchase.length, 0)
  })
  await test('商品请求失败不挡历史，失败不能发新购买', async () => {
    const t = setup({ products: async () => { throw Error('商品加载失败') } }); await t.page.onLoad({ stationId: 1 })
    assert.equal(t.page.data.productsError, '商品加载失败'); assert.equal(t.page.data.purchases.length, 1)
    assert.equal(t.page.data.purchasesError, ''); assert.equal(t.page.data.productsLoading, false)
    await t.page.onPurchase(); assert.equal(t.calls.purchase.length, 0)
  })
  await test('报价失败仍读取历史且禁止购买', async () => {
    const t = setup({ quote: async () => { throw Error('金额暂时不可用') } }); await t.page.onLoad({ stationId: 1 })
    assert.equal(t.page.data.quoteError, '金额暂时不可用'); assert.equal(t.page.data.quoteLoading, false)
    assert.equal(t.page.data.purchases[0].id, 81); await t.page.onPurchase(); assert.equal(t.calls.purchase.length, 0)
  })
  await test('商品请求未结束时历史先到达并完成自己的加载状态', async () => {
    const gate = deferred(), t = setup({ products: () => gate.promise }); const load = t.page.onLoad({ stationId: 1 })
    await Promise.resolve(); await Promise.resolve()
    assert.equal(t.page.data.productsLoading, true); assert.equal(t.page.data.purchasesLoading, false)
    assert.equal(t.page.data.purchasesLoaded, true); assert.equal(t.page.data.purchases[0].id, 81)
    gate.resolve({ data: [] }); await load
  })
  await test('报价等待期间历史已显示且不能购买', async () => {
    const gate = deferred(), t = setup({ quote: () => gate.promise }); const load = t.page.onLoad({ stationId: 1 })
    await Promise.resolve(); await Promise.resolve(); await Promise.resolve()
    assert.equal(t.page.data.quoteLoading, true); assert.equal(t.page.data.purchasesLoaded, true)
    await t.page.onPurchase(); assert.equal(t.calls.purchase.length, 0)
    gate.resolve({ data: { amount: 30, stationName: '购买站' } }); await load
  })
  await test('历史失败不伪装为空记录，点击重试恢复且不需要重报金额', async () => {
    let fail = true
    const t = setup({ history: async () => { if (fail) throw Error('购买记录加载失败'); return { data: [pending()] } } })
    await t.page.onLoad({ stationId: 1 }); assert.equal(t.page.data.purchasesLoaded, false)
    assert.equal(t.page.data.purchasesError, '购买记录加载失败'); assert.equal(t.page.data.purchasesLoading, false)
    fail = false; await t.page.loadPurchases(); assert.equal(t.page.data.purchasesError, '')
    assert.equal(t.page.data.purchasesLoaded, true); assert.equal(t.page.data.purchases[0].id, 81)
    assert.equal(t.calls.history.length, 2); assert.equal(t.calls.quotes.length, 1)
  })
  await test('成功空历史才标记已加载且无错误', async () => {
    const t = setup({ history: async () => ({ data: [] }) }); await t.page.onLoad({ stationId: 1 })
    assert.equal(t.page.data.purchasesLoaded, true); assert.equal(t.page.data.purchasesError, '')
    assert.equal(t.page.data.purchases.length, 0)
  })
  await test('历史刷新失败保留上次记录但禁止按旧状态撤回', async () => {
    let fail = false
    const t = setup({ history: async () => { if (fail) throw Error('记录暂不可用'); return { data: [pending()] } } })
    await t.page.onLoad({ stationId: 1 }); fail = true; await t.page.loadPurchases()
    assert.equal(t.page.data.purchases[0].id, 81); assert.equal(t.page.data.purchasesError, '记录暂不可用')
    t.page.onWithdraw(event()); assert.equal(t.calls.withdraw.length, 0); assert.equal(t.wx.__calls.modal.length, 0)
  })
  await test('商品失败后点击重试仅恢复商品与报价，既有历史不丢', async () => {
    let fail = true
    const t = setup({ products: async () => { if (fail) throw Error('商品失败'); return { data: goods() } } })
    await t.page.onLoad({ stationId: 1 }); fail = false; await t.page.loadProducts()
    assert.equal(t.page.data.productsError, ''); assert.equal(t.page.data.productsLoaded, true)
    assert(t.page.data.quote); assert.equal(t.page.data.purchases[0].id, 81); assert.equal(t.calls.history.length, 1)
  })
  await test('报价重试恢复金额且不会把历史清空或重复读取', async () => {
    let fail = true
    const t = setup({ quote: async () => { if (fail) throw Error('金额失败'); return { data: { amount: 60, stationName: '购买站' } } } })
    await t.page.onLoad({ stationId: 1 }); fail = false; await t.page.refreshQuote()
    assert.equal(t.page.data.quote.amount, 60); assert.equal(t.page.data.quoteError, '')
    assert.equal(t.calls.history.length, 1); assert.equal(t.page.data.purchases[0].id, 81)
  })
  await test('下架后仍用原购买编号撤回未付款申请并只刷新历史', async () => {
    const t = setup({ products: async () => ({ data: [] }) }); await t.page.onLoad({ stationId: 1 }); deferModal(t)
    t.page.onWithdraw(event('81')); const modal = t.wx.__calls.modal.at(-1)
    assert(modal.content.includes('已交钱但尚未确认')); assert(modal.content.includes('先联系水站核实收款'))
    await modal.success({ confirm: true }); assert.deepStrictEqual(t.calls.withdraw, ['81'])
    assert.equal(t.calls.history.length, 2); assert.equal(t.calls.products.length, 1); assert.equal(t.calls.quotes.length, 0)
    assert.equal(t.page.data.withdrawingId, null)
  })
  await test('商品读取失败不影响合法未付款撤回', async () => {
    const t = setup({ products: async () => { throw Error('商品失败') } }); await t.page.onLoad({ stationId: 1 }); deferModal(t)
    t.page.onWithdraw(event()); await t.wx.__calls.modal.at(-1).success({ confirm: true })
    assert.deepStrictEqual(t.calls.withdraw, [81]); assert.equal(t.calls.history.length, 2)
  })
  await test('撤回弹窗取消不会提交原凭据', async () => {
    const t = setup(); await t.page.onLoad({ stationId: 1 }); deferModal(t)
    t.page.onWithdraw(event()); await t.wx.__calls.modal.at(-1).success({ confirm: false }); assert.equal(t.calls.withdraw.length, 0)
  })
  await test('已确认/已撤回记录与编造编号都不能发撤回', async () => {
    const t = setup({ history: async () => ({ data: [{ ...pending(), status: 'PAID' }, { ...pending(), id: 82, status: 'CANCELLED' }] }) })
    await t.page.onLoad({ stationId: 1 }); for (const id of [81, 82, 999]) t.page.onWithdraw(event(id))
    assert.equal(t.calls.withdraw.length, 0); assert.equal(t.wx.__calls.modal.length, 0)
  })
  await test('弹窗期间身份改变或退出不能撤回旧客户记录', async () => {
    for (const customer of [8, null]) {
      const t = setup(); await t.page.onLoad({ stationId: 1 }); deferModal(t); t.page.onWithdraw(event())
      if (customer) t.wx.setStorageSync(STORAGE_KEYS.CUSTOMER_ID, customer); else t.wx.removeStorageSync(STORAGE_KEYS.CUSTOMER_ID)
      await t.wx.__calls.modal.at(-1).success({ confirm: true }); assert.equal(t.calls.withdraw.length, 0)
      assert(t.wx.__calls.toast.at(-1).title.includes('变化'))
    }
  })
  await test('弹窗期间记录刷新为已付款不能继续撤回', async () => {
    let paid = false
    const t = setup({ history: async () => ({ data: [{ ...pending(), status: paid ? 'PAID' : 'PENDING' }] }) })
    await t.page.onLoad({ stationId: 1 }); deferModal(t); t.page.onWithdraw(event()); paid = true; await t.page.loadPurchases()
    await t.wx.__calls.modal.at(-1).success({ confirm: true }); assert.equal(t.calls.withdraw.length, 0)
  })
  await test('实际收款状态变化由服务端拒绝时保留记录并提示失败', async () => {
    const t = setup({ withdraw: async () => { throw Error('押金已确认到账，请走权益退还申请') } })
    await t.page.onLoad({ stationId: 1 }); deferModal(t); t.page.onWithdraw(event()); await t.wx.__calls.modal.at(-1).success({ confirm: true })
    assert.equal(t.calls.withdraw.length, 1); assert.equal(t.page.data.purchases[0].id, 81)
    assert(t.wx.__calls.toast.at(-1).title.includes('权益退还申请')); assert.equal(t.page.data.withdrawingId, null)
  })
  await test('旧报价晚返回不能覆盖新商品/数量的报价', async () => {
    const waits = [], t = setup({ quote: (...args) => { const gate = deferred(); waits.push({ ...gate, args }); return gate.promise } })
    const load = t.page.onLoad({ stationId: 1 }); await Promise.resolve(); await Promise.resolve(); await Promise.resolve()
    const change = t.page.onProduct({ detail: { value: 1 } }); const quantity = t.page.onQuantity({ detail: { value: '3' } })
    assert.deepStrictEqual(waits[2].args, [1, 6, 3]); assert.equal(t.page.data.quote, null)
    waits[2].resolve({ data: { amount: 90, stationName: '新报价站' } }); await quantity
    waits[1].resolve({ data: { amount: 30, stationName: '旧商品报价站' } }); await change
    waits[0].resolve({ data: { amount: 10, stationName: '最旧报价站' } }); await load
    assert.equal(t.page.data.quote.amount, 90); assert.equal(t.page.data.stationName, '新报价站'); assert.equal(t.page.data.quoteLoading, false)
  })
  await test('旧报价失败不覆盖新报价成功', async () => {
    const waits = [], t = setup({ quote: () => { const gate = deferred(); waits.push(gate); return gate.promise } })
    const load = t.page.onLoad({ stationId: 1 }); await Promise.resolve(); await Promise.resolve(); await Promise.resolve()
    const change = t.page.onQuantity({ detail: { value: '2' } }); waits[1].resolve({ data: { amount: 60, stationName: '购买站' } }); await change
    waits[0].reject(Error('旧报价错误')); await load; assert.equal(t.page.data.quote.amount, 60); assert.equal(t.page.data.quoteError, '')
  })
  await test('旧历史响应不覆盖更新结果且身份变化不显示旧客户记录', async () => {
    const waits = [], t = setup({ history: () => { const gate = deferred(); waits.push(gate); return gate.promise } })
    const load = t.page.onLoad({ stationId: 1 }); const retry = t.page.loadPurchases()
    waits[1].resolve({ data: [{ ...pending(), id: 82 }] }); await retry
    waits[0].resolve({ data: [pending()] }); await load; assert.equal(t.page.data.purchases[0].id, 82)
    const next = t.page.loadPurchases(); t.wx.setStorageSync(STORAGE_KEYS.CUSTOMER_ID, 8)
    waits[2].resolve({ data: [pending()] }); await next
    assert.equal(t.page.data.purchases.length, 0); assert.equal(t.page.data.purchasesLoaded, false)
    assert(t.page.data.purchasesError.includes('身份已变化'))
  })
  await test('WXML 重试/撤回事件可达，空态分支在加载失败之后且改动文件无 BOM', async () => {
    const t = setup(), file = path.join(ROOT, 'miniapp-user/pages/barrel/purchase.wxml'), wxml = fs.readFileSync(file, 'utf8')
    for (const match of wxml.matchAll(/\b(?:bind|catch)(?:\w+|:\w+)="([\w]+)"/g)) assert.equal(typeof t.page[match[1]], 'function', match[1])
    for (const handler of ['loadProducts', 'refreshQuote', 'loadPurchases', 'onWithdraw']) assert(wxml.includes('bindtap="' + handler + '"'))
    assert(wxml.includes('wx:elif="{{purchasesError}}"')); assert(wxml.includes('wx:elif="{{purchasesLoaded && purchases.length === 0}}"'))
    assert(wxml.indexOf('wx:elif="{{purchasesError}}"') < wxml.indexOf('purchasesLoaded && purchases.length === 0'))
    assert(wxml.includes('已交钱但水站尚未确认')); assert(wxml.includes('购买编号 {{item.id}}'))
    for (const ext of ['js', 'wxml', 'wxss']) {
      const bytes = fs.readFileSync(path.join(ROOT, 'miniapp-user/pages/barrel/purchase.' + ext))
      assert.notEqual(bytes.subarray(0, 3).toString('hex'), 'efbbbf')
    }
  })
  done(); console.log('AQUAFLOW_SUITE_OK ' + passed)
}
main().catch(err => { done(); console.error(err); process.exitCode = 1 })
