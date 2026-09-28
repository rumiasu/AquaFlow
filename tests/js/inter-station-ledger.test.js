/**
 * 站长端「站间结算」台账页流程测试（v67 / 2026-09-27 产品拍板 4.b）。
 *
 * 跑法：node tests/js/inter-station-ledger.test.js
 *
 * **锁的是什么**：这一页是「每笔钱知道谁收、谁欠谁、什么时候算办完」这条验收标准的界面落点，
 * 而它最容易出的三种错都不报错：
 *   ① 页面**自己算**金额或方向（口径就分叉了 —— 后端算的是"实收 − 营收"的差额，
 *      前端重算必然与它不一致，而且两边都不报错）；
 *   ② require 的 api 函数与导出**对不上**（JS 里是 `undefined`，调用时才 TypeError）；
 *   ③ 加载/操作失败被吞掉（站长会以为"本站没有站间欠款"）。
 *
 * 覆盖五件事：
 *   ① 金额/方向/口径文案**原样来自后端**，页面不改写、不重算；
 *   ② `canSettle` / `canChangePrice` 原样透传（能不能操作由**后端判权**，前端只决定显不显示按钮）；
 *   ③ 「登记结清」走 `settleInterStation(orderId, note)`；
 *   ④ 「冲销」走 `reverseInterStation`，且**失败要出声**（把后端原因原样透出）；
 *   ⑤ 结清时间与凭据说明会被拼进展示串（那是"什么时候算办完"的答案，不能被丢掉）。
 */

const assert = require('assert')
const fs = require('fs')
const path = require('path')
const { loadPage, createWx, createApp, armWatchdog, ROOT } = require('./harness')

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

/** 后端台账响应的形状（字段名以 InterStationSettlementServiceImpl#ledgerOf 为准）。 */
function ledgerPayload() {
  return {
    stationId: 2,
    items: [
      {
        orderId: 134, fromStationId: 1, fromStationName: '甲站',
        toStationId: 2, toStationName: '乙站',
        direction: 'RECEIVE', directionText: '别人欠本站',
        amount: 24.0, basis: 3, basisText: '水票按挂牌价',
        ticketQty: 2, unitPrice: 10.0, feeAmount: 0,
        paymentMethod: 3, paymentMethodText: '水票',
        revenueAmount: 24.0, waterAmount: 24.0, ticketActualAmount: 20.0,
        status: 1, statusText: '待结清', settledTime: null, settleNote: null,
        canChangePrice: false, canSettle: false
      },
      {
        orderId: 140, fromStationId: 2, fromStationName: '乙站',
        toStationId: 1, toStationName: '甲站',
        direction: 'PAY', directionText: '本站欠别人',
        amount: 13.0, basis: 1, basisText: '本单营收照实结',
        ticketQty: 0, unitPrice: null, feeAmount: 0,
        paymentMethod: 1, paymentMethodText: '微信',
        revenueAmount: 13.0, waterAmount: 12.0, ticketActualAmount: 0,
        status: 2, statusText: '已结清',
        settledTime: '2026-09-27T22:10:00', settleNote: '转账 20260927-001',
        canChangePrice: false, canSettle: false
      }
    ],
    unsettledCount: 1,
    receivableAmount: 24.0, payableAmount: 13.0, netAmount: 11.0,
    settledReceivableAmount: 0, settledPayableAmount: 13.0,
    danglingSettled: [
      {
        orderId: 141, fromStationId: 2, fromStationName: '乙站',
        toStationId: 1, toStationName: '甲站',
        direction: 'PAY', directionText: '本站欠别人',
        amount: 13.0, basis: 2, basisText: '水票折算实付',
        ticketQty: 1, unitPrice: 13.0,
        status: 2, statusText: '已结清',
        settledTime: '2026-09-27T21:00:00', settleNote: null
      }
    ],
    scopeNote: '这里只算已收款、未取消的跨站单'
  }
}

function newPage(sc) {
  const s = sc || {}
  const calls = { settle: [], reverse: [], ledger: 0 }

  const stubs = {
    'api/station-mgmt': {
      getInterStationSettlements: async () => {
        calls.ledger++
        if (s.loadThrows) throw new Error(s.loadThrows)
        return { code: 0, data: s.payload || ledgerPayload() }
      },
      settleInterStation: async (orderId, note) => {
        calls.settle.push({ orderId, note })
        if (s.settleThrows) throw new Error(s.settleThrows)
        return { code: 0, data: {} }
      },
      reverseInterStation: async (orderId) => {
        calls.reverse.push(orderId)
        if (s.reverseThrows) throw new Error(s.reverseThrows)
        return { code: 0, data: { changed: true } }
      }
    },
    'api/delivery': {
      priceByListed: async () => ({ code: 0, data: {} })
    }
  }

  const wx = createWx()
  const app = createApp()
  const page = loadPage('miniapp-delivery/pages/station-mgmt/inter-station/index.js',
    { stubs, wx, app })
  return { page, calls, wx }
}

