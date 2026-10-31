'use strict'
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const { loadPage, createWx, createApp, armWatchdog } = require('./harness')
const { parseWxml, renderElements } = require('./wxml-tree')

const repo = path.resolve(__dirname, '../..')
const setupProgressSource = fs.readFileSync(path.join(repo, 'miniapp-delivery/utils/setup-progress.js'), 'utf8')
const overviewSource = fs.readFileSync(path.join(repo, 'miniapp-delivery/templates/station-navbar.wxml'), 'utf8')
const detailSource = fs.readFileSync(path.join(repo, 'miniapp-delivery/templates/setup-module-tasks.wxml'), 'utf8')
const tests = []
const test = (name, run) => tests.push({ name, run })
const deliveryRoute = '/pages/station-mgmt/delivery-config/index'
const productRoute = '/pages/station-mgmt/products/index'
const ticketRoute = '/pages/station-mgmt/ticket-packages/index'
const items = [
  { key: 'stationPhone', label: '水站联系电话', where: '水站资料', route: '/pages/station-mgmt/station-info/index', level: 'P0', levelText: '必填', done: true },
  { key: 'stationPosition', label: '水站位置', where: '水站资料', route: '/pages/station-mgmt/station-info/index', level: 'P0', levelText: '必填', done: false },
  { key: 'deliveryFee', label: '配送计费', where: '配送计费', route: deliveryRoute, level: 'P0', levelText: '必填', done: false },
  { key: 'optional', label: '员工薪酬', where: '配送员薪酬', route: '/pages/station-mgmt/payroll/index', level: 'P1', levelText: '建议', done: false },
  { key: 'catalogOnShelf', label: '上架商品', where: '商品与库存', route: productRoute, level: 'P0', levelText: '必填', done: false },
  { key: 'ticketPackage', label: '水票档位', where: '商品与库存', route: ticketRoute, level: 'P1', levelText: '建议', done: false },
  { key: 'stationNotice', label: '营业公告', where: '营业状态与公告', route: '/pages/station-mgmt/station-status/index', level: 'P2', levelText: '选填', done: true }
]
function helpers(fetch = async () => null) {
  const app = { _loginGeneration: 1, globalData: { isLogin: true, userInfo: { staffId: 1, stationId: 11 } }, canAccessStationBusiness: () => true }
  const module = { exports: {} }
  vm.runInNewContext(setupProgressSource, { module, require: () => ({ fetchSetupGuide: fetch }), getApp: () => app })
  return { api: module.exports, app }
}
function home() {
  const wx = createWx()
  const app = createApp({ globalData: { isLogin: true, accessToken: 'synthetic', userInfo: { staffId: 1, stationId: 11, role: 'STATION_MANAGER' } } })
  const state = { guide: { pendingCount: 5, doneCount: 2, totalCount: 7, p0PendingCount: 3, all: items, pending: items.filter(item => !item.done), summaryText: '还需完成 3 项必填', onlineHint: '合成下一步' } }
  const routes = []
  delete require.cache[require.resolve(path.join(repo, 'miniapp-delivery/behaviors/stationNavbar'))]
  const page = loadPage('miniapp-delivery/pages/coordination/index.js', { wx, app, stubs: {
    'api/delivery': {},
    'utils/request': { get: async () => ({ code: 0, data: { statusText: '待上线', operatingStatus: 3 } }) },
    'utils/setup-reminder': { checkSetupReminder: async () => state.guide },
    'utils/navigation': { open: route => routes.push(route) }
  } })
  page.setData({ isManager: true, stationOperatingStatus: 3, stationStatusText: '待上线' })
  return { page, state, routes }
}
function homeCue(page, relative = 'miniapp-delivery/pages/coordination/index.wxml') {
  const tree = parseWxml(fs.readFileSync(path.join(repo, relative), 'utf8'))
  const found = []
  function walk(node) {
    if (node.attrs && ['setup-launch-entry', 'setup-launch-quiet'].includes(node.attrs.class)) found.push(node)
    for (const child of node.children || []) walk(child)
  }
  walk(tree)
  return renderElements({ children: found }, page.data, { includeText: true })
}

