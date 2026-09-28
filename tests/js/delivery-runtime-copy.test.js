/**
 * 员工端（站长 + 配送员）运行时事实表达回归 —— 2026-09-27 走查 M01/M02/M03/M04/M06、D01/D02/D03/D05/D06/D07。
 *
 * 跑法：node tests/js/delivery-runtime-copy.test.js
 *
 * 真实执行页面处理函数（不是 grep 字符串）；只有"页面上渲染的静态文案"那几条走
 * **剥掉注释后的 WXML 文本**，因为本仓注释里会大段出现"旧文案"（直接整文件 indexOf 会误判）。
 *
 * ⚠️ 反向验证（把修复改回去，本套件应变红）：
 *   · 把 payments 的说明改回「钱已收到但系统还未确认」 ⇒ M01 条红；
 *   · 把 `decorateTodo` 的 `if (count <= 0) return` 删掉 ⇒ M02 两条红；
 *   · 把 TODO_ROUTES.staffBinding 的 `?tab=apply` 去掉、或删掉 staff 页的 onLoad ⇒ M03 两条红；
 *   · 把「订单管理」那一格的 data-url 改回 pages/coordination/index ⇒ M04 条红；
 *   · 把 实体例文案任一处改回带「运营告警」的原句 ⇒ M06 条红；
 *   · 把 `_moneyView` 的 paid 分支删掉（回到"一律红字大字"） ⇒ D01 两条红；
 *   · 把 `deliveryItems` 换成回桶行 `items` ⇒ D02 条红；
 *   · 把 `resultState` 的成功分支改回"进页面就画绿勾" ⇒ D03 一条红；
 *   · 把 settings 的 `'未分配'` 兜底改回来 ⇒ D06 条红；
 *   · 让配送员也能看到待办提醒开关 ⇒ D07 条红。
 */

const assert = require('assert')
const fs = require('fs')
const path = require('path')
const { loadPage, createWx, createApp, armWatchdog, ROOT } = require('./harness')

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

const readFile = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8')
/** 剥掉 WXML 注释后再断言文案：注释里的旧词不算"页面还在渲染它" */
const renderedWxml = (rel) => readFile(rel).replace(/<!--[\s\S]*?-->/g, '')

const COMPLETE_PAGE = 'miniapp-delivery/pages/order/complete.js'

/** 组织一次「完成配送」页：可控的订单详情 + 可控的提交结果 */
function loadComplete(order, opts) {
  const o = opts || {}
  const calls = { completeOrder: [] }
  const stubs = {
    'api/delivery': {
      getOrderDetail: async () => ({ code: 0, data: order }),
      completeOrder: async (id, body) => {
        calls.completeOrder.push({ id, body })
        if (o.completeThrow) throw new Error(o.completeThrow)
        return { code: 0, data: {} }
      }
    },
    'utils/request': { get: async () => ({ code: 0, data: {} }) }
  }
  const wx = createWx()
  const page = loadPage(COMPLETE_PAGE, { stubs, wx, app: createApp() })
  page.setData({ orderId: order.id, from: 'detail' })
  return { page, calls, wx }
}

/** 一条明细（category: 1 桶装水 / 2 瓶装水 / 3 饮水器，后端 product.category） */
const item = (extra) => Object.assign({
  id: 501, productNameSnapshot: '农夫山泉 19L', quantity: 2, barrelItem: true, suggestedReturnQty: 2, category: 1
}, extra || {})

const order = (extra) => Object.assign({
  id: 77,
  status: 2,
  firstBarrelOrder: false,
  paymentMethod: 2,
  paymentStatus: 1,
  needCollect: true,
  payMethodText: '货到付款',
  payState: 'PENDING',
  payStateText: '待收款',
  payHint: '货到付款，配送员送达时收款',
  totalAmount: 40,
  depositAmount: 0,
  items: [item()]
}, extra || {})

console.log('员工端运行时事实表达（真实执行页面处理函数）')

