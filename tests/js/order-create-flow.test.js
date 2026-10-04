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
const { loadPage, createWx, createApp, armWatchdog, ROOT } = require('./harness')

// 完成哨兵：**只用 ASCII**。受限沙箱下子进程往文件描述符写中文会被编码毁成 `?`，
// 于是中文完成标记匹配不到、正常套件被误判成"未跑完"（2026-09-27 实测）。
const MARK_ASCII = 'AQUAFLOW_SUITE_OK'

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
            ticketPay: null, enterpriseHint: '', firstStationAsset: false, stationName: '测试水站',
            // 渠道能力（后端 quote 的 wechatPay）：默认**微信走模拟渠道可用**，
            // 这样"微信要不要发支付请求"的用例只需覆盖自己的那一条。
            // 要测"渠道没开"就显式传 quote.wechatPay = { enabled: false }。
            wechatPay: { method: 1, enabled: true, simulated: true, label: '模拟微信支付（点击即成功，仅联调期开启）' }
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

  const wx = sc.wx || createWx()
  const app = createApp({ globalData: { isLogin: true, customerId: 7, tempStationId: 1 } })
  const page = loadPage('miniapp-user/pages/order/create.js', { stubs, wx, app })

  // 把页面摆到"商品已选、地址已选、可以提交"的状态（这些本来由 onLoad/loadXxx 填）
  page.data.stationId = 1
  page.data.address = { id: 11 }
  page.data.products = [{ id: 5, name: '农夫山泉 19L', quantity: 2, price: 10, deposit: 30, category: 1 }]
  page.data.totalAmount = 20
  page.data.selectedMethod = sc.method == null ? 3 : sc.method
  page.data.blocked = false
  page.data.quoteReady = true // 此夹具明确模拟已核实报价的可提交状态；未核实路径另有专门回归。
  // [2026-09-26] 「确认下单」现在要求先在《水桶与押金说明》里勾选（assetReadAgreed）。
  // 默认按"客户已经看过说明并勾选"摆好 —— 否则每个涉及首次押金的用例都要先补一次勾选，
  // 而那些用例测的是别的分支。**专门测这个闸门的用例会自己把它置回 false**（见"未勾选说明"）。
  page.data.assetReadAgreed = true
  // [2026-09-26] 真机上 onLoad 会把"刚刚下过的那一单"从本地读回来（同一客户/同一设备）。
  // 测试里 loadPage 不跑 onLoad，这里补上等价的两次调用，让"已下单"态在测试中也成立。
  page._restorePendingOrderFromStorage()
  page._syncPendingOrderState()
  return { page, calls, wx }
}

