/**
 * 「配送端订单详情页 · 回桶行」流程测试。
 *
 * 跑法：node tests/js/delivery-detail-return-row.test.js
 *
 * <p><b>为什么单独立一个套件</b>：这一行曾经读的是 `order.expectedReturnBarrels` ——
 * 后端**根本没有这个字段**（全仓 grep 零命中），于是"预计回桶"永远渲染 `0个`：
 * 续购单看着像"不用回桶"，而首单本来就不该有默认回桶值。这类"字段名对不上后端"
 * 的错误静态门禁查不出来（`audit_wxml_handlers.py` 只查事件绑定、`audit_js_syntax.py` 只查解析），
 * 只能靠"真执行页面处理函数 + 断言渲染值"来守。</p>
 *
 * 触发场景（产品原话）：「第一次送达桶确实不需要回收，把第一次桶送达时的默认回桶值取消掉」。
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

/** 订单详情页的假后端：`/orders/{id}` 返回一个 orders 实体投影，图片/流水一律空。 */
function newPage(order) {
  const stubs = {
    'api/delivery': {
      getOrderDetail: async () => ({ code: 0, data: order })
    },
    'utils/request': { get: async () => ({ code: 0, data: [] }), put: async () => ({ code: 0 }), post: async () => ({ code: 0 }) }
  }
  const wx = createWx()
  const page = loadPage('miniapp-delivery/pages/order/detail.js', { stubs, wx, app: createApp() })
  return { page, wx }
}

/** 一个不涉及桶的普通单底板；各用例只覆盖自己关心的那几个字段。 */
const baseOrder = (extra) => Object.assign({
  id: 77,
  status: 1,                 // 待配送
  firstBarrelOrder: false,
  deliveryBucketQty: 2,
  returnBucketQty: null,
  statusText: '待配送',
  payMethodText: '货到付款',
  // 回桶行的数来自**逐条明细**：barrelItem / suggestedReturnQty 都是后端下发的投影
  //（suggestedReturnQty = 客户手上的旧桶，本合同批新买押金的不算）
  items: [{ id: 501, productId: 9, productNameSnapshot: '农夫山泉 19L', quantity: 2, barrelItem: true, suggestedReturnQty: 2 }]
}, extra || {})

const barrelItem = (extra) => Object.assign({
  id: 501, productId: 9, productNameSnapshot: '农夫山泉 19L', quantity: 2, barrelItem: true, suggestedReturnQty: 2
}, extra || {})

console.log('配送端订单详情页 · 回桶行（真实执行页面处理函数）')

;(async () => {
const doneWatchdog = armWatchdog()

// ---------------------------------------------------------------- 首单
await test('首单（押金桶）：不给数字，直接说"无需回桶"', async () => {
  const { page } = newPage(baseOrder({ firstBarrelOrder: true, items: [barrelItem({ quantity: 3, suggestedReturnQty: 0 })] }))
  await page.loadOrderDetail(77)
  assert.strictEqual(page.data.order.returnValue, '押金桶 · 无需回桶',
    '首单不能出现默认回桶数（送出多少回多少）：' + page.data.order.returnValue)
  assert.strictEqual(page.data.order.returnLabel, '回桶')
})

// ---------------------------------------------------------------- 续购未送达
await test('续购单未送达：默认回收数取「客户手上的旧桶」，不是送出桶数', async () => {
  const { page } = newPage(baseOrder({ items: [barrelItem({ quantity: 4, suggestedReturnQty: 2 })] }))
  await page.loadOrderDetail(77)
  assert.strictEqual(page.data.order.returnLabel, '预计回桶')
  assert.strictEqual(page.data.order.returnValue, '2 个',
    '本单新买押金的 2 个桶不参与回收（送出 4 只该回 2）：' + page.data.order.returnValue)
})

await test('混合单（桶装水 + 瓶装水）：只累加桶装水那几行', async () => {
  const { page } = newPage(baseOrder({
    items: [
      barrelItem({ id: 501, quantity: 3, suggestedReturnQty: 1 }),
      { id: 502, productNameSnapshot: '农夫山泉 550ml', quantity: 2, barrelItem: false, suggestedReturnQty: null }
    ]
  }))
  await page.loadOrderDetail(77)
  assert.strictEqual(page.data.order.returnValue, '1 个', '瓶装水不计入回桶：' + page.data.order.returnValue)
})

await test('客户手上没有旧桶（首单之外的普通单）：整行留空，不显示"预计回桶 0 个"', async () => {
  const { page } = newPage(baseOrder({ items: [barrelItem({ quantity: 2, suggestedReturnQty: 0 })] }))
  await page.loadOrderDetail(77)
  assert.strictEqual(page.data.order.returnValue, '')
})

// ---------------------------------------------------------------- 已送达
await test('已送达：改成"已回桶"并给实际回收数，不能再叫"预计"', async () => {
  const { page } = newPage(baseOrder({ status: 3, returnBucketQty: 3 }))
  await page.loadOrderDetail(77)
  assert.strictEqual(page.data.order.returnLabel, '已回桶')
  assert.strictEqual(page.data.order.returnValue, '3 个')
})

await test('已完成：同"已送达"口径（3 / 4 都是终态）', async () => {
  const { page } = newPage(baseOrder({ status: 4, returnBucketQty: 0 }))
  await page.loadOrderDetail(77)
  assert.strictEqual(page.data.order.returnLabel, '已回桶')
  assert.strictEqual(page.data.order.returnValue, '0 个', '一个都没还也要显示 0，不是空白')
})

// ---------------------------------------------------------------- 不下发桶的单
await test('不下发桶的单（瓶装水/饮水机）：整行为空，由 wxml 隐藏', async () => {
  const { page } = newPage(baseOrder({
    deliveryBucketQty: null,
    items: [{ id: 502, productNameSnapshot: '农夫山泉 550ml', quantity: 2, barrelItem: false, suggestedReturnQty: null }]
  }))
  await page.loadOrderDetail(77)
  assert.strictEqual(page.data.order.returnValue, '',
    '没有桶的单不该出现"预计回桶 0 个"这种误导性的一行')
})

// ---------------------------------------------------------------- 反向验证
await test('旧字段 expectedReturnBarrels 即使下发也不再被读取（幽灵字段回归）', async () => {
  const { page } = newPage(baseOrder({ expectedReturnBarrels: 99 }))
  await page.loadOrderDetail(77)
  assert.strictEqual(page.data.order.returnValue, '2 个',
    '值必须来自真实字段；读到 99 说明又接回那个后端不存在的字段了')
})

await test('送出桶数不再被当成"预计回桶"（混合单回归）', async () => {
  const { page } = newPage(baseOrder({ deliveryBucketQty: 4, items: [barrelItem({ quantity: 4, suggestedReturnQty: 1 })] }))
  await page.loadOrderDetail(77)
  assert.strictEqual(page.data.order.returnValue, '1 个',
    '不能用 deliveryBucketQty（送出 4）冒充预计回桶，否则混合单会多报本单新买的押金桶')
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
