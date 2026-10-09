/**
 * 退桶审批页 · 「退押金并当面交付」流程测试（v66 / 2026-09-27）。
 *
 * 跑法：node tests/js/barrel-return-refund.test.js
 *
 * **锁的是什么**：`docs/design/35` §7 拍板后的第 3 步语义 —— 押金**核销与当面交付是同一次动作**，
 * 所以要验的不是文案，而是**页面真的把 `refundChannel` 传下去了、并且失败原因原样透出**。
 *
 * 覆盖四件事：
 *   ① 页面 require 的 api 函数与 `api/station-mgmt.js` 的导出**真的对得上**
 *      （require/export 拼错在 JS 里是 `undefined`，调用时才 TypeError —— 静态门禁查不出来，
 *       而本页的路径刚刚从"写死在页面里"搬进 `config/api.js` + 包装函数，正是最容易拼错的时刻）；
 *   ② `CASH` 提交 ⇒ 走 `updateBarrelRecordStatus(id, 3, {refundChannel:'CASH'})`；
 *   ③ `ONLINE` 被后端拒绝（微信退款通道未接入）⇒ **原因原样透出**，不许被改写成"操作失败"
 *      （资格查询后的写入拒绝仍须保留原渠道责任，不自行切现金）；
 *   ④ 「登记押金已交付」⇒ 走 `markRefundPaid`（补登记的那条路），不是再退一次款。
 */

const assert = require('assert')
const { loadPage, createWx, createApp, armWatchdog, ROOT } = require('./harness')
const fs = require('fs')
const path = require('path')
const { parseWxml, renderElements } = require('./wxml-tree')

// 完成哨兵：**只用 ASCII**（受限沙箱下子进程往 fd 写中文会被编码毁成 `?`，见 skill §8.31）。
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

function newPage(sc) {
  const s = sc || {}
  const calls = { status: [], paid: [], undelivered: 0 }
  const rows = [7, 8, 12].map(id => ({ id, stationId: 1, type: 2, status: id === 12 ? 3 : 2,
    customerId: 7, productId: 1, quantity: 1, depositRefund: 50, createTime: '2026-10-07T09:00:00' }))
  const read = id => rows.find(row => String(row.id) === String(id))

  const stubs = {
    'api/business-rules': { getReturnApplications: async args => ({ code: 0, data: { stationId: 1, scope: args.scope, limit: 50,
      items: rows.slice().sort((a,b) => b.id-a.id), nextBeforeId: null } }) },
    'utils/pending-reminder': { getPendingReturnRecord: async id => ({ code: 0, data: read(id) }), syncPendingReminder() {} },
    'api/station-mgmt': {
      getAllBarrelRecords: async () => ({ code: 0, data: rows }),
      getBarrelRefundEligibility: async id => ({ code: 0, data: { recordId: id, recordStatus: read(id).status, available: true,
        channel: s.channel || 'CASH', refundAmount: 50, reason: '原收款方式已核对' } }),
      updateBarrelRecordStatus: async (id, status, extra) => {
        calls.status.push({ id, status, extra })
        if (s.statusThrows) throw new Error(s.statusThrows)
        Object.assign(read(id), { status, refundPaidTime: '2026-10-07T10:30:00' })
        return { code: 0, data: {} }
      },
      markRefundPaid: async (id) => {
        calls.paid.push(id)
        read(id).refundPaidTime = '2026-10-07T10:30:00'
        return { code: 0, data: { alreadyPaid: false } }
      },
      getRefundUndelivered: async () => {
        calls.undelivered++
        return { code: 0, data: { records: [], count: 0, amount: '0' } }
      }
    }
  }

  const wx = createWx()
  const page = loadPage('miniapp-delivery/pages/station-mgmt/barrel-return/index.js',
    { stubs, wx, app: createApp({ globalData: { isLogin: true, userInfo: { staffId: 1, stationId: 1, role: 'STATION_MANAGER' } } }) })
  return { page, calls, wx }
}