test('top setup row appears only for pending-launch status while status management remains available', async () => {
  const t = home()
  await t.page.loadStationPending()
  assert.equal(t.page.data.setupDoneCount, 1)
  assert.equal(t.page.data.setupTotalCount, 4)
  assert(homeCue(t.page).some(node => (node.text || '').includes('还需完成 3 项必填')))
  for (const relative of ['miniapp-delivery/pages/home/index.wxml', 'miniapp-delivery/pages/coordination/index.wxml']) {
    for (const status of [3, 1, 2, 4, null]) {
      t.page.setData({ stationOperatingStatus: status, setupRequiredPending: 0 })
      const hasTopRow = homeCue(t.page, relative).some(node => node.attrs.class === 'setup-launch-entry')
      assert.equal(hasTopRow, status === 3, relative + ' status ' + status)
    }
  }
  assert(overviewSource.includes('bindtap="goStationStatus"'), 'status-page entry must remain available from the panel')
})
test('failed or incomplete progress never becomes a zero-item completion', async () => {
  const t = home()
  await t.page.loadStationPending()
  t.state.guide = null
  await t.page.loadStationPending()
  assert.equal(t.page.data.setupProgressKnown, false)
  assert(homeCue(t.page).some(node => (node.text || '').includes('进度待核实')))
  assert(!homeCue(t.page).some(node => (node.text || '').includes('0/0')))
  t.state.guide = { pending: [], all: [], pendingCount: 0 }
  await t.page.loadStationPending()
  assert.equal(t.page.data.setupProgressKnown, false)
})
test('overview groups product and optional ticket tasks into one inventory card and routes it to products', () => {
  const h = helpers()
  const grouped = h.api.groupSetupItems(items)
  const cards = grouped.filter(item => item.moduleStart)
  assert.equal(cards.length, 5)
  const inventory = cards.find(item => item.moduleTitle === '商品库存')
  assert(inventory)
  assert.equal(inventory.route, productRoute)
  assert.equal(grouped.filter(item => item.moduleTitle === '商品库存').length, 2)
  const t = home()
  t.page.onPanelGoFill({ currentTarget: { dataset: { route: inventory.route } } })
  assert.deepEqual(t.routes, [productRoute])
  assert.equal(t.page.data.stationPanelShow, false)
})
test('detail page receives complete and optional ticket facts through a frontend route alias', () => {
  const h = helpers()
  const completedCatalog = { ...items[4], done: true }
  const patch = h.api.moduleTaskPatch({ items: [items[4], items[5], completedCatalog] }, productRoute)
  assert.deepEqual(Array.from(patch.moduleSetupTasks, item => item.key), ['catalogOnShelf', 'ticketPackage', 'catalogOnShelf'])
  const ticket = patch.moduleSetupTasks[1]
  assert.equal(ticket.route, productRoute)
  assert.equal(ticket.levelText, '选填')
  assert(ticket.completionEvidence.includes('水票是选填'))
  assert(ticket.suggestion.includes('已开水票'))
  assert.equal(patch.moduleSetupReady, true)
  assert.equal(h.api.moduleTaskPatch(null, productRoute).moduleSetupReady, false)
})
test('optional ticket never marks the inventory module as missing a required setup item', () => {
  const h = helpers()
  const ticketOnly = h.api.groupSetupItems([items[5]]).find(item => item.moduleStart)
  assert.equal(ticketOnly.moduleTitle, '商品库存')
  assert.equal(ticketOnly.route, productRoute)
  assert.equal(ticketOnly.moduleDone, true)
  assert(ticketOnly.moduleSummary.includes('水票设置 · 选填'))
  assert(!ticketOnly.moduleSummary.includes('必填待完善'))
})
test('overview renders module status only and contains no how-to field', () => {
  const h = helpers()
  const grouped = h.api.groupSetupItems(items)
  const tree = parseWxml(overviewSource)
  const data = { stationPanelShow: true, stationPanelItems: grouped, stationPanelDone: [], stationPanelPending: items, stationPanelLoading: false }
  const nodes = renderElements(tree, data, { includeText: true })
  assert.equal(nodes.filter(node => node.attrs.class === 'st-item st-module-card').length, 5)
  assert.equal(nodes.filter(node => node.attrs.class === 'st-item-go').length, 5)
  assert.equal(nodes.filter(node => node.attrs.class === 'st-module-how').length, 0)
  assert.equal(nodes.filter(node => node.attrs.class === 'st-module-howto').length, 0)
  assert(!overviewSource.includes('怎么做'))
  assert(!setupProgressSource.includes('moduleHowTo'))
  const inventory = nodes.find(node => node.attrs.class === 'st-module-title' && node.text === '商品库存')
  assert(inventory)
  assert(nodes.some(node => (node.text || '').includes('水票设置 · 选填')))
})
test('detail task rows show status and direct actions without completion claims for pending items', () => {
  const h = helpers()
  const tasks = h.api.moduleTaskPatch({ items: [items[4], items[5]] }, productRoute).moduleSetupTasks
  const tree = parseWxml(detailSource)
  const nodes = renderElements(tree, { loading: false, ready: true, error: '', tasks }, { includeText: true })
  assert(nodes.some(node => node.text === '待填写'))
  assert(nodes.some(node => node.text === '选填'))
  assert(!nodes.some(node => node.text === '至少一款商品已启用并上架。'))
  assert(!nodes.some(node => node.text.includes('水票是选填')))
  assert(!nodes.some(node => node.text === '重新核对完成状态'))
  assert(nodes.some(node => node.text === '去上架商品'))
  assert(nodes.some(node => node.text === '查看水票商品'))
})
test('station pending rows omit generic backend explanations and retain action and retry', () => {
  const tree = parseWxml(detailSource)
  const tasks = [
    { key: 'stationPhone', label: '水站联系电话', done: false, levelText: '必填', why: '电话用途说明', suggestion: '重复电话说明', completionEvidence: '联系电话已保存' },
    { key: 'stationPosition', label: '水站位置', done: false, levelText: '必填', why: '冗长位置说明', suggestion: '重复位置说明', completionEvidence: '地址和坐标均已保存' }
  ]
  const nodes = renderElements(tree, { loading: false, ready: true, error: '', tasks }, { includeText: true })
  assert.equal(nodes.filter(node => node.text === '待填写').length, 2)
  assert.equal(nodes.filter(node => node.text === '去填写').length, 2)
  for (const phrase of ['电话用途说明', '重复电话说明', '联系电话已保存', '冗长位置说明', '重复位置说明', '地址和坐标均已保存']) assert(!nodes.some(node => node.text === phrase))
  const errorNodes = renderElements(tree, { loading: false, ready: false, error: '读取失败', tasks: [] }, { includeText: true })
  assert(errorNodes.some(node => node.text === '读取失败'))
  assert(errorNodes.some(node => node.attrs.bindtap === 'refreshModuleSetup'))
})
test('station checklist actions lead to the missing field', () => {
  const wx = createWx(), targets = []
  wx.pageScrollTo = options => targets.push(options.selector)
  const page = loadPage('miniapp-delivery/pages/station-mgmt/station-info/index.js', { wx, app: createApp(), stubs: { 'api/station-mgmt': {} } })
  page.setData({ moduleSetupTasks: [{key: 'stationPhone'}, {key: 'stationPosition'}], origin: {address: ''}, coord: {lat: 1, lng: 2} })
  const tap = key => page.onModuleSetupTask({currentTarget: {dataset: {key}}})
  tap('stationPhone'); tap('stationPosition')
  page.setData({coord: {lat: null, lng: null}}); tap('stationPosition')
  tap('unrelated')
  assert.deepEqual(targets, ['#setup-station-phone', '#info-basics', '#setup-station-position'])
})
test('coordinate warning is limited to missing coordinates', () => {
  const source = fs.readFileSync(path.join(repo, 'miniapp-delivery/pages/station-mgmt/station-info/index.wxml'), 'utf8')
  const tree = parseWxml(source)
  const view = coord => renderElements(tree, {loading: false, loadError: '', origin: {id: 1}, coord, settingsSections: [], form: {}}, {includeText: true})
  const warning = '未设置坐标，无法计算配送距离。'
  assert(view({lat: null, lng: null}).some(node => node.text === warning))
  assert(view({lat: 1, lng: null}).some(node => node.text === warning))
  assert(!view({lat: 1, lng: 2}).some(node => node.text === warning))
})
test('module read failure has an explicit retry state and identity races cannot repaint', async () => {
  let resolve
  const h = helpers(() => new Promise(done => { resolve = done }))
  const data = h.api.initialModuleData()
  const page = { data, setData(patch) { Object.assign(data, patch) } }
  const pending = h.api.refreshModuleTasks(page, productRoute)
  h.app.globalData.userInfo.stationId = 12
  resolve({ items })
  await pending
  assert.equal(data.moduleSetupReady, false)
  const f = helpers(async () => null)
  const other = { data: {}, setData(patch) { Object.assign(this.data, patch) } }
  await f.api.refreshModuleTasks(other, productRoute)
  assert(other.data.moduleSetupError)
  assert.equal(other.data.moduleSetupReady, false)
})
test('required progress excludes optional tasks and supports raw and normalized snapshots', () => {
  const { api } = helpers()
  const raw = api.progressPatch({ items, p0PendingCount: 3, doneCount: 2, totalCount: 7 })
  const normalized = api.progressPatch({ all: items, p0PendingCount: 3, doneCount: 2, totalCount: 7 })
  for (const result of [raw, normalized]) {
    assert.equal(result.setupDoneCount, 1)
    assert.equal(result.setupTotalCount, 4)
    assert.equal(result.setupRequiredPending, 3)
    assert.equal(result.setupProgressKnown, true)
  }
  const optional = api.progressPatch({ items: [items[5]], p0PendingCount: 0 })
  assert.equal(optional.setupTotalCount, 0)
  assert.equal(optional.setupDoneCount, 0)
  for (const guide of [null, { p0PendingCount: 0 }, { items: [], p0PendingCount: null }]) {
    const result = api.progressPatch(guide)
    assert.equal(result.setupProgressKnown, false)
    assert.equal(result.setupDoneCount, null)
    assert.equal(result.setupTotalCount, null)
  }
})

