'use strict'
const assert=require('assert'),fs=require('fs'),path=require('path')
const {loadPage,createApp,createWx,ROOT,armWatchdog}=require('./harness')
const done=armWatchdog(),read=p=>fs.readFileSync(path.join(ROOT,p),'utf8'),tests=[]
const test=(name,run)=>tests.push({name,run})
const tick=()=>new Promise(resolve=>setImmediate(resolve))
const {parseWxml,renderElements}=require('./wxml-tree')
const collection=require('../../miniapp-delivery/utils/collection-confirmation')
const owner=()=>createApp({globalData:{isLogin:true,userInfo:{staffId:8,stationId:1,role:'STATION_MANAGER'}}})
test('cash collection confirms only server service remainder, not prepaid water',()=>{
 const order={id:3,status:2,stationId:1,paymentMethod:3,ticketFeePaymentMethod:2,paymentStatus:1,needCollect:true,totalAmount:43,collectAmount:7}
 assert(collection.collectionPrompt(order).includes('¥7.00'));assert(!collection.collectionPrompt(order).includes('¥43.00'))
 assert(collection.canConfirmCash(order,{role:'STATION_MANAGER',stationId:1}));assert(!collection.canConfirmCash({...order,ticketFeePaymentMethod:1},{role:'STATION_MANAGER',stationId:1}))
})
test('fee channel is part of immutable order intent, with original request retained',()=>{
 const page=loadPage('miniapp-user/pages/order/create.js',{wx:createWx(),app:createApp({globalData:{isLogin:true,customerId:7}})})
 const intent=require('../../miniapp-user/utils/orderIntent'),base={customerId:7,stationId:1,addressId:2,paymentMethod:3,items:[{productId:9,quantity:2}]}
 assert.notEqual(intent.fingerprint({...base,ticketFeePaymentMethod:1}),intent.fingerprint({...base,ticketFeePaymentMethod:2}))
 page._originalOrderRequest={orderId:11,body:{paymentMethod:3,ticketFeePaymentMethod:1,ticketFeeQuoteAmount:7},wechatPay:{enabled:true},paymentContext:{amount:7}}
 page.setData({selectedMethod:2,ticketFeePaymentMethod:2,stationId:1});const proof=page._captureOrderNavigation(11)
 assert.equal(proof.paymentMethod,3);assert.equal(proof.ticketFeePaymentMethod,1);assert.equal(proof.ticketFeeQuoteAmount,7)
})
test('customer quote exposes one-off delivery credit and separate actual fee methods',()=>{
 const page=loadPage('miniapp-user/pages/order/create.js',{wx:createWx(),app:createApp()})
 const rows=renderElements(parseWxml(read('miniapp-user/pages/order/create.wxml')),{...page.data,loading:false,isTicketPay:true,ticketPay:{fullyCovered:true,hint:'水票仅抵水费；楼层费另收'},ticketDeliveryCredit:3,ticketDeliveryCreditText:'3.00',ticketFeeAmount:4,ticketFeePaymentMethod:2,ticketFeeMethods:[{id:1,name:'当前线上付款',enabled:true},{id:2,name:'货到付款',enabled:true}]},{includeText:true})
 assert(rows.some(n=>n.text.includes('楼层费另收')));assert.equal(rows.filter(n=>n.attrs.bindtap==='onPickTicketFeeMethod').length,2)
 page.setData({submitting:false,submitState:'unknown',ticketFeeMethods:[{id:1,enabled:true}],ticketFeePaymentMethod:2});page.refreshQuote=()=>{throw Error('must retain unknown request')};page.onPickTicketFeeMethod({currentTarget:{dataset:{id:1}}});assert.equal(page.data.ticketFeePaymentMethod,2)
})
test('read-only water refund permits zero cash and sends exact confirmed ticket quantity',async()=>{
 const dialogs=[],app=owner(),wx=createWx();wx.showActionSheet=o=>o.success({tapIndex:0})
 const page=loadPage('miniapp-delivery/pages/order/detail.js',{wx,app,stubs:{'api/business-rules':{refundPreview:async()=>({data:{notice:'回补水票，不折现金',scopes:[{scope:'WATER',label:'退2张水票',expectedRefundAmount:0,expectedTicketQty:2,confirmationRequired:true}]}})},'utils/business-dialog':{showBusinessModal:o=>dialogs.push(o)}}})
 let sent;page.doRefundPayment=(...args)=>{sent=args};await page.chooseRefundScope(99);assert.equal(dialogs.length,1);dialogs[0].success({confirm:true});assert.deepEqual(sent,[99,'WATER',{expectedRefundAmount:0,expectedTicketQty:2}])
})
test('delivery-offer load failure stays unknown and late responses after identity change stay hidden',async()=>{
 const app=owner(),wx=createWx();let resolve
 const page=loadPage('miniapp-delivery/pages/station-mgmt/ticket-packages/index.js',{wx,app,stubs:{'utils/request':{get:()=>new Promise(r=>{resolve=r})}}})
 const pending=page.loadDeliveryOffers();page.onHide();app.globalData.userInfo={staffId:9,stationId:2,role:'STATION_MANAGER'};resolve({code:0,data:[{label:'他站不能显示'}]});await pending;assert.equal(page.data.deliveryOffers.length,0)
 page._hidden=false;let rejected=false;const modulePage=loadPage('miniapp-delivery/pages/station-mgmt/ticket-packages/index.js',{wx:createWx(),app,stubs:{'utils/request':{get:async()=>{rejected=true;throw Error('网络失败')}}}})
 await modulePage.loadDeliveryOffers();assert(rejected);assert(modulePage.data.deliveryOffersError);assert.equal(modulePage.data.deliveryOffers.length,0)
})
test('mixed online fee uses common authorized channel preflight and cannot relabel original ticket order',async()=>{
 const {fixture,localChannel}=require('./payment-sandbox-harness')
 const channel={...localChannel(),simulationAuthorization:{version:1,scope:'SERVER_TEST',customerId:7}}
 const t=fixture({version:'trial',channel}),body={orderId:42,paymentMethod:3,ticketFeePaymentMethod:1}
 await t.load('miniapp-user/api/order.js').createPayment(body);assert.deepEqual(t.reads,['/api/payments/capabilities']);assert.equal(t.posts.length,1);assert.equal(t.posts[0].data.paymentMethod,3);assert.equal(t.posts[0].data.ticketFeePaymentMethod,1)
 const denied=fixture({version:'release',channel});await assert.rejects(denied.load('miniapp-user/api/order.js').createPayment(body));assert.equal(denied.posts.length,0)
})
test('mixed COD fee never dispatches an online channel payment',async()=>{
 const {fixture}=require('./payment-sandbox-harness'),t=fixture({version:'trial'})
 await t.load('miniapp-user/api/order.js').createPayment({orderId:42,paymentMethod:3,ticketFeePaymentMethod:2});assert.equal(t.reads.length,0);assert.equal(t.posts.length,1);assert.equal(t.posts[0].data.paymentMethod,3)
})
function staffOrderFixture(options={}) {
 const wx=options.wx || createWx(),app=options.app || owner(),writes=[],dialogs=[]
 const request={get:async()=>({code:0,data:[]}),post:async(url,body)=>{writes.push(JSON.parse(JSON.stringify(body)));return options.write?options.write(url,body):{code:0,data:{orderId:99}}}}
 const page=loadPage('miniapp-delivery/pages/station-mgmt/place-order/index.js',{wx,app,stubs:{'utils/request':request,'utils/business-dialog':{showBusinessModal:o=>dialogs.push(o)}}})
 page.setData({stationId:1,customer:{id:7},addressId:8,cartLines:[{productId:9,quantity:2}],selectedMethod:3,ticketFeePaymentMethod:2,quote:{ticketFeeAmount:7,ticketDeliveryCredit:0,barrelPurchases:[]}})
 return {wx,app,page,writes,dialogs}
}
test('staff unknown order survives page recreation with exact original fee channel and idempotency key',async()=>{
 const t=staffOrderFixture({write:async()=>{throw Error('timeout')}});await t.page.doCreate(false);assert.equal(t.writes.length,1);assert(t.page.data.unknownOrder)
 const original=t.writes[0],again=staffOrderFixture({wx:t.wx,app:t.app});again.page.restoreOriginalOrder();assert(again.page.data.unknownOrder)
 again.page.setData({customer:null,addressId:null,cartLines:[],selectedMethod:1,ticketFeePaymentMethod:1,quote:null});await again.page.onRetryOriginal()
 assert.equal(again.writes.length,1);assert.deepEqual(again.writes[0],original);assert.equal(again.writes[0].ticketFeePaymentMethod,2)
})
test('staff storage failure prevents dispatch and preserves the original retry',async()=>{
 const t=staffOrderFixture();t.wx.setStorageSync=()=>{throw Error('full')};await t.page.doCreate(false);assert.equal(t.writes.length,0);assert(t.page.data.unknownOrder);assert(t.page._originalOrderRequest)
})
test('staff restart under a different station cannot reveal or replay the previous station request',async()=>{
 const t=staffOrderFixture({write:async()=>{throw Error('timeout')}});await t.page.doCreate(false)
 const next=staffOrderFixture({wx:t.wx,app:createApp({globalData:{isLogin:true,userInfo:{staffId:8,stationId:2,role:'STATION_MANAGER'}}})});next.page.setData({stationId:2});next.page.restoreOriginalOrder()
 assert(!next.page._originalOrderRequest);assert.equal(next.page.data.orderRecoverySummary,'');assert.equal(next.writes.length,0)
})
test('staff unknown replay business rejection cannot clear the original request',async()=>{
 const t=staffOrderFixture({write:async()=>{throw Error('timeout')}});await t.page.doCreate(false)
 const again=staffOrderFixture({wx:t.wx,app:t.app,write:async()=>{throw Object.assign(Error('当前客户无法下单'),{businessRejected:true})}});again.page.restoreOriginalOrder();await again.page.onRetryOriginal();assert(again.page.data.unknownOrder);assert.deepEqual(again.page._originalOrderRequest,t.writes[0])
 const restarted=staffOrderFixture({wx:t.wx,app:t.app});restarted.page.restoreOriginalOrder();assert(restarted.page.data.unknownOrder)
})
test('staff shortage confirmation retains the same request key and explicitly confirmed body',async()=>{
 const t=staffOrderFixture({write:async()=>({code:0,data:{needConfirm:true,shortages:[]}})});await t.page.doCreate(false);assert.equal(t.writes.length,1);const original=t.writes[0]
 t.dialogs[0].success({confirm:true});await tick();assert.equal(t.writes.length,2);assert.equal(t.writes[1].idempotencyKey,original.idempotencyKey);assert.equal(t.writes[1].ticketFeePaymentMethod,2);assert.equal(t.writes[1].confirmShortage,true)
})
test('corrupt staff recovery stays unknown without a fabricated empty history or a new order',async()=>{
 const t=staffOrderFixture();t.wx.setStorageSync('staffOrderRequest.v1:8:1:STATION_MANAGER',{version:1,actor:'8:1:STATION_MANAGER',body:{customerId:7}});t.page.restoreOriginalOrder();assert(t.page.data.unknownOrder);assert(t.page.data.orderRecoveryError);await t.page.onRetryOriginal();assert.equal(t.writes.length,0)
})
test('another staff page adopts the existing unknown request instead of overwriting it with a second cart',async()=>{
 const first=staffOrderFixture({write:async()=>{throw Error('timeout')}});await first.page.doCreate(false)
 const second=staffOrderFixture({wx:first.wx,app:first.app});second.page.setData({customer:{id:17},addressId:18});await second.page.doCreate(false)
 assert.equal(second.writes.length,0);assert.deepEqual(second.page._originalOrderRequest,first.writes[0]);assert(second.page.data.unknownOrder)
 await second.page.onRetryOriginal();assert.equal(second.writes.length,1);assert.deepEqual(second.writes[0],first.writes[0])
})

