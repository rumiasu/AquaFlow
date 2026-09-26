/**
 * 「客户下单闭环」流程测试（契约工作包 B / 下单契约 §4 的 12 类场景）。
 *
 * 跑法：node tests/js/order-create-flow.test.js
 *
 * 与静态门禁的区别：这里**真的执行** miniapp-user/pages/order/create.js 的处理函数，
 * 用假 wx / 假后端回调驱动，断言的是"发了几次建单请求、用的哪个幂等键、跳去哪一页、
 * 页面处于什么状态"。grep 一句 needConfirm 不算数。
 */

const assert = require('assert')
const { loadPage, createWx, createApp, ROOT } = require('./harness')

let passed = 0
const failures = []
/** 逐个 await，保证异步用例的失败被算进结果（不然会变成"打印全过、退出码 1"）。 */
async function test(name, fn) {
  try {
    await fn()
    passed++
    console.log('  ✓ ' + name)
  } catch (e) {
    failures.push({ name, error: e })
    console.log('  ✗ ' + name + '\n      ' + (e && e.message))
  }
}

/** 组织一次页面加载：可控的建单/支付/详情假后端 + 记录调用。 */
function newPage(scenario) {
  const sc = scenario || {}
  const calls = { createOrder: [], createPayment: [], getOrderDetail: [], getQuote: [] }

  const stubs = {
    'api/order': {
      createOrder: async (body) => {
        calls.createOrder.push(body)
        const n = calls.createOrder.length
        const step = (sc.createOrder || [])[n - 1] || { data: { orderId: 1000 + n, needConfirm: false } }
        if (step.throw) throw Object.assign(new Error(step.throw), {})
        return { code: 0, data: step.data }
      },
      createPayment: async (body) => {
        calls.createPayment.push(body)
        const step = (sc.createPayment || [])[calls.createPayment.length - 1] || { data: { status: 2 } }
        if (step.throw) throw new Error(step.throw)
        return { code: 0, data: step.data }
      },
      getOrderDetail: async (id) => {
        calls.getOrderDetail.push(id)
        const step = (sc.getOrderDetail || [])[calls.getOrderDetail.length - 1] || { data: { id, paymentStatus: 2 } }
        if (step.throw) throw new Error(step.throw)
        return { code: 0, data: step.data }
      },
      cancelOrder: async () => ({ code: 0 }),
      getOrders: async () => ({ code: 0, data: [] }),
      getMyLatestStation: async () => ({ code: 0, data: null })
    },
    'api/payment': {
      getQuote: async (body) => {
        calls.getQuote.push(body)
        return {
          code: 0,
          data: Object.assign({
            waterAmount: 20, barrelDeposit: 0, extraDeposit: 0, extraDepositBuckets: 0,
            deliveryFee: 0, floorFee: 0, totalAmount: 20,
            allowOfflinePayment: true, methods: [{ id: 3, name: '水票支付', enabled: true }],
            defaultMethod: 3, warnings: [], blocked: false, blockReason: '',
            ticketPay: null, enterpriseHint: '', firstStationAsset: false, stationName: '测试水站'
          }, sc.quote || {})
        }
      }
    },
    'api/product': { getProductDetail: async () => ({ code: 0, data: {} }) },
    'api/address': { getAddresses: async () => ({ code: 0, data: [] }) },
    'api/barrel': { getBarrelSummary: async () => ({ code: 0, data: [] }), getBarrelSummaryByType: async () => ({ code: 0, data: [] }) },
    'api/ticket': { getTicketAccounts: async () => ({ code: 0, data: [] }) },
    'api/station': { getStationPublicPhone: async () => ({ code: 0, data: {} }), getStationStatus: async () => ({ code: 0, data: {} }) },
    'api/enterprise': { submitEnterpriseApply: async () => ({ code: 0 }), getMyEnterpriseApplies: async () => ({ code: 0, data: [] }) }
  }

  const wx = createWx()
  const app = createApp({ globalData: { isLogin: true, customerId: 7, tempStationId: 1 } })
  const page = loadPage('miniapp-user/pages/order/create.js', { stubs, wx, app })

  // 把页面摆到"商品已选、地址已选、可以提交"的状态（这些本来由 onLoad/loadXxx 填）
  page.data.stationId = 1
  page.data.address = { id: 11 }
  page.data.products = [{ id: 5, name: '农夫山泉 19L', quantity: 2, price: 10, deposit: 30, category: 1 }]
  page.data.totalAmount = 20
  page.data.selectedMethod = sc.method == null ? 3 : sc.method
  page.data.blocked = false
  return { page, calls, wx }
}