test('completed overview retains accessible check and route while removing repeated completion copy', () => {
  const { api } = helpers()
  const completed = items.filter(item => item.level === 'P0').map(item => ({ ...item, done: true }))
  const grouped = api.groupSetupItems(completed.concat(items[5]))
  const nodes = renderElements(parseWxml(overviewSource), { stationPanelShow: true, stationPanelItems: grouped, stationPanelDone: completed, stationPanelPending: [], stationPanelLoading: false }, { includeText: true })
  assert.equal(nodes.filter(node => node.attrs.class === 'st-module-done').length, 3)
  assert(nodes.filter(node => node.attrs.class === 'st-module-done').every(node => node.attrs['aria-label'] === '必填事项已完成'))
  assert(!nodes.some(node => (node.text || '').includes('本模块准备事项已完成')))
  assert(nodes.some(node => node.text === '水票设置 · 选填'))
  assert.equal(nodes.filter(node => node.attrs.bindtap === 'onPanelGoFill').length, 3)
})

test('completed preparation keeps original zero-pending truth and accessible completed modules', () => {
  const { page } = home()
  const complete = { ...items[0], done: true }
  const guide = { all: [complete], pending: [], p0PendingCount: 0, doneCount: 1, totalCount: 1, summaryText: '准备事项已完成', onlineHint: '已具备上线条件' }
  const before = JSON.stringify(guide)
  page.setData({ ...page.setupPatchOf(guide), stationPanelShow: true })
  assert.equal(page.data.stationPanelSummary, '')
  assert.equal(page.data.stationPendingCount, 0)
  assert.equal(page.data.setupRequiredPending, 0)
  assert.equal(page.data.setupDoneCount, 1)
  assert.equal(page.data.setupTotalCount, 1)
  page.onPanelShowDone()
  const nodes = renderElements(parseWxml(overviewSource), page.data, { includeText: true })
  assert(nodes.some(node => node.attrs.class === 'st-module-done' && node.text === '✓'))
  assert.equal(page.data.stationPanelItems[0].done, true)
  assert.equal(JSON.stringify(guide), before)
})

