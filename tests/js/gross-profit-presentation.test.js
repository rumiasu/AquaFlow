'use strict'
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { loadPage, createApp, createWx, ROOT } = require('./harness')
const { parseWxml, renderElements } = require('./wxml-tree')
const { createNavigator } = require('../../miniapp-delivery/utils/navigation')

const relWxml = 'miniapp-delivery/pages/station-mgmt/gross-profit/index.wxml'
const relWxss = 'miniapp-delivery/pages/station-mgmt/gross-profit/index.wxss'
const wxml = fs.readFileSync(path.join(ROOT, relWxml), 'utf8')
const styles = fs.readFileSync(path.join(ROOT, relWxss), 'utf8')
const tree = parseWxml(wxml)
const report = {
  from: '2026-11-01', to: '2026-11-09',
  items: [], costComplete: true, profitComparable: true, profitUnavailableReason: null, orderCount: 1,
  orderRevenue: 40, ticketRevenue: 0, deliveryFee: 5, floorFee: 2,
  totalIncome: 47, orderIncome: 47, totalCost: 24, wage: 6, netProfit: 17,
  totalRevenue: 40, totalProfit: 16,
  incomeBasisNote: '含尚未收款的订单水费、服务费与已收票款，不等于到账现金。',
  periodBasisNote: '订单按下单日，购票按收款日；水票收款与兑水成本可能跨期。',
  helpNote: [
    '按服务端口径查看本期经营数据。',
    '1. 按所选日期和本站范围统计。',
    '2. 配送费计入收入。',
    '3. 水票收入按实收计算。',
    '4. 合作款独立列示。',
    '5. 押金不计作销售收入。',
    '6. 出库时记录实际成本。',
    '7. 未填成本时利润不完整。'
  ].join('\n'),
  profitBasisNote: '收入统计范围由服务端给出。',
  costBasisNote: '使用出库时记录的成本。',
  cooperation: { scopeNote: '合作款按服务端规则列示。', rows: [
    { key: 'pendingPayAmount', label: '合作款待付', amount: 25 },
    { key: 'pendingReceiveAmount', label: '合作款待收', amount: 5 },
    { key: 'recordedCashNetAmount', label: '登记净现金（不等于利润）', amount: 10 }
  ] }
}

async function verifyWorkbenchArrival() {
  const wx = createWx(), app = createApp({ globalData: { isLogin: true, userInfo: { stationId: 3 } } })
  const stack = [], nativePushes = [], feedback = []
  let timeout, failReport, reportReads = 0
  wx.navigateTo = options => nativePushes.push(options)
  const navigator = createNavigator({
    wx, pages: () => stack, feedback: failure => feedback.push(failure),
    setTimer(fn) { timeout = fn; return 1 },
    clearTimer() { timeout = undefined }
  })
  const reportUrl = '/api/manager/gross-profit'
  const load = (route, get) => Object.assign(loadPage('miniapp-delivery/' + route + '.js', {
    wx, app, stubs: { 'utils/navigation': navigator, 'utils/request': { get } }
  }), { route, options: {} })
  const workbench = load('pages/station-mgmt/index', async () => ({ data: { items: [] } }))
  const profit = load('pages/station-mgmt/gross-profit/index', url => {
    assert.equal(url, reportUrl)
    if (++reportReads === 1) return new Promise((_, reject) => { failReport = reject })
    return Promise.resolve({ code: 0, data: report })
  })
  global.getCurrentPages = () => stack
  stack.push(workbench)
  workbench.onShow()
  workbench.onNavigate({ currentTarget: { dataset: { url: '/' + profit.route } } })
  assert.equal(nativePushes.length, 1)
  assert.equal(nativePushes[0].url, '/' + profit.route)
  workbench.onHide()
  stack.push(profit)
  profit.onLoad(profit.options)
  profit.onShow()
  assert.equal(reportReads, 1)
  assert.equal(profit.data.loading, true)

  // 模拟页面已展示，但微信没有回调原导航。报表仍在加载时不能弹出「页面未打开」。
  timeout()
  assert.equal(feedback.length, 0, 'visible profit page must acknowledge navigation while its request is pending')
  failReport(new Error('合成读取超时'))
  await new Promise(setImmediate)
  assert.equal(profit.data.reportError, '本期报表加载失败，请重试')
  await profit.onRetryReport()
  assert.equal(profit.data.profitView.netProfitText, '¥17')
  assert.equal(nativePushes.length, 1, 'report retry stays on the current page')
}

