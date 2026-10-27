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
  items: [],
  totalRevenue: 120,
  totalCost: 45,
  totalProfit: 75,
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
  cooperation: { scopeNote: '合作款按服务端规则列示。', rows: [] }
}

async function main() {
  const page = loadPage('miniapp-delivery/pages/station-mgmt/gross-profit/index.js', {
    stubs: {
      'config/api': { API: { MANAGER_GROSS_PROFIT: '/api/manager/gross-profit' } },
      'utils/request': {
        get: async url => url.endsWith('/missing-cost') ? { code: 0, data: [] } : { code: 0, data: report },
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

  page.onToggleHelpGroup({ currentTarget: { dataset: { group: 'costs' } } })
  assert.deepEqual(page.data.profitHelpSections.map(group => group.expanded), [false, false, true])
  page.onShowHelp()
  assert(page.data.profitHelpSections.every(group => group.expanded))
  page.onShowHelp()
  assert(page.data.profitHelpSections.every(group => !group.expanded))

  const nodes = renderElements(tree, { ...page.data, report: { ...report, missingCostHint: '' } }, { includeText: true })
  const hero = nodes.filter(node => node.attrs?.class === 'hero-item')
  assert.equal(hero.length, 3)
  const heroValues = nodes.filter(node => node.className?.includes('hero-num')).map(node => node.text)
  assert.deepEqual(heroValues, ['¥120', '¥45', '¥75'])
  assert(styles.includes('@media screen and (max-width: 359px)'))
  assert(!styles.includes('.hero-item:last-child'), 'the old full-row third metric rule must not override the 2-column layout')
  assert(wxml.indexOf('<text class="hero-label">收入合计</text>') < wxml.indexOf('{{profitView.totalRevenueText}}'))
  console.log('AQUAFLOW_SUITE_OK 1')
}

main().catch(error => { console.error(error); process.exitCode = 1 })
