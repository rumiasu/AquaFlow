const assert = require('assert'), fs = require('fs'), path = require('path')
const { loadPage, createWx, createApp, armWatchdog, ROOT } = require('./harness')
const { parseWxml, validateConditionalSiblings, renderElements } = require('./wxml-tree')
const done = armWatchdog(30000), tests = []
const test = (name, run) => tests.push({ name, run })
const deferred = () => { let resolve, reject; const promise = new Promise((a,b) => { resolve=a; reject=b }); return { promise, resolve, reject } }
const ok = data => ({ code: 0, data })
const event = data => ({ currentTarget: { dataset: data } })
function fixture(route, stubs = {}) {
  const wx = createWx(), app = createApp()
  wx.setStorageSync('selectedStation', { id: 11, name: '原站 AquaFlow' })
  delete require.cache[require.resolve(path.join(ROOT, 'miniapp-user/utils/asset-view-station'))]
  const page = loadPage('miniapp-user/pages/' + route + '.js', { wx, app, stubs })
  return { page, wx, app }
}
function elements(route, data) {
  return renderElements(parseWxml(fs.readFileSync(path.join(ROOT, 'miniapp-user/pages/' + route + '.wxml'), 'utf8')), data, { includeText: true })
}
test('all nineteen owned route templates have balanced markup and valid conditional chains', () => {
  const excluded = new Set(['pages/home/index','pages/home/notice','pages/address/list','pages/address/edit','pages/notice/index','pages/mine/company','pages/exception/list/list'])
  const routes = require('../../miniapp-user/app.json').pages.filter(r => !excluded.has(r))
  assert.equal(routes.length, 19)
  for (const route of routes) validateConditionalSiblings(parseWxml(fs.readFileSync(path.join(ROOT, 'miniapp-user/' + route + '.wxml'), 'utf8')))
})
test('error presentation hides raw exception text and preserves the original unknown flags', () => {
  const { errorText, money } = require('../../miniapp-user/pages/order/visual')
  const error = Object.assign(Error('java.lang.Exception 数据库失败'), { code: 500, resultUnknown: true })
  assert.ok(!errorText(error).includes('Exception')); assert.equal(error.code, 500); assert.equal(error.resultUnknown, true)
  assert.equal(money(null), '待核实'); assert.equal(money(''), '待核实'); assert.equal(money(0), '0.00')
})
test('result without an order id has an explicit failure and no save-template action', () => {
  const t = fixture('order/success')
  t.page.onLoad({})
  assert.equal(t.page.data.loadState, 'failed')
  const view = elements('order/success', t.page.data)
  assert.ok(!view.some(e => e.attrs.bindtap === 'onSaveTemplate'))
  assert.ok(view.some(e => e.attrs.bindtap === 'onViewOrders'))
})
test('result loading never exposes template saving or implies payment completion', () => {
  const t = fixture('order/success')
  const view = elements('order/success', t.page.data)
  assert.ok(view.some(e => e.text && e.text.includes('正在核对订单结果')))
  assert.ok(!view.some(e => ['onSaveTemplate','onPayNow'].includes(e.attrs.bindtap)))
})
test('result mismatch and raw network exceptions remain explicit failures', async () => {
  let mode = 'mismatch'
  const t = fixture('order/success', { 'api/order': { getOrderDetail: async () => { if (mode === 'raw') throw Error('ECONNRESET'); return ok({ id: 999 }) } } })
  await t.page.loadOrderStatus(20); assert.equal(t.page.data.loadState, 'failed')
  mode = 'raw'; await t.page.loadOrderStatus(20)
  assert.equal(t.page.data.loadState, 'failed'); assert.ok(!t.page.data.loadErrorText.includes('ECONNRESET'))
})
test('unknown payment status has a warning tone and absent money is never shown as zero', () => {
  const t = fixture('order/success')
  t.page.applyOrderFacts({ id: 20, statusText: '待配送' })
  assert.equal(t.page.data.heroTone, 'warn'); assert.equal(t.page.data.amountText, '金额待核实')
  t.page.applyOrderFacts({ id: 20, statusText: '待配送', payState: 'PAID', totalAmount: 0 })
  assert.equal(t.page.data.amountText, '¥0.00')
})
test('repeated result payments issue one request and a malformed receipt remains unknown', async () => {
  const d = deferred(); let calls = 0
  const t = fixture('order/success', { 'api/order': { createPayment: () => { calls++; return d.promise } } })
  t.page.setData({ loadState: 'ok', orderId: 20, orderAmount: 50, orderPaymentMethod: 1 })
  const first = t.page.onPayNow(); await t.page.onPayNow(); assert.equal(calls, 1)
  d.resolve(ok(null)); await first
  assert.ok(t.page.data.actionError.includes('尚未确认')); assert.equal(t.wx.__calls.toast.length, 0)
})
test('template first read failure has retry and never a true-empty claim', async () => {
  const t = fixture('template/index', { 'api/template': { getTemplates: async () => { throw Error('ECONNRESET') } } })
  await t.page.onShow()
  const view = elements('template/index', t.page.data)
  assert.ok(t.page.data.loadError); assert.ok(!view.some(e => e.className === 'local-empty'))
  assert.ok(view.some(e => e.attrs.bindtap === 'onRetry'))
})
test('template save latches before resolving the station and preserves input on failure', async () => {
  const d = deferred(); let writes = 0
  const t = fixture('template/index', { 'api/template': { saveTemplate: () => { writes++; return d.promise } } })
  t.page.setData({ stationId: 11, form: { name: '用户原名 Aqua', specialNote: '放门口', addressId: 3, items: [{ productId: 5, quantity: 2 }] } })
  const input = JSON.stringify(t.page.data.form), pending = t.page.onSaveTemplate()
  await t.page.onSaveTemplate(); assert.equal(writes, 1)
  d.reject(Error('SQLSTATE UPDATE error')); await pending
  assert.equal(JSON.stringify(t.page.data.form), input); assert.ok(t.page.data.saveError.includes('输入已保留'))
  assert.ok(!t.page.data.saveError.includes('SQLSTATE'))
})
test('profile double save issues one request and a failed save preserves the exact input', async () => {
  const d = deferred(); let writes = 0
  const t = fixture('mine/edit', { 'api/auth': { updateProfile: () => { writes++; return d.promise } } })
  t.page.onLoad(); t.page.setData({ nickname: '用户 User', phone: '13800000000' })
  const pending = t.page.onSubmit(); await t.page.onSubmit(); assert.equal(writes, 1)
  d.reject(Error('Exception 原始异常')); await pending
  assert.equal(t.page.data.nickname, '用户 User'); assert.equal(t.page.data.phone, '13800000000')
  assert.ok(t.page.data.saveError.includes('输入已保留'))
})
test('reordering copies only customerNote and never internal specialNote', async () => {
  const t = fixture('order/create')
  t.page.loadAddress = () => {}; t.page.loadItemsProducts = async () => {}; t.page.loadBarrel = () => {}
  await t.page._doLoadFromOrder({ stationId: 11, specialNote: '[内部]收费处理', customerNote: '客户 original留言', items: [] })
  assert.equal(t.page.data.note, '客户 original留言')
  await t.page._doLoadFromOrder({ stationId: 11, specialNote: '[内部]收费处理', items: [] })
  assert.equal(t.page.data.note, '')
})
test('a partially unread product list cannot silently submit just the remaining items', () => {
  const t = fixture('order/create')
  t.page.setData({ productsError: '有一件商品未核实', quoteReady: true, quoteLoading: false, blocked: false,
    products: [{id:5,quantity:2}], address:{id:3} })
  t.page._hasCurrentQuote = () => true
  assert.equal(t.page._finalSubmissionAllowed(), false)
  assert.ok(t.wx.__calls.toast.at(-1).title.includes('完整商品'))
})
test('missing quote amounts do not become a zero-money quote while genuine zero remains valid', async () => {
  let data={waterAmount:20,methods:[{id:1,name:'微信支付',enabled:true}]}
  const t=fixture('order/create',{'api/payment':{getQuote:async()=>ok(data)}})
  t.page.setData({loading:false,stationId:11,address:{id:3},products:[{id:5,quantity:1}],selectedMethod:1})
  await t.page.refreshQuote();assert.equal(t.page.data.quoteReady,false);assert.ok(t.page.data.quoteError)
  assert.ok(elements('order/create',t.page.data).some(e=>e.text==='待核实'))
  data={...data,waterAmount:0,totalAmount:0,deliveryFee:0,floorFee:0};await t.page.refreshQuote()
  assert.equal(t.page.data.quoteReady,true);assert.equal(t.page.data.payableAmountText,'0.00');assert.equal(t.page.data.feeBreakdownReady,true)
})
test('an incomplete covered-ticket receipt cannot invent a zero payment or undefined ticket count', async () => {
  const t=fixture('order/create',{'api/payment':{getQuote:async()=>ok({waterAmount:20,totalAmount:20,methods:[{id:3,name:'水票支付',enabled:true}],ticketPay:{fullyCovered:true}})}})
  t.page.setData({loading:false,stationId:11,address:{id:3},products:[{id:5,quantity:1}],selectedMethod:3})
  await t.page.refreshQuote();assert.equal(t.page.data.quoteReady,false);assert.equal(t.page.data.ticketCoverText,'');assert.ok(t.page.data.quoteError.includes('尚未核实'))
})
test('shop search distinguishes no match from an empty station catalog', async () => {
  const t = fixture('shop/index')
  t.page.setData({ loading: false, currentStationId: 11, allProducts: [{id:5,name:'Aqua 商品',brand:'原厂'},{id:6,name:'矿泉水'}] })
  t.page.onSearch({ detail: { value: '不存在' } }); assert.equal(t.page.data.visibleProducts.length, 0)
  assert.ok(elements('shop/index', t.page.data).some(e => e.text && e.text.includes('匹配')))
  t.page.onClearSearch(); assert.equal(t.page.data.visibleProducts.length, 2)
})
test('phone loading and unselected station never claim the station has no telephone', async () => {
  const d = deferred()
  const t = fixture('service/index', { 'api/station': { getStationPublicPhone: () => d.promise } })
  t.page.onLoad({}); const pending = t.page.loadStation()
  assert.equal(t.page.data.phoneLoading, true); assert.equal(t.page.data.phoneLoaded, false)
  d.resolve(ok({ phone: '' })); await pending
  assert.equal(t.page.data.phoneLoaded, true); assert.equal(t.page.data.serviceInfo.phone, '')
})
test('refund-selection empty response is a genuine loaded state rather than an error', async () => {
  const t = fixture('service/index', { 'api/feedback': { getRefundOptions: async () => ok({ options: [], hasMore: false }) } })
  t.page.onLoad({}); await t.page.onShowRefundOptions()
  assert.equal(t.page.data.refundOptionsLoaded, true); assert.equal(t.page.data.refundOptionsError, ''); assert.equal(t.page.data.refundOptionsLoading, false)
})
test('guest contact keeps public phone access and never reads or submits personal feedback', async () => {
  let privateCalls=0
  const t=fixture('service/index', {'api/feedback':{getMyFeedback:async()=>{privateCalls++;return ok([])},getRefundDisputes:async()=>{privateCalls++;return ok([])},submitFeedback:async()=>{privateCalls++}},
    'api/station':{getStationPublicPhone:async()=>ok({phone:'4000000000'})}})
  t.app.globalData.isLogin=false;t.page.onLoad({});t.page.onShow();await new Promise(r=>setImmediate(r))
  t.page.setData({content:'保留草稿 Original'});await t.page.onSubmit()
  assert.equal(privateCalls,0);assert.equal(t.page.data.isLogin,false);assert.equal(t.page.data.serviceInfo.phone,'4000000000');assert.equal(t.page.data.content,'保留草稿 Original')
})
test('guest asset view clears old customer facts without presenting a successful empty account', async () => {
  let calls=0
  const t=fixture('barrel/index', {'api/barrel':{getBarrelSummary:async()=>{calls++;return ok({})}}})
  t.app.globalData.isLogin=false;t.page.onLoad({});t.page.setData({summary:{depositBalance:900}});await t.page.loadData()
  assert.equal(calls,0);assert.equal(t.page.data.summary,null);assert.equal(t.page.data.needLogin,true);assert.equal(t.page.data.summaryFailed,true)
})
test('refund-thread refresh failure retains marked original notes and disables submission', async () => {
  let fail=false
  const t=fixture('service/index', {'api/feedback':{getRefundNotes:async()=>{if(fail)throw Error('ECONNRESET');return ok({refundType:'BARREL_RETURN',refundId:7,objectText:'原退款 #7',notes:[{id:1,content:'原说明 Original'}],dispute:{statusText:'待核对',canOpen:true,actions:[]}})}}})
  t.page.onLoad({refundType:'BARREL_RETURN',refundId:'7'});await t.page.loadRefundThread();fail=true;await t.page.loadRefundThread()
  assert.equal(t.page.data.refundReady,false);assert.equal(t.page.data.refundStale,true);assert.equal(t.page.data.history[0].content,'原说明 Original');assert.ok(t.page.data.refundError.includes('上次读取'))
})
test('automatic diagnostics have readable history copy while genuine customer notes remain exact', async () => {
  const original='用户留言 Original：NaturalWater 产品名称保持原样'
  const t=fixture('service/index', {'api/feedback':{getMyFeedback:async()=>ok([{id:1,category:'错误报告',content:'【自动上报·系统错误】Exception raw /api/internal'},{id:2,category:'商品与水',content:original}])}})
  t.page.onLoad({});await t.page.loadHistory()
  assert.ok(!t.page.data.history[0].displayContent.includes('Exception'));assert.equal(t.page.data.history[1].displayContent,original);assert.equal(t.page.data.history[1].content,original)
})
test('result-page arrival with a mismatched receipt cannot clear the customer cart', async () => {
  const t=fixture('order/success',{'api/order':{getOrderDetail:async()=>ok({id:999,stationId:11})},'api/template':{getQuickOrder:async()=>ok(null)}})
  let clears=0;t.app.clearCart=()=>{clears++};t.page.onLoad({id:'20',stationId:'11'});await new Promise(r=>setImmediate(r))
  assert.equal(clears,0);assert.equal(t.page.data.loadState,'failed')
})
test('missing return-application receipt preserves original key and input rather than claiming success', async () => {
  const t=fixture('barrel/index',{'api/barrel':{requestBarrelReturn:async()=>ok(null)}})
  t.page.onLoad({});t.page._returnPreviewVersion=1
  const assets=require('../../miniapp-user/utils/asset-view-station')
  t.page._lastAssetScope=assets.beginRead(t.page)
  const context=assets.beginRead(t.page,'preview')
  t.page._previewIntent={stationId:11,productId:5,quantity:1,version:1,context}
  t.page.setData({loading:false,showReturnModal:true,previewReady:true,returnForm:{productId:5,quantity:1,note:'原输入 Original',pickupMode:'STORE'}})
  await t.page.onSubmitReturn()
  assert.ok(t.page.data.returnError.includes('尚未确认'));assert.equal(t.page.data.showReturnModal,true);assert.equal(t.page.data.returnForm.note,'原输入 Original');assert.ok(t.page.data.pendingReturn.body.idempotencyKey)
  assert.ok(!t.wx.__calls.toast.some(x=>x.icon==='success'))
})
test('ticket malformed price tiers stay failed with retry rather than a no-tier claim', async () => {
  const t = fixture('ticket/index', { 'api/ticket': { getTicketPackages: async () => ok({}) } })
  t.page.onLoad({}); t.page.setData({ showPurchase: true, buyForm: {productId:5} })
  await t.page.loadBuyPackages(5)
  assert.ok(t.page.data.packagesError); assert.equal(t.page.data.packagesLoading, false); assert.equal(t.page.data.packagesLoaded, false)
})
test('companion-order menus page through every fetched order without oversized native menus', async () => {
  const t = fixture('barrel/index'), menus = [], picks = [4, 1]
  t.wx.showActionSheet = options => { menus.push(options.itemList); options.success({tapIndex:picks.shift()}) }
  const selected = await t.page.pickCompanionOrder(Array.from({length:9},(_,i)=>({id:i+1})),()=>true)
  assert.equal(selected.id, 6); assert.ok(menus.every(rows=>rows.length <= 6)); assert.equal(menus[0][4], '下一页订单')
})
test('barrel withdrawal prompt and mutation cannot be duplicated by repeated taps', async () => {
  const d = deferred(); let writes=0
  const t=fixture('barrel/index', { 'api/barrel': { withdrawBarrelReturn: () => {writes++; return d.promise} } })
  t.page.onLoad({}); t.page.setData({records:[{id:7}]}); t.page.loadData=async()=>{}
  t.page.onReturnWithdraw(event({id:7})); t.page.onReturnWithdraw(event({id:7}))
  assert.equal(writes,1); assert.equal(t.wx.__calls.modal.length,1)
  d.resolve(ok(null)); await new Promise(resolve=>setImmediate(resolve)); assert.equal(t.page._withdrawFlight,false)
})
;(async()=>{
  let failed=0
  for(const t of tests){try{await t.run();console.log('PASS '+t.name)}catch(error){failed++;console.error('FAIL '+t.name+' '+error.stack)}}
  if(failed)process.exitCode=1;else console.log('AQUAFLOW_SUITE_OK '+tests.length)
  done()
})()
