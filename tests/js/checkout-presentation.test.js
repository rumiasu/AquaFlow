'use strict'
const assert = require('assert')
const { loadPage, createWx, armWatchdog, ROOT } = require('./harness')
const fs = require('fs'), path = require('path')
const { parseWxml, renderElements } = require('./wxml-tree')
const findNode = (node, predicate) => predicate(node) ? node : (node.children || []).map(child => findNode(child, predicate)).find(Boolean)
const done = armWatchdog(), tests = []
const test = (name, run) => tests.push({ name, run })
const reason = '水票不能支付新增押金，请改选付款方式、或先独立办理押金；已有容量被占用时也可等待释放'
const quote = () => ({ data: {
  waterAmount: 40, extraDeposit: 100, extraDepositBuckets: 2, totalAmount: 140,
  independentRights: true, barrelPurchases: [{ productId: 5, quantity: 2, unitPrice: 50, amount: 100 }],
  methods: [{ id: 1, name: '微信支付', desc: '模拟支付（点击即成功，仅联调开启）', enabled: true },
    { id: 3, name: '水票支付', enabled: true }], defaultMethod: 3,
  blocked: true, blockReason: reason, warnings: [reason],
  ticketPay: { fullyCovered: false, hint: reason, reason: 'DEPOSIT', payableAmount: 140 }
} })
function fixture(handler = async () => quote(), spec) {
  const wx = createWx(), calls = { orders: 0, payments: 0 }
  const page = loadPage('miniapp-user/pages/order/create.js', { wx, stubs: {
    'api/product': { getProductDetail: async () => ({ data: { id: 5, name: '清泉桶装水', spec, category: 1, price: 20, deposit: 50 } }) },
    'api/payment': { getQuote: handler },
    'api/order': { createOrder: async () => { calls.orders++; return { data: { orderId: 81 } } },
      createPayment: async () => { calls.payments++; return { data: { status: 2 } } } }
  } })
  page.setData({ items: [{ productId: 5, quantity: 2 }], products: [{ id: 5, quantity: 2, category: 1, price: 20 }],
    stationId: 1, address: { id: 12 }, selectedMethod: 3, loading: false })
  return { page, wx, calls }
}
test('复购重新读取原单商品数量和地址并获取当前商品及报价，不建单或付款', async () => {
  const wx = createWx(), calls = { detail: [], products: [], addresses: 0, quotes: [], writes: 0 }
  const page = loadPage('miniapp-user/pages/order/create.js', { wx, stubs: {
    'utils/station': { resolveStationId: async () => 1 },
    'api/order': { getOrderDetail: async id => {
      calls.detail.push(id); return { code: 0, data: { id, stationId: 1, deliveryStationId: 2, addressId: 17,
        items: [{ productId: 5, quantity: 2, price: 1 }], amount: 2 } }
    }, createOrder: async () => { calls.writes++ }, createPayment: async () => { calls.writes++ } },
    'api/product': { getProductDetail: async (id, stationId) => {
      calls.products.push({ id, stationId }); return { code: 0, data: { id, name: '当前商品', category: 1, spec: '实际规格', effectivePrice: 27, stock: 0 } }
    } },
    'api/address': { getAddresses: async () => { calls.addresses++; return { code: 0, data: [{ id: 12, isDefault: 1 }, { id: 17, detail: '原单当前地址' }] } } },
    'api/barrel': { getBarrelSummaryByType: async () => ({ data: [] }), getBarrelSummary: async () => ({ data: {} }) },
    'api/station': { getStationStatus: async () => ({ data: {} }) },
    'api/payment': { getQuote: async body => {
      calls.quotes.push(body); return { code: 0, data: { waterAmount: 54, extraDeposit: 50, extraDepositBuckets: 1, totalAmount: 104,
        independentRights: true, barrelPurchases: [{ productId: 5, quantity: 1, unitPrice: 50, amount: 50 }],
        methods: [{ id: 1, name: '微信支付', enabled: true }], defaultMethod: 1, blocked: false } }
    } }
  } })
  page.setData({ stationId: 1 }); await page.loadReorder(81)
  await new Promise(resolve => setImmediate(resolve))
  assert.deepStrictEqual(calls.detail, [81]); assert.deepStrictEqual(calls.products, [{ id: 5, stationId: 2 }])
  assert.strictEqual(calls.addresses, 1); assert.strictEqual(page.data.address.id, 17)
  assert.strictEqual(page.data.products[0].quantity, 2); assert.strictEqual(page.data.products[0].price, 27)
  assert.strictEqual(page.data.products[0].stock, 0)
  assert.ok(calls.quotes.some(q => q.addressId === 17 && q.items[0].quantity === 2))
  assert.strictEqual(page.data.extraDepositAmountText, '50.00'); assert.strictEqual(page.data.payableAmountText, '104.00')
  assert.strictEqual(calls.writes, 0)
  assert.strictEqual(wx.__calls.modal.length, 1, '既有默认站与当前站不同，必须保留确认')
  assert.ok(calls.quotes.every(q => q.stationId === 2), '既有默认复购站的商品和报价一致')
})
for (const change of ['identity', 'hide', 'unload']) {
  test('复购切站确认在' + change + '后不再应用原单', async () => {
    const wx = createWx(), applied = []
    wx.showModal = options => wx.__calls.modal.push(options)
    const page = loadPage('miniapp-user/pages/order/create.js', { wx, stubs: {
      'utils/station': { resolveStationId: async () => 4 },
      'api/order': { getOrderDetail: async id => ({ code: 0, data: { id, stationId: 1, deliveryStationId: 3 } }) }
    } })
    page._doLoadFromOrder = async order => applied.push(order.id)
    await page.loadReorder(81)
    assert.strictEqual(wx.__calls.modal.length, 1, '当前站与既有默认复购站不同，保留原确认')
    if (change === 'identity') page.__app.globalData.customerId = 8
    else page[change === 'hide' ? 'onHide' : 'onUnload']()
    wx.__calls.modal[0].success({ confirm: true })
    await new Promise(resolve => setImmediate(resolve))
    assert.deepStrictEqual(applied, [])
  })
}

