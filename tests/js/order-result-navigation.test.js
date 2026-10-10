// 2026-10-10：真实确认页处理函数 + 合成API/原生导航回调；不访问微信、DB或真实付款。
const assert = require('assert')
const fs = require('fs')
const path = require('path')
const { loadPage, createWx, createApp, armWatchdog, ROOT } = require('./harness')
const { parseWxml, renderElements } = require('./wxml-tree')
const navigation = require('../../miniapp-user/utils/order-result-navigation')
const tests = []
const test = (name, run) => tests.push({ name, run })
const tree = parseWxml(fs.readFileSync(path.join(ROOT, 'miniapp-user/pages/order/create.wxml'), 'utf8'))

function fixture(status = 2) {
  for (const file of Object.keys(require.cache)) if (file.includes(path.join('miniapp-user', 'utils'))) delete require.cache[file]
  let now = 0, timerId = 0
  const timers = new Map(), nav = [], writes = [], payments = [], reads = [], changes = []
  const wx = createWx(), app = createApp({ _loginGeneration: 1, globalData: {
    isLogin: true, customerId: 7, accessToken: 'synthetic-access', refreshToken: 'synthetic-refresh'
  } })
  wx.setStorageSync('selectedStation', { id: 11 })
  for (const api of ['redirectTo', 'switchTab']) wx[api] = options => nav.push({ api, options })
  const page = loadPage('miniapp-user/pages/order/create.js', { wx, app, stubs: {
    'utils/order-result-navigation': { createOrderResultNavigator: env => navigation.createOrderResultNavigator({ ...env,
      setTimer(fn, ms) { const id = ++timerId; timers.set(id, { at: now + ms, fn }); return id },
      clearTimer(id) { timers.delete(id) }
    }) },
    'api/order': {
      createOrder: async body => { writes.push(body); return { data: { orderId: 777, warnings: [] } } },
      createPayment: async body => { payments.push(body); return { data: { status: 2 } } },
      getOrderDetail: async id => { reads.push(id); if (status === 'unknown') throw new Error('synthetic detail timeout'); return { data: { id, paymentStatus: status } } }
    }
  } })
  page.setData({ loading: false, stationId: 11, address: { id: 91 },
    products: [{ id: 5, name: 'Synthetic water', price: 10, deposit: 0, quantity: 2 }],
    selectedMethod: 1, wechatPay: { enabled: true }, quoteReady: true, blocked: false,
    totalAmount: 20, totalWaterCost: 20, totalDeposit: 0, shortageItems: [], barrelPurchases: [] })
  const setData = page.setData
  page.setData = (data, callback) => { changes.push(data); setData.call(page, data, callback) }
  function fail(index = nav.length - 1) {
    const { api, options } = nav[index], error = { errMsg: api + ':fail timeout' }
    options.fail(error); options.complete(error)
  }
  function ok(index = nav.length - 1) {
    const { api, options } = nav[index], result = { errMsg: api + ':ok' }
    options.success(result); options.complete(result)
  }
  function advance(ms) {
    now += ms
    for (const [id, timer] of [...timers]) if (timer.at <= now) { timers.delete(id); timer.fn() }
  }
  const elements = () => renderElements(tree, page.data, { includeText: true })
  function tap(handler) {
    const button = elements().find(e => e.attrs.bindtap === handler)
    assert(button, 'recovery action must be reachable in actual WXML: ' + handler)
    page[button.attrs.bindtap]()
  }
  const businessCounts = () => [writes.length, payments.length, reads.length]
  const sameOrder = index => assert.strictEqual(nav[index].options.url, '/pages/order/success?id=777&stationId=11')
  return { page, wx, app, nav, writes, payments, reads, changes, timers, fail, ok, advance, tap, elements, businessCounts, sameOrder }
}

test('已付后redirect超时保留原单付款事实，不能进入建单失败catch', async () => {
  const f = fixture(); await f.page._createOrder(false)
  assert.deepStrictEqual(f.businessCounts(), [1, 1, 1]); f.sameOrder(0)
  assert.strictEqual(f.page.data.orderNavigationBusy, true)
  f.fail()
  assert.strictEqual(f.page.data.pendingOrderPaid, true)
  assert(f.page.data.orderNavigationError.includes('订单号 777：这笔订单已确认付款。页面打开超时'))
  assert.strictEqual(f.page.data.orderNavigationBusy, false)
  assert(!f.wx.__calls.modal.some(m => m.title === '下单没提交成功'))
  assert(f.elements().some(e => e.attrs.bindtap === 'onRetryOrderNavigation'))
  assert(f.elements().some(e => e.attrs.bindtap === 'onOpenMyOrders'))
})

