const assert = require('assert'), fs = require('fs'), path = require('path')
const { loadPage, createWx, createApp, armWatchdog, ROOT } = require('./harness')
const { parseWxml, renderElements } = require('./wxml-tree')
const done = armWatchdog(30000)
const pagePath = 'miniapp-delivery/pages/station-mgmt/orders/index'
const tree = parseWxml(fs.readFileSync(path.join(ROOT, pagePath + '.wxml'), 'utf8'))
const own = extra => ({ id: 41, stationId: 11, deliveryStationId: 11, status: 1,
  statusText: '待配送', deliveryStaffId: null, receiverName: '很长的客户名称用于小屏检查',
  addressSnapshot: '很长的送达地址用于小屏换行检查', itemSummary: '纯净水 2桶，矿泉水 3瓶',
  totalAmount: 98, needCollect: true, collectAmount: 18, payStateText: '待收款',
  payMethodText: '水票抵水费 + 现金付其他费用', ...extra })
function fixture(read = async () => ({ data: [own()] })) {
  const wx = createWx(), app = createApp({ _loginGeneration: 1, globalData: {
    isLogin: true, userInfo: { staffId: 5, role: 'STATION_MANAGER', stationId: 11 } } })
  const requests = []
  const page = loadPage(pagePath + '.js', { wx, app, stubs: {
    'api/station-mgmt': { getOrders: q => { requests.push(q); return read(q) },
      getCrossStationOrders: async () => ({ data: { count: 1, amountTotal: 98, pending: 0,
        delivering: 1, completed: 0, orders: [own({ id: 52, stationId: 22, status: 3,
          statusText: '已送达', receiverName: '合成收货人' })] } }) },
    'utils/request': { get: async () => ({ data: [] }) }
  } })
  return { page, wx, requests }
}
const elements = data => renderElements(tree, data, { includeText: true })
let passed = 0, failed = 0
async function test(name, fn) {
  try { await fn(); passed++; console.log('PASS ' + name) }
  catch (e) { failed++; console.error('FAIL ' + name + ': ' + e.stack) }
}
;(async () => {
  await test('first read prioritizes pending orders; all history remains reachable', async () => {
    const t = fixture(); await t.page.loadData()
    assert.strictEqual(t.requests[0].status, 1)
    assert.strictEqual(t.page.data.tabs.at(-1).id, 0)
    await t.page.onTabChange({ currentTarget: { dataset: { id: 0 } } })
    assert.strictEqual(t.requests[1].status, null)
  })
  await test('loaded-page badge does not masquerade as a complete order count', async () => {
    const t = fixture(async () => ({ data: Array.from({ length: 20 }, (_, i) => own({ id: i + 1 })) }))
    await t.page.loadData()
    assert.strictEqual(t.page.data.hasMore, true)
    assert(elements(t.page.data).some(n => n.className === 'tab-badge' && n.text === '20+'))
  })
  await test('assignment hint never claims another station can be assigned locally', async () => {
    const t = fixture(async () => ({ data: [own(), own({ id: 42, deliveryStaffId: 7 }),
      own({ id: 43, deliveryStationId: 22 }), own({ id: 44, deliveryStationId: null }),
      own({ id: 45, status: 4, deliveryStaffId: null })] }))
    await t.page.loadData()
    assert.deepStrictEqual(t.page.data.list.map(r => r.assignmentText),
      ['尚未安排配送员', '已安排配送员', '', '', ''])
  })
  await test('payment facts separate onsite collection from order amount', async () => {
    const t = fixture(); await t.page.loadData()
    const rendered = elements(t.page.data).map(n => n.text || '').join('\n')
    assert(rendered.includes('付款：待收款'))
    assert(rendered.includes('水票抵水费 + 现金付其他费用'))
    assert(rendered.includes('订单金额'))
    assert(rendered.includes('98'))
    assert(rendered.includes('现场待收 ¥18'))
    assert(!rendered.includes('现场待收 ¥98'))
    assert(!rendered.includes('实收 ¥98'))
    assert(!rendered.includes('应收 ¥98'))
  })
  await test('no onsite collection amount is inferred from an order total', async () => {
    const t = fixture(async () => ({ data: [own({ needCollect: false, collectAmount: 98,
      payStateText: '已付款' }), own({ id: 42, needCollect: true, collectAmount: null }),
      own({ id: 43, needCollect: true, collectAmount: 0 })] }))
    await t.page.loadData()
    assert(!elements(t.page.data).some(n => n.className === 'order-collect'))
  })
  await test('action routes use workbench tab and collection page, not duplicate list actions', async () => {
    const t = fixture(); t.page.onOpenDispatchWork(); t.page.onOpenCollectionWork()
    assert.deepStrictEqual(t.wx.__calls.nav, [
      { type: 'switchTab', url: '/pages/coordination/index' },
      { type: 'navigateTo', url: '/pages/station-mgmt/payments/index' }
    ])
  })
  await test('current-station card precedes cross-station summary and cross count labels include delivered', async () => {
    const t = fixture(); await t.page.loadData()
    const rendered = elements(t.page.data)
    assert(rendered.findIndex(n => n.className === 'order-card') < rendered.findIndex(n =>
      (n.className || '').includes('cross-card')))
    const visible = rendered.map(n => n.text || '').join('\n')
    assert(visible.includes('配送中/已送达'))
    assert(visible.includes('订单金额合计'))
  })
  await test('own-list failure does not masquerade as an empty order list', async () => {
    const t = fixture(async () => { throw new Error('合成读取失败') }); await t.page.loadData()
    assert.strictEqual(t.page.data.listError, '合成读取失败')
    assert(!elements(t.page.data).some(n => n.className === 'empty'))
  })
  done()
  if (failed) process.exitCode = 1
  else console.log('AQUAFLOW_SUITE_OK ' + passed)
})().catch(e => { done(); console.error(e); process.exitCode = 1 })
