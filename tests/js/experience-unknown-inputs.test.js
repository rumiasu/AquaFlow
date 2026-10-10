/** Real page/API/request functions and WXML projection; synthetic transport only. */
const assert = require('assert')
const fs = require('fs')
const path = require('path')
const { loadPage, createWx, createApp, armWatchdog, ROOT } = require('./harness')
const { parseWxml, renderElements } = require('./wxml-tree')
const tests = []
const test = (group, name, run) => tests.push({ group, name, run })
const flush = async () => { for (let i = 0; i < 12; i++) await Promise.resolve() }
const success = (call, data) => call.success({ statusCode: 200, data: { code: 0, data } })
const fail = call => call.fail({ errMsg: 'request:fail time out' })

function environment(relPage, staff, dispatch) {
  for (const file of Object.keys(require.cache)) if (/miniapp-(?:user|delivery)/.test(file)) delete require.cache[file]
  global.__wxConfig = { envVersion: 'develop' }
  const wx = createWx(), calls = []
  const app = createApp({ _loginGeneration: 1, globalData: {
    isLogin: true, customerId: 7, accessToken: 'synthetic-access-A', refreshToken: 'synthetic-refresh-A',
    userInfo: staff ? { staffId: 11, role: 'STATION_MANAGER', stationId: 11 } : { customerId: 7 }
  } })
  wx.setStorageSync('selectedStation', { id: 11, name: 'Synthetic station' })
  wx.request = call => { calls.push(call); dispatch(call) }
  const page = loadPage(relPage, { wx, app })
  page.setData = (patch, callback) => {
    for (const [key, value] of Object.entries(patch)) {
      const parts = key.split('.'); let target = page.data
      for (const part of parts.slice(0, -1)) target = target[part] || (target[part] = {})
      target[parts[parts.length - 1]] = value
    }
    if (callback) callback()
  }
  const tree = parseWxml(fs.readFileSync(path.join(ROOT, relPage.replace(/\.js$/, '.wxml')), 'utf8'))
  const elements = () => renderElements(tree, page.data, { includeText: true })
  return { page, wx, app, calls, elements }
}

function customers() {
  const pending = []
  const t = environment('miniapp-delivery/pages/station-mgmt/customers/index.js', true, call => {
    if (/\/enterprise\/manager\/config(?:\?|$)/.test(call.url) && call.method === 'PUT') success(call, {})
    else if (/\/enterprise\/manager\/config(?:\?|$)/.test(call.url)) pending.push(call)
    else if (/\/enterprise\/manager\/applications(?:\?|$)/.test(call.url)) success(call, [])
    else if (/\/customers(?:\?|$)/.test(call.url)) success(call, [{ id: 7, name: 'Synthetic customer', customerType: 1 }])
    else throw new Error('unexpected request: ' + call.url)
  })
  return { ...t, pending, text: () => t.elements().map(e => e.text).join('\n') }
}

