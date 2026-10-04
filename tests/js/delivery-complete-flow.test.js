/**
 * 「配送送达页」流程测试（契约工作包 C：C1 收款事实 / C2 回桶与原因按数量之和 / C3 错误可行动）。
 *
 * 跑法：node tests/js/delivery-complete-flow.test.js
 *
 * 真的执行 miniapp-delivery/pages/order/complete.js 的处理函数，断言的是
 * "填了什么、提交了什么、失败时留在哪一页"，不是页面上有没有某个字符串。
 */

const assert = require('assert')
const { loadPage, createWx, createApp, armWatchdog } = require('./harness')

// 完成哨兵：**只用 ASCII**。受限沙箱下子进程往文件描述符写中文会被编码毁成 `?`，
// 于是中文完成标记匹配不到、正常套件被误判成"未跑完"（2026-09-27 实测）。
const MARK_ASCII = 'AQUAFLOW_SUITE_OK'

let passed = 0
const failures = []
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

/** 组织一次页面加载：可控的订单详情 + 完成配送假后端。 */
function newPage(order, opts) {
  const o = opts || {}
  const calls = { completeOrder: [], getOrderDetail: [] }
  const stubs = {
    'api/delivery': {
      getOrderDetail: async (id) => {
        calls.getOrderDetail.push(id)
        return { code: 0, data: order }
      },
      completeOrder: async (id, body) => {
        calls.completeOrder.push({ id, body })
        if (o.completeThrow) throw new Error(o.completeThrow)
        return { code: 0, data: {} }
      }
    },
    'utils/request': { get: async () => ({ code: 0, data: {} }) }
  }
  const wx = createWx()
  const page = loadPage('miniapp-delivery/pages/order/complete.js', { stubs, wx, app: createApp() })
  // onLoad 平时负责把订单号与来源写进 data；流程测试直接调处理函数，所以这里补上
  page.setData({ orderId: order.id, from: 'detail' })
  return { page, calls, wx }
}

/** 已付款单（水票/微信）：没有现场收款这一步，用来单独测回桶与原因 */
const paidOrder = (extra) => baseOrder(Object.assign({
  needCollect: false, paymentMethod: 3, paymentStatus: 2, payMethodText: '水票支付', payStateText: '已付款'
}, extra || {}))

const baseOrder = (extra) => Object.assign({
  id: 55,
  status: 2,
  firstBarrelOrder: false,
  paymentMethod: 2,
  paymentStatus: 1,
  needCollect: true,
  payMethodText: '货到付款',
  payStateText: '待收款',
  payHint: '货到付款，配送员送达时收款',
  totalAmount: 40,
  depositAmount: 0,
  // 明细口径（[2026-09-26]）：`barrelItem` / `suggestedReturnQty` 由后端下发 ——
  // 完成页只对桶装水画回桶行，默认回桶数取后端算好的旧桶数（本合同批新买押金的不算）。
  // 这里是一条"旧桶换新水"的普通行：送出 2、该回 2（客户手上正好有 2 个旧桶）。
  items: [{
    id: 501,
    productNameSnapshot: '农夫山泉 19L',
    quantity: 2,
    barrelItem: true,
    suggestedReturnQty: 2
  }]
}, extra || {})

/** 一条桶装水明细（完成页的回桶行只认 barrelItem=true）。 */
const barrelItem = (extra) => Object.assign({
  id: 501, productNameSnapshot: '农夫山泉 19L', quantity: 2, barrelItem: true, suggestedReturnQty: 2
}, extra || {})

console.log('配送送达页 · 流程测试（真实执行页面处理函数）')

