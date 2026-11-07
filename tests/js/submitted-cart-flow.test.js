const assert=require('assert')
const {loadPage,createWx,createApp}=require('./harness')
const {captureSubmittedCart,consumeSubmittedCart}=require('../../miniapp-user/utils/submitted-cart')
let passed=0
function test(name,fn){fn();passed++;console.log('PASS '+name)}
const items=[{productId:5,quantity:2}]
const path=require('path'), {ROOT}=require('./harness')
function env(){
  const wx=createWx(); let app
  global.wx=wx; global.getApp=()=>app; global.__wxConfig={envVersion:'develop'}
  global.App=c=>{app=c}; const file=path.join(ROOT,'miniapp-user/app.js')
  delete require.cache[require.resolve(file)]; require(file)
  app.globalData.isLogin=true; app.globalData.customerId=7
  app.setCartQty(11,5,2)
  return {app,wx}
}
test('exact submitted cart is consumed once',()=>{const t=env(),p=captureSubmittedCart(t.app,11,items);assert(consumeSubmittedCart(t.app,p,()=>true));assert(!consumeSubmittedCart(t.app,p,()=>true));assert.deepEqual(t.app.getCart(11),{})})
test('subset and smaller checkout quantity preserve unsubmitted quantities',()=>{const t=env();t.app.setCartQty(11,6,3);const p=captureSubmittedCart(t.app,11,[{productId:5,quantity:1}]);assert(consumeSubmittedCart(t.app,p,()=>true));assert.deepEqual({...t.app.getCart(11)},{5:1,6:3})})
test('order items absent from the cart do not prevent consuming proven cart items',()=>{const t=env(),p=captureSubmittedCart(t.app,11,[...items,{productId:6,quantity:1}]);assert(consumeSubmittedCart(t.app,p,()=>true));assert.deepEqual({...t.app.getCart(11)},{})})
test('unsupported Proxy keeps cart usable and declines destructive cleanup',()=>{const t=env(),saved=global.Proxy;try{global.Proxy=undefined;assert.equal(captureSubmittedCart(t.app,11,items),null);assert.equal(t.app.getCart(11)[5],2)}finally{global.Proxy=saved}})
test('changed submitted item is retained while untouched item is consumed',()=>{const t=env();t.app.setCartQty(11,6,3);const p=captureSubmittedCart(t.app,11,[...items,{productId:6,quantity:3}]);t.app.setCartQty(11,5,4);assert(consumeSubmittedCart(t.app,p,()=>true));assert.deepEqual({...t.app.getCart(11)},{5:4})})
test('change then revert through public methods still retains the edited item',()=>{const t=env(),p=captureSubmittedCart(t.app,11,items);t.app.setCartQty(11,5,3);t.app.setCartQty(11,5,2);assert(!consumeSubmittedCart(t.app,p,()=>true));assert.equal(t.app.getCart(11)[5],2)})
test('direct mutable cart change then revert also retains the edited item',()=>{const t=env(),cart=t.app.getCart(11),p=captureSubmittedCart(t.app,11,items);cart[5]=3;cart[5]=2;assert(!consumeSubmittedCart(t.app,p,()=>true));assert.equal(cart[5],2)})
test('station, identity changes and clear/re-add invalidate old receipt',()=>{for(const change of [t=>{t.app.setCartQty(12,5,7)},t=>{t.app.clearCart(11);t.app.setCartQty(11,5,2)},t=>{t.app.globalData.customerId=8;t.app.setCartQty(11,5,2)}]){const t=env(),p=captureSubmittedCart(t.app,11,items);change(t);const ok=consumeSubmittedCart(t.app,p,()=>true);if(t.app.globalData.customerId===7 && t.app.getCart(12)[5]===7)assert(ok);else assert(!ok)}})
test('invalid or excessive submissions cannot create a receipt',()=>{const t=env();for(const x of [[],[{productId:5,quantity:3}],[{productId:5,quantity:1.2}],[{productId:0,quantity:2}],[{productId:5,quantity:2},{productId:5,quantity:2}]])assert.equal(captureSubmittedCart(t.app,11,x),null)})
test('guard and storage failures never change a confirmed order result',()=>{const t=env(),p=captureSubmittedCart(t.app,11,items);assert.equal(consumeSubmittedCart(t.app,p,()=>false),false);assert.equal(consumeSubmittedCart(t.app,p,()=>{throw Error('guard')}),false);t.app.setCartQty=()=>{throw Error('storage')};assert.equal(consumeSubmittedCart(t.app,p,()=>true),false)})
test('home cart checkout explicitly marks its origin without changing business source',()=>{const wx=createWx(),app=createApp();const p=loadPage('miniapp-user/pages/home/index.js',{wx,app});p.setData({loading:false,catalogState:'ready',currentStationId:11,total:{count:2},address:{id:4,detail:'合成地址'}});p.invalidateHomeQuote=()=>{};p.refreshDerived=()=>{};p.collectCartItems=()=>items;p.onSubmit();const n=wx.__calls.nav.find(x=>x.url&&x.url.includes('/pages/order/create?'));assert(n);const q=new URLSearchParams(n.url.split('?')[1]);assert.equal(q.get('cartOrigin'),'1');assert.equal(q.get('source'),'3');assert.equal(q.get('stationId'),'11')})
test('ticket cards show cash due, including zero, or unknown without falling back to retail total',()=>{
  let config; global.Component=c=>{config=c}; const f=path.join(ROOT,'miniapp-user/components/OrderCard/index.js');delete require.cache[require.resolve(f)];require(f)
  for(const [order,known,text,label] of [
    [{paymentMethod:3,ticketCashAmount:4,totalAmount:30},true,'4.00','其他费用合计'],
    [{paymentMethod:3,ticketCashAmount:0,totalAmount:30},true,'0.00','其他费用合计'],
    [{ticketCashAmount:4,totalAmount:30},true,'4.00','其他费用合计'],
    [{paymentMethod:3,totalAmount:30},false,'','其他费用合计'],
    [{paymentMethod:2,totalAmount:30},true,'30.00','']]){
    const c={data:{},setData(v){Object.assign(this.data,v)}};config.observers.order.call(c,order)
    assert.equal(c.data.amountKnown,known);assert.equal(c.data.amountText,text);assert.equal(c.data.amountLabel,label)
  }
})
test('order result uses cash due for display without changing payment request amount',()=>{
  const p=loadPage('miniapp-user/pages/order/success.js',{wx:createWx(),app:createApp()})
  for(const [value,expected] of [[4,'¥4.00'],[0,'¥0.00'],[null,'金额待核实']]){
    p.applyOrderFacts({paymentMethod:3,ticketCashAmount:value,totalAmount:30})
    assert.equal(p.data.amountText,expected);assert.equal(p.data.amountLabel,'其他费用合计');assert.equal(p.data.orderAmount,30)
  }
})
console.log('AQUAFLOW_SUITE_OK '+passed)
