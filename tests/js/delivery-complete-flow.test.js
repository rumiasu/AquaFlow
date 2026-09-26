/**
 * 「配送送达页」流程测试（契约工作包 C：C1 收款事实 / C2 回桶与原因按数量之和 / C3 错误可行动）。
 *
 * 跑法：node tests/js/delivery-complete-flow.test.js
 *
 * 真的执行 miniapp-delivery/pages/order/complete.js 的处理函数，断言的是
 * "填了什么、提交了什么、失败时留在哪一页"，不是页面上有没有某个字符串。
 */

const assert = require('assert')
const { loadPage, createWx, createApp } = require('./harness')

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
  items: [{ id: 501, productNameSnapshot: '农夫山泉 19L', quantity: 2 }]
}, extra || {})

console.log('配送送达页 · 流程测试（真实执行页面处理函数）')

// Node 24 不允许"顶层 await + require"混用，所以用例统一在 async 主函数里顺序执行
;(async () => {

// ---------------------------------------------------------------- C1 首单
await test('首单（押金桶）：不再弹"全部为 0 是否确认"，直接进最终确认', async () => {
  const { page, wx } = newPage(baseOrder({ firstBarrelOrder: true, needCollect: false, payMethodText: '水票支付', paymentStatus: 2 }))
  await page.loadOrder(55)
  assert.strictEqual(page.data.isFirstBarrelOrder, true)
  assert.strictEqual(page.data.items[0].actual, 0)
  await page.onConfirmComplete()
  const titles = wx.__calls.modal.map(m => m.title)
  assert.ok(!titles.some(t => t.indexOf('确认回桶数') > -1), '首单不该再问"全部为 0"：' + JSON.stringify(titles))
  assert.ok(titles.some(t => t.indexOf('确认完成配送') > -1), '应直接进最终确认：' + JSON.stringify(titles))
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
  const { page, calls } = newPage(paidOrder({ items: [{ id: 501, productNameSnapshot: '农夫山泉 19L', quantity: 3 }] }))
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
  const { page, calls } = newPage(paidOrder({ items: [{ id: 501, productNameSnapshot: '农夫山泉 19L', quantity: 3 }] }))
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

console.log('')
if (failures.length) {
  console.log('失败 ' + failures.length + ' 项 / 通过 ' + passed + ' 项')
  process.exitCode = 1
} else {
  console.log('全部通过：' + passed + ' 项（流程测试，真实执行页面处理函数）')
}
})()
