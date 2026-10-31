'use strict'
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path')
const {loadPage,createWx,createApp,armWatchdog,ROOT}=require('./harness')
const {fixture}=require('./extreme-product-save.test')
const {parseWxml,renderElements}=require('./wxml-tree')
const tests=[],test=(name,run)=>tests.push({name,run}),tick=()=>new Promise(setImmediate)
const event=(id,source)=>({currentTarget:{dataset:{id,source}}})
function customer(){
 const wx=createWx(),app=createApp({globalData:{isLogin:true,customerId:7,stationId:1}}),calls={buy:[],query:[]}
 const tiers=[{source:'CUSTOM',packageId:77,qty:10,price:'19.95',unitPrice:'2.00',selectionKey:'CUSTOM:77'},
 {source:'UNIFIED',packageId:null,qty:10,price:'17.50',unitPrice:'1.75',selectionKey:'UNIFIED:10'},
 {source:'RETAIL',packageId:null,qty:1,price:'8.00',unitPrice:'8.00',selectionKey:'RETAIL:1'}]
 const page=loadPage('miniapp-user/pages/ticket/index.js',{wx,app,stubs:{
  'utils/token':{getAccessToken:()=> 'synthetic',getCustomerId:()=>7,captureSession:()=>({loggedIn:true,customerId:7}),isCurrentSession:()=>true},
  'utils/asset-view-station':{beginRead:()=>({stationId:1}),current:()=>true},
  'api/ticket':{getTicketPackages:async()=>({code:0,data:tiers}),purchaseTicket:async body=>{calls.buy.push(JSON.parse(JSON.stringify(body)));return {code:0,data:{paymentId:91,status:1,amount:'59.85',...body}}},
   getTicketPurchaseResult:async key=>{calls.query.push(key);return {code:0,data:calls.original||null}}},
  'api/product':{},'api/barrel':{getBarrelSummaryByType:async()=>({code:0,data:[]})}
 }})
 const set=page.setData.bind(page);page.setData=function(p,cb){for(const[k,v]of Object.entries(p)){if(k.includes('.')){const parts=k.split('.');let t=this.data;for(const x of parts.slice(0,-1))t=t[x];t[parts.at(-1)]=v}else this.data[k]=v}if(cb)cb()}
 page.loadData=async()=>{};page.setData({currentStationId:1,currentStation:{id:1,name:'合成水站'},showPurchase:true,packagesLoaded:true,buyPackages:tiers,
  buyForm:{productId:5,productName:'合成商品',paymentMethod:2,quantity:1,packageId:null,unifiedQty:null,totalPrice:null}})
 return {page,wx,calls,tiers}
}

