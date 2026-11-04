'use strict'
const assert = require('node:assert/strict')
const { loadPage, createWx, createApp, armWatchdog } = require('./harness')
const { selectedAddressStorage } = require('../../miniapp-user/utils/storage')
const { captureSession, beginSession } = require('../../miniapp-user/utils/token')
const done = armWatchdog(), tests = []
const test = (name, run) => tests.push({ name, run })
const flush = async () => { for (let i = 0; i < 20; i++) await Promise.resolve() }
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
const addressA = { id: 71, detail: '账号甲地址', isDefault: 1 }
const addressB = { id: 81, detail: '账号乙地址', isDefault: 1 }
const tap = item => ({ currentTarget: { dataset: { item } } })

function fixture() {
  const wx = createWx(), app = createApp({ globalData: { isLogin: true, customerId: 7, accessToken: 'account-a' } })
  const page = (path, stubs = {}) => loadPage('miniapp-user/pages/' + path + '.js', { wx, app, stubs })
  const switchAccount = id => {
    app.globalData.customerId = id
    app.globalData.accessToken = 'account-' + id
    app.globalData.isLogin = !!id
    beginSession()
  }
  const list = (read = async () => ({ data: [addressA] })) => page('address/list', { 'api/address': { getAddresses: read } })
  const home = () => {
    const p = page('home/index')
    p.checkStation = () => {}; p.checkNotifications = () => {}
    return p
  }
  const order = (read = async () => ({ data: [] })) => page('order/create', { 'api/address': { getAddresses: read } })
  return { wx, app, page, list, home, order, switchAccount }
}

test('选择地址按账号保存，首页和下单页同账号正常一次性接收', async () => {
  for (const target of ['home', 'order']) {
    const t = fixture(), list = t.list()
    list.onLoad({ from: target }); await list.loadAddresses(); list.onSelectAddress(tap(addressA))
    assert.equal(t.wx.getStorageSync('selectedAddress').customerId, '7')
    assert.equal(t.wx.__calls.nav.at(-1).type, 'navigateBack')
    const consumer = t[target](); consumer.onShow()
    assert.equal(consumer.data.address.id, addressA.id)
    assert.equal(t.wx.getStorageSync('selectedAddress'), '')
  }
})

test('未正常退出直接换号，首页和下单页都不会展示或使用上一账号选择', async () => {
  for (const target of ['home', 'order']) {
    const t = fixture(), list = t.list()
    list.onLoad({ from: target }); await list.loadAddresses(); list.onSelectAddress(tap(addressA))
    t.switchAccount(8)
    const consumer = t[target](), applied = [], setData = consumer.setData
    consumer.setData = patch => { if (patch.address) applied.push(patch.address); setData.call(consumer, patch) }
    consumer.onShow(); await flush()
    assert.equal(consumer.data.address, null)
    assert.deepEqual(applied, [], '旧地址即使短暂渲染也属于泄露')
    assert.equal(t.wx.getStorageSync('selectedAddress'), '')
  }
})

test('升级前无归属的裸地址不推断主人，两个消费页面均丢弃', async () => {
  for (const target of ['home', 'order']) {
    const t = fixture(), consumer = t[target]()
    t.wx.setStorageSync('selectedAddress', addressA)
    consumer.onShow(); await flush()
    assert.equal(consumer.data.address, null)
    assert.equal(t.wx.getStorageSync('selectedAddress'), '')
  }
})

test('冷启动仍为同一账号时保留待回写选择', async () => {
  const t = fixture(), list = t.list()
  list.onLoad({ from: 'home' }); await list.loadAddresses(); list.onSelectAddress(tap(addressA))
  beginSession()
  const home = t.home(); home.onShow()
  assert.equal(home.data.address.id, addressA.id)
})

test('账号切换后旧地址页的迟到列表和旧点击不能给新账号保存地址', async () => {
  const t = fixture(), late = deferred(), list = t.list(() => late.promise)
  list.onLoad({ from: 'home' }); const pending = list.loadAddresses()
  t.switchAccount(8); late.resolve({ data: [addressA] }); await pending
  list.onSelectAddress(tap(addressA))
  assert.equal(t.wx.getStorageSync('selectedAddress'), '')
  assert.equal(t.wx.__calls.nav.length, 0)
  assert.equal(list.data.accessDenied, true)
})

test('上一登录周期即使同客户重新登录也不能写入新的待选地址', () => {
  const t = fixture(); t.home()
  const old = captureSession(); t.switchAccount(7)
  assert.equal(selectedAddressStorage.set(addressA, old), false)
  assert.equal(t.wx.getStorageSync('selectedAddress'), '')
})

test('已存活下单页换号后立刻清旧地址，慢请求与读取失败不恢复旧值', async () => {
  const t = fixture(), late = deferred()
  let reads = 0
  const order = t.order(() => ++reads === 1 ? Promise.resolve({ data: [addressA] }) : late.promise)
  await order.loadAddress(); assert.equal(order.data.address.id, addressA.id)
  t.switchAccount(8); order.onShow()
  assert.equal(order.data.address, null); assert.equal(order.data.addressText, '')
  assert.equal(order.data.quoteReady, false)
  late.reject(Error('合成网络异常')); await flush()
  assert.equal(order.data.address, null); assert(order.data.addressLoadError)
})

test('旧账号的迟到地址回执不能覆盖新账号已经读取的地址', async () => {
  const t = fixture(), late = deferred()
  let reads = 0
  const order = t.order(() => ++reads === 1 ? late.promise : Promise.resolve({ data: [addressB] }))
  const pending = order.loadAddress()
  t.switchAccount(8); order.onShow(); await flush()
  assert.equal(order.data.address.id, addressB.id)
  late.resolve({ data: [addressA] }); await pending
  assert.equal(order.data.address.id, addressB.id)
})

test('退出为游客后首页立即清除内存中的收货地址', async () => {
  const t = fixture(), home = t.home()
  selectedAddressStorage.set(addressA); home.onShow(); assert.equal(home.data.address.id, addressA.id)
  t.switchAccount(null); home.onShow()
  assert.equal(home.data.address, null); assert.equal(home.data.addressHint, '点击设置配送地址')
})

;(async () => {
  let passed = 0
  for (const { name, run } of tests) { await run(); passed++; console.log('PASS ' + name) }
  console.log('AQUAFLOW_SUITE_OK ' + passed)
})().catch(e => { console.error(e); process.exitCode = 1 }).finally(done)
