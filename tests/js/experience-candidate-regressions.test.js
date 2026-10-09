/** Actual pages/API/request modules with synthetic in-memory wx transport; no service/DB or real WeChat. */
const assert = require('assert')
const fs = require('fs')
const path = require('path')
const { loadPage, createWx, createApp, armWatchdog, ROOT } = require('./harness')
const { parseWxml, renderElements } = require('./wxml-tree')
const tests = [], results = [], observations = []
const test = (name, run) => tests.push({ name, run })
const flush = async () => { for (let i = 0; i < 12; i++) await Promise.resolve() }
const success = (call, data) => call.success({ statusCode: 200, data: { code: 0, data } })
const timeout = call => call.fail({ errMsg: 'request:fail time out' })
const copy = data => JSON.parse(JSON.stringify(data))
let currentName = ''
const record = detail => observations.push({ case: currentName, ...copy(detail) })

function nativeSetData(page) {
  // The common harness assigns shallow properties. These pages use native dotted paths for editable fields.
  page.setData = (patch, callback) => {
    for (const [key, value] of Object.entries(patch)) {
      const parts = key.split('.'); let target = page.data
      for (const part of parts.slice(0, -1)) target = target[part] || (target[part] = {})
      target[parts[parts.length - 1]] = value
    }
    if (callback) callback()
  }
}

function environment(side, relPage, dispatch) {
  for (const file of Object.keys(require.cache)) {
    if (file.startsWith(path.join(ROOT, 'miniapp-user') + path.sep)
      || file.startsWith(path.join(ROOT, 'miniapp-delivery') + path.sep)) delete require.cache[file]
  }
  global.__wxConfig = { envVersion: 'develop' }
  const wx = createWx(), calls = []
  wx.stopPullDownRefresh = () => { wx.__pullStops = (wx.__pullStops || 0) + 1 }
  const app = createApp({ _loginGeneration: 1, globalData: {
    isLogin: true, customerId: 7, accessToken: 'synthetic-access-A', refreshToken: 'synthetic-refresh-A',
    userInfo: side === 'staff' ? { staffId: 11, role: 'STATION_MANAGER', stationId: 11 } : { customerId: 7 }
  } })
  wx.setStorageSync('selectedStation', { id: 11, name: 'Synthetic station' })
  wx.request = call => { calls.push(call); dispatch(call, calls) }
  const page = loadPage(relPage, { wx, app }); nativeSetData(page)
  return { wx, app, page, calls }
}

function orderPage(paymentResult, queryResult, confirm = false) {
  const writes = [], payments = [], reads = []
  const env = environment('customer', 'miniapp-user/pages/order/create.js', call => {
    const method = call.method || 'GET'
    if (method === 'POST' && call.data && Array.isArray(call.data.items)) {
      writes.push(copy(call.data)); success(call, { orderId: 777, warnings: [], needConfirm: false })
    } else if (method === 'POST' && call.data && call.data.orderId) {
      payments.push(copy(call.data)); paymentResult(call, payments.length)
    } else if (method === 'GET' && /\/orders\/777(?:\?|$)/.test(call.url)) {
      reads.push(777); queryResult(call, reads.length)
    } else success(call, {})
  })
  env.wx.__modalAutoConfirm = confirm
  env.page.setData({ stationId: 11, address: { id: 91 },
    products: [{ id: 5, name: 'Synthetic water', price: '10.00', deposit: '0', quantity: 2 }],
    selectedMethod: 1, wechatPay: { enabled: true }, quoteReady: true, quoteLoading: false,
    quoteError: '', blocked: false, totalAmount: 20, totalWaterCost: 20, totalDeposit: 0,
    shortageItems: [], barrelPurchases: [], note: 'synthetic original instruction' })
  return { ...env, writes, payments, reads }
}