async function verifyTicketSeparation() {
  let response
  const page = loadPage('miniapp-delivery/pages/station-mgmt/gross-profit/index.js', {
    stubs: { 'utils/request': { get: async () => ({ code: 0, data: response }) } }
  })
  const render = () => renderElements(tree, page.data, { includeText: true })
  const texts = (nodes, name) => nodes.filter(node => node.className?.split(/\s+/).includes(name)).map(node => node.text)
  for (const [ticketRevenue, orderIncome, totalCost] of [[36, 0, 0], [0, 0, 24], [36, 7, 24]]) {
    response = { ...report, ticketRevenue, orderIncome, totalCost, totalIncome: 999,
      profitComparable: false, profitUnavailableReason: '购票与兑水成本未对应，利润暂不显示。',
      totalProfit: 999, netProfit: 123,
      items: [{ productId: 1, productName: '合成商品', soldQty: 2, revenue: orderIncome,
        ticketRevenue, revenueTotal: 999, costAmount: totalCost, profitText: '旧利润', profitRateText: '100%' }] }
    await page.load()
    assert.equal(page.data.costComplete, true, 'ticket mismatch must not mean missing cost')
    assert.equal(page.data.profitChart.length, 0)
    let nodes = render()
    assert.deepEqual(texts(nodes, 'hero-label'), ['订单水费及服务费', '购票实收', '出库成本', '计件工钱'])
    assert.deepEqual(texts(nodes, 'hero-num'), ['¥' + orderIncome, '¥' + ticketRevenue, '¥' + totalCost, '¥6'])
    assert.deepEqual(texts(nodes, 'profit-unavailable-note'), [response.profitUnavailableReason])
    assert(!nodes.some(node => node.text.includes('¥999')))
    if (!page.data.incomeDetailsExpanded) page.onToggleIncomeDetails()
    nodes = render()
    assert(!texts(nodes, 'ledger-label').includes('销售净利'))
    assert(!texts(nodes, 'ledger-label').includes('销售收入'))
    assert(!nodes.some(node => node.text.includes('100%') || node.text.includes('旧利润')))
    assert(texts(nodes, 'ledger-label').includes('出库成本'))
    assert(texts(nodes, 'ledger-label').includes('计件工钱'))
  }
  response = { ...report, profitComparable: false, costComplete: false, totalCost: null, netProfit: null,
    profitUnavailableReason: '购票与兑水成本未对应，利润暂不显示。' }
  await page.load()
  assert.equal(page.data.costComplete, false)
  assert.equal(page.data.profitView.totalCostText, '待核实')
  assert.equal(page.data.profitView.wageText, '¥6')
  response = { ...report, profitComparable: undefined, profitUnavailableReason: undefined }
  await page.load()
  assert.equal(page.data.costComplete, true)
  assert.equal(page.data.showProfit, false, 'older response cannot authorize comparable profit')
  assert.equal(page.data.profitChart.length, 0)
  response = { ...report, wage: undefined }
  await page.load()
  assert.equal(page.data.costComplete, true)
  assert.equal(page.data.showProfit, false, 'missing wage cannot authorize a complete profit display')
  assert.equal(page.data.profitView.totalCostText, '¥24')
}