test('enterprise', '首次配置超时保留未知入口，主客户查询独立完成', async () => {
  const t = customers(), task = t.page.onShow(); await flush()
  assert.strictEqual(t.page.data.loading, false); assert.strictEqual(t.page.data.list.length, 1)
  fail(t.pending[0]); await task
  assert.strictEqual(t.page.data.entCfgState, 'error')
  assert(t.text().includes('设置读取失败')); assert(t.elements().some(e => e.attrs.bindtap === 'onEntCfgRetry'))
  assert.strictEqual(t.page.data.loadError, '')
})
test('enterprise', '已开启配置随后超时不能被改成关闭，也不展示旧阈值为当前事实', async () => {
  const t = customers(), first = t.page.onShow(); await flush()
  success(t.pending[0], { enabled: true, barrelThreshold: 30, usingDefault: true }); await first
  const previous = JSON.stringify(t.page.data.entCfg), next = t.page.onSearch(); await flush()
  fail(t.pending[1]); await next
  assert.strictEqual(JSON.stringify(t.page.data.entCfg), previous)
  assert.strictEqual(t.page.data.entCfgState, 'error'); assert(t.text().includes('设置读取失败'))
  assert(!t.elements().some(e => e.attrs.bindtap === 'onEntOpen'))
})
test('enterprise', '只有成功返回关闭配置才能隐藏企业入口', async () => {
  const t = customers(), task = t.page.onShow(); await flush(); success(t.pending[0], { enabled: false }); await task
  assert.strictEqual(t.page.data.entCfgState, 'ready')
  assert(!t.elements().some(e => e.className === 'ent-entry'))
})
test('enterprise', '局部重试不重查客户，成功后恢复原阈值与审核入口', async () => {
  const t = customers(), task = t.page.onShow(); await flush(); fail(t.pending[0]); await task
  const customerCalls = t.calls.filter(c => /\/customers(?:\?|$)/.test(c.url)).length
  const retry = t.page.onEntCfgRetry(); await flush()
  assert.strictEqual(t.page.data.entCfgState, 'loading'); assert.strictEqual(t.page.data.loading, false)
  success(t.pending[1], { enabled: true, barrelThreshold: 30, usingDefault: true }); await retry
  assert.strictEqual(t.page.data.entCfgState, 'ready'); assert.strictEqual(t.page.data.entCfg.barrelThreshold, 30)
  assert(t.elements().some(e => e.attrs.bindtap === 'onEntOpen'))
  assert.strictEqual(t.calls.filter(c => /\/customers(?:\?|$)/.test(c.url)).length, customerCalls)
})
test('enterprise', '同页重叠配置请求只接受最新请求', async () => {
  const t = customers(), older = t.page.loadEnterpriseConfig(), newer = t.page.loadEnterpriseConfig(); await flush()
  success(t.pending[1], { enabled: true }); await newer
  success(t.pending[0], { enabled: false }); await older
  assert.strictEqual(t.page.data.entCfg.enabled, true)
})
test('enterprise', '离页、搜索变化及换登录周期均不回写迟到配置，普通续期仍有效', async () => {
  for (const change of [t => t.page.onHide(), t => t.page.onKeywordInput({ detail: { value: 'new' } }), t => { t.app._loginGeneration++ }]) {
    const t = customers(), task = t.page.loadEnterpriseConfig(); await flush(); change(t)
    const before = JSON.stringify(t.page.data); success(t.pending[0], { enabled: true }); await task
    assert.strictEqual(JSON.stringify(t.page.data), before)
  }
  const t = customers(), task = t.page.loadEnterpriseConfig(); await flush()
  t.app.globalData.accessToken = 'synthetic-refreshed'; success(t.pending[0], { enabled: true }); await task
  assert.strictEqual(t.page.data.entCfg.enabled, true)
})
test('enterprise', '空或损坏配置不能当作明确关闭', async () => {
  for (const data of [null, {}, { enabled: null }]) {
    const t = customers(), task = t.page.loadEnterpriseConfig(); await flush(); success(t.pending[0], data); await task
    assert.strictEqual(t.page.data.entCfgState, 'error'); assert(t.text().includes('设置读取失败'))
  }
})
test('enterprise', '设置弹窗未编辑的表单在重试及返回页面后同步当前阈值', async () => {
  const t = customers(), first = t.page.onShow(); await flush()
  success(t.pending[0], { enabled: true, barrelThreshold: 30 }); await first
  t.page.onEntOpen({ currentTarget: { dataset: { view: 'settings' } } })
  assert.strictEqual(t.page.data.entCfgForm.barrels, '30')
  const failed = t.page.loadEnterpriseConfig(); await flush(); fail(t.pending[1]); await failed
  assert(!t.elements().some(e => e.attrs.bindtap === 'onEntCfgSave'))
  const retry = t.page.onEntCfgRetry(); await flush(); success(t.pending[2], { enabled: true, barrelThreshold: 60 }); await retry
  assert.strictEqual(t.page.data.entCfgForm.barrels, '60')
  t.page.onHide(); const returned = t.page.onShow(); await flush()
  success(t.pending[3], { enabled: true, barrelThreshold: 70 }); await returned
  assert.strictEqual(t.page.data.entCfgForm.barrels, '70')
})
test('enterprise', '重试恢复配置不能覆盖用户已输入的阈值草稿', async () => {
  const t = customers(), first = t.page.onShow(); await flush()
  success(t.pending[0], { enabled: true, barrelThreshold: 30 }); await first
  t.page.onEntOpen({ currentTarget: { dataset: { view: 'settings' } } })
  t.page.onEntCfgBarrelInput({ detail: { value: '45' } })
  const failed = t.page.loadEnterpriseConfig(); await flush(); fail(t.pending[1]); await failed
  const retry = t.page.onEntCfgRetry(); await flush(); success(t.pending[2], { enabled: true, barrelThreshold: 60 }); await retry
  assert.strictEqual(t.page.data.entCfg.barrelThreshold, 60)
  assert.strictEqual(t.page.data.entCfgForm.barrels, '45')
})
test('enterprise', '换身份后不得保存旧站草稿，重新读取后回填当前站阈值', async () => {
  const t = customers(), first = t.page.onShow(); await flush()
  success(t.pending[0], { enabled: true, barrelThreshold: 30 }); await first
  t.page.onEntOpen({ currentTarget: { dataset: { view: 'settings' } } })
  t.page.onEntCfgBarrelInput({ detail: { value: '45' } })
  t.page.onHide(); t.app._loginGeneration++
  t.app.globalData.userInfo = { staffId: 22, role: 'STATION_MANAGER', stationId: 22 }
  await t.page.onEntCfgSave()
  assert.strictEqual(t.calls.filter(c => c.method === 'PUT').length, 0)
  const returned = t.page.onShow(); await flush()
  success(t.pending[1], { enabled: true, barrelThreshold: 60 }); await returned
  assert.strictEqual(t.page.data.entCfgForm.barrels, '60')
})

