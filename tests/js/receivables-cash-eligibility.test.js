const assert = require('node:assert/strict'), fs = require('fs')
const { loadPage, createApp, createWx, ROOT, armWatchdog } = require('./harness')
const { parseWxml, renderElements } = require('./wxml-tree')
const done = armWatchdog(), tests = []
const test = (name, run) => tests.push({ name, run })
const event = id => ({ currentTarget: { dataset: { id } } })
const tick = () => new Promise(setImmediate)
const row = (orderId, extra = {}) => ({ orderId, customerId: 7, totalAmount: '10.00',
  status: 3, paymentStatus: 1, settlementStatus: 1, paymentMethod: 2,
  canRegisterCash: true, canViewOrder: true, collectionMethodText: '现金（货到付款）',
  collectionBlockReason: '', collectionNextStep: '实际收到现金后勾选登记。', ...extra })

async function fixture(rows, options = {}) {
  const wx = createWx(), posts = [], reads = [], customer = { customerId: 7, customerName: '渠道核对客户' }
  const app = createApp({ _loginGeneration: 1, globalData: { isLogin: true, accessToken: 'synthetic',
    userInfo: { staffId: 1, stationId: 11, role: 'STATION_MANAGER' } } })
  wx.showModal = modal => wx.__calls.modal.push(modal)
  const page = loadPage('miniapp-delivery/pages/station-mgmt/receivables/index.js', { wx, app, stubs: {
    'utils/request': { get: async url => {
      reads.push(url)
      return url.includes('/orders?')
        ? { data: JSON.parse(JSON.stringify(rows.filter(o => String(o.customerId) === url.split('customerId=')[1]))) }
        : { data: { customers: options.customers || [customer] } }
    },
    post: async (url, body) => { posts.push({ url, body }); return options.post
      ? options.post(url, body) : { data: { settledCount: body.orderIds.length, settledAmount: '17.00' } } } }
  } })
  page.onShow(); await tick(); await page.onPickCustomer(event(7))
  return { page, wx, posts, reads }
}

test('select all admits cash and ticket cash remainder only when the server explicitly permits them', async () => {
  const t = await fixture([row(101), row(102, { paymentMethod: 3, totalAmount: '7.00', collectionMethodText: '水票抵水费，其他费用现金到付' }),
    row(103, { paymentMethod: 1, canRegisterCash: false }), row(104, { canRegisterCash: false }),
    row(105, { canRegisterCash: undefined }), row(106, { canRegisterCash: 'true' })])
  t.page.onSelectAll()
  assert.deepEqual(t.page.data.orders.filter(o => o.checked).map(o => o.orderId), [101, 102])
  assert.equal(t.page.data.selectedCount, 2); assert.equal(t.page.data.selectedAmountText, '¥17.00')
  const work = t.page.onSettle(), modal = t.wx.__calls.modal.at(-1)
  assert(modal); assert(modal.content.includes('所选 2 单，合计 ¥17.00'))
  modal.success({ confirm: true }); await work
  assert.deepEqual(t.posts[0].body, { customerId: 7, orderIds: [101, 102] })
  assert.equal(t.page.data.settlementReceipt, '已登记 2 单 · ¥17.00')
})

test('blocked and unknown rows cannot toggle but their order detail remains available', async () => {
  const t = await fixture([row(101, { canRegisterCash: false }), row(102, { canRegisterCash: undefined })])
  t.page.onToggleOrder(event(101)); t.page.onToggleOrder(event(102))
  assert.equal(t.page.data.selectedCount, 0)
  t.page.onOpenOrder(event(101))
  assert.equal(t.wx.__calls.nav.at(-1).url, '/pages/order/detail?id=101')
  assert.equal(t.posts.length, 0)
})

