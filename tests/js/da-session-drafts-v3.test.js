const assert = require('assert'), fs = require('fs'), path = require('path'), vm = require('vm')
const { createApp, createWx, loadPage, armWatchdog, ROOT } = require('./harness')
const done = armWatchdog(15000), tick = () => new Promise(resolve => setImmediate(resolve))
const deferred = () => { let resolve, reject; const promise = new Promise((a,b) => { resolve=a; reject=b }); return {promise,resolve,reject} }
const makeApp = () => createApp({ _loginGeneration:1, globalData:{ isLogin:true, userInfo:{ staffId:5, stationId:1, role:'STATION_MANAGER', bindStatus:'BOUND' } } })
function pageFactory(route, app, wx, stubs) {
  let definition; const source=fs.readFileSync(path.join(ROOT,route),'utf8')
  const localRequire = name => {
    for (const [key,value] of Object.entries(stubs)) if (name.endsWith(key)) return value
    if (name.endsWith('utils/draft-guard')) return require(path.join(ROOT,'miniapp-delivery/utils/draft-guard.js'))
    throw Error('unexpected require '+name)
  }
  vm.runInNewContext(source, { require:localRequire, Page:value=>{definition=value}, getApp:()=>app, wx, console, WeakMap, Map, Set, JSON, Math, Date, String, Object, Array, Promise })
  return () => { const page={data:JSON.parse(JSON.stringify(definition.data||{}))}; page.setData=patch=>Object.assign(page.data,patch); for(const [name,value] of Object.entries(definition)) if(typeof value==='function') page[name]=value.bind(page); return page }
}
async function main() {
  {
    const app=makeApp(),wx=createWx(),api={getMyFeedbacks:async()=>({data:[]}),submitFeedback:async()=>({data:{}})}
    const make=pageFactory('miniapp-delivery/pages/report/index.js',app,wx,{'api/feedback':api,'utils/business-dialog':{showBusinessModal(){}}})
    const a=make();a.onLoad();await a.onShow();a.onSelectCategory({currentTarget:{dataset:{category:'feature'}}});a.onContentInput({detail:{value:'私密说明'}});a.onContactInput({detail:{value:'13800000000'}})
    const slot=a._categorySlot,stored=JSON.stringify([...slot.values.values()]);assert(stored.includes('feature'));assert(!stored.includes('私密说明'));assert(!stored.includes('13800000000'));assert.equal(wx.__storage.size,0)
    a.onUnload();app.globalData.userInfo={...app.globalData.userInfo};const b=make();b.onLoad();await b.onShow();assert.equal(b.data.categoryDraftAvailable,true);assert.equal(b.data.content,'');assert.equal(b.data.contact,'')
    b.onContentInput({detail:{value:'新的输入'}});b.onRestoreCategoryDraft();assert.equal(b.data.category,'feature');assert.equal(b.data.content,'新的输入')
    await b.onSubmit();assert.equal(b._categoryGuard.read(b._categoryToken).state,'empty');assert.equal(b.data.submitNotice,'反馈已提交。')
    console.log('PASS session category restore is explicit, private text stays out and success clears')
  }
  {
    const app=makeApp(),wx=createWx(),api={getMyFeedbacks:async()=>({data:[]}),submitFeedback:async()=>{throw Error('offline')}}
    const make=pageFactory('miniapp-delivery/pages/report/index.js',app,wx,{'api/feedback':api,'utils/business-dialog':{showBusinessModal(){}}}),a=make();a.onLoad();await a.onShow();a.onSelectCategory({currentTarget:{dataset:{category:'ui'}}});a.onContentInput({detail:{value:'保留当前文字'}});await a.onSubmit()
    assert(a.data.submitNotice.includes('尚未确认'));assert.equal(a.data.content,'保留当前文字');assert.equal(a._categoryGuard.read(a._categoryToken).state,'saved')
    const old=a._categorySlot;app._loginGeneration++;app.globalData.userInfo={staffId:6,stationId:2,role:'STATION_MANAGER'};await a.onShow();assert.equal(old.values.size,0);assert.equal(a.data.content,'');assert.equal(a.data.categoryDraftAvailable,false)
    console.log('PASS unknown result retains category and identity switch clears old session')
  }
  {
    const app=makeApp(),wx=createWx(),d=deferred(),api={getMyFeedbacks:async()=>({data:[]}),submitFeedback:()=>d.promise}
    const make=pageFactory('miniapp-delivery/pages/report/index.js',app,wx,{'api/feedback':api,'utils/business-dialog':{showBusinessModal(){}}}),a=make();a.onLoad();await a.onShow();a.onSelectCategory({currentTarget:{dataset:{category:'feature'}}});a.onContentInput({detail:{value:'旧内容'}});const pending=a.onSubmit();a.onUnload()
    const b=make();b.onLoad();await b.onShow();b.onDiscardCategoryDraft();b.onSelectCategory({currentTarget:{dataset:{category:'other'}}});d.resolve({data:{}});await pending
    assert.equal(b._categoryGuard.read(b._categoryToken).draft.category,'other');console.log('PASS late success does not clear a newer session draft')
  }
  {
    const app=makeApp(),wx=createWx(),api={getMyFeedbacks:async()=>({data:[]}),submitFeedback:async()=>({data:{}})}
    const make=pageFactory('miniapp-delivery/pages/report/index.js',app,wx,{'api/feedback':api,'utils/business-dialog':{showBusinessModal(){}}}),a=make();a.onLoad();await a.onShow();a._categorySlot.storage.setStorageSync=()=>{throw Error('storage')};a.onSelectCategory({currentTarget:{dataset:{category:'ui'}}});assert(a.data.categoryDraftNotice.includes('尚未确认'));assert.equal(a.data.categoryDraftBlocked,true)
    console.log('PASS draft save failure is visible and not treated as saved')
  }
  {
    const app=makeApp(),wx=createWx(),sent=[];let first=true
    const api={options:async()=>({data:{intakeEnabled:true,requestTypes:[{value:'QUERY',label:'查询'}]}}),mine:async()=>({data:{items:[]}}),submit:async payload=>{sent.push({...payload});if(first){first=false;throw Error('unknown')}return {data:{id:8,notice:'已登记'}}}}
    const make=pageFactory('miniapp-delivery/pages/mine/account-data/index.js',app,wx,{'api/account-data-requests':api}),a=make();a.onShow();await tick();a.onType({detail:{value:'0'}});a.onNote({detail:{value:'原说明'}});await a.onSubmit();assert(a.data.pending);a.onUnload()
    const b=make();b.onShow();await tick();assert.equal(b.data.pending,true);assert.equal(b.data.note,'原说明');await b.onSubmit();assert.deepStrictEqual(sent[0],sent[1]);assert.equal(b.data.pending,false);assert.equal(b.data.note,'');assert.equal(wx.__storage.size,0)
    console.log('PASS unknown account request survives page recreation with original key and body')
  }
  {
    const app=makeApp(),wx=createWx(),d=deferred(),sent=[];const api={options:async()=>({data:{intakeEnabled:true,requestTypes:[{value:'QUERY',label:'查询'}]}}),mine:async()=>({data:{items:[]}}),submit:p=>{sent.push(p);return d.promise}}
    const make=pageFactory('miniapp-delivery/pages/mine/account-data/index.js',app,wx,{'api/account-data-requests':api}),a=make();a.onShow();await tick();a.onType({detail:{value:'0'}});a.onNote({detail:{value:'未确认'}});const first=a.onSubmit();a.onUnload();const b=make();b.onShow();await tick();assert.equal(b.data.submitting,true);await b.onSubmit();assert.equal(sent.length,1);d.resolve({data:{id:9,notice:'已登记'}});await first;await tick();assert.equal(b.data.pending,false);assert.equal(b.data.note,'');assert.equal(b.data.activeView,'records')
    console.log('PASS in-flight account request remains single flight after recreation and success clears')
  }
  {
    const app=makeApp(),wx=createWx(),api={options:async()=>({data:{intakeEnabled:true,requestTypes:[{value:'QUERY',label:'查询'}]}}),mine:async()=>({data:{items:[]}}),submit:async()=>{throw Error('unknown')}}
    const make=pageFactory('miniapp-delivery/pages/mine/account-data/index.js',app,wx,{'api/account-data-requests':api}),a=make();a.onShow();await tick();a.onType({detail:{value:'0'}});a.onNote({detail:{value:'旧身份'}});await a.onSubmit();a.onUnload();app._loginGeneration++;app.globalData.userInfo={staffId:6,stationId:2,role:'STATION_MANAGER'};const b=make();b.onShow();await tick();assert.equal(b.data.pending,false);assert.equal(b.data.note,'')
    console.log('PASS account original request is isolated from a different identity')
  }
  {
    const app=makeApp(),wx=createWx(),writes=[];const page=loadPage('miniapp-delivery/pages/station-mgmt/customer-feedback/index.js',{app,wx,stubs:{'api/feedback':{closeRefundDispute:async p=>writes.push(p)}}})
    page.setData({selectedDispute:{refundType:'BARREL_RETURN',refundId:12,canClose:true},disputeReady:true,disputeResult:'尚未提交',disputeSubmitting:false});page.onReturnDispute();assert.equal(writes.length,0);assert.equal(page.data.selectedDispute,null)
    console.log('PASS return from dispute never invokes case closure')
  }
  {
    const app=makeApp(),wx=createWx(),api={getMyFeedbacks:async()=>({data:[]}),submitFeedback:async()=>({data:{}})}
    const make=pageFactory('miniapp-delivery/pages/report/index.js',app,wx,{'api/feedback':api,'utils/business-dialog':{showBusinessModal(){}}}),a=make();a.onLoad();await a.onShow();a.onSelectCategory({currentTarget:{dataset:{category:'feature'}}});const old=a._categorySlot;app._loginGeneration++;app.globalData.isLogin=false;app.globalData.userInfo=null;a.onUnload();assert.equal(old.values.size,0)
    console.log('PASS active page clears session category on logout')
  }
  {
    const app=makeApp(),wx=createWx(),api={options:async()=>({data:{intakeEnabled:true,requestTypes:[{value:'QUERY',label:'查询'}]}}),mine:async()=>({data:{items:[]}}),submit:async()=>{throw Error('unknown')}}
    let enabled=0,disabled=0;wx.enableAlertBeforeUnload=()=>enabled++;wx.disableAlertBeforeUnload=()=>disabled++
    const make=pageFactory('miniapp-delivery/pages/mine/account-data/index.js',app,wx,{'api/account-data-requests':api}),a=make();a.onShow();await tick();a.onType({detail:{value:'0'}});a.onNote({detail:{value:'不写入设备'}});await a.onSubmit();const old=a._requestSlot;assert(enabled>0);app._loginGeneration++;app.globalData.userInfo={staffId:6,stationId:2,role:'STATION_MANAGER'};a.onUnload();assert.equal(old.submission,null);assert(disabled>0);assert.equal(wx.__storage.size,0)
    console.log('PASS active account page clears original on identity exit and warns before leaving')
  }
  {
    const app=makeApp(),wx=createWx(),sent=[];const api={options:async()=>({data:{intakeEnabled:true,requestTypes:[{value:'QUERY',label:'查询'}]}}),mine:async()=>({data:{items:[]}}),submit:async p=>{sent.push(p);throw Error('unknown')}}
    const make=pageFactory('miniapp-delivery/pages/mine/account-data/index.js',app,wx,{'api/account-data-requests':api}),a=make();a.onShow();await tick();a.onType({detail:{value:'0'}});a.onNote({detail:{value:'原说明'}});await a.onSubmit();a.onUnload();app.globalData.userInfo={...app.globalData.userInfo};const b=make();b.onShow();await tick();assert.equal(b.data.pending,true);assert.equal(b.data.originalUnavailable,true);assert.equal(b.data.note,'');assert(b.data.submitNotice.includes('不要重复'));await b.onSubmit();assert.equal(sent.length,1)
    console.log('PASS identity-object replacement blocks duplicate when sensitive original cannot be recovered')
  }
  done();console.log('AQUAFLOW_SUITE_OK 11')
}
main().catch(error=>{done();console.error(error);process.exitCode=1})