function order(data) {
  const writes = [], payments = [], reads = []
  const t = environment('miniapp-user/pages/order/create.js', false, call => {
    if (call.method === 'POST' && Array.isArray(call.data.items)) { writes.push(call.data); success(call, { orderId: 777, warnings: [] }) }
    else if (call.method === 'POST' && call.data.orderId) { payments.push(call.data.orderId); fail(call) }
    else if (/\/orders\/777(?:\?|$)/.test(call.url)) { reads.push(777); success(call, data) }
    else success(call, {})
  })
  t.wx.__modalAutoConfirm = false
  t.page.setData({ loading: false, stationId: 11, address: { id: 91 }, products: [{ id: 5, name: 'Synthetic water', price: 10, deposit: 0, quantity: 2 }],
    selectedMethod: 1, wechatPay: { enabled: true }, quoteReady: true, quoteLoading: false, quoteError: '', blocked: false,
    totalAmount: 20, totalWaterCost: 20, totalDeposit: 0, shortageItems: [], barrelPurchases: [] })
  return { ...t, writes, payments, reads, hint: () => t.elements().find(e => e.className === 'pending-order-text')?.text || '' }
}
for (const [name, data] of [['空详情', null], ['数字详情', 0], ['缺少支付字段', {}], ['空支付值', { paymentStatus: null }],
  ['空字符串', { paymentStatus: '' }], ['非法枚举', { paymentStatus: 99 }], ['布尔值', { paymentStatus: false }], ['数组值', { paymentStatus: [] }]]) {
  test('payment', name + '保留未知及原订单，不能宣称未付款', async () => {
    const t = order(data); await t.page.onSubmit()
    assert(t.wx.__calls.modal.some(m => m.title.includes('支付结果未知')))
    assert(t.hint().includes('待确认')); assert(!t.hint().includes('还没付款'))
    assert.strictEqual(t.page.data.lastSubmittedOrder.paymentState, 'unknown')
    t.page.setData({ lastSubmittedOrder: null }); t.page._restorePendingOrderFromStorage(); t.page._syncPendingOrderState()
    assert(t.hint().includes('待确认')); await t.page.onSubmit()
    assert.strictEqual(t.writes.length, 1); assert.deepStrictEqual(t.payments, [777]); assert.deepStrictEqual(t.reads, [777])
  })
}
test('payment', '合法数字及旧数字字符串保持原已付、未付与关闭语义', async () => {
  for (const [status, state] of [[0, 'unpaid'], [1, 'unpaid'], [2, 'paid'], [3, 'closed'], [4, 'closed']]) {
    for (const paymentStatus of [status, String(status)]) assert.strictEqual(await order({ paymentStatus }).page._verifyOrderPayment(777), state)
  }
})

;(async () => {
  const done = armWatchdog(30000), selected = tests.filter(t => (!process.env.UNKNOWN_INPUT_GROUP || t.group === process.env.UNKNOWN_INPUT_GROUP)
    && (!process.env.UNKNOWN_INPUT_CASE || t.name.includes(process.env.UNKNOWN_INPUT_CASE)))
  let failures = 0
  try {
    for (const t of selected) {
      try { await t.run(); console.log('PASS ' + t.name) }
      catch (error) { failures++; console.log('FAIL ' + t.name + ': ' + error.message) }
    }
  } finally { done() }
  console.log('AQUAFLOW_UNKNOWN_INPUTS_RECORDED ' + selected.length + ' ' + failures)
  if (!failures) console.log('AQUAFLOW_SUITE_OK ' + selected.length)
  process.exitCode = failures ? 1 : 0
})().catch(error => { console.error(error); process.exitCode = 1 })
