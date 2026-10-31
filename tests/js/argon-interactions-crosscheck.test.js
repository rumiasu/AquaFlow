'use strict'
// Exercises actual page handlers, shared adapter and component logic with synthetic APIs only.
const assert=require('node:assert/strict'),fs=require('fs'),path=require('path'),vm=require('vm')
const {loadPage,createWx,createApp,armWatchdog,ROOT}=require('./harness')
const {parseWxml,renderElements}=require('./wxml-tree')
const done=armWatchdog(30000), tick=()=>new Promise(setImmediate), ok=data=>({code:0,data})
function component(rel,wx,modules={}){
 let def;vm.runInNewContext(fs.readFileSync(path.join(ROOT,rel),'utf8'),{Component:d=>{def=d},wx,require:id=>{if(!(id in modules))throw Error('unexpected component dependency '+id);return modules[id]}})
 const c={data:JSON.parse(JSON.stringify(def.data||{})),properties:{},setData(p,cb){Object.assign(this.data,p);if(cb)cb()}}
 for(const [name,fn] of Object.entries(def.methods||{}))c[name]=fn.bind(c)
 if(def.lifetimes?.attached)def.lifetimes.attached.call(c)
 return {c,def}
}
function nested(page){page.setData=function(p,cb){for(const [k,v] of Object.entries(p)){const parts=k.split('.');let o=this.data;for(const part of parts.slice(0,-1))o=o[part]||(o[part]={});o[parts.at(-1)]=v}if(cb)cb()};return page}
function dialog(side,wx,page){const x=component(side+'/components/BusinessDialog/index.js',wx);page.selectComponent=id=>id==='#business-dialog'?x.c:null;global.getCurrentPages=()=>[page];return x}
function draftPage(wx,app,session){
 const token={captureSession:()=>({...session}),isCurrentSession:s=>s.customerId===session.customerId&&s.epoch===session.epoch,getCustomerId:()=>session.customerId,getAccessToken:()=> 'synthetic'}
 const scope={beginRead:()=>({stationId:1,session:{...session}}),current:()=>true,init(){},activate(){},suspend(){},select:()=>true,id:()=>1,url:(p,u)=>u}
 return nested(loadPage('miniapp-user/pages/barrel/index.js',{wx,app,stubs:{'utils/token':token,'utils/asset-view-station':scope,'api/barrel':{previewBarrelReturn:async()=>ok({refundAmount:60})}}}))
}
async function draft(){
 const wx=createWx(),app=createApp(),session={loggedIn:true,customerId:7,epoch:1};wx.showModal=()=>{throw Error('native fallback used')}
 const data={viewStationId:1,loading:false,maxReturnQty:5,customerBarrelAsset:[{productId:5,availableRights:5}],returnProductOptions:[{id:5}],summary:{independentRights:true}}
 const first=draftPage(wx,app,session);first.setData(data);first.onShowReturnModal();first.onReturnQtyInput({detail:{value:'2'}});first.onReturnNoteInput({detail:{value:'搬家'}})
 const next=draftPage(wx,app,session);next.setData(data);next.readUiDraft();assert.equal(next.data.uiDraftAvailable,true)
 const d=dialog('miniapp-user',wx,next);next.onRestoreUiDraft();assert.equal(d.c.data.visible,true);assert.equal(next.data.returnForm.productId,null);d.c.onCancel();await tick();assert.equal(next.data.returnForm.productId,null)
 next.onRestoreUiDraft();assert.equal(d.c.data.visible,true);d.c.onConfirm();await tick();assert.equal(next.data.returnForm.productId,5);assert.equal(next.data.returnForm.quantity,2);assert.equal(next.data.returnForm.note,'搬家');assert.equal(next.data.showReturnModal,true)
 next.setData({returnForm:{productId:5,quantity:1,note:'未替换'}});next.onRestoreUiDraft();d.def.pageLifetimes.hide.call(d.c);await tick();assert.equal(next.data.returnForm.note,'未替换')
 const appJson=JSON.parse(fs.readFileSync(path.join(ROOT,'miniapp-user/app.json'),'utf8'));assert.equal(appJson.usingComponents['business-dialog'],'/components/BusinessDialog/index');assert(fs.readFileSync(path.join(ROOT,'miniapp-user/pages/barrel/index.wxml'),'utf8').includes('<business-dialog id="business-dialog"'))
 console.log('PASS barrel draft: real page → adapter → component cancel/confirm/hide, exact quantity and note')
}
async function station(){
 const wx=createWx(),app=createApp(),stations=[{id:11,name:'原资产站'},{id:22,name:'空资产站'}],reads=[]
 wx.setStorageSync('selectedStation',{id:33,name:'下单站'})
 const api={getAssetStation:async id=>ok(stations.find(x=>x.id===id)),listAssetStations:async()=>ok({stations,hasMore:false})}
 const summary={independentRights:true,rightBuckets:0,availableRights:0,owedBuckets:0,heldBuckets:0,occupiedBuckets:0,pendingReturns:0,storageBuckets:0,depositBalance:0}
 delete require.cache[require.resolve(path.join(ROOT,'miniapp-user/utils/asset-view-station'))]
 const page=nested(loadPage('miniapp-user/pages/barrel/index.js',{wx,app,stubs:{'api/asset-stations':api,'api/barrel':{getBarrelSummary:async id=>{reads.push(id);return ok(summary)},getBarrelSummaryByType:async()=>ok([]),getBarrelRecords:async()=>ok([])}}}))
 page.onLoad({stationId:22});await page.onShow();await tick();assert.equal(page.data.viewStationId,22)
 // Even when the secondary empty-card shortcut is hidden, the top component is always present.
 page.setData({hasOtherAssetStation:false});const wxml=fs.readFileSync(path.join(ROOT,'miniapp-user/pages/barrel/index.wxml'),'utf8')
 const nodes=renderElements(parseWxml(wxml),{...page.data,summary:page.data.summary||{}},{includeText:true});assert(nodes.some(n=>n.tag==='asset-station-view'));assert(!nodes.some(n=>n.attrs.bindtap==='onChooseAssetStation'))
 const token=require(path.join(ROOT,'miniapp-user/utils/token'));const stationComp=component('miniapp-user/components/AssetStationView/index.js',wx,{'../../api/asset-stations':api,'../../utils/token':token})
 stationComp.c.properties={busy:false,stationId:22};stationComp.c.triggerEvent=(name,value)=>{assert.equal(name,'change');page.onAssetStationChange({detail:value})};await stationComp.c.openPicker();assert.equal(stationComp.c.data.open,true);assert.equal(stationComp.c.data.stations.length,2)
 stationComp.c.choose({currentTarget:{dataset:{id:11}}});await tick();assert.equal(page.data.viewStationId,11);assert.deepEqual(wx.getStorageSync('selectedStation'),{id:33,name:'下单站'});assert(reads.includes(11))
 const markup=fs.readFileSync(path.join(ROOT,'miniapp-user/components/AssetStationView/index.wxml'),'utf8');assert(markup.includes('bindtap="openPicker"'));assert(markup.includes('bindtap="choose"'))
 console.log('PASS empty asset: actual top component opens, reads, chooses another asset station without changing order station')
}
async function collection(){
 const wx=createWx(),app=createApp({_loginGeneration:1,globalData:{isLogin:true,userInfo:{staffId:7,stationId:11,role:'STATION_MANAGER'}}});let resolveWrite,writes=0
 const pending=new Promise(r=>{resolveWrite=r});wx.showModal=()=>{throw Error('native fallback used')}
 const page=loadPage('miniapp-delivery/pages/station-mgmt/payments/index.js',{wx,app,stubs:{'api/station-mgmt':{confirmPayment:async()=>{writes++;return pending},getPendingPayments:async()=>ok([])}}})
 page._paymentsSeq=1;page._paymentsHidden=false;page.setData({view:'pending',list:[{id:42,orderId:44,amountText:'25.00'}]});page.loadData=()=>{}
 const d=dialog('miniapp-delivery',wx,page),e={currentTarget:{dataset:{id:42}}};page.onConfirm(e);page.onConfirm(e)
 assert.equal(d.c.data.visible,true);assert.equal(d.c._queue.length,0);assert(d.c.data.content.includes('确认已收到'));d.c.onConfirm();page.onConfirm(e);assert.equal(writes,1);assert.equal(d.c._queue.length,0);resolveWrite();await tick();assert(wx.__calls.toast.some(x=>x.title==='已确认到账'))
 const wxml=fs.readFileSync(path.join(ROOT,'miniapp-delivery/pages/station-mgmt/payments/index.wxml'),'utf8');assert(wxml.includes('待确认不代表已到账'));assert(wxml.includes('核对收款方及款项后登记'))
 console.log('PASS collection: actual confirmation component names receipt, repeated taps issue one write')
}
async function receivables(){
 const wx=createWx(),app=createApp(),page=loadPage('miniapp-delivery/pages/station-mgmt/receivables/index.js',{wx,app})
 page.setData({loading:false,loadError:'',onlyOverdue:true,customer:null,overview:{outstandingAmount:10,overdueAmount:0,customerCount:1,overdueCustomerCount:0},allCustomers:[{customerId:3,customerName:'合成客户',overdueAmount:0,overdueOrderCount:0,outstandingAmount:10}],customers:[]})
 const wxml=fs.readFileSync(path.join(ROOT,'miniapp-delivery/pages/station-mgmt/receivables/index.wxml'),'utf8'),tree=parseWxml(wxml)
 const before=renderElements(tree,page.data,{includeText:true});assert(before.some(n=>n.attrs.bindtap==='onFilterAll'));assert(before.some(n=>n.text.includes('暂无逾期客户；全部待收仍可查询')))
 page.onFilterAll();assert.equal(page.data.onlyOverdue,false);assert.equal(page.data.customers.length,1);assert.equal(page.data.customers[0].customerId,3);assert.equal(page.data.overview.outstandingAmount,10)
 const after=renderElements(tree,page.data,{includeText:true});assert(!after.some(n=>n.text.includes('暂无逾期客户；全部待收仍可查询')))
 console.log('PASS receivables: overdue empty state keeps actual all-filter action and returns unmodified full result')
}
;(async()=>{try{await draft();await station();await collection();await receivables();console.log('AQUAFLOW_SUITE_OK 4')}finally{done()}})().catch(e=>{console.error(e);process.exitCode=1})