test('A: 双超时保留原单原键，但不得把 unknown 告知为支付未成功', async () => {
  const t = orderPage(timeout, timeout); await t.page.onSubmit()
  const original = copy(t.page._originalOrderRequest.body), key = original.idempotencyKey
  await t.page.onSubmit() // Current-cart continuation must still go to the existing order, never create a second one.
  record({ modals: t.wx.__calls.modal.map(m => ({ title: m.title, content: m.content })),
    orderWrites: t.writes, paymentOrderIds: t.payments.map(p => p.orderId), detailReads: t.reads,
    pendingOrderId: t.page.data.pendingOrderId, originalKeyPreserved: t.page._originalOrderRequest.body.idempotencyKey === key,
    navigations: t.wx.__calls.nav })
  assert.strictEqual(t.writes.length, 1); assert.strictEqual(t.payments[0].orderId, 777)
  assert.strictEqual(t.reads.length, 1); assert.strictEqual(t.page.data.pendingOrderId, 777)
  assert(t.page._originalOrderRequest.body.idempotencyKey === key)
  assert(!t.wx.__calls.modal.some(m => /支付还没成功|支付没有完成/.test(m.title + m.content)), 'unknown must remain unknown in the visible message')
})
test('A: 再次支付与原单回查也超时，toast 仍须保持未知事实', async () => {
  const t = orderPage(timeout, timeout, true); await t.page.onSubmit()
  record({ toasts: t.wx.__calls.toast, orderWrites: t.writes.length,
    paymentOrderIds: t.payments.map(p => p.orderId), detailReads: t.reads })
  assert.strictEqual(t.writes.length, 1); assert.deepStrictEqual(t.payments.map(p => p.orderId), [777, 777])
  assert(!t.wx.__calls.toast.some(t => /还没付成功/.test(t.title)), 'a second unknown result cannot be presented as unpaid')
})
test('A control: 原单确认已付后不重付；未付确认后的续办始终是同单', async () => {
  const paid = orderPage(timeout, call => success(call, { id: 777, paymentStatus: 2 }))
  await paid.page.onSubmit(); assert.strictEqual(paid.payments.length, 1); assert.strictEqual(paid.page.data.pendingOrderPaid, true)
  const unpaid = orderPage(timeout, call => success(call, { id: 777, paymentStatus: 0 }), true)
  await unpaid.page.onSubmit(); assert.strictEqual(unpaid.writes.length, 1)
  assert.deepStrictEqual(unpaid.payments.map(p => p.orderId), [777, 777])
  record({ confirmedPaidPaymentCalls: paid.payments.length, confirmedUnpaidOrderWrites: unpaid.writes.length,
    confirmedUnpaidPaymentIds: unpaid.payments.map(p => p.orderId) })
})
test('A: 支付接口回应成功但原单回查超时，也只能告知结果未知', async () => {
  const t = orderPage(call => success(call, { status: 1 }), timeout)
  await t.page.onSubmit()
  const modal = t.wx.__calls.modal.find(m => m.title.includes('支付结果未知'))
  assert(modal && modal.content.includes('订单号 777') && modal.cancelText === '查原单')
  assert.strictEqual(t.writes.length, 1); assert.strictEqual(t.payments.length, 1)
  assert.strictEqual(t.page.data.pendingOrderPaid, false)
})

