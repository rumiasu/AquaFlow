'use strict'
const assert = require('assert')
const { loadPage, createWx, createApp, armWatchdog } = require('./harness')
const done = armWatchdog(), tests = []
const test = (name, run) => tests.push({ name, run })
const event = id => ({ currentTarget: { dataset: { id, name: 'Synthetic '+id } } })
function pending() { let resolve, reject; const promise=new Promise((a,b)=>{resolve=a;reject=b});return {promise,resolve,reject} }
function fixture() {
  const reads=[], writes=[], wx=createWx()
  const app=createApp({ _loginGeneration:1, globalData:{ isLogin:true, accessToken:'synthetic-token',
    userInfo:{staffId:11,role:'STATION_MANAGER',stationId:11,bindStatus:'BOUND'} } })
  const page=loadPage('miniapp-delivery/pages/station-mgmt/customers/index.js',{wx,app,stubs:{
    'api/station-mgmt':{
      getOfflinePaymentSummary:id=>{const d=pending();reads.push({id,...d});return d.promise},
      updateOfflinePayment:(id,body)=>{const d=pending();writes.push({id,body,...d});return d.promise},
      getCustomers:async()=>({data:[]}),getEnterpriseApplies:async()=>({data:[]}),getEnterpriseConfig:async()=>({data:{enabled:false}})
    }
  }})
  // Match native setData dotted paths, as the existing enterprise projection tests do.
  page.setData=(patch,callback)=>{
    for(const [key,value] of Object.entries(patch)) {
      const parts=key.split('.');let target=page.data
      for(const part of parts.slice(0,-1))target=target[part]||(target[part]={})
      target[parts[parts.length-1]]=value
    }
    if(callback)callback()
  }
  let refreshes=0;page.loadData=async()=>{refreshes++}
  return {page,wx,app,reads,writes,refreshes:()=>refreshes}
}
function resolveRead(t,index,marker=1,enabled=0) {
  t.reads[index].resolve({data:{orderCount:marker,overdueCount:marker,overdueAmount:String(marker)+'.00',blockReason:'Synthetic '+marker,offlinePaymentEnabled:enabled}})
}
async function open(t,id=101,marker=1) {const p=t.page.onCod(event(id));resolveRead(t,t.reads.length-1,marker);await p}
test('A late read cannot replace newer B modal, facts or toggle',async()=>{
  const t=fixture(),a=t.page.onCod(event(101)),b=t.page.onCod(event(202))
  resolveRead(t,1,2,1);await b;resolveRead(t,0,1,0);await a
  assert.strictEqual(t.page.data.codModal.customerId,202);assert.strictEqual(t.page.data.codModal.customerName,'Synthetic 202')
  assert.strictEqual(t.page.data.codModal.orderCount,2);assert.strictEqual(t.page.data.codForm.enabled,true)
})
test('A-B-A keeps newest A generation rather than matching customer ID alone',async()=>{
  const t=fixture(),a1=t.page.onCod(event(101)),b=t.page.onCod(event(202)),a2=t.page.onCod(event(101))
  resolveRead(t,2,3,1);await a2;resolveRead(t,1,2);await b;resolveRead(t,0,1);await a1
  assert.strictEqual(t.page.data.codModal.customerId,101);assert.strictEqual(t.page.data.codModal.orderCount,3)
  assert.strictEqual(t.page.data.codForm.enabled,true)
})
test('opening a pending new target removes old editable modal and blocks save',async()=>{
  const t=fixture();await open(t)
  const b=t.page.onCod(event(202));assert.strictEqual(t.page.data.codModal.visible,false)
  await t.page.onCodSave();assert.strictEqual(t.writes.length,0);resolveRead(t,1,2);await b
})
test('close invalidates a pending read so it cannot reopen',async()=>{
  const t=fixture(),a=t.page.onCod(event(101));t.page.onCodClose();resolveRead(t,0);await a
  assert.strictEqual(t.page.data.codModal.visible,false)
})
for(const leave of ['onHide','onUnload'])test(leave+' invalidates pending read and closes visible COD',async()=>{
  const t=fixture();await open(t);const b=t.page.onCod(event(202));t.page[leave]();resolveRead(t,1,2);await b
  assert.strictEqual(t.page.data.codModal.visible,false)
})
test('hide then show/reopen does not revive pre-hide read',async()=>{
  const t=fixture(),a=t.page.onCod(event(101));t.page.onHide();await t.page.onShow()
  const b=t.page.onCod(event(202));resolveRead(t,1,2);await b;resolveRead(t,0);await a
  assert.strictEqual(t.page.data.codModal.customerId,202);assert.strictEqual(t.page.data.codModal.orderCount,2)
})
test('hidden page cannot start a new COD read',async()=>{
  const t=fixture();t.page.onHide();const task=t.page.onCod(event(101));assert.strictEqual(t.reads.length,0);await task
})
for(const field of ['staffId','stationId','role','bindStatus'])test('identity change '+field+' rejects a late read',async()=>{
  const t=fixture(),a=t.page.onCod(event(101));t.app.globalData.userInfo[field]='changed';resolveRead(t,0);await a
  assert.strictEqual(t.page.data.codModal.visible,false)
})
test('same identity after logout/relogin still rejects old login generation',async()=>{
  const t=fixture(),a=t.page.onCod(event(101));t.app._loginGeneration+=2;resolveRead(t,0);await a
  assert.strictEqual(t.page.data.codModal.visible,false)
})
test('ordinary token renewal keeps same-context COD read valid',async()=>{
  const t=fixture(),a=t.page.onCod(event(101));t.app.globalData.accessToken='synthetic-renewed';resolveRead(t,0,4,1);await a
  assert.strictEqual(t.page.data.codModal.visible,true);assert.strictEqual(t.page.data.codModal.orderCount,4)
})
test('obsolete GET errors do not show toast over current B',async()=>{
  const t=fixture(),a=t.page.onCod(event(101)),b=t.page.onCod(event(202));resolveRead(t,1,2);await b
  t.reads[0].reject(new Error('synthetic old failure'));await a
  assert.strictEqual(t.wx.__calls.toast.length,0);assert.strictEqual(t.page.data.codModal.customerId,202)
})
test('current GET failure remains visible as original failure toast',async()=>{
  const t=fixture(),a=t.page.onCod(event(101));t.reads[0].reject(new Error('synthetic current failure'));await a
  assert.ok(t.wx.__calls.toast.some(x=>x.title==='synthetic current failure'));assert.strictEqual(t.page.data.codModal.visible,false)
})
test('save after obsolete A arrives still submits current B rather than the old whole-modal replacement',async()=>{
  const t=fixture(),a=t.page.onCod(event(101)),b=t.page.onCod(event(202))
  resolveRead(t,1,2);await b;resolveRead(t,0,1);await a
  t.page.onCodToggle({detail:{value:true}});const save=t.page.onCodSave()
  assert.strictEqual(t.writes[0].id,202)
  t.writes[0].resolve({code:0});await save
})
test('B save stays bound to B while old A arrives, then closes and refreshes once',async()=>{
  const t=fixture(),a=t.page.onCod(event(101)),b=t.page.onCod(event(202));resolveRead(t,1,2);await b
  t.page.onCodToggle({detail:{value:true}});const save=t.page.onCodSave()
  resolveRead(t,0,1);await a
  assert.deepStrictEqual(t.writes.map(x=>({id:x.id,body:x.body})),[{id:202,body:{offlinePaymentEnabled:1}}])
  assert.strictEqual(t.page.data.codModal.customerId,202)
  t.writes[0].resolve({code:0});await save
  assert.strictEqual(t.page.data.codModal.visible,false);assert.strictEqual(t.refreshes(),1)
})
test('late A save success does not close or refresh B context',async()=>{
  const t=fixture();await open(t,101);const save=t.page.onCodSave();await open(t,202,2)
  t.writes[0].resolve({code:0});await save
  assert.strictEqual(t.page.data.codModal.visible,true);assert.strictEqual(t.page.data.codModal.customerId,202)
  assert.strictEqual(t.refreshes(),0);assert.strictEqual(t.wx.__calls.toast.length,0)
})
test('late save failure after close leaves no toast or modal revival',async()=>{
  const t=fixture();await open(t);const save=t.page.onCodSave();t.page.onCodClose()
  t.writes[0].reject(new Error('synthetic old save failure'));await save
  assert.strictEqual(t.wx.__calls.toast.length,0);assert.strictEqual(t.page.data.codModal.visible,false)
})
test('identity changed before save cannot submit old customer configuration',async()=>{
  const t=fixture();await open(t);t.app._loginGeneration++;const task=t.page.onCodSave();assert.strictEqual(t.writes.length,0);await task
})
test('closed modal cannot toggle or save retained facts',async()=>{
  const t=fixture();await open(t);t.page.onCodClose();t.page.onCodToggle({detail:{value:true}})
  const task=t.page.onCodSave();assert.strictEqual(t.writes.length,0);await task;assert.strictEqual(t.page.data.codForm.enabled,false)
})
test('same-context repeat save submits once',async()=>{
  const t=fixture();await open(t);const a=t.page.onCodSave(),b=t.page.onCodSave()
  assert.strictEqual(t.writes.length,1);t.writes[0].resolve({code:0});await Promise.all([a,b])
})
test('current business failure keeps draft editable and can retry exact target',async()=>{
  const t=fixture();await open(t);t.page.onCodToggle({detail:{value:true}});let save=t.page.onCodSave()
  t.writes[0].resolve({code:1,message:'synthetic refusal'});await save
  assert.strictEqual(t.page.data.codModal.visible,true);assert.strictEqual(t.page.data.codForm.enabled,true)
  assert.ok(t.wx.__calls.toast.some(x=>x.title==='synthetic refusal'))
  save=t.page.onCodSave();assert.strictEqual(t.writes[1].id,101);t.writes[1].resolve({code:0});await save
})
;(async()=>{let failed=0;for(const t of tests){try{await t.run();console.log('PASS '+t.name)}catch(e){failed++;console.error('FAIL '+t.name,e.stack)}}done();if(failed)process.exitCode=1;else console.log('AQUAFLOW_SUITE_OK '+tests.length)})()
