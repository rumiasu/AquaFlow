const assert = require('assert')
const fs = require('fs')
const path = require('path')
const { loadPage, createWx, createApp, armWatchdog, ROOT } = require('./harness')

const done = armWatchdog()
let passed = 0
async function test(name, fn) {
  await fn()
  passed++
  console.log('  ✓ ' + name)
}

;(async () => {
  let getMode = 'fail'
  let settleCalls = 0
  const wx = createWx()
  const page = loadPage('miniapp-delivery/pages/station-mgmt/receivables/index.js', {
    wx,
    app: createApp(),
    stubs: {
      'utils/request': {
        get: async (url) => {
          if (getMode === 'fail') throw new Error('network down')
          if (url.includes('/orders?')) return { data: [] }
          return { data: { customers: [], outstandingAmount: 0 } }
        },
        post: async () => { settleCalls++; return { data: {} } },
        put: async () => ({ data: {} })
      }
    }
  })
  await test('首次总览请求失败保留常驻错误，不伪装成已结清', async () => {
    await page.load()
    assert.strictEqual(page.data.loading, false)
    assert.match(page.data.loadError, /network down/)
    assert.strictEqual(page.data.overview, null)
  })
  await test('真实空响应与加载失败可区分，重试能恢复', async () => {
    getMode = 'ok'
    await page.onRetryLoad()
    assert.strictEqual(page.data.loadError, '')
    assert.deepStrictEqual(page.data.customers, [])
    assert.ok(page.data.overview)
  })
  await test('订单明细失败时不允许用陈旧勾选数据登记收款', async () => {
    page.setData({ customer: { customerId: 3 }, orders: [{ orderId: 11, checked: true }], selectedCount: 1 })
    getMode = 'fail'
    await page.loadOrders()
    await page.onSettle()
    assert.match(page.data.ordersError, /network down/)
    assert.strictEqual(settleCalls, 0)
  })

  const orderJs = fs.readFileSync(path.join(ROOT, 'miniapp-user/pages/order/detail.js'), 'utf8')
  const orderWxml = fs.readFileSync(path.join(ROOT, 'miniapp-user/pages/order/detail.wxml'), 'utf8')
  const ordersXml = fs.readFileSync(path.join(ROOT, 'AquaFlow-backend/src/main/resources/mapper/OrderMapper.xml'), 'utf8')
  const barrelMapper = fs.readFileSync(path.join(ROOT, 'AquaFlow-backend/src/main/java/com/example/aquaflow/mapper/BarrelRecordMapper.java'), 'utf8')
  const dispatchWxml = fs.readFileSync(path.join(ROOT, 'miniapp-delivery/pages/coordination/index.wxml'), 'utf8')
  const stationOrdersWxml = fs.readFileSync(path.join(ROOT, 'miniapp-delivery/pages/station-mgmt/orders/index.wxml'), 'utf8')
  const orderCardJs = fs.readFileSync(path.join(ROOT, 'miniapp-user/components/OrderCard/index.js'), 'utf8')
  const serviceJs = fs.readFileSync(path.join(ROOT, 'miniapp-user/pages/service/index.js'), 'utf8')
  const serviceWxml = fs.readFileSync(path.join(ROOT, 'miniapp-user/pages/service/index.wxml'), 'utf8')
  await test('客户详情不再由送出减回收推算欠桶，也不展示员工内部备注', async () => {
    assert.ok(!orderJs.includes('pendingUnreturned'))
    assert.ok(orderWxml.includes('order.customerNote'))
    assert.ok(!orderWxml.includes('{{order.specialNote}}'))
    assert.ok(barrelMapper.includes('and type = 8'))
  })
  await test('新订单备注有独立列且下单 SQL 同时写入', async () => {
    assert.ok(ordersXml.includes('special_note, customer_note'))
    assert.ok(ordersXml.includes('#{specialNote}, #{customerNote}'))
  })
  await test('取消文案不承诺自动退款，站长台账商品使用逐项摘要', async () => {
    assert.ok(!orderJs.includes('自动释放库存、退水票、退款'))
    assert.ok(!orderCardJs.includes('取消后将自动退款'))
    assert.ok(stationOrdersWxml.includes('item.itemSummary'))
    assert.ok(!stationOrdersWxml.includes('item.firstProductName'))
  })
  await test('调度页只保留一个 pending 查单入口且 handler 仍存在', async () => {
    assert.strictEqual((dispatchWxml.match(/bindtap="onOpenOrders"/g) || []).length, 1)
    assert.ok(dispatchWxml.includes('queue-head'))
  })
  await test('反馈分类用面向顾客的中文原文提交，不发送 bug/ui 等开发分类', async () => {
    assert.ok(serviceJs.includes("category: '配送服务'"))
    assert.ok(serviceWxml.includes("data-category=\"商品与水\""))
    assert.ok(serviceWxml.includes("data-category=\"水桶押金\""))
    assert.ok(!serviceWxml.includes("data-category=\"ui\""))
  })

  console.log(`\n全部通过：${passed} 项（应收失败恢复与订单事实契约）`)
  console.log(`AQUAFLOW_SUITE_OK ${passed}`)
  done()
})().catch((err) => {
  console.error(err)
  done()
  process.exitCode = 1
})