test('custom package copies preserve selected tier, exact cents and request total tickets',async()=>{
 const f=customer();f.page.onBuyPackageSelect(event(77,'CUSTOM'));f.page.onBuyQuantityInput({detail:{value:'3'}})
 assert.equal(f.page.data.buyForm.quantity,30);assert.equal(f.page.data.buyForm.totalPrice,'59.85');assert.equal(f.page.data.buyForm.packageId,77)
 await f.page.onBuySubmit();assert.equal(f.calls.buy.length,1);assert.equal(f.calls.buy[0].quantity,30);assert.equal(f.calls.buy[0].packageId,77)
 assert(!('amount'in f.calls.buy[0]));assert(!('price'in f.calls.buy[0]))
})
test('unified copies keep single-copy tier and durable original intent integrity',()=>{
 const f=customer();f.page.onBuyPackageSelect(event(10,'UNIFIED'));f.page.onBuyQtyStep({currentTarget:{dataset:{type:'add'}}})
 assert.equal(f.page.data.buyForm.unifiedQty,10);assert.equal(f.page.data.buyForm.quantity,20);assert.equal(f.page.data.buyForm.totalPrice,'35.00')
 const saved={customerId:7,body:{stationId:1,productId:5,quantity:20,paymentMethod:2,packageId:null,unifiedQty:10,idempotencyKey:'original'}}
 assert(f.page.validPurchaseIntent(saved,7));assert(!f.page.validPurchaseIntent({...saved,body:{...saved.body,quantity:21}},7))
})
test('retail compatibility is just a one-ticket selectable package with no fake package id',()=>{
 const f=customer();f.page.onBuyPackageSelect(event(1,'RETAIL'));f.page.onBuyQuantityInput({detail:{value:'2'}})
 assert.equal(f.page.data.buyForm.selectedTierKey,'RETAIL:1');assert.equal(f.page.data.buyForm.quantity,2);assert.equal(f.page.data.buyForm.totalPrice,'16.00')
 assert.equal(f.page.data.buyForm.packageId,null);assert.equal(f.page.data.buyForm.unifiedQty,null)
})
test('invalid counts keep the selected tier but block totals and new writes',async()=>{
 for(const value of ['',0,-1,'2x','1.5',501]){const f=customer();f.page.onBuyPackageSelect(event(77,'CUSTOM'));f.page.onBuyQuantityInput({detail:{value}})
  assert.equal(f.page.data.buyForm.quantity,null);assert.equal(f.page.data.buyForm.totalPrice,null);assert.equal(f.page.data.buyForm.packageId,77)
  await f.page.onBuySubmit();assert.equal(f.calls.buy.length,0)}
})
test('legacy 6000-ticket original remains queryable while new 6000-ticket purchases are rejected',async()=>{
 const f=customer(),body={stationId:1,productId:5,quantity:6000,paymentMethod:2,packageId:null,unifiedQty:null,idempotencyKey:'legacy-original'}
 const saved={customerId:7,body,productName:'旧商品',stationName:'原水站'};assert(f.page.validPurchaseIntent(saved,7))
 f.wx.setStorageSync(f.page.purchaseStorageKey(7),saved);f.calls.original={...body,paymentId:91,status:1,amount:'48000.00'}
 await f.page.onQueryPurchaseResult();assert.equal(f.calls.query.length,1);assert.equal(f.calls.buy.length,0);assert.equal(f.page.data.purchaseStorageError,'')
 const next=customer();next.page.setData({'buyForm.quantity':6000,'buyForm.totalPrice':48000,'buyForm.selectedTierKey':'RETAIL:1'});await next.page.onBuySubmit();assert.equal(next.calls.buy.length,0)
})
test('changing copies after an unknown result cannot reuse the original purchase key for different content',async()=>{
 const f=customer();f.page.onBuyPackageSelect(event(77,'CUSTOM'));f.page.onBuyQuantityInput({detail:{value:2}})
 const body={stationId:1,productId:5,quantity:10,paymentMethod:2,packageId:77,unifiedQty:null,idempotencyKey:'original-key'}
 f.wx.setStorageSync(f.page.purchaseStorageKey(7),{customerId:7,body});await f.page.onBuySubmit();assert.equal(f.calls.buy.length,0)
})
test('new product shows one untouched ten-ticket draft, and saving never auto-creates that package',async()=>{
 const f=fixture();f.page.openAdd();assert.equal(f.page.data.pkgRows.length,1);assert.equal(String(f.page.data.pkgRows[0].qty),'10');assert.equal(f.page.data.pkgRows[0].price,'')
 f.page.setData({'editForm.name':'合成商品','editForm.price':'12','editForm.deposit':'0','editForm.ticketEnabled':true});await f.page.onSaveEdit()
 assert.equal(f.calls.filter(c=>c[0]==='create').length,1);assert.equal(f.packages.length,0)
})
test('untouched default row neither deletes nor rewrites another existing tier as a new tier',async()=>{
 const f=fixture();f.openEdit();f.page.setData({'editForm.ticketEnabled':true,pkgRows:[{id:31,key:'p31',qty:'20',price:'30',onShelf:false},{key:'draft',qty:'10',price:'',defaultDraft:true,touched:false}]})
 await f.page.onSaveEdit();assert.equal(f.packages.length,1);assert.equal(f.packages[0].id,31);assert.equal(f.packages[0].status,0);assert(!f.packages.some(p=>p.delete))
})
test('one-ticket tier price mirrors old clients and an inactive tier stays inactive on unrelated saves',async()=>{
 for(const onShelf of [true,false]){const f=fixture();f.openEdit();f.page.setData({'editForm.ticketEnabled':true,pkgRows:[{id:31,key:'p31',qty:'1',price:'9.25',onShelf}]});await f.page.onSaveEdit()
  assert.equal(f.calls.find(c=>c[0]==='edit')[2].ticketPrice,onShelf?9.25:0);assert.equal(f.packages[0].id,31);assert.equal(f.packages[0].status,onShelf?1:0)}
})
test('package save rejection leaves the exact edited draft open for retry',async()=>{
 const f=fixture();f.openEdit();f.page.setData({'editForm.ticketEnabled':true,pkgRows:[{id:31,key:'p31',qty:'1',price:'9.25',onShelf:true}]});f.state.package=()=>{throw Object.assign(Error('合成保存失败'),{businessRejected:true})}
 await f.page.onSaveEdit();assert.equal(f.page.data.showEdit,true);assert.equal(f.page.data.pkgRows[0].price,'9.25');assert.equal(f.page.data.saving,false)
})
test('legacy retail is exposed as a stable one-ticket draft only on reads, with no writes',async()=>{
 const wx=createWx(),app=createApp({globalData:{isLogin:true,userInfo:{staffId:1,stationId:1,role:'STATION_MANAGER'}}}),writes=[]
 const p=loadPage('miniapp-delivery/pages/station-mgmt/products/index.js',{wx,app,stubs:{'utils/request':{get:async()=>({code:0,data:[]}),post:async x=>writes.push(x)},'utils/upload-recovery-page':{wrapUploadRecovery:p=>p}}})
 p.setData({editForm:{ticketPrice:'8.00'}});await p.loadPkgRows(5);assert.equal(p.data.pkgRows[0].key,'legacy-retail');assert.equal(String(p.data.pkgRows[0].qty),'1');assert.equal(p.data.pkgRows[0].price,'8.00')
 await p.loadPkgRows(5);assert.equal(p.data.pkgRows.length,1);assert.equal(writes.length,0)
})
test('customer purchase UI exposes per-copy tickets, copies, total tickets and total money, without loose-buy controls',()=>{
 const f=customer();f.page.onBuyPackageSelect(event(77,'CUSTOM'));f.page.onBuyQuantityInput({detail:{value:3}})
 const source=fs.readFileSync(path.join(ROOT,'miniapp-user/pages/ticket/index.wxml'),'utf8'),tree=parseWxml(source)
 const elements=renderElements(tree,{...f.page.data,loading:false},{includeText:true});assert(elements.some(n=>n.text==='购买份数'));assert(elements.some(n=>n.text==='共 30 张'));assert(elements.some(n=>n.text==='合计 ¥59.85'))
 assert(!elements.some(n=>n.attrs.bindtap==='onBuyPackageClear'))
})
test('money overflow is explained while retaining the selected package and original input',async()=>{
 const f=customer();f.tiers[0].price='99999999.99';f.page.onBuyPackageSelect(event(77,'CUSTOM'));f.page.onBuyQuantityInput({detail:{value:'2'}})
 assert(f.page.data.purchaseCopiesError.includes('金额'));assert.equal(f.page.data.buyForm.copies,'2');assert.equal(f.page.data.buyForm.packageId,77)
 assert.equal(f.page.data.buyForm.totalPrice,null);await f.page.onBuySubmit();assert.equal(f.calls.buy.length,0)
})
;(async()=>{const done=armWatchdog(45000);let failed=0;try{for(const t of tests){try{await t.run();console.log('PASS '+t.name)}catch(e){failed++;console.error('FAIL '+t.name+': '+e.stack)}}if(failed)process.exitCode=1;else console.log('AQUAFLOW_SUITE_OK '+tests.length)}finally{done()}})()