test('有效复购切站确认只应用原单，取消保留原有返回行为', async () => {
  for (const confirm of [false, true]) {
    const wx = createWx(), applied = []
    wx.__modalAutoConfirm = confirm
    const page = loadPage('miniapp-user/pages/order/create.js', { wx, stubs: {
      'utils/station': { resolveStationId: async () => 4 },
      'api/order': { getOrderDetail: async id => ({ code: 0, data: { id, stationId: 1, deliveryStationId: 3 } }) }
    } })
    page._doLoadFromOrder = async order => applied.push(order.id)
    await page.loadReorder(81)
    assert.deepStrictEqual(applied, confirm ? [81] : [])
    assert.strictEqual(wx.__calls.nav.filter(n => n.type === 'navigateBack').length, confirm ? 0 : 1)
  }
})

test('复购取单中进入后台再返回会重新核实，旧响应和换号均不能复用', async () => {
  for (const changed of [false, true]) {
    const wx = createWx(), reads = [], applied = []
    const page = loadPage('miniapp-user/pages/order/create.js', { wx, stubs: {
      'utils/station': { resolveStationId: async () => 1 },
      'api/order': { getOrderDetail: () => new Promise(resolve => reads.push(resolve)) }
    } })
    page.loadAddress = () => {}; page._doLoadFromOrder = async order => applied.push(order.id)
    const old = page.loadReorder(81); page.onHide()
    if (changed) page.__app.globalData.customerId = 8
    page.onShow()
    assert.strictEqual(reads.length, changed ? 1 : 2)
    reads[0]({ data: { id: 81, stationId: 1 } }); await old
    assert.deepStrictEqual(applied, [])
    if (!changed) {
      reads[1]({ data: { id: 81, stationId: 1 } })
      await new Promise(resolve => setImmediate(resolve))
      assert.deepStrictEqual(applied, [81]); assert.strictEqual(page._pendingReorder, null)
    }
  }
})

