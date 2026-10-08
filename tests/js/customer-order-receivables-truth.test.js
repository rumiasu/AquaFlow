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

  function receivablePage(get, post = async () => ({ data: { settledCount: 1, settledAmount: 20 } })) {
    const p = loadPage('miniapp-delivery/pages/station-mgmt/receivables/index.js', {
      wx: createWx(), app: createApp(), stubs: { 'utils/request': { get, post, put: async () => ({ data: {} }) } }
    })
    p.setData({ overview: { outstandingAmount: 70 }, customer: { customerId: 2, customerName: '乙', outstandingAmount: 20, dueDays: 7 },
      orders: [{ orderId: 5, checked: true }], selectedCount: 1 })
    return p
  }
  function deferred() {
    let resolve, reject
    const promise = new Promise((yes, no) => { resolve = yes; reject = no })
    return { promise, resolve, reject }
  }
  const summary = (amount, customers = []) => ({ data: { outstandingAmount: amount, customers } })
  await test('收款成功同时刷新总额、客户集合和当前客户摘要，保留客户与筛选', async () => {
    const urls = []
    const p = receivablePage(async url => { urls.push(url); return url.includes('/orders?') ? { data: [] } : summary(50, [{ customerId: 1, outstandingAmount: 50 }]) })
    p.setData({ onlyOverdue: true })
    await p.onSettle()
    assert.strictEqual(p.data.overview.outstandingAmount, 50)
    assert.strictEqual(p.data.customer.customerId, 2)
    assert.strictEqual(p.data.customer.customerName, '乙')
    assert.strictEqual(p.data.customer.dueDays, 7)
    assert.strictEqual(p.data.customer.outstandingAmount, 0)
    assert.strictEqual(p.data.onlyOverdue, true)
    assert.strictEqual(p.data.allCustomers.length, 1)
    assert.strictEqual(p.data.customers.length, 0)
    assert.strictEqual(p.data.orders.length, 0)
    assert.strictEqual(p.data.selectedCount, 0)
    assert.ok(urls.includes('/api/manager/receivables'))
    assert.ok(urls.includes('/api/manager/receivables/orders?customerId=2'))
  })
  await test('部分收款后客户余额使用服务端摘要，不按核销金额本地相减', async () => {
    const p = receivablePage(async url => url.includes('/orders?') ? { data: [{ orderId: 6, totalAmount: 13 }] } : summary(63, [{ customerId: 2, customerName: '乙', outstandingAmount: 13 }]))
    await p.onSettle()
    assert.strictEqual(p.data.customer.outstandingAmount, 13)
    assert.strictEqual(p.data.overview.outstandingAmount, 63)
    assert.strictEqual(p.data.orders[0].orderId, 6)
  })
  await test('已收款但总览刷新失败时保留真实旧值和错误，重试摘要可恢复', async () => {
    let failed = true
    const p = receivablePage(async url => {
      if (url.includes('/orders?')) return { data: [] }
      if (failed) throw new Error('summary unavailable')
      return summary(50)
    })
    await p.onSettle()
    assert.strictEqual(p.data.overview.outstandingAmount, 70)
    assert.strictEqual(p.data.customer.outstandingAmount, 20)
    assert.match(p.data.loadError, /summary unavailable/)
    assert.strictEqual(p.data.orders.length, 0)
    assert.strictEqual(p.__wx.__calls.modal.filter(m => m.title === '核销未完成').length, 0)
    failed = false
    await p.onRetryLoad()
    assert.strictEqual(p.data.loadError, '')
    assert.strictEqual(p.data.customer.outstandingAmount, 0)
  })
  await test('收款后明细刷新失败不能伪装成无待收，摘要仍更新', async () => {
    const p = receivablePage(async url => {
      if (url.includes('/orders?')) throw new Error('orders unavailable')
      return summary(50)
    })
    await p.onSettle()
    assert.strictEqual(p.data.overview.outstandingAmount, 50)
    assert.match(p.data.ordersError, /orders unavailable/)
    assert.strictEqual(p.data.settling, false)
  })
  await test('缺少完整客户集合的损坏摘要不会被解释成客户已结清', async () => {
    const p = receivablePage(async () => ({ data: {} }))
    await p.load()
    assert.ok(p.data.loadError)
    assert.strictEqual(p.data.customer.outstandingAmount, 20)
  })
  await test('收款前慢总览不能覆写收款后新摘要', async () => {
    const slow = deferred()
    let calls = 0
    const p = receivablePage(async url => {
      if (url.includes('/orders?')) return { data: [] }
      return ++calls === 1 ? slow.promise : summary(50)
    })
    const oldLoad = p.load()
    await p.onSettle()
    slow.resolve(summary(70, [{ customerId: 2, outstandingAmount: 20 }]))
    await oldLoad
    assert.strictEqual(p.data.overview.outstandingAmount, 50)
    assert.strictEqual(p.data.customer.outstandingAmount, 0)
  })
  await test('旧总览失败不能抹掉最新加载成功状态', async () => {
    const slow = deferred()
    let calls = 0
    const p = receivablePage(async () => ++calls === 1 ? slow.promise : summary(50))
    const first = p.load()
    await p.load()
    slow.reject(new Error('old failure'))
    await first
    assert.strictEqual(p.data.loadError, '')
    assert.strictEqual(p.data.loading, false)
    assert.strictEqual(p.data.overview.outstandingAmount, 50)
  })
  await test('收款前慢明细不能重新带回已收订单', async () => {
    const slow = deferred()
    let calls = 0
    const p = receivablePage(async url => url.includes('/orders?') ? (++calls === 1 ? slow.promise : { data: [] }) : summary(50))
    const oldLoad = p.loadOrders()
    await p.onSettle()
    slow.resolve({ data: [{ orderId: 5, totalAmount: 20 }] })
    await oldLoad
    assert.deepStrictEqual(p.data.orders, [])
    assert.strictEqual(p.data.ordersLoading, false)
  })
  await test('切换客户后旧客户的迟到明细和错误均不污染新客户', async () => {
    for (const failure of [false, true]) {
      const slow = deferred()
      const p = receivablePage(async url => url.endsWith('=2') ? slow.promise : { data: [{ orderId: 8 }] })
      const oldLoad = p.loadOrders()
      p.setData({ customer: { customerId: 3 } })
      await p.loadOrders()
      if (failure) slow.reject(new Error('old customer failure'))
      else slow.resolve({ data: [{ orderId: 5 }] })
      await oldLoad
      assert.strictEqual(p.data.customer.customerId, 3)
      assert.strictEqual(p.data.orders[0].orderId, 8)
      assert.strictEqual(p.data.ordersError, '')
    }
  })
  await test('返回列表后迟到明细不能恢复已离开的客户订单', async () => {
    const slow = deferred()
    const p = receivablePage(async url => url.includes('/orders?') ? slow.promise : summary(50))
    const oldLoad = p.loadOrders()
    p.onBack()
    slow.resolve({ data: [{ orderId: 5 }] })
    await oldLoad
    assert.strictEqual(p.data.customer, null)
    assert.deepStrictEqual(p.data.orders, [])
    assert.strictEqual(p.data.ordersLoading, false)
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

  await test('员工端期望时间只在有请求时展示，原字段保留且不生成预约动作', async () => {
    const { parseWxml, renderElements } = require('./wxml-tree')
    const root = parseWxml(fs.readFileSync(path.join(ROOT, 'miniapp-delivery/pages/order/detail.wxml'), 'utf8'))
    let requestNode
    function visit(node) {
      if (node.attrs && node.attrs['wx:if'] === '{{order.deliveryTimeRequest}}') requestNode = node
      for (const child of node.children || []) visit(child)
    }
    visit(root)
    assert.ok(requestNode)
    const onlyRequest = { tag: '#root', children: [requestNode] }
    assert.strictEqual(renderElements(onlyRequest, { order: {} }).length, 0)
    const elements = renderElements(onlyRequest, { order: { deliveryTimeRequest: '希望18点前送达' } })
    assert.ok(elements.length > 0)
    assert.ok(elements.every(e => e.tag !== 'button' && !e.attrs.bindtap))
    assert.ok(JSON.stringify(requestNode).includes('{{order.deliveryTimeRequest}}'))
  })

  console.log(`\n全部通过：${passed} 项（应收失败恢复与订单事实契约）`)
  console.log(`AQUAFLOW_SUITE_OK ${passed}`)
  done()
})().catch((err) => {
  console.error(err)
  done()
  process.exitCode = 1
})