function customers() {
  const pending = []
  const t = environment('staff', 'miniapp-delivery/pages/station-mgmt/customers/index.js', call => {
    if (/\/customers(?:\?|$)/.test(call.url)) pending.push(call)
    else success(call, /\/enterprise\/manager\/config(?:\?|$)/.test(call.url) ? { enabled: false } : [])
  })
  return { ...t, pending }
}
const customer = (id, name) => ({ id, name, customerType: 1 })
test('B: 搜索 B 先返回，搜索 A 的迟到响应不能覆盖 B 名单', async () => {
  const t = customers(); t.page.onKeywordInput({ detail: { value: 'synthetic A' } }); const a = t.page.loadData()
  t.page.onKeywordInput({ detail: { value: 'synthetic B' } }); const b = t.page.loadData()
  success(t.pending[1], [customer(2, 'Synthetic B')]); await b
  success(t.pending[0], [customer(1, 'Synthetic A')]); await a
  record({ requestedUrls: t.pending.map(c => new URL(c.url).pathname + new URL(c.url).search),
    currentKeyword: t.page.data.keyword, shownIds: t.page.data.list.map(c => c.id), loading: t.page.data.loading })
  assert.deepStrictEqual(t.page.data.list.map(c => c.id), [2], 'current query must retain its own result')
})
test('B: 新搜索失败不能把旧名单重新展示为当前搜索结果', async () => {
  const t = customers(); t.page.setData({ keyword: 'synthetic A' }); const a = t.page.loadData()
  success(t.pending[0], [customer(1, 'Synthetic A')]); await a
  t.page.onKeywordInput({ detail: { value: 'synthetic B' } }); const b = t.page.loadData(); timeout(t.pending[1]); await b
  const root = parseWxml(fs.readFileSync(path.join(ROOT, 'miniapp-delivery/pages/station-mgmt/customers/index.wxml'), 'utf8'))
  const shown = renderElements(root, t.page.data, { includeText: true }).filter(e => e.className === 'customer-name').map(e => e.text)
  record({ currentKeyword: t.page.data.keyword, shownIds: t.page.data.list.map(c => c.id), visibleNames: shown,
    loading: t.page.data.loading, toasts: t.wx.__calls.toast })
  assert(!shown.includes('Synthetic A'), 'old data must be cleared or explicitly presented as a prior query, not current results')
})
test('B: 换登录周期后旧失败不能结束新查询的 loading 或给新页面提示', async () => {
  const t = customers(); const a = t.page.loadData()
  t.app._loginGeneration++; t.app.globalData.userInfo = { staffId: 22, role: 'STATION_MANAGER', stationId: 22 }
  t.app.globalData.accessToken = 'synthetic-access-B'; t.page.setData({ keyword: 'synthetic B' }); const b = t.page.loadData()
  success(t.pending[0], [customer(1, 'Synthetic A')]); await a
  record({ whileNewQueryPending: { loading: t.page.data.loading, shownIds: t.page.data.list.map(c => c.id), toasts: t.wx.__calls.toast } })
  const loading = t.page.data.loading, toastCount = t.wx.__calls.toast.length
  success(t.pending[1], [customer(2, 'Synthetic B')]); await b
  assert.strictEqual(loading, true, 'old session callback must not clear the new session loading state')
  assert.strictEqual(toastCount, 0, 'old session failure must not be shown in the new session')
})
test('B control: 真实 request 层拒绝跨会话旧名单，不能据 API 桩称旧名单泄漏', async () => {
  const t = customers(); const a = t.page.loadData()
  t.app._loginGeneration++; t.app.globalData.userInfo = { staffId: 22, role: 'STATION_MANAGER', stationId: 22 }
  success(t.pending[0], [customer(1, 'Synthetic A')]); await a
  record({ oldCustomerApplied: t.page.data.list.some(c => c.id === 1), requestLayerAlreadyProtectsIdentity: true })
  assert(!t.page.data.list.some(c => c.id === 1))
})
test('B: 请求回复已接受而页面 await 尚未应用时换周期，页面仍须拒绝旧名单', async () => {
  const t = customers(); const pending = t.page.loadData()
  success(t.pending[0], [customer(1, 'Synthetic A')]) // Transport accepts A while its own snapshot is still current.
  t.app._loginGeneration++; t.app.globalData.userInfo = { staffId: 22, role: 'STATION_MANAGER', stationId: 22 }
  t.app.globalData.accessToken = 'synthetic-access-B'
  await pending
  record({ switchedAfterTransportSuccess: true, currentGeneration: t.app._loginGeneration,
    currentStation: t.app.globalData.userInfo.stationId, appliedIds: t.page.data.list.map(c => c.id) })
  assert(!t.page.data.list.some(c => c.id === 1), 'page must recheck its original session after the await boundary')
})

