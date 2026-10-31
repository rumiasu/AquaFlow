const assert=require('assert')
const {loadPage,createWx,createApp,armWatchdog}=require('./harness')
const done=armWatchdog(15000), tick=()=>new Promise(r=>setImmediate(r)), deferred=()=>{let resolve;const promise=new Promise(r=>resolve=r);return {promise,resolve}}
const app=()=>createApp({_loginGeneration:1,globalData:{isLogin:true,userInfo:{staffId:5,stationId:1,role:'STATION_MANAGER'}}})
const session=()=>{const a=getApp();return {app:a,generation:a._loginGeneration||0,identity:JSON.stringify(a.globalData.userInfo)}}
const current=s=>s.app===getApp()&&s.generation===(getApp()._loginGeneration||0)&&s.identity===JSON.stringify(getApp().globalData.userInfo)
async function main(){
 {
  const a=app(),wx=createWx(),d=deferred();let writes=0,commits=0;a.setLoginState=()=>{commits++}
  const p=loadPage('miniapp-delivery/pages/role-select/index.js',{app:a,wx,stubs:{'utils/request':{post:()=>{writes++;return d.promise},captureSession:session,currentSession:current},'utils/navigation':{open:()=>{}}}})
  const first=p.onSelectStationManager();p.onSelectDelivery();assert.equal(writes,1);a._loginGeneration++;d.resolve({data:{stationId:1}});await first;assert.equal(commits,0);assert.equal(p.data.selecting,false);console.log('PASS role selection single flight and late login guard')
 }
 {
  const a=app(),wx=createWx(),d=deferred();let map,posts=0,commits=0;a.refreshIdentityAndRoute=async()=>false;a.setLoginState=()=>{commits++};wx.chooseLocation=o=>{map=o}
  const p=loadPage('miniapp-delivery/pages/station-mgmt/create-station/index.js',{app:a,wx,stubs:{'utils/request':{post:()=>{posts++;return d.promise},captureSession:session,currentSession:current}}})
  await p.onShow();p.onChooseLocation();a._loginGeneration++;map.success({name:'旧位置',address:'旧地址',latitude:1,longitude:2});assert.equal(p.data.locationText,'');await p.onShow();p.setData({stationName:'测试水站',phone:'123',locationText:'测试地址',address:'地址',latitude:1,longitude:2});const first=p.onSubmit();await p.onSubmit();assert.equal(posts,1);a._loginGeneration++;d.resolve({data:{stationId:7}});await first;assert.equal(commits,0);console.log('PASS create station stale map and duplicate submit guards')
 }
 {
  const a=app(),wx=createWx(),dialogs=[],calls=[];const p=loadPage('miniapp-delivery/pages/station-mgmt/customers/adjust/index.js',{app:a,wx,stubs:{'utils/business-dialog':{showBusinessModal:o=>dialogs.push(o)},'api/station-mgmt':{executeAdjustment:async id=>calls.push(id)}}})
  p.setData({list:[{id:3,isPending:true,adjustNo:'T3',adjustTypeText:'调整',hasQty:true,qtyText:'+2'}]});p.onExecute({currentTarget:{dataset:{id:3}}});p.onExecute({currentTarget:{dataset:{id:3}}});assert.equal(dialogs.length,1);p.onHide();dialogs[0].success({confirm:true});await tick();assert.equal(calls.length,0);console.log('PASS adjustment execute late confirmation never writes')
 }
 {
  const a=app(),wx=createWx(),dialogs=[];let writes=0;const p=loadPage('miniapp-delivery/pages/bind-wait/index.js',{app:a,wx,stubs:{'utils/business-dialog':{showBusinessModal:o=>dialogs.push(o)},'utils/request':{get:async()=>({data:{}}),post:async()=>{writes++}}}})
  p.setData({statusReady:true,mode:'PENDING'});p.onWithdraw();p.onWithdraw();assert.equal(dialogs.length,1);p.onHide();await dialogs[0].success({confirm:true});assert.equal(writes,0);console.log('PASS withdrawal stale confirmation never writes')
 }
 {
  const a=app(),wx=createWx(),d=deferred();let location,writes=0,commits=0;wx.getLocation=o=>{location=o};a.setLoginState=()=>{commits++}
  const p=loadPage('miniapp-delivery/pages/station-mgmt/apply-bind/index.js',{app:a,wx,stubs:{'utils/request':{get:async()=>({data:[]}),post:()=>{writes++;return d.promise}}}})
  p.onRequestLocation();a._loginGeneration++;location.success({latitude:1,longitude:2});assert.equal(p.data.located,false)
  p.setData({stationList:[{id:7,name:'水站'}]});const first=p.onApplyStation({currentTarget:{dataset:{id:7}}});await p.onApplyStation({currentTarget:{dataset:{id:7}}});assert.equal(writes,1);a._loginGeneration++;d.resolve({data:{}});await first;assert.equal(commits,0);console.log('PASS binding stale location and duplicate application guards')
 }
 done();console.log('AQUAFLOW_SUITE_OK 5')
}
main().catch(e=>{done();console.error(e);process.exitCode=1})