test('复购原确认离页后失效，返回的新确认可继续，弹窗失败可读且收尾', async () => {
  const wx = createWx(), applied = []
  wx.showModal = options => wx.__calls.modal.push(options)
  const page = loadPage('miniapp-user/pages/order/create.js', { wx, stubs: {
    'utils/station': { resolveStationId: async () => 3 },
    'api/order': { getOrderDetail: async id => ({ data: { id, stationId: 1 } }) }
  } })
  page.loadAddress = () => {}; page._doLoadFromOrder = async order => applied.push(order.id)
  await page.loadReorder(81); page.onHide(); page.onShow()
  await new Promise(resolve => setImmediate(resolve))
  assert.strictEqual(wx.__calls.modal.length, 2)
  wx.__calls.modal[0].success({ confirm: true }); assert.deepStrictEqual(applied, [])
  wx.__calls.modal[1].success({ confirm: true }); assert.deepStrictEqual(applied, [81])
  await page.loadReorder(82); wx.__calls.modal[2].fail()
  assert.strictEqual(page.data.loading, false); assert.ok(page.data.loadError)
  assert.strictEqual(page._pendingReorder, null)
})

test('规格为空或为占位字符串时不显示，保留原始商品字段', async () => {
  for (const spec of [null, undefined, '', ' ', 'null', ' NULL ', 'undefined']) {
    const t = fixture(undefined, spec)
    // Isolate product loading; these callbacks are covered by the quote flow suites.
    t.page.loadStationStatus = () => {}; t.page.syncBarrelSummary = () => {}; t.page.refreshQuote = () => {}
    await t.page.loadItemsProducts()
    assert.strictEqual(t.page.data.products[0].specText, '')
    assert.strictEqual(t.page.data.products[0].spec, spec)
    assert.strictEqual(t.page.data.products[0].quantity, 2)
  }
})
test('真实规格正常展示且不改变价格和数量', async () => {
  const t = fixture(undefined, ' 18.9L / 桶 ')
  t.page.loadStationStatus = () => {}; t.page.syncBarrelSummary = () => {}; t.page.refreshQuote = () => {}
  await t.page.loadItemsProducts()
  assert.strictEqual(t.page.data.products[0].specText, '18.9L / 桶')
  assert.strictEqual(t.page.data.products[0].price, 20)
  assert.strictEqual(t.page.data.products[0].subtotalText, '40.00')
})
test('同一水票限制只展示一次，阻断、换付款动作和两笔金额均保留', async () => {
  const t = fixture(); await t.page.refreshQuote()
  assert.deepStrictEqual(t.page.data.checkoutWarnings, [reason])
  assert.strictEqual(t.page.data.blocked, true); assert.strictEqual(t.page.data.blockReason, reason)
  assert.strictEqual(t.page.data.ticketShortfallHint, reason)
  assert.strictEqual(t.page.data.ticketAltMethod, 1); assert.strictEqual(t.page.data.ticketAltLabel, '改用微信支付')
  assert.strictEqual(t.page.data.ticketCanBuy, false)
  assert.strictEqual(t.page.data.totalWaterCostText, '40.00')
  assert.strictEqual(t.page.data.extraDepositAmountText, '100.00')
  assert.strictEqual(t.page.data.payableAmountText, '140.00')
  assert.deepStrictEqual(t.page.data.barrelPurchases, quote().data.barrelPurchases)
  assert.strictEqual(t.page.data.payMethods[0].desc, quote().data.methods[0].desc)
  await t.page.onSubmit(); assert.strictEqual(t.calls.orders, 0); assert.strictEqual(t.calls.payments, 0)
})
test('不同告知均展示，去买水票只在余额不足时提供', async () => {
  const t = fixture(async () => {
    const res = quote(); res.data.warnings = [reason, '本次楼层信息未填', '本次楼层信息未填']
    res.data.ticketPay.hint = '水票余额不足'; res.data.ticketPay.reason = 'INSUFFICIENT'
    return res
  })
  await t.page.refreshQuote()
  assert.deepStrictEqual(t.page.data.checkoutWarnings, [reason, '本次楼层信息未填', '水票余额不足'])
  assert.strictEqual(t.page.data.ticketCanBuy, true)
})
test('重新报价时不残留上一轮提示，失败仍阻断，恢复后清掉失效限制', async () => {
  let resolve, failing = false, pending = false
  const t = fixture(() => {
    if (pending) return new Promise(r => { resolve = r })
    if (failing) throw Error('网络断开')
    const res = quote(); res.data.blocked = false; res.data.warnings = []; res.data.ticketPay = null
    return Promise.resolve(res)
  })
  t.page.setData({ checkoutWarnings: [reason] }); pending = true
  const work = t.page.refreshQuote(); assert.deepStrictEqual(t.page.data.checkoutWarnings, [])
  resolve(quote()); await work; assert.deepStrictEqual(t.page.data.checkoutWarnings, [reason])
  pending = false; failing = true; await t.page.refreshQuote()
  assert.strictEqual(t.page.data.quoteReady, false); assert.strictEqual(t.page.data.blocked, true)
  assert(!t.page.data.checkoutWarnings.includes(reason))
  failing = false; t.page.setData({ selectedMethod: 1 }); await t.page.refreshQuote()
  assert.deepStrictEqual(t.page.data.checkoutWarnings, []); assert.strictEqual(t.page.data.blocked, false)
})
test('零售参考16与水票计账20分开，整单票结清明确用票张数及现金0', async () => {
  const requests = []
  const t = fixture(async body => {
    requests.push(body)
    return { code: 0, data: {
      waterAmount: body.paymentMethod === 3 ? 20 : 16, totalAmount: body.paymentMethod === 3 ? 20 : 16,
      extraDeposit: 0, extraDepositBuckets: 0, independentRights: true, barrelPurchases: [], blocked: false,
      methods: [{ id: 2, name: '货到付款', enabled: true }, { id: 3, name: '水票支付', enabled: true }], defaultMethod: 3,
      ticketPay: body.paymentMethod === 3 ? { fullyCovered: true, coverQty: 1, coverAmount: 20, payableAmount: 0 } : null
    } }
  })
  t.page.setData({ products: [{ id: 5, quantity: 1, category: 1, price: 16, subtotalText: '16.00' }] })
  await t.page.refreshQuote()
  assert.strictEqual(requests[0].paymentMethod, 3)
  assert.deepStrictEqual(requests[0].items, [{ productId: 5, quantity: 1 }])
  assert.strictEqual(t.page.data.products[0].price, 16)
  assert.strictEqual(t.page.data.products[0].subtotalText, '16.00')
  assert.strictEqual(t.page.data.totalWaterCostText, '20.00')
  assert.strictEqual(t.page.data.totalAmountText, '20.00')
  assert.strictEqual(t.page.data.ticketCoverText, '使用 1 张水票 · 抵扣 ¥20.00')
  assert.strictEqual(t.page.data.payableAmountLabel, '本次现金需付')
  assert.strictEqual(t.page.data.payableAmountText, '0.00')
  const markup = fs.readFileSync(path.join(ROOT, 'miniapp-user/pages/order/create.wxml'), 'utf8')
  assert(markup.includes('商品清单') && markup.includes('零售小计'))
  assert(markup.includes('水费'))
  const feeContent = findNode(parseWxml(markup), node => node.attrs && node.attrs.class === 'detail-sheet-footer')
  assert(feeContent)
  const feeView = renderElements({ children: [feeContent] }, t.page.data, { includeText: true })
  assert(feeView.some(node => node.text === '使用 1 张水票 · 抵扣 ¥20.00'))
  assert(feeView.some(node => node.text === '本次现金需付'))
  assert(feeView.some(node => node.text === '¥0.00'))
  assert.strictEqual((markup.match(/isTicketPay \? payableAmountLabel/g) || []).length, 1)
  assert.strictEqual((markup.match(/>\{\{payableAmountLabel\}\}</g) || []).length, 2)
  t.page.setData({ selectedMethod: 2 }); await t.page.refreshQuote()
  assert.strictEqual(requests[1].paymentMethod, 2)
  assert.strictEqual(t.page.data.totalAmountText, '16.00')
  assert.strictEqual(t.page.data.ticketCoverText, '')
  assert.strictEqual(t.page.data.payableAmountLabel, '本次需付')
  assert.strictEqual(t.page.data.payableAmountText, '16.00')
  const cashContent = findNode(parseWxml(markup), node => node.attrs && node.attrs.class === 'detail-sheet-content')
  assert(cashContent)
  const cashView = renderElements({ children: [cashContent] }, t.page.data, { includeText: true })
  assert(cashView.some(node => node.text === '水费')); assert(cashView.some(node => node.text === '¥16.00'))
  assert.strictEqual(t.calls.orders, 0); assert.strictEqual(t.calls.payments, 0)
})
test('票不足不得称现金0或暗示票加现金混付', async () => {
  const t = fixture(async () => ({ data: {
    waterAmount: 20, totalAmount: 20, extraDeposit: 0, extraDepositBuckets: 0,
    independentRights: true, barrelPurchases: [], blocked: false,
    methods: [{ id: 3, name: '水票支付', enabled: true }], defaultMethod: 3,
    ticketPay: { fullyCovered: false, coverQty: 0, coverAmount: 0, payableAmount: 20, reason: 'INSUFFICIENT', hint: '票不足，不能混合支付' }
  } }))
  await t.page.refreshQuote()
  assert.strictEqual(t.page.data.ticketCoverText, '')
  assert.strictEqual(t.page.data.payableAmountLabel, '本次需付')
  assert.strictEqual(t.page.data.payableAmountText, '20.00')
  assert.strictEqual(t.calls.orders, 0); assert.strictEqual(t.calls.payments, 0)
})
test('票报价失效立即清除现金0结论，网络失败仍保持未知和阻断', async () => {
  let fail = false
  const t = fixture(async () => {
    if (fail) throw new Error('synthetic offline')
    return { data: { waterAmount: 20, totalAmount: 20, extraDeposit: 0, independentRights: true, barrelPurchases: [], blocked: false,
      methods: [{ id: 3, name: '水票支付', enabled: true }], defaultMethod: 3,
      ticketPay: { fullyCovered: true, coverQty: 1, coverAmount: 20, payableAmount: 0 } } }
  })
  await t.page.refreshQuote(); assert.strictEqual(t.page.data.payableAmountLabel, '本次现金需付')
  t.page.invalidateQuote()
  assert.strictEqual(t.page.data.ticketCoverText, '')
  assert.strictEqual(t.page.data.payableAmountLabel, '本次需付')
  assert.strictEqual(t.page.data.payableAmountText, '—')
  fail = true; await t.page.refreshQuote()
  assert.strictEqual(t.page.data.quoteReady, false); assert.strictEqual(t.page.data.blocked, true)
  assert.strictEqual(t.page.data.payableAmountText, '—')
  assert.strictEqual(t.calls.orders, 0); assert.strictEqual(t.calls.payments, 0)
})
// Refund wording is projected from the real page/modal; the original action remains unchanged.
test('取消确认说明独立押金与实际到账边界，关闭弹窗不提交，确认仍只提交原单一次', async () => {
  const wx = createWx(), modals = [], calls = []
  wx.showModal = options => modals.push(options)
  const page = loadPage('miniapp-delivery/pages/coordination/index.js', { wx, stubs: {
    'api/delivery': { approveCancelRequest: async id => { calls.push(id) } }
  } })
  let refreshed = 0
  page.loadAllData = () => { refreshed++ }
  const event = { currentTarget: { dataset: { id: 91 } } }
  page.onApproveCancel(event)
  assert.match(modals[0].content, /独立押金不随取消自动退还/)
  assert.match(modals[0].content, /到账以实际退款结果为准/)
  assert.ok(!modals[0].content.includes('退还水票/押金'))
  assert.strictEqual(modals[0].confirmText, '同意取消')
  await modals[0].success({ confirm: false })
  assert.deepStrictEqual(calls, []); assert.strictEqual(refreshed, 0)
  page.onApproveCancel(event)
  await modals[1].success({ confirm: true })
  assert.deepStrictEqual(calls, [91]); assert.strictEqual(refreshed, 1)
  assert.ok(wx.__calls.toast.some(o => o.title === '已同意，订单已取消'))
})

