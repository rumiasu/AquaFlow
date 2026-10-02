/**
 * 「客户在线买水票」流程测试。
 *
 * 跑法：node tests/js/ticket-purchase-flow.test.js
 *
 * 锁的是 2026-09-26 的产品口径：**水票购买是客户的自助预付，不是向水站申请、等水站同意**
 * （原话：「水票购买不需要水站同意，直接微信收款就行。现在只是没做收款实现而已」）。
 *
 * 这条口径的落地形状是**后端**决定的（`PaymentService.confirmMockChannelIfApplicable`，
 * 判据见 docs/design/19 §8.1），客户端的责任只有两件，也正是本套件断言的东西：
 *   ① 支付方式文案不许把"收款确认"说成"审批"（也不许反过来吹"无需水站确认"——真实渠道还没接入）；
 *   ② 结果必须按后端返回的**真实 status** 说：2 = 票已到账，1 = 等水站确认收到钱。
 * 另外顺带锁住"连点只发一次请求"与"失败重试复用同一个幂等键"这两条请求侧的契约 ——
 * 它们不靠界面样式（`disabled` 只改背景色，拦不住 tap），只能靠代码。
 */

const assert = require('assert')
const { loadPage, createWx, createApp, armWatchdog } = require('./harness')

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

/** 组织一次页面加载：可控的购票假后端 + 记录调用。 */
function newPage(scenario) {
  const sc = scenario || {}
  const calls = { purchase: [], query: [], rights: 0, accounts: 0, records: 0 }
  const responseFor = (body, extra) => Object.assign({ paymentId: 900, amount: 24, status: 2,
    stationId: body.stationId, productId: body.productId, quantity: body.quantity,
    paymentMethod: body.paymentMethod, packageId: body.packageId, unifiedQty: body.unifiedQty }, extra)

  const stubs = {
    'api/barrel': { getBarrelSummaryByType: async () => {
      calls.rights++
      if (sc.rightsThrow) throw new Error('权益查询失败')
      return { code: 0, data: sc.rights === undefined ? [{ productId: 5, quantity: 1, rightQty: 1 }] : sc.rights }
    } },
    'api/ticket': {
      getTicketAccounts: async () => { calls.accounts++; return { code: 0, data: sc.accounts || [] } },
      getTicketRecords: async () => { calls.records++; return { code: 0, data: [] } },
      getTicketPackages: async () => ({ code: 0, data: [] }),
      getTicketPurchaseResult: async (key) => {
        calls.query.push(key)
        const step = (sc.query || [])[calls.query.length - 1] || {}
        if (step.throw) throw new Error(step.throw)
        return { code: 0, data: step.data || null }
      },
      purchaseTicket: async (body) => {
        calls.purchase.push(body)
        // 需要"请求还没回来"的用例（连点）：给一个由用例自己放行的 Promise。
        // ⚠️ resolvers 用**数组**收：后来的一次调用不能覆盖前一次的 resolver ——
        // 覆盖了第一个 await 就永远等不到，套件会静默退出（这个坑真的踩过一次，见 harness.armWatchdog）。
        if (sc.hold) {
          return new Promise((resolve) => {
            sc.resolvers = sc.resolvers || []
            sc.resolvers.push(() => resolve({ code: 0, data: responseFor(body, sc.holdData) }))
          })
        }
        const step = (sc.purchase || [])[calls.purchase.length - 1]
        if (step && step.throw) throw new Error(step.throw)
        return { code: 0, data: responseFor(body, step && step.data) }
      }
    },
    'api/product': { getStationProducts: async () => ({ code: 0, data: sc.products || [] }) },
    'api/station': { getPublicStations: async () => ({ code: 0, data: [] }) }
  }

  const wx = sc.wx || createWx()
  // 已选水站（真实 utils/storage 读的就是这个键）
  wx.setStorageSync('selectedStation', { id: 1, name: '测试水站' })
  const app = sc.app || createApp()
  const page = loadPage('miniapp-user/pages/ticket/index.js', { stubs, wx, app })

  // 把页面摆到"商品已选、张数已定、可以提交"的状态（本来由 onShow/选商品填）
  page.data.currentStationId = 1
  page.data.buyProducts = [{ id: 5, name: '测试水', price: 8, effectiveTicketPrice: 8, ticketEnabled: 1, category: 1 }]
  page.data.buyForm = Object.assign({}, page.data.buyForm, {
    productId: 5, productName: '测试水', faceValue: 8, quantity: 3, totalPrice: 24
  })
  return { page, calls, wx, app }
}