;(async () => {
  const doneWatchdog = armWatchdog()

  /* ============================ M01 待确认收款 ============================ */
  await test('M01 待确认收款页不得说"钱已收到"（status=1 是待收款，不是到账证明）', async () => {
    const wxml = renderedWxml('miniapp-delivery/pages/station-mgmt/payments/index.wxml')
    assert.strictEqual(wxml.indexOf('钱已收到'), -1,
      '「钱已收到」把"待收款"说成了到账；本列表按 payment_record.status = 1 过滤，那不是到账证明')
    assert.ok(wxml.indexOf('还没核实到账') > -1 || wxml.indexOf('确实收到') > -1,
      '要按事实写：请核实实际到账后再确认')
  })

  await test('M01 前端不自带 1/2/3 映射表：statusText 有值用后端的，缺值兜底仍说"待核实"', async () => {
    const list = [
      { id: 1, orderId: 9, amount: 40, methodText: '现金', statusText: '待收款' },
      { id: 2, orderId: null, ticketQty: 3, amount: 60, methodText: '微信' }   // 老后端没下发 statusText
    ]
    const page = loadPage('miniapp-delivery/pages/station-mgmt/payments/index.js', {
      stubs: {
        'api/station-mgmt': {
          getPendingPayments: async () => ({ code: 0, data: list }),
          confirmPayment: async () => ({ code: 0, data: {} })
        }
      },
      wx: createWx(),
      app: createApp()
    })
    await page.loadData()
    assert.strictEqual(page.data.list[0].statusText, '待收款', '后端下发的文案原样渲染')
    assert.strictEqual(page.data.list[1].statusText, '待核实到账',
      '缺值兜底不许写成"已收到"（那是与事实相反），也不该是空占位')
    assert.strictEqual(page.data.list[1].isTicketPurchase, true, '无订单号 = 线上购票，仍要能在这张列表里找到')
  })

  await test('M01 列表加载失败时页面留下常驻原因（不是一句会消失的 toast）', async () => {
    const page = loadPage('miniapp-delivery/pages/station-mgmt/payments/index.js', {
      stubs: {
        'api/station-mgmt': {
          getPendingPayments: async () => { throw new Error('网络连接失败，请检查网络后重试') },
          confirmPayment: async () => ({ code: 0, data: {} })
        }
      },
      wx: createWx(),
      app: createApp()
    })
    assert.strictEqual(typeof page.onRetryLoad, 'function', 'wxml 绑了 onRetryLoad，js 必须真有这个方法')
    await page.loadData()
    assert.ok(page.data.loadError.indexOf('网络') > -1, '失败原因要留在 data.loadError 上')
  })

  /* ============================ M02 零值待办 ============================ */
  /** 首页待办卡：只喂 decorateTodo（它是纯函数式的映射，不请求） */
  function todoPage() {
    return loadPage('miniapp-delivery/pages/coordination/index.js', {
      stubs: { 'api/delivery': {} },
      wx: createWx(),
      app: createApp()
    })
  }

  await test('M02 全零待办：卡里一项都不留（原来两行八个 0 占掉上半屏）', async () => {
    const page = todoPage()
    const payload = {
      items: page.TODO_KEYS.map((k, i) => ({ key: k, label: '事项' + i, count: 0, amount: null, level: 'P2' })),
      p0Total: 0
    }
    const todo = page.decorateTodo(payload)
    assert.strictEqual(todo.items.length, 0, '八个 0 不该渲染出来：' + JSON.stringify(todo.items))
  })

  await test('M02 有可处理事项时照常露出（计数与金额保留）', async () => {
    const page = todoPage()
    const payload = {
      items: [
        { key: 'overdueReceivable', label: '逾期应收', count: 3, amount: 120.5, level: 'P1' },
        { key: 'pendingPayment', label: '待确认收款', count: 0, amount: 0, level: 'P1' },
        { key: 'staffBinding', label: '员工绑定申请', count: 1, amount: null, level: 'P1' }
      ],
      p0Total: 0
    }
    const todo = page.decorateTodo(payload)
    const keys = todo.items.map(i => i.key)
    assert.deepStrictEqual(keys, ['overdueReceivable', 'staffBinding'],
      '只留非零项、并保持后端给的顺序：' + JSON.stringify(keys))
    assert.strictEqual(todo.items[0].amountText, '120.50', '金额格式化仍在 js 里做（wxml 不能调方法）')
    assert.strictEqual(todo.items[0].hasCount, true)
  })

  await test('M02 修 P0 红点这件事不能改坏：卡里永远不含 P0 项，红点只认 payload 的 p0Total', async () => {
    const page = todoPage()
    const payload = {
      // 6 个 P0 项（待分配/转单/客户取消/站内取消/指定外派/退桶）即使非零也不进这张卡
      items: [
        { key: 'pendingAssign', label: '待分配', count: 5, level: 'P0' },
        { key: 'pendingTransfer', label: '转单请求', count: 2, level: 'P0' },
        { key: 'customerCancel', label: '客户取消申请', count: 1, level: 'P0' },
        { key: 'stationCancel', label: '站内取消申请', count: 1, level: 'P0' },
        { key: 'directedIncoming', label: '指定外派待确认', count: 3, level: 'P0' },
        { key: 'barrelReturn', label: '退桶审批', count: 2, level: 'P0' },
        { key: 'overdueReceivable', label: '逾期应收', count: 1, level: 'P1' }
      ],
      p0Total: 6
    }
    const todo = page.decorateTodo(payload)
    assert.deepStrictEqual(todo.items.map(i => i.key), ['overdueReceivable'],
      'P0 项由 tab 角标/红点负责，不该被这张卡重复报一遍（TODO_KEYS 的设计）')
    // 红点的判据在 utils/pending-reminder：p0Total > 0 就亮，与 todo.items 无关
    const reminder = readFile('miniapp-delivery/utils/pending-reminder.js')
    assert.ok(reminder.indexOf('data.p0Total') > -1 || reminder.indexOf('data && data.p0Total') > -1,
      '红点必须读完整 payload 的 p0Total')
    const coordJs = readFile('miniapp-delivery/pages/coordination/index.js')
    assert.ok(coordJs.indexOf('applyRedDot(d)') > -1,
      'loadTodo 必须把**完整** payload 交给 applyRedDot；不许改成用 todo.items 推红点')
  })

  /* ============================ M03 落到申请页签 ============================ */
  await test('M03 首页「员工绑定申请」入口带 ?tab=apply，且 staff 页真的认这个参数', async () => {
    const coord = todoPage()
    assert.strictEqual(coord.TODO_ROUTES.staffBinding, '/pages/station-mgmt/staff/index?tab=apply',
      '两个入口要落到不同页签：待办 → 申请，常规员工管理 → 名单')

    const staffStub = {
      stubs: {
        'api/delivery': { getStaffList: async () => ({ code: 0, data: [] }) },
        'api/station-mgmt': { createStaff: async () => ({ code: 0 }), detachStaff: async () => ({ code: 0 }) },
        'utils/request': { get: async () => ({ code: 0, data: [] }), post: async () => ({ code: 0 }) }
      },
      wx: createWx(),
      app: createApp()
    }
    const applyPage = loadPage('miniapp-delivery/pages/station-mgmt/staff/index.js', staffStub)
    applyPage.onLoad({ tab: 'apply' })
    assert.strictEqual(applyPage.data.activeTab, 'apply', '带参数时落在申请页签')

    const normalPage = loadPage('miniapp-delivery/pages/station-mgmt/staff/index.js', staffStub)
    normalPage.onLoad({})
    assert.strictEqual(normalPage.data.activeTab, 'staff', '常规入口（不带参数）仍默认员工名单')

    const junkPage = loadPage('miniapp-delivery/pages/station-mgmt/staff/index.js', staffStub)
    junkPage.onLoad({ tab: '不存在的页签' })
    assert.strictEqual(junkPage.data.activeTab, 'staff', '不认的参数退回默认，不把页面打成空白')
  })

  /* ============================ M04 查订单入口 ============================ */
  await test('M04 管理目录「订单管理」直达订单台账（不再绕回首页），且台账是真实页面', async () => {
    const wxml = renderedWxml('miniapp-delivery/pages/station-mgmt/index.wxml')
    assert.ok(wxml.indexOf('data-url="/pages/station-mgmt/orders/index"') > -1,
      '「订单管理」要指向台账页；指回 pages/coordination/index 就是把站长绕回首页')
    const coordWxml = renderedWxml('miniapp-delivery/pages/coordination/index.wxml')
    assert.ok(coordWxml.indexOf('查全部订单') > -1,
      '待分配队列**顶部**要有常驻入口，不能只沉在队尾（20 条待分配时得滚到底才找得到）')
    // 台账页必须在 app.json 注册（不注册 = 死链，page_reach_audit 也会红）
    const appJson = JSON.parse(readFile('miniapp-delivery/app.json'))
    assert.ok(appJson.pages.indexOf('pages/station-mgmt/orders/index') > -1, '台账页要注册在 app.json')
    // 台账页不是 tabBar 页 ⇒ 必须走 navigateTo（switchTab 对非 tab 页会失败）
    const idxJs = readFile('miniapp-delivery/pages/station-mgmt/index.js')
    const tabBarBlock = idxJs.slice(idxJs.indexOf('TAB_BAR_PAGES'), idxJs.indexOf('TAB_BAR_PAGES') + 200)
    assert.strictEqual(tabBarBlock.indexOf('orders/index'), -1,
      '台账页不是 tabBar 页，不许被塞进 TAB_BAR_PAGES（会被 switchTab 静默失败）')
  })

  /* ============================ M06 术语 ============================ */
  await test('M06 面向站长/顾客的文案里不得出现「运营告警」（开发/运营术语）', async () => {
    const files = [
      'miniapp-delivery/pages/station-mgmt/exceptions/index.wxml',
      'miniapp-delivery/pages/station-mgmt/index.wxml'
    ]
    files.forEach(f => {
      assert.strictEqual(renderedWxml(f).indexOf('运营告警'), -1, f + ' 里渲染了「运营告警」')
    })
  })

  await test('M06 「画像」不再作为按钮/描述渲染；「核销」只出现在解释里，动作写「登记已收款」', async () => {
    const staffWxml = renderedWxml('miniapp-delivery/pages/station-mgmt/staff/index.wxml')
    const custWxml = renderedWxml('miniapp-delivery/pages/station-mgmt/customers/index.wxml')
    const mgmtWxml = renderedWxml('miniapp-delivery/pages/station-mgmt/index.wxml')
    assert.ok(staffWxml.indexOf('>详情<') > -1, '员工行的按钮应写「详情」')
    assert.strictEqual(staffWxml.indexOf('>画像<'), -1, '「画像」是建模术语')
    assert.ok(custWxml.indexOf('>详情<') > -1, '客户行的按钮应写「详情」')
    assert.strictEqual(custWxml.indexOf('>画像<'), -1)
    assert.strictEqual(mgmtWxml.indexOf('员工、画像'), -1, '目录副标题里也不该有「画像」')

    const recWxml = renderedWxml('miniapp-delivery/pages/station-mgmt/receivables/index.wxml')
    assert.ok(recWxml.indexOf('登记已收款') > -1, '收钱动作要写成站长看得懂的动作')
    assert.ok(recWxml.indexOf('登记就是核销') > -1, '解释里保留「核销」的含义（报告口径）')
  })

  /* ============================ D01 钱卡 ============================ */
  await test('D01 已付款单：主结论是"已付款"，总额不再用红字大字；重复的"已付款 已付款"消失', async () => {
    const { page } = loadComplete(order({
      needCollect: false, paymentMethod: 3, paymentStatus: 2,
      payMethodText: '水票支付', payState: 'PAID', payStateText: '已付款', payHint: '已付款'
    }))
    await page.loadOrder(77)
    assert.strictEqual(page.data.moneyMode, 'paid')
    assert.strictEqual(page.data.moneyEmphasis, false, '已付款不该用警示色大字')
    assert.ok(page.data.moneyHeadline.indexOf('已付款') > -1, '主结论要说清"无需收钱"：' + page.data.moneyHeadline)
    assert.strictEqual(page.data.moneySub, '',
      'payStateText 与 payHint 在 PAID 下是同一句，不该渲染两遍（原来屏幕上印着"已付款 已付款"）')
    // 模板只保留一个（非 collect）分支：
    const wxml = readFile('miniapp-delivery/pages/order/complete.wxml')
    const moneyBlock = wxml.slice(wxml.indexOf('money-card'), wxml.indexOf('money-prep'))
    assert.strictEqual(moneyBlock.indexOf('{{orderInfo.payStateText}}'), -1,
      '钱卡不许再直接铺 payStateText + payHint 两格')
  })

  await test('D01 现金未收：红字大字 = 本次要收的钱（唯一配得上警示色的那种）', async () => {
    const { page } = loadComplete(order())
    await page.loadOrder(77)
    assert.strictEqual(page.data.moneyMode, 'collect')
    assert.strictEqual(page.data.moneyEmphasis, true)
    assert.strictEqual(page.data.moneyHeadline, '本次要收')
  })

  await test('D01 钱没到手但不归本次收（微信/水票未付）：照实说状态，总额降级', async () => {
    const { page } = loadComplete(order({
      needCollect: false, paymentMethod: 1, paymentStatus: 0,
      payMethodText: '微信支付', payState: 'UNPAID', payStateText: '未付款', payHint: '下单后在线支付'
    }))
    await page.loadOrder(77)
    assert.strictEqual(page.data.moneyMode, 'uncollected', '既不收钱也不是已付 ⇒ 第三种形态')
    assert.strictEqual(page.data.moneyEmphasis, false, '不归本次收的钱不该用"要收"的强调色')
    assert.strictEqual(page.data.moneyHeadline, '未付款')
    assert.strictEqual(page.data.moneySub, '下单后在线支付', '与主结论不同的补充说明才带上')
  })

  /* ============================ D02 送货清单 ============================ */
  await test('D02 首单（新押金桶）：本次送货仍列全商品与数量（原来这一块什么都没有）', async () => {
    const { page } = loadComplete(order({
      firstBarrelOrder: true, needCollect: false, paymentStatus: 2, payMethodText: '水票支付',
      items: [
        item({ id: 501, productNameSnapshot: '农夫山泉 19L', quantity: 2, category: 1 }),
        item({ id: 502, productNameSnapshot: '农夫山泉 550ml', quantity: 3, category: 2, barrelItem: false, suggestedReturnQty: null })
      ]
    }))
    await page.loadOrder(77)
    // 回桶行首单恒为空 —— 这正是原来"看不到商品"的原因
    assert.deepStrictEqual(page.data.items, [], '首单不该有回桶行（口径不变）')
    const goods = page.data.deliveryItems
    assert.strictEqual(goods.length, 2, '送货清单必须全量列出：' + JSON.stringify(goods))
    assert.strictEqual(goods[0].name, '农夫山泉 19L')
    assert.strictEqual(goods[0].qtyText, '2 桶', '桶装水按"桶"计')
    assert.strictEqual(goods[1].qtyText, '3 瓶', '瓶装水按"瓶"计 —— 不许一律说成桶（quantity 是全单总件数）')
  })

  await test('D02 送货清单不参与回桶推算（送出数量 ≠ 该回数量）', async () => {
    const { page, calls } = loadComplete(order({
      depositAmount: 60,
      items: [item({ id: 501, quantity: 4, suggestedReturnQty: 2 })],
      needCollect: false, payState: 'PAID', payStateText: '已付款', paymentMethod: 3, paymentStatus: 2
    }))
    await page.loadOrder(77)
    assert.strictEqual(page.data.items[0].expected, 2, '该回数仍取后端 suggestedReturnQty')
    assert.strictEqual(page.data.items[0].actual, 2)
    await page.onConfirmComplete()
    await new Promise(r => setTimeout(r, 10))
    assert.strictEqual(calls.completeOrder[0].body.itemReturns[0].expected, 2,
      '提交的回桶数不许被送货清单里的"送出 4"顶掉（会逼出假的少回收异常）')
    assert.strictEqual(calls.completeOrder[0].body.itemReturns[0].actual, 2)
  })

  /* ============================ D03 结果态 ============================ */
  await test('D03 进页面是未提交态（没有大绿勾），提交中/成功/失败/未知可区分', async () => {
    const { page, wx } = loadComplete(order({
      needCollect: false, payState: 'PAID', payStateText: '已付款', paymentMethod: 3, paymentStatus: 2
    }))
    await page.loadOrder(77)
    assert.strictEqual(page.data.resultState, 'idle', '还没提交，不该是成功态')

    const wxml = readFile('miniapp-delivery/pages/order/complete.wxml')
    const head = wxml.slice(wxml.indexOf('head-card'), wxml.indexOf('本次送货'))
    assert.ok(head.indexOf("resultState === 'success'") > -1,
      '大绿勾必须只在 success 分支里（原来一进页面就画着）')
    assert.ok(head.indexOf('确认送达') > -1, '提交前说的是动作与后果')

    // 提交成功 ⇒ success
    await page._doSubmit()
    await new Promise(r => setTimeout(r, 10))
    assert.strictEqual(page.data.resultState, 'success')

    // 网络断开 ⇒ unknown + 常驻提示（不是"失败"）
    const net = loadComplete(order({
      needCollect: false, payState: 'PAID', payStateText: '已付款', paymentMethod: 3, paymentStatus: 2
    }), { completeThrow: '网络连接失败，请检查网络后重试' })
    await net.page.loadOrder(77)
    await net.page._doSubmit()
    await new Promise(r => setTimeout(r, 10))
    assert.strictEqual(net.page.data.resultState, 'unknown',
      '网络类失败 = 结果未知（订单可能已完成），不能说成失败让人直接重提')
    assert.ok(net.page.data.unknownHint.indexOf('可能已经完成') > -1, '要说清订单可能已经完成')
    assert.strictEqual(net.wx.__calls.nav.length, 0, '结果未知不许跳走')

    // 业务拒绝 ⇒ failed（服务端明确说没做成）
    const biz = loadComplete(order({
      needCollect: false, payState: 'PAID', payStateText: '已付款', paymentMethod: 3, paymentStatus: 2
    }), { completeThrow: '本单的备货记录不完整，暂时无法完成配送。（订单号 77）' })
    await biz.page.loadOrder(77)
    await biz.page._doSubmit()
    await new Promise(r => setTimeout(r, 10))
    assert.strictEqual(biz.page.data.resultState, 'failed')
    assert.strictEqual(biz.page.data.unknownHint, '', '业务失败不该留下"结果未知"提示')
  })

  /* ============================ D05 详情页顺序 ============================ */
  await test('D05 配送详情：订单内容与订单备注排在「历史订单」之前（别让人先滚过经营指标）', async () => {
    const wxml = readFile('miniapp-delivery/pages/order/detail.wxml')
    const iContent = wxml.indexOf('订单内容')
    const iNote = wxml.indexOf('订单备注')
    const iHistory = wxml.indexOf('历史订单')
    assert.ok(iContent > -1 && iNote > -1 && iHistory > -1, '三块都还在')
    assert.ok(iNote < iHistory, '客户交待的话必须排在历史频次之前')
    assert.ok(iContent < iHistory, '送什么也要排在历史频次之前')
  })

  await test('D04 详情页不得把系统操作记录挂成「客户备注」', async () => {
    const wxml = renderedWxml('miniapp-delivery/pages/order/detail.wxml')
    assert.strictEqual(wxml.indexOf('客户备注'), -1,
      'order.notes 拆的是 special_note（客户留言 + 系统留痕混在一列），挂"客户备注"等于冒称客户说的话')
    assert.ok(wxml.indexOf('订单备注') > -1, '中性标签下内容一条不删')
    // 也不许用"像不像系统标记"的字符串规则去截客户原话
    const js = readFile('miniapp-delivery/pages/order/detail.js')
    const noteBlock = js.slice(js.indexOf('order.specialNote'), js.indexOf('order.specialNote') + 260)
    assert.ok(noteBlock.indexOf('split') > -1, '仍按换行拆行展示')
    assert.strictEqual(noteBlock.indexOf('startsWith'), -1,
      '不许按前缀过滤：标记形态没有契约保证，会把客户原话一起切掉')
  })

  /* ============================ D06 / D07 设置页 ============================ */
  await test('D06 已绑定但缺站名：显示「名称暂未加载」+ 可重试，不许写成「未分配」', async () => {
    const app = createApp()
    app.globalData.userInfo = { staffId: 4, role: 'DELIVERY', stationId: 3, bindStatus: 'BOUND' }
    const wx = createWx()
    const page = loadPage('miniapp-delivery/pages/settings/index.js', {
      stubs: { 'utils/request': { get: async () => ({ code: 0, data: { name: '示例水站' } }) } },
      wx,
      app
    })
    page.loadSettings()
    assert.strictEqual(page.data.stationStateText, '名称暂未加载',
      '已绑定但站名没拿到，是"没加载出来"而不是"未分配"（后者是另一种事实）')
    assert.strictEqual(page.data.stationNameRetry, true, '要给一个重试的口子')
    const wxml = renderedWxml('miniapp-delivery/pages/settings/index.wxml')
    assert.strictEqual(wxml.indexOf('未分配'), -1, '硬编码「未分配」会与「我的」页的"已绑定"自相矛盾')
  })

  await test('D06 重试成功后补上真实站名（并落本地缓存）', async () => {
    const app = createApp()
    app.globalData.userInfo = { staffId: 4, role: 'DELIVERY', stationId: 3, bindStatus: 'BOUND' }
    const wx = createWx()
    const page = loadPage('miniapp-delivery/pages/settings/index.js', {
      stubs: { 'utils/request': { get: async () => ({ code: 0, data: { name: '示例水站' } }) } },
      wx,
      app
    })
    page.loadSettings()
    await page.onRetryStationName()
    assert.strictEqual(page.data.stationName, '示例水站')
    assert.strictEqual(page.data.stationStateText, '示例水站')
    assert.strictEqual(page.data.stationNameRetry, false)
  })

  await test('D06 确实没有水站时说「未绑定水站」/审核中说「绑定审核中」（三种事实分开）', async () => {
    const mk = (bindStatus, stationId) => {
      const app = createApp()
      app.globalData.userInfo = { staffId: 4, role: 'DELIVERY', stationId, bindStatus }
      return loadPage('miniapp-delivery/pages/settings/index.js', {
        stubs: { 'utils/request': { get: async () => ({ code: 0, data: null }) } },
        wx: createWx(),
        app
      })
    }
    const unbound = mk('UNBOUND', null); unbound.loadSettings()
    assert.strictEqual(unbound.data.stationStateText, '未绑定水站')
    const pending = mk('PENDING', null); pending.loadSettings()
    assert.strictEqual(pending.data.stationStateText, '绑定审核中')
  })

  await test('D07 待办提醒开关只对站长渲染；配送员看到的是说明，不会说到不存在的「首页」', async () => {
    const manager = createApp()
    manager.globalData.userInfo = { staffId: 1, role: 'STATION_MANAGER', stationId: 3, stationName: '示例水站' }
    const mPage = loadPage('miniapp-delivery/pages/settings/index.js', {
      stubs: { 'utils/request': { get: async () => ({ code: 0, data: null }) } },
      wx: createWx(), app: manager
    })
    mPage.loadSettings()
    assert.strictEqual(mPage.data.isManager, true, '站长才给开关')

    const delivery = createApp()
    delivery.globalData.userInfo = { staffId: 4, role: 'DELIVERY', stationId: 3, stationName: '示例水站' }
    const dPage = loadPage('miniapp-delivery/pages/settings/index.js', {
      stubs: { 'utils/request': { get: async () => ({ code: 0, data: null }) } },
      wx: createWx(), app: delivery
    })
    dPage.loadSettings()
    assert.strictEqual(dPage.data.isManager, false)
    const wxml = renderedWxml('miniapp-delivery/pages/settings/index.wxml')
    assert.ok(wxml.indexOf('{{isManager}}') > -1, '通知设置整块必须按角色分叉')
    assert.ok(wxml.indexOf('配送端没有「首页」页签') > -1,
      '配送员那一支要如实说明这条提醒对他不起作用（原来只说"首页标签亮红点"，而他没有首页）')
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
