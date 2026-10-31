'use strict'
const assert=require('node:assert/strict'),fs=require('fs'),path=require('path'),vm=require('vm')
const {loadPage,createWx,createApp,armWatchdog,ROOT}=require('./harness'),{parseWxml,renderElements}=require('./wxml-tree')
const done=armWatchdog(30000),ok=data=>({code:0,data}),tick=()=>new Promise(setImmediate)
function deferred(){let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b});return{promise,resolve,reject}}
const A={id:22,name:'异步确认站'},B={id:11,name:'切换后站'}
function fixture(route,{storedName='',explicit=true}={}){
 const wx=createWx(),app=createApp(),gate=deferred();wx.__storage.set('selectedStation',{id:22,name:storedName});app.globalData.accessToken='synthetic-token'
 const stations={getAssetStation:()=>gate.promise,listAssetStations:async()=>ok({stations:[B,A],hasMore:false,nextStationId:null})}
 delete require.cache[require.resolve(path.join(ROOT,'miniapp-user/utils/asset-view-station.js'))]
 const summary={independentRights:true,rightBuckets:0,availableRights:0,owedBuckets:0,heldBuckets:0,occupiedBuckets:0,pendingReturns:0,storageBuckets:0,depositBalance:0}
 const stubs={'api/asset-stations':stations,'api/barrel':{getBarrelSummary:async()=>ok(summary),getBarrelSummaryByType:async()=>ok([]),getBarrelRecords:async()=>ok([])},'api/ticket':{getTicketAccounts:async()=>ok([])},'api/company':{getCompanyInfo:async()=>ok(null)},'utils/request':{get:async()=>ok([])},'utils/ui-scene':{show(){},hide(){},imageError(){}}}
 const page=loadPage('miniapp-user/pages/'+route+'.js',{wx,app,stubs});if(page.onLoad)page.onLoad(explicit?{stationId:22}:{})
 const token=require(path.join(ROOT,'miniapp-user/utils/token.js'));let def
 vm.runInNewContext(fs.readFileSync(path.join(ROOT,'miniapp-user/components/AssetStationView/index.js'),'utf8'),{Component:v=>{def=v},wx,require:id=>id.endsWith('api/asset-stations')?stations:id.endsWith('utils/token')?token:(()=>{throw Error(id)})()})
 const c={properties:{compact:false,stationId:null,stationName:'',statusText:'',orderStationName:'',busy:false},data:JSON.parse(JSON.stringify(def.data)),setData(v){Object.assign(this.data,v)}};for(const [n,f] of Object.entries(def.methods))c[n]=f.bind(c);def.lifetimes.attached.call(c)
 const set=page.setData.bind(page);page.setData=(v,cb)=>{set(v,cb);Object.assign(c.properties,{stationId:page.data.viewStationId,stationName:page.data.stationName||'',statusText:page.data.viewStationStatusText||'',orderStationName:page.data.orderStationName||'',busy:false})}
 c.triggerEvent=(name,station)=>{assert.equal(name,'change');page.onAssetStationChange({detail:station})}
 const source=fs.readFileSync(path.join(ROOT,'miniapp-user/components/AssetStationView/index.wxml'),'utf8'),tree=parseWxml(source)
 const nodes=()=>renderElements(tree,{...c.properties,...c.data},{includeText:true})
 const text=()=>nodes().map(n=>n.text).join('\n')
 const scope=()=>nodes().find(n=>n.className==='scope-name').text
 return{wx,page,gate,c,text,scope}
}
;(async()=>{try{
 for(const route of ['mine/index','barrel/index','deposit/records/index']){
  const t=fixture(route);await t.page.onShow();await tick();assert.equal(t.page.data.viewStationId,22);assert.equal(t.page.data.stationName,'');assert(t.text().includes('资产水站名称待核实'))
  await t.c.openPicker();assert.equal(t.c.data.stations.length,2);assert.equal(t.scope(),'资产水站名称待核实','component does not silently infer props from picker list');t.c.close()
  t.gate.resolve(ok(A));await tick();assert.equal(t.page.data.stationName,A.name);assert.equal(t.c.properties.stationName,A.name);assert(t.text().includes(A.name));assert(!t.text().includes('资产水站名称待核实'))
  await t.c.openPicker();assert.equal(t.c.data.stations.length,2);t.c.choose({currentTarget:{dataset:{id:11}}});await tick();assert.equal(t.page.data.viewStationId,11);assert.equal(t.page.data.stationName,B.name);assert(t.text().includes(B.name));assert.equal(t.wx.__storage.get('selectedStation').id,22);t.page.onHide()
  const stale=fixture(route);await stale.page.onShow();await stale.c.openPicker();stale.c.choose({currentTarget:{dataset:{id:11}}});await tick();stale.gate.resolve(ok(A));await tick();assert.equal(stale.page.data.stationName,B.name);assert(stale.text().includes(B.name));stale.page.onHide()
  const cached=fixture(route,{storedName:'已选水站',explicit:false});await cached.page.onShow();assert.equal(cached.page.data.stationName,'已选水站');assert.equal(cached.scope(),'已选水站');cached.page.onHide()
  const failed=fixture(route);await failed.page.onShow();failed.gate.reject(Error('合成水站名称读取失败'));await tick();assert.equal(failed.scope(),'资产水站名称待核实');await failed.c.openPicker();failed.c.choose({currentTarget:{dataset:{id:11}}});await tick();assert.equal(failed.scope(),B.name);failed.page.onHide()
  console.log('PASS '+route+' real shared station sync → component props; known/missing names, failed/late metadata and picker switch')
 }
 console.log('AQUAFLOW_SUITE_OK 3')
 }finally{done()}})().catch(e=>{console.error(e);process.exitCode=1})