test('optional-only preparation has a neutral item count and no missing-item summary', () => {
  const { page, routes } = home()
  const complete = { ...items[0], done: true }, optional = { ...items[5], done: false }
  const guide = { all: [complete, optional], pending: [optional], p0PendingCount: 0, doneCount: 1, totalCount: 2, summaryText: '还需完善 1 项', onlineHint: '已具备上线条件' }
  const before = JSON.stringify(guide)
  page.setData({ ...page.setupPatchOf(guide), stationPanelShow: true })
  const nodes = renderElements(parseWxml(overviewSource), page.data, { includeText: true })
  assert(nodes.some(node => /准备事项\s+1/.test(node.text || '')))
  assert(!nodes.some(node => /待完善\s*1|还需完善\s*1|缺\s*1\s*项/.test(node.text || '')))
  assert(!nodes.some(node => node.attrs.class === 'st-summary'))
  assert(nodes.some(node => node.text === '水票设置 · 选填'))
  assert.equal(page.data.stationPendingCount, 0)
  assert.equal(page.data.setupRequiredPending, 0)
  assert.equal(page.data.setupDoneCount, 1)
  assert.equal(page.data.setupTotalCount, 1)
  assert.equal(page.data.stationPanelPending.length, 1)
  assert.equal(page.data.stationPanelItems[0].done, false)
  page.onPanelGoFill({ currentTarget: { dataset: { route: page.data.stationPanelItems[0].route } } })
  assert.deepEqual(routes, [productRoute])
  assert.equal(JSON.stringify(guide), before)
})