test('split fee detail distinguishes reference water from other-fee total, including zero',()=>{
 const tree=parseWxml(read('miniapp-user/pages/order/detail.wxml'))
 for(const cash of [0,7]){
  const view=renderElements(tree,{...loadPage('miniapp-user/pages/order/detail.js',{wx:createWx(),app:createApp()}).data,detailState:'ready',order:{id:3,status:1,ticketCashAmount:cash,ticketDeliveryCredit:0},fee:{waterText:'40.00',totalText:'47.00',hasDeposit:false,extraDepositText:'0.00',hasDeliveryFee:false,hasFloorFee:false},payments:[]},{includeText:true})
  assert(view.some(n=>n.text.includes('水费参考金额（水票抵扣）')))
  assert(view.some(n=>n.text.includes('原单其他费用合计')))
  assert(view.some(n=>n.text.includes('原单参考总额')))
 }
 const legacy=renderElements(tree,{...loadPage('miniapp-user/pages/order/detail.js',{wx:createWx(),app:createApp()}).data,detailState:'ready',order:{id:3,status:1},fee:{waterText:'40.00',totalText:'47.00',hasDeposit:false,extraDepositText:'0.00',hasDeliveryFee:false,hasFloorFee:false},payments:[]},{includeText:true})
 assert(!legacy.some(n=>n.text.includes('原单其他费用合计')))
 assert(legacy.some(n=>n.tag==='text'&&n.text==='合计'))
})

;(async()=>{for(const t of tests){await t.run();console.log('PASS '+t.name)}done();console.log('AQUAFLOW_SUITE_OK '+tests.length)})().catch(e=>{done();console.error(e);process.exitCode=1})