;(async () => {
  const doneWatchdog = armWatchdog()
  console.log('退桶审批 · 退押金并当面交付（v66 回归）')

  await test('① 只读自查真的打到了 api 包装函数（require/export 对不上会在这里红）', async () => {
    const { page, calls } = newPage()
    await page.loadUndelivered()
    assert.strictEqual(calls.undelivered, 1,
      '页面必须通过 api/station-mgmt 的包装函数取「已核销未交付」，'
      + '而不是自己拼路径 —— require 名字拼错的话这里是 0 次调用（静默 undefined）')
  })

  await test('② CASH 提交：走 status=3 且必须带上 refundChannel', async () => {
    const { page, calls } = newPage()
    await page.loadData()
    await page.onSelectRecord({ currentTarget: { dataset: { id: 7 } } })
    await page.onRefund({ currentTarget: { dataset: { id: 7 } } })
    assert.strictEqual(calls.status.length, 1, '应发出 1 次状态变更请求')
    assert.strictEqual(calls.status[0].id, 7)
    assert.strictEqual(calls.status[0].status, 3, '第 3 步 = 已退押金')
    assert.strictEqual(calls.status[0].extra && calls.status[0].extra.refundChannel, 'CASH',
      'v66 起第 3 步**必须**带 refundChannel —— 不带就表达不出这笔钱怎么交出去的'
      + '（产品口径「不现场给钱的不要退」）')
  })

  await test('③ ONLINE 被后端拒绝 ⇒ 原因原样透出，不许改写成"操作失败"', async () => {
    const reason = '线上退款暂未开通，本笔押金仍待按原渠道退回'
    const { page, calls } = newPage({ statusThrows: reason, channel: 'ONLINE' })
    await page.loadData()
    await page.onSelectRecord({ currentTarget: { dataset: { id: 8 } } })
    await page.onRefund({ currentTarget: { dataset: { id: 8 } } })
    assert.ok(page.data.actionError.includes(reason), '保留写入拒绝原因，回查原记录后仍显示待办责任')
    assert.strictEqual(calls.status.length, 1, '拒绝后不自动重发退款')
    assert.strictEqual(calls.status[0].extra.refundChannel, 'ONLINE', '不自行切换现金')
  })

  await test('④ 「登记押金已交付」走补登记端点，不是再退一次款', async () => {
    const { page, calls } = newPage()
    // harness 的 showModal 默认"用户点了确认"
    await page.loadData()
    await page.onConfirmPaid({ currentTarget: { dataset: { id: 12, amount: '50.00' } } })
    assert.strictEqual(calls.paid.length, 1, '应调用 markRefundPaid 补登记交付')
    assert.strictEqual(calls.paid[0], 12)
    assert.strictEqual(calls.status.length, 0,
      '补登记只补"钱已交给顾客"这个事实：不动状态、不动金额（再退一次款就是重复退钱）')
  })

  function refundFactNodes() {
    const root = parseWxml(fs.readFileSync(path.join(ROOT, 'miniapp-user/pages/barrel/index.wxml'), 'utf8'))
    const nodes = []
    function visit(node) {
      if (node.attrs && ['{{item.statusText && item.refundPaidTime}}', '{{item.statusText && item.handleNote}}'].includes(node.attrs['wx:if'])) nodes.push(node)
      for (const child of node.children || []) visit(child)
    }
    visit(root)
    assert.strictEqual(nodes.length, 2)
    return { tag: '#root', children: nodes }
  }
  await test('顾客只有真实退款登记时间时才看到交付登记，已退款状态不能代造时间', async () => {
    const root = refundFactNodes()
    const visible = item => renderElements(root, { item })
    assert.strictEqual(visible({ status: 3, statusText: '押金已交付' }).length, 0)
    assert.strictEqual(visible({ statusText: '押金已交付', refundPaidTime: '2026-10-05 18:00:00' }).length, 1)
    assert.strictEqual(visible({ type: 8, status: 3 }).length, 0)
    const elements = visible({ statusText: '押金已交付', refundPaidTime: '2026-10-05 18:00:00', handleNote: '现金当面交付' })
    assert.strictEqual(elements.length, 2)
    assert.ok(elements.every(e => !e.attrs.bindtap && !e.attrs.catchtap), '只读展示不增加客户二次确认资金动作')
  })

  doneWatchdog()
  console.log('')
  if (failures.length) {
    console.log('失败 ' + failures.length + ' 项：')
    failures.forEach(f => console.log('  - ' + f.name + ' :: ' + (f.error && f.error.message)))
    process.exit(1)
  }
  console.log('全部通过：' + passed + ' 项（流程测试，真实执行页面处理函数）')
  console.log(MARK_ASCII + ' ' + passed)
})()
