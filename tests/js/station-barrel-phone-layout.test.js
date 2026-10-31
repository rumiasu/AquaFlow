'use strict'
const assert=require('node:assert/strict'),fs=require('fs'),path=require('path')
const {loadPage,createWx,createApp,armWatchdog,ROOT}=require('./harness')
const {parseWxml,renderElements,validateConditionalSiblings}=require('./wxml-tree')
const tests=[],test=(name,run)=>tests.push({name,run}),ok=data=>({code:0,data})
const deferred=()=>{let resolve;const promise=new Promise(r=>{resolve=r});return{promise,resolve}}
const read=rel=>fs.readFileSync(path.join(ROOT,rel),'utf8')
const holding=(extra={})=>({productId:5,productName:'普利思 饮用天然泉水',productSpec:'18.5L',independentRights:true,assetQty:3,heldTotalQty:3,inTransitQty:0,occupiedQty:2,owedQty:0,storageQty:0,availableRights:2,deposit:60,...extra})
function fixture(options={}){
 const wx=createWx(),app=createApp();wx.__storage.set('selectedStation',{id:11,name:'资产站甲'})
 app.globalData.accessToken='synthetic-token';const calls=[]
 delete require.cache[require.resolve(path.join(ROOT,'miniapp-user/utils/asset-view-station.js'))]
 const page=loadPage('miniapp-user/pages/barrel/index.js',{wx,app,stubs:{
  'api/barrel':{getBarrelSummary:async()=>ok({independentRights:true,rightBuckets:3,heldBuckets:3,availableRights:2,occupiedBuckets:2,owedBuckets:0,pendingReturns:0,pendingDeliveryBuckets:0,storageBuckets:0,depositBalance:180}),getBarrelRecords:async()=>ok([]),getBarrelSummaryByType:async()=>ok([holding(options.holding)])},
  'api/product':{getProductDetail:(id,stationId)=>{calls.push({id,stationId});return options.photo?options.photo(id,stationId):Promise.resolve(ok({id,imageUrl:'https://images.example.test/sku-'+id+'.webp'}))}}
 }})
 return {page,wx,app,calls}
}
const flush=async()=>{await Promise.resolve();await Promise.resolve()}
function find(root,predicate){const result=[];const visit=n=>{if(predicate(n))result.push(n);(n.children||[]).forEach(visit)};visit(root);return result}
test('the existing product detail is read with exact product and asset station; numeric facts are preserved',async()=>{
 const t=fixture();await t.page.loadData();await flush()
 assert.deepEqual(t.calls,[{id:5,stationId:11}]);const row=t.page.data.customerBarrelAsset[0]
 assert.equal(row.assetImageUrl,'https://images.example.test/sku-5.webp');assert.equal(row.productName,'普利思 饮用天然泉水')
 assert.equal(row.assetQty,3);assert.equal(row.availableRights,2);assert.equal(row.occupiedQty,2);assert.equal(row.deposit,60)
 assert.equal(t.page.data.summary.depositBalance,180);assert.equal(t.page.data.maxReturnQty,2)
 assert.equal(t.wx.__calls.storageSet.length,0);assert.equal(t.wx.__calls.nav.length,0)
})
test('photo loading does not block usable assets or refund eligibility',async()=>{
 const pending=deferred(),t=fixture({photo:()=>pending.promise});await t.page.loadData()
 assert.equal(t.page.data.loading,false);assert.equal(t.page.data.maxReturnQty,2);assert.equal(t.page.data.holdingsFailed,false)
 assert.equal(t.page.data.customerBarrelAsset[0].assetImageUrl,'');pending.resolve(ok({id:5,imageUrl:''}));await flush()
})
test('an existing holding photo is reused without an extra product lookup',async()=>{
 const t=fixture({holding:{imageUrl:'https://images.example.test/current.webp'}});await t.page.loadData()
 assert.equal(t.calls.length,0);assert.equal(t.page.data.customerBarrelAsset[0].assetImageUrl,'https://images.example.test/current.webp')
})
test('product mismatch, missing image, forbidden URL and read failure keep a neutral placeholder',async()=>{
 for(const response of [ok({id:7,imageUrl:'https://images.example.test/wrong.webp'}),ok({id:5}),ok({id:5,imageUrl:'javascript:bad'}),{code:1,data:{id:5,imageUrl:'https://images.example.test/no.webp'}}]){
  const t=fixture({photo:async()=>response});await t.page.loadData();await flush();assert.equal(t.page.data.customerBarrelAsset[0].assetImageUrl,'');assert.equal(t.page.data.loadError,'')
 }
 const t=fixture({photo:async()=>{throw Error('synthetic offline')}});await t.page.loadData();await flush();assert.equal(t.page.data.maxReturnQty,2);assert.equal(t.page.data.loadError,'')
})
test('late photo cannot cross station, logout or hidden page boundaries',async()=>{
 for(const change of ['station','logout','hide']){
  const d=deferred(),t=fixture({photo:()=>d.promise});await t.page.loadData()
  if(change==='station'){t.wx.__storage.set('selectedStation',{id:22,name:'站乙'});t.page._assetLife=(t.page._assetLife||0)+1}
  else if(change==='logout'){t.app.globalData.isLogin=false;t.app.globalData.customerId=null}
  else t.page.onHide()
  d.resolve(ok({id:5,imageUrl:'https://images.example.test/old.webp'}));await flush()
  assert.equal(t.page.data.customerBarrelAsset[0].assetImageUrl,'')
 }
})
test('a newer same-product refresh wins over an older photo response',async()=>{
 const a=deferred(),b=deferred();let n=0;const t=fixture({photo:()=>++n===1?a.promise:b.promise})
 await t.page.loadData();await t.page.loadData();b.resolve(ok({id:5,imageUrl:'https://images.example.test/new.webp'}));await flush()
 a.resolve(ok({id:5,imageUrl:'https://images.example.test/old.webp'}));await flush();assert.equal(t.page.data.customerBarrelAsset[0].assetImageUrl,'https://images.example.test/new.webp')
})
test('image error falls back only for the current matching image',async()=>{
 const t=fixture();await t.page.loadData();await flush()
 t.page.onAssetPhotoError({currentTarget:{dataset:{id:5,src:'https://images.example.test/old.webp'}}});assert.equal(t.page.data.customerBarrelAsset[0].assetImageFailed,false)
 t.page.onAssetPhotoError({currentTarget:{dataset:{id:5,src:t.page.data.customerBarrelAsset[0].assetImageUrl}}});assert.equal(t.page.data.customerBarrelAsset[0].assetImageFailed,true)
})
test('unknown placeholder specs become short display text without editing source product data',async()=>{
 for(const spec of ['',null,'按实物标签填写','未知']){
  const t=fixture({holding:{productSpec:spec}});await t.page.loadData();assert.equal(t.page.data.customerBarrelAsset[0].displaySpec,'规格待确认')
 }
 const t=fixture();await t.page.loadData();assert.equal(t.page.data.customerBarrelAsset[0].displaySpec,'18.5L');assert.equal(t.page.data.customerBarrelAsset[0].productSpec,'18.5L')
})
test('barrel rights use shares, zero owed is neutral, and purchase/return keep distinct roles',async()=>{
 const t=fixture();await t.page.loadData();const root=parseWxml(read('miniapp-user/pages/barrel/index.wxml'))
 const stats=find(root,n=>n.attrs?.class==='stats-card')[0],shown=renderElements({children:[stats]},t.page.data,{includeText:true})
 assert(shown.some(n=>n.text==='份'));assert(shown.some(n=>n.className.includes('stat-neutral')));assert(!shown.some(n=>n.className.split(' ').includes('owed')))
 const purchase=find(root,n=>n.attrs?.bindtap==='onPurchaseRights');assert.equal(purchase.length,1);assert(purchase[0].attrs.class.includes('af-action--primary'))
 const returns=find(root,n=>n.attrs?.bindtap==='onShowReturnModal'&&n.attrs.class.includes('barrel-return-entry'));assert.equal(returns.length,1);assert(returns[0].attrs.class.includes('af-action--quiet'))
 const photo=find(root,n=>n.tag==='image'&&n.attrs.binderror==='onAssetPhotoError')[0];assert.equal(photo.attrs.mode,'aspectFit');assert.equal(photo.attrs['data-id'],'{{item.productId}}')
 assert(!read('miniapp-user/pages/barrel/index.wxml').includes('爱护水桶'))
})
// Small cascade evaluator: uses actual app/shared/page declarations and specificity.
function widthOf(css,classes,ancestors){let winner=null;for(const rule of css.replace(/\/\*[\s\S]*?\*\//g,'').matchAll(/([^{}]+)\{([^{}]*)\}/g))for(const raw of rule[1].split(',')){
 const selector=raw.trim();if(!/^(\.[\w-]+)+( (\.[\w-]+)+)*$/.test(selector))continue
 const parts=selector.split(' '),last=parts.pop().slice(1).split('.');if(!last.every(c=>classes.includes(c)))continue
 if(!parts.every(p=>p.slice(1).split('.').every(c=>ancestors.includes(c))))continue
 const weight=(selector.match(/\./g)||[]).length
 for(const m of rule[2].matchAll(/(?:^|;)\s*width\s*:\s*([^;]+);/g))if(!winner||weight>=winner.weight)winner={weight,value:m[1].trim()}
 }return winner?.value}
test('actual cascade clears the old third-width on grid, standalone dashboard and expanded asset lists',()=>{
 const css=read('miniapp-delivery/styles/actions.wxss')+'\n'+read('miniapp-delivery/pages/station-mgmt/index.wxss')
 for(const ancestors of [['v2-management','management-grid'],['v2-management'],['v2-management','management-list']])assert.equal(widthOf(css,['management-tile','af-entry','b3-record-entry','v2-dashboard'],ancestors),'100%')
 for(const screen of [320,375]){
  const cell=(screen-32-10)/2,text=cell-20-2-26-8
  assert(cell>=139);assert(text>=56,'four Chinese characters retain horizontal space');assert(screen-32>260,'dashboard spans the page')
 }
 const json=JSON.parse(read('miniapp-delivery/pages/station-mgmt/index.json'));assert.equal(json.navigationBarTextStyle,'black')
})
test('manager route catalogue stays complete and shared compact mode defaults off',()=>{
 const root=parseWxml(read('miniapp-delivery/pages/station-mgmt/index.wxml'))
 const routes=find(root,n=>n.attrs?.bindtap==='onNavigate').map(n=>n.attrs['data-url'])
 for(const route of ['orders','products','customers','receivables','payments','staff','dashboard','barrel-return','asset-claims','owed-barrels','payroll','inter-station','gross-profit'])assert(routes.includes('/pages/station-mgmt/'+route+'/index'))
 assert(/compact:\s*\{ type: Boolean, value: false \}/.test(read('miniapp-user/components/AssetStationView/index.js')))
 for(const rel of ['miniapp-user/pages/barrel/index.wxml','miniapp-user/components/AssetStationView/index.wxml','miniapp-delivery/pages/station-mgmt/index.wxml'])validateConditionalSiblings(parseWxml(read(rel)))
})
;(async()=>{const done=armWatchdog(20000);let failures=0;for(const t of tests){try{await t.run();console.log('PASS '+t.name)}catch(e){failures++;console.error('FAIL '+t.name+'\n'+e.stack)}}done();if(failures)process.exitCode=1;else console.log('AQUAFLOW_SUITE_OK '+tests.length)})()
