const assert=require('node:assert/strict'),fs=require('fs'),path=require('path')
const {loadPage,createApp,createWx,ROOT}=require('./harness'),{parseWxml}=require('./wxml-tree')
const tree=parseWxml(fs.readFileSync(path.join(ROOT,'miniapp-delivery/pages/station-mgmt/receivables/index.wxml'),'utf8'))
function walk(n,anc=[],out=[]){if(n.tag!=='#text')out.push({n,anc});for(const c of n.children||[])walk(c,[...anc,n],out);return out}
const elements=walk(tree), row=elements.find(x=>x.n.attrs?.bindtap==='onOpenOrder'),check=elements.find(x=>x.n.attrs?.catchtap==='onToggleOrder')
assert(row&&check&&check.anc.includes(row.n));assert(fs.readFileSync(path.join(ROOT,'miniapp-delivery/pages/station-mgmt/receivables/index.wxml'),'utf8').includes('查看详情'))
const customer={customerId:7,customerName:'test',outstandingAmount:12,overdueAmount:12,overdueOrderCount:1},rowData={orderId:88,customerId:7,totalAmount:12,status:3,paymentStatus:1,settlementStatus:1,canRegisterCash:true,canViewOrder:true}
let reads=0,posts=0,resolvePending=null
const wx=createWx(),app=createApp({_loginGeneration:1,globalData:{isLogin:true,accessToken:'test',userInfo:{staffId:1,stationId:11,role:'STATION_MANAGER'}}})
const p=loadPage('miniapp-delivery/pages/station-mgmt/receivables/index.js',{wx,app,stubs:{'utils/request':{get:async url=>{if(!url.includes('/orders?'))return {data:{customers:[customer],outstandingAmount:12}};reads++;if(resolvePending)return new Promise(r=>{resolvePending=r});return {data:[rowData]}},post:async()=>{posts++;return {data:{}}},put:async()=>({data:{}})}}})
const tick=()=>new Promise(setImmediate), event=id=>({currentTarget:{dataset:{id}}})
;(async()=>{
 p.onShow();await tick();await p.onPickCustomer(event(7));p.onToggleOrder(event(88));assert.equal(p.data.selectedCount,1);assert.equal(wx.__calls.nav.length,0)
 p.onOpenOrder(event(88));p.onOpenOrder(event(88));assert.equal(wx.__calls.nav.length,1);assert.equal(wx.__calls.nav[0].url,'/pages/order/detail?id=88');assert.equal(p.data.selectedCount,1);assert.equal(posts,0)
 p.onHide();p.onShow();assert.equal(p.data.selectedCount,0);await tick();assert.equal(p.data.orders.length,1);assert.equal(p.data.orders[0].checked,false)
 p.onOpenOrder(event(999));assert.equal(wx.__calls.nav.length,1)
 resolvePending=true;const old=p.loadOrders();await tick();assert.equal(typeof resolvePending,'function');p.onBack();resolvePending({data:[rowData]});await old;await tick();assert.equal(p.data.customer,null);assert.deepEqual(p.data.orders,[]);assert.equal(posts,0)
 console.log('AQUAFLOW_SUITE_OK 5')
})().catch(e=>{console.error(e);process.exitCode=1})
