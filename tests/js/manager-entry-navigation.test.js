'use strict'
const assert = require('node:assert/strict')
const fs = require('fs'), path = require('path')
const { loadPage, createWx, createApp, armWatchdog, ROOT } = require('./harness')
const { parseWxml, renderElements, validateConditionalSiblings } = require('./wxml-tree')
const done = armWatchdog(), tests = [], test = (name, run) => tests.push({ name, run })
const paths = {
  home: 'miniapp-delivery/pages/coordination/index', mine: 'miniapp-delivery/pages/mine/index',
  workbench: 'miniapp-delivery/pages/station-mgmt/index', staff: 'miniapp-delivery/pages/station-mgmt/staff/index'
}
const trees = Object.fromEntries(Object.entries(paths).map(([name, file]) => {
  const tree = parseWxml(fs.readFileSync(path.join(ROOT, file + '.wxml'), 'utf8'))
  validateConditionalSiblings(tree)
  return [name, tree]
}))
const tabPaths = require('../../miniapp-delivery/app.json').tabBar.list.map(t => '/' + t.pagePath)
const nodes = (name, page) => renderElements(trees[name], page.data, { includeText: true })
const entries = (name, page, handler) => nodes(name, page).filter(n => n.attrs.bindtap === handler)
const tap = dataset => ({ currentTarget: { dataset } })
function fixture(role = 'STATION_MANAGER', stationId = 3, isLogin = true) {
  const wx = createWx(), calls = [], highlights = [], guards = []
  const app = createApp({ globalData: { isLogin, userInfo: { staffId: 7, role, stationId, bindStatus: 'BOUND' } } })
  app.isStationManager = () => app.globalData.userInfo.role === 'STATION_MANAGER'
  app.canAccessStationBusiness = () => !!app.globalData.userInfo.stationId
  app.routeByRole = silent => guards.push(silent)
  for (const method of ['navigateTo', 'switchTab', 'navigateBack']) wx[method] = options => {
    if (method === 'navigateTo') assert(!tabPaths.includes(options.url.split('?')[0]), 'tab pages require switchTab')
    if (method === 'switchTab') { assert(tabPaths.includes(options.url)); assert(!options.url.includes('?')) }
    calls.push({ method, ...options }); if (options.success) options.success({ errMsg: method + ':ok' })
  }
  const load = name => loadPage(paths[name] + '.js', { wx, app, stubs: {
    'api/delivery': { getStaffList: async () => ({ code: 0, data: [] }) },
    'utils/request': { get: async () => ({ code: 0, data: [] }) }
  } })
  const home = () => {
    const p = load('home')
    p.loadStationStatus = () => {}; p.loadStationPending = () => {}; p.loadTodo = () => {}; p.loadAllData = () => {}
    p.getTabBar = () => ({ sync: selected => highlights.push(selected) })
    wx.setStorageSync('delivery-coordination-guide-v2', true)
    p.checkRole()
    return p
  }
  return { wx, app, calls, highlights, guards, load, home }
}

test('真实站长首页零待办、读取中和失败时均有一个可点击工作台入口', () => {
  for (const state of [
    { todo: { items: [] }, todoError: '', todoLoading: false },
    { todo: null, todoError: '', todoLoading: true },
    { todo: null, todoError: '待办未核对', todoLoading: false, loadError: '合成读取失败' }
  ]) {
    const f = fixture(), p = f.home(); p.setData(state)
    const visible = entries('home', p, 'onGoStationMgmt')
    assert.equal(visible.length, 1); assert.equal(visible[0].attrs['aria-label'], '进入工作台')
    p[visible[0].attrs.bindtap]()
    assert.equal(f.calls.length, 1); assert.equal(f.calls[0].method, 'navigateTo')
    assert.equal(f.calls[0].url, '/pages/station-mgmt/index')
  }
})

test('配送员、未登录和未建站账号无工作台入口，直接调用旧handler也不跳转', () => {
  for (const args of [['DELIVERY', 3, true], ['STATION_MANAGER', 3, false], ['STATION_MANAGER', null, true]]) {
    const f = fixture(...args), p = f.home()
    assert.equal(entries('home', p, 'onGoStationMgmt').length, 0)
    p.onGoStationMgmt(); assert.equal(f.calls.length, 0)
  }
})

test('首页保留旧渲染时换号或离站，点击仍按当前身份拒绝；onShow重新投影', () => {
  for (const change of [f => { f.app.globalData.userInfo.role = 'DELIVERY' }, f => { f.app.globalData.isLogin = false }, f => { f.app.globalData.userInfo.stationId = null }]) {
    const f = fixture(), p = f.home(); assert.equal(entries('home', p, 'onGoStationMgmt').length, 1)
    change(f); p.onGoStationMgmt(); assert.equal(f.calls.length, 0)
    p.onShow(); assert.equal(entries('home', p, 'onGoStationMgmt').length, 0)
  }
})

test('从工作台返回首页保留调度页签，并恢复合法首页tab高亮', () => {
  const f = fixture(), p = f.home(); p.setData({ activeTab: 'approval', approvalTab: 'station' })
  p.onShow(); p.onGoStationMgmt(); p.onHide(); p.onShow()
  assert.equal(p.data.activeTab, 'approval'); assert.equal(p.data.approvalTab, 'station')
  assert.equal(f.highlights.at(-1), '/pages/coordination/index')
  assert(tabPaths.includes(f.highlights.at(-1)))
})

