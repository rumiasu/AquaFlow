'use strict'
const assert=require('assert'),fs=require('fs'),path=require('path')
const {pageFixture,unknownFromPage,event,image}=require('./page-recovery.test')
const {flush,ok,observe}=require('./cloud-upload.mock.test')
const {select,finalRead}=require('./persistence-page-boundaries.test')
const KEY='aq_delivery_uploadRecovery_v1'
function mutate(target,from,to){return(name,source)=>{if(name!==target)return source;assert(source.includes(from),'missing mutation anchor');return source.replace(from,to)}}
const controls=[
 {name:'remove-page-hidden-epoch-guard',change:mutate('upload-recovery-page','instance._uploadRecoveryActive && instance._uploadRecoveryEpoch === epoch &&',''),check:async change=>{
  const f=pageFixture('complete',new Map(),change),id=await unknownFromPage(f);await select(f,id);await finalRead(f);f.p.onHide();f.calls[2].resolve(ok([image]));await flush();assert.equal(f.api.uploadCases().length,1)}},
 {name:'retain-old-visible-state-after-read-error',change:mutate('upload-recovery-page',"page._uploadRecoverySelection = null; page.setData({ uploadRecoveryReadFailed: true, uploadRecoveryCases: [], uploadCaseIds: [], uploadRecoverySelection: null })","page.setData({ uploadRecoveryReadFailed: true })"),check:async change=>{
  const f=pageFixture('complete',new Map(),change);await unknownFromPage(f);f.wx.getStorageSync=()=>{throw Error('synthetic')};f.p.refreshUploadRecovery();assert.equal(f.p.data.uploadCaseIds.length,0)}},
 {name:'omit-durable-marker-before-dispatch',change:mutate('upload','stateStore.addCase(context.ownerKey, context.intentKey, durableCase)','void durableCase'),check:async change=>{
  const shared=new Map(),f=pageFixture('complete',shared,change);f.p.onAddFloorPhoto();f.picks[0].success({tempFilePaths:['/synthetic.png']});await flush();f.read();await flush();assert.equal(f.calls.length,1)
  const r=pageFixture('complete',shared,change);r.p.refreshUploadRecovery();assert.equal(r.p.data.uploadCaseIds.length,1)}},
 {name:'ignore-durable-cases-after-restart',change:mutate('upload','stateStore.cases(context.ownerKey, key).forEach','stateStore.cases(context.ownerKey, key).filter(() => false).forEach'),check:async change=>{
  const shared=new Map(),f=pageFixture('complete',shared,change);await unknownFromPage(f);const r=pageFixture('complete',shared,change);r.p.onAddFloorPhoto();r.picks[0].success({tempFilePaths:['/second.png']});await flush();assert.equal(r.stats.length,0)}},
 // Canonical storage validation now independently rejects a key with its draft component removed.
 // Mutate draft selection instead, so this negative still exercises an actual cross-draft lock.
 {name:'merge-independent-common-drafts',change:mutate('upload-state','let selectedId = selected || (!fresh && b.current[target])','let selectedId = selected || b.current[target]'),check:async change=>{
  const f=pageFixture('product',new Map(),change);await unknownFromPage(f);f.p.onUploadNewDraft();f.modals[0].success({confirm:true});await flush();f.p.uploadImage();f.picks[1].success({tempFilePaths:['/second.png']});await flush();assert.equal(f.stats.length,2)}},
 {name:'remove-account-station-storage-isolation',change:mutate('upload-state','return JSON.stringify([route, Number(user.staffId), Number(user.stationId)])','return JSON.stringify([route, 1, 1])'),check:async change=>{
  const shared=new Map(),a=pageFixture('product',shared,change);await unknownFromPage(a);const b=pageFixture('product',shared,change);b.switchAccount();b.p.refreshUploadRecovery();assert.equal(b.p.data.uploadCaseIds.length,0)}},
 {name:'clear-case-on-empty-recovery-read',change:mutate('upload',"if (!row || state.pending.size || state.unknown.get(proof.caseId) !== record\n    || (proof.objectName && row.objectName !== proof.objectName) || (proof.isCurrent && !proof.isCurrent()))","if (false)"),check:async change=>{
  const f=pageFixture('complete',new Map(),change),id=await unknownFromPage(f);await select(f,id);await finalRead(f);f.calls[2].resolve(ok([]));await flush();assert.equal(f.api.uploadCases().length,1)}},
 {name:'permit-corrupted-metadata-reset',change:mutate('upload-state','return validate(JSON.parse(JSON.stringify(value)))','return { version: 1, owners: {} }'),check:async change=>{
  const f=pageFixture('complete',new Map(),change);f.storage.set(KEY,'{"version":1,"owners":[]}');f.p.onAddFloorPhoto();f.picks[0].success({tempFilePaths:['/synthetic.png']});await flush();assert.equal(f.stats.length,0)}}
]
async function main(){const results=[],timer=setTimeout(()=>{console.error('PAGE_NEGATIVE_TIMEOUT');process.exit(1)},45000)
 try{for(const c of controls){let detected=false;try{await c.check(c.change)}catch(error){if(error.code==='ERR_ASSERTION')detected=true;else throw error}
  assert(detected,'mutation escaped the actual page check: '+c.name);results.push({name:c.name,detected:true});console.log('DETECTED '+c.name)}
  fs.writeFileSync(path.join(__dirname,'page-mutation-results.json'),JSON.stringify({passed:results.length,failed:0,results,scope:'in-memory mutations exercised through actual page entries; production bytes unchanged'},null,2));console.log('AQUAFLOW_PAGE_NEGATIVE_OK '+results.length);console.log('AQUAFLOW_SUITE_OK '+results.length)
 }finally{clearTimeout(timer)}}
main().catch(error=>{console.error(error.stack);process.exitCode=1})
