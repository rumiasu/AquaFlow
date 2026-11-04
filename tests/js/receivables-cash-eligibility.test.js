const assert = require('node:assert/strict'), fs = require('fs')
const { loadPage, createApp, createWx, ROOT, armWatchdog } = require('./harness')
const { parseWxml, renderElements } = require('./wxml-tree')
const done = armWatchdog(), tests = []
const test = (name, run) => tests.push({ name, run })
const event = id => ({ currentTarget: { dataset: { id } } })
const tick = () => new Promise(setImmediate)
const row = (orderId, extra = {}) => ({ orderId, customerId: 7, totalAmount: '10.00',
  status: 3, paymentStatus: 1, settlementStatus: 1, paymentMethod: 2,
  canRegisterCash: true, collectionMethodText: '现金（货到付款）',
  collectionBlockReason: '', collectionNextStep: '实际收到现金后勾选登记。', ...extra })

async function fixture(rows) {
  const wx = createWx(), posts = [], customer = { customerId: 7, customerName: '渠道核对客户' }
  const app = createApp({ _loginGeneration: 1, globalData: { isLogin: true, accessToken: 'synthetic',
    userInfo: { staffId: 1, stationId: 11, role: 'STATION_MANAGER' } } })
  wx.showModal = modal => wx.__calls.modal.push(modal)
  const page = loadPage('miniapp-delivery/pages/station-mgmt/receivables/index.js', { wx, app, stubs: {
    'utils/request': { get: async url => url.includes('/orders?')
      ? { data: JSON.parse(JSON.stringify(rows)) } : { data: { customers: [customer] } },
    post: async (url, body) => { posts.push({ url, body }); return { data: { settledCount: body.orderIds.length, settledAmount: '17.00' } } } }
  } })
  page.onShow(); await tick(); await page.onPickCustomer(event(7))
  return { page, wx, posts }
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
})

test('blocked and unknown rows cannot toggle but their order detail remains available', async () => {
  const t = await fixture([row(101, { canRegisterCash: false }), row(102, { canRegisterCash: undefined })])
  t.page.onToggleOrder(event(101)); t.page.onToggleOrder(event(102))
  assert.equal(t.page.data.selectedCount, 0)
  t.page.onOpenOrder(event(101))
  assert.equal(t.wx.__calls.nav.at(-1).url, '/pages/order/detail?id=101')
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

test('each blocked row exposes the original channel, specific reason and next step without hiding detail', async () => {
  const t = await fixture([row(101), row(102, { paymentMethod: 1, canRegisterCash: false,
    collectionMethodText: '微信支付', collectionBlockReason: '原付款尚待渠道确认，不能登记为现金。',
    collectionNextStep: '查看订单详情核对原付款结果。' })])
  const nodes = renderElements(parseWxml(fs.readFileSync(ROOT + '/miniapp-delivery/pages/station-mgmt/receivables/index.wxml', 'utf8')),
    t.page.data, { includeText: true })
  const text = nodes.map(n => n.text || '').join('\n')
  for (const expected of ['微信支付', '原付款尚待渠道确认，不能登记为现金。', '查看订单详情核对原付款结果。', '可登记现金', '暂不可登记现金']) assert(text.includes(expected), expected)
  assert.equal(nodes.filter(n => n.attrs.catchtap === 'onToggleOrder').length, 1)
  assert.equal(nodes.filter(n => n.attrs.bindtap === 'onOpenOrder').length, 2)
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