;(async () => {
  const doneWatchdog = armWatchdog()
  console.log('水票购买流程 · 流程测试（真实执行页面处理函数）')
  await test('该桶型没有汇总行时：服务端拒绝无权益购票，明确引导先交押金', async () => {
    const {page,calls,wx}=newPage({rights:[],purchase:[{throw:'请先购买该桶权益'}]})
    await page.onBuySubmit()
    assert.strictEqual(calls.purchase.length,1)
    assert.ok(wx.__calls.modal.some(m=>m.content.includes('权益')))
    assert.ok(wx.__calls.nav.some(n=>n.url.includes('/pages/barrel/purchase')))
  })

  await test('支付方式文案：叫「微信支付」，且不把收款确认说成"申请/审批"', async () => {
    const { page } = newPage()
    const methods = page.data.buyMethods
    assert.strictEqual(methods.length, 1, '当前只有一种收款方式（界面不摆假的二选一）')
    assert.strictEqual(methods[0].name, '微信支付', '渠道名必须说清是微信收款，不是"水站确认收款"')
    const words = JSON.stringify(methods)
    ;['申请', '审批', '审核', '同意'].forEach((w) => {
      assert.ok(words.indexOf(w) === -1,
        '购票文案里不许出现「' + w + '」—— 买水票是自助预付，不需要水站点头：' + words)
    })
    // 反向：也不许吹"无需水站确认"（真实微信渠道还没接入，当前部署里到账仍要站长确认收到钱）
    assert.ok(methods[0].desc.indexOf('无需') === -1,
      '真实渠道没接入时不许承诺"无需水站确认"：' + methods[0].desc)
  })

  await test('提交体：数量/收款方式(微信=1)/水站/幂等键齐全', async () => {
    const { page, calls } = newPage()
    await page.onBuySubmit()
    assert.strictEqual(calls.purchase.length, 1, '点一次只发一次购票请求')
    const body = calls.purchase[0]
    assert.strictEqual(body.productId, 5)
    assert.strictEqual(body.quantity, 3)
    assert.strictEqual(body.paymentMethod, 1, '购票走微信收款')
    assert.strictEqual(body.stationId, 1, '水站必须带上（后端按站定价、按站入账）')
    assert.ok(body.idempotencyKey, 'v33 起幂等键必传：无订单支付在库里零防重')
  })

  await test('已到账(status=2)：说"购买成功，水票已到账"，不弹等待框', async () => {
    const { page, wx } = newPage()
    await page.onBuySubmit()
    const toasts = wx.__calls.toast.map((t) => t.title)
    assert.ok(toasts.indexOf('购买成功，水票已到账') !== -1, '模拟渠道下当场到账，就该说已到账：' + JSON.stringify(toasts))
    assert.strictEqual(wx.__calls.modal.length, 0, '票已到账就不该再弹"等待到账"')
    assert.strictEqual(page.data.showPurchase, false, '成功后购票弹窗要关掉')
  })

  await test('未到账(status=1)：如实说"等水站确认收到钱"，不谎报已到账', async () => {
    const { page, wx } = newPage({ purchase: [{ data: { paymentId: 901, amount: 24, status: 1 } }] })
    await page.onBuySubmit()
    const toasts = wx.__calls.toast.map((t) => t.title)
    assert.strictEqual(toasts.indexOf('购买成功，水票已到账'), -1, '钱还没确认就不能说已到账（客户会以为票没了）')
    assert.strictEqual(wx.__calls.modal.length, 1, '必须有一个明确的说明弹窗')
    const modal = wx.__calls.modal[0]
    assert.strictEqual(modal.title, '等待到账')
    assert.ok(modal.content.indexOf('水站还没确认收到这笔钱') !== -1,
      '要说清卡在"没确认收到钱"，而不是让客户以为在等审批：' + modal.content)
    ;['申请', '审批', '审核', '同意'].forEach((w) => {
      assert.ok(modal.content.indexOf(w) === -1, '等待文案里不许出现「' + w + '」：' + modal.content)
    })
  })

  await test('提交中重复点击：只发一次请求（按钮的 disabled 样式拦不住 tap）', async () => {
    const sc = { hold: true }
    const { page, calls } = newPage(sc)
    const first = page.onBuySubmit()
    const second = page.onBuySubmit()   // 用户在请求还没回来时又点了一下
    await Promise.resolve() // 新前置查询结束，购票请求才进入可控的等待。
    sc.resolvers.forEach((r) => r())
    await first
    await second
    assert.strictEqual(calls.purchase.length, 1, '连点不得发第二次请求（同键虽能兜住重复入账，但会再弹一次结果）')
    assert.strictEqual(page.data.submitting, false, '收尾必须把提交中状态复位，否则按钮永远点不动')
  })

  await test('失败后重试：复用同一个幂等键（重试的是同一笔购买，不是新的一笔）', async () => {
    const { page, calls } = newPage({ purchase: [{ throw: '网络超时' }] })
    await page.onBuySubmit()
    assert.strictEqual(calls.purchase.length, 1)
    await page.onBuySubmit()
    assert.strictEqual(calls.purchase.length, 2, '失败要能重试')
    assert.strictEqual(calls.purchase[0].idempotencyKey, calls.purchase[1].idempotencyKey,
      '两次重试必须是同一个键：换键就等于"又买一笔"，超时重试会变成两次购买')
  })

  await test('成交之后换新键：下一笔购买不能被当成上一笔的重放', async () => {
    const { page, calls } = newPage()
    await page.onBuySubmit()
    page.data.buyForm = Object.assign({}, page.data.buyForm, { productId: 5, quantity: 1, totalPrice: 8 })
    await page.onBuySubmit()
    assert.strictEqual(calls.purchase.length, 2)
    assert.notStrictEqual(calls.purchase[0].idempotencyKey, calls.purchase[1].idempotencyKey,
      '成功后键要重新生成，否则第二次购买会被后端当成重放、返回上一笔（客户以为买了其实没买）')
  })

  // F-75 反向旅程：模拟服务端已成功、仅客户端丢响应。不会把“假后端绿”称为 MySQL 已验。
  await test('丢响应退出后查回原款，商品已下架也不再次付款', async () => {
    const first = newPage({ purchase: [{ throw: '响应丢失' }] })
    await first.page.onBuySubmit()
    const key = first.calls.purchase[0].idempotencyKey
    const second = newPage({ wx: first.wx, products: [], query: [{ data: {
      paymentId: 900, status: 2, amount: 24, stationId: 1, productId: 5, quantity: 3, paymentMethod: 1, packageId: null
    } }] })
    await second.page.onShow()
    assert.deepStrictEqual(second.calls.query, [key])
    assert.strictEqual(second.calls.purchase.length, 0)
    assert.strictEqual(second.page.data.pendingPurchase, null)
    assert.ok(second.wx.__calls.toast.some(t => t.title === '购买成功，水票已到账'))
  })

  await test('退出重进未查到时保留原请求，权益查询失败也可原键重试', async () => {
    const first = newPage({ purchase: [{ throw: '响应丢失' }] })
    await first.page.onBuySubmit()
    const original = first.calls.purchase[0]
    const second = newPage({ wx: first.wx, rightsThrow: true })
    await second.page.onShow()
    assert.ok(second.page.data.pendingPurchase)
    await second.page.onRetryOriginalPurchase()
    assert.deepStrictEqual(second.calls.purchase[0], original)
    assert.strictEqual(second.calls.rights, 0, '原款恢复不得依赖新的权益/商品配置')
  })

  for (const field of ['stationId', 'productId', 'quantity', 'paymentMethod', 'packageId', 'unifiedQty']) {
    await test('未知结果后修改 ' + field + ' 不得拿旧键当新购买', async () => {
      const t = newPage({ purchase: [{ throw: '超时' }] })
      await t.page.onBuySubmit()
      if (field === 'stationId') t.page.data.currentStationId = 2
      else t.page.data.buyForm[field] = field === 'packageId' ? 99 : 8
      await t.page.onBuySubmit()
      assert.strictEqual(t.calls.purchase.length, 1)
      assert.ok(t.page.data.pendingPurchase)
    })
  }

  await test('关弹窗不遗忘未知购买，修改 handler 被阻止', async () => {
    const t = newPage({ purchase: [{ throw: '超时' }] })
    await t.page.onBuySubmit()
    const before = t.page.data.pendingPurchase.body
    t.page.onClosePurchase()
    t.page.onBuyPaymentMethodSelect({ currentTarget: { dataset: { id: 2 } } })
    t.page.applyLooseQty(10)
    assert.deepStrictEqual(t.page.data.pendingPurchase.body, before)
    assert.strictEqual(t.page.data.buyForm.quantity, 1)
    assert.strictEqual(t.page.data.buyForm.paymentMethod, 1)
    await t.page.onRetryOriginalPurchase()
    assert.strictEqual(t.calls.purchase[1].quantity, 3)
  })

  await test('查回失败不显示成功、不丢原购买、也不换编号', async () => {
    const t = newPage({ purchase: [{ throw: '超时' }], query: [{ throw: '查询超时' }] })
    await t.page.onBuySubmit()
    await t.page.onQueryPurchaseResult()
    assert.strictEqual(t.page.data.pendingPurchase.body.idempotencyKey, t.calls.purchase[0].idempotencyKey)
    assert.ok(t.page.data.purchaseRecoveryText.includes('查询失败'))
    assert.ok(!t.wx.__calls.toast.some(n => n.title.includes('成功')))
  })

  await test('收到不完整状态不谎称已登记，原购买仍可恢复', async () => {
    const t = newPage({ purchase: [{ data: { paymentId: 900, amount: 24, status: null } }] })
    await t.page.onBuySubmit()
    assert.ok(t.page.data.pendingPurchase && !t.page.data.pendingPurchase.result)
    assert.ok(!t.wx.__calls.modal.some(n => n.title === '等待到账'))
  })

  await test('身份切换后保留各自原购买，新客户不用旧客户的编号', async () => {
    const t = newPage({ purchase: [{ throw: '超时' }, { throw: '超时' }] })
    await t.page.onBuySubmit()
    t.app.globalData.customerId = 8
    await t.page.onBuySubmit()
    assert.notStrictEqual(t.calls.purchase[0].idempotencyKey, t.calls.purchase[1].idempotencyKey)
    t.app.globalData.customerId = 7
    t.page.restorePurchaseIntent()
    assert.strictEqual(t.page.data.pendingPurchase.body.idempotencyKey, t.calls.purchase[0].idempotencyKey)
  })

  await test('持久化失败时不发送购票，避免退出后丢失编号', async () => {
    const t = newPage()
    t.wx.setStorageSync = () => { throw new Error('存储空间不足') }
    await t.page.onBuySubmit()
    assert.strictEqual(t.calls.purchase.length, 0)
    assert.strictEqual(t.page.data.submitting, false)
  })

  await test('已登记待收款必须明确另买一笔才生成新编号', async () => {
    const t = newPage({ purchase: [{ data: { paymentId: 900, status: 1, amount: 24 } }], query: [{ data: {
      paymentId: 900, amount: 24, status: 1, stationId: 1, productId: 5, quantity: 3, paymentMethod: 1, packageId: null
    } }] })
    await t.page.onBuySubmit()
    t.page.data.buyForm.productId = 5
    await t.page.onBuySubmit()
    assert.strictEqual(t.calls.purchase.length, 1)
    t.wx.__modalAutoConfirm = false
    await t.page.onStartNextPurchase()
    assert.ok(t.page.data.pendingPurchase)
    t.wx.__modalAutoConfirm = true
    await t.page.onStartNextPurchase()
    t.page.data.buyForm.productId = 5
    await t.page.onBuySubmit()
    assert.strictEqual(t.calls.purchase.length, 2)
    assert.notStrictEqual(t.calls.purchase[0].idempotencyKey, t.calls.purchase[1].idempotencyKey)
  })

  await test('原款内容不一致不能显示购票成功', async () => {
    const t = newPage({ purchase: [{ throw: '超时' }], query: [{ data: {
      paymentId: 900, amount: 24, status: 2, stationId: 2, productId: 5, quantity: 3, paymentMethod: 1
    } }] })
    await t.page.onBuySubmit()
    await t.page.onQueryPurchaseResult()
    assert.ok(t.page.data.pendingPurchase)
    assert.ok(!t.wx.__calls.toast.some(n => n.title.includes('成功')))
  })

  await test('原购买记录损坏或读取失败不能覆盖成新购买', async () => {
    const t = newPage()
    t.wx.setStorageSync('aq_user_ticketPurchaseIntent:7', { customerId: 7 })
    await t.page.onShow()
    await t.page.onBuySubmit()
    assert.strictEqual(t.calls.purchase.length, 0)
    assert.ok(t.page.data.purchaseStorageError)
    t.wx.getStorageSync = () => { throw new Error('存储不可用') }
    await t.page.onBuySubmit()
    assert.strictEqual(t.calls.purchase.length, 0)
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