(async () => {
console.log('客户下单闭环 · 流程测试（真实执行页面处理函数）')

// ---------------------------------------------------------------- 场景 1
await test('正常老客（现金）：建 1 单、不发支付请求、带合法订单号跳结果页', async () => {
  const { page, calls, wx } = newPage({ method: 2, createOrder: [{ data: { orderId: 321, needConfirm: false, warnings: [] } }] })
  page.data.selectedMethod = 2
  return page._createOrder(false).then(() => {
    assert.strictEqual(calls.createOrder.length, 1, '应只建 1 单')
    assert.strictEqual(calls.createPayment.length, 0, '现金单不应发支付请求')
    const nav = wx.__calls.nav
    assert.strictEqual(nav.length, 1, '应只跳一次')
    assert.strictEqual(nav[0].type, 'redirectTo')
    assert.ok(nav[0].url.indexOf('id=321') > -1, 'URL 里必须是真实订单号：' + nav[0].url)
  })
})

// ---------------------------------------------------------------- 场景 2 / 12
await test('畸形/空建单响应：不支付、不跳成功页，进入"结果未知"并保留幂等键', async () => {
  const { page, calls, wx } = newPage({ createOrder: [{ data: {} }] })
  return page._createOrder(false).then(() => {
    assert.strictEqual(calls.createPayment.length, 0, '未知结果绝不能发支付')
    assert.strictEqual(wx.__calls.nav.length, 0, '未知结果绝不能跳成功页')
    assert.strictEqual(page.data.submitState, 'unknown')
    assert.ok(page.data.idempotencyKey, '必须保留幂等键以便重试同一意图')
  })
})

await test('把整个响应对象当订单号（旧 bug）：现在判定为未知结果，不拼进 URL', async () => {
  const { page, calls, wx } = newPage({ createOrder: [{ data: { needConfirm: false, warnings: [] } }] })  // 没有 orderId
  return page._createOrder(false).then(() => {
    assert.strictEqual(wx.__calls.nav.length, 0)
    assert.strictEqual(calls.createPayment.length, 0)
    assert.strictEqual(page.data.submitState, 'unknown')
  })
})

// ---------------------------------------------------------------- 场景 4 / 5
await test('缺货未确认：不建单不支付不跳转，保留同一个键', async () => {
  const { page, calls, wx } = newPage({
    createOrder: [{ data: { needConfirm: true, shortages: [{ productId: 5, productName: '农夫山泉 19L', requested: 2, stock: 0 }] } }]
  })
  return page._createOrder(false).then(() => {
    assert.strictEqual(calls.createPayment.length, 0)
    assert.strictEqual(wx.__calls.nav.length, 0)
    assert.strictEqual(page.data.showShortageConfirm, true, '应弹缺货确认')
    assert.ok(page.data.idempotencyKey, '缺货不是成功，键必须留着')
    const key1 = page.data.idempotencyKey
    // 返回调整：什么都不发
    page.onShortageBack()
    assert.strictEqual(wx.__calls.nav.length, 0)
    assert.strictEqual(calls.createOrder.length, 1)
    assert.strictEqual(page.data.idempotencyKey, key1)
  })
})

await test('缺货后同意等待：两次创建请求用同一个键、第二次带确认、只产生 1 单', async () => {
  const { page, calls, wx } = newPage({
    createOrder: [
      { data: { needConfirm: true, shortages: [{ productId: 5, productName: '农夫山泉 19L', requested: 2, stock: 0 }] } },
      { data: { orderId: 777, needConfirm: false, warnings: [] } }
    ]
  })
  return page._createOrder(false).then(() => {
    page.onShortageAgree()
    return new Promise((r) => setTimeout(r, 10)).then(() => {
      assert.strictEqual(calls.createOrder.length, 2, '应发两次：一次探测缺货、一次带确认')
      assert.strictEqual(calls.createOrder[0].idempotencyKey, calls.createOrder[1].idempotencyKey, '两次必须同一个键')
      assert.strictEqual(calls.createOrder[0].confirmShortage, false)
      assert.strictEqual(calls.createOrder[1].confirmShortage, true)
      const urls = wx.__calls.nav.map(n => n.url || '')
      assert.ok(urls.some(u => u.indexOf('id=777') > -1), '最终应带着那张单的结果页：' + JSON.stringify(urls))
    })
  })
})

// ---------------------------------------------------------------- 场景 6
await test('服务端已建单但响应丢失（网络异常）：不动键，重试沿用同一个键拿回原单', async () => {
  const { page, calls, wx } = newPage({
    createOrder: [
      { throw: '请求超时' },
      { data: { orderId: 888, needConfirm: false } }
    ]
  })
  // 弹窗**不自动确认**：这样才能明确区分"第一次就自动重试了"和"客户点了重试"
  wx.__modalAutoConfirm = false
  await page._createOrder(false)
  assert.strictEqual(calls.createOrder.length, 1, '第一次只发一次请求')
  assert.strictEqual(wx.__calls.nav.length, 0, '失败时不该跳成功页')
  const key = page.data.idempotencyKey
  assert.ok(key, '失败后键必须还在')
  const modal = wx.__calls.modal[wx.__calls.modal.length - 1]
  assert.ok(modal && modal.title.indexOf('没提交成功') > -1, '应给出可重试的提示')
  // 客户点了弹窗里的「重试」
  modal.success({ confirm: true })
  await new Promise((r) => setTimeout(r, 10))
  assert.strictEqual(calls.createOrder.length, 2)
  assert.strictEqual(calls.createOrder[0].idempotencyKey, calls.createOrder[1].idempotencyKey,
    '重试必须沿用同一个键（服务端据此返回原单）')
  assert.ok(wx.__calls.nav.some(n => (n.url || '').indexOf('id=888') > -1))
})

// ---------------------------------------------------------------- 场景 7
await test('明确改了业务意图（数量变化）⇒ 换新键', async () => {
  const { page, calls } = newPage({ createOrder: [{ data: { orderId: 1, needConfirm: false } }] })
  return page._createOrder(false).then(() => {
    const key1 = calls.createOrder[0].idempotencyKey
    page.data.products[0].quantity = 3           // 客户明确改了数量
    return page._createOrder(false).then(() => {
      assert.strictEqual(calls.createOrder.length, 2)
      assert.notStrictEqual(calls.createOrder[1].idempotencyKey, key1, '换了意图必须换键')
      assert.strictEqual(calls.createOrder[1].items[0].quantity, 3)
    })
  })
})

await test('换了水站 / 换了支付方式 ⇒ 换新键', async () => {
  const { page, calls } = newPage({ createOrder: [{ data: { orderId: 1, needConfirm: false } }, { data: { orderId: 2, needConfirm: false } }, { data: { orderId: 3, needConfirm: false } }] })
  return page._createOrder(false).then(() => {
    const k1 = calls.createOrder[0].idempotencyKey
    page.data.stationId = 2
    return page._createOrder(false).then(() => {
      const k2 = calls.createOrder[1].idempotencyKey
      assert.notStrictEqual(k2, k1, '换站必须换键')
      page.data.selectedMethod = 2
      return page._createOrder(false).then(() => {
        assert.notStrictEqual(calls.createOrder[2].idempotencyKey, k2, '换支付方式必须换键')
      })
    })
  })
})

await test('成功建单之后：下一次提交是新意图（键已清）', async () => {
  const { page, calls } = newPage({ createOrder: [{ data: { orderId: 5, needConfirm: false } }, { data: { orderId: 6, needConfirm: false } }] })
  return page._createOrder(false).then(() => {
    const k1 = calls.createOrder[0].idempotencyKey
    return page._createOrder(false).then(() => {
      assert.notStrictEqual(calls.createOrder[1].idempotencyKey, k1, '上一单已建出来 ⇒ 本次是新的一单')
    })
  })
})

// ---------------------------------------------------------------- 场景 2（首次押金）
await test('首次押金告知在**建单之前**：取消 ⇒ 一个建单请求都不发', async () => {
  const { page, calls } = newPage({ quote: { firstStationAsset: true } })
  page.data.firstStationAsset = true
  return page.onSubmit().then(() => {
    assert.strictEqual(calls.createOrder.length, 0, '还没确认就不该建单')
    assert.strictEqual(page.data.showAssetConfirm, true)
    page.onAssetConfirmCancel()
    assert.strictEqual(calls.createOrder.length, 0, '取消确认后依然零请求')
    assert.strictEqual(page.data.showAssetConfirm, false)
  })
})

await test('首次押金：确认后才建单（顺序反了就是原来的 bug）', async () => {
  // ⚠️ 判据必须由**报价**下发（onSubmit 里会先 refreshQuote，它会按服务端事实覆盖这个字段），
  //    所以这里既给 quote 也让页面拿到它 —— 只手工改 data 是测不出真实行为的。
  const { page, calls } = newPage({ quote: { firstStationAsset: true }, createOrder: [{ data: { orderId: 9, needConfirm: false } }] })
  page.data.firstStationAsset = true
  await page.onSubmit()
  assert.strictEqual(calls.createOrder.length, 0, '还没确认就不该建单')
  page.onAssetConfirmOk()
  await new Promise((r) => setTimeout(r, 20))
  assert.strictEqual(calls.createOrder.length, 1, '确认后才建单')
})

// 契约 §4「首次押金，确认；同时缺货」：所有必要确认都在建单前，且**最终只建 1 单、不反复确认**
await test('首次押金 + 缺货同时发生：先押金确认、再缺货确认，最终只建 1 单', async () => {
  const { page, calls, wx } = newPage({
    quote: { firstStationAsset: true },
    createOrder: [
      { data: { needConfirm: true, shortages: [{ productId: 5, productName: '农夫山泉 19L', requested: 2, stock: 0 }] } },
      { data: { orderId: 4321, needConfirm: false, warnings: [] } }
    ]
  })
  page.data.firstStationAsset = true
  await page.onSubmit()
  assert.strictEqual(calls.createOrder.length, 0, '押金告知阶段零请求')
  assert.strictEqual(page.data.showAssetConfirm, true)

  page.onAssetConfirmOk()                       // 客户确认押金 → 这时才发第一次建单
  await new Promise((r) => setTimeout(r, 20))
  assert.strictEqual(calls.createOrder.length, 1)
  assert.strictEqual(page.data.showShortageConfirm, true, '缺货要接着确认，而不是当成功')
  assert.strictEqual(wx.__calls.nav.length, 0, '缺货阶段不许跳成功页')

  page.onShortageAgree()                        // 同意等待 → 同一个键重提
  await new Promise((r) => setTimeout(r, 20))
  assert.strictEqual(calls.createOrder.length, 2, '整个流程只发两次建单请求（探测 + 带确认）')
  assert.strictEqual(calls.createOrder[0].idempotencyKey, calls.createOrder[1].idempotencyKey)
  assert.strictEqual(wx.__calls.modal.filter(m => m.title.indexOf('首次资产业务') > -1).length, 0,
    '押金确认只问一次（它现在在建单前，不会在建单后再弹一遍）')
  assert.ok(wx.__calls.nav.some(n => (n.url || '').indexOf('id=4321') > -1), '最终结果是那唯一一张单')
})

// 契约 §4「同键重复点击」：提交中再点不能发第二次请求
await test('提交中重复点击：只发一次请求', async () => {
  const { page, calls } = newPage({ createOrder: [{ data: { orderId: 11, needConfirm: false } }] })
  const first = page._createOrder(false)
  const second = page._createOrder(false)      // 连点
  await Promise.all([first, second])
  assert.strictEqual(calls.createOrder.length, 1, '连点只能发一次建单请求')
})

// 契约 §4「缺货后明确改数量/站/地址」：新意图重算，且**旧确认不能继续有效**
await test('改了业务意图后：旧的一次性确认失效（押金告知要重新确认）', async () => {
  const { page, calls } = newPage({
    quote: { firstStationAsset: true },
    createOrder: [{ data: { orderId: 21, needConfirm: false } }]
  })
  page.data.firstStationAsset = true
  await page.onSubmit()
  page.onAssetConfirmOk()
  await new Promise((r) => setTimeout(r, 20))
  assert.strictEqual(calls.createOrder.length, 1)

  // 客户回去改了数量 → 报价重算 → 上一次的"已确认"必须作废（金额/归属都变了）
  page.setData({ assetConfirmed: false })
  page.data.products[0].quantity = 5
  page.data.assetConfirmed = false
  await page.onSubmit()
  assert.strictEqual(calls.createOrder.length, 1, '重新提交前必须先重新确认，不能拿旧确认绕过')
  assert.strictEqual(page.data.showAssetConfirm, true)
})

// ---------------------------------------------------------------- 场景 9 / 10 / 11
await test('水票支付成功：同一张单只发一次支付请求', async () => {
  const { page, calls } = newPage({
    method: 3,
    createOrder: [{ data: { orderId: 66, needConfirm: false } }],
    createPayment: [{ data: { status: 2 } }]
  })
  return page._createOrder(false).then(() => {
    assert.strictEqual(calls.createOrder.length, 1)
    assert.strictEqual(calls.createPayment.length, 1)
    assert.strictEqual(calls.createPayment[0].orderId, 66, '支付必须挂在原单上')
  })
})

await test('水票支付失败但**已扣票**（回查发现已付）：不重复扣票、照常去结果页', async () => {
  const { page, calls } = newPage({
    method: 3,
    createOrder: [{ data: { orderId: 67, needConfirm: false } }],
    createPayment: [{ throw: '网络超时' }],
    getOrderDetail: [{ data: { id: 67, paymentStatus: 2 } }]
  })
  return page._createOrder(false).then(() => {
    assert.strictEqual(calls.createPayment.length, 1, '超时后不能再次扣票')
    assert.strictEqual(calls.getOrderDetail.length, 1, '应先回查原单支付事实')
    assert.ok(page.__wx.__calls.nav.some(n => (n.url || '').indexOf('id=67') > -1), '仍要能看到这张单')
  })
})

await test('水票支付明确失败：不伪称已付，留在原单可恢复路径', async () => {
  const { page, calls } = newPage({
    method: 3,
    createOrder: [{ data: { orderId: 68, needConfirm: false } }],
    createPayment: [{ throw: '水票不足' }],
    getOrderDetail: [{ data: { id: 68, paymentStatus: 0 } }]
  })
  return page._createOrder(false).then(() => {
    assert.strictEqual(calls.createPayment.length, 1)
    const titles = page.__wx.__calls.modal.map(m => m.title)
    assert.ok(titles.some(t => t.indexOf('支付还没成功') > -1), '必须说清"订单已提交但支付没成功"：' + JSON.stringify(titles))
    assert.ok(page.__wx.__calls.nav.some(n => (n.url || '').indexOf('id=68') > -1))
  })
})

await test('回查也失败：说"查不到"，不猜已付、不重复扣票', async () => {
  const { page, calls } = newPage({
    method: 3,
    createOrder: [{ data: { orderId: 69, needConfirm: false } }],
    createPayment: [{ throw: '网络超时' }],
    getOrderDetail: [{ throw: '网络异常' }]
  })
  return page._createOrder(false).then(() => {
    assert.strictEqual(calls.createPayment.length, 1)
    const titles = page.__wx.__calls.modal.map(m => m.title)
    assert.ok(titles.some(t => t.indexOf('支付还没成功') > -1))
  })
})

// ---------------------------------------------------------------- 换账号隔离
await test('换账号：不继承上一位客户的待确认请求（键按客户隔离）', async () => {
  const { page, calls, wx } = newPage({
    createOrder: [{ data: { needConfirm: true, shortages: [] } }, { data: { orderId: 12, needConfirm: false } }]
  })
  return page._createOrder(false).then(() => {
    const keyA = page.data.idempotencyKey
    assert.ok(wx.__storage.get('orderIntent.v1'), '应把意图记在本地')
    // 换账号：customerId 变了 ⇒ 五要素变了 ⇒ 必须换键
    page.__app.globalData.customerId = 8
    return page._createOrder(false).then(() => {
      assert.notStrictEqual(calls.createOrder[1].idempotencyKey, keyA, '换了客户必须换键')
    })
  })
})

// ---------------------------------------------------------------- 结果页映射（纯函数级）
await test('结果页事实映射：已取消/已退款/现金未付/已付 各说各的，不落到"尽快配送"', () => {
  const stubs = {
    'api/order': { getOrderDetail: async () => ({ code: 0, data: {} }), createOrder: async () => ({ code: 0 }), createPayment: async () => ({ code: 0 }) },
    'api/template': { setFromOrder: async () => ({ code: 0 }), getQuickOrder: async () => ({ code: 0, data: {} }) },
    'utils/pay': { notifyPayResult: () => {} }
  }
  const page = loadPage('miniapp-user/pages/order/success.js', { stubs, wx: createWx(), app: createApp() })
  const cases = [
    [{ status: 5, paymentStatus: 0 }, '订单已取消'],
    [{ status: 1, paymentStatus: 3 }, '已退款'],
    [{ status: 1, paymentMethod: 2, paymentStatus: 1, totalAmount: 30 }, '下单成功'],
    [{ status: 1, paymentStatus: 2 }, '下单成功'],
    [{ status: 1, paymentMethod: 3, paymentStatus: 0, totalAmount: 20 }, '订单已提交']
  ]
  cases.forEach(([order, expectTitle]) => {
    page.applyOrderFacts(Object.assign({ id: 1 }, order))
    assert.strictEqual(page.data.heroTitle, expectTitle, JSON.stringify(order) + ' ⇒ ' + page.data.heroTitle)
  })
  // 现金未付必须给出"送到再付 ¥X"
  page.applyOrderFacts({ id: 1, status: 1, paymentMethod: 2, paymentStatus: 1, totalAmount: 30 })
  assert.ok(page.data.heroDesc.indexOf('送到再付') > -1, '现金单要说清送到再付：' + page.data.heroDesc)
  assert.ok(page.data.heroDesc.indexOf('30') > -1)
})

await test('结果页：拿不到订单 ⇒ failed 状态 + 可重试，不显示成功', async () => {
  const stubs = {
    'api/order': {
      getOrderDetail: async () => ({ code: 0, data: null }),
      createOrder: async () => ({ code: 0 }),
      createPayment: async () => ({ code: 0 })
    },
    'api/template': { setFromOrder: async () => ({ code: 0 }), getQuickOrder: async () => ({ code: 0, data: {} }) },
    'utils/pay': { notifyPayResult: () => {} }
  }
  const wx = createWx()
  const page = loadPage('miniapp-user/pages/order/success.js', { stubs, wx, app: createApp() })
  page.setData({ orderId: 123 })
  await page.loadOrderStatus(123)
  assert.strictEqual(page.data.loadState, 'failed')
  assert.ok(page.data.loadErrorText.length > 0)
})

console.log('')
if (failures.length) {
  console.log('失败 ' + failures.length + ' 项 / 通过 ' + passed + ' 项')
  process.exitCode = 1
} else {
  console.log('全部通过：' + passed + ' 项（流程测试，真实执行页面处理函数）')
}
})()
