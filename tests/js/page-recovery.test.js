'use strict'
const fs=require('fs'),path=require('path'),vm=require('vm'),assert=require('assert')
const {fixture,flush,png,ok,observe,ROOT}=require('./cloud-upload.mock.test')
const files={complete:'pages/order/complete.js',detail:'pages/order/detail.js',product:'pages/station-mgmt/products/index.js',adjustment:'pages/station-mgmt/customers/adjust/edit/index.js'}
function pageFixture(kind,storage=new Map(),mutate=(n,s)=>s) {
 const f=fixture('cloudContainer','miniapp-delivery',mutate,storage),modals=[],actions=[],previews=[];let definition
 Object.assign(f.app,{canAccessStationBusiness:()=>true,isStationManager:()=>true,routeByRole(){}})
 Object.assign(f.wx,{showModal:o=>modals.push(o),showActionSheet:o=>actions.push(o),previewImage:o=>previews.push(o)})
 const noop=()=>Promise.resolve({code:0,data:[]})
 const stub=new Proxy({data:{},methods:{}},{get:(target,key)=>key in target?target[key]:noop})
 vm.runInNewContext(mutate('page',fs.readFileSync(path.join(ROOT,'miniapp-delivery',files[kind]),'utf8')),{
  Page:value=>{definition=value},getApp:()=>f.app,wx:f.wx,console:{warn(){},error(){},log(){}},
  getCurrentPages:()=>[],setTimeout,clearTimeout,setInterval:()=>1,clearInterval(){},
  require(id){if(id.endsWith('/upload-recovery-page'))return f.load('upload-recovery-page');if(id.endsWith('/upload'))return f.api;
   if(id.endsWith('/request'))return f.load('request');if(id.includes('config/api'))return f.config;return stub}
 },{filename:files[kind]})
 const p={...definition,data:JSON.parse(JSON.stringify(definition.data)),setData(values){for(const[k,v]of Object.entries(values)){const parts=k.split('.');let o=this.data;for(let i=0;i<parts.length-1;i++){o[parts[i]]=o[parts[i]]||{};o=o[parts[i]]}o[parts[parts.length-1]]=v}}}
 Object.assign(p.data,{orderId:42,floorPhotos:[],photos:[],floorUploading:false,uploading:false,isAdd:true,editId:null,customerId:42,evidenceList:[],denied:false,submitting:false,previewing:false})
 p._customerSeq=1;p.loadFloorPhotos=async()=>{};p.loadData=async()=>{}
 const pick=kind==='complete'||kind==='detail'?'onAddFloorPhoto':kind==='product'?'uploadImage':'onChooseEvidence'
 return{...f,p,modals,actions,previews,pick}
}
async function unknownFromPage(f,file='/synthetic.png'){
 const old=f.picks.length;f.p[f.pick]();assert.equal(f.picks.length,old+1)
 f.picks[old].success({tempFilePaths:[file]});await flush();f.read(f.stats.length-1);await flush()
 f.calls[f.calls.length-1].resolve({statusCode:200,data:{code:1}});await flush()
 assert.equal(f.p.data.uploadCaseIds.length,1);return f.p.data.uploadCaseIds[0]
}
const event=id=>({currentTarget:{dataset:{caseid:id}}})
const image={id:101,orderId:42,type:3,objectName:'private/order/42/synthetic.png',url:'/synthetic-present'}
const tests=[],test=(name,run)=>tests.push({name,run})
for(const kind of ['complete','detail']) {
 test(kind+' real picker → unknown panel → preview/confirm → existing read recovery',async()=>{
  const f=pageFixture(kind),id=await unknownFromPage(f);assert(f.p.data.uploadRecoveryCases[0].id===id)
  const r=observe(f.p.onUploadRecover(event(id)));await flush();assert.equal(f.calls[1].options.method,'GET');f.calls[1].resolve(ok([image]));await flush()
  f.actions[0].success({tapIndex:0});await flush();assert(f.p.data.uploadRecoverySelection);f.p.onUploadPreviewSelected();assert.equal(f.previews.length,1)
  const confirmation=observe(f.p.onUploadConfirmSelected());await flush();f.modals[0].success({confirm:true});await flush()
  assert.equal(f.calls[2].options.method,'GET');f.calls[2].resolve(ok([image]));await flush();assert(!r.error);assert.equal(f.p.data.uploadCaseIds.length,0)
  assert.equal(f.calls.filter(x=>x.options.method==='POST').length,1)
 })
 test(kind+' empty/cancelled recovery retains the durable case and performs no repeat POST',async()=>{
  const f=pageFixture(kind),id=await unknownFromPage(f),r=observe(f.p.onUploadRecover(event(id)));await flush();f.calls[1].resolve(ok([]));await flush()
  assert(!r.error);assert.equal(f.p.data.uploadCaseIds.length,1);assert.equal(f.calls.length,2)
  const r2=observe(f.p.onUploadRecover(event(id)));await flush();f.calls[2].resolve(ok([image]));await flush();f.actions[0].success({tapIndex:0});await flush()
  const cancelled=observe(f.p.onUploadConfirmSelected());await flush();f.modals[0].success({confirm:false});await flush();assert.equal(f.p.data.uploadCaseIds.length,1);assert.equal(f.calls.length,3)
 })
 test(kind+' new runtime onShow restores unknown and picker cannot retry under a new path/relogin',async()=>{
  const shared=new Map(),f=pageFixture(kind,shared);await unknownFromPage(f)
  const restarted=pageFixture(kind,shared);restarted.p.refreshUploadRecovery();assert.equal(restarted.p.data.uploadCaseIds.length,1)
  restarted.app._loginGeneration++;restarted.p[restarted.pick]();restarted.picks[0].success({tempFilePaths:['/second.png']});await flush()
  assert.equal(restarted.calls.length,0);assert.equal(restarted.stats.length,0);assert.equal(restarted.p.data.uploadCaseIds.length,1)
 })
}
for(const kind of ['product','adjustment']) {
 test(kind+' real page retains common case without fake success or order recovery GET',async()=>{
  const f=pageFixture(kind),id=await unknownFromPage(f);f.p.onUploadRecover(event(id));await flush();assert.equal(f.modals.length,1);assert.equal(f.calls.length,1);assert.equal(f.p.data.uploadCaseIds.length,1)
 })
 test(kind+' explicit independent draft does not clear old case or lock the new draft',async()=>{
  const f=pageFixture(kind),id=await unknownFromPage(f),oldDraft=f.p._uploadDraftId;f.p.onUploadNewDraft();f.modals[0].success({confirm:true});await flush();assert.notEqual(f.p._uploadDraftId,oldDraft)
  const pick=f.picks.length;f.p[f.pick]();f.picks[pick].success({tempFilePaths:['/second.png']});await flush();f.read(f.stats.length-1);await flush();assert.equal(f.calls.length,2)
  f.calls[1].resolve(ok('/synthetic-independent'));await flush();assert(f.p.data.uploadCaseIds.includes(id))
  f.p.onUploadResumeDraft(event(id));assert.equal(f.p._uploadDraftId,oldDraft)
  const before=f.calls.length,pick2=f.picks.length;f.p[f.pick]();if(kind==='product')assert.equal(f.picks.length,pick2);else f.picks[pick2].success({tempFilePaths:['/second.png']});await flush();assert.equal(f.calls.length,before)
 })
 test(kind+' restart restores the original draft ID and unknown case; normal login cleanup cannot discard it',async()=>{
  const shared=new Map(),f=pageFixture(kind,shared),id=await unknownFromPage(f),draft=f.p._uploadDraftId
  const r=pageFixture(kind,shared);r.p.refreshUploadRecovery();assert(r.p.data.uploadCaseIds.includes(id));r.p.onUploadResumeDraft(event(id));assert.equal(r.p._uploadDraftId,draft)
  r.app._loginGeneration++;const n=r.picks.length;r.p[r.pick]();if(kind==='product')assert.equal(r.picks.length,n);else r.picks[n].success({tempFilePaths:['/second.png']});await flush();assert.equal(r.calls.length,0)
 })
}
test('account/station switch hides previous owner cases and does not lock independent owner draft',async()=>{
 const shared=new Map(),a=pageFixture('product',shared);await unknownFromPage(a);const b=pageFixture('product',shared);b.switchAccount();b.p.refreshUploadRecovery();assert.equal(b.p.data.uploadCaseIds.length,0)
 b.p.uploadImage();b.picks[0].success({tempFilePaths:['/synthetic.png']});await flush();b.read();await flush();assert.equal(b.calls.length,1);b.calls[0].resolve(ok('/synthetic-B'));await flush()
})
test('persistent marker exists before POST response; abrupt restart blocks the original operation',async()=>{
 const shared=new Map(),f=pageFixture('complete',shared);f.p.onAddFloorPhoto();f.picks[0].success({tempFilePaths:['/synthetic.png']});await flush();f.read();await flush();assert.equal(f.calls.length,1)
 const r=pageFixture('complete',shared);r.p.refreshUploadRecovery();assert.equal(r.p.data.uploadCaseIds.length,1);r.p.onAddFloorPhoto();r.picks[0].success({tempFilePaths:['/second.png']});await flush();assert.equal(r.calls.length,0)
 f.calls[0].resolve({statusCode:200,data:{code:1}});await flush()
})
test('metadata has no credentials, signed URLs, file paths or image bytes',async()=>{
 const f=pageFixture('complete');await unknownFromPage(f);const raw=f.storage.get('aq_delivery_uploadRecovery_v1')
 assert(!/synthetic-A|synthetic-RA|Bearer|accessToken|refreshToken|\/synthetic\.png|Uint8Array|q-signature/.test(raw));const parsed=JSON.parse(raw);assert.equal(parsed.version,1)
})
test('storage failure before dispatch prevents POST and cannot be treated as success',async()=>{
 const f=pageFixture('complete');f.wx.setStorageSync=()=>{throw Error('synthetic storage failure')};f.p.onAddFloorPhoto();f.picks[0].success({tempFilePaths:['/synthetic.png']});await flush();f.read();await flush();assert.equal(f.calls.length,0)
})
test('actual WXML binds every recovery control to a real page method',async()=>{
 for(const kind of Object.keys(files)){const f=pageFixture(kind),wxml=fs.readFileSync(path.join(ROOT,'miniapp-delivery',files[kind].replace('.js','.wxml')),'utf8');assert(/bindtap="onUploadRecover"/.test(wxml));assert.equal(typeof f.p.onUploadRecover,'function')
  if(['product','adjustment'].includes(kind)){for(const name of ['onUploadResumeDraft','onUploadNewDraft']){assert(wxml.includes('bindtap="'+name+'"'));assert.equal(typeof f.p[name],'function')}}}
})
async function main(){let passed=0;const timer=setTimeout(()=>{console.error('PAGE_RECOVERY_TIMEOUT');process.exit(1)},30000)
 try{for(const t of tests){await t.run();passed++;console.log('PASS '+t.name)}fs.writeFileSync(path.join(__dirname,'page-recovery-results.json'),JSON.stringify({passed,failed:0,scope:'actual four page handlers + recovery/store/request modules, synthetic SDK/storage/FS/UI mocks; no real uploads'},null,2));console.log('AQUAFLOW_PAGE_RECOVERY_OK '+passed);console.log('AQUAFLOW_SUITE_OK '+passed)}finally{clearTimeout(timer)}}
if(require.main===module)main().catch(e=>{console.error(e.stack);process.exitCode=1})
module.exports={pageFixture,unknownFromPage,event,image}