;(async () => {
  const doneWatchdog = armWatchdog()
  console.log('站间结算台账 · 谁欠谁 / 欠多少 / 什么时候算办完（v67 回归）')

  await test('① 金额与方向原样来自后端，页面不重算（口径只有服务端一处）', async () => {
    const { page } = newPage()
    await page.loadData()
    const it = page.data.items[0]
    assert.strictEqual(it.amount, 24.0, '金额必须是后端下发的那个数，不能在页面里再算一遍')
    assert.strictEqual(it.directionText, '别人欠本站', '方向文案由后端下发（前端不自造同义句）')
    assert.strictEqual(it._amountText, '24.00', '展示串只做补零，不改数值')
    assert.strictEqual(page.data.summary.receivableText, '24.00')
    assert.strictEqual(page.data.summary.payableText, '13.00')
    assert.strictEqual(page.data.summary.netPositive, true, '净额为正 ⇒ 别人净欠本站')
    assert.strictEqual(page.data.scopeNote, '这里只算已收款、未取消的跨站单',
      '口径说明整句来自服务端（前端不自造）')
  })

  await test('② canSettle / canChangePrice 原样透传（判权在后端，前端只决定显不显示）', async () => {
    const { page } = newPage()
    await page.loadData()
    assert.strictEqual(page.data.items[0].canSettle, false,
      '票单的收款方不能自己登记结清 —— 按钮显不显示听后端的')
    assert.strictEqual(page.data.items[1].canSettle, false)
  })

  await test('③ 「登记结清」走 settleInterStation，带上订单号与凭据说明', async () => {
    const payload = ledgerPayload()
    payload.items[1].canSettle = true
    const { page, calls } = newPage({ payload })
    await page.loadData()
    page.onSettle({ currentTarget: { dataset: { id: 140, amount: '13.00' } } })
    await new Promise((r) => setTimeout(r, 0))
    assert.strictEqual(calls.settle.length, 1, '应调用 settleInterStation')
    assert.strictEqual(calls.settle[0].orderId, 140)
    assert.ok(typeof calls.settle[0].note === 'string', '凭据说明即使留空也要以字符串传下去')
  })

  await test('④ 「冲销」走 reverseInterStation；失败必须出声并原样透出后端原因', async () => {
    const reason = '本单与本站无关，不能冲销'
    const { page, calls, wx } = newPage({ reverseThrows: reason })
    await page.loadData()
    page.onReverse({ currentTarget: { dataset: { id: 141, amount: '13.00' } } })
    await new Promise((r) => setTimeout(r, 0))
    assert.strictEqual(calls.reverse[0], 141)
    const toast = wx.__calls.toast[wx.__calls.toast.length - 1]
    assert.ok(toast, '失败必须出声（静默失败 = 站长以为冲销成功了）')
    assert.strictEqual(toast.title, reason, '后端拒的原因要原样展示，不许改写成"操作失败"')
  })

  await test('⑤ 「什么时候算办完」的展示串不能丢：结清时间 + 凭据说明都要出现', async () => {
    const { page } = newPage()
    await page.loadData()
    const settled = page.data.items[1]._settledText
    assert.ok(/已结清/.test(settled), '已结清的行要标出状态，实际=' + settled)
    assert.ok(/2026-09-27 22:10/.test(settled), '要显示结清时间（那是"办完"的答案），实际=' + settled)
    assert.ok(/转账 20260927-001/.test(settled), '凭据说明要显示出来，实际=' + settled)
  })

  await test('⑥ 加载失败必须出声（不许让站长以为"没人欠我钱"）', async () => {
    const { page, wx } = newPage({ loadThrows: '网络连接失败' })
    await page.loadData()
    const toast = wx.__calls.toast[wx.__calls.toast.length - 1]
    assert.ok(toast, '拉不到台账要弹提示')
    assert.strictEqual(toast.title, '网络连接失败')
  })

  await test('⑦ 站长待办卡里看得到它：key 与路由都在，且路由指向**已注册**的真实页面', async () => {
    const js = fs.readFileSync(path.join(ROOT, 'miniapp-delivery/pages/coordination/index.js'), 'utf8')
    // ⚠️ 必须**只扫 TODO_KEYS 那一段**：第一版拿整个文件做正则，结果"key 从 TODO_KEYS 里删掉"
    //    它照样绿（TODO_ROUTES 里还有同名 key）—— 一条永远不会红的断言等于没有断言。
    const keysBlock = js.slice(js.indexOf('TODO_KEYS:'), js.indexOf('TODO_ROUTES:'))
    assert.ok(keysBlock.length > 0, '应能定位到 TODO_KEYS 清单（改动本文件时别把标记改名）')
    assert.ok(/interStationUnsettled/.test(keysBlock),
      '待办卡的 key 清单（TODO_KEYS）里必须有 interStationUnsettled —— 否则后端报了角标、首页也不显示')
    assert.ok(/interStationUnsettled:\s*'\/pages\/station-mgmt\/inter-station\/index'/.test(js),
      'TODO_ROUTES 要把它指到站间结算页（清单有、路由没有 ⇒ 点一下只弹"暂未接入页面"）')

    const appJson = JSON.parse(fs.readFileSync(path.join(ROOT, 'miniapp-delivery/app.json'), 'utf8'))
    assert.ok(appJson.pages.indexOf('pages/station-mgmt/inter-station/index') >= 0,
      'route 指向的页面必须在 app.json 的 pages 里注册，否则 navigateTo 会失败')
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