(async () => {
const doneWatchdog = armWatchdog()
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

// [2026-09-26 行为反转 + 形态调整] 原来这条叫「成功建单之后：下一次提交是新意图（键已清）」，
//   它守护的是**缺陷**：成功那刻就清键 ⇒ 同一页面上再点一次 = 新键 = 新单。
//   实测（订单 37/38/39）客户点 3 下成交 3 单，`request_digest` 完全相同、幂等键三个都不同，
//   库存与押金各扣一次。
//   现在的形态是**业内主流**：不弹任何确认框，而是让页面记住这一单 ——
//   购物车没动时按钮变「继续支付/查看这笔订单」，重复提交**没有入口**；
//   改了内容（指纹变）才回到「立即下单」。
await test('购物车没动再点一次 ⇒ 不建新单，直接去刚才那张单（无确认框）', async () => {
  const { page, calls } = newPage({
    createOrder: [{ data: { orderId: 5, needConfirm: false } }, { data: { orderId: 6, needConfirm: false } }]
  })
  await page._createOrder(false)
  assert.strictEqual(calls.createOrder.length, 1)
  assert.strictEqual(page.data.pendingOrderId, 5, '建单成功后页面要进入"已下单"态（按钮据此变文案）')

  await page.onSubmit()                                           // 又点了一次按钮
  await new Promise((r) => setTimeout(r, 20))
  assert.strictEqual(calls.createOrder.length, 1, '第二次点击不许建单（这是那次 3 连单的根因）')
  assert.ok(page.__wx.__calls.nav.some(n => (n.url || '').indexOf('id=5') > -1),
    '应把客户带到刚才那张单，而不是弹确认框')
})

await test('"已下单"态：付款没结清 ⇒ 提示"还没付款"；结清了 ⇒ 提示已下好', async () => {
  const paid = newPage({
    method: 1,
    quote: {
      methods: [{ id: 1, name: '微信支付', desc: '模拟支付', enabled: true }],
      defaultMethod: 1,
      wechatPay: { method: 1, enabled: true, simulated: true, label: '模拟微信支付' }
    },
    createOrder: [{ data: { orderId: 11, needConfirm: false, warnings: [] } }],
    createPayment: [{ data: { status: 2 } }],
    getOrderDetail: [{ data: { id: 11, paymentStatus: 2 } }]
  })
  await paid.page.refreshQuote()
  await paid.page._createOrder(false)
  assert.strictEqual(paid.page.data.pendingOrderId, 11)
  assert.strictEqual(paid.page.data.pendingOrderPaid, true, '回读到已付 ⇒ 按钮显示"查看这笔订单"')

  // 没结清的那一路：支付失败 + 回读仍是未付（默认 stub 会给 paymentStatus=2，必须显式盖掉，
  // 否则这条用例测的其实是"已付"那条路）
  const unpaid = newPage({
    createOrder: [{ data: { orderId: 12, needConfirm: false } }],
    createPayment: [{ throw: '水票不足' }],
    getOrderDetail: [{ data: { id: 12, paymentStatus: 0 } }]
  })
  unpaid.wx.__modalAutoConfirm = false         // 失败弹窗里选"看订单"，不重试
  await unpaid.page._createOrder(false)
  assert.strictEqual(unpaid.page.data.pendingOrderId, 12)
  assert.strictEqual(unpaid.page.data.pendingOrderPaid, false, '没结清 ⇒ 按钮显示"继续支付"')
})

await test('页面重进后仍记得那一单（实测那次"跳结果页又回来点"的形状）', async () => {
  const sc = {
    createOrder: [{ data: { orderId: 77, needConfirm: false } }, { data: { orderId: 78, needConfirm: false } }]
  }
  const first = newPage(sc)
  await first.page._createOrder(false)
  assert.strictEqual(first.page.data.pendingOrderId, 77)

  // 同一台设备、同一个客户，重新进下单页：**共用一个 storage**（模拟"跳结果页 → 返回"）
  const second = newPage(Object.assign({}, sc, { wx: first.wx }))
  assert.strictEqual(second.page.data.pendingOrderId, 77, '重进页面后仍应认得"刚下过的那一单"')
  await second.page.onSubmit()
  await new Promise((r) => setTimeout(r, 20))
  assert.strictEqual(second.calls.createOrder.length, 0, '重进后再点一下也不许建第二单')
  assert.ok(second.page.__wx.__calls.nav.some(n => (n.url || '').indexOf('id=77') > -1))
})

await test('改了内容之后再点 ⇒ 不是同一单，直接建单（不打扰）', async () => {
  const { page, calls } = newPage({
    createOrder: [{ data: { orderId: 7, needConfirm: false } }, { data: { orderId: 8, needConfirm: false } }]
  })
  await page._createOrder(false)
  const k1 = calls.createOrder[0].idempotencyKey
  page.data.products[0].quantity = 5          // 明确改了数量 = 另一笔生意
  await page.refreshQuote()                   // 报价重算 ⇒ "已下单"态应自动失效
  assert.strictEqual(page.data.pendingOrderId, null, '内容变了就该回到「立即下单」')
  await page._createOrder(false)
  assert.strictEqual(calls.createOrder.length, 2, '内容变了就不该拦')
  assert.notStrictEqual(calls.createOrder[1].idempotencyKey, k1)
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
  page.onAssetReadAgree()                        // 说明里勾选（弹窗每次打开都会重置，必须显式走这一步）
  page.onAssetConfirmOk()
  await new Promise((r) => setTimeout(r, 20))
  assert.strictEqual(calls.createOrder.length, 1, '确认后才建单')
})

await test('未勾选使用说明时「确认下单」不建单，但必须给出下一步（看说明），不能是死按钮', async () => {
  const { page, calls, wx } = newPage({
    quote: { firstStationAsset: true },
    createOrder: [{ data: { orderId: 9, needConfirm: false } }]
  })
  page.data.firstStationAsset = true
  page.data.assetReadAgreed = false          // 客户还没看说明
  await page.onSubmit()
  assert.strictEqual(page.data.showAssetConfirm, true)
  page.onAssetConfirmOk()                    // 直接点「确认下单」
  await new Promise((r) => setTimeout(r, 20))
  assert.strictEqual(calls.createOrder.length, 0, '没勾选就不许建单（闸门是真的）')
  const titles = wx.__calls.modal.map(m => m.title).join('|')
  assert.ok(titles.indexOf('说明') > -1, '必须说清"先看说明"，而不是点了没反应：' + titles)
  // 弹窗里选「看说明」= 直接打开说明那一屏（这就是"下一步"，不是死路）
  assert.strictEqual(page.data.showAssetDetail, true, '「看说明」要真的把说明打开')

  page.onAssetReadAgree()                    // 勾上（切换真值）
  assert.strictEqual(page.data.assetReadAgreed, true)
  page.onAssetConfirmOk()
  await new Promise((r) => setTimeout(r, 20))
  assert.strictEqual(calls.createOrder.length, 1, '勾选后即可正常下单')
})

await test('每次重新弹首次确认都要重置勾选（不能拿上次的勾选顶过新的金额）', async () => {
  const { page } = newPage({ quote: { firstStationAsset: true } })
  page.data.firstStationAsset = true
  page.data.assetReadAgreed = true           // 假装上次勾过
  await page.onSubmit()
  assert.strictEqual(page.data.assetReadAgreed, false, '新的一次确认必须重新读说明')
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

  page.onAssetReadAgree()                       // 说明里勾选（弹窗每次打开会重置）
  page.onAssetConfirmOk()                       // 客户确认押金 → 这时才发第一次建单
  await new Promise((r) => setTimeout(r, 20))
  assert.strictEqual(calls.createOrder.length, 1)
  assert.strictEqual(page.data.showShortageConfirm, true, '缺货要接着确认，而不是当成功')
  assert.strictEqual(wx.__calls.nav.length, 0, '缺货阶段不许跳成功页')

  page.onShortageAgree()                        // 同意等待 → 同一个键重提
  await new Promise((r) => setTimeout(r, 20))
  assert.strictEqual(calls.createOrder.length, 2, '整个流程只发两次建单请求（探测 + 带确认）')
  assert.strictEqual(calls.createOrder[0].idempotencyKey, calls.createOrder[1].idempotencyKey)
  // [2026-09-26 删掉一条**恒真**的断言] 原来是：
  //   assert.strictEqual(wx.__calls.modal.filter(m => m.title.indexOf('首次资产业务') > -1).length, 0)
  //   它看着在守"押金确认只问一次"，其实**永远通过** —— 首次押金确认是 **wxml 里的自定义弹窗**
  //   （showAssetConfirm），根本不经过 wx.showModal，所以 __calls.modal 里永远没有它。
  //   改名后做过反向验证：把标题改回「首次资产业务」这条依旧 exit=0，证明它守不住任何东西。
  //   真正守"只问一次"的是上面两条：`calls.createOrder.length === 2`（探测 + 带确认，没有第三次）
  //   与 `page.data.showAssetConfirm === false`（确认后弹窗已关）。**别再把它加回来。**
  assert.strictEqual(page.data.showAssetConfirm, false, '确认过就不该再弹首次押金确认')
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
  page.onAssetReadAgree()
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
  assert.strictEqual(page.data.assetReadAgreed, false, '旧的勾选也要一并作废')
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

await test('同单支付明确失败：客户选「看订单」⇒ 不重试、不双扣，去同一张单', async () => {
  const { page, calls, wx } = newPage({
    method: 3,
    createOrder: [{ data: { orderId: 68, needConfirm: false } }],
    createPayment: [{ throw: '水票不足' }],
    getOrderDetail: [{ data: { id: 68, paymentStatus: 0 } }]
  })
  // 弹窗出现的语义是"要不要再试一次"：这里选**不试**（取消 = 看订单）
  wx.__modalAutoConfirm = false
  await page._createOrder(false)
  assert.strictEqual(calls.createPayment.length, 1, '用户没选重试就不能再发支付请求（防双扣）')
  const titles = page.__wx.__calls.modal.map(m => m.title)
  assert.ok(titles.some(t => t.indexOf('支付还没成功') > -1), '必须说清"订单已提交但支付没成功"：' + JSON.stringify(titles))
  assert.ok(page.__wx.__calls.nav.some(n => (n.url || '').indexOf('id=68') > -1))
})

await test('同单支付失败后客户选「再试一次」⇒ 同一个订单号重试，第二次成功即算已付', async () => {
  const { page, calls } = newPage({
    method: 3,
    createOrder: [{ data: { orderId: 70, needConfirm: false } }],
    // 第一次超时、第二次成功；回读支付事实：第一次未付、第二次已付
    createPayment: [{ throw: '网络超时' }, { data: { status: 2 } }],
    getOrderDetail: [{ data: { id: 70, paymentStatus: 0 } }, { data: { id: 70, paymentStatus: 2 } }]
  })
  await page._createOrder(false)
  assert.strictEqual(calls.createPayment.length, 2, '重试应真的再发一次（用户点了「再试一次」）')
  assert.strictEqual(calls.createPayment[1].orderId, 70, '重试必须挂在**同一张单**上')
  assert.strictEqual(calls.createOrder.length, 1, '重试付款绝不能重新建单')
  assert.ok(page.__wx.__calls.nav.some(n => (n.url || '').indexOf('id=70') > -1), '最终仍回到这张单的结果页')
})

await test('重试仍失败 ⇒ 停手并如实提示，不无限重试、不假报已付', async () => {
  const { page, calls } = newPage({
    method: 3,
    createOrder: [{ data: { orderId: 71, needConfirm: false } }],
    createPayment: [{ throw: '网络超时' }, { throw: '网络超时' }],
    getOrderDetail: [{ data: { id: 71, paymentStatus: 0 } }, { data: { id: 71, paymentStatus: 0 } }]
  })
  await page._createOrder(false)
  assert.strictEqual(calls.createPayment.length, 2, '最多重试一次就停手')
  assert.ok(page.__wx.__calls.nav.some(n => (n.url || '').indexOf('id=71') > -1))
})

await test('回查也失败：说"查不到"，不猜已付、不重复扣票', async () => {
  const { page, calls, wx } = newPage({
    method: 3,
    createOrder: [{ data: { orderId: 69, needConfirm: false } }],
    createPayment: [{ throw: '网络超时' }],
    getOrderDetail: [{ throw: '网络异常' }]
  })
  wx.__modalAutoConfirm = false   // 客户不重试
  await page._createOrder(false)
  assert.strictEqual(calls.createPayment.length, 1)
  const titles = page.__wx.__calls.modal.map(m => m.title)
  assert.ok(titles.some(t => t.indexOf('支付还没成功') > -1))
  assert.ok(!titles.some(t => t.indexOf('已经付好了') > -1), '查不到就不能说"已经付好了"')
})

// ---------------------------------------------------------------- 微信模拟渠道（2026-09-26 返工契约 P0-b）
// 判据：建单之后要不要对**同一张单**发起 createPayment，取决于服务端下发的 wechatPay.enabled
// （唯一实现 PayMethod.payChannel）。前端不按 id===1 猜渠道、不碰真实 wx.requestPayment。

await test('微信（模拟渠道开启）：建 1 单 → 同单模拟支付 → 回读已付 → 结果页', async () => {
  const { page, calls } = newPage({
    method: 3,
    quote: {
      // 只下发微信一项：页面此刻停在"水票"（本地偏好），而本站没给水票项 ⇒ 必须切到服务端默认项。
      // （若这里也给水票项，偏好会合法地保留水票，测不到"切到微信"这件事。）
      methods: [
        { id: 1, name: '微信支付', desc: '模拟支付（点击即成功，仅联调期开启）', enabled: true }
      ],
      defaultMethod: 1,
      wechatPay: { method: 1, enabled: true, simulated: true, label: '模拟微信支付（点击即成功，仅联调期开启）' }
    },
    createOrder: [{ data: { orderId: 90, needConfirm: false, warnings: [] } }],
    createPayment: [{ data: { status: 2 } }],
    getOrderDetail: [{ data: { id: 90, paymentStatus: 2 } }]
  })
  await page.refreshQuote()
  assert.strictEqual(page.data.selectedMethod, 1, '偏好的方式不在服务端列表里 ⇒ 取 defaultMethod')
  assert.strictEqual(page.data.wechatPay.enabled, true, '渠道能力来自服务端')
  await page._createOrder(false)
  assert.strictEqual(calls.createOrder.length, 1, '只建一单')
  assert.strictEqual(calls.createPayment.length, 1, '微信也必须发这一笔，否则服务端模拟渠道不会成形')
  assert.strictEqual(calls.createPayment[0].orderId, 90, '支付必须挂在原单上')
  assert.strictEqual(calls.createPayment[0].paymentMethod, 1, '支付方式仍是微信')
  assert.ok(page.__wx.__calls.nav.some(n => (n.url || '').indexOf('id=90') > -1), '去结果页')
})

await test('微信（模拟渠道关闭）：不发支付请求，也不假报已付', async () => {
  const { page, calls } = newPage({
    method: 1,
    quote: {
      methods: [{ id: 3, name: '水票支付', desc: '使用账户水票抵扣', enabled: true }],
      defaultMethod: 3,
      wechatPay: { method: 1, enabled: false, simulated: false, label: '微信支付暂未开通' }
    },
    createOrder: [{ data: { orderId: 91, needConfirm: false, warnings: [] } }],
    createPayment: [{ data: { status: 1 } }],
    getOrderDetail: [{ data: { id: 91, paymentStatus: 1 } }]
  })
  await page.refreshQuote()
  page.data.selectedMethod = 1        // 即便页面停在微信上（老偏好），渠道没开也不能付
  await page._createOrder(false)
  assert.strictEqual(calls.createOrder.length, 1)
  assert.strictEqual(calls.createPayment.length, 0,
    '渠道没开就不许发支付请求：那会造出一条必然 PENDING 的流水（' + JSON.stringify(calls.createPayment) + '）')
  const titles = page.__wx.__calls.modal.map(m => m.title)
  assert.ok(!titles.some(t => t.indexOf('已经付好了') > -1), '绝不能假报已付')
})

await test('微信支付请求成功但服务端仍待收款(1) ⇒ 不假报已付，给同单恢复入口', async () => {
  const { page, calls, wx } = newPage({
    method: 1,
    quote: {
      methods: [{ id: 1, name: '微信支付', desc: '模拟支付', enabled: true }],
      defaultMethod: 1,
      wechatPay: { method: 1, enabled: true, simulated: true, label: '模拟微信支付' }
    },
    createOrder: [{ data: { orderId: 92, needConfirm: false, warnings: [] } }],
    createPayment: [{ data: { status: 1 } }],            // 幂等返回旧 PENDING 流水的形状
    getOrderDetail: [{ data: { id: 92, paymentStatus: 1 } }]
  })
  await page.refreshQuote()
  // 第一次看到"还没成功"时选**不重试**（取消 = 看订单）：本用例只关心"有没有假报已付"
  wx.__modalAutoConfirm = false
  await page._createOrder(false)
  assert.strictEqual(calls.createPayment.length, 1, '只发一笔（活跃流水唯一键兜底，不落第二条）')
  const titles = page.__wx.__calls.modal.map(m => m.title)
  assert.ok(titles.some(t => t.indexOf('支付还没成功') > -1),
    '响应成功 ≠ 钱到账：必须按回读到的 paymentStatus 说话：' + JSON.stringify(titles))
  assert.ok(!titles.some(t => t.indexOf('已经付好了') > -1))
})

await test('下单响应的软提醒(warnings)必须显示：形状按解包后的 data 读', async () => {
  const { page } = newPage({
    method: 3,
    createOrder: [{ data: { orderId: 93, needConfirm: false, warnings: ['水站当前休息中，可能延迟配送'] } }],
    getOrderDetail: [{ data: { id: 93, paymentStatus: 2 } }]
  })
  await page._createOrder(false)
  const shown = page.__wx.__calls.modal.map(m => m.content).join('|')
  assert.ok(shown.indexOf('水站当前休息中') > -1,
    'warnings 曾被静默丢掉（读取了 data.data.warnings）：' + shown)
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

// ---------------------------------------------------------------- 结果页（后端字段直出）
//
// [2026-09-26 H2] 本用例原先的期望值是**前端自建映射表**的产物（status===5 → '订单已取消'、
// paymentStatus===3 → '已退款'、paymentMethod===2 && paymentStatus===1 → '下单成功'…），
// 而页面同屏又渲染后端下发的 statusText —— 两套状态文案。现已收敛为"只用后端字段"，
// 所以夹具也照**真实接口响应**给（Orders 派生字段 statusText / payState / payStateText / payHint）。
// ⚠️ 这是同步既有用例，不是"写一个只镜像新文案的测试"：断言仍然是
// 「后端说什么、页面就显示什么」，以及「查不到订单时不许显示成功」。
await test('结果页事实：标题只取后端 statusText，付款说明只取后端 payHint，不落到"尽快配送"', () => {
  const stubs = {
    'api/order': { getOrderDetail: async () => ({ code: 0, data: {} }), createOrder: async () => ({ code: 0 }), createPayment: async () => ({ code: 0 }) },
    'api/template': { setFromOrder: async () => ({ code: 0 }), getQuickOrder: async () => ({ code: 0, data: {} }) },
    'utils/pay': { notifyPayResult: () => {} }
  }
  const page = loadPage('miniapp-user/pages/order/success.js', { stubs, wx: createWx(), app: createApp() })
  const cases = [
    [{ status: 5, statusText: '已取消', payState: 'UNPAID', payStateText: '未付款', payHint: '订单已取消', paymentStatus: 0 }, '已取消'],
    [{ status: 1, statusText: '待配送', payState: 'REFUNDED', payStateText: '已退款', payHint: '已退款', paymentStatus: 3 }, '待配送'],
    [{ status: 1, statusText: '待配送', payState: 'PENDING', payStateText: '待收款', payHint: '货到付款，配送员送达时收款', paymentMethod: 2, paymentStatus: 1, totalAmount: 30 }, '待配送'],
    [{ status: 2, statusText: '配送中', payState: 'PAID', payStateText: '已付款', payHint: '已付款', paymentStatus: 2 }, '配送中'],
    [{ status: 3, statusText: '已送达', payState: 'PAID', payStateText: '已付款', payHint: '已付款', paymentStatus: 2 }, '已送达']
  ]
  cases.forEach(([order, expectTitle]) => {
    page.applyOrderFacts(Object.assign({ id: 1 }, order))
    assert.strictEqual(page.data.heroTitle, expectTitle, JSON.stringify(order) + ' ⇒ ' + page.data.heroTitle)
  })
  // 后端没给状态文案时：标题留空（wxml 不渲染），**不许**回落到"下单成功 / 尽快配送"
  page.applyOrderFacts({ id: 1, paymentStatus: 0 })
  assert.strictEqual(page.data.heroTitle, '', '没有 statusText 就不许编一个成功标题')
  assert.strictEqual(page.data.heroDesc.indexOf('尽快配送'), -1)
  assert.strictEqual(page.data.heroDesc.indexOf('下单成功'), -1)
  // 现金单"送到再付"必须由后端 payHint 到达客户眼前，金额照后端总额格式化
  page.applyOrderFacts({ id: 1, status: 1, statusText: '待配送', payState: 'PENDING', payStateText: '待收款', payHint: '货到付款，配送员送达时收款', paymentMethod: 2, paymentStatus: 1, totalAmount: 30 })
  assert.ok(page.data.heroDesc.indexOf('货到付款') > -1, '现金单要说清送到再付：' + page.data.heroDesc)
  assert.ok(page.data.amountText.indexOf('30') > -1, '金额取后端 totalAmount：' + page.data.amountText)
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

// 契约「货到付款不给开通的话，用户端直接不显示」（2026-09-26 产品裁定）：
// 判据在服务端（PayMethod.availableMethods 未开通就整项不下发），前端**只渲染收到的列表**。
await test('支付方式只渲染服务端下发的那些：未开通货到付款时页面里没有这一项', async () => {
  const { page } = newPage({
    quote: { methods: [{ id: 3, name: '水票支付', desc: '使用账户水票抵扣', enabled: true }], defaultMethod: 3 }
  })
  await page.refreshQuote()
  assert.strictEqual(page.data.payMethods.length, 1, '服务端给 1 项就渲染 1 项：' + JSON.stringify(page.data.payMethods))
  assert.strictEqual(page.data.payMethods[0].id, 3)
  assert.strictEqual(page.data.payMethods.some(m => m.id === 2), false, '前端不得自己补一项货到付款')
  assert.strictEqual(page.data.selectedMethod, 3, '默认项取服务端的 defaultMethod')
})

await test('服务端下发了现金项（已开通）⇒ 页面就有它且可选', async () => {
  const { page } = newPage({
    quote: {
      methods: [
        { id: 2, name: '货到付款', desc: '配送员送达后现金/扫码支付', enabled: true },
        { id: 3, name: '水票支付', desc: '使用账户水票抵扣', enabled: true }
      ],
      defaultMethod: 3,
      allowOfflinePayment: true
    }
  })
  await page.refreshQuote()
  const cash = page.data.payMethods.find(m => m.id === 2)
  assert.ok(cash && cash.enabled === true, '开通后应能看到并选中货到付款：' + JSON.stringify(page.data.payMethods))
  assert.strictEqual(page.data.allowOfflinePayment, true)
})

/* ==================================================================
 *  [2026-09-27 走查 C03/C04] 水票不足时给出下一步 + 备注框盒模型
 * ================================================================== */

await test('C03 票够：不给"去买水票/换支付方式"这两个动作（它们只在票不足时才有意义）', async () => {
  const { page } = newPage({
    quote: { ticketPay: { fullyCovered: true, coverQty: 2, coverAmount: 20, payableAmount: 0 } }
  })
  await page.refreshQuote()
  assert.strictEqual(page.data.ticketShortfallHint, '', '票够时不该有不足提示')
  assert.strictEqual(page.data.ticketCanBuy, false)
  assert.strictEqual(page.data.ticketAltMethod, null)
})

await test('C03 余额不够：给「去买水票」+「改用 X 支付」，且标签用后端下发的名字', async () => {
  const { page } = newPage({
    quote: {
      methods: [
        { id: 1, name: '微信支付', enabled: true },
        { id: 3, name: '水票支付', enabled: true }
      ],
      defaultMethod: 3,
      ticketPay: { fullyCovered: false, reason: 'INSUFFICIENT', hint: '本单还缺 1 张水票' }
    }
  })
  await page.refreshQuote()
  assert.strictEqual(page.data.ticketCanBuy, true, '余额不够 ⇒ 补票有意义，要给购票入口')
  assert.strictEqual(page.data.ticketAltMethod, 1, '替代方式要从后端下发且 enabled 的列表里挑非水票的第一个')
  assert.strictEqual(page.data.ticketAltLabel, '改用微信支付',
    '标签用后端下发的 name，前端不自造映射表，实际=' + page.data.ticketAltLabel)
})

await test('C03 商品不支持用票：**不给**「去买水票」（补票没用），但仍可换支付方式', async () => {
  const { page } = newPage({
    quote: {
      methods: [
        { id: 2, name: '货到付款', enabled: true },
        { id: 3, name: '水票支付', enabled: true }
      ],
      defaultMethod: 3,
      allowOfflinePayment: true,
      ticketPay: { fullyCovered: false, reason: 'NOT_SUPPORTED', hint: '本单含不支持水票的商品，票不会被扣' }
    }
  })
  await page.refreshQuote()
  assert.strictEqual(page.data.ticketCanBuy, false,
    '★ 商品根本不能用票时补票没用 —— 把人引去买票是错的（走查原文的硬要求）')
  assert.strictEqual(page.data.ticketAltMethod, 2, '仍要给出可换的支付方式')
  assert.strictEqual(page.data.ticketAltLabel, '改用货到付款')
})

await test('C03 「改用 X 支付」只切换选择、不提交（不许替客户改支付方式还替他下单）', async () => {
  const { page, calls } = newPage({
    quote: {
      methods: [
        { id: 1, name: '微信支付', enabled: true },
        { id: 3, name: '水票支付', enabled: true }
      ],
      defaultMethod: 3,
      ticketPay: { fullyCovered: false, reason: 'INSUFFICIENT', hint: '本单还缺 1 张水票' }
    }
  })
  await page.refreshQuote()
  const before = calls.createOrder.length
  page.onSwitchPayMethod({ currentTarget: { dataset: { id: 1 } } })
  await new Promise(r => setTimeout(r, 30))
  assert.strictEqual(page.data.selectedMethod, 1, '应切到微信')
  assert.ok(calls.getQuote.length > 0, '切换后要重走报价（金额与费用都按新方式算）')
  assert.strictEqual(calls.createOrder.length, before,
    '★ 只有切换，**绝不能顺手建单** —— 走查验收原文「不得自动改变支付方式并提交」')
})

await test('C03 「换支付方式」不接受可用列表之外的 id（拿页面旧数据也切不过去）', async () => {
  const { page, wx } = newPage({
    quote: {
      methods: [{ id: 3, name: '水票支付', enabled: true }],
      defaultMethod: 3,
      ticketPay: { fullyCovered: false, reason: 'INSUFFICIENT', hint: '本单还缺 1 张水票' }
    }
  })
  await page.refreshQuote()
  page.onSwitchPayMethod({ currentTarget: { dataset: { id: 2 } } })
  await new Promise(r => setTimeout(r, 10))
  assert.strictEqual(page.data.selectedMethod, 3, '不可用的方式不该被切过去')
  assert.ok(wx.__calls.toast.length > 0, '要给一句可读提示，不能静默无反应')
})

await test('C04 备注输入框必须显式 border-box（否则 100% 宽 + 内边距会横溢出卡片）', async () => {
  const fs = require('fs')
  const path = require('path')
  const wxss = fs.readFileSync(path.join(ROOT, 'miniapp-user/pages/order/create.wxss'), 'utf8')
  // 只看 .note-input 那一条规则体（到第一个右花括号）
  const start = wxss.indexOf('.note-input {')
  assert.ok(start >= 0, 'create.wxss 里应能找到 .note-input')
  const body = wxss.slice(start, wxss.indexOf('}', start))
  assert.ok(/width:\s*100%/.test(body), '前提：它仍是 100% 宽（占满卡片）')
  assert.ok(/box-sizing:\s*border-box/.test(body),
    '★ 必须显式 border-box —— 全局只有 view,text,image 有（app.wxss），input 不在其中：'
    + 'content-box 下 100% + 左右 padding 会比卡片宽，右侧溢出（走查 C04 截图 11）')
  assert.ok(!/overflow:\s*hidden/.test(body), '不许用 overflow:hidden 掩盖（会把输入区裁掉）')
})

console.log('')
doneWatchdog()
if (failures.length) {
  console.log('失败 ' + failures.length + ' 项 / 通过 ' + passed + ' 项')
  process.exitCode = 1
} else {
  console.log('全部通过：' + passed + ' 项（流程测试，真实执行页面处理函数）')
  console.log(MARK_ASCII + ' ' + passed)
}
})()
