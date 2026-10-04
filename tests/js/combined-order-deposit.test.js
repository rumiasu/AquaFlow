'use strict'
const assert = require('assert')
const { loadPage, createWx, createApp, armWatchdog } = require('./harness')
const done = armWatchdog(), tests = []
const test = (name, run) => tests.push({ name, run })
const flush = async () => { for (let i = 0; i < 20; i++) await Promise.resolve() }
const line = { productId: 5, productName: '水', quantity: 1, unitPrice: 50, amount: 50, availableRights: 0, busyRights: 0 }
const quote = (lines = [line]) => ({ data: { independentRights: true, waterAmount: 20, extraDeposit: lines.reduce((s,l) => s+l.amount,0),
  extraDepositBuckets: lines.reduce((s,l) => s+l.quantity,0), totalAmount: 20+lines.reduce((s,l) => s+l.amount,0), blocked: false,
  barrelPurchases: lines, missingRights: lines.map(l => ({ productId: l.productId, quantity: l.quantity })),
  methods: [{ id: 1, name: '模拟微信支付', enabled: true }], defaultMethod: 1, wechatPay: { method: 1, enabled: true, simulated: true, label: '模拟微信支付' } } })
function page(scenario = {}) {
  const wx = createWx(), app = createApp(), orders = [], payments = []
  wx.__modalAutoConfirm = false
  let current = quote(scenario.lines)
  const page = loadPage('miniapp-user/pages/order/create.js', { wx, app, stubs: {
    'api/payment': { getQuote: async () => current },
    'api/order': { createOrder: async body => { orders.push(body); return { data: scenario.unknown && orders.length === 1 ? {} : { orderId: 81 } } },
      createPayment: async body => { payments.push(body); return { data: { status: 2 } } }, getOrderDetail: async () => ({ data: { paymentStatus: 2 } }) }
  } })
  page.setData({ stationId: 1, selectedMethod: 1, address: { id: 12 }, products: [{ id: 5, price: 20, deposit: 50, category: 1, quantity: 1 }], loading: false })
  return { page, wx, app, orders, payments, setQuote: x => { current = x } }
}
test('首单显示水20押金50合计70，明确确认之前零建单', async () => {
  const t = page(); await t.page.refreshQuote(); assert.equal(t.page.data.payableAmountText, '70.00')
  await t.page.onSubmit(); assert.equal(t.orders.length, 0); const modal = t.wx.__calls.modal.at(-1)
  assert(modal.content.includes('50')); assert.equal(modal.confirmText, '一起付款')
  modal.success({ confirm: true }); await flush(); assert.equal(t.orders.length, 1)
  assert.deepStrictEqual(t.orders[0].barrelPurchases, [{ productId: 5, quantity: 1, unitPrice: 50 }]); assert.equal(t.payments.length, 1)
})
test('已有容量被占用可等待释放，取消确认不建单不付款', async () => {
  const t = page({ lines: [{ ...line, busyRights: 1 }] }); await t.page.refreshQuote(); await t.page.onSubmit()
  const modal = t.wx.__calls.modal.at(-1); assert(modal.content.includes('等待释放')); assert.equal(modal.cancelText, '等待释放')
  modal.success({ confirm: false }); await flush(); assert.equal(t.orders.length, 0); assert.equal(t.payments.length, 0)
})
test('容量足够或独立买过押金后没有再次补购确认', async () => {
  const t = page({ lines: [] }); await t.page.refreshQuote(); await t.page.onSubmit(); assert.equal(t.orders.length, 1)
  assert.equal(t.page.data.payableAmountText, '20.00'); assert.deepStrictEqual(t.orders[0].barrelPurchases, [])
})
test('报价更新作废旧补购弹窗，切换会话不能用旧确认建单', async () => {
  for (const changed of ['quote', 'session']) {
    const t = page(); await t.page.refreshQuote(); await t.page.onSubmit(); const modal = t.wx.__calls.modal.at(-1)
    if (changed === 'quote') { t.setQuote(quote([{ ...line, unitPrice: 60, amount: 60 }])); await t.page.refreshQuote() }
    else t.app.globalData.isLogin = false
    modal.success({ confirm: true }); await flush(); assert.equal(t.orders.length, 0); assert.equal(t.payments.length, 0)
  }
})
test('金额丢失或畸形补购明细不能解除报价失败态', async () => {
  for (const lines of [undefined, [{ ...line, amount: '坏数据' }], [line, line], [{ ...line, productId: 99 }]]) {
    const t = page(); const response = quote(); response.data.barrelPurchases = lines; t.setQuote(response)
    await t.page.refreshQuote(); assert.equal(t.page.data.quoteReady, false); assert(t.page.data.quoteError)
    await t.page.onSubmit(); assert.equal(t.orders.length, 0)
  }
  const t=page(),response=quote();response.data.extraDeposit='坏数据';t.setQuote(response)
  await t.page.refreshQuote();assert.equal(t.page.data.quoteReady,false);await t.page.onSubmit();assert.equal(t.orders.length,0)
})
test('建单响应丢失后报价失败，仍用原补购明细和原键查询原单', async () => {
  const t = page({ unknown: true }); await t.page.refreshQuote(); await t.page.onSubmit();t.wx.__calls.modal.at(-1).success({ confirm: true });await flush()
  assert.equal(t.page.data.submitState, 'unknown'); t.setQuote({ data: { methods: [] } }); await t.page.refreshQuote()
  await t.page.onRetryUnknown();assert.equal(t.orders.length, 2);assert.deepStrictEqual(t.orders[1],t.orders[0]);assert.equal(t.payments.length,0)
})
function staff(scenario = {}) {
  const wx=createWx(), app=createApp(), sent=[];wx.__modalAutoConfirm=false
  let response=quote();response.data.methods=[{id:2,name:'现金',enabled:true}];response.data.defaultMethod=2
  const page=loadPage('miniapp-delivery/pages/station-mgmt/place-order/index.js',{wx,app,stubs:{'utils/request':{
    get:async () => ({data:[{id:12}]}),
    post:async (url,body) => {
      if (url.includes('/quote')) return response
      sent.push(JSON.parse(JSON.stringify(body)))
      return {data:scenario.unknown && sent.length===1?{}:{orderId:81}}
    }
  }}})
  page.setData({stationId:1,customer:{id:7},addressId:12,cartLines:[{productId:5,quantity:1}],selectedMethod:2})
  return {page,wx,sent,setQuote:r=>{response=r}}
}
test('站长代下单先向客户确认新增押金，确认后传逐商品快照',async()=>{
  const t=staff();await t.page.onQuote();await t.page.onSubmit();assert.equal(t.sent.length,0)
  const modal=t.wx.__calls.modal.at(-1);assert(modal.content.includes('客户确认'));assert.equal(modal.confirmText,'已确认')
  modal.success({confirm:true});await flush();assert.equal(t.sent.length,1)
  assert.deepStrictEqual(t.sent[0].barrelPurchases,[{productId:5,quantity:1,unitPrice:50}])
})
test('站长新报价作废旧确认，损坏的押金报价不解锁建单',async()=>{
  const t=staff();await t.page.onQuote();await t.page.onSubmit();const modal=t.wx.__calls.modal.at(-1)
  const broken=quote();delete broken.data.barrelPurchases;t.setQuote(broken);await t.page.onQuote()
  modal.success({confirm:true});await flush();assert.equal(t.sent.length,0);assert.equal(t.page.data.quote,null)
})
test('站长未知结果保留原内容和编号，换客户不能默默重放旧单',async()=>{
  const t=staff({unknown:true});await t.page.onQuote();await t.page.onSubmit();t.wx.__calls.modal.at(-1).success({confirm:true});await flush()
  assert.equal(t.page.data.unknownOrder,true);t.page.setData({quote:null});await t.page.onRetryOriginal()
  assert.deepStrictEqual(t.sent[1],t.sent[0]);assert.equal(t.page.data.unknownOrder,false)
  t.page.setData({customer:{id:8}});await t.page.onRetryOriginal();assert.equal(t.sent.length,2)
})
test('选定客户的地址使用该客户编号查询',async()=>{
  const t=staff();await t.page.pickCustomer({id:7});assert.deepStrictEqual(t.page.data.addresses,[{id:12}])
})
function refundPage(response, rejectFirst = false) {
  const wx=createWx(), sent=[];wx.__modalAutoConfirm=false
  wx.showActionSheet=options=>options.success({tapIndex:0})
  let current=response
  const page=loadPage('miniapp-delivery/pages/order/detail.js',{wx,app:createApp(),stubs:{
    'api/business-rules':{refundPreview:async()=>({data:current})},
    'api/delivery':{},'utils/delivery-problem':{},
    'utils/request':{put:async(url,body)=>{sent.push({url,body});if(rejectFirst&&sent.length===1)throw new Error('退款金额已变化，请重新预览并确认');return {data:null}}}
  }})
  page.setData({orderId:9,order:{independentBusinessRules:true}})
  page.loadPayments=async()=>{};page.loadOrderDetail=async()=>{}
  return {page,wx,sent,setPreview:p=>{current=p}}
}
const refundPreview=amount=>({notice:'保留已占用的押金',scopes:[{scope:'ALL_CONSUMPTION',label:'取消并退款 ¥'+amount,expectedRefundAmount:amount,confirmationRequired:true}]})
test('退款回传弹窗确认时的金额，预览对象变化不能静默换金额',async()=>{
  const response=refundPreview(20),t=refundPage(response);await t.page.chooseRefundScope(11)
  const modal=t.wx.__calls.modal.at(-1);response.scopes[0].expectedRefundAmount=70
  modal.success({confirm:true});await flush()
  assert.equal(t.sent.length,1);assert.equal(t.sent[0].body.expectedRefundAmount,20);assert.equal(t.sent[0].body.scope,'ALL_CONSUMPTION')
})
test('需要金额确认而预览缺少金额时拒绝提交',async()=>{
  const response=refundPreview(20);delete response.scopes[0].expectedRefundAmount
  const t=refundPage(response);await t.page.chooseRefundScope(11)
  for(const modal of t.wx.__calls.modal)if(modal.success)modal.success({confirm:true})
  await flush();assert.equal(t.sent.length,0);assert(t.wx.__calls.modal.some(m=>(m.content||'').includes('重新')))
})
test('金额变化拒绝后显示原因，重新预览确认才提交新金额',async()=>{
  const t=refundPage(refundPreview(20),true);await t.page.chooseRefundScope(11)
  t.wx.__calls.modal.at(-1).success({confirm:true});await flush()
  assert.equal(t.sent.length,1);assert(t.wx.__calls.modal.at(-1).content.includes('重新预览'))
  t.setPreview(refundPreview(70));await t.page.chooseRefundScope(11)
  t.wx.__calls.modal.at(-1).success({confirm:true});await flush()
  assert.equal(t.sent.length,2);assert.equal(t.sent[0].body.expectedRefundAmount,20);assert.equal(t.sent[1].body.expectedRefundAmount,70)
})
test('历史无随单押金凭据的退款继续原路径，不伪造确认金额',async()=>{
  const t=refundPage({notice:'历史原路径',scopes:[{scope:'WATER',label:'退该笔付款'}]});await t.page.chooseRefundScope(11)
  t.wx.__calls.modal.at(-1).success({confirm:true});await flush()
  assert.equal(t.sent.length,1);assert(!Object.hasOwn(t.sent[0].body,'expectedRefundAmount'))
})
;(async () => {
  let failed = 0
  for (const t of tests) { try { await t.run(); console.log('PASS ' + t.name) } catch (e) { failed++;console.error('FAIL '+t.name+'\n'+e.stack) } }
  done(); if (failed) process.exitCode = 1; else console.log('AQUAFLOW_SUITE_OK ' + tests.length)
})().catch(e => { done();console.error(e);process.exitCode = 1 })
