'use strict'
// Actual home/detail handlers; synthetic idempotent report service, storage and wx only.
const assert = require('assert'), path = require('path'), fs = require('fs')
const { loadPage, createWx, createApp, armWatchdog, ROOT } = require('./harness')
const { parseWxml, renderElements } = require('./wxml-tree')
const sourceRoot = process.env.WB08_BASELINE_ROOT || ROOT
const done = armWatchdog(30000), tests = [], test = (name, run) => tests.push({ name, run })
const tick = () => new Promise(resolve => setImmediate(resolve))
const event = id => ({ currentTarget: { dataset: { id } } })
function deferred() { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
function fixture(kind = 'detail', options = {}) {
  const wx = createWx(), modals = [], sheets = [], requests = [], reads = [], records = new Map()
  wx.showModal = value => modals.push(value); wx.showActionSheet = value => sheets.push(value)
  const app = createApp({ _loginGeneration: 1, globalData: { isLogin: true, accessToken: 'synthetic',
    userInfo: { role: 'DELIVERY', staffId: 8, stationId: 1, bindStatus: 'BOUND' } } })
  const order = { id: 20, status: 2, stationId: 1, deliveryStaffId: 8, paymentMethod: 2,
    paymentStatus: 1, needCollect: true, totalAmount: '48.25', items: [], statusText: '配送中' }
  const api = { getOrderDetail: async id => { reads.push(id); return { code: 0, data: { ...order, id } } },
    reportOrder: async (id, body) => {
      requests.push({ id, body: { ...body } })
      const accept = () => {
        const key = body.clientRequestId || 'legacy-' + requests.length
        if (!records.has(key)) records.set(key, { id: records.size + 70, orderId: Number(id), clientRequestId: body.clientRequestId })
        return { code: 0, data: records.get(key) }
      }
      return options.send ? options.send({ id, body, accept, number: requests.length }) : accept()
    } }
  for (const method of ['getAssignedToMe', 'getDeliveringOrders', 'getCompletedToday', 'getDeliveredUnpaid', 'getTransferList', 'getPendingOrders']) {
    api[method] = async () => { reads.push(method); return { code: 0, data: method === 'getDeliveringOrders' ? [order] : [] } }
  }
  delete require.cache[require.resolve(path.join(sourceRoot, 'miniapp-delivery/utils/delivery-problem.js'))]
  function pageOf(type, actor = app, id = 20) {
    const page = loadPage(path.join(sourceRoot, 'miniapp-delivery/pages/' + (type === 'detail' ? 'order/detail.js' : 'home/index.js')),
      { wx, app: actor, stubs: { 'api/delivery': api, 'utils/request': { get: async () => ({ code: 0, data: [] }) }, 'utils/tabbar': { syncTabBar() {} } } })
    if (type === 'detail') page.setData({ orderId: id, order: { ...order, id }, loading: false, hasMoreActions: true })
    else page.setData({ currentOrderId: id, deliveringOrders: [{ ...order, id }], deliveredUnpaidOrders: [], loading: false })
    return page
  }
  return { kind, page: pageOf(kind), pageOf, app, wx, modals, sheets, requests, reads, records, order }
}
function launch(t, page = t.page, kind = t.kind) { return kind === 'detail' ? page.onReport() : page.onReportProblem() }
async function submit(t, reasonIndex = 2, page = t.page, kind = t.kind) {
  const count = t.sheets.length, pending = launch(t, page, kind)
  await tick(); assert.equal(t.sheets.length, count + 1, 'new event needs a reason picker')
  t.sheets.at(-1).success({ tapIndex: reasonIndex }); await tick()
  assert.ok(t.modals.at(-1)); t.modals.at(-1).success({ confirm: true }); await tick()
  return { pending }
}
async function retry(t, page = t.page, kind = t.kind) {
  assert.equal(typeof page.onRetryReport, 'function', 'explicit original-report recovery entry required')
  const pickerCount = t.sheets.length, modalCount = t.modals.length
  const pending = kind === 'detail' ? page.onRetryReport() : page.onRetryReport(event(20))
  await tick(); assert.equal(t.sheets.length, pickerCount, 'original reason must not be reselected')
  assert.equal(t.modals.length, modalCount + 1, 'retry needs explicit confirmation')
  t.modals.at(-1).success({ confirm: true }); await tick()
  return { pending }
}
function recoveryKeys(t) { return Array.from(t.wx.__storage.keys()).filter(key => key.startsWith('aquaflow:order-write-recovery:')) }

for (const kind of ['detail', 'home']) {
  test(kind + ' saves stable event identity before dispatch and clears only matching receipt', async () => {
    let saved = false
    const t = fixture(kind, { send: ({ body, accept }) => {
      saved = Array.from(t.wx.__storage.values()).some(value => value && value.report && value.report.clientRequestId === body.clientRequestId)
      return accept()
    } })
    const attempt = await submit(t); await attempt.pending
    assert.equal(saved, true); assert.equal(t.records.size, 1)
    assert.match(t.requests[0].body.clientRequestId, /^DR-[\w-]{5,61}$/)
    assert.equal(t.requests[0].body.reasonKey, 'customer_refuse'); assert.equal(t.requests[0].body.reason, '客户拒收')
    assert.deepStrictEqual(recoveryKeys(t), [])
  })
  test(kind + ' new later event with identical order and reason gets a distinct identity', async () => {
    const t = fixture(kind), first = await submit(t); await first.pending
    const second = await submit(t); await second.pending
    assert.equal(t.records.size, 2); assert.notEqual(t.requests[0].body.clientRequestId, t.requests[1].body.clientRequestId)
  })
  test(kind + ' duplicate picker taps and in-flight reports issue one request', async () => {
    const d = deferred(), t = fixture(kind, { send: ({ accept }) => { const receipt = accept(); return d.promise.then(() => receipt) } })
    const first = launch(t), second = launch(t); await tick(); assert.equal(t.sheets.length, 1)
    t.sheets[0].success({ tapIndex: 0 }); await tick(); t.modals[0].success({ confirm: true }); await tick()
    const third = launch(t); await tick(); const count = t.requests.length
    d.resolve(); await Promise.all([first, second, third]); await tick()
    assert.equal(count, 1); assert.equal(t.records.size, 1)
  })
  test(kind + ' lost success recovers via exact original request without another record', async () => {
    const t = fixture(kind, { send: ({ number, accept }) => {
      const value = accept(); if (number === 1) throw Object.assign(Error('synthetic timeout'), { requestMayHaveOccurred: true }); return value
    } })
    const first = await submit(t); await first.pending
    assert.ok(recoveryKeys(t).length); assert.equal(t.records.size, 1)
    const original = { ...t.requests[0].body }, again = await retry(t); await again.pending
    assert.deepStrictEqual(t.requests[1].body, original); assert.equal(t.records.size, 1); assert.deepStrictEqual(recoveryKeys(t), [])
    assert.ok(t.wx.__calls.toast.some(value => value.title.includes('#70')))
  })
  test(kind + ' original unknown retry rejected later still keeps the original intent', async () => {
    const t = fixture(kind, { send: ({ number, accept }) => {
      if (number === 1) { accept(); throw Object.assign(Error('timeout'), { requestMayHaveOccurred: true }) }
      throw Object.assign(Error('当前无权操作'), { businessRejected: true })
    } })
    const first = await submit(t); await first.pending
    const again = await retry(t); await again.pending
    assert.ok(recoveryKeys(t).length); assert.equal(t.records.size, 1); assert.deepStrictEqual(t.requests[1].body, t.requests[0].body)
  })
  test(kind + ' definite first rejection permits a fresh event without pretending success', async () => {
    const t = fixture(kind, { send: ({ number, accept }) => {
      if (number === 1) throw Object.assign(Error('确定拒绝'), { businessRejected: true }); return accept()
    } })
    const first = await submit(t); await first.pending; assert.deepStrictEqual(recoveryKeys(t), [])
    assert.equal(t.records.size, 0); assert.ok(t.wx.__calls.toast.some(value => value.title === '确定拒绝'))
    const next = await submit(t); await next.pending
    assert.notEqual(t.requests[0].body.clientRequestId, t.requests[1].body.clientRequestId); assert.equal(t.records.size, 1)
  })
  for (const change of ['hide', 'identity', 'order']) test(kind + ' stale confirmation after ' + change + ' cannot submit', async () => {
    const t = fixture(kind), pending = launch(t); await tick()
    t.sheets[0].success({ tapIndex: 1 }); await tick()
    if (change === 'hide') t.page.onHide()
    else if (change === 'identity') t.app._loginGeneration++
    else t.page.setData(kind === 'detail' ? { orderId: 21 } : { currentOrderId: 21 })
    t.modals[0].success({ confirm: true }); await pending
    assert.equal(t.requests.length, 0); assert.deepStrictEqual(recoveryKeys(t), [])
  })
}

for (const malformed of [null, {}, { id: 71, orderId: 21 }, { id: true, orderId: 20 }, { id: 71, orderId: 20, clientRequestId: 'DR-other-key' }]) {
  test('incomplete or foreign receipt keeps original report ' + JSON.stringify(malformed), async () => {
    const t = fixture('detail', { send: () => ({ code: 0, data: malformed }) })
    const first = await submit(t); await first.pending
    assert.equal(t.page.data.writeUnknown, true); assert.ok(recoveryKeys(t).length)
    assert.ok(!t.wx.__calls.toast.some(value => value.title.startsWith('已记录反馈')))
  })
}
test('unknown report survives unload, ordinary token renewal and home/detail reentry', async () => {
  const t = fixture('detail', { send: ({ number, accept }) => { const value = accept(); if (number === 1) throw Object.assign(Error('timeout'), { requestMayHaveOccurred: true }); return value } })
  const first = await submit(t); await first.pending; t.page.onUnload(); t.app.globalData.accessToken = 'renewed'
  const home = t.pageOf('home'); await home.loadData(false)
  assert.equal(home.data.collectionRecoveryItems[0].canRetryReport, true)
  const again = await retry(t, home, 'home'); await again.pending
  assert.equal(t.records.size, 1); assert.deepStrictEqual(t.requests[1].body, t.requests[0].body)
})
test('persisted unknown report survives a fresh App object and same-identity login', async () => {
  const t = fixture('home', { send: ({ number, accept }) => { const value = accept(); if (number === 1) throw Object.assign(Error('timeout'), { requestMayHaveOccurred: true }); return value } })
  const first = await submit(t); await first.pending; t.page.onUnload()
  const app = createApp({ _loginGeneration: 9, globalData: { ...t.app.globalData, userInfo: { ...t.app.globalData.userInfo } } })
  const page = t.pageOf('detail', app); await page.loadOrderDetail(20)
  assert.equal(page.data.canRetryReport, true)
  const again = await retry(t, page, 'detail'); await again.pending; assert.equal(t.records.size, 1)
})
test('other staff never sees or resends original feedback', async () => {
  const t = fixture('detail', { send: ({ accept }) => { accept(); throw Object.assign(Error('timeout'), { requestMayHaveOccurred: true }) } })
  const first = await submit(t); await first.pending; t.app.globalData.userInfo.staffId = 9
  const home = t.pageOf('home'); await home.loadData(false)
  assert.equal(home.data.collectionRecoveryItems.length, 0); await home.onRetryReport(event(20))
  assert.equal(t.requests.length, 1)
})
test('in-flight storage removal cannot permit a second report from another page', async () => {
  const d = deferred(), t = fixture('home', { send: ({ accept }) => { accept(); return d.promise } })
  const first = await submit(t); t.wx.clearStorageSync(); t.page.onHide()
  const page = t.pageOf('detail'); await page.loadOrderDetail(20)
  assert.equal(page.data.writePending, true); await page.onReport(); assert.equal(t.requests.length, 1)
  d.reject(Object.assign(Error('timeout'), { requestMayHaveOccurred: true })); await first.pending
  await page.onVerifyOrderWrite(); assert.equal(page.data.canRetryReport, true); assert.equal(t.records.size, 1)
})
test('storage failure or silent storage loss prevents first dispatch', async () => {
  for (const broken of [() => { throw Error('unavailable') }, () => {}]) {
    const t = fixture('detail'); t.wx.setStorageSync = broken
    const first = await submit(t); await first.pending; assert.equal(t.requests.length, 0)
    assert.ok(t.wx.__calls.toast.some(value => value.title.includes('保存')))
  }
})
test('cancelled order projection cannot clear an unresolved feedback intent', async () => {
  const t = fixture('detail', { send: ({ accept }) => { accept(); throw Object.assign(Error('timeout'), { requestMayHaveOccurred: true }) } })
  const first = await submit(t); await first.pending; t.order.status = 5
  await t.page.onVerifyOrderWrite(); assert.equal(t.page.data.writeUnknown, true); assert.equal(t.page.data.canRetryReport, true)
})
test('legacy unknown report without original identity stays conservative', async () => {
  const t = fixture('detail'), recovery = require(path.join(sourceRoot, 'miniapp-delivery/utils/order-write-recovery.js'))
  const token = recovery.begin(20, 'onReport', t.order); recovery.finish(token, { requestMayHaveOccurred: true })
  await t.page.loadOrderDetail(20); assert.equal(t.page.data.canRetryReport, false)
  if (t.page.onRetryReport) await t.page.onRetryReport()
  assert.equal(t.requests.length, 0); assert.equal(t.page.data.writeUnknown, true)
})
test('financial refusal unknown has no ordinary-feedback retry permission', async () => {
  const t = fixture('detail'), recovery = require(path.join(sourceRoot, 'miniapp-delivery/utils/order-write-recovery.js'))
  const token = recovery.begin(20, 'onRefusalWriteOff', t.order); recovery.finish(token, { requestMayHaveOccurred: true })
  await t.page.loadOrderDetail(20); assert.equal(t.page.data.canRetryReport, false)
  if (t.page.onRetryReport) await t.page.onRetryReport()
  assert.equal(t.requests.length, 0); assert.equal(t.page.data.writeUnknown, true)
})
test('unpaid card calls existing receiver snapshot phone without profile lookup or navigation', () => {
  const t = fixture('home'), phones = []
  t.wx.makePhoneCall = options => phones.push(options.phoneNumber)
  t.page.setData({ deliveredUnpaidOrders: [{ ...t.order, status: 3,
    receiverPhone: 'synthetic-receiver-snapshot', customerPhone: 'synthetic-foreign-profile' }] })
  const source = fs.readFileSync(path.join(sourceRoot, 'miniapp-delivery/pages/home/index.wxml'), 'utf8')
  const elements = renderElements(parseWxml(source), t.page.data, { includeText: true })
  const phone = elements.find(value => value.attrs.catchtap === 'onCallCustomer' && value.text === 'synthetic-receiver-snapshot')
  assert.ok(phone, 'unpaid card needs its existing snapshot phone entry')
  const before = t.reads.length
  t.page.onCallCustomer({ currentTarget: { dataset: { phone: phone.text } } })
  assert.deepStrictEqual(phones, ['synthetic-receiver-snapshot']); assert.equal(t.reads.length, before)
  assert.equal(t.requests.length, 0); assert.equal(t.wx.__calls.nav.length, 0)
})
test('unpaid card without receiver snapshot never borrows a customer profile phone', () => {
  const t = fixture('home')
  t.page.setData({ deliveredUnpaidOrders: [{ ...t.order, status: 3, receiverPhone: null, customerPhone: 'synthetic-foreign-profile' }] })
  const source = fs.readFileSync(path.join(sourceRoot, 'miniapp-delivery/pages/home/index.wxml'), 'utf8')
  const elements = renderElements(parseWxml(source), t.page.data, { includeText: true })
  assert.ok(!elements.some(value => value.attrs.catchtap === 'onCallCustomer' && value.text === 'synthetic-foreign-profile'))
  assert.equal(t.reads.length, 0); assert.equal(t.requests.length, 0)
})
;(async () => {
  let failed = 0
  for (const entry of tests) { try { await entry.run(); console.log('PASS ' + entry.name) } catch (error) { failed++; console.error('FAIL ' + entry.name + ': ' + error.message) } }
  console.log('RESULT ' + (tests.length - failed) + '/' + tests.length)
  done(); if (failed) process.exitCode = 1; else console.log('AQUAFLOW_SUITE_OK ' + tests.length)
})().catch(error => { done(); console.error(error); process.exitCode = 1 })
