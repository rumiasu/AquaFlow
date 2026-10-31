'use strict'
const assert=require('assert'),path=require('path'),fs=require('fs')
const {createWx,loadPage,armWatchdog,ROOT}=require('./harness')
const done=armWatchdog(30000),tick=()=>new Promise(resolve=>setImmediate(resolve))
let checks=0
function fixture(side,id=7){
 const wx=createWx();wx.getStorageInfoSync=()=>({keys:[...wx.__storage.keys()]})
 let app,station=1
 global.wx=wx;global.__wxConfig={envVersion:'develop'};global.getCurrentPages=()=>[];global.App=value=>{app=value};global.getApp=()=>app
 const requestPath=path.join(ROOT,side,'utils/request.js'),prior=require.cache[requestPath]
 require.cache[requestPath]={id:requestPath,filename:requestPath,loaded:true,exports:{get:async()=>({data:{staffId:id,role:'STATION_MANAGER',stationId:station,bindStatus:'BOUND'}})}}
 const file=path.join(ROOT,side,'app.js');delete require.cache[file]
 try{require(file)}finally{if(prior)require.cache[requestPath]=prior;else delete require.cache[requestPath]}
 const login=next=>{if(side==='miniapp-user'){app.setLoginInfo('synthetic','synthetic-refresh',{customerId:next});app.globalData.customerId=next}else app.setLoginState({accessToken:'synthetic',refreshToken:'synthetic-refresh',staffId:next,role:'STATION_MANAGER',stationId:station,bindStatus:'BOUND'})}
 login(id)
 const api=require(path.join(ROOT,side,'utils/draft-guard.js'))
 const scope=()=>({app:side,actor:side==='miniapp-user'?app.globalData.customerId:(app.globalData.userInfo||{}).staffId,station:side==='miniapp-user'?'personal':(app.globalData.userInfo||{}).stationId,route:'synthetic/page',entity:'new',generation:app._loginGeneration||0})
 return {wx,app,api,scope,login,station:value=>{station=value},logout:()=>side==='miniapp-user'?app.clearLoginInfo():app.clearLoginState()}
}
async function main(){
 for(const side of ['miniapp-user','miniapp-delivery']){
  const f=fixture(side),guard=f.api.createDraftGuard(f.scope,f.wx),token=guard.begin();guard.save(token,{note:'synthetic UI text'})
  const original={id:'original',phase:'unknown'};f.wx.__storage.set('aquaflow:order-write-recovery:original',original)
  let memory='synthetic session text';f.api.registerSessionDraftCleanup(f.app,()=>{memory=''})
  f.logout();assert.equal(f.wx.__storage.has(token.key),false);assert.equal(memory,'');assert.equal(f.wx.__storage.get('aquaflow:order-write-recovery:original'),original);assert.equal(f.app.globalData.isLogin,false);checks++
  f.login(7);const current=guard.begin();guard.save(current,{note:'A'});f.login(8);assert.equal(f.wx.__storage.has(current.key),false);assert.equal(guard.read(guard.begin()).state,'empty');checks++
  const other=f.api.createDraftGuard(()=>({...f.scope(),actor:99}),f.wx).begin();f.wx.__storage.set(other.key,{version:1,scopeKey:other.key,draft:{note:'other identity'}})
  // An entry left by an earlier process has no live guard registration.
  const cold='aquaflow:ui-draft:v1:'+encodeURIComponent(JSON.stringify([side,'8',side==='miniapp-user'?'personal':'1','cold/page','new']))
  f.wx.__storage.set(cold,{version:1,scopeKey:cold,draft:{note:'cold'}});f.logout();assert.equal(f.wx.__storage.has(cold),false);assert.equal(f.wx.__storage.has(other.key),true);checks++
 }
 const staff=fixture('miniapp-delivery'),guard=staff.api.createDraftGuard(staff.scope,staff.wx),token=guard.begin();guard.save(token,{category:'bug'})
 staff.app.globalData.userInfo.staffId=String(staff.app.globalData.userInfo.staffId);await staff.app.syncIdentity();assert.equal(guard.read(token).state,'saved');staff.station(2);await staff.app.syncIdentity();assert.equal(staff.wx.__storage.has(token.key),false);checks++
 const f=fixture('miniapp-delivery');let finish
 const api={options:async()=>({data:{intakeEnabled:true,requestTypes:[{value:'ACCESS',label:'query'}]}}),mine:async()=>({data:{items:[]}}),submit:()=>new Promise(resolve=>{finish=resolve})}
 const page=loadPage('miniapp-delivery/pages/mine/account-data/index.js',{app:f.app,wx:f.wx,stubs:{'api/account-data-requests':api}})
 page.onShow();await tick();page.onType({detail:{value:'0'}});page.onNote({detail:{value:'synthetic private body'}})
 const pending=page.onSubmit();await tick();const slot=page._requestSlot;assert(slot.submission);page.onUnload();f.logout();assert.equal(slot.submission,null);assert.equal(slot.flight,null);assert.equal(slot.pages.size,0)
 finish({data:{id:11,status:'SUBMITTED'}});await pending;assert.equal(slot.submission,null);checks++
 const bad=fixture('miniapp-user'),saved=bad.api.createDraftGuard(bad.scope,bad.wx),badToken=saved.begin();saved.save(badToken,{nickname:'synthetic'})
 const remove=bad.wx.removeStorageSync;bad.wx.removeStorageSync=key=>{if(key.startsWith('aquaflow:ui-draft:v1:'))throw Error('synthetic storage failure');return remove(key)}
 bad.logout();assert.equal(bad.app.globalData.isLogin,false);assert.equal(bad.app._uiDraftCleanup.state,'unknown');checks++
 console.log('PASS actual App logout/relogin/identity refresh clears UI and destroyed-page session caches without deleting original business recovery')
 console.log('AQUAFLOW_SUITE_OK '+checks)
}
main().then(done,error=>{done();console.error(error);process.exitCode=1})