test('real required gaps preserve their count, summary and direct fill route', () => {
  const { page, routes } = home()
  const complete = { ...items[0], done: true }, required = { ...items[1], done: false }, optional = { ...items[5], done: false }
  const guide = { all: [complete, required, optional], pending: [required, optional], p0PendingCount: 1, doneCount: 1, totalCount: 3, summaryText: '还需完成 1 项必填', onlineHint: '请完善水站位置' }
  const before = JSON.stringify(guide)
  page.setData({ ...page.setupPatchOf(guide), stationPanelShow: true })
  const nodes = renderElements(parseWxml(overviewSource), page.data, { includeText: true })
  assert(nodes.some(node => /准备事项\s+2/.test(node.text || '')))
  assert(nodes.some(node => node.attrs.class === 'st-summary' && node.text === '还需完成 1 项必填'))
  assert(nodes.some(node => (node.text || '').includes('必填待完善：水站位置')))
  assert.equal(page.data.stationPendingCount, 1)
  assert.equal(page.data.setupRequiredPending, 1)
  assert.equal(page.data.setupDoneCount, 1)
  assert.equal(page.data.setupTotalCount, 2)
  page.onPanelGoFill({ currentTarget: { dataset: { route: required.route } } })
  assert.deepEqual(routes, [required.route])
  assert.equal(JSON.stringify(guide), before)
})

test('successful fee save refreshes the real preparation snapshot', async () => {
  const wx = createWx(), app = createApp(), calls = []
  const page = loadPage('miniapp-delivery/pages/station-mgmt/delivery-config/index.js', { wx, app, stubs: {
    'utils/request': { put: async () => ({ code: 0 }), get: async () => ({ code: 0, data: { config: {} } }) }
  } })
  page.load = () => {}
  page.loadSetup = () => calls.push('setup')
  await page.onSave()
  assert.deepEqual(calls, ['setup'])
})

;(async () => {
  const done = armWatchdog()
  let failures = 0
  try {
    for (const current of tests) {
      try { await current.run(); console.log('PASS ' + current.name) }
      catch (error) { failures++; console.log('FAIL ' + current.name + ': ' + error.stack) }
    }
    if (failures) process.exitCode = 1
    else console.log('AQUAFLOW_SUITE_OK ' + tests.length)
  } finally { done() }
})()