test('我的员工更多保留申请页签，普通员工入口仍落员工名单', () => {
  for (const selected of [0, 1]) {
    const f = fixture(), mine = f.load('mine')
    mine.setData({ isLogin: true, isManager: true, staffExpanded: true, mgmtActiveTab: selected, userInfo: f.app.globalData.userInfo })
    const more = entries('mine', mine, 'onGoStaff'); assert.equal(more.length, 1)
    mine[more[0].attrs.bindtap]()
    const destination = f.calls.at(-1)
    assert.equal(destination.method, 'navigateTo')
    assert.equal(destination.url, '/pages/station-mgmt/staff/index' + (selected === 1 ? '?tab=apply' : ''))
    const staff = f.load('staff'), options = Object.fromEntries(new URLSearchParams(destination.url.split('?')[1] || ''))
    staff.onLoad(options); staff.loadData = () => {}; staff.onShow()
    assert.equal(staff.data.activeTab, selected === 1 ? 'apply' : 'staff')
    const active = entries('staff', staff, 'onTabSwitch').filter(n => n.className.includes('af-choice--selected'))
    assert.equal(active.length, 1); assert.equal(active[0].attrs['data-tab'], staff.data.activeTab)
  }
})

test('我的旧员工入口不能由配送员、游客或无水站账号继续进入', () => {
  for (const args of [['DELIVERY', 3, true], ['STATION_MANAGER', 3, false], ['STATION_MANAGER', null, true]]) {
    const f = fixture(...args), mine = f.load('mine')
    mine.setData({ isLogin: true, isManager: true, mgmtActiveTab: 1 })
    mine.onGoStaff(); assert.equal(f.calls.length, 0)
  }
})

test('首页员工申请和工作台带参数跳转均完整到达申请页签，不回退到旧名单', () => {
  const f = fixture(), home = f.home()
  home.onTodoTap(tap({ key: 'staffBinding' }))
  assert.equal(f.calls.at(-1).url, '/pages/station-mgmt/staff/index?tab=apply')
  const menu = f.load('workbench')
  global.getCurrentPages = () => [{ route: 'pages/coordination/index' }, { route: 'pages/station-mgmt/staff/index', options: {} }, menu]
  menu.onNavigate(tap({ url: '/pages/station-mgmt/staff/index?tab=apply' }))
  assert.equal(f.calls.at(-1).method, 'navigateTo'); assert.equal(f.calls.at(-1).url, '/pages/station-mgmt/staff/index?tab=apply')
  const staff = f.load('staff'); staff.onLoad({ tab: 'apply' }); assert.equal(staff.data.activeTab, 'apply')
})

test('工作台逾期角标落到逾期客户视图，普通应收入口仍落全量', () => {
  const f = fixture(), menu = f.load('workbench')
  menu.setData({ receivablesBadgeCount: 2, businessWaitingBadgeCount: 0,
    barrelReturnBadgeCount: 0, refundDisputeBadgeCount: 0 })
  const pending = entries('workbench', menu, 'onNavigate').find(n => n.className.includes('v2-pending--money'))
  assert(pending, '逾期角标应可见')
  assert.equal(pending.attrs['data-url'], '/pages/station-mgmt/receivables/index?overdue=1')
  global.getCurrentPages = () => [{ route: 'pages/station-mgmt/index' }, menu]
  menu.onNavigate(tap({ url: pending.attrs['data-url'] }))
  assert.equal(f.calls.at(-1).url, pending.attrs['data-url'])
  // onLoad 是应收页的真实落地筛选，入口不能只改一段文案。
  const ledger = loadPage('miniapp-delivery/pages/station-mgmt/receivables/index.js', { wx: f.wx, app: f.app })
  ledger.onLoad(Object.fromEntries(new URLSearchParams(f.calls.at(-1).url.split('?')[1])))
  assert.equal(ledger.data.onlyOverdue, true)
  const ordinary = entries('workbench', menu, 'onNavigate').find(n => n.attrs['data-url'] === '/pages/station-mgmt/receivables/index')
  assert(ordinary)
  assert.equal(ordinary.attrs['data-url'], '/pages/station-mgmt/receivables/index')
  const ordinaryLedger = loadPage('miniapp-delivery/pages/station-mgmt/receivables/index.js', { wx: f.wx, app: f.app })
  ordinaryLedger.onLoad({})
  assert.equal(ordinaryLedger.data.onlyOverdue, false)
})

test('员工页只接受两个合法页签，坏事件不会让申请页面变空或退回名单', () => {
  const f = fixture(), staff = f.load('staff'); staff.onLoad({ tab: 'apply' })
  for (const tab of ['', null, undefined, 'unknown', '0']) {
    staff.onTabSwitch(tap({ tab })); assert.equal(staff.data.activeTab, 'apply')
    assert.equal(entries('staff', staff, 'onTabSwitch').filter(n => n.className.includes('af-choice--selected')).length, 1)
  }
  staff.onTabSwitch(tap({ tab: 'staff' })); assert.equal(staff.data.activeTab, 'staff')
  staff.onTabSwitch(tap({ tab: 'apply' })); assert.equal(staff.data.activeTab, 'apply')
  const invalidLanding = f.load('staff'); invalidLanding.onLoad({ tab: 'unknown' }); assert.equal(invalidLanding.data.activeTab, 'staff')
})

;(async () => {
  let passed = 0
  for (const { name, run } of tests) { await run(); passed++; console.log('PASS ' + name) }
  console.log('AQUAFLOW_SUITE_OK ' + passed)
})().catch(e => { console.error(e); process.exitCode = 1 }).finally(done)