const previewValue = (amount, quantity) => ({ refundAmount: String(amount), quantity, blocked: false, lots: [] })
function barrels() {
  const previews = [], writes = []
  const t = environment('customer', 'miniapp-user/pages/barrel/index.js', call => {
    if (/\/return\/preview(?:\?|$)/.test(call.url)) previews.push(call)
    else if (call.method === 'POST' && /\/barrels\/return(?:\?|$)/.test(call.url)) { writes.push(copy(call.data)); timeout(call) }
    else success(call, [])
  })
  t.page.onLoad({ stationId: 11 })
  t.page.setData({ showReturnModal: true, maxReturnQty: 5,
    returnForm: { productId: 5, quantity: 1, note: 'synthetic input', pickupMode: 'STORE', companionOrderId: null } })
  return { ...t, previews, writes }
}
async function readyPreview(t) { const p = t.page.refreshPreview(); success(t.previews[0], previewValue(50, 1)); await p }
function previewNodes() {
  const root = parseWxml(fs.readFileSync(path.join(ROOT, 'miniapp-user/pages/barrel/index.wxml'), 'utf8')); let found
  const visit = n => { if (n.attrs && n.attrs.class === 'preview-row') found = n; (n.children || []).forEach(visit) }
  visit(root); assert(found); return { tag: '#root', children: [found] }
}
test('C: 修改数量后的新试算期间清掉旧金额，实际 WXML 不能继续显示旧 ¥50', async () => {
  const t = barrels(); await readyPreview(t); t.page.onReturnQtyChange({ currentTarget: { dataset: { type: 'add' } } }); await flush()
  const shown = renderElements(previewNodes(), t.page.data, { includeText: true }).map(e => e.text).filter(Boolean)
  record({ currentQuantity: t.page.data.returnForm.quantity, previewing: t.page.data.previewing,
    retainedPreview: t.page.data.preview, actualRenderedPreview: shown, query: new URL(t.previews[1].url).search })
  assert.strictEqual(t.page.data.returnForm.quantity, 2); assert.strictEqual(t.page.data.previewing, true)
  assert(!shown.some(text => text.includes('50')), 'new quantity must not display the old refund estimate')
})
test('C: 新试算未返回时，旧金额不能作为当前申请的确认依据', async () => {
  const t = barrels(); await readyPreview(t); t.page.onReturnQtyInput({ detail: { value: '2' } }); await flush()
  await t.page.onSubmitReturn()
  record({ previewing: t.page.data.previewing, retainedAmount: t.page.data.preview && t.page.data.preview.refundAmount,
    submittedRequests: t.writes, notePreserved: t.page.data.returnForm.note, formQuantity: t.page.data.returnForm.quantity })
  assert.strictEqual(t.writes.length, 0, 'a fresh preview of this exact intent is needed before confirming')
})
test('C control: 最新数量结果先到时，旧数量迟到结果已被 sequence 护栏拒绝', async () => {
  const t = barrels(); const a = t.page.refreshPreview(); t.page.onReturnQtyInput({ detail: { value: '2' } })
  success(t.previews[1], previewValue(100, 2)); await flush(); success(t.previews[0], previewValue(50, 1)); await a
  record({ currentQuantity: t.page.data.returnForm.quantity, refundAmount: t.page.data.preview.refundAmount,
    requestQueries: t.previews.map(c => new URL(c.url).search) })
  assert.strictEqual(t.page.data.preview.refundAmount, '100')
  const product = barrels(); const first = product.page.refreshPreview()
  product.page.onSelectProduct({ currentTarget: { dataset: { id: 6 } } })
  success(product.previews[1], previewValue(75, 1)); await flush()
  success(product.previews[0], previewValue(50, 1)); await first
  record({ currentProduct: product.page.data.returnForm.productId, refundAmount: product.page.data.preview.refundAmount,
    requestQueries: product.previews.map(c => new URL(c.url).search) })
  assert.strictEqual(product.page.data.returnForm.productId, 6)
  assert.strictEqual(product.page.data.preview.refundAmount, '75')
})
test('C control: 切换资产站或隐藏页面后，原站/原页面迟到试算均不能应用', async () => {
  const switched = barrels(); const a = switched.page.refreshPreview()
  switched.page.onAssetStationChange({ detail: { id: 22, name: 'Synthetic station B' } })
  success(switched.previews[0], previewValue(50, 1)); await a; await flush()
  assert.strictEqual(switched.page.data.preview, null)
  const hidden = barrels(); const b = hidden.page.refreshPreview(); hidden.page.onHide()
  success(hidden.previews[0], previewValue(50, 1)); await b
  assert.strictEqual(hidden.page.data.preview, null)
  record({ afterStationChange: switched.page.data.preview, afterHide: hidden.page.data.preview })
})