test('cash registration receipt never follows the manager into another customer or login session', async () => {
  const t = await fixture([row(101)])
  t.page.setData({ settlementReceipt: '已登记 1 单 · ¥10.00' })
  t.page.onBack()
  assert.equal(t.page.data.settlementReceipt, '')
  t.page.setData({ customers: [{ customerId: 8, customerName: '另一客户' }],
    settlementReceipt: '已登记 1 单 · ¥10.00' })
  await t.page.onPickCustomer(event(8))
  assert.equal(t.page.data.customer.customerId, 8)
  assert.equal(t.page.data.settlementReceipt, '')
  t.page.setData({ settlementReceipt: '已登记 1 单 · ¥10.00' })
  t.page.onHide()
  const app = t.page._viewSession.app
  app._loginGeneration++
  app.globalData.userInfo.staffId = 2
  t.page.onShow()
  assert.equal(t.page.data.settlementReceipt, '')
})

test('late successful registration refreshes the overview without painting customer A receipt on customer B', async () => {
  let resolvePost
  const posted = new Promise(resolve => { resolvePost = resolve })
  const t = await fixture([row(101), row(202, { customerId: 8, totalAmount: '20.00' })], { customers: [
    { customerId: 7, customerName: '客户甲', outstandingAmount: '10.00' },
    { customerId: 8, customerName: '客户乙', outstandingAmount: '20.00' }
  ], post: () => posted })
  t.page.onSelectAll()
  const work = t.page.onSettle(), modal = t.wx.__calls.modal.at(-1)
  assert(modal)
  modal.success({ confirm: true })
  await tick()
  assert.equal(t.posts.length, 1, '甲的收款登记已经发出，之后不能假装撤销')
  t.page.onBack()
  await t.page.onPickCustomer(event(8))
  t.page.onSelectAll()
  assert.equal(t.page.data.selectedCount, 1)
  const overviewReads = t.reads.filter(url => url === '/api/manager/receivables').length
  resolvePost({ data: { settledCount: 1, settledAmount: '10.00' } })
  await work
  assert.equal(t.page.data.customer.customerId, 8)
  assert.equal(t.page.data.settlementReceipt, '')
  assert.equal(t.page.data.selectedCount, 1, '甲的回调不能清掉乙已勾选的订单')
  assert.equal(t.page.data.orders[0].orderId, 202)
  assert.equal(t.page.data.orders[0].checked, true)
  assert.equal(t.reads.filter(url => url === '/api/manager/receivables').length, overviewReads + 1,
    '迟到的成功仍须只读刷新总台账')
  assert.equal(t.reads.filter(url => url.includes('/orders?customerId=8')).length, 1,
    '甲的回调不能替乙重读明细')
})

test('unpaid WeChat preserves the customer payment instruction without an inaccessible employee order link', async () => {
  const instruction = '请客户从原订单核对付款进度，收到渠道结果后重新加载明细；已付款请勿重复支付。'
  const t = await fixture([row(101, { status: 1, paymentMethod: 1, canRegisterCash: false, canViewOrder: false,
    collectionMethodText: '微信支付', collectionNextStep: instruction })])
  const nodes = renderElements(parseWxml(fs.readFileSync(ROOT + '/miniapp-delivery/pages/station-mgmt/receivables/index.wxml', 'utf8')),
    t.page.data, { includeText: true })
  assert(nodes.some(n => n.text === instruction))
  assert(!nodes.some(n => n.className === 'order-detail-link'))
  t.page.onOpenOrder(event(101)); assert.equal(t.wx.__calls.nav.length, 0)
  t.page.onToggleOrder(event(101)); assert.equal(t.page.data.selectedCount, 0); assert.equal(t.posts.length, 0)
})

test('different delivery station can prevent detail navigation without removing legitimate cash registration', async () => {
  const instruction = '订单由其他水站履约，请联系履约站核实详情；已实际收到现金可按原收款安排登记。'
  const t = await fixture([row(101, { canViewOrder: false, collectionNextStep: instruction })])
  t.page.onOpenOrder(event(101)); assert.equal(t.wx.__calls.nav.length, 0)
  assert.equal(t.page.data.orders[0].cashSelectable, true); assert.equal(t.page.data.orders[0].collectionNextStep, instruction)
  t.page.onToggleOrder(event(101)); assert.equal(t.page.data.selectedCount, 1)
  const work = t.page.onSettle(), modal = t.wx.__calls.modal.at(-1)
  assert(modal); modal.success({ confirm: true }); await work
  assert.deepEqual(t.posts[0].body, { customerId: 7, orderIds: [101] })
})

