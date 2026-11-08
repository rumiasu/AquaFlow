'use strict'
const assert = require('node:assert/strict')
const { loadPage, createApp, createWx, armWatchdog } = require('./harness')
const { createNavigator } = require('../../miniapp-delivery/utils/navigation')
const done = armWatchdog(), tests = []
const test = (name, run) => tests.push({ name, run })

function fixture(response = { orderId: 99 }) {
  const wx = createWx(), dialogs = [], feedback = [], writes = []
  const app = createApp({ _loginGeneration: 1, globalData: { isLogin: true,
    userInfo: { staffId: 8, stationId: 1, role: 'STATION_MANAGER', bindStatus: 'BOUND' } } })
  let page
  const navigation = createNavigator({ wx, pages: () => [page],
    session: () => JSON.stringify([app._loginGeneration, app.globalData.userInfo]),
    feedback: options => feedback.push(options) })
  page = loadPage('miniapp-delivery/pages/station-mgmt/place-order/index.js', { app, wx, stubs: {
    'utils/navigation': navigation,
    'utils/business-dialog': { showBusinessModal: options => dialogs.push(options) },
    'utils/request': { post: async (url, body) => { writes.push({ url, body }); return { code: 0, data: response } } }
  } })
  page.setData({ stationId: 1, customer: { id: 7 }, addressId: 8,
    cartLines: [{ productId: 9, quantity: 2 }], selectedMethod: 2, quote: { barrelPurchases: [] } })
  return { wx, app, page, dialogs, feedback, writes }
}

function taskPage(t) {
  const page = loadPage('miniapp-delivery/pages/home/index.js', { app: t.app, wx: t.wx,
    stubs: { 'utils/tabbar': { syncTabBar() {} } } })
  for (const method of ['_syncCollectionRecovery', 'resetHomeStationIdentity', 'loadHomeStationIdentity', 'loadStationPending', 'loadData']) page[method] = () => {}
  page.setData({ activeTab: 'completed' })
  return page
}

test('successful phone order keeps warnings and opens its exact detail without another write', async () => {
  const t = fixture({ orderId: 99, warnings: ['合成缺货提示'] })
  await t.page.doCreate(false)
  const dialog = t.dialogs[0]
  assert.equal(dialog.confirmText, '查看订单'); assert.equal(dialog.cancelText, '配送任务')
  assert(dialog.content.includes('付款状态')); assert(dialog.content.includes('付款状态与配送安排可在订单详情查看'))
  assert(dialog.content.includes('合成缺货提示'))
  dialog.success({ confirm: true })
  assert.deepEqual(t.wx.__calls.nav, [{ type: 'redirectTo', url: '/pages/order/detail?id=99' }])
  assert.equal(t.writes.length, 1); assert.equal(t.writes[0].body.source, 1)
})

test('task choice selects pending deliveries once and never accepts or collects the order', async () => {
  const t = fixture(); await t.page.doCreate(false)
  t.dialogs[0].success({ cancel: true })
  assert.deepEqual(t.wx.__calls.nav, [{ type: 'switchTab', url: '/pages/home/index' }])
  const tasks = taskPage(t); tasks.onShow()
  assert.equal(tasks.data.activeTab, 'assigned'); assert.equal(t.app.globalData.mineDeliveryTabIntent, undefined)
  tasks.setData({ activeTab: 'completed' }); tasks.onShow(); assert.equal(tasks.data.activeTab, 'completed')
  assert.equal(t.writes.length, 1); assert.equal(t.writes[0].url, '/api/orders/create')
})

test('task intent cannot select a tab after another login or station change', async () => {
  for (const change of [t => { t.app._loginGeneration++ }, t => { t.app.globalData.userInfo.stationId = 2 }]) {
    const t = fixture(); await t.page.doCreate(false); t.dialogs[0].success({ cancel: true }); change(t)
    const tasks = taskPage(t); tasks.onShow(); assert.equal(tasks.data.activeTab, 'completed')
    assert.equal(t.app.globalData.mineDeliveryTabIntent, undefined)
  }
})

test('leaving or changing identity dismisses both success destinations', async () => {
  for (const choice of [{ confirm: true }, { cancel: true }]) {
    for (const change of [t => t.page.onHide(), t => { t.app._loginGeneration++ }, t => { t.app.globalData.userInfo.stationId = 2 }]) {
      const t = fixture(); await t.page.doCreate(false); change(t); t.dialogs[0].success(choice)
      assert.equal(t.wx.__calls.nav.length, 0); assert.equal(t.app.globalData.mineDeliveryTabIntent, undefined)
    }
  }
})

test('unknown order and shortage confirmation never offer a successful delivery handoff', async () => {
  for (const response of [{}, { needConfirm: true, shortages: [] }]) {
    const t = fixture(response); await t.page.doCreate(false)
    assert.notEqual(t.dialogs[0].title, '下单成功'); assert.notEqual(t.dialogs[0].cancelText, '配送任务')
    assert.equal(t.wx.__calls.nav.length, 0); assert.equal(t.app.globalData.mineDeliveryTabIntent, undefined)
  }
})

test('failed navigation can retry the destination without submitting the order again', async () => {
  const t = fixture(); await t.page.doCreate(false)
  const switchTab = t.wx.switchTab
  t.wx.switchTab = options => options.fail({ errMsg: 'switchTab:fail' })
  t.dialogs[0].success({ cancel: true }); assert.equal(t.feedback.length, 1)
  t.wx.switchTab = switchTab; t.feedback[0].retry()
  assert.deepEqual(t.wx.__calls.nav, [{ type: 'switchTab', url: '/pages/home/index' }])
  assert.equal(t.writes.length, 1)
  const tasks = taskPage(t); tasks.onShow(); assert.equal(tasks.data.activeTab, 'assigned')
})

test('dialog lifecycle dismissal never acts as the delivery-task choice, even before page onHide', async () => {
  const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm')
  for (const hidden of [true, false]) {
    const t = fixture(); await t.page.doCreate(false)
    let definition
    vm.runInNewContext(fs.readFileSync(path.resolve(__dirname, '../../miniapp-delivery/components/BusinessDialog/index.js'), 'utf8'),
      { wx: t.wx, Component: value => { definition = value } })
    const component = { data: { ...definition.data }, setData(patch) { Object.assign(this.data, patch) } }
    for (const [key, fn] of Object.entries(definition.methods)) component[key] = fn.bind(component)
    definition.lifetimes.attached.call(component)
    const shown = component.open(t.dialogs[0])
    if (hidden) definition.pageLifetimes.hide.call(component)
    else component.onCancel()
    await shown
    assert.equal(t.wx.__calls.nav.length, hidden ? 0 : 1)
    assert.equal(t.writes.length, 1)
  }
})

;(async () => {
  for (const t of tests) { await t.run(); console.log('PASS ' + t.name) }
  done(); console.log('AQUAFLOW_SUITE_OK ' + tests.length)
})().catch(error => { done(); console.error(error); process.exitCode = 1 })