test('下单内联和弹窗押金说明均区分未领桶、实际交接与退款，不增加客户确认', () => {
  const text = fs.readFileSync(path.join(ROOT, 'miniapp-user/pages/order/create.wxml'), 'utf8')
  const nodes = [...text.matchAll(/<text class="(asset-bar-desc|popup-desc|detail-rule-text)"[^>]*>([\s\S]*?)<\/text>(?:[^<]*<\/text>)?/g)]
  const short = nodes.filter(m => ['asset-bar-desc','popup-desc'].includes(m[1]) && m[2].includes('押金可按规则申请退还'))
  assert.strictEqual(short.length, 2)
  for (const node of short) assert.match(node[2], /未领桶无需交桶，已领桶按批准安排交回/)
  assert.strictEqual((text.match(/退还申请、实际交接和退款分别办理。/g) || []).length, 2)
  assert.ok(!text.includes('归还空桶后押金退还给你'))
  assert.ok(!text.includes('将空桶归还水站后，押金退还给你'))
})

test('底栏展示当前报价阻断原因，长原因可读完整内容且不触发资金动作', async () => {
  const longReason = reason + '。请核对本次商品与支付方式后重新报价。'
  const t = fixture(async () => { const r = quote(); r.data.blockReason = longReason; return r })
  await t.page.refreshQuote()
  assert.strictEqual(t.page.data.blockReason, longReason)
  assert.ok(t.page.data.quoteBlockSummary.startsWith('水票不能支付新增押金'))
  assert.strictEqual(t.page.data.quoteBlockDetail, longReason)
  t.page.onViewQuoteBlockReason()
  assert.strictEqual(t.wx.__calls.modal.at(-1).content, longReason)
  assert.strictEqual(t.calls.orders, 0); assert.strictEqual(t.calls.payments, 0)
})

