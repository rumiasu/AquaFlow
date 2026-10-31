const assert=require('assert')
const {loadPage,createWx,createApp,armWatchdog}=require('./harness')
const done=armWatchdog(15000), ok=data=>({data}), tick=()=>new Promise(r=>setImmediate(r))
const deferred=()=>{let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b});return {promise,resolve,reject}}
const app=()=>createApp({_loginGeneration:1,globalData:{isLogin:true,accessToken:'synthetic',userInfo:{staffId:5,stationId:1,role:'STATION_MANAGER',bindStatus:'BOUND'}}})
async function main(){
 {
  const a=app(),wx=createWx();let attempts=0;const api={getCatalog:async()=>{if(++attempts===1)throw Error('断网');return ok([{id:11,name:'水',selected:true,enabled:1}])}}
  const p=loadPage('miniapp-delivery/pages/station-mgmt/customers/adjust/edit/index.js',{app:a,wx,stubs:{'api/station-mgmt':api}})
  await p.loadProducts();assert(p.data.productError);assert.equal(p.data.manualProduct,false);assert.deepStrictEqual(p.data.productList,[])
  await p.onRetryProducts();assert.equal(p.data.productError,'');assert.equal(p.data.productList[0].id,11);assert.equal(p.data.manualProduct,false)
  const d=deferred();api.getCatalog=()=>d.promise;const pending=p.loadProducts();a._loginGeneration++;d.resolve(ok([{id:12,name:'旧站',selected:true,enabled:1}]));await pending;assert.equal(p.data.productList[0].id,11)
  console.log('PASS adjustment product failure, retry and stale session')
 }
 {
  const a=app(),wx=createWx();let response={data:{list:[]}};const p=loadPage('miniapp-delivery/pages/station-mgmt/customers/adjust/index.js',{app:a,wx,stubs:{'api/station-mgmt':{listAdjustments:async()=>response}}})
  await p.loadData();assert.equal(p.data.total,null);assert.equal(p.data.listError,'');response={data:{}};await p.loadData();assert(p.data.listError);assert.equal(p.data.total,null)
  console.log('PASS adjustment unknown total and malformed list')
 }
 {
  const a=app(),wx=createWx(),dialogs=[],calls=[];const p=loadPage('miniapp-delivery/pages/station-mgmt/customers/adjust/detail/index.js',{app:a,wx,stubs:{'utils/business-dialog':{showBusinessModal:o=>dialogs.push(o)},'api/station-mgmt':{reverseAdjustment:async(...args)=>{calls.push(args);return ok({})},getAdjustment:async()=>ok({})}}})
  p.setData({detail:{id:9,adjustNo:'A9',adjustTypeText:'补录',hasQty:true,qtyText:'2',isEffective:true},reverseClientToken:'original'});p.onShow();p.onReverse();p.onReverse();assert.equal(dialogs.length,1);assert.equal(dialogs[0].initialValue,'');assert(dialogs[0].description.includes('2'));dialogs[0].success({confirm:false});assert.equal(calls.length,0)
  p.onReverse();const old=dialogs[1];p.onHide();old.success({confirm:true,content:'原因'});assert.equal(calls.length,0);p.onShow();p.onReverse();dialogs[2].success({confirm:true,content:'原因'});await tick();assert.equal(calls.length,1);assert.equal(calls[0][1],'原因');assert.equal(calls[0][2],'original')
  console.log('PASS reverse dialog cancellation, stale callback, single confirmation')
 }
 {
  const a=app(),wx=createWx(),d=deferred(),calls=[];const p=loadPage('miniapp-delivery/pages/report/index.js',{app:a,wx,stubs:{'api/feedback':{getMyFeedbacks:async()=>ok([]),submitFeedback:body=>{calls.push(body);return d.promise}}}})
  p.onLoad();await p.onShow();p.onContentInput({detail:{value:'建议'}});const first=p.onSubmit();await p.onSubmit();assert.equal(calls.length,1);assert.equal(wx.__calls.modal.length,0);d.resolve(ok({}));await first;assert.equal(p.data.content,'');assert.equal(p.data.submitNotice,'反馈已提交。')
  console.log('PASS ordinary feedback submits once with inline receipt')
 }
 {
  const a=app(),wx=createWx(),d=deferred(),calls=[];const p=loadPage('miniapp-delivery/pages/report/index.js',{app:a,wx,stubs:{'api/feedback':{getMyFeedbacks:async()=>ok([]),submitFeedback:body=>{calls.push(body);return d.promise}}}})
  p.onLoad();await p.onShow();p.onContentInput({detail:{value:'原稿'}});const first=p.onSubmit();a._loginGeneration++;a.globalData.userInfo={staffId:8,stationId:2,role:'STATION_MANAGER'};await p.onShow();d.resolve(ok({}));await first;assert.equal(p.data.content,'');assert.equal(p.data.submitNotice,'');assert.equal(wx.__calls.toast.length,0)
  console.log('PASS feedback old receipt cannot affect a new login')
 }
 {
  const a=app(),wx=createWx(),p=loadPage('miniapp-delivery/pages/station-mgmt/customers/adjust/detail/index.js',{app:a,wx,stubs:{'api/station-mgmt':{reverseAdjustment:async()=>ok(null)}}})
  p.setData({detail:{id:9},reverseClientToken:'original'});p.loadData=()=>{};await p.doReverse('原因');assert(wx.__calls.toast.some(t=>t.title.includes('尚未确认')));assert(!wx.__calls.toast.some(t=>t.icon==='success'));console.log('PASS malformed reverse receipt is not presented as success')
 }
 done();console.log('AQUAFLOW_SUITE_OK 6')
}
main().catch(e=>{done();console.error(e);process.exitCode=1})