// Node 24 不允许"顶层 await + require"混用，所以用例统一在 async 主函数里顺序执行
;(async () => {
const doneWatchdog = armWatchdog()

// ---------------------------------------------------------------- C1 首单
await test('首次产生押金的混合单：不生成回桶项，也不向后端提交回桶明细', async () => {
  // 首单即使同时买了其他商品也不回桶；即使后端建议数非零（例如并发首单已先送达），首单标记优先。
  const { page, wx, calls } = newPage(baseOrder({
    firstBarrelOrder: true, needCollect: false, payMethodText: '水票支付', paymentStatus: 2,
    items: [
      barrelItem({ suggestedReturnQty: 2 }),
      { id: 502, productNameSnapshot: '550ml瓶装水', quantity: 2, barrelItem: false, suggestedReturnQty: null }
    ]
  }))
  await page.loadOrder(55)
  assert.strictEqual(page.data.isFirstBarrelOrder, true)
  assert.deepStrictEqual(page.data.items, [], '首单不应创建隐藏的回桶输入项')
  assert.strictEqual(page.data.hasBarrelItems, false)
  await page.onConfirmComplete()
  const titles = wx.__calls.modal.map(m => m.title)
  await new Promise((r) => setTimeout(r, 10))
  assert.ok(!titles.some(t => t.indexOf('确认回桶数') > -1), '首单不该再问回桶数：' + JSON.stringify(titles))
  assert.ok(titles.some(t => t.indexOf('确认完成配送') > -1), '应直接进最终确认：' + JSON.stringify(titles))
  assert.deepStrictEqual(calls.completeOrder[0].body.itemReturns, [], '首单完成配送不得提交回桶明细')
})

// ---------------------------------------------------------------- 回桶口径（[2026-09-26] 混合单 / 新买押金桶）
await test('混合单：瓶装水不进回桶块，只留桶装水那一行（默认值取后端口径）', async () => {
  const { page, calls } = newPage(baseOrder({
    depositAmount: 60,
    items: [
      barrelItem({ id: 501, quantity: 3, suggestedReturnQty: 1 }),          // 旧桶 1 个换新水，另 2 个是本单新买押金桶
      { id: 502, productNameSnapshot: '农夫山泉 550ml', quantity: 2, barrelItem: false, suggestedReturnQty: null }
    ]
  }))
  await page.loadOrder(55)
  assert.strictEqual(page.data.items.length, 1, '瓶装水不该出现在回桶块里：' + JSON.stringify(page.data.items))
  assert.strictEqual(page.data.hasBarrelItems, true)
  assert.strictEqual(page.data.hasDepositOldBarrelHint, true, '本单有新押金桶且有旧桶回收时，应明确说明口径')
  assert.strictEqual(page.data.items[0].sentQty, 3, '「送出 N 桶」仍要显示真实送出数')
  assert.strictEqual(page.data.items[0].expected, 1, '该回数 = 客户手上的旧桶，不含本单新买押金桶')
  assert.strictEqual(page.data.items[0].actual, 1, '默认值 = 该回数')
  page.onSelectCollected({ currentTarget: { dataset: { value: 'true' } } })
  await page.onConfirmComplete()
  await new Promise((r) => setTimeout(r, 10))
  const body = calls.completeOrder[0].body
  assert.strictEqual(body.itemReturns.length, 1, '只提交桶装水那一行：' + JSON.stringify(body.itemReturns))
  assert.strictEqual(body.itemReturns[0].orderItemId, 501)
  assert.strictEqual(body.itemReturns[0].expected, 1)
  assert.strictEqual(body.itemReturns[0].actual, 1)
})

await test('新买押金的桶不参与回收：送出 4、客户手上 2 个旧桶 ⇒ 该回 2（不是 4）', async () => {
  const { page } = newPage(baseOrder({ depositAmount: 60, items: [barrelItem({ quantity: 4, suggestedReturnQty: 2 })] }))
  await page.loadOrder(55)
  assert.strictEqual(page.data.hasDepositOldBarrelHint, true)
  assert.strictEqual(page.data.items[0].sentQty, 4)
  assert.strictEqual(page.data.items[0].expected, 2)
  // 用送出数当 expected 会立刻要求填"少回收 2 桶"的原因 —— 这正是要避免的假异常
  assert.strictEqual(page.data.items[0].discrepancy, 0)
  page.onSelectCollected({ currentTarget: { dataset: { value: 'true' } } })
  assert.strictEqual(page._validate(), true, '默认值本身必须是可提交的，不该逼人填异常原因')
})

await test('纯瓶装水单：没有回桶这回事 —— 整块不渲染，也不弹"回桶数为 0"', async () => {
  const { page, calls, wx } = newPage(baseOrder({
    needCollect: false, paymentStatus: 2, payMethodText: '水票支付',
    items: [{ id: 502, productNameSnapshot: '农夫山泉 550ml', quantity: 2, barrelItem: false, suggestedReturnQty: null }]
  }))
  await page.loadOrder(55)
  assert.strictEqual(page.data.items.length, 0)
  assert.strictEqual(page.data.hasBarrelItems, false)
  assert.ok(!page.data.successSubtitle.includes('回桶'), '没有回桶这一步就别再说核对回桶后确认完成')
  await page.onConfirmComplete()
  const titles = wx.__calls.modal.map(m => m.title)
  assert.ok(!titles.some(t => t.indexOf('确认回桶数') > -1), '不涉及桶就别问回桶：' + JSON.stringify(titles))
  await new Promise((r) => setTimeout(r, 10))
  assert.deepStrictEqual(calls.completeOrder[0].body.itemReturns, [], '不该给瓶装水报回桶（服务端会拒）')
})

// ---------------------------------------------------------------- C1 现金未收
await test('现金未收款：没表态就不许提交（不默认已收、也不默认未收）', async () => {
  const { page, calls, wx } = newPage(baseOrder())
  await page.loadOrder(55)
  assert.strictEqual(page.data.collected, null, '默认必须是"还没选"')
  await page.onConfirmComplete()
  assert.strictEqual(calls.completeOrder.length, 0, '没确认收款不许提交')
  assert.ok(wx.__calls.toast.some(t => (t.title || '').indexOf('收到钱') > -1), '应提示先确认收款')
})

await test('现金未收：选"未收款"后提交，摘要写明"已送达，待收款"且请求带 collected=false', async () => {
  const { page, calls, wx } = newPage(baseOrder())
  await page.loadOrder(55)
  page.onSelectCollected({ currentTarget: { dataset: { value: 'false' } } })
  assert.strictEqual(page.data.collectedChosen, true)
  await page.onConfirmComplete()
  const summary = wx.__calls.modal.map(m => m.content || '').join('\n')
  assert.ok(summary.indexOf('已送达，待收款') > -1, '摘要必须写明待收款：' + summary)
  assert.ok(summary.indexOf('已收齐') === -1, '没收到钱不能写成已收齐')
  await new Promise((r) => setTimeout(r, 10))
  assert.strictEqual(calls.completeOrder.length, 1)
  assert.strictEqual(calls.completeOrder[0].body.collected, false)
})

await test('现金已收齐：请求带 collected=true，摘要写"已收款"', async () => {
  const { page, calls, wx } = newPage(baseOrder())
  await page.loadOrder(55)
  page.onSelectCollected({ currentTarget: { dataset: { value: 'true' } } })
  await page.onConfirmComplete()
  const summary = wx.__calls.modal.map(m => m.content || '').join('\n')
  assert.ok(summary.indexOf('已收款') > -1, summary)
  await new Promise((r) => setTimeout(r, 10))
  assert.strictEqual(calls.completeOrder[0].body.collected, true)
})

// GUI 回归：最终弹窗的配送结果、金额和确认动作必须与已收/未收选择一致。
for (const collected of [false, true]) {
  await test(`现金${collected ? '已收' : '未收'}最终确认：正确说明结果，保留总额、押金和取消闸门`, async () => {
    const { page, wx, calls } = newPage(baseOrder({ totalAmount: 140, depositAmount: 100 }))
    await page.loadOrder(55)
    wx.__modalAutoConfirm = false
    page.onSelectCollected({ currentTarget: { dataset: { value: String(collected) } } })
    await page.onConfirmComplete()
    const modal = wx.__calls.modal.find(m => m.title === '确认完成配送')
    assert.ok(modal, '仍需最终人工确认')
    const summary = modal.content
    assert.ok(summary.includes('¥140'), '本单总额必须取服务端：' + summary)
    assert.ok(summary.includes('含押金 ¥100'), '押金金额必须保留：' + summary)
    assert.ok(summary.includes('计件工钱'), '须说明配送后的计件记账')
    assert.ok(summary.includes('不能撤回'), '须保留不可撤回告知')
    assert.strictEqual(modal.confirmText, '确认完成')
    assert.strictEqual(calls.completeOrder.length, 0, '取消确认不能提交')
    if (collected) {
      assert.ok(summary.includes('已收款'), summary)
      assert.ok(summary.includes('记为「已完成」'), summary)
      assert.ok(!summary.includes('待收款'), summary)
    } else {
      assert.ok(summary.includes('记为「已送达」'), summary)
      assert.ok(summary.includes('仍待收款'), summary)
      assert.ok(summary.includes('实际收到钱后') && summary.includes('再确认收款'), summary)
      assert.ok(!summary.includes('已完成') && !summary.includes('结算'), summary)
    }
    modal.success({ confirm: true })
    await new Promise(resolve => setTimeout(resolve, 10))
    assert.strictEqual(calls.completeOrder.length, 1)
    assert.strictEqual(calls.completeOrder[0].body.collected, collected, '原收款事实原样提交')
  })
}

await test('现金摘要缺失金额时不伪造零元或显示 undefined/null', async () => {
  const { page, wx, calls } = newPage(baseOrder({ totalAmount: undefined, depositAmount: undefined }))
  await page.loadOrder(55); wx.__modalAutoConfirm = false
  page.onSelectCollected({ currentTarget: { dataset: { value: 'false' } } })
  await page.onConfirmComplete()
  const summary = wx.__calls.modal.find(m => m.title === '确认完成配送').content
  assert.ok(!summary.includes('undefined') && !summary.includes('null') && !summary.includes('¥0'), summary)
  assert.strictEqual(calls.completeOrder.length, 0)
})

// 三态体验回归：真实 Page 加载/选择事件驱动页头、按钮和最终确认。
await test('配送三态：已付、普通现金未收、新押金未收的页面提示和按钮一致', async () => {
  const scenarios = [
    { order: paidOrder({ hasOrderBarrelPurchase: false }), selected: null, blocked: false, title: '确认完成配送', action: '确认完成' },
    { order: baseOrder({ hasOrderBarrelPurchase: false }), selected: false, blocked: false, title: '登记送达，待收款', action: '确认送达' },
    { order: baseOrder({ hasOrderBarrelPurchase: true, totalAmount: 90, depositAmount: 50 }), selected: false, blocked: true, title: '先收齐款项，再交桶', action: '先收齐款项' },
    { order: paidOrder({ hasOrderBarrelPurchase: true, totalAmount: 90, depositAmount: 50 }), selected: null, blocked: false, title: '确认完成配送', action: '确认完成' }
  ]
  for (const scenario of scenarios) {
    const { page, wx, calls } = newPage(scenario.order)
    await page.loadOrder(55); wx.__modalAutoConfirm = false
    assert.strictEqual(page.data.collected, null, '不预选收款事实')
    if (scenario.selected !== null) page.onSelectCollected({ currentTarget: { dataset: { value: String(scenario.selected) } } })
    assert.strictEqual(page.data.completionTitle, scenario.title)
    assert.strictEqual(page.data.completionButtonText, scenario.action)
    assert.strictEqual(page.data.completionBlocked, scenario.blocked)
    assert.ok(!page.data.completionHint.includes('结算'))
    await page.onConfirmComplete()
    const final = wx.__calls.modal.find(m => m.title === '确认完成配送')
    if (scenario.blocked) {
      assert.strictEqual(final, undefined, '未收齐新增押金不能弹可送达确认')
      assert.ok(page.data.completionHint.includes('收齐水款和本单押金'))
      assert.ok(page.data.cashUncollectedDesc.includes('不能交桶'))
    } else {
      assert.ok(final)
      assert.ok(final.content.includes(scenario.selected === false ? '记为「已送达」' : '记为「已完成」'))
    }
    assert.strictEqual(calls.completeOrder.length, 0)
  }
})

await test('新押金未收不能绕过确认直接提交；明确收齐后同一页恢复原有确认和一次提交', async () => {
  const { page, wx, calls } = newPage(baseOrder({ hasOrderBarrelPurchase: true, totalAmount: 90, depositAmount: 50 }))
  await page.loadOrder(55); wx.__modalAutoConfirm = false
  assert.strictEqual(page.data.completionBlocked, true, '尚未选择也不能交桶')
  page.onSelectCollected({ currentTarget: { dataset: { value: 'false' } } })
  await page.onConfirmComplete(); page._showConfirmDialog(); await page._doSubmit()
  assert.strictEqual(calls.completeOrder.length, 0)
  assert.ok(!wx.__calls.modal.some(m => m.title === '确认完成配送'))
  assert.ok(wx.__calls.toast.some(m => m.title.includes('先收齐水款和本单押金')))
  page.onSelectCollected({ currentTarget: { dataset: { value: 'true' } } })
  assert.strictEqual(page.data.completionBlocked, false)
  assert.strictEqual(page.data.completionButtonText, '确认完成')
  await page.onConfirmComplete()
  const final = wx.__calls.modal.find(m => m.title === '确认完成配送')
  assert.ok(final.content.includes('已收款 ¥90') && final.content.includes('含押金 ¥50'))
  assert.ok(final.content.includes('记为「已完成」') && !final.content.includes('仍待收款'))
  assert.strictEqual(calls.completeOrder.length, 0)
  final.success({ confirm: true }); await new Promise(resolve => setTimeout(resolve, 10))
  assert.strictEqual(calls.completeOrder.length, 1)
  assert.strictEqual(calls.completeOrder[0].body.collected, true)
  assert.strictEqual(page.data.resultState, 'success')
  assert.strictEqual(page.data.successTitle, '配送已完成')
})

await test('历史押金现金单不因全局新规则开关被误拦；普通未收成功仍显示待收款', async () => {
  const { page, wx, calls } = newPage(baseOrder({ independentBusinessRules: true, hasOrderBarrelPurchase: false,
    totalAmount: 90, depositAmount: 50 }))
  await page.loadOrder(55)
  page.onSelectCollected({ currentTarget: { dataset: { value: 'false' } } })
  assert.strictEqual(page.data.requiresDepositCollection, false)
  assert.strictEqual(page.data.completionBlocked, false)
  await page.onConfirmComplete(); await new Promise(resolve => setTimeout(resolve, 10))
  assert.strictEqual(calls.completeOrder.length, 1)
  assert.strictEqual(calls.completeOrder[0].body.collected, false)
  assert.strictEqual(page.data.resultState, 'success')
  assert.strictEqual(page.data.successTitle, '已送达，待收款')
  assert.strictEqual(page.data.successButtonText, '已送达')
  assert.ok(page.data.successSubtitle.includes('实际收款'))
})

await test('三态文案绑定到实际 WXML，交桶禁用和选择事件均可达，未知重试保留', async () => {
  const fs = require('fs'), path = require('path'), { ROOT } = require('./harness')
  const template = fs.readFileSync(path.join(ROOT, 'miniapp-delivery/pages/order/complete.wxml'), 'utf8').replace(/<!--[\s\S]*?-->/g, '')
  for (const binding of ['{{completionTitle}}', '{{completionHint}}', '{{cashUncollectedDesc}}', '{{cashUncollectedHint}}',
    '{{successTitle}}', '{{successSubtitle}}']) assert.ok(template.includes(binding), binding)
  assert.ok(/disabled="{{submittingComplete \|\| completionBlocked}}"/.test(template))
  assert.ok(template.includes('completionButtonText') && template.includes('successButtonText'))
  assert.ok(template.includes('catchtap="onSelectCollected" data-value="false"'))
  assert.ok(template.includes('bindtap="onConfirmComplete"'))
  assert.ok(template.includes("resultState === 'unknown'") && template.includes('再提交一次（先确认列表）'))
  assert.ok(!template.includes('立即结算') && !template.includes('先按"未收款"提交'))
  const config = JSON.parse(fs.readFileSync(path.join(ROOT, 'miniapp-delivery/pages/order/complete.json'), 'utf8'))
  assert.strictEqual(config.navigationBarTitleText, '确认送达')
})

// ---------------------------------------------------------------- C2 原因按数量之和
await test('少回桶原因：展示与校验都按数量之和（1 项 × 3 桶 = 3 桶，不是 1 桶）', async () => {
  const { page } = newPage(paidOrder())
  await page.loadOrder(55)
  page._updateItemActual(0, 0)                        // 送出 2、回 0 ⇒ 少 2
  assert.strictEqual(page.data.items[0].discrepancy, 2)
  page.onOpenReasonPicker({ currentTarget: { dataset: { idx: 0 } } })
  page.onSelectReason({ currentTarget: { dataset: { key: 'customer_kept' } } })
  // 新选一项时预填"还没分配的缺口"（= 2），允许再改
  assert.strictEqual(page.data.items[0].reasonQtySum, 2, '应预填剩余缺口')
  page.onReasonQtyChange({ currentTarget: { dataset: { idx: 0, ridx: 0 } }, detail: { value: '2' } })
  assert.strictEqual(page.data.items[0].reasonQtySum, 2)
  assert.strictEqual(page._validate(), true, '数量之和对上少桶数就应通过')
})

await test('原因数量之和不足：拦住提交并说清差多少', async () => {
  const { page, calls, wx } = newPage(paidOrder())
  await page.loadOrder(55)
  page._updateItemActual(0, 0)                        // 少 2
  page.onOpenReasonPicker({ currentTarget: { dataset: { idx: 0 } } })
  page.onSelectReason({ currentTarget: { dataset: { key: 'lost' } } })
  page.onReasonQtyChange({ currentTarget: { dataset: { idx: 0, ridx: 0 } }, detail: { value: '1' } })  // 只填 1
  assert.strictEqual(page._validate(), false)
  await page.onConfirmComplete()
  assert.strictEqual(calls.completeOrder.length, 0, '原因合计不对不许提交')
  assert.ok(wx.__calls.toast.some(t => (t.title || '').indexOf('异常原因合计') > -1),
    '要告诉人差多少：' + JSON.stringify(wx.__calls.toast))
})

await test('提交体：原因数量原样上报（服务端会按同一个口径再校验一次）', async () => {
  const { page, calls } = newPage(baseOrder())
  await page.loadOrder(55)
  page._updateItemActual(0, 0)
  page.onOpenReasonPicker({ currentTarget: { dataset: { idx: 0 } } })
  page.onSelectReason({ currentTarget: { dataset: { key: 'damaged' } } })
  page.onReasonQtyChange({ currentTarget: { dataset: { idx: 0, ridx: 0 } }, detail: { value: '2' } })
  page.onSelectCollected({ currentTarget: { dataset: { value: 'false' } } })
  await page.onConfirmComplete()
  await new Promise((r) => setTimeout(r, 10))
  const body = calls.completeOrder[0].body
  assert.strictEqual(body.itemReturns[0].reasons[0].key, 'damaged')
  assert.strictEqual(body.itemReturns[0].reasons[0].qty, 2)
  assert.strictEqual(body.itemReturns[0].expected, 2)
  assert.strictEqual(body.itemReturns[0].actual, 0)
})

// ---------------------------------------------------------------- C3 错误可行动
await test('服务端拒绝（备货记录不完整）：停在原页、把服务端文案原样给人看，不吞异常', async () => {
  const { page, calls, wx } = newPage(baseOrder(), { completeThrow: '本单的备货记录不完整，暂时无法完成配送。请联系站长核对库存后再送。（订单号 55）' })
  await page.loadOrder(55)
  page.onSelectCollected({ currentTarget: { dataset: { value: 'false' } } })
  await page.onConfirmComplete()
  await new Promise((r) => setTimeout(r, 10))
  assert.strictEqual(calls.completeOrder.length, 1)
  const errModal = wx.__calls.modal.filter(m => m.title === '没能完成配送')
  assert.strictEqual(errModal.length, 1, '失败要用弹窗说清楚')
  assert.ok(errModal[0].content.indexOf('订单号 55') > -1, '要带上订单号：' + errModal[0].content)
  assert.strictEqual(wx.__calls.nav.length, 0, '失败不许跳走（现场填写的内容要留着）')
})

// ---------------------------------------------------------------- C4 备货信息
await test('备货提示：已备齐 / 还缺哪些，都来自服务端 stockPrep', async () => {
  const ready = newPage(baseOrder({ stockPrep: { ready: true, shortageTotal: 0, itemsWithoutCredential: 0, items: [] } }))
  await ready.page.loadOrder(55)
  assert.strictEqual(ready.page.data.stockPrepText, '本单已备齐')

  const short = newPage(baseOrder({
    stockPrep: { ready: false, shortageTotal: 3, itemsWithoutCredential: 0, items: [{ productId: 5, productName: '农夫山泉 19L', shortage: 3 }] }
  }))
  await short.page.loadOrder(55)
  assert.ok(short.page.data.stockPrepText.indexOf('还缺 3 桶') > -1, short.page.data.stockPrepText)
  assert.ok(short.page.data.stockPrepText.indexOf('农夫山泉 19L') > -1)
})

await test('备货信息不可用（老后端没这个字段）时：不显示、不报错', async () => {
  const { page } = newPage(baseOrder())
  await page.loadOrder(55)
  assert.strictEqual(page.data.stockPrepText, '')
  assert.strictEqual(page.data.stockPrep, null)
})

// 契约 §4「同一原因 3 桶显示 3 而非 1」
await test('同一原因 3 桶：按数量之和显示 3（不是按原因条数显示 1）', async () => {
  const { page, calls } = newPage(paidOrder({ items: [barrelItem({ quantity: 3, suggestedReturnQty: 3 })] }))
  await page.loadOrder(55)
  page._updateItemActual(0, 0)                        // 送出 3、回 0 ⇒ 少 3
  page.onOpenReasonPicker({ currentTarget: { dataset: { idx: 0 } } })
  page.onSelectReason({ currentTarget: { dataset: { key: 'customer_kept' } } })
  // 一条原因、3 桶：展示用的 reasonQtySum 必须是 3，页面文案由它渲染
  assert.strictEqual(page.data.items[0].reasons.length, 1)
  assert.strictEqual(page.data.items[0].reasonQtySum, 3, '显示与校验都按数量之和')
  assert.strictEqual(page._validate(), true)
  page.onSelectCollected({ currentTarget: { dataset: { value: 'false' } } })
  await page.onConfirmComplete()
  await new Promise((r) => setTimeout(r, 10))
  assert.strictEqual(calls.completeOrder[0].body.itemReturns[0].reasons[0].qty, 3)
})

// 契约 §4「多原因数量匹配」
await test('多原因：数量之和等于缺口才放行（1 + 2 = 3）', async () => {
  const { page, calls } = newPage(paidOrder({ items: [barrelItem({ quantity: 3, suggestedReturnQty: 3 })] }))
  await page.loadOrder(55)
  page._updateItemActual(0, 0)
  page.onOpenReasonPicker({ currentTarget: { dataset: { idx: 0 } } })
  page.onSelectReason({ currentTarget: { dataset: { key: 'customer_kept' } } })   // 预填 3
  page.onReasonQtyChange({ currentTarget: { dataset: { idx: 0, ridx: 0 } }, detail: { value: '1' } })
  page.onSelectReason({ currentTarget: { dataset: { key: 'damaged' } } })         // 预填剩余 2
  assert.strictEqual(page.data.items[0].reasons.length, 2)
  assert.strictEqual(page.data.items[0].reasonQtySum, 3, '第二项应预填剩余缺口')
  assert.strictEqual(page._validate(), true)
  page.onSelectCollected({ currentTarget: { dataset: { value: 'false' } } })
  await page.onConfirmComplete()
  await new Promise((r) => setTimeout(r, 10))
  const reasons = calls.completeOrder[0].body.itemReturns[0].reasons
  assert.strictEqual(reasons.length, 2)
  assert.strictEqual(reasons.reduce((s, r) => s + r.qty, 0), 3)
})

// 契约 §4「取消弹窗零写」
await test('最终确认里点取消：一个写请求都不发（订单不动）', async () => {
  const { page, calls, wx } = newPage(baseOrder())
  await page.loadOrder(55)
  page.onSelectCollected({ currentTarget: { dataset: { value: 'true' } } })
  wx.__modalAutoConfirm = false
  await page.onConfirmComplete()
  const summary = wx.__calls.modal[wx.__calls.modal.length - 1]
  assert.ok(summary && summary.title.indexOf('确认完成配送') > -1)
  summary.success({ confirm: false })                 // 客户/配送员点了取消
  await new Promise((r) => setTimeout(r, 10))
  assert.strictEqual(calls.completeOrder.length, 0, '取消不许提交')
  assert.strictEqual(wx.__calls.nav.length, 0, '取消不许跳走')
})

// 契约 §4「失败/超时/连点可恢复」
await test('连点"确认完成"：只发一次提交请求', async () => {
  const { page, calls } = newPage(baseOrder())
  await page.loadOrder(55)
  page.onSelectCollected({ currentTarget: { dataset: { value: 'false' } } })
  page._doSubmit()
  page._doSubmit()                                    // 连点
  await new Promise((r) => setTimeout(r, 20))
  assert.strictEqual(calls.completeOrder.length, 1, '连点只能提交一次')
})

/* ==========================================================================
 * 楼层与楼梯凭证（2026-09-26 产品口径：楼层一般不会"不清楚"，**有争议才展开**）
 * ========================================================================== */

await test('普通单（地址写清了有无电梯）：楼层块默认收起，点「楼梯有争议」才展开', async () => {
  const { page } = newPage(paidOrder({ addressFloor: 6, addressHasElevator: 1 }))
  await page.loadOrder(55)
  assert.strictEqual(page.data.elevatorUnknown, false, '写了有电梯就不是"没写清"')
  assert.strictEqual(page.data.floorBlockOpen, false, '默认收起 —— 不该每次送达都问爬了几层')
  assert.strictEqual(page.data.reportedFloor, '6', '地址里的楼层仍要带出来（展开后直接用）')

  page.onOpenFloorDispute()
  assert.strictEqual(page.data.floorBlockOpen, true, '点了争议入口要展开楼层 + 楼梯凭证')
})

await test('地址没写清有没有电梯（hasElevator 为空）：载入即自动展开并说明原因', async () => {
  const { page } = newPage(paidOrder({ addressFloor: 6, addressHasElevator: null }))
  await page.loadOrder(55)
  assert.strictEqual(page.data.elevatorUnknown, true)
  assert.strictEqual(page.data.floorBlockOpen, true, '电梯未知 = 楼层补贴最容易扯皮的情况，不能让人找不到入口')
  assert.strictEqual(page.data.showMore, true,
    '楼层块在「更多」里面：不同时展开「更多」= 等于没展开（人根本看不到）')
})

await test('普通单不会顺手把「更多」也撑开（正常送达仍只有商品 + 回桶 + 收钱）', async () => {
  const { page } = newPage(paidOrder({ addressFloor: 6, addressHasElevator: 1 }))
  await page.loadOrder(55)
  assert.strictEqual(page.data.showMore, false)
  assert.strictEqual(page.data.floorBlockOpen, false)
})

await test('明确"无电梯"不算没写清：仍然默认收起', async () => {
  const { page } = newPage(paidOrder({ addressFloor: 6, addressHasElevator: 0 }))
  await page.loadOrder(55)
  assert.strictEqual(page.data.elevatorUnknown, false)
  assert.strictEqual(page.data.floorBlockOpen, false)
})

await test('展开后填的楼层照旧上报（改名/收起都不影响上报口径）', async () => {
  const { page, calls } = newPage(paidOrder({ addressFloor: 6, addressHasElevator: 1 }))
  await page.loadOrder(55)
  page.onOpenFloorDispute()
  page.onFloorInput({ detail: { value: '8' } })
  page.onConfirmComplete()
  await new Promise((r) => setTimeout(r, 10))
  assert.strictEqual(calls.completeOrder[0].body.reportedFloor, 8, '上报值必须是配送员填的那个数')
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