test('恢复重试连点只导航同原单，创建付款回查增量均零', async () => {
  const f = fixture(); await f.page._createOrder(false); f.fail()
  f.tap('onRetryOrderNavigation')
  for (let i = 0; i < 5; i++) f.page.onRetryOrderNavigation()
  assert.strictEqual(f.nav.length, 2); f.sameOrder(1)
  assert.deepStrictEqual(f.businessCounts(), [1, 1, 1])
  f.ok(); assert.strictEqual(f.page.data.orderNavigationError, '')
  assert.strictEqual(f.page.data.orderNavigationBusy, false)
  assert.strictEqual(f.page.data.pendingOrderPaid, true)
})

test('我的订单使用顾客tab，连点单飞且失败后仍能重试原单', async () => {
  const f = fixture(); await f.page._createOrder(false); f.fail(); f.tap('onOpenMyOrders')
  for (let i = 0; i < 5; i++) f.page.onOpenMyOrders()
  assert.strictEqual(f.nav.length, 2); assert.strictEqual(f.nav[1].api, 'switchTab')
  assert.strictEqual(f.nav[1].options.url, '/pages/order/list')
  f.fail(); f.tap('onRetryOrderNavigation'); f.sameOrder(2)
  assert.deepStrictEqual(f.businessCounts(), [1, 1, 1])
})

test('提交、原单详情及_createOrder已有单入口共用一份在途导航', async () => {
  const f = fixture(); await f.page._createOrder(false)
  await f.page.onSubmit(); f.page.onViewPendingOrder(); await f.page._createOrder(false)
  assert.strictEqual(f.nav.length, 1); assert.deepStrictEqual(f.businessCounts(), [1, 1, 1])
  f.fail(); await f.page._createOrder(false)
  assert.strictEqual(f.nav.length, 2); f.sameOrder(1)
  assert.deepStrictEqual(f.businessCounts(), [1, 1, 1])
})

test('fail和complete重复回调只呈现一次失败', async () => {
  const f = fixture(); await f.page._createOrder(false); f.fail(); f.fail()
  assert.strictEqual(f.changes.filter(d => d.orderNavigationError).length, 1)
  assert.strictEqual(f.timers.size, 0)
})

for (const success of [true, false]) test('仅complete回调也准确处理' + (success ? '成功' : '失败'), async () => {
  const f = fixture(); await f.page._createOrder(false)
  f.nav[0].options.complete({ errMsg: success ? 'redirectTo:ok' : 'redirectTo:fail' })
  assert.strictEqual(f.page.data.orderNavigationBusy, false)
  assert.strictEqual(!!f.page.data.orderNavigationError, !success)
})

test('原生API同步抛错留导航恢复，不能伪称建单失败', async () => {
  const f = fixture(); f.wx.redirectTo = () => { throw new Error('synthetic platform failure') }
  await f.page._createOrder(false)
  assert(f.page.data.orderNavigationError.includes('页面暂时无法打开'))
  assert.strictEqual(f.page.data.pendingOrderPaid, true)
  assert(!f.wx.__calls.modal.some(m => m.title === '下单没提交成功'))
  assert.deepStrictEqual(f.businessCounts(), [1, 1, 1])
})

test('原生无回调12秒兜底只解锁和提示，不自动重试', async () => {
  const f = fixture(); await f.page._createOrder(false); f.advance(11999)
  assert.strictEqual(f.page.data.orderNavigationBusy, true); assert.strictEqual(f.page.data.orderNavigationError, '')
  f.advance(1); assert(f.page.data.orderNavigationError.includes('页面打开超时'))
  f.advance(12000); assert.strictEqual(f.nav.length, 1)
  assert.deepStrictEqual(f.businessCounts(), [1, 1, 1])
})

test('兜底后的晚成功不能抹掉恢复提示；旧成功/失败不能释放新尝试', async () => {
  const f = fixture(); await f.page._createOrder(false); f.advance(12000)
  const error = f.page.data.orderNavigationError; f.ok(0)
  assert.strictEqual(f.page.data.orderNavigationError, error)
  f.tap('onRetryOrderNavigation'); f.ok(0); f.fail(0)
  assert.strictEqual(f.page.data.orderNavigationBusy, true)
  f.advance(12000); assert(f.page.data.orderNavigationError.includes('页面打开超时'))
  assert.strictEqual(f.nav.length, 2)
})

