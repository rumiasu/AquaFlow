'use strict'
const assert=require('assert'),fs=require('fs'),path=require('path')
const {flush,ok,observe}=require('./cloud-upload.mock.test')
const {pageFixture,unknownFromPage,event,image}=require('./page-recovery.test')
const KEY='aq_delivery_uploadRecovery_v1',tests=[],test=(name,run)=>tests.push({name,run})
async function select(f,id,rows=[image]){
 const before=f.actions.length
 const r=observe(f.p.onUploadRecover(event(id)));await flush();f.calls[f.calls.length-1].resolve(ok(rows));await flush()
 if(f.actions.length>before){f.actions[f.actions.length-1].success({tapIndex:0});await flush()}
 return r
}
async function finalRead(f){const r=observe(f.p.onUploadConfirmSelected());await flush();f.modals[f.modals.length-1].success({confirm:true});await flush();return r}
test('hide during first authorized read cannot show a sheet or mutate persistent records',async()=>{
 const f=pageFixture('complete'),id=await unknownFromPage(f),before=f.storage.get(KEY),r=observe(f.p.onUploadRecover(event(id)));await flush();f.p.onHide();f.calls[1].resolve(ok([image]));await flush()
 assert(!r.error);assert.equal(f.actions.length,0);assert.equal(f.storage.get(KEY),before);assert.equal(f.p.data.uploadRecovering,false)
})
test('unload after selecting a row cannot accept a late choice or show a confirmation',async()=>{
 const f=pageFixture('detail'),id=await unknownFromPage(f),r=observe(f.p.onUploadRecover(event(id)));await flush();f.calls[1].resolve(ok([image]));await flush();f.p.onUnload();f.actions[0].success({tapIndex:0});await flush()
 assert(!r.error);assert.equal(f.p.data.uploadRecoverySelection,null);assert.equal(f.modals.length,0);assert.equal(f.api.uploadCases().length,1)
})
test('hide during final recovery GET cannot clear the durable original case',async()=>{
 const f=pageFixture('complete'),id=await unknownFromPage(f);await select(f,id);const r=await finalRead(f);const before=f.storage.get(KEY)
 f.p.onHide();f.calls[2].resolve(ok([image]));await flush();assert(!r.error);assert.equal(f.storage.get(KEY),before);assert.equal(f.api.uploadCases().length,1)
})
test('hide/show invalidates an old confirmation; native preview itself never clears a case',async()=>{
 const f=pageFixture('complete'),id=await unknownFromPage(f);await select(f,id);f.p.onUploadPreviewSelected();assert.equal(f.previews.length,1)
 const r=observe(f.p.onUploadConfirmSelected());await flush();f.p.onHide();f.p.onShow();f.modals[0].success({confirm:true});await flush();assert(!r.error);assert.equal(f.calls.length,2);assert.equal(f.api.uploadCases().length,1)
 const newer=await finalRead(f);f.calls[2].resolve(ok([image]));await flush();assert(!newer.error);assert.equal(f.api.uploadCases().length,0)
})
test('switching orders after selection clears old inline image and cannot confirm it',async()=>{
 const f=pageFixture('complete'),id=await unknownFromPage(f);await select(f,id);f.p.data.orderId=43;f.p.refreshUploadRecovery();assert.equal(f.p.data.uploadRecoverySelection,null)
 await f.p.onUploadConfirmSelected();assert.equal(f.calls.length,2);assert.equal(f.api.uploadCases().length,1)
})
test('switching accounts during final read leaves A case intact and shows no A metadata to B',async()=>{
 const f=pageFixture('detail'),id=await unknownFromPage(f);await select(f,id);const r=await finalRead(f);f.switchAccount();f.calls[2].resolve(ok([image]));await flush();assert(!r.error)
 f.p.refreshUploadRecovery();assert.equal(f.p.data.uploadCaseIds.length,0);assert.equal(f.p.data.uploadRecoverySelection,null)
 f.app.globalData.userInfo={staffId:1,stationId:1,role:'STATION_MANAGER'};f.app._loginGeneration++;assert.equal(f.api.uploadCases().length,1)
})
test('storage read failure clears old visible case IDs and selected image without clearing storage',async()=>{
 const f=pageFixture('complete'),id=await unknownFromPage(f);await select(f,id);const before=f.storage.get(KEY);f.wx.getStorageSync=()=>{throw Error('synthetic')};f.p.refreshUploadRecovery()
 assert.equal(f.p.data.uploadCaseIds.length,0);assert.equal(f.p.data.uploadRecoverySelection,null);assert(f.p.data.uploadRecoveryReadFailed);assert.equal(f.storage.get(KEY),before)
})
test('malformed stored metadata is fail-closed from the actual picker, never silently emptied',async()=>{
 const f=pageFixture('complete');f.storage.set(KEY,'{"version":1,"owners":[]}');const before=f.storage.get(KEY)
 f.p.onAddFloorPhoto();f.picks[0].success({tempFilePaths:['/synthetic.png']});await flush();assert.equal(f.calls.length,0);assert.equal(f.stats.length,0);assert.equal(f.storage.get(KEY),before);assert(f.p.data.uploadRecoveryReadFailed)
})
test('successful POST with failed metadata cleanup remains unknown across a fresh runtime',async()=>{
 const shared=new Map(),f=pageFixture('complete',shared);f.p.onAddFloorPhoto();f.picks[0].success({tempFilePaths:['/synthetic.png']});await flush();f.read();await flush()
 f.wx.setStorageSync=()=>{throw Error('synthetic')};f.calls[0].resolve(ok(image));await flush();assert.equal(f.p.data.uploadCaseIds.length,1)
 const r=pageFixture('complete',shared);r.p.refreshUploadRecovery();assert.equal(r.p.data.uploadCaseIds.length,1)
})
test('failed persistent cleanup during verified recovery cannot show success or clear the case',async()=>{
 const f=pageFixture('complete'),id=await unknownFromPage(f);await select(f,id);const r=await finalRead(f);const before=f.storage.get(KEY)
 f.wx.setStorageSync=()=>{throw Error('synthetic')};f.calls[2].resolve(ok([image]));await flush();assert(!r.error);assert.equal(f.storage.get(KEY),before);assert.equal(f.p.data.uploadCaseIds.length,1)
 assert(!f.toast.some(x=>x.title==='已核实原图片'))
})
test('selected object replacement on the second read cannot release the case',async()=>{
 const f=pageFixture('detail'),id=await unknownFromPage(f);await select(f,id);const r=await finalRead(f);f.calls[2].resolve(ok([{...image,objectName:'private/order/42/different.png'}]));await flush();assert(!r.error);assert.equal(f.api.uploadCases().length,1)
})
test('invalid/missing image IDs produce no generated or undefined visible identity',async()=>{
 const f=pageFixture('complete'),id=await unknownFromPage(f);await select(f,id,[{...image,id:null},{...image,id:true},{...image,id:undefined}]);assert.equal(f.actions.length,0);assert.equal(f.api.uploadCases().length,1)
})
for(const kind of ['product','adjustment']){
 test(kind+' a draft switch before picker return discards the old choice without POST',async()=>{
  const f=pageFixture(kind);if(kind==='product')f.p.openAdd();f.p[f.pick]();const first=f.p._uploadDraftId;f.p.onUploadNewDraft()
  if(kind==='product'){assert.equal(f.modals.length,0,'busy chooser cannot switch image draft');assert.equal(f.p._uploadDraftId,first);f.p.closeEdit();f.p.openAdd()}
  else f.modals[0].success({confirm:true})
  await flush();assert.notEqual(f.p._uploadDraftId,first)
  f.picks[0].success({tempFilePaths:['/synthetic.png']});await flush();assert.equal(f.calls.length,0);assert.equal(f.stats.length,0)
 })
 test(kind+' late success from an abandoned draft cannot fill a newly chosen draft',async()=>{
  const f=pageFixture(kind);if(kind==='product')f.p.openAdd();f.p[f.pick]();f.picks[0].success({tempFilePaths:['/synthetic.png']});await flush();f.read();await flush()
  f.p.onUploadNewDraft()
  if(kind==='product'){assert.equal(f.modals.length,0,'in-flight upload cannot switch image draft');f.p.closeEdit();f.p.openAdd()}
  else f.modals[0].success({confirm:true})
  await flush();f.calls[0].resolve(ok('/synthetic-old'));await flush()
  assert.equal(f.p.data.uploading,false);if(kind==='product')assert.equal(f.p.data.editForm.imageUrl,'');else assert.deepEqual(f.p.data.evidenceList,[])
 })
 test(kind+' cancelled or stale new-draft prompt retains original draft and case',async()=>{
  const f=pageFixture(kind),id=await unknownFromPage(f),original=f.p._uploadDraftId;f.p.onUploadNewDraft();f.modals[0].success({confirm:false});await flush();assert.equal(f.p._uploadDraftId,original)
  f.p.onUploadNewDraft();f.p.onHide();f.modals[1].success({confirm:true});await flush();assert.equal(f.p._uploadDraftId,original);assert(f.api.uploadCases().some(x=>x.id===id))
 })
}
test('new customer uses its own draft automatically instead of borrowing a stale page pointer',async()=>{
 const f=pageFixture('adjustment'),id=await unknownFromPage(f),old=f.p._uploadDraftId;f.p.data.customerId=43;f.p._customerSeq++
 f.p.onChooseEvidence();assert.notEqual(f.p._uploadDraftId,old);f.picks[1].success({tempFilePaths:['/second.png']});await flush();f.read(f.stats.length-1);await flush();f.calls[1].resolve(ok('/synthetic-43'));await flush();assert(f.api.uploadCases().some(x=>x.id===id))
})
test('registry rejects guessed draft IDs without deleting any original unknown cases',async()=>{
 const f=pageFixture('product');await unknownFromPage(f);const before=f.storage.get(KEY)
 await assert.rejects(f.api.upload(f.args({intent:{kind:'product-image',id:'new',draftId:'draft-guessed'}})));assert.equal(f.storage.get(KEY),before);assert.equal(f.calls.length,1)
})
test('all four real page lifecycle hooks and new confirmation controls preserve current context',async()=>{
 for(const kind of ['complete','detail','product','adjustment']){const f=pageFixture(kind);assert.equal(typeof f.p.onHide,'function');assert.equal(typeof f.p.onUnload,'function');assert.equal(typeof f.p.onShow,'function')
  if(['complete','detail'].includes(kind)){const name=kind==='complete'?'complete':'detail';const w=fs.readFileSync(path.join(require('./cloud-upload.mock.test').ROOT,'miniapp-delivery/pages/order',name+'.wxml'),'utf8');assert(w.includes('bindtap="onUploadConfirmSelected"'));assert(w.includes('bindtap="onUploadPreviewSelected"'))}}
})
async function main(){let passed=0;const timer=setTimeout(()=>{console.error('PERSISTENCE_PAGE_TIMEOUT');process.exit(1)},45000)
 try{for(const t of tests){await t.run();passed++;console.log('PASS '+t.name)}fs.writeFileSync(path.join(__dirname,'persistence-page-results.json'),JSON.stringify({passed,failed:0,scope:'actual page entries with restart/storage/lifecycle/SDK mocks; no real credentials or upload'},null,2));console.log('AQUAFLOW_PERSISTENCE_PAGE_OK '+passed);console.log('AQUAFLOW_SUITE_OK '+passed)}finally{clearTimeout(timer)}}
if(require.main===module)main().catch(e=>{console.error(e.stack);process.exitCode=1})
module.exports={select,finalRead}