test('底栏原因随新报价失效和恢复，成功报价不残留旧阻断', async () => {
  let blocked = true
  const t = fixture(async () => { const r = quote(); r.data.blocked = blocked; return r })
  await t.page.refreshQuote()
  t.page.invalidateQuote()
  assert.strictEqual(t.page.data.quoteBlockSummary, '正在核实最新报价，请稍候')
  assert.strictEqual(t.page.data.quoteBlockDetail, '')
  blocked = false
  await t.page.refreshQuote()
  assert.strictEqual(t.page.data.blocked, false)
  assert.strictEqual(t.page.data.quoteBlockSummary, '')
  assert.strictEqual(t.page.data.quoteBlockDetail, '')
  assert.strictEqual(t.calls.orders, 0); assert.strictEqual(t.calls.payments, 0)
})

test('底栏不透传技术错误，原拒绝事实保留；恢复原单期间不出现新报价提示', async () => {
  const raw = 'HTTP 500 /api/payment stationId=1，报价异常'
  const t = fixture(async () => { const r = quote(); r.data.blockReason = raw; return r })
  await t.page.refreshQuote()
  assert.strictEqual(t.page.data.blockReason, raw)
  assert.strictEqual(t.page.data.quoteBlockSummary, '最新金额和支付方式尚未核实，请重试报价')
  const source = fs.readFileSync(path.join(ROOT, 'miniapp-user/pages/order/create.wxml'), 'utf8')
  const find = n => (n.attrs && n.attrs.class === 'quote-block-notice' ? n : (n.children || []).map(find).find(Boolean))
  const notice = find(parseWxml(source))
  const tree = { children: [notice] }
  const base = { ...t.page.data, submitting: false, pendingOrderId: null, submitState: 'idle' }
  assert.ok(renderElements(tree, base, { includeText: true }).some(n => n.text === base.quoteBlockSummary))
  for (const state of [{ submitting: true }, { pendingOrderId: 81 }, { submitState: 'unknown' }, { blocked: false }]) {
    assert.strictEqual(renderElements(tree, { ...base, ...state }).length, 0)
  }
  assert.strictEqual(t.calls.orders, 0); assert.strictEqual(t.calls.payments, 0)
})