for (const change of ['hide', 'unload', 'station', 'selectedStation', 'identity', 'sameCustomerRelogin']) {
  test(change + '使旧回调及恢复动作失效，不导航旧单也不改付款事实', async () => {
    const f = fixture(); await f.page._createOrder(false); f.fail()
    if (change === 'hide') f.page.onHide()
    else if (change === 'unload') f.page.onUnload()
    else if (change === 'station') f.page.setData({ stationId: 22 })
    else if (change === 'selectedStation') f.wx.setStorageSync('selectedStation', { id: 22 })
    else if (change === 'identity') f.app.globalData.customerId = 8
    else require('../../miniapp-user/utils/token').beginSession()
    const before = JSON.stringify(f.page.data)
    f.ok(0); f.fail(0); assert.strictEqual(JSON.stringify(f.page.data), before)
    f.page.onRetryOrderNavigation(); f.page.onOpenMyOrders()
    assert.strictEqual(f.nav.length, 1); assert.deepStrictEqual(f.businessCounts(), [1, 1, 1])
    assert.strictEqual(f.page.data.lastSubmittedOrder.paid, true)
  })
}

test('离页返回后旧尝试无效，当前原单入口可以建立新导航', async () => {
  const f = fixture(); await f.page._createOrder(false); f.page.onHide()
  f.page.loadBarrel = () => {}; f.page.refreshQuote = () => {}; f.page.onShow()
  f.page.onViewPendingOrder(); assert.strictEqual(f.nav.length, 2)
  f.fail(0); assert.strictEqual(f.page.data.orderNavigationError, '')
  assert.strictEqual(f.page.data.orderNavigationBusy, true)
  f.fail(1); assert(f.page.data.orderNavigationError.includes('订单号 777'))
})

for (const status of [0, 1, 3, 4, 'unknown']) test('支付状态' + status + '导航失败不能显示已确认付款', async () => {
  const f = fixture(status); f.wx.__modalAutoConfirm = status === 'unknown'
  await f.page._createOrder(false); f.fail()
  assert.strictEqual(f.page.data.pendingOrderPaid, false)
  assert(!f.page.data.orderNavigationError.includes('已确认付款'))
  assert(f.page.data.orderNavigationError.includes('付款状态请在原订单中核实'))
  const before = f.businessCounts(); f.tap('onRetryOrderNavigation')
  assert.deepStrictEqual(f.businessCounts(), before); f.sameOrder(1)
})

test('其他原单的paid快照不能让当前目标被说成已付', () => {
  const f = fixture(); f.page._rememberSubmittedOrder(888, 'other', true, 'paid')
  f.page._openOrderResult(777); f.fail()
  assert(!f.page.data.orderNavigationError.includes('已确认付款'))
  f.sameOrder(0); assert.deepStrictEqual(f.businessCounts(), [0, 0, 0])
})

test('导航等待中换站，迟到失败不能显示旧站反馈或恢复入口', async () => {
  const f = fixture(); await f.page._createOrder(false); f.page.setData({ stationId: 22 })
  const before = JSON.stringify(f.page.data); f.fail(); f.advance(12000)
  assert.strictEqual(JSON.stringify(f.page.data), before)
  f.page.onRetryOrderNavigation(); f.page.onOpenMyOrders(); assert.strictEqual(f.nav.length, 1)
})

test('付款等待期间离页，最终回执不得重新打开原单页面', async () => {
  const f = fixture(); let release
  f.page._paySameOrder = () => new Promise(resolve => { release = resolve })
  const task = f.page._createOrder(false)
  for (let i = 0; i < 12; i++) await Promise.resolve()
  assert(release); f.page.onHide(); release({ ok: true, state: 'paid' }); await task
  assert.strictEqual(f.nav.length, 0); assert.strictEqual(f.page.data.orderNavigationError, '')
})

test('同登录周期正常续期仍可处理当前导航失败和恢复', async () => {
  const f = fixture(); await f.page._createOrder(false)
  const token = require('../../miniapp-user/utils/token'), session = token.captureSession()
  assert(token.acceptRefreshedTokens(session, 'synthetic-refreshed-access', 'synthetic-refreshed-refresh'))
  f.fail(); f.tap('onRetryOrderNavigation'); f.sameOrder(1)
  assert.deepStrictEqual(f.businessCounts(), [1, 1, 1])
})

test('索引存储失败也保留已付原单导航恢复，不重建单', async () => {
  const f = fixture(), writeStorage = f.wx.setStorageSync
  f.wx.setStorageSync = (key, value) => { if (key === 'lastOrderId') throw new Error('synthetic storage failure'); writeStorage(key, value) }
  await f.page._createOrder(false); f.fail(); f.tap('onRetryOrderNavigation'); f.sameOrder(1)
  assert.strictEqual(f.page.data.pendingOrderPaid, true)
  assert.deepStrictEqual(f.businessCounts(), [1, 1, 1])
  assert(!f.wx.__calls.modal.some(m => m.title === '下单没提交成功'))
})

