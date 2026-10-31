'use strict'
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { loadPage, ROOT } = require('./harness')
const { parseWxml, renderElements } = require('./wxml-tree')
const reportPath = 'miniapp-delivery/pages/station-mgmt/gross-profit/index'
const homePath = 'miniapp-delivery/pages/home/index'
const reportWxml = fs.readFileSync(path.join(ROOT, reportPath + '.wxml'), 'utf8')
const reportCss = fs.readFileSync(path.join(ROOT, reportPath + '.wxss'), 'utf8')
const homeWxml = fs.readFileSync(path.join(ROOT, homePath + '.wxml'), 'utf8')
const homeCss = fs.readFileSync(path.join(ROOT, homePath + '.wxss'), 'utf8')
const tree = parseWxml(reportWxml)
const rows = [
  ['agreedPayAmount', '原接受应付报酬', 11], ['pendingPayAmount', '合作款待付', 12],
  ['pendingReceiveAmount', '合作款待收', 13], ['settledPayAmount', '已登记合作交款', 14],
  ['recoveryPendingReceiveAmount', '退款后返还待收', 15], ['recordedCashNetAmount', '登记净现金（不等于利润）', -16],
  ['futureField', '未来字段', 17]
].map(([key, label, amount]) => ({ key, label, amount }))
let report = { costComplete: true, totalIncome: 100, netProfit: -50, items: [], totalRevenue: 100, totalCost: 150, totalProfit: -50, helpNote: '1. 服务端范围\n6. 服务端成本', cooperation: { rows, scopeNote: '合作款说明' } }
let failReport = false, failMissing = false, missing = []
const page = loadPage(reportPath + '.js', { stubs: {
  'config/api': { API: { MANAGER_GROSS_PROFIT: '/gp' } },
  'utils/request': { get: async url => {
    if (url.endsWith('/missing-cost')) { if (failMissing) throw Error('missing'); return { data: missing } }
    if (failReport) throw Error('report')
    return { data: report }
  }, put: async () => ({}) }
} })
function visible() { return renderElements(tree, page.data, { includeText: true }) }
function textContains(nodes, text) { return nodes.some(node => String(node.text || '').includes(text)) }
async function main() {
  await page.load()
  assert.deepEqual(page.data.cooperationView.summary.map(row => row.key), ['pendingPayAmount', 'pendingReceiveAmount', 'recordedCashNetAmount'])
  assert.deepEqual(page.data.cooperationView.sections.map(group => group.key), ['agreement', 'settlement', 'recovery', 'other'])
  assert.equal(page.data.cooperationView.sections.flatMap(group => group.rows).length + page.data.cooperationView.summary.length, rows.length)
  assert.deepEqual(page.data.profitChart.map(row => row.width), [100 / 150 * 100, 100, 50 / 150 * 100])
  assert.equal(page.data.profitChart[2].negative, true)
  assert.equal(page.data.profitChart[2].text, '¥-50')
  assert.equal(page.data.profitHelpVisible, false)
  let nodes = visible()
  assert(textContains(nodes, '合作款待付'))
  assert(!textContains(nodes, '已登记合作交款'))
  assert(!textContains(nodes, '服务端范围'))
  page.onToggleCooperationGroup({ currentTarget: { dataset: { group: 'settlement' } } })
  nodes = visible()
  assert(textContains(nodes, '已登记合作交款'))
  page.onToggleCooperationGroup({ currentTarget: { dataset: { group: 'settlement' } } })
  assert(!textContains(visible(), '已登记合作交款'))
  page.onToggleHelpVisible()
  page.onToggleHelpGroup({ currentTarget: { dataset: { group: 'scope' } } })
  assert(textContains(visible(), '服务端范围'))
  page.onToggleHelpVisible()
  assert(!textContains(visible(), '服务端范围'))
  report = { ...report, totalRevenue: 0, totalCost: 0, totalProfit: 0 }
  await page.load()
  assert(page.data.profitChart.every(row => row.width === 0 && !row.negative))
  report = { ...report, totalProfit: null, missingCostHint: '缺少成本' }
  missing = [{ productId: 1, productName: '水' }]
  await page.load()
  assert.equal(page.data.profitChart.length, 0)
  assert.equal(page.data.profitView.totalProfitText, '未填成本')
  nodes = visible()
  assert(textContains(nodes, '本期成本未完整记录'))
  assert(!textContains(nodes, '本期金额对比'))
  report = { ...report, totalProfit: undefined, missingCostHint: '' }
  missing = []
  await page.load()
  assert.equal(page.data.profitView.totalProfitText, '待核实')
  assert.equal(page.data.profitChart.length, 0)
  report = { ...report, totalProfit: 10, costComplete: false }
  failMissing = true
  await page.load()
  nodes = visible()
  assert(!textContains(nodes, '未填成本清单尚未读取'))
  assert(!textContains(nodes, '本期金额对比'))
  failReport = true
  await page.load()
  assert.equal(page.data.report, null)
  assert(textContains(visible(), '当前期间报表尚未读取'))
  const homeTree = parseWxml(homeWxml)
  const homeNodes = renderElements(homeTree, { activeTab: 'completed', completedOrders: [
    { id: 1, status: 4, isUnpaid: false }, { id: 2, status: 3, isUnpaid: true },
    { id: 3, status: 4, isUnpaid: true }, { id: 4, isUnpaid: false }
  ], deliveredUnpaidOrders: [], readReady: {}, collectionRecoveryItems: [] }, { includeText: true })
  const cards = homeNodes.filter(node => node.attrs && node.attrs.bindtap === 'onOrderTap')
  assert.equal(cards.length, 4)
  assert(cards[0].className.includes('completed-order-card'))
  assert(cards.slice(1).every(card => !card.className.includes('completed-order-card')))
  assert(homeCss.includes('.completed-order-card .address-text { font-size: 32rpx'))
  assert(homeCss.includes('min-height: 48px;'))
  assert(/overflow-wrap:\s*anywhere/.test(reportCss))
  assert(/\.profit-help-entry,\.profit-help-toggle\s*\{[^}]*min-height:\s*52px/.test(reportCss), 'disclosure rows retain at least the previous 48px target')
  console.log('AQUAFLOW_SUITE_OK 1')
}
main().catch(error => { console.error(error); process.exitCode = 1 })
