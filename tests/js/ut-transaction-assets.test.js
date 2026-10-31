'use strict'
// U-T page-owned behavior checks. API responses are synthetic; no simulator, payment or live data claims.
const assert = require('node:assert/strict')
const { loadPage, createWx, createApp, armWatchdog } = require('./harness')
const cases = [], test = (name, run) => cases.push({name, run})
const tick = () => new Promise(setImmediate)
const event = (id, value, type) => ({ currentTarget: { dataset: {id, type} }, detail: {value} })
const ok = data => ({code:0, data})
function fixture(pagePath, stubs = {}, extra = {}) {
  const wx = extra.wx || createWx(), app = extra.app || createApp(), session = {loggedIn:true, customerId:7, epoch:1}
  const token = { captureSession:()=>({...session}), isCurrentSession:s=>s.epoch===session.epoch && s.customerId===session.customerId,
    getCustomerId:()=>session.customerId, getAccessToken:()=> 'synthetic' }
  let active = true
  const scope = { beginRead:()=>({stationId:1,session:{...session}}), current:(p,c)=>active && token.isCurrentSession(c.session),
    init:()=>{}, activate:()=>{}, suspend:()=>{active=false}, select:()=>true, id:()=>1, url:(p,url)=>url+'?stationId=1' }
  const page = loadPage(pagePath,{wx,app,stubs:{'utils/token':token,'utils/asset-view-station':scope,
    // Supported-dialog fixture; the real F adapter/fallback is covered separately.
    'utils/business-dialog':{showBusinessModal:options=>wx.showModal(options)},...stubs}})
  // Match native setData's dot-path updates; the shared minimal harness intentionally uses a shallow setter.
  page.setData = function(patch, callback) { for (const [key,value] of Object.entries(patch)) { const parts=key.split('.');let target=this.data;for (const part of parts.slice(0,-1)) target=target[part];target[parts.at(-1)]=value } if(callback)callback() }
  if (extra.data) page.setData(extra.data)
  return {wx,app,page,session,token,scope,hide:()=>{active=false}}
}
test('U09 editable quantity retains blank and rejects invalid preview without guessing 1',async()=>{
  let previews=0
  const t=fixture('miniapp-user/pages/barrel/index.js',{'api/barrel':{previewBarrelReturn:async()=>{previews++;return ok({refundAmount:30})}}})
  t.page.setData({loading:false,summaryFailed:false,holdingsFailed:false,maxReturnQty:5,returnForm:{productId:5,quantity:1,note:''}})
  t.page.onReturnQtyInput(event(5,''));await tick()
  assert.equal(t.page.data.returnForm.quantity,'');assert(t.page.data.returnQuantityError);assert.equal(previews,0)
  t.page.onReturnQtyInput(event(5,'2'));await tick()
  assert.equal(t.page.data.returnForm.quantity,2);assert.equal(t.page.data.returnQuantityError,'');assert.equal(previews,1)
  t.page.onReturnQtyInput(event(5,'2x'));await tick();assert.equal(previews,1);assert.equal(t.page.data.previewReady,false)
})
test('U09 closing dirty draft is cancellable and reopens the exact same form',async()=>{
  const t=fixture('miniapp-user/pages/barrel/index.js');t.page.refreshPreview=()=>{}
  t.page._returnRecoveryScope='7:1';t.page._returnInitial='{}';t.page.syncReturnRecovery=()=>null
  const form={productId:5,quantity:2,note:'长备注，关闭后继续',pickupMode:'STORE'}
  t.page.setData({showReturnModal:true,returnForm:form,maxReturnQty:5,summary:{independentRights:true}})
  t.wx.__modalAutoConfirm=false;t.page.onCloseReturnModal();assert.equal(t.page.data.showReturnModal,true)
  t.wx.__modalAutoConfirm=true;t.page.onCloseReturnModal();assert.equal(t.page.data.showReturnModal,false)
  t.page.onShowReturnModal();assert.equal(t.page.data.showReturnModal,true);assert.deepEqual(t.page.data.returnForm,form)
})
test('U09 a close callback after session change cannot hide or save another context',()=>{
  const t=fixture('miniapp-user/pages/barrel/index.js');t.page._returnInitial='{}';t.page.setData({showReturnModal:true,returnForm:{quantity:2}})
  t.wx.showModal=o=>t.wx.__calls.modal.push(o);t.page.onCloseReturnModal();t.session.epoch++
  const modal=t.wx.__calls.modal[0];modal.success({confirm:true});modal.complete({confirm:true})
  assert.equal(t.page.data.showReturnModal,true);assert.equal(t.page._returnDraft,undefined)
})
test('U09 arrangement uses shared edit contract and settles cancellation only once',async()=>{
  const t=fixture('miniapp-user/pages/barrel/index.js');let modal
  t.wx.showModal=o=>{modal=o}
  const result=t.page.arrangementDialog({editable:true,description:'说明',initialValue:'',placeholderText:'原因'})
  assert.equal(modal.initialValue,'');assert.equal(modal.description,'说明')
  modal.success({confirm:false});modal.success({confirm:true,content:'迟到确认'})
  assert.equal(await result,null)
})
test('U13 selected product, tier and copies survive close/reopen; cancellation keeps panel open',()=>{
  const t=fixture('miniapp-user/pages/ticket/index.js');t.page.restorePurchaseIntent=()=>null;t.page.refreshOnlineChannel=()=>{}
  const form={productId:5,packageId:77,selectedTierKey:'CUSTOM:77',copies:'3',quantity:30,totalPrice:'59.85'}
  t.page.setData({showPurchase:true,currentStationId:1,packagesLoaded:true,buyForm:form})
  t.wx.__modalAutoConfirm=false;t.page.onClosePurchase();assert.equal(t.page.data.showPurchase,true)
  t.wx.__modalAutoConfirm=true;t.page.onClosePurchase();assert.equal(t.page.data.showPurchase,false);assert.deepEqual(t.page.data.buyForm,form)
  t.page.onShowPurchase();assert.deepEqual(t.page.data.buyForm,form);assert.equal(t.page.data.showPurchase,true)
})
test('U13 unknown original purchase is retained when form closes and does not create a new key',()=>{
  const t=fixture('miniapp-user/pages/ticket/index.js'),pending={body:{idempotencyKey:'original',quantity:30}}
  t.page.setData({pendingPurchase:pending,purchaseIdempotencyKey:'original',showPurchase:true,buyForm:{productId:5,quantity:30}})
  t.page.onClosePurchase();assert.deepEqual(t.page.data.pendingPurchase,pending);assert.equal(t.page.data.purchaseIdempotencyKey,'original')
  assert.equal(t.page.data.showPurchase,false);assert.equal(t.wx.__calls.modal.length,0)
})
test('U13 missing ticket movement stays unknown while known zero and both directions remain explicit',()=>{
  const t=fixture('miniapp-user/pages/ticket/index.js')
  assert.equal(t.page.presentTicketRecord({}).changeDirection,'变动待核实');assert.equal(t.page.presentTicketRecord({}).changeText,'—')
  assert.equal(t.page.presentTicketRecord({increaseQty:0,decreaseQty:0}).changeText,'0')
  assert.equal(t.page.presentTicketRecord({increaseQty:2,decreaseQty:1}).changeText,'+2 / −1')
})
test('U10 blank draft invalidates an old quote and valid input obtains a fresh server amount',async()=>{
  let calls=0
  const t=fixture('miniapp-user/pages/barrel/purchase.js',{'api/barrel':{quoteBarrelRight:async(s,p,q)=>{calls++;return ok({amount:q*30,stationName:'合成站'})}}})
  t.page.mayEdit=()=>true;t.page.setData({stationId:1,products:[{id:5}],productsLoaded:true,quote:{amount:30}})
  await t.page.onQuantity(event(5,''));assert.equal(t.page.data.quantity,'');assert.equal(t.page.data.quote,null);assert.equal(calls,0)
  await t.page.onQuantity(event(5,'2'));assert.equal(calls,1);assert.equal(t.page.data.quote.amount,60)
})
test('U04 phone error, absent phone and successful retry remain distinguishable',async()=>{
  let mode='error'
  const t=fixture('miniapp-user/pages/order/detail.js',{'api/station':{getStationPublicPhone:async()=>{if(mode==='error')throw Error('网络');return ok(mode==='empty'?{}:{phone:'01000000000'})}}})
  t.page._orderId=9;t.page._detailSeq=1;t.page._detailContext={id:9,seq:1,session:{...t.session}};t.page.setData({order:{id:9,stationId:1}})
  await t.page.loadCallableStationPhone(t.page.data.order);assert.equal(t.page.data.phoneState,'error')
  mode='empty';await t.page.onRetryPhone();assert.equal(t.page.data.phoneState,'empty');assert.equal(t.page.data.callPhone,'')
  mode='ready';await t.page.onRetryPhone();assert.equal(t.page.data.phoneState,'ready');assert.equal(t.page.data.callPhone,'01000000000')
})
test('U04 late public telephone response cannot populate a different login',async()=>{
  let release;const pending=new Promise(r=>release=r)
  const t=fixture('miniapp-user/pages/order/detail.js',{'api/station':{getStationPublicPhone:()=>pending}})
  t.page._orderId=9;t.page._detailSeq=1;t.page._detailContext={id:9,seq:1,session:{...t.session}}
  const read=t.page.loadCallableStationPhone({stationId:1});t.session.epoch++;release(ok({phone:'01000000000'}));await read
  assert.equal(t.page.data.callPhone,'')
})
test('U06 result view opens the original order detail without making a payment',()=>{
  const t=fixture('miniapp-user/pages/order/success.js');t.page.setData({orderId:99});t.page.onViewCurrentOrder()
  assert.equal(t.wx.__calls.nav[0].url,'/pages/order/detail?id=99');assert.equal(t.wx.__calls.request.length,0)
})
test('U21 signed values, zero and order navigation preserve facts and stale rows cannot navigate',async()=>{
  const t=fixture('miniapp-user/pages/payment/records.js',{'api/payment':{getPaymentsByCustomer:async()=>ok([{id:1,amount:-12.5,orderId:99},{id:2,amount:0}])}})
  await t.page.onShow();assert.equal(t.page.data.records[0].directionText,'退回');assert.equal(t.page.data.records[0].amountText,'-12.50')
  assert.equal(t.page.data.records[1].directionText,'金额为零');t.page.onViewOrder(event(1));assert.equal(t.wx.__calls.nav.length,1)
  t.page.setData({error:'刷新失败'});t.page.onViewOrder(event(1));assert.equal(t.wx.__calls.nav.length,1)
})
test('U22 missing type is explicit and zero never becomes an increase or deduction',()=>{
  const t=fixture('miniapp-user/pages/deposit/records/index.js')
  assert.equal(t.page.decorate({amount:0}).directionText,'金额未变化');assert.equal(t.page.decorate({amount:0}).typeText,'类型未提供')
  assert.equal(t.page.decorate({amount:-30}).amountText,'-30.00');assert.equal(t.page.decorate({amount:-30}).directionText,'扣减')
})
test('U01 typed cart quantity preserves invalid draft and blocks checkout, then accepts a corrected integer',()=>{
  const t=fixture('miniapp-user/pages/home/index.js'),cart={5:1}
  t.app.getCart=()=>cart;t.app.setCartQty=(s,id,q)=>{cart[id]=q};t.page.scheduleHomeQuote=()=>{}
  t.page.setData({isLogin:true,loading:false,catalogState:'ready',state:'ready',currentStationId:1,products:[{id:5,price:10}],cart})
  t.page.refreshDerived(undefined,false);t.page.onCartQuantityInput(event(5,''));assert.equal(cart[5],1);assert.equal(t.page.data.productsView[0].qtyDraft,'');assert.equal(t.page.data.quantityInputError,true)
  t.page.onSubmit();assert.equal(t.wx.__calls.nav.length,0)
  t.page.onCartQuantityInput(event(5,'3'));assert.equal(cart[5],3);assert.equal(t.page.data.quantityInputError,false)
})
test('U11 typed quantity uses the current station cart and keeps invalid edits from checkout',async()=>{
  const cart={5:1},writes=[]
  const t=fixture('miniapp-user/pages/shop/index.js',{'api/product':{getStationProducts:async()=>ok([{id:5,name:'合成商品'}])},'api/station':{getStationStatus:async()=>ok({})}})
  t.wx.setStorageSync('selectedStation',{id:1,name:'合成站'});t.app.getCurrentStationId=()=>1;t.app.getCart=()=>cart;t.app.getCartCount=()=>cart[5]||0
  t.app.setCartQty=(sid,id,q)=>{writes.push({sid,id,q});cart[id]=q};await t.page.onShow()
  t.page.onProductQuantityInput(event(5,'3'));assert.deepEqual(writes,[{sid:1,id:5,q:3}]);assert.equal(t.page.data.visibleProducts[0].quantityDraft,'3')
  t.page.onProductQuantityInput(event(5,''));t.page.onGoCheckout();assert.equal(writes.length,1);assert.equal(t.wx.__calls.nav.length,0)
  t.session.epoch++;t.page.onProductQuantityInput(event(5,'6'));assert.equal(writes.length,1)
})
test('U01 station switch confirms once; cancelled switch never saves the remember preference',async()=>{
  for(const confirm of [false,true]) {
    const t=fixture('miniapp-user/pages/home/index.js');t.wx.setStorageSync('selectedStation',{id:1});t.wx.__modalAutoConfirm=confirm
    t.page.setData({currentStationId:1,stationList:[{id:2}],rememberSwitchNotice:true});t.page.checkStation=async()=>{}
    await t.page.confirmStation(2);assert.equal(t.wx.__calls.modal.length,1)
    assert.equal(!!t.wx.getStorageSync('stationSwitchNoticeDisabled'),confirm);assert.equal(t.wx.getStorageSync('selectedStation').id,confirm?2:1)
  }
})
test('U05 enterprise application is entered explicitly and a late response after identity change cannot open it',async()=>{
  let queries=0,release
  const t=fixture('miniapp-user/pages/order/create.js',{'api/enterprise':{getMyEnterpriseApplies:()=>{queries++;return new Promise(r=>release=r)}}})
  t.page.setData({stationId:1,enterpriseHint:'可申请企业订水'});assert.equal(queries,0);assert.equal(t.wx.__calls.modal.length,0)
  const pending=t.page.onEnterpriseEntry();t.session.epoch++;release(ok([]));await pending
  assert.equal(queries,1);assert.equal(t.wx.__calls.modal.length,0)
})
test('U01 multiple unread notices share one complete dialog and cancelled reading never marks them read',async()=>{
  for(const confirm of [false,true]) {
    let marks=0
    const t=fixture('miniapp-user/pages/home/index.js',{'api/notification':{getUnreadNotifications:async()=>ok([{title:'订单提醒甲',content:'内容甲'},{title:'订单提醒乙',content:'内容乙'}]),markAllRead:async()=>{marks++}}})
    t.wx.__modalAutoConfirm=confirm;await t.page.checkNotifications()
    assert.equal(t.wx.__calls.modal.length,1);assert.match(t.wx.__calls.modal[0].content,/内容甲[\s\S]*内容乙/);assert.equal(marks,confirm?1:0)
  }
})
test('U01 late shared quantity events after login, station change or hide cannot write a cart',()=>{
  for(const change of ['login','station','hide']) {
    const t=fixture('miniapp-user/pages/home/index.js'),cart={5:1};let writes=0
    t.app.getCart=()=>cart;t.app.setCartQty=()=>{writes++};t.page.scheduleHomeQuote=()=>{}
    t.wx.setStorageSync('selectedStation',{id:1})
    t.page.setData({loading:false,catalogState:'ready',currentStationId:1,products:[{id:5,price:10}],cart})
    t.page.refreshDerived(undefined,false)
    if(change==='login')t.session.epoch++
    if(change==='station')t.wx.setStorageSync('selectedStation',{id:2})
    if(change==='hide')t.page._homeHidden=true
    t.page.onCartQuantityInput(event(5,'3'));t.page.onCartQtyChange(event(5,null,'add'));assert.equal(writes,0)
  }
})
test('U05 failed application read remains explicit and does not open a new application or disturb a valid quote',async()=>{
  let fail=true
  const t=fixture('miniapp-user/pages/order/create.js',{'api/enterprise':{getMyEnterpriseApplies:async()=>{if(fail)throw Error('网络');return ok([])}}})
  t.page.setData({stationId:1,enterpriseHint:'可申请',quoteReady:true})
  await t.page.onEnterpriseEntry();assert(t.page.data.enterpriseEntryError);assert.equal(t.wx.__calls.modal.length,0);assert.equal(t.page.data.quoteReady,true)
  fail=false;t.wx.__modalAutoConfirm=false;await t.page.onEnterpriseEntry();assert.equal(t.page.data.enterpriseEntryError,'');assert.equal(t.wx.__calls.modal.length,1)
})
test('U05 blank shared-stepper input keeps the draft and invalidates quoting until corrected',async()=>{
  const t=fixture('miniapp-user/pages/order/create.js');t.page.scheduleQuote=()=>{};t.page.syncBarrelSummary=()=>{}
  t.page.setData({products:[{id:5,quantity:2,price:10}],items:[{productId:5,quantity:2}],quoteReady:true})
  t.page.onQtyInput({currentTarget:{dataset:{index:0}},detail:{value:''}})
  assert.equal(t.page.data.products[0].quantityDraft,'');assert.equal(t.page.data.quoteReady,false);assert.equal(await t.page.refreshQuote(),false)
  t.page.onQtyInput({currentTarget:{dataset:{index:0}},detail:{value:'3'}});assert.equal(t.page.data.items[0].quantity,3);assert.equal(t.page.data.products[0].quantityError,'')
})
test('U09 UI draft survives page replacement and restores only after explicit confirmation',async()=>{
  const api={'api/barrel':{previewBarrelReturn:async()=>ok({refundAmount:60})}}
  const t=fixture('miniapp-user/pages/barrel/index.js',api)
  const data={viewStationId:1,loading:false,maxReturnQty:5,customerBarrelAsset:[{productId:5,availableRights:5}],returnProductOptions:[{id:5}],summary:{independentRights:true}}
  t.page.setData(data);t.page.onShowReturnModal();t.page.onReturnQtyInput(event(5,'2'));t.page.onReturnNoteInput(event(5,'搬家'))
  const next=fixture('miniapp-user/pages/barrel/index.js',api,{wx:t.wx});next.page.setData(data);next.page.readUiDraft()
  assert.equal(next.page.data.uiDraftAvailable,true);assert.equal(next.page.data.returnForm.productId,null)
  next.wx.__modalAutoConfirm=false;next.page.onRestoreUiDraft();assert.equal(next.page.data.returnForm.productId,null)
  next.wx.__modalAutoConfirm=true;next.page.onRestoreUiDraft();await tick();assert.equal(next.page.data.returnForm.quantity,2);assert.equal(next.page.data.returnForm.note,'搬家')
})
test('U09 failed UI draft storage preserves input and late login cannot save into another actor',()=>{
  const t=fixture('miniapp-user/pages/barrel/index.js');t.page.setData({viewStationId:1,showReturnModal:true,returnForm:{productId:5,quantity:2,note:'输入'}})
  t.page._uiDraftFormToken=t.page.uiDraftGuard().begin();t.page._uiDraftEdited=true
  t.wx.setStorageSync=()=>{throw Error('写入失败')};t.page.persistUiDraft();assert.match(t.page.data.uiDraftError,/尚未确认保存/);assert.equal(t.page.data.returnForm.note,'输入')
  t.session.customerId=8;const before=t.page.data.uiDraftError;t.page.persistUiDraft();assert.equal(t.page.data.uiDraftError,before);assert.equal(t.wx.__storage.size,0)
})
test('U13 UI draft stores selections without prices or payment identity and restores against current tiers',async()=>{
  const tiers=[{source:'CUSTOM',packageId:77,qty:10,price:'25.00',unitPrice:'2.50'}]
  const t=fixture('miniapp-user/pages/ticket/index.js',{'api/ticket':{getTicketPackages:async()=>ok(tiers)}})
  t.page.setData({viewStationId:1,currentStationId:1,showPurchase:true,buyProducts:[{id:5,name:'合成水'}],buyForm:{productId:5,selectedTierKey:'CUSTOM:77',copies:'3',totalPrice:'60.00',paymentMethod:1}})
  t.page._uiDraftFormToken=t.page.uiDraftGuard().begin();t.page._uiDraftEdited=true;t.page.persistUiDraft()
  const saved=[...t.wx.__storage.values()].find(v=>v&&v.version===1);assert(saved);assert(!JSON.stringify(saved).includes('totalPrice'));assert(!JSON.stringify(saved).includes('idempotencyKey'))
  t.page.setData({buyForm:{productId:null,quantity:1,totalPrice:null,paymentMethod:1}});t.page.refreshOnlineChannel=()=>{}
  t.page.onRestoreUiDraft();await tick();assert.equal(t.page.data.buyForm.totalPrice,'75.00');assert.equal(t.page.data.buyForm.quantity,30)
})
;(async()=>{const done=armWatchdog();let passed=0;for(const c of cases){await c.run();passed++;console.log('PASS '+c.name)}done();console.log('AQUAFLOW_SUITE_OK '+passed)})().catch(e=>{console.error(e);process.exit(1)})