test('恢复按钮位于既有吸底操作区，页尾失败也可发现且旧提示不重复', async () => {
  const find = (node, name) => node.attrs && node.attrs.class === name ? node
    : (node.children || []).map(child => find(child, name)).find(Boolean)
  const bottom = find(tree, 'bottom-bar'); assert(bottom)
  assert(find(bottom, 'order-navigation-error'))
  const f = fixture(); await f.page._createOrder(false); f.fail()
  assert(!f.elements().some(e => e.className === 'pending-order-hint'))
  assert(f.elements().some(e => e.className === 'order-navigation-message' && e.text.includes('订单号 777')))
})

function layoutQueries(f) {
  const pending = []
  f.wx.createSelectorQuery = () => ({
    in(owner) { assert.strictEqual(owner, f.page); return this },
    select(selector) { assert.strictEqual(selector, '.bottom-bar'); return this },
    boundingClientRect(callback) { pending.push(callback); return this }, exec() {}
  })
  return pending
}

test('普通态保留240rpx，恢复态按整栏实测高度占位并包含安全区和间距', async () => {
  const f = fixture(), pending = layoutQueries(f)
  assert.strictEqual(f.page.data.orderNavigationRecoveryStyle, '')
  const container = tree.children.find(n => n.attrs && n.attrs.class === 'container')
  assert.strictEqual(container.attrs.style, '{{orderNavigationRecoveryStyle}}')
  const css = fs.readFileSync(path.join(ROOT, 'miniapp-user/pages/order/create.wxss'), 'utf8')
  assert(/\.container\s*\{\s*padding:\s*16rpx 24rpx 240rpx;/.test(css), 'ordinary padding must remain unchanged')
  await f.page._createOrder(false); f.fail(); assert.strictEqual(pending.length, 1)
  pending[0]({ height: 212.6953125 })
  assert.strictEqual(f.page.data.orderNavigationRecoveryStyle, 'padding-bottom:calc(213px + 24rpx)')
  // 已亲看原生测量：390宽、备注底557.59375、栏顶540.3046875；增加的占位足以消除17.289px遮挡。
  const rpx = 390 / 750, addedPadding = 213 + 24 * rpx - 240 * rpx
  assert(557.59375 - addedPadding < 540.3046875)
  assert.deepStrictEqual(f.businessCounts(), [1, 1, 1])
})

test('窗口变窄或文字换行后重测，占位随真实高度增加而非固定大空白', async () => {
  const f = fixture(), pending = layoutQueries(f)
  await f.page._createOrder(false); f.fail(); pending[0]({ height: 212.6953125 })
  f.page.onResize(); assert.strictEqual(pending.length, 2)
  pending[1]({ height: 278.4 })
  assert.strictEqual(f.page.data.orderNavigationRecoveryStyle, 'padding-bottom:calc(279px + 24rpx)')
  pending[0]({ height: 212.6953125 })
  assert.strictEqual(f.page.data.orderNavigationRecoveryStyle, 'padding-bottom:calc(279px + 24rpx)')
  assert.strictEqual(f.nav.length, 1)
})

test('恢复态结束立即清掉动态占位，旧测量不能恢复空白；离页测量同样失效', async () => {
  const f = fixture(), pending = layoutQueries(f)
  await f.page._createOrder(false); f.fail(); pending[0]({ height: 213 })
  f.page.onResize(); f.page.onRetryOrderNavigation()
  assert.strictEqual(f.page.data.orderNavigationRecoveryStyle, '')
  pending[1]({ height: 280 }); assert.strictEqual(f.page.data.orderNavigationRecoveryStyle, '')
  f.fail(1); const last = pending[pending.length - 1]; f.page.onHide(); last({ height: 300 })
  assert.strictEqual(f.page.data.orderNavigationRecoveryStyle, '')
  assert.deepStrictEqual(f.businessCounts(), [1, 1, 1])
})

test('缺失或非法布局测量不伪造高度，也不影响原单恢复', async () => {
  const f = fixture(), pending = layoutQueries(f)
  await f.page._createOrder(false); f.fail()
  for (const rect of [null, {}, { height: 0 }, { height: NaN }, { height: -1 }]) pending[0](rect)
  assert.strictEqual(f.page.data.orderNavigationRecoveryStyle, '')
  assert(f.page.data.orderNavigationError.includes('订单号 777'))
  f.page.onRetryOrderNavigation(); f.sameOrder(1)
  assert.deepStrictEqual(f.businessCounts(), [1, 1, 1])
})

;(async () => {
  const done = armWatchdog(30000); let failed = 0
  try {
    for (const t of tests) {
      try { await t.run(); console.log('PASS ' + t.name) }
      catch (error) { failed++; console.log('FAIL ' + t.name + ': ' + error.stack) }
    }
  } finally { done() }
  if (!failed) console.log('AQUAFLOW_SUITE_OK ' + tests.length)
  process.exitCode = failed ? 1 : 0
})().catch(error => { console.error(error); process.exitCode = 1 })
