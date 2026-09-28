/**
 * 走查「入口 → 落地视图」一致性 + 文案禁开发词（2026-09-27 走查 M04 / M05 / M06 / C07 / X02）。
 *
 * 跑法：node tests/js/entry-landing-consistency.test.js
 *
 * 两件事：
 *   ① **入口与落地页签/筛选必须对得上**（M03 的页签已在 delivery-runtime-copy 里测了；
 *      这里测 M05 的逾期入口与 M04 的订单台账入口），断言的是"点了那个入口，落地页读到的参数
 *      真的会改变页面状态"——而不是"某个字符串写在了文件里"；
 *   ② **渲染文案禁开发词**（AGENTS §6：接口/后端/前端/服务端/落库/端点/字段/部署/重新构建
 *      不许出现在 `<text>` 里）。静态扫描，但它扫的是**剥掉注释后的渲染文本**，
 *      且明确记录一处豁免（顾客端自动上报的诊断内容，见下方 EXEMPT）。
 *
 * ⚠️ 反向验证（把修复改回去，本套件应变红）：
 *   · 把 TODO_ROUTES.overdueReceivable 的 `?overdue=1` 去掉 ⇒ M05 两条红（入口不带参 + 落地不筛）；
 *   · 删掉 receivables 的 `onLoad` / `onFilterAll` ⇒ M05 三条红；
 *   · 把 receivables 页的 `{{overview.scopeNote}}` 铺回页面（不套 help-tip） ⇒ M05 口径条红；
 *   · 在任一页面的 `<text>` 里写回「接口」/「后端」这类词 ⇒ 禁词条红。
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
const renderedWxml = (rel) => readFile(rel).replace(/<!--[\s\S]*?-->/g, '')

/** 走查证据目录里的图是本次修复的前后对照，不参与文案扫描 */
const SKIP_DIRS = ['station-mgmt\\barrel-return', 'station-mgmt\\dashboard']
/** 有意豁免：顾客端「自动上报·系统错误」的诊断正文，见文件头说明 */
const EXEMPT = ['miniapp-user\\utils\\request.js']

function walk(dir, out) {
  fs.readdirSync(dir, { withFileTypes: true }).forEach((e) => {
    const full = path.join(dir, e.name)
    if (e.isDirectory()) walk(full, out)
    else out.push(full)
  })
  return out
}

console.log('入口 → 落地视图一致性 + 文案禁开发词')

