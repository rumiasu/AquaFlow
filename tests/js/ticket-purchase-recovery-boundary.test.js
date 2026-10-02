/** 真实页面及会话工具，API/微信替身；不连真实收款渠道。 */
const assert = require('assert')
const { loadPage, createWx, createApp, armWatchdog } = require('./harness')
const { STORAGE_KEYS: K } = require('../../miniapp-user/utils/storage-keys')
const { beginSession } = require('../../miniapp-user/utils/token')
const tests = [], test = (name, run) => tests.push({ name, run })
const key = K.TICKET_PURCHASE_INTENT + '7'
const body = { stationId: 1, productId: 5, quantity: 3, paymentMethod: 1, packageId: null, unifiedQty: null, idempotencyKey: 'original' }
const payment = (extra = {}) => ({ ...body, paymentId: 900, status: 1, amount: 24, ...extra })
const intent = (extra = {}) => ({ customerId: 7, body: { ...body }, productName: 'Water', stationName: 'Station', ...extra })
function setup(sc = {}) {
  const wx = sc.wx || createWx(), app = sc.app || createApp(), calls = { purchase: [], close: [] }
  const page = loadPage('miniapp-user/pages/ticket/index.js', { wx, app, stubs: {
    'api/ticket': {
      getTicketAccounts: async () => ({ code: 0, data: [] }), getTicketRecords: async () => ({ code: 0, data: [] }),
      getTicketPackages: async () => ({ code: 0, data: [] }), getTicketPurchaseResult: sc.query || (async () => ({ code: 0, data: null })),
      purchaseTicket: async b => { calls.purchase.push({ ...b }); return sc.purchase ? sc.purchase(b) : { code: 0, data: payment() } },
      closeTicketPurchaseIntent: async k => { calls.close.push(k); return sc.close ? sc.close(k) : { code: 0, data: { closed: true, idempotencyKey: k, payment: null } } }
    },
    'api/barrel': { getBarrelSummaryByType: async () => ({ code: 0, data: [] }) },
    'api/product': { getStationProducts: async () => ({ code: 0, data: [] }) },
    'api/station': { getPublicStations: async () => ({ code: 0, data: [] }) }
  } })
  page.data.currentStationId = 1
  Object.assign(page.data.buyForm, { productId: 5, productName: 'Water', quantity: 3, faceValue: 8 })
  return { page, wx, app, calls }
}
test('明确业务拒绝不会清键；获得封锁回执后可开始新意图', async () => {
  const t = setup({ purchase: async () => { throw new Error('该商品当前没有启用水票') } })
  await t.page.onBuySubmit(); const saved = t.page.data.pendingPurchase
  assert(saved); await t.page.onQueryPurchaseResult(); t.page.onStartNextPurchase()
  assert.strictEqual(t.page.data.pendingPurchase.body.idempotencyKey, saved.body.idempotencyKey)
  await t.page.closeOriginalPurchase(saved)
  assert.strictEqual(t.page.data.pendingPurchase, null)
  assert.strictEqual(t.calls.close[0], saved.body.idempotencyKey)
  await t.page.onBuySubmit()
  assert.notStrictEqual(t.calls.purchase[0].idempotencyKey, t.calls.purchase[1].idempotencyKey)
})
for (const status of [1, 2, 3, 4]) {
  test('已有原款状态' + status + '不能被结束为无款', async () => {
    const t = setup({ close: async k => ({ code: 0, data: { closed: false, idempotencyKey: k, payment: payment({ status }) } }) })
    const saved = intent(); t.wx.setStorageSync(key, saved); t.page.restorePurchaseIntent()
    await t.page.closeOriginalPurchase(saved)
    assert.strictEqual(t.calls.purchase.length, 0)
    if (status === 2) assert.strictEqual(t.page.data.pendingPurchase, null)
    else assert.strictEqual(t.page.data.pendingPurchase.result.paymentId, 900)
    assert(!t.wx.__calls.toast.some(x => x.title === '原购买已结束，可重新选择'))
  })
}
for (const receipt of [null, {}, { closed: true, idempotencyKey: 'other' }, { closed: 'true', idempotencyKey: 'original' },
  { closed: true, idempotencyKey: 'original', payment: payment() }]) {
  test('不完整或矛盾关闭回执不能清原记录 ' + JSON.stringify(receipt), async () => {
    const t = setup({ close: async () => ({ code: 0, data: receipt }) }), saved = intent()
    t.wx.setStorageSync(key, saved); await t.page.closeOriginalPurchase(saved)
    assert.strictEqual(t.wx.getStorageSync(key).body.idempotencyKey, 'original')
    assert(t.page.data.pendingPurchase)
  })
}
test('终结丢响应仍保留原键，重试同键终结可恢复', async () => {
  let n = 0
  const t = setup({ close: async k => { if (!n++) throw new Error('time out'); return { code: 0, data: { closed: true, idempotencyKey: k } } } })
  const saved = intent(); t.wx.setStorageSync(key, saved)
  await t.page.closeOriginalPurchase(saved); assert(t.page.data.pendingPurchase)
  await t.page.closeOriginalPurchase(saved); assert.strictEqual(t.page.data.pendingPurchase, null)
  assert.deepStrictEqual(t.calls.close, ['original', 'original'])
})
for (const result of [{}, { paymentId: 1 }, payment({ paymentId: 0 }), payment({ amount: '' }), payment({ amount: -1 }),
  payment({ status: 99 }), payment({ stationId: 2 }), payment({ unifiedQty: 3 }), payment({ stationId: [1] }), payment({ status: true })]) {
  test('损坏原款结果阻止另买/重试 ' + JSON.stringify(result), async () => {
    const t = setup(), saved = intent({ result }); t.wx.setStorageSync(key, saved)
    t.page.restorePurchaseIntent(); assert(t.page.data.purchaseStorageError)
    t.page.onStartNextPurchase(); await t.page.onBuySubmit(); await t.page.onRetryOriginalPurchase()
    assert.strictEqual(t.calls.purchase.length, 0)
    assert.strictEqual(t.wx.getStorageSync(key).body.idempotencyKey, 'original')
  })
}
for (const changed of [{ quantity: -1 }, { stationId: 0 }, { productId: null }, { paymentMethod: 3 },
  { packageId: 2, unifiedQty: 3 }, { unifiedQty: 4 }, { idempotencyKey: '' }, { quantity: true }, { stationId: [1] }]) {
  test('损坏原请求阻止覆盖 ' + JSON.stringify(changed), async () => {
    const t = setup(); t.wx.setStorageSync(key, intent({ body: { ...body, ...changed } }))
    await t.page.onBuySubmit(); assert(t.page.data.purchaseStorageError); assert.strictEqual(t.calls.purchase.length, 0)
  })
}
test('保存无异常但未生效，读回校验失败不能发送', async () => {
  const t = setup(); t.wx.setStorageSync = () => {}
  await t.page.onBuySubmit()
  assert.strictEqual(t.calls.purchase.length, 0); assert(t.page.data.purchaseStorageError); assert(t.page.data.pendingPurchase)
})
for (const mode of ['throw', 'noop', 'delete-then-throw']) {
  test('删除' + mode + '后保留原凭据，不能另买', async () => {
    const t = setup(), saved = intent(); t.wx.setStorageSync(key, saved)
    const remove = t.wx.removeStorageSync
    t.wx.removeStorageSync = k => { if (mode === 'delete-then-throw') remove(k); if (mode !== 'noop') throw new Error('storage failed') }
    await t.page.closeOriginalPurchase(saved)
    assert(t.page.data.pendingPurchase); assert(t.page.data.purchaseStorageError)
    t.page.restorePurchaseIntent(); assert(t.page.data.pendingPurchase)
    assert.strictEqual(t.wx.getStorageSync(key).body.idempotencyKey, 'original')
    await t.page.onBuySubmit(); assert(t.calls.purchase.every(x => x.idempotencyKey === 'original'))
  })
}
test('待收款保存结果失败仍保留原请求，无另买出口', async () => {
  const t = setup(), saved = intent(); t.wx.setStorageSync(key, saved)
  const set = t.wx.setStorageSync; t.wx.setStorageSync = (k, v) => { if (v.result) throw new Error('storage failed'); set(k, v) }
  assert.throws(() => t.page.applyPurchaseResult(saved, payment()))
  assert(t.page.data.purchaseStorageError); t.page.onStartNextPurchase()
  assert.strictEqual(t.wx.getStorageSync(key).body.idempotencyKey, 'original')
})
test('关闭响应A到B到A，旧周期不能清原购买', async () => {
  let resolve
  const t = setup({ close: () => new Promise(r => { resolve = r }) }), saved = intent()
  t.wx.setStorageSync(key, saved); const pending = t.page.closeOriginalPurchase(saved)
  beginSession(); t.app.globalData.customerId = 8
  beginSession(); t.app.globalData.customerId = 7
  resolve({ code: 0, data: { closed: true, idempotencyKey: 'original' } }); await pending
  assert(t.wx.getStorageSync(key)); assert(t.page.data.pendingPurchase)
})
test('关闭确认弹窗后同客户退出重登，不发送终结', async () => {
  const t = setup(); t.wx.setStorageSync(key, intent()); t.wx.showModal = o => t.wx.__calls.modal.push(o)
  t.page.onCloseOriginalPurchase(); beginSession(); t.wx.__calls.modal[0].success({ confirm: true })
  assert.strictEqual(t.calls.close.length, 0)
})
test('不同客户的删除失败凭据分别保留，A到B到A不覆盖', async () => {
  const t = setup(), savedA = intent(), savedB = intent({ customerId: 8, body: { ...body, idempotencyKey: 'original-B' } })
  t.page.holdPurchaseStorageError(savedA)
  t.app.globalData.customerId = 8; t.page.holdPurchaseStorageError(savedB); t.page.restorePurchaseIntent()
  t.app.globalData.customerId = 7; t.page.restorePurchaseIntent()
  assert.strictEqual(t.wx.getStorageSync(key).body.idempotencyKey, 'original')
  assert.strictEqual(t.wx.getStorageSync(K.TICKET_PURCHASE_INTENT + '8').body.idempotencyKey, 'original-B')
})
test('形状完整的缓存原款未查到时仍不能另买，查询后降回原键待核实', async () => {
  const t = setup(), saved = intent({ result: payment() }); t.wx.setStorageSync(key, saved)
  await t.page.onStartNextPurchase()
  assert(t.page.data.pendingPurchase); assert.strictEqual(t.wx.getStorageSync(key).body.idempotencyKey, 'original')
  await t.page.onQueryPurchaseResult()
  assert.strictEqual(t.page.data.pendingPurchase.result, undefined)
  assert.strictEqual(t.page.data.pendingPurchase.body.idempotencyKey, 'original')
})
;(async () => {
  const done = armWatchdog(30000); let passed = 0
  for (const t of tests) { await t.run(); passed++; console.log('PASS ' + t.name) }
  done(); console.log('AQUAFLOW_SUITE_OK ' + passed)
})().catch(error => { console.error(error); process.exitCode = 1 })
