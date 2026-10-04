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
  const orderWxss = fs.readFileSync(path.join(ROOT, 'miniapp-user/pages/order/detail.wxss'), 'utf8')
  const collectionCondition = orderWxml.match(/class="collect-banner" wx:if="\{\{([^}]+)\}\}"/)[1]
  const collectionVisible = data => new Function('order', 'deliveredAwaitingCollection', `return (${collectionCondition})`)(data.order, data.deliveredAwaitingCollection)
  const statusBorder = status => orderWxss.match(new RegExp(`\\.status-card\\.status-${status}\\s*\\{([^}]+)\\}`))[1]
  let detailResponse
  let detailMutationCalls = 0
  const detail = loadPage('miniapp-user/pages/order/detail.js', {
    wx: createWx(),
    stubs: {
      'api/order': {
        getOrderDetail: async () => ({ data: detailResponse }),
        cancelOrder: async () => { detailMutationCalls++ },
        createPayment: async () => { detailMutationCalls++ }
      },
      'api/orderImage': { getOrderImages: async () => ({ data: [] }) },
      'api/product': { getProductDetail: async () => ({ data: {} }) },
      'api/station': { getStationPublicPhone: async () => ({ data: {} }) }
    }
  })
  async function showDetail(fields) {
    detailResponse = Object.assign({ id: 71, items: [], canCancel: false, canRepay: false, repayLabel: '去支付' }, fields)
    const original = JSON.parse(JSON.stringify(detailResponse))
    await detail.loadOrder(71)
    assert.deepStrictEqual(detail.data.order, original, '展示转换不能改写原始订单')
    assert.strictEqual(detail.data.statusText, original.statusText)
    assert.strictEqual(detail.data.payStatusText, original.payStateText)
    assert.strictEqual(detail.data.canCancel, !!original.canCancel)
    assert.strictEqual(detail.data.canRepay, !!original.canRepay)
    assert.strictEqual(detail.data.repayLabel, original.repayLabel)
    assert.strictEqual(detailMutationCalls, 0, '查看状态不能触发收款、取消或支付')
    return detail.data
  }
  await test('已送达待收款显示横幅与收款核对说明，不再提示送达时收款', async () => {
    const data = await showDetail({ status: 3, statusText: '已送达', paymentStatus: 1, payState: 'PENDING', payStateText: '待收款', needCollect: true, canCancel: true, payHint: '货到付款，配送员送达时收款' })
    assert.strictEqual(collectionVisible(data), true)
    assert.strictEqual(data.deliveredAwaitingCollection, true)
    assert.ok(orderWxml.includes('已送达，待确认收款'))
    assert.match(data.payHint, /已送达.*待确认收款/)
    assert.match(data.payHint, /配送员或水站.*核对/)
    assert.ok(!data.payHint.includes('送达时收款'))
    assert.strictEqual(data.payStatusClass, 'warning')
  })
  await test('已完成已付款不提示收款，完成卡片使用成功色', async () => {
    const data = await showDetail({ status: 4, statusText: '已完成', paymentStatus: 2, payState: 'PAID', payStateText: '已付款', needCollect: false, payHint: '已付款' })
    assert.strictEqual(collectionVisible(data), false)
    assert.strictEqual(data.deliveredAwaitingCollection, false)
    assert.strictEqual(data.payHint, '已付款')
    assert.strictEqual(data.payStatusClass, 'success')
    assert.ok(statusBorder(4).includes('var(--success-color)'))
  })
  await test('已取消退款文案与权限原样保留，关闭卡片使用中性色', async () => {
    const data = await showDetail({ status: 5, statusText: '已取消', paymentStatus: 3, payState: 'REFUNDED', payStateText: '已退款', needCollect: true, payHint: '订单已取消' })
    assert.strictEqual(collectionVisible(data), false)
    assert.strictEqual(data.deliveredAwaitingCollection, false)
    assert.strictEqual(data.payHint, '订单已取消')
    assert.strictEqual(data.payStatusText, '已退款')
    assert.strictEqual(data.payStatusClass, 'default')
    assert.ok(statusBorder(5).includes('var(--text-secondary)'))
    assert.ok(!statusBorder(5).includes('var(--success-color)'))
    assert.ok(orderWxml.includes('wx:if="{{order.status === 5}}" class="btn-action btn-reorder"'))
  })
  await test('已付款待配送仍展示待配送与已付款，不误显示收款横幅', async () => {
    const data = await showDetail({ status: 1, statusText: '待配送', paymentStatus: 2, payState: 'PAID', payStateText: '已付款', needCollect: false, canCancel: true, payHint: '已付款' })
    assert.strictEqual(collectionVisible(data), false)
    assert.strictEqual(data.deliveredAwaitingCollection, false)
    assert.strictEqual(data.payHint, '已付款')
    assert.strictEqual(data.payStatusClass, 'success')
    assert.ok(statusBorder(1).includes('var(--warning-color)'))
  })
  await test('已送达的退款或支付取消终态不因 needCollect 被误报待收款', async () => {
    for (const [payState, paymentStatus, text] of [['REFUNDED', 3, '已退款'], ['CANCELLED', 4, '支付已取消']]) {
      const data = await showDetail({ status: 3, statusText: '已送达', paymentStatus, payState, payStateText: text, needCollect: true, payHint: text })
      assert.strictEqual(collectionVisible(data), false)
      assert.strictEqual(data.deliveredAwaitingCollection, false)
      assert.strictEqual(data.payHint, text)
    }
  })
  await test('历史现金未付款也只提示核对收款，线上未付款保留后端支付入口', async () => {
    const cash = await showDetail({ status: 3, statusText: '已送达', paymentStatus: 0, payState: 'UNPAID', payStateText: '未付款', needCollect: true, payHint: '货到付款，配送员送达时收款' })
    assert.strictEqual(collectionVisible(cash), true)
    assert.match(cash.payHint, /已送达.*待确认收款/)
    const online = await showDetail({ status: 3, statusText: '已送达', paymentStatus: 0, payState: 'UNPAID', payStateText: '未付款', needCollect: false, canRepay: true, repayLabel: '重新支付', payHint: '还未付款，可在本页继续支付' })
    assert.strictEqual(collectionVisible(online), false)
    assert.strictEqual(online.deliveredAwaitingCollection, false)
    assert.strictEqual(online.canRepay, true)
    assert.strictEqual(online.repayLabel, '重新支付')
    assert.strictEqual(online.payHint, detailResponse.payHint)
    assert.ok(orderWxml.includes('wx:if="{{canRepay}}" class="btn-action btn-pay" bindtap="onPayNow"'))
    assert.ok(orderWxml.includes('wx:if="{{canCancel}}" class="btn-action btn-cancel" bindtap="onCancel"'))
  })
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
