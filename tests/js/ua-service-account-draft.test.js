'use strict'
const assert = require('assert')
const { loadPage, createWx, createApp, armWatchdog } = require('./harness')
const done = armWatchdog(15000), tick = () => new Promise(r => setImmediate(r))
const cases=[]; const test=(name,run)=>cases.push({name,run})
function app(id=7) { return createApp({globalData:{isLogin:true,customerId:id,accessToken:'test-token'}}) }
function account(wx, a, overrides={}) {
 const calls=[]; const api=Object.assign({options:async()=>({data:{intakeEnabled:true,closureCheckSupported:true,requestTypes:[{value:'CLOSURE',label:'注销'}]}}),mine:async()=>({data:{items:[],nextBeforeId:null}}),submit:async p=>{calls.push({...p});return {data:{id:5,notice:'已登记'}}}},overrides)
 const p=loadPage('miniapp-user/pages/mine/account-data/index.js',{wx,app:a,stubs:{'api/account-data-requests':api}})
 return {p,calls,api}
}
async function show(p) {p.onShow();await tick();await tick()}
const type=p=>p.onType({detail:{value:'0'}})
const note=(p,value)=>p.onNote({detail:{value}})
test('account draft survives unload without native warning; explicit recovery cannot overwrite newer input',async()=>{
 const wx=createWx(),a=app(),x=account(wx,a);await show(x.p);type(x.p);note(x.p,'原说明');x.p.onUnload()
 const y=account(wx,a);await show(y.p);assert.equal(y.p.data.draftAvailable,true);assert.equal(y.p.data.note,'')
 note(y.p,'新说明');assert.match(y.p.data.draftNotice,/尚未确认保存/);wx.__modalAutoConfirm=false;y.p.onRestoreDraft();assert.equal(y.p.data.note,'新说明')
 wx.__modalAutoConfirm=true;y.p.onRestoreDraft();assert.equal(y.p.data.note,'原说明');assert.equal(y.p.data.selectedLabel,'注销')
})
test('account failed verification sends nothing; retry keeps one original key',async()=>{
 const wx=createWx(),a=app(),x=account(wx,a);await show(x.p);type(x.p);note(x.p,'说明')
 const set=wx.setStorageSync;wx.setStorageSync=(k,v)=>{if(!k.startsWith('account-data-intent:'))set(k,v)}
 await x.p.onSubmit();assert.equal(x.calls.length,0);assert.equal(x.p.data.pending,true);const key=x.p._submission.idempotencyKey
 wx.setStorageSync=set;await x.p.onSubmit();assert.equal(x.calls.length,1);assert.equal(x.calls[0].idempotencyKey,key)
})
test('unknown account outcome persists exact original key across unload and isolates actor',async()=>{
 const wx=createWx(),a=app(),sent=[];const x=account(wx,a,{submit:async p=>{sent.push({...p});throw Error('unknown')}})
 await show(x.p);type(x.p);note(x.p,'原说明');await x.p.onSubmit();assert.equal(x.p.data.pending,true);x.p.onUnload()
 const other=account(wx,app(8));await show(other.p);assert.equal(other.p.data.pending,false);assert.equal(other.p.data.draftAvailable,false)
 const y=account(wx,a,{submit:async p=>{sent.push({...p});return {data:{id:9,notice:'已登记'}}}});await show(y.p);assert.equal(y.p.data.pending,true);assert.equal(y.p.data.selectedLabel,'注销');await y.p.onSubmit();assert.equal(sent.length,2);assert.deepStrictEqual(sent[1],sent[0]);assert.equal(y.p.data.pending,false)
})
test('unreadable account intent blocks new writes rather than pretending no record',async()=>{
 const wx=createWx(),a=app();wx.setStorageSync('account-data-intent:customer:7',{version:1,actor:7,payload:{}})
 const x=account(wx,a);await show(x.p);assert.equal(x.p.data.intentBlocked,true);type(x.p);note(x.p,'内容');await x.p.onSubmit();assert.equal(x.calls.length,0)
})
function service(wx,a,ref) {
 const api={getMyFeedback:async()=>({data:[]}),getRefundDisputes:async()=>({data:[]}),getRefundNotes:async()=>({data:{refundType:'ORDER',refundId:3,objectText:'退款',notes:[]}}),getRefundOptions:async()=>({data:{options:[],hasMore:false}}),submitFeedback:async()=>({data:null})}
 const p=loadPage('miniapp-user/pages/service/index.js',{wx,app:a,stubs:{'api/feedback':api,'api/station':{getStationPublicPhone:async()=>({code:0,data:{phone:''}})}}});p.onLoad(ref||{});return p
}
test('service draft is isolated by station, explicit restore preserves new input until confirmation',async()=>{
 const wx=createWx(),a=app();wx.setStorageSync('selectedStation',{id:11});const x=service(wx,a);await show(x);x.onContentInput({detail:{value:'原反馈'}});x.onHide()
 wx.setStorageSync('selectedStation',{id:12});await show(x);assert.equal(x.data.draftContextMismatch,true);assert.match(x.data.draftNotice,/变化/)
 wx.setStorageSync('selectedStation',{id:11});await show(x);assert.equal(x.data.draftContextMismatch,false);x.onUnload()
 const y=service(wx,a);await show(y);assert.equal(y.data.draftAvailable,true);y.onContentInput({detail:{value:'新反馈'}});wx.__modalAutoConfirm=false;y.onRestoreDraft();assert.equal(y.data.content,'新反馈');wx.__modalAutoConfirm=true;y.onRestoreDraft();assert.equal(y.data.content,'原反馈')
 const other=service(wx,app(8));await show(other);assert.equal(other.data.draftAvailable,false)
})
test('service refund object drafts do not cross references or override pending intent',async()=>{
 const wx=createWx(),a=app(),x=service(wx,a,{refundType:'ORDER',refundId:3});await show(x);x.onContentInput({detail:{value:'退款说明'}});x.onUnload()
 const y=service(wx,a,{refundType:'ORDER',refundId:3});await show(y);assert.equal(y.data.draftAvailable,true);y.setData({pendingRefundNote:true});y.onRestoreDraft();assert.equal(y.data.content,'')
 const z=service(wx,a,{refundType:'ORDER',refundId:4});await show(z);assert.equal(z.data.draftAvailable,false)
})
test('service silent storage failure reports unconfirmed draft instead of claiming saved',async()=>{
 const wx=createWx(),a=app();wx.setStorageSync('selectedStation',{id:11});const x=service(wx,a);await show(x)
 const set=wx.setStorageSync;wx.setStorageSync=(k,v)=>{if(!k.startsWith('aquaflow:ui-draft:v1:'))set(k,v)}
 x.onContentInput({detail:{value:'重要输入'}});assert.equal(x.data.content,'重要输入');assert.match(x.data.draftNotice,/尚未确认保存/)
})
test('same pending refund note is retried under original key and matching stale draft is cleared only on confirmed result',async()=>{
 const wx=createWx(),a=app(),sent=[];let fail=true
 const api={getMyFeedback:async()=>({data:[]}),getRefundDisputes:async()=>({data:[]}),getRefundNotes:async()=>({data:{refundType:'ORDER',refundId:3,objectText:'退款',notes:[]}}),appendRefundNote:async p=>{sent.push({...p});if(fail)throw Error('unknown');return {data:{id:9,refundType:'ORDER',refundId:3}}}}
 function page(){const p=loadPage('miniapp-user/pages/service/index.js',{wx,app:a,stubs:{'api/feedback':api}});p.onLoad({refundType:'ORDER',refundId:3});return p}
 const x=page();await show(x);x.onContentInput({detail:{value:'退款说明'}});await x.onSubmit();assert.equal(x.data.pendingRefundNote,true);x.onUnload()
 fail=false;const y=page();await show(y);assert.equal(y.data.pendingRefundNote,true);await y.onRetryOriginalNote();assert.equal(sent.length,2);assert.equal(sent[0].idempotencyKey,sent[1].idempotencyKey);assert.equal(y.data.pendingRefundNote,false)
 assert.equal([...wx.__storage.keys()].filter(k=>k.startsWith('aquaflow:ui-draft:v1:')).length,0)
})
test('late account response after identity switch cannot clear prior intent or reuse its key for another actor',async()=>{
 const wx=createWx(),a=app(),sent=[];let resolve;const x=account(wx,a,{submit:p=>{sent.push({...p});return new Promise(r=>{resolve=r})}})
 await show(x.p);type(x.p);note(x.p,'原人资料');const request=x.p.onSubmit();assert.equal(sent.length,1);x.p.onHide();a.globalData.customerId=8;await show(x.p);resolve({data:{id:8}});await request
 assert.equal(x.p.data.pending,false);assert(wx.__storage.has('account-data-intent:customer:7'));assert(!wx.__storage.has('account-data-intent:customer:8'))
 a.globalData.customerId=7;const y=account(wx,a,{submit:async p=>{sent.push({...p});return {data:{id:8}}}});await show(y.p);assert.equal(y.p.data.pending,true);await y.p.onSubmit();assert.equal(sent[1].idempotencyKey,sent[0].idempotencyKey)
})
test('delayed account restore after hide cannot overwrite current input',async()=>{
 const wx=createWx(),a=app(),x=account(wx,a);await show(x.p);type(x.p);note(x.p,'旧草稿');x.p.onUnload()
 const y=account(wx,a);await show(y.p);note(y.p,'当前输入');let modal;wx.showModal=o=>{modal=o};y.p.onRestoreDraft();y.p.onHide();modal.success({confirm:true});assert.equal(y.p.data.note,'当前输入')
})
test('delayed service restore after hide and hidden input events do not alter saved draft',async()=>{
 const wx=createWx(),a=app();wx.setStorageSync('selectedStation',{id:11});const x=service(wx,a);await show(x);x.onContentInput({detail:{value:'旧草稿'}});x.onUnload()
 const y=service(wx,a);await show(y);y.onContentInput({detail:{value:'当前输入'}});let modal;wx.showModal=o=>{modal=o};y.onRestoreDraft();y.onHide();modal.success({confirm:true});y.onContentInput({detail:{value:'迟到输入'}});assert.equal(y.data.content,'当前输入')
})
test('concurrent account windows cannot replace the original in-flight request',async()=>{
 const wx=createWx(),a=app(),sent=[];let resolve;const submit=p=>{sent.push({...p});return new Promise(r=>{resolve=r})}
 const x=account(wx,a,{submit}),y=account(wx,a,{submit});await show(x.p);await show(y.p);type(x.p);note(x.p,'第一份');const flight=x.p.onSubmit();type(y.p);note(y.p,'第二份');await y.p.onSubmit()
 assert.equal(sent.length,1);assert.equal(y.p.data.intentBlocked,true);assert.equal(wx.getStorageSync('account-data-intent:customer:7').payload.note,'第一份')
 resolve({data:{id:6}});await flight
})
test('concurrent service draft creation cannot overwrite another window',async()=>{
 const wx=createWx(),a=app();wx.setStorageSync('selectedStation',{id:11});const x=service(wx,a),y=service(wx,a);await show(x);await show(y)
 x.onContentInput({detail:{value:'第一份'}});y.onContentInput({detail:{value:'第二份'}});assert.equal(y.data.draftAvailable,true);assert.match(y.data.draftNotice,/当前输入尚未确认保存/)
 const key=[...wx.__storage.keys()].find(k=>k.startsWith('aquaflow:ui-draft:v1:'));assert.equal(wx.getStorageSync(key).draft.content,'第一份')
})
test('stale draft cannot be restored or discarded after another window changes it',async()=>{
 const wx=createWx(),a=app();wx.setStorageSync('selectedStation',{id:11});const x=service(wx,a);await show(x);x.onContentInput({detail:{value:'原内容'}})
 const y=service(wx,a);await show(y);assert.equal(y.data.draftAvailable,true);x.onContentInput({detail:{value:'其他窗口的新内容'}})
 y.onRestoreDraft();assert.equal(y.data.content,'');assert.match(y.data.draftNotice,/已变化/);y.onDiscardDraft();assert.equal([...wx.__storage.values()].find(v=>v && v.version===1 && v.draft).draft.content,'其他窗口的新内容')
})
test('invalid account receipt never clears the original intent',async()=>{
 const wx=createWx(),a=app(),x=account(wx,a,{submit:async()=>({data:{id:'not-an-id'}})});await show(x.p);type(x.p);note(x.p,'说明');await x.p.onSubmit();assert.equal(x.p.data.pending,true);assert(wx.__storage.has('account-data-intent:customer:7'));assert.match(x.p.data.submitNotice,/尚未确认/)
})
test('service restore asks before replacing category-only or anonymous choices',async()=>{
 const wx=createWx(),a=app();wx.setStorageSync('selectedStation',{id:11});const x=service(wx,a);await show(x);x.onContentInput({detail:{value:'旧反馈'}});x.onUnload()
 const y=service(wx,a);await show(y);y.onSelectCategory({currentTarget:{dataset:{category:'其他'}}});wx.__modalAutoConfirm=false;y.onRestoreDraft()
 assert.equal(y.data.category,'其他');assert.equal(y.data.content,'');assert.equal(y.data.draftAvailable,true)
})
// RC3: exercise the actual shared component, not a native-modal stub that has a four-character limit.
function serviceDialog(p, wx) {
 const fs=require('fs'),path=require('path'),vm=require('vm');let definition
 vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../../miniapp-user/components/BusinessDialog/index.js'),'utf8'),{Component:value=>{definition=value},wx,Promise})
 const d={data:{...definition.data},setData(patch){Object.assign(this.data,patch)}}
 for(const [name,fn] of Object.entries(definition.methods))d[name]=fn.bind(d)
 definition.lifetimes.attached.call(d);p.selectComponent=id=>id==='#business-dialog'?d:null;global.getCurrentPages=()=>[p]
 return d
}
function fillServiceDraft(p) {
 p.onContentInput({detail:{value:'尚未提交的反馈'}});p.onContactInput({detail:{value:'本次联系方式'}});p.onDisputeReason({detail:{value:'本次争议理由'}})
}
test('RC3 shared switch cancel preserves all text, original association and owned draft',async()=>{
 const wx=createWx(),a=app(),p=service(wx,a,{refundType:'ORDER',refundId:3});await show(p);fillServiceDraft(p)
 const d=serviceDialog(p,wx),before={content:p.data.content,contact:p.data.contact,disputeReason:p.data.disputeReason,ref:{...p.data.refundRef}},stored=JSON.stringify([...wx.__storage.entries()]);let reads=0;p.loadRefundThread=async()=>{reads++}
 const pending=p.openRefundRef('ORDER',4);assert.equal(d.data.confirmText,'放弃并切换');assert.equal(d.data.cancelText,'继续填写');d.onCancel();await pending
 assert.deepStrictEqual({content:p.data.content,contact:p.data.contact,disputeReason:p.data.disputeReason,ref:{...p.data.refundRef}},before)
 assert.equal(JSON.stringify([...wx.__storage.entries()]),stored);assert.equal(reads,0);assert.equal(wx.__calls.modal.length,0)
})
test('RC3 shared switch confirm clears only its draft and reads the selected refund once',async()=>{
 const wx=createWx(),a=app(),p=service(wx,a,{refundType:'ORDER',refundId:3});await show(p);fillServiceDraft(p)
 const own=p._draftToken.key,foreignScope=JSON.parse(decodeURIComponent(own.slice('aquaflow:ui-draft:v1:'.length)));foreignScope[1]='8'
 const foreign='aquaflow:ui-draft:v1:'+encodeURIComponent(JSON.stringify(foreignScope)),foreignValue={version:1,scopeKey:foreign,draft:{content:'其他身份原草稿'}}
 wx.setStorageSync(foreign,foreignValue);wx.setStorageSync('refund-note-intent:unrelated',{key:'保留原业务请求'})
 const d=serviceDialog(p,wx);let reads=0;p.loadRefundThread=async()=>{reads++}
 const pending=p.openRefundRef('ORDER',4);assert.equal(d.data.confirmText,'放弃并切换');d.onConfirm();await pending;await tick()
 assert.equal(p.data.content,'');assert.equal(p.data.contact,'');assert.equal(p.data.disputeReason,'');assert.deepStrictEqual(p.data.refundRef,{refundType:'ORDER',refundId:4})
 assert.equal(wx.getStorageSync(own),'');assert.deepStrictEqual(wx.getStorageSync(foreign),foreignValue);assert.deepStrictEqual(wx.getStorageSync('refund-note-intent:unrelated'),{key:'保留原业务请求'})
 assert.equal(reads,1);assert.equal(d._queue.length,0);assert.equal(wx.__calls.modal.length,0);assert.equal(wx.__calls.request.length,0)
})
test('RC3 native switch fallback stays within four characters and cancellation preserves input',async()=>{
 const wx=createWx(),p=service(wx,app(),{refundType:'ORDER',refundId:3});await show(p);fillServiceDraft(p);wx.__modalAutoConfirm=false
 p.openRefundRef('ORDER',4);const modal=wx.__calls.modal.at(-1);assert.equal(modal.confirmText,'确认切换');assert.equal(modal.cancelText,'继续填写')
 assert.equal(p.data.content,'尚未提交的反馈');assert.equal(p.data.contact,'本次联系方式');assert.equal(p.data.disputeReason,'本次争议理由');assert.equal(p.data.refundRef.refundId,3)
})
;(async()=>{let failed=0;try{for(const t of cases){try{await t.run();console.log('PASS '+t.name)}catch(e){failed++;console.error('FAIL '+t.name,e)}}if(failed)process.exitCode=1;else console.log('AQUAFLOW_SUITE_OK '+cases.length)}finally{done()}})()