;(async () => {
  const doneWatchdog = armWatchdog()

  /* ============================ M05 逾期入口与落地视图 ============================ */
  await test('M05 首页「逾期应收」入口带上筛选参数（原来只传路径、落地是全部待收）', async () => {
    const coord = loadPage('miniapp-delivery/pages/coordination/index.js', {
      stubs: { 'api/delivery': {} }, wx: createWx(), app: createApp()
    })
    const url = coord.TODO_ROUTES.overdueReceivable
    assert.ok(url.indexOf('/pages/station-mgmt/receivables/index') === 0, '仍指向应收账款页：' + url)
    assert.ok(url.indexOf('overdue=1') > -1, '必须带 `?overdue=1` 才能落到逾期视图：' + url)
  })

  await test('M05 应收账款页认 `?overdue=1`：落地即逾期视图，且能切回全部', async () => {
    const page = loadPage('miniapp-delivery/pages/station-mgmt/receivables/index.js', {
      stubs: {
        'utils/request': {
          get: async () => ({ code: 0, data: { scopeNote: '待收款 = 支付状态为待收款且未取消的订单合计', customers: [] } }),
          post: async () => ({ code: 0, data: {} }),
          put: async () => ({ code: 0, data: {} })
        }
      },
      wx: createWx(), app: createApp()
    })
    assert.strictEqual(typeof page.onLoad, 'function', '页面必须解析入口带来的查询参数')
    page.onLoad({ overdue: '1' })
    assert.strictEqual(page.data.onlyOverdue, true, '带参数进来 = 逾期视图')

    // 两个筛选处理函数都要真实存在（wxml 绑了它们；不存在 = 点击静默无反应）
    assert.strictEqual(typeof page.onFilterAll, 'function')
    assert.strictEqual(typeof page.onFilterOverdue, 'function')

    // 有逾期 / 没逾期两类客户：只保留有逾期的
    page.setData({
      allCustomers: [
        { customerId: 1, customerName: '甲', outstandingAmount: 100, overdueAmount: 100, overdueOrderCount: 1 },
        { customerId: 2, customerName: '乙', outstandingAmount: 40, overdueAmount: 0, overdueOrderCount: 0 }
      ]
    })
    page._applyOverdueFilter()
    assert.deepStrictEqual(page.data.customers.map(c => c.customerId), [1], '逾期视图只留逾期客户')
    page.onFilterAll()
    assert.deepStrictEqual(page.data.customers.map(c => c.customerId), [1, 2],
      '必须保留未逾期应收的查询能力（报告 M05 验收）')
    assert.strictEqual(page.data.onlyOverdue, false)
    page.onFilterOverdue()
    assert.deepStrictEqual(page.data.customers.map(c => c.customerId), [1])
  })

  await test('M05 总览数字不因筛选被改（口径仍由服务端算，前端不重算）', async () => {
    const page = loadPage('miniapp-delivery/pages/station-mgmt/receivables/index.js', {
      stubs: {
        'utils/request': {
          get: async () => ({
            code: 0,
            data: {
              outstandingAmount: 140, overdueAmount: 100, customerCount: 2, overdueCustomerCount: 1,
              scopeNote: 'x', customers: [{ customerId: 1, overdueAmount: 100, overdueOrderCount: 1 }]
            }
          }),
          post: async () => ({ code: 0, data: {} }),
          put: async () => ({ code: 0, data: {} })
        }
      },
      wx: createWx(), app: createApp()
    })
    page.onLoad({ overdue: '1' })
    await page.load()
    assert.strictEqual(page.data.overview.outstandingAmount, 140, '总览仍是服务端下发的全量口径')
    assert.strictEqual(page.data.overview.overdueAmount, 100)
    assert.strictEqual(page.data.customers.length, 1, '列表按逾期筛过')
  })

  await test('M05 页面口径说明收进「?」：不把数据库注释铺在正文里', async () => {
    const wxml = renderedWxml('miniapp-delivery/pages/station-mgmt/receivables/index.wxml')
    assert.ok(wxml.indexOf('这里只算客户还没付的订单款') > -1, '正文要写成人话')
    assert.strictEqual(/<text[^>]*>\s*\{\{overview\.scopeNote\}\}/.test(wxml), false,
      'scopeNote（「支付状态为待收款且未取消…」）是数据库口径，不许当成正文铺在页面上')
    // 后端那句口径仍然可达（只是不再常显）：作为 help-tip 的 text 传入
    assert.ok(wxml.indexOf('help-tip') > -1, '口径要收进 help-tip')
    assert.ok(wxml.indexOf('text="{{overview.scopeNote}}"') > -1,
      '口径正文不该被丢掉，只是不该铺在页面上')
  })

  await test('M05 逾期视图的空态给出回「全部待收」的路（空列表 ≠ 本站账全清了）', async () => {
    const wxml = renderedWxml('miniapp-delivery/pages/station-mgmt/receivables/index.wxml')
    assert.ok(wxml.indexOf('onlyOverdue') > -1, '空态要按筛选分叉')
    assert.ok(wxml.indexOf('没有逾期的欠款客户') > -1, '逾期视图的空态要说清"只是没有逾期的"')
  })

  /* ============================ M06：渲染文案禁开发词 ============================ */
  await test('M06/X02 两端 <text> 渲染文本里不得出现开发词（AGENTS §6）', async () => {
    const BAD = ['接口', '后端', '前端', '服务端', '落库', '端点', '字段', '部署', '重新构建']
    const hits = []
    ;['miniapp-delivery', 'miniapp-user'].forEach((appRoot) => {
      const files = walk(path.join(ROOT, appRoot), [])
        .filter(f => f.endsWith('.wxml'))
        .filter(f => !SKIP_DIRS.some(d => f.indexOf(d) > -1))
      files.forEach((full) => {
        const rel = path.relative(ROOT, full)
        const text = renderedWxml(path.relative(ROOT, full))
        const nodes = text.match(/<text[^>]*>[\s\S]*?<\/text>/g) || []
        nodes.forEach((n) => {
          BAD.forEach((w) => {
            if (n.indexOf(w) > -1) hits.push(rel + ' :: ' + w + ' :: ' + n.replace(/\s+/g, ' ').slice(0, 90))
          })
        })
      })
    })
    assert.deepStrictEqual(hits, [], '下面这些渲染文本里出现了开发词：\n      ' + hits.join('\n      '))
  })

  await test('M06 两端 js 里会弹给用户看的 strings 也不得含开发词（豁免项已登记）', async () => {
    const BAD = ['接口', '后端', '前端', '服务端', '落库', '端点', '部署', '重新构建']
    const PAT = /(title|content|confirmText|cancelText)\s*:\s*(['"`])((?:\\.|(?!\2).)*)\2/g
    const hits = []
    ;['miniapp-delivery', 'miniapp-user'].forEach((appRoot) => {
      walk(path.join(ROOT, appRoot), [])
        .filter(f => f.endsWith('.js'))
        .filter(f => !SKIP_DIRS.some(d => f.indexOf(d) > -1))
        .forEach((full) => {
          const rel = path.relative(ROOT, full)
          if (EXEMPT.some(e => rel === e)) return   // 有意豁免：见文件头
          readFile(rel).split('\n').forEach((line, i) => {
            const s = line.trim()
            if (s.startsWith('//') || s.startsWith('*') || s.startsWith('/*')) return
            let m
            PAT.lastIndex = 0
            while ((m = PAT.exec(line)) !== null) {
              BAD.forEach((w) => {
                if (m[3].indexOf(w) > -1) hits.push(rel + ':' + (i + 1) + ' :: ' + w + ' :: ' + m[3].slice(0, 70))
              })
            }
          })
        })
    })
    assert.deepStrictEqual(hits, [], '这些会弹给用户看的文案里有开发词：\n      ' + hits.join('\n      '))
  })

  await test('M06 待办卡标题与页签文案里没有「运营告警」这类内部叫法', async () => {
    const coord = loadPage('miniapp-delivery/pages/coordination/index.js', {
      stubs: { 'api/delivery': {} }, wx: createWx(), app: createApp()
    })
    // operationAlert 是**后端下发的 key**（页面只做路由，label 由服务端给），key 不改；
    // 这里断言的是"路由指向处理留痕页签"，而不是文案
    assert.strictEqual(coord.TODO_ROUTES.operationAlert,
      '/pages/station-mgmt/exceptions/index?tab=alerts')
  })

  console.log('')
  doneWatchdog()
  if (failures.length) {
    console.log('失败 ' + failures.length + ' 项 / 通过 ' + passed + ' 项')
    process.exitCode = 1
  } else {
    console.log('全部通过：' + passed + ' 项（流程测试 + 静态文案扫描）')
    console.log(MARK_ASCII + ' ' + passed)
  }
})()
