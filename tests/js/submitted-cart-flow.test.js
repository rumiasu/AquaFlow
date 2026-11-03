const assert=require('assert')
const {loadPage,createWx,createApp}=require('./harness')
const {captureSubmittedCart,consumeSubmittedCart}=require('../../miniapp-user/utils/submitted-cart')
let passed=0
function test(name,fn){fn();passed++;console.log('PASS '+name)}
const items=[{productId:5,quantity:2}]
function env(){let cart={5:2},clears=0;const app={getCart:()=>cart,clearCart:id=>{assert.equal(id,11);clears++;cart={}}};return {app,set:v=>{cart=v},get:()=>cart,count:()=>clears}}
test('exact submitted cart is consumed once',()=>{const t=env(),p=captureSubmittedCart(t.app,11,items);assert(consumeSubmittedCart(t.app,p,()=>true));assert(!consumeSubmittedCart(t.app,p,()=>true));assert.equal(t.count(),1)})
test('editing cart while original order is in flight prevents clearing',()=>{const t=env(),p=captureSubmittedCart(t.app,11,items);t.set({5:3});assert(!consumeSubmittedCart(t.app,p,()=>true));assert.equal(t.count(),0)})
test('unrelated cart item cannot be consumed by checkout subset',()=>{const t=env();t.set({5:2,6:1});assert.equal(captureSubmittedCart(t.app,11,items),null)})
test('checkout quantity change cannot clear old quantity',()=>{const t=env();assert.equal(captureSubmittedCart(t.app,11,[{productId:5,quantity:1}]),null)})
test('invalid empty or fractional submission has no receipt',()=>{const t=env();for(const x of [[],[{productId:5,quantity:1.2}],[{productId:0,quantity:2}],[{productId:5,quantity:2},{productId:5,quantity:2}]])assert.equal(captureSubmittedCart(t.app,11,x),null)})
test('identity or navigation guard denies clearing',()=>{const t=env(),p=captureSubmittedCart(t.app,11,items);assert(!consumeSubmittedCart(t.app,p,()=>false));assert.equal(t.count(),0)})
test('guard/storage failures never turn confirmed order into failure',()=>{const t=env(),p=captureSubmittedCart(t.app,11,items);assert.equal(consumeSubmittedCart(t.app,p,()=>{throw Error('synthetic guard')}),false);t.app.clearCart=()=>{throw Error('synthetic storage')};assert.equal(consumeSubmittedCart(t.app,p,()=>true),false)})
test('home cart checkout explicitly marks its origin without changing business source',()=>{const wx=createWx(),app=createApp();const p=loadPage('miniapp-user/pages/home/index.js',{wx,app});p.setData({loading:false,catalogState:'ready',currentStationId:11,total:{count:2},address:{id:4,detail:'合成地址'}});p.invalidateHomeQuote=()=>{};p.refreshDerived=()=>{};p.collectCartItems=()=>items;p.onSubmit();const n=wx.__calls.nav.find(x=>x.url&&x.url.includes('/pages/order/create?'));assert(n);const q=new URLSearchParams(n.url.split('?')[1]);assert.equal(q.get('cartOrigin'),'1');assert.equal(q.get('source'),'3');assert.equal(q.get('stationId'),'11')})
console.log('AQUAFLOW_SUITE_OK '+passed)