test('底栏新增原因按实测高度留白，旧测量回调不能覆盖当前布局', () => {
  const t = fixture(), callbacks = []
  t.wx.createSelectorQuery = () => {
    const q = { in: () => q, select: () => q, boundingClientRect: cb => { callbacks.push(cb); return q }, exec: () => {} }
    return q
  }
  t.page.setData({ blocked: true, blockReason: reason })
  t.page._syncQuoteBlockNotice()
  t.page.setData({ blockReason: '请先核实配送地址' })
  t.page._syncQuoteBlockNotice()
  callbacks[0]({ height: 300 })
  assert.strictEqual(t.page.data.orderNavigationRecoveryStyle, '')
  callbacks[1]({ height: 150 })
  assert.strictEqual(t.page.data.orderNavigationRecoveryStyle, 'padding-bottom:calc(150px + 24rpx)')
  t.page.setData({ blocked: false }); t.page._syncQuoteBlockNotice()
  assert.strictEqual(t.page.data.orderNavigationRecoveryStyle, '')
})

;(async () => {
  let failed = 0
  for (const t of tests) {
    try { await t.run(); console.log('PASS ' + t.name) }
    catch (e) { failed++; console.error('FAIL ' + t.name, e.stack) }
  }
  done()
  if (failed) process.exitCode = 1
  else console.log('AQUAFLOW_SUITE_OK ' + tests.length)
})()