function completion(secondResult) {
  const writes = [], reads = []
  const order = { id: 55, status: 2, paymentMethod: 3, paymentStatus: 2, needCollect: false,
    payMethodText: '水票支付', payStateText: '已付款', totalAmount: 20, firstBarrelOrder: false,
    items: [{ id: 501, productNameSnapshot: 'Synthetic water', quantity: 2, barrelItem: true, suggestedReturnQty: 2 }] }
  const t = environment('staff', 'miniapp-delivery/pages/order/complete.js', call => {
    if (/\/55\/complete(?:\?|$)/.test(call.url)) { writes.push(copy(call.data)); if (writes.length === 1) timeout(call); else secondResult(call) }
    else if (/\/orders\/55(?:\?|$)/.test(call.url)) { reads.push(55); success(call, order) }
    else success(call, {})
  })
  t.page.setData({ orderId: 55, from: 'detail' }); return { ...t, writes, reads }
}
test('D: unknown 提供可达的原单核实入口，仍保留原内容安全重试', async () => {
  const t = completion(call => call.success({ statusCode: 200, data: { code: 1, message: '该订单当前状态不可完成配送' } }))
  await t.page.loadOrder(55); t.page.setData({ noteText: 'synthetic现场', reportedFloor: '3' })
  await t.page.onConfirmComplete(); await flush(); assert.strictEqual(t.page.data.resultState, 'unknown')
  const input = copy({ items: t.page.data.items, noteText: t.page.data.noteText, reportedFloor: t.page.data.reportedFloor })
  const source = parseWxml(fs.readFileSync(path.join(ROOT, 'miniapp-delivery/pages/order/complete.wxml'), 'utf8')); let button
  const visit = n => { if (n.attrs && n.attrs.bindtap === 'onVerifyOriginalOrder') button = n; (n.children || []).forEach(visit) }; visit(source)
  assert(button, 'unknown needs a reachable original-order verification action')
  const rendered = renderElements({ tag: '#root', children: [button] }, t.page.data, { includeText: true })
  await t.page[button.attrs.bindtap](); await flush()
  assert(t.wx.__calls.nav.some(n => /\/pages\/order\/detail\?id=55/.test(n.url)), 'verification must open this original order')
  await t.page.onConfirmComplete(); await flush()
  record({ visibleButton: rendered[0].text, writes: t.writes, navigations: t.wx.__calls.nav,
    stateAfterRetry: t.page.data.resultState, unknownHint: t.page.data.unknownHint, inputPreserved: input.noteText === t.page.data.noteText })
  assert.deepStrictEqual(t.writes[0], t.writes[1], 'original unedited replay is already preserved and must remain available')
})
test('D control: 首次未办理时原内容安全续办能成功，不能人为禁止重试或清空现场', async () => {
  const t = completion(call => success(call, {})); await t.page.loadOrder(55)
  t.page.setData({ noteText: 'synthetic现场', reportedFloor: '3' }); await t.page.onConfirmComplete(); await flush()
  assert.strictEqual(t.page.data.resultState, 'unknown'); await t.page.onConfirmComplete(); await flush()
  record({ stateAfterRetry: t.page.data.resultState, writes: t.writes,
    retainedInput: { note: t.page.data.noteText, floor: t.page.data.reportedFloor } })
  assert.strictEqual(t.page.data.resultState, 'success'); assert.deepStrictEqual(t.writes[0], t.writes[1])
  assert.strictEqual(t.page.data.noteText, 'synthetic现场'); assert.strictEqual(t.page.data.reportedFloor, '3')
})

