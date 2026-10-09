/** Actual pages/API/request modules and WXML projection; synthetic transport, no native GUI/service/DB. */
const assert = require('assert')
const fs = require('fs')
const path = require('path')
const { loadPage, createWx, createApp, armWatchdog, ROOT } = require('./harness')
const { parseWxml, renderElements } = require('./wxml-tree')
const tests = [], results = [], observations = []
let caseName = ''
const test = (name, run) => tests.push({ name, run })
const flush = async () => { for (let i = 0; i < 12; i++) await Promise.resolve() }
const success = (call, data) => call.success({ statusCode: 200, data: { code: 0, data } })
const fail = call => call.fail({ errMsg: 'request:fail time out' })
const copy = value => JSON.parse(JSON.stringify(value))
const event = dataset => ({ currentTarget: { dataset } })
const application = id => ({ id, companyName: 'Synthetic company ' + id, applyTime: '2026-10-09T12:34:56', contactPhone: '13800000001' })

function environment(relPage, staff, dispatch) {
  for (const file of Object.keys(require.cache)) {
    if (/miniapp-(?:user|delivery)/.test(file)) delete require.cache[file]
  }
  global.__wxConfig = { envVersion: 'develop' }
  const wx = createWx(), calls = []
  wx.makePhoneCall = options => { wx.__phone = options.phoneNumber }
  wx.stopPullDownRefresh = () => { wx.__pullStops = (wx.__pullStops || 0) + 1 }
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

function customers(enabled = true) {
  const pending = []
  const t = environment('miniapp-delivery/pages/station-mgmt/customers/index.js', true, call => {
    if (/\/enterprise\/manager\/applications(?:\?|$)/.test(call.url)) pending.push(call)
    else if (/\/enterprise\/manager\/config(?:\?|$)/.test(call.url)) success(call, { enabled })
    else if (/\/offline-payment\/summary(?:\?|$)/.test(call.url)) success(call, { orderCount: 3, offlinePaymentEnabled: 1 })
    else if (/\/customers(?:\?|$)/.test(call.url)) success(call, [{ id: 7, name: 'Synthetic customer', customerType: 1, phone: '13800000007' }])
    else throw new Error('unexpected request: ' + call.method + ' ' + call.url)
  })
  const enterpriseText = () => t.elements().filter(e => /^(ent-entry-desc|ent-empty)$/.test(e.className)).map(e => e.text).join('\n')
  const titles = () => t.elements().filter(e => e.text.startsWith('企业身份申请')).map(e => e.text).join('\n')
  return { ...t, pending, enterpriseText, titles }
}
const openReview = t => t.page.onEntOpen(event({ view: 'review' }))
const noFalseEmpty = t => {
  observations.push({ case: caseName, enterpriseState: t.page.data.entAppliesState || 'missing',
    enterpriseText: t.enterpriseText(), modalTitle: t.titles(), mainLoading: t.page.data.loading, customerIds: t.page.data.list.map(c => c.id) })
  assert(!t.enterpriseText().includes('暂无待审申请'), 'unverified/loading/failure cannot assert an empty application list')
  assert(!t.titles().includes('（0）'), 'unknown count cannot be displayed as zero')
}

test('ENT: 首次未核实、在途读取与客户主列表独立可用', async () => {
  const t = customers(); t.page.setData({ 'entCfg.enabled': true }); openReview(t)
  noFalseEmpty(t); assert(t.enterpriseText().includes('未核实'))
  const task = t.page.onShow(); await flush()
  noFalseEmpty(t); assert(t.enterpriseText().includes('加载中'))
  assert.strictEqual(t.page.data.loading, false); assert.strictEqual(t.page.data.list.length, 1)
  assert(t.elements().some(e => e.attrs.bindtap === 'onManage'))
  success(t.pending[0], []); await task
})
test('ENT: 成功非空显示真实数量及原审核入口', async () => {
  const t = customers(), task = t.page.onShow(); await flush(); success(t.pending[0], [application(1)]); await task; openReview(t)
  assert(t.enterpriseText().includes('有 1 条')); assert(t.titles().includes('（1）'))
  assert(t.elements().some(e => e.attrs.bindtap === 'onEntApprove' && e.id === 1))
  assert(t.elements().some(e => e.attrs.bindtap === 'onEntReject' && e.id === 1))
  assert.strictEqual(t.page.data.entApplies[0].applyTimeText, '2026-10-09 12:34')
})
test('ENT: 只有成功空列表显示暂无与零数量', async () => {
  const t = customers(), task = t.page.onShow(); await flush(); success(t.pending[0], []); await task; openReview(t)
  assert.strictEqual(t.page.data.entAppliesState, 'ready')
  assert.strictEqual(t.enterpriseText().split('暂无待审申请').length - 1, 2)
  assert(t.titles().includes('（0）'))
})
test('ENT: 读取失败在入口与弹窗明确显示失败，查询/详情/致电/货到付款保持可用', async () => {
  const t = customers(), task = t.page.onShow(); await flush(); fail(t.pending[0]); await task; openReview(t)
  noFalseEmpty(t); assert(t.enterpriseText().includes('读取失败')); assert.strictEqual(t.page.data.loadError, '')
  for (const handler of ['onManage', 'onCall', 'onCod']) assert(t.elements().some(e => e.attrs.bindtap === handler))
  t.page.onManage(event({ id: 7 })); t.page.onCall(event({ phone: '13800000007' })); await t.page.onCod(event({ id: 7, name: 'Synthetic customer' }))
  assert(t.wx.__calls.nav.some(n => n.url.endsWith('/detail/index?id=7'))); assert.strictEqual(t.wx.__phone, '13800000007')
  assert.strictEqual(t.page.data.codModal.visible, true); assert.strictEqual(t.page.data.codForm.enabled, true)
  const retrySearch = t.page.onSearch(); await flush(); success(t.pending[1], []); await retrySearch
  assert.strictEqual(t.page.data.list.length, 1)
})
test('ENT: 局部重试可恢复，连点不重发，也不清空客户列表', async () => {
  const t = customers(), task = t.page.onShow(); await flush(); fail(t.pending[0]); await task; openReview(t)
  const retry = t.elements().find(e => e.attrs.bindtap === 'onEntRetry' || e.attrs.catchtap === 'onEntRetry')
  assert(retry, 'visible failure must provide a wired local retry')
  const mainReads = t.calls.filter(c => /\/customers(?:\?|$)/.test(c.url)).length
  const next = t.page.onEntRetry(); await flush(); t.page.onEntRetry()
  assert.strictEqual(t.pending.length, 2); assert.strictEqual(t.page.data.list.length, 1)
  success(t.pending[1], [application(2)]); await next
  assert.strictEqual(t.page.data.entAppliesState, 'ready'); assert.deepStrictEqual(t.page.data.entApplies.map(a => a.id), [2])
  assert.strictEqual(t.calls.filter(c => /\/customers(?:\?|$)/.test(c.url)).length, mainReads)
})
for (const oldOutcome of ['success', 'failure']) {
  test('ENT: 旧' + oldOutcome + '不能覆盖新轮成功或失败', async () => {
    for (const newOutcome of ['success', 'failure']) {
      const t = customers(), old = t.page.onShow(); await flush()
      const latest = t.page.onSearch(); await flush()
      if (newOutcome === 'success') success(t.pending[1], [application(2)]); else fail(t.pending[1])
      await latest; const before = copy(t.page.data)
      if (oldOutcome === 'success') success(t.pending[0], [application(1)]); else fail(t.pending[0])
      await old; assert.deepStrictEqual(t.page.data, before)
    }
    const t = customers(), old = t.page.onShow(); await flush(); t.page.onHide()
    const shown = t.page.onShow(); await flush(); success(t.pending[1], [application(2)]); await shown
    const before = copy(t.page.data)
    if (oldOutcome === 'success') success(t.pending[0], [application(1)]); else fail(t.pending[0])
    await old; assert.deepStrictEqual(t.page.data, before)
  })
  test('ENT: 离页或换登录后的旧' + oldOutcome + '不能修改当前页面', async () => {
    for (const invalidate of [t => t.page.onHide(), t => t.page.onUnload(), t => { t.app._loginGeneration++ },
      t => { t.app.globalData.userInfo.stationId = 22 }]) {
      const t = customers(), task = t.page.onShow(); await flush(); invalidate(t); const before = copy(t.page.data)
      if (oldOutcome === 'success') success(t.pending[0], [application(1)]); else fail(t.pending[0])
      await task; assert.deepStrictEqual(t.page.data, before)
    }
  })
}
test('ENT: 局部直接读取也有独立序号、登录与离页保护', async () => {
  const t = customers(), old = t.page.loadEnterpriseApplies(); await flush()
  const latest = t.page.loadEnterpriseApplies(); await flush(); success(t.pending[1], [application(2)]); await latest
  const before = copy(t.page.data); success(t.pending[0], [application(1)]); await old; assert.deepStrictEqual(t.page.data, before)
  for (const invalidate of [() => t.page.onHide(), () => { t.page._customersHidden = false; t.app._loginGeneration++ }]) {
    t.page._customersHidden = false
    const next = t.page.loadEnterpriseApplies(); await flush(); invalidate(); const state = copy(t.page.data)
    fail(t.pending[t.pending.length - 1]); await next; assert.deepStrictEqual(t.page.data, state)
  }
})
test('ENT: 编辑关键字使在途结果失效，待审恢复为未核实并可再次核实', async () => {
  const t = customers(), task = t.page.onShow(); await flush()
  t.page.onKeywordInput({ detail: { value: 'new query' } }); const before = copy(t.page.data)
  success(t.pending[0], [application(1)]); await task; assert.deepStrictEqual(t.page.data, before)
  noFalseEmpty(t); assert(t.enterpriseText().includes('未核实'))
  const retry = t.page.onEntRetry(); await flush(); success(t.pending[1], []); await retry
  assert.strictEqual(t.page.data.entAppliesState, 'ready')
})
test('ENT: 损坏数据不得冒充成功空列表；默认关闭入口保留', async () => {
  const t = customers(), task = t.page.onShow(); await flush(); success(t.pending[0], null); await task; openReview(t)
  noFalseEmpty(t); assert.strictEqual(t.page.data.entAppliesState, 'error')
  const disabled = customers(false), next = disabled.page.onShow(); await flush(); success(disabled.pending[0], []); await next
  assert(!disabled.elements().some(e => e.className === 'ent-entry'))
})
test('ENT: 普通token续期同周期响应仍有效', async () => {
  const t = customers(), task = t.page.onShow(); await flush(); t.app.globalData.accessToken = 'synthetic-refreshed'
  success(t.pending[0], [application(1)]); await task; assert.strictEqual(t.page.data.entApplies.length, 1)
})

function order(paymentResult, readResult) {
  const writes = [], payments = [], reads = []
  const t = environment('miniapp-user/pages/order/create.js', false, call => {
    if (call.method === 'POST' && Array.isArray(call.data.items)) { writes.push(copy(call.data)); success(call, { orderId: 777, warnings: [] }) }
    else if (call.method === 'POST' && call.data.orderId) { payments.push(call.data.orderId); paymentResult(call) }
    else if (/\/orders\/777(?:\?|$)/.test(call.url)) { reads.push(777); readResult(call) }
    else success(call, {})
  })
  t.wx.__modalAutoConfirm = false
  t.page.setData({ loading: false, stationId: 11, address: { id: 91 }, products: [{ id: 5, name: 'Synthetic water', price: 10, deposit: 0, quantity: 2 }],
    selectedMethod: 1, wechatPay: { enabled: true }, quoteReady: true, quoteLoading: false, quoteError: '', blocked: false,
    totalAmount: 20, totalWaterCost: 20, totalDeposit: 0, shortageItems: [], barrelPurchases: [] })
  const hint = () => t.elements().find(e => e.className === 'pending-order-text')?.text || ''
  return { ...t, writes, payments, reads, hint }
}
test('PAY: 双超时弹窗和恢复卡片都保留未知，重进与续办仍绑定原单', async () => {
  const t = order(fail, fail); await t.page.onSubmit()
  observations.push({ case: caseName, hint: t.hint(), modalTitles: t.wx.__calls.modal.map(m => m.title),
    orderWrites: t.writes.length, paymentIds: t.payments, readIds: t.reads, cachedOrder: t.wx.getStorageSync('recentOrder.v1') })
  assert(t.wx.__calls.modal.some(m => m.title.includes('支付结果未知')))
  assert(t.hint().includes('待确认')); assert(!t.hint().includes('还没付款'))
  t.page.setData({ lastSubmittedOrder: null }); t.page._restorePendingOrderFromStorage(); t.page._syncPendingOrderState()
  assert(t.hint().includes('待确认')); await t.page.onSubmit()
  assert.strictEqual(t.writes.length, 1); assert.deepStrictEqual(t.payments, [777]); assert.deepStrictEqual(t.reads, [777])
  assert(t.wx.__calls.nav.every(n => n.url.includes('id=777')))
  const acknowledged = order(call => success(call, { status: 1 }), fail); await acknowledged.page.onSubmit()
  assert(acknowledged.hint().includes('待确认')); assert.strictEqual(acknowledged.writes.length, 1)
  const retried = order(fail, fail); retried.wx.__modalAutoConfirm = true; await retried.page.onSubmit()
  assert(retried.hint().includes('待确认')); assert.strictEqual(retried.writes.length, 1)
  assert.deepStrictEqual(retried.payments, [777, 777])
})
test('PAY: 回读确实未付与已付，恢复卡片保持各自事实；旧未知缓存不猜未付', async () => {
  const unpaid = order(fail, call => success(call, { paymentStatus: 0 })); await unpaid.page.onSubmit()
  assert(unpaid.hint().includes('还没付款')); assert.strictEqual(unpaid.page.data.pendingOrderPaid, false)
  const paid = order(fail, call => success(call, { paymentStatus: 2 })); await paid.page.onSubmit()
  assert(paid.hint().includes('已经下好了')); assert.strictEqual(paid.page.data.pendingOrderPaid, true)
  const legacy = order(fail, fail); await legacy.page.onSubmit()
  delete legacy.page.data.lastSubmittedOrder.paymentState; legacy.page._syncPendingOrderState()
  assert(legacy.hint().includes('待确认'))
})
test('RETURN: 未领桶权益与实物退还共用提示不强迫无桶交桶，实物仍须核验', async () => {
  const t = environment('miniapp-user/pages/barrel/index.js', false, () => { throw new Error('copy check must not request business writes') })
  t.page.setData({ loading: false, summary: { independentRights: true, depositPerBucket: 50 }, records: [
    { id: 1, type: 2, returnDetail: { requiredBarrels: 0, pickupFee: 0, status: 'APPLIED' } },
    { id: 2, type: 2, returnDetail: { requiredBarrels: 2, pickupFee: 0, status: 'APPLIED' } }
  ] })
  const visible = t.elements().map(e => e.text).join('\n')
  assert(!visible.includes('确认收到空桶后再处理退款'))
  assert(visible.includes('核实') && visible.includes('未领桶权益'))
  assert(visible.includes('应交回 0 桶') && visible.includes('应交回 2 桶'))
  t.page.onEcoRuleTap(); const content = t.wx.__calls.modal[0].content
  assert(content.includes('未领桶权益')); assert(content.includes('应交回的空桶') && content.includes('确认收到'))
  assert.strictEqual(t.calls.length, 0)
})

function textColor(css, className, tokens) {
  let chosen = null, specificity = 0
  const classes = className.split(/\s+/)
  for (const rule of css.matchAll(/([^{}]+)\{([^{}]+)\}/g)) {
    for (const selector of rule[1].trim().split(/\s*,\s*/)) {
      if (!/^\.[\w-]+(?:\.[\w-]+)*$/.test(selector)) continue
      const names = selector.slice(1).split('.')
      const color = /(?:^|;)\s*color\s*:\s*([^;]+)/.exec(rule[2])
      if (color && names.every(n => classes.includes(n)) && names.length >= specificity) {
        specificity = names.length; chosen = color[1].trim()
      }
    }
  }
  return chosen?.replace(/var\((--[\w-]+)\)/g, (_, key) => {
    const value = new RegExp(key + '\\s*:\\s*(#[0-9a-fA-F]{6})').exec(tokens)
    assert(value, 'missing concrete color ' + key); return value[1]
  })
}
function contrast(foreground, background) {
  const luminance = hex => {
    const rgb = hex.slice(1).match(/../g).map(h => parseInt(h, 16) / 255).map(c => c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4)
    return rgb[0] * 0.2126 + rgb[1] * 0.7152 + rgb[2] * 0.0722
  }
  const a = luminance(foreground), b = luminance(background)
  return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05)
}
test('CONTRAST: 同名无电话的地址/来源与退款金额/登记核实文字达到正文对比', async () => {
  const t = customers(), task = t.page.onShow(); await flush(); success(t.pending[0], []); await task
  t.page.setData({ list: [
    { id: 1, name: 'Synthetic same name', tagsList: [], matchedAddressText: 'Synthetic address A', matchedAddressSource: 'ORDER_HISTORY' },
    { id: 2, name: 'Synthetic same name', tagsList: [], addressText: 'Synthetic address B' }
  ] })
  const staffCss = fs.readFileSync(path.join(ROOT, 'miniapp-delivery/pages/station-mgmt/customers/index.wxss'), 'utf8')
  const staffTokens = fs.readFileSync(path.join(ROOT, 'miniapp-delivery/app.wxss'), 'utf8')
  assert(/\.customer-card\s*\{[^}]*background:\s*var\(--bg-card\)/.test(staffCss))
  assert(/--bg-card:\s*#FFFFFF/i.test(staffTokens)); assert(/\.customer-phone\s*\{[^}]*font-size:\s*26rpx/.test(staffCss))
  const amounts = environment('miniapp-user/pages/barrel/index.js', false, () => {})
  amounts.page.setData({ loading: false, summary: {}, records: [{ id: 1, type: 2, depositRefund: '100.00',
    statusText: '已登记', refundPaidTime: '2026-10-09 12:34', handleNote: 'Synthetic handling note' }] })
  const userCss = fs.readFileSync(path.join(ROOT, 'miniapp-user/pages/barrel/index.wxss'), 'utf8')
  const userTokens = fs.readFileSync(path.join(ROOT, 'miniapp-user/styles/variable.wxss'), 'utf8')
  assert(/\.section\s*\{[^}]*background-color:\s*#fff/.test(userCss))
  assert(/\.record-summary\s*\{[^}]*font-size:\s*24rpx/.test(userCss))
  const nodes = [...t.elements().filter(e => e.text.includes('地址')), ...amounts.elements().filter(e => /^应退押金|^水站登记退款交付时间/.test(e.text))]
  assert.strictEqual(nodes.length, 4)
  const evidence = nodes.map(e => {
    const staff = e.text.includes('地址'), color = textColor(staff ? staffCss : userCss, e.className, staff ? staffTokens : userTokens)
    const ratio = contrast(color, '#FFFFFF')
    return { text: e.text, className: e.className, color, background: '#FFFFFF', fontRpx: staff ? 26 : 24, ratio }
  })
  observations.push({ contrast: evidence })
  for (const e of evidence) assert(e.ratio >= 4.5, e.text + ': contrast ' + e.ratio + ' (' + e.color + ' on ' + e.background + ')')
})

;(async () => {
  const done = armWatchdog(30000)
  try {
    for (const t of tests) {
      caseName = t.name
      try { await t.run(); results.push({ name: t.name, passed: true }); console.log('PASS ' + t.name) }
      catch (error) { results.push({ name: t.name, passed: false, error: String(error) }); console.log('FAIL ' + t.name + ': ' + error.message) }
    }
  } finally { done() }
  const result = { baseline: '685b1f573fc83b83c01a782a1461b7530287f127', syntheticOnly: true,
    realPagesApisRequest: true, nativeGui: false, cases: results.length, failures: results.filter(r => !r.passed).length, results, observations }
  if (process.env.FACT_STATES_EVIDENCE) fs.writeFileSync(process.env.FACT_STATES_EVIDENCE, JSON.stringify(result, null, 2) + '\n')
  console.log('AQUAFLOW_FACT_STATES_RECORDED ' + result.cases + ' ' + result.failures)
  if (!result.failures) console.log('AQUAFLOW_SUITE_OK ' + result.cases)
  process.exitCode = result.failures ? 1 : 0
})().catch(error => { console.error(error); process.exitCode = 1 })