async function main() {
  const requests = []
  const page = loadPage('miniapp-delivery/pages/station-mgmt/gross-profit/index.js', {
    stubs: {
      'config/api': { API: { MANAGER_GROSS_PROFIT: '/api/manager/gross-profit' } },
      'utils/request': {
        get: async url => { requests.push(url); return url.endsWith('/missing-cost') ? { code: 0, data: [] } : { code: 0, data: report } },
        put: async () => ({ code: 0 })
      }
    }
  })
  await page.load()

  const groups = page.data.profitHelpSections
  assert.deepEqual(groups.map(group => group.key), ['scope', 'fees', 'costs'])
  assert(groups.every(group => group.expanded === false), 'explanation groups start collapsed')
  assert.deepEqual(groups[0].notes, [report.incomeBasisNote, report.periodBasisNote, '按服务端口径查看本期经营数据。', '按所选日期和本站范围统计。'])
  assert.deepEqual(groups[1].notes, ['配送费计入收入。', '水票收入按实收计算。', '合作款独立列示。', '押金不计作销售收入。', '合作款按服务端规则列示。'])
  assert.deepEqual(groups[2].notes, ['出库时记录实际成本。', '未填成本时利润不完整。'])
  assert.deepEqual(page.data.cooperationView.summary.map(row => row.key), ['pendingPayAmount', 'pendingReceiveAmount'])
  assert(page.data.cooperationView.sections.some(group => group.key === 'customerCash' && group.rows.some(row => row.key === 'recordedCashNetAmount')))

  page.onToggleHelpGroup({ currentTarget: { dataset: { group: 'costs' } } })
  assert.deepEqual(page.data.profitHelpSections.map(group => group.expanded), [false, false, true])
  page.onShowHelp()
  assert(page.data.profitHelpSections.every(group => group.expanded))
  page.onShowHelp()
  assert(page.data.profitHelpSections.every(group => !group.expanded))

  const nodes = renderElements(tree, { ...page.data, report: { ...report, missingCostHint: '' } }, { includeText: true })
  const textsOf = (elements, className) => elements.filter(node => node.className?.split(/\s+/).includes(className)).map(node => node.text)
  assert.deepEqual(textsOf(nodes, 'hero-label'), ['销售净利', '销售收入', '出库成本', '计件工钱'])
  assert.deepEqual(textsOf(nodes, 'hero-num'), ['¥17', '¥47', '¥24', '¥6'])
  assert(nodes.some(node => node.className?.includes('hero-period') && node.text.includes('2026-11-01 至 2026-11-09')), 'headline shows the server-confirmed period')
  assert.deepEqual(textsOf(nodes, 'report-period-note'), [], 'date details remain in the expandable explanation')
  assert.equal(page.data.periodExpanded, false, 'detailed date controls start collapsed')
  assert.equal(nodes.filter(node => node.tag === 'picker').length, 0)
  assert(!nodes.some(node => node.className?.includes('period-current')), 'confirmed period is not repeated below the headline')
  assert(nodes.some(node => node.className?.includes('period-action') && node.text === '调整'))
  page.onTogglePeriod()
  assert.equal(page.data.periodExpanded, true)
  let periodNodes = renderElements(tree, page.data, { includeText: true })
  assert.equal(periodNodes.filter(node => node.tag === 'picker').length, 2)
  assert.equal(periodNodes.filter(node => node.className?.split(/\s+/).includes('af-choice')).length, 4)
  assert.equal(nodes.filter(node => node.className?.includes('ledger-row')).length, 2, 'income details start collapsed while the two cooperation actions remain visible')
  page.onToggleAmountComparison()
  page.onToggleIncomeDetails()
  const expanded = renderElements(tree, page.data, { includeText: true })
  assert.deepEqual(textsOf(expanded, 'profit-chart-label'), ['销售收入', '出库成本', '计件工钱', '销售净利'])
  assert.deepEqual(textsOf(expanded, 'profit-chart-value'), ['¥47', '¥24', '¥6', '¥17'])
  assert.deepEqual(textsOf(expanded, 'ledger-label'), [
    '单数', '订单收入（水费）', '水票收入（买票实收）', '配送费', '楼层费',
    '销售收入', '− 出库成本', '− 计件工钱', '销售净利', '合作款待付', '合作款待收'
  ])
  assert.deepEqual(textsOf(expanded, 'ledger-value'), [
    '1 单', '¥40', '¥0', '¥5', '¥2', '¥47', '¥24', '¥6', '¥17', '¥25', '¥5'
  ])
  page.setData({ quickRange: 0, from: '2026-10-01', to: '2026-10-31' })
  await page.load()
  assert.equal(requests.at(-1), '/api/manager/gross-profit', 'month shortcut must request current month from server on reload')
  page.onQuickRange({ currentTarget: { dataset: { days: '7' } } })
  assert.equal(page.data.quickRange, 7, 'selected shortcut is shown')
  assert.equal(page.data.periodExpanded, true, 'shortcut selection keeps the controls open')
  page.onDate({ currentTarget: { dataset: { field: 'from' } }, detail: { value: '2026-10-01' } })
  assert.equal(page.data.quickRange, null, 'manual dates clear the shortcut selection')
  assert.equal(page.data.periodExpanded, true, 'editing the first date keeps the second picker available')
  assert(requests.at(-1).includes('from=2026-10-01'))
  await page.onRetryReport()
  assert.equal(page.data.periodExpanded, true, 'retry does not close the period controls')
  page.onTogglePeriod()
  periodNodes = renderElements(tree, page.data, { includeText: true })
  assert.equal(periodNodes.filter(node => node.tag === 'picker').length, 0)
  assert(styles.includes('@media screen and (max-width: 359px)'))
  assert(/\.chips\s*\{\s*flex-wrap:wrap/.test(styles), 'narrow screens wrap shortcut choices into rows')
  assert(/\.chips \.af-choice__face\s*\{[^}]*white-space:nowrap/.test(styles), 'near-30-day text stays together')
  assert(!styles.includes('.hero-item:last-child'), 'the old full-row third metric rule must not override the 2-column layout')
  assert(!nodes.some(node => node.text?.includes(report.incomeBasisNote)), 'income explanation is not a permanent caution beside the headline')
  page.onToggleHelpVisible()
  page.onToggleHelpGroup({ currentTarget: { dataset: { group: 'scope' } } })
  const helpNodes = renderElements(tree, page.data, { includeText: true })
  assert(helpNodes.some(node => node.text?.includes(report.incomeBasisNote)), 'the original income explanation remains available on demand')
  await verifyWorkbenchArrival()
  await verifyTicketSeparation()
  console.log('AQUAFLOW_SUITE_OK 8')
}

main().catch(error => { console.error(error); process.exitCode = 1 })