test('B: 输入、筛选、下拉和离页重入均只采用当前查询，失败可常驻重试', async () => {
  const t = customers(); const a = t.page.onShow(); await flush()
  t.page.onFilterType({ currentTarget: { dataset: { type: '2' } } }); await flush()
  success(t.pending[1], [{ ...customer(2, 'Synthetic enterprise'), customerType: 2 }]); await flush()
  success(t.pending[0], [customer(1, 'Synthetic old')]); await flush()
  assert.deepStrictEqual(t.page.data.list.map(c => c.id), [2])
  t.page.onKeywordInput({ detail: { value: 'new' } })
  assert.strictEqual(t.page.data.list.length, 0, 'edited keyword must not relabel the prior list')
  const pull = t.page.onPullDownRefresh(); await flush(); timeout(t.pending[2]); await pull; await flush()
  assert(t.page.data.loadError, 'failure must remain visible until retry')
  const root = parseWxml(fs.readFileSync(path.join(ROOT, 'miniapp-delivery/pages/station-mgmt/customers/index.wxml'), 'utf8'))
  assert(renderElements(root, t.page.data, { includeText: true }).some(e => e.attrs.bindtap === 'onSearch' && /重试/.test(e.text)))
  t.page.onSearch(); await flush(); const left = t.pending[3]
  assert.strictEqual(typeof t.page.onHide, 'function'); t.page.onHide(); t.page.onShow(); await flush()
  success(t.pending[4], [{ ...customer(4, 'Synthetic current'), customerType: 2 }]); await flush()
  success(left, [{ ...customer(3, 'Synthetic hidden'), customerType: 2 }]); await flush()
  assert.deepStrictEqual(t.page.data.list.map(c => c.id), [4]); assert.strictEqual(t.page.data.loadError, '')
  record({ currentIds: t.page.data.list.map(c => c.id), requests: t.pending.length, errorAfterRetry: t.page.data.loadError })
})
test('C: 关闭重开后的旧试算不能复活，失败期间不得提交且保留备注', async () => {
  const t = barrels(); const old = t.page.refreshPreview(); t.page.onCloseReturnModal()
  t.page.setData({ showReturnModal: true, 'returnForm.quantity': 2 }); const fresh = t.page.refreshPreview()
  success(t.previews[0], previewValue(50, 1)); await old
  assert.strictEqual(t.page.data.preview, null); assert.strictEqual(t.page.data.previewing, true)
  timeout(t.previews[1]); await fresh; await t.page.onSubmitReturn()
  assert(t.page.data.previewHint); assert.strictEqual(t.writes.length, 0)
  assert.strictEqual(t.page.data.returnForm.note, 'synthetic input')
  const valid = t.page.refreshPreview(); success(t.previews[2], previewValue(100, 2)); await valid
  await t.page.onSubmitReturn(); assert.strictEqual(t.writes.length, 1)
  assert.strictEqual(t.writes[0].quantity, 2); assert.strictEqual(t.writes[0].stationId, 11)
  assert(!Object.hasOwn(t.writes[0], 'refundAmount'), 'backend continues to recalculate the amount')
  record({ writes: t.writes, note: t.page.data.returnForm.note })
})
test('F: 原退桶申请在待交接和登记退款后持续展示应退额，不声称实际到账', async () => {
  const t = barrels(); t.page.setData({ loading: false, showReturnModal: false, summary: {} })
  const root = parseWxml(fs.readFileSync(path.join(ROOT, 'miniapp-user/pages/barrel/index.wxml'), 'utf8'))
  for (const state of [ { status: 1, statusText: '待交接', depositRefund: '50.00' },
    { status: 3, statusText: '已退款', depositRefund: '0.00', refundPaidTime: '2026-10-09 10:00' } ]) {
    t.page.setData({ records: [{ id: 71, type: 2, typeText: '退桶申请', quantity: 1, ...state }] })
    const visible = renderElements(root, t.page.data, { includeText: true }).map(e => e.text).join('\n')
    record({ state, visible })
    assert(visible.includes('应退押金') && visible.includes(state.depositRefund), 'the original application must show its own refund amount, including zero')
    if (state.refundPaidTime) assert(visible.includes('水站登记退款交付时间') && visible.includes('如实际未收到'))
    assert(!/已经到账|实际已到账/.test(visible))
  }
})
test('G: 当前报价站在已有资产、非桶商品、首次提示收起时都可见；迟到旧站不能覆盖', async () => {
  const pending = []
  const t = environment('customer', 'miniapp-user/pages/order/create.js', call => { pending.push(call) })
  t.page.setData({ loading: false, stationId: 11, products: [{ id: 5, category: 2, quantity: 1 }], selectedMethod: 1 })
  const quote = name => ({ waterAmount: 10, totalAmount: 10, stationName: name,
    methods: [{ id: 1, name: '微信支付', enabled: true }], defaultMethod: 1, firstStationAsset: false })
  const a = t.page.refreshQuote(); t.page.setData({ stationId: 22 }); const b = t.page.refreshQuote()
  success(pending[1], quote('Synthetic quoted station B')); await b
  success(pending[0], quote('Synthetic old station A')); await a
  const root = parseWxml(fs.readFileSync(path.join(ROOT, 'miniapp-user/pages/order/create.wxml'), 'utf8'))
  for (const firstStationAsset of [false, true]) {
    t.page.setData({ firstStationAsset, assetBarExpanded: false })
    const visible = renderElements(root, t.page.data, { includeText: true }).map(e => e.text).join('\n')
    assert(visible.includes('本次下单水站') && visible.includes('Synthetic quoted station B'))
    assert(!visible.includes('Synthetic old station A'))
  }
  const c = t.page.refreshQuote(); success(pending[2], quote('')); await c
  const visible = renderElements(root, t.page.data, { includeText: true }).map(e => e.text).join('\n')
  assert(visible.includes('水站 #22'), 'missing quote station name falls back to its current request station number')
  record({ requestStations: pending.map(c => c.data.stationId), currentStationId: t.page.data.stationId, visible })
})
test('G: 再来一单采用原单履约站，展示与报价请求一致，不改首页选择站', async () => {
  const quotes = []
  const t = environment('customer', 'miniapp-user/pages/order/create.js', call => {
    if (/\/payments\/quote(?:\?|$)/.test(call.url)) quotes.push(call)
    else if (/\/products\/5(?:\?|$)/.test(call.url)) success(call, { id: 5, name: 'Synthetic bottled water', category: 2, price: 10, deposit: 0 })
    else success(call, [])
  })
  await t.page._doLoadFromOrder({ id: 88, stationId: 11, deliveryStationId: 22, items: [{ productId: 5, quantity: 1 }] })
  await flush(); assert.strictEqual(quotes.length, 1); assert.strictEqual(quotes[0].data.stationId, 22)
  success(quotes[0], { waterAmount: 10, totalAmount: 10, stationName: 'Synthetic original fulfillment station',
    methods: [{ id: 1, name: '微信支付', enabled: true }], defaultMethod: 1, firstStationAsset: false })
  await flush()
  const root = parseWxml(fs.readFileSync(path.join(ROOT, 'miniapp-user/pages/order/create.wxml'), 'utf8'))
  const visible = renderElements(root, t.page.data, { includeText: true }).map(e => e.text).join('\n')
  assert(visible.includes('本次下单水站') && visible.includes('Synthetic original fulfillment station'))
  assert.strictEqual(t.wx.getStorageSync('selectedStation').id, 11)
  record({ quoteStation: quotes[0].data.stationId, originalHomeStation: t.wx.getStorageSync('selectedStation').id })
})

;(async () => {
  const done = armWatchdog(30000)
  try {
    for (const t of tests) {
      currentName = t.name
      try { await t.run(); results.push({ name: t.name, passed: true }); console.log('PASS ' + t.name) }
      catch (error) { results.push({ name: t.name, passed: false, error: String(error) }); console.log('FAIL ' + t.name + ': ' + error.message) }
    }
  } finally { done() }
  const result = { baseline: '54261ccb5e7459019af1c54a419547db61d6660b', syntheticOnly: true,
    realPagesApisAndRequest: true, phase: process.env.EXPERIENCE_CANDIDATE_PHASE || 'regression', cases: results.length,
    failures: results.filter(r => !r.passed).length, results, observations }
  if (process.env.EXPERIENCE_CANDIDATE_EVIDENCE) fs.writeFileSync(process.env.EXPERIENCE_CANDIDATE_EVIDENCE, JSON.stringify(result, null, 2) + '\n')
  console.log('AQUAFLOW_EXPERIENCE_RECORDED ' + result.cases + ' ' + result.failures)
  if (!result.failures) console.log('AQUAFLOW_SUITE_OK ' + result.cases)
  process.exitCode = result.failures ? 1 : 0
})().catch(error => { console.error(error); process.exitCode = 1 })