test('missing or non-boolean order access never creates a detail link or a fabricated detail instruction', async () => {
  for (const canViewOrder of [undefined, null, 'true', 1]) {
    const t = await fixture([row(101, { canViewOrder, collectionNextStep: undefined })])
    t.page.onOpenOrder(event(101)); assert.equal(t.wx.__calls.nav.length, 0)
    assert.equal(t.page.data.orders[0].canViewOrder, false)
    assert(!t.page.data.orders[0].collectionNextStep.includes('订单详情'))
    t.page.onToggleOrder(event(101)); assert.equal(t.page.data.selectedCount, 1)
  }
})

test('order access changed after selection is rechecked at navigation without clearing cash selection', async () => {
  const t = await fixture([row(101)])
  t.page.onToggleOrder(event(101)); assert.equal(t.page.data.selectedCount, 1)
  t.page.data.orders[0].canViewOrder = false
  t.page.onOpenOrder(event(101)); assert.equal(t.wx.__calls.nav.length, 0)
  assert.equal(t.page.data.selectedCount, 1); assert.equal(t.page.data.orders[0].checked, true)
  assert.equal(t.posts.length, 0)
})

test('an injected checked online row cannot reach confirmation or registration', async () => {
  const t = await fixture([row(101, { paymentMethod: 1, canRegisterCash: false })])
  t.page.data.orders[0].checked = true
  const work = t.page.onSettle()
  // Resolve a wrongly opened old confirmation before asserting, so a red run cannot hang.
  if (t.wx.__calls.modal.length) t.wx.__calls.modal.at(-1).success({ confirm: true })
  await work
  assert.equal(t.wx.__calls.modal.length, 0); assert.equal(t.posts.length, 0)
})

test('an open confirmation becomes invalid when cash eligibility changes', async () => {
  const t = await fixture([row(101)])
  t.page.onSelectAll(); const work = t.page.onSettle(), modal = t.wx.__calls.modal.at(-1)
  assert(modal); t.page.data.orders[0].canRegisterCash = false
  modal.success({ confirm: true }); await work
  assert.equal(t.posts.length, 0)
})

test('each blocked row exposes the original channel and actual next step, with detail only when permitted', async () => {
  const t = await fixture([row(101), row(102, { paymentMethod: 1, canRegisterCash: false,
    canViewOrder: false,
    collectionMethodText: '微信支付', collectionBlockReason: '原付款尚待渠道确认，不能登记为现金。',
    collectionNextStep: '请客户核对原订单付款结果，再重新加载明细。' })])
  const nodes = renderElements(parseWxml(fs.readFileSync(ROOT + '/miniapp-delivery/pages/station-mgmt/receivables/index.wxml', 'utf8')),
    t.page.data, { includeText: true })
  const text = nodes.map(n => n.text || '').join('\n')
  for (const expected of ['微信支付', '原付款尚待渠道确认，不能登记为现金。', '请客户核对原订单付款结果，再重新加载明细。', '可登记现金', '暂不可登记现金']) assert(text.includes(expected), expected)
  assert.equal(nodes.filter(n => n.attrs.catchtap === 'onToggleOrder').length, 1)
  assert.equal(nodes.filter(n => n.attrs.bindtap === 'onOpenOrder').length, 2)
  assert.equal(nodes.filter(n => n.className === 'order-detail-link').length, 1)
})

;(async () => {
  let failed = 0
  for (const { name, run } of tests) {
    try { await run(); console.log('PASS ' + name) }
    catch (error) { failed++; console.error('FAIL ' + name + '\n' + error.stack) }
  }
  if (failed) process.exitCode = 1
  else console.log('AQUAFLOW_SUITE_OK ' + tests.length)
})().finally(done)
