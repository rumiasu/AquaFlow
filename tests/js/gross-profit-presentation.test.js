'use strict'
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { loadPage, ROOT } = require('./harness')
const { parseWxml, renderElements } = require('./wxml-tree')

const relWxml = 'miniapp-delivery/pages/station-mgmt/gross-profit/index.wxml'
const relWxss = 'miniapp-delivery/pages/station-mgmt/gross-profit/index.wxss'
const wxml = fs.readFileSync(path.join(ROOT, relWxml), 'utf8')
const styles = fs.readFileSync(path.join(ROOT, relWxss), 'utf8')
const tree = parseWxml(wxml)
const report = {
  from: '2026-11-01', to: '2026-11-09',
  items: [], costComplete: true, totalIncome: 120, netProfit: 75,
  totalRevenue: 120,
  totalCost: 45,
  totalProfit: 75,
  incomeBasisNote: '含尚未收款的订单水费、服务费与已收票款，不等于到账现金。',
  helpNote: [
    '按服务端口径查看本期经营数据。',
    '1. 按所选日期和本站范围统计。',
    '2. 配送费计入收入。',
    '3. 水票收入按实收计算。',
    '4. 合作款独立列示。',
    '5. 押金不计作销售收入。',
    '6. 当前成本用于本期计算。',
    '7. 未填成本时利润不完整。'
  ].join('\n'),
  profitBasisNote: '收入统计范围由服务端给出。',
  costBasisNote: '成本按当前进货价计算。',
  cooperation: { scopeNote: '合作款按服务端规则列示。', rows: [
    { key: 'pendingPayAmount', label: '合作款待付', amount: 25 },
    { key: 'pendingReceiveAmount', label: '合作款待收', amount: 5 },
    { key: 'recordedCashNetAmount', label: '登记净现金（不等于利润）', amount: 10 }
  ] }
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
  assert.deepEqual(groups[0].notes, ['按服务端口径查看本期经营数据。', '按所选日期和本站范围统计。'])
  assert.deepEqual(groups[1].notes, ['配送费计入收入。', '水票收入按实收计算。', '合作款独立列示。', '押金不计作销售收入。', '合作款按服务端规则列示。'])
  assert.deepEqual(groups[2].notes, ['当前成本用于本期计算。', '未填成本时利润不完整。'])
  assert.equal(page.data.profitView.totalRevenueText, '¥120')
  assert.equal(page.data.profitView.totalCostText, '¥45')
  assert.equal(page.data.profitView.totalProfitText, '¥75')
  assert.deepEqual(page.data.cooperationView.summary.map(row => row.key), ['pendingPayAmount', 'pendingReceiveAmount'])
  assert(page.data.cooperationView.sections.some(group => group.key === 'customerCash' && group.rows.some(row => row.key === 'recordedCashNetAmount')))

  page.onToggleHelpGroup({ currentTarget: { dataset: { group: 'costs' } } })
  assert.deepEqual(page.data.profitHelpSections.map(group => group.expanded), [false, false, true])
  page.onShowHelp()
  assert(page.data.profitHelpSections.every(group => group.expanded))
  page.onShowHelp()
  assert(page.data.profitHelpSections.every(group => !group.expanded))

  const nodes = renderElements(tree, { ...page.data, report: { ...report, missingCostHint: '' } }, { includeText: true })
  const hero = nodes.filter(node => node.className?.split(/\s+/).includes('hero-item'))
  assert.equal(hero.length, 3)
  const heroValues = nodes.filter(node => node.className?.includes('hero-num')).map(node => node.text)
  assert.deepEqual(heroValues, ['¥120', '¥45', '¥75'])
  assert(nodes.some(node => node.className?.includes('hero-period') && node.text.includes('2026-11-01 至 2026-11-09')), 'headline shows the server-confirmed period')
  assert.equal(nodes.filter(node => node.className?.includes('ledger-row')).length, 2, 'income details start collapsed while the two cooperation actions remain visible')
  page.onToggleIncomeDetails()
  const expanded = renderElements(tree, { ...page.data, report: { ...report, missingCostHint: '' } }, { includeText: true })
  assert(expanded.filter(node => node.className?.includes('ledger-row')).length > 2, 'income details remain available when expanded')
  page.setData({ quickRange: 0, from: '2026-10-01', to: '2026-10-31' })
  await page.load()
  assert.equal(requests.at(-1), '/api/manager/gross-profit', 'month shortcut must request current month from server on reload')
  page.onQuickRange({ currentTarget: { dataset: { days: '7' } } })
  assert.equal(page.data.quickRange, 7, 'selected shortcut is shown')
  page.onDate({ currentTarget: { dataset: { field: 'from' } }, detail: { value: '2026-10-01' } })
  assert.equal(page.data.quickRange, null, 'manual dates clear the shortcut selection')
  assert(styles.includes('@media screen and (max-width: 359px)'))
  assert(!styles.includes('.hero-item:last-child'), 'the old full-row third metric rule must not override the 2-column layout')
  assert(wxml.indexOf('<text class="hero-label">销售收入</text>') < wxml.indexOf('{{profitView.totalIncomeText}}'))
  assert(nodes.some(node => node.className?.includes('hero-income-basis') && node.text.includes('不等于到账现金')), 'sales income caveat comes from the server beside the headline')
  console.log('AQUAFLOW_SUITE_OK 1')
}

main().catch(error => { console.error(error); process.exitCode = 1 })
