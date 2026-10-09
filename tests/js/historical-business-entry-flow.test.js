const assert = require('assert')
const fs = require('fs')
const path = require('path')
const { loadPage, createWx, createApp, armWatchdog, ROOT } = require('./harness')
const { parseWxml, renderElements } = require('./wxml-tree')
const done = armWatchdog(20000)
let passed = 0
const ok = data => ({ code: 0, data })
const event = (id, scope) => ({ currentTarget: { dataset: { id, scope } }, detail: { value: String(id || '') } })
const deferred = () => { let resolve, reject; const promise = new Promise((a,b) => { resolve=a;reject=b }); return { promise,resolve,reject } }
const manager = () => createApp({ globalData: { isLogin: true, userInfo: { staffId: 17, stationId: 1, role: 'STATION_MANAGER' } } })
function pageData(rows, args, key) {
  const scope = args.orderId ? 'LOOKUP' : args.scope
  const found = rows.filter(r => args.orderId ? Number(r[key]) === Number(args.orderId) :
    (scope === 'ALL' || r.active) && (!args.beforeId || Number(r[key]) < Number(args.beforeId))).sort((a,b) => b[key]-a[key])
  const items = found.slice(0,50)
  return ok({ stationId: 1, scope, limit: 50, items, nextBeforeId: found.length > 50 ? items.at(-1)[key] : null })
}
function returns(extra={}) {
  const wx=createWx(),app=manager(),reads=[]
  const original={ id:1,type:2,status:1,stationId:1,customerId:7,productId:10,quantity:1,depositRefund:30,active:true,
    returnDetail:{ status:'APPROVED',arrangementVersion:1,requiredBarrels:0,customerConfirmationRequired:false } }
  const history=[original,...Array.from({length:501},(_,i)=>({id:i+2,type:8,stationId:1,active:false}))]
  const rows=extra.rows || [original]
  const api={ getReturnApplications:async args=>{reads.push(args);return pageData(rows,args,'id')},...extra.api }
  const station={ getAllBarrelRecords:async()=>ok(history.slice(-500).reverse()),getRefundUndelivered:async()=>ok({records:[],count:0}),
    getBarrelRefundEligibility:async id=>ok({recordId:id,available:false,detailStatus:'APPROVED',reason:'先办理交接'}),...extra.station }
  const page=loadPage('miniapp-delivery/pages/station-mgmt/barrel-return/index.js',{wx,app,stubs:{'api/station-mgmt':station,'api/business-rules':api,
    'utils/pending-reminder':{getPendingReturnRecord:async id=>{const row=rows.find(r=>String(r.id)===String(id));return row?ok(row):{code:1,message:'未找到本站申请'}},syncPendingReminder:async()=>{}}}})
  return {page,wx,app,original,rows,reads}
}
function refusals(extra={}) {
  const wx=extra.wx || createWx(),app=manager(),reads=[],writes=[]
  const original={order_id:1,stationId:1,active:true,version:0,canRevoke:true,canRelease:true,canFreeze:false}
  const rows=extra.rows || [original,...Array.from({length:201},(_,i)=>({order_id:i+2,active:false,version:2,canRevoke:false,canRelease:false}))]
  const waiting={schemaAvailable:true,limit:200,counts:{waitingStock:0,returnsTotal:0,recoveriesTotal:0,barrelsTotal:0},stock:[],returns:[],recoveries:[],barrels:[]}
  const api={getWaiting:async()=>ok(waiting),getTicketExitBatches:async()=>ok([]),
    getRefusals:async args=>{reads.push(args);return args?pageData(rows,args,'order_id'):ok(rows.slice(-200).reverse())},
    refusalHistory:async id=>ok([{id:7,reason:'原案件 '+id}]),revokeRefusal:async(id,body)=>{writes.push({id,body});return ok({id:9,orderId:id,action:'REVOKE'})},...extra.api}
  const page=loadPage('miniapp-delivery/pages/station-mgmt/business-waiting/index.js',{wx,app,stubs:{'api/business-rules':api,'utils/pending-reminder':{syncPendingReminder:async()=>{}}}})
  return {page,wx,app,original,rows,reads,writes,api}
}
async function test(name,run) {if(process.argv[2] && !name.includes(process.argv[2]))return;await run();passed++;console.log('PASS '+name)}
async function main() {
  await test('approved original remains reachable after 501 unrelated barrel records',async()=>{
    const t=returns();await t.page.onShow();assert(t.page.data.list.some(r=>r.id===1),'批准未交接申请不能被500条全部类型流水挤出')
    await t.page.onSelectRecord(event(1));assert.equal(t.page.data.selected.primaryAction,'receive');assert.equal(t.page.data.selected.requiredText,'无需实际交桶')
    assert.equal(t.reads[0].scope,'ACTIVE')
  })
  await test('effective refusal survives 201 closed cases and still opens original resolution',async()=>{
    const t=refusals();await t.page.onShow();assert(t.page.data.refusals.some(r=>r.order_id===1),'有效拒付不能被200条已结历史挤出')
    t.page.onOpenRefusalResolution({currentTarget:{dataset:{id:1,action:'REVOKE'}}});assert.equal(t.page.data.selectedRefusal.order_id,1)
  })
  await test('both history lists traverse every old row with stable cursors and retry failed next page',async()=>{
    const returnRows=Array.from({length:121},(_,i)=>({id:i+1,type:2,stationId:1,status:4,active:false,returnDetail:{status:'REJECTED'}}))
    const t=returns({rows:returnRows});await t.page.onShow();assert.equal(t.page.data.list.length,0);assert.equal(t.page.data.recordsReady,true)
    await t.page.onScopeChange(event(null,'ALL'));assert.equal(t.page.data.list.length,50)
    let fail=true;const original=t.page.data.nextBeforeId
    // 网络模块是页面读取的真实入口；失败不改变游标或把已读历史变成空态。
    const s=returns({rows:returnRows,api:{getReturnApplications:async args=>{if(args.beforeId && fail)throw Error('offline');return pageData(returnRows,args,'id')}}})
    await s.page.onShow();await s.page.onScopeChange(event(null,'ALL'));await s.page.onMoreRecords();assert.equal(s.page.data.nextBeforeId,original);assert.equal(s.page.data.list.length,50);assert(s.page.data.moreError)
    fail=false;await s.page.onMoreRecords();await s.page.onMoreRecords();assert.equal(s.page.data.list.length,121);assert.equal(new Set(s.page.data.list.map(r=>r.id)).size,121);assert.equal(s.page.data.nextBeforeId,null)
    const f=refusals();await f.page.onShow();await f.page.onRefusalScope(event(null,'ALL'));while(f.page.data.refusalNextBeforeId)await f.page.onMoreRefusals()
    assert.equal(f.page.data.refusals.length,202);assert.equal(new Set(f.page.data.refusals.map(r=>r.order_id)).size,202)
  })
  await test('ID lookup opens old completed evidence and stays explicit on lookup failure',async()=>{
    const t=returns();await t.page.onShow();t.page.onRecordSearchInput(event(1));await t.page.onRecordSearch();assert.equal(t.page.data.selected.id,1)
    t.page.onRecordSearchInput(event(999));await t.page.onRecordSearch();assert.equal(t.page.data.recordsReady,false);assert(t.page.data.loadError);assert.equal(t.page.data.selected,null)
    await t.page.onClearRecordSearch();assert.equal(t.page.data.recordsReady,true)
    const f=refusals();await f.page.onShow();f.page.onRefusalSearchInput(event(202));await f.page.onRefusalSearch();assert.deepEqual(f.page.data.refusals.map(r=>r.order_id),[202])
    await f.page.onRefusalHistory(event(202));assert.equal(f.page.data.refusalHistory[0].id,7)
  })
  await test('unknown refusal result reentry locates closed original by ID and retries same request',async()=>{
    let first=true;const t=refusals({api:{revokeRefusal:async(id,body)=>{t.writes.push({id,body});if(first){first=false;t.original.active=false;t.original.canRevoke=false;throw Error('lost receipt')}return ok({id:9,orderId:id,action:'REVOKE'})}}})
    await t.page.onShow();t.page.onOpenRefusalResolution({currentTarget:{dataset:{id:1,action:'REVOKE'}}});t.page.onRefusalReason({detail:{value:'核实为误判'}});await t.page.onSubmitRefusalResolution()
    const original=t.writes[0].body;t.page.onHide();await t.page.onShow();assert.equal(t.page.data.refusals[0].order_id,1);assert.equal(t.page.data.refusals[0].retryRevoke,true)
    t.page.onOpenRefusalResolution({currentTarget:{dataset:{id:1,action:'REVOKE'}}});await t.page.onRetryRefusalResolution();assert.deepEqual(t.writes[1].body,original)
  })
  await test('filter changes and changed identity discard late pages and malformed envelopes are not zero',async()=>{
    const d=deferred(),t=returns({api:{getReturnApplications:args=>args.scope==='ALL'?pageData([],args,'id'):d.promise}})
    const old=t.page.onShow();await t.page.onScopeChange(event(null,'ALL'));d.resolve(pageData([t.original],{scope:'ACTIVE'},'id'));await old;assert.equal(t.page.data.list.length,0);assert.equal(t.page.data.scope,'ALL')
    const late=deferred(),f=refusals({api:{getRefusals:()=>late.promise}});const request=f.page.onShow();f.app.globalData.userInfo={staffId:18,stationId:2,role:'STATION_MANAGER'};late.resolve(pageData([f.original],{scope:'ACTIVE'},'order_id'));await request;assert.equal(f.page.data.refusals.length,0)
    const malformed=returns({api:{getReturnApplications:async()=>ok({items:[],nextBeforeId:1,scope:'ACTIVE',stationId:1,limit:50})}});await malformed.page.onShow();assert.equal(malformed.page.data.recordsReady,false);assert(malformed.page.data.loadError)
  })
  await test('first failure is not empty, retries recover, and invalid older cursors cannot append',async()=>{
    let fail=true
    const rows=Array.from({length:51},(_,i)=>({id:i+1,type:2,stationId:1,status:1,active:true}))
    const t=returns({rows,api:{getReturnApplications:async args=>fail?{code:1,message:'尚未核对'}:pageData(rows,args,'id')}})
    await t.page.onShow();assert.equal(t.page.data.recordsReady,false);assert(t.page.data.loadError)
    fail=false;await t.page.onRetry();assert.equal(t.page.data.recordsReady,true);assert.equal(t.page.data.list.length,50)
    const cursor=t.page.data.nextBeforeId
    // ID在游标之后的响应不得追加，也不能消耗下一页游标。
    const bad=returns({rows,api:{getReturnApplications:async args=>args.beforeId?ok({stationId:1,scope:args.scope,limit:50,items:[rows[2]],nextBeforeId:null}):pageData(rows,args,'id')}})
    await bad.page.onShow();await bad.page.onMoreRecords();assert.equal(bad.page.data.list.length,50);assert.equal(bad.page.data.nextBeforeId,cursor);assert(bad.page.data.moreError)
    let refusalFail=true;const f=refusals({rows:[],api:{getRefusals:async args=>refusalFail?{code:1,message:'拒付清单未核对'}:pageData([],args,'order_id')}})
    await f.page.onShow();assert.equal(f.page.data.ready.refusals,false);assert(f.page.data.errors.refusals)
    refusalFail=false;await f.page.onRetry();assert.equal(f.page.data.ready.refusals,true);assert.equal(f.page.data.refusals.length,0)
  })
  await test('refusal next-page retry preserves cursor and a late page cannot overwrite changed filter',async()=>{
    const rows=Array.from({length:61},(_,i)=>({order_id:i+1,version:0,active:true,canRevoke:true}))
    let fail=true,late=null
    const f=refusals({rows,api:{getRefusals:async args=>{
      if(args.beforeId && late)return late.promise
      if(args.beforeId && fail)throw Error('offline')
      return pageData(rows,args,'order_id')
    }}})
    await f.page.onShow();const cursor=f.page.data.refusalNextBeforeId;await f.page.onMoreRefusals()
    assert.equal(f.page.data.refusals.length,50);assert.equal(f.page.data.refusalNextBeforeId,cursor);assert(f.page.data.refusalMoreError)
    fail=false;await f.page.onMoreRefusals();assert.equal(f.page.data.refusals.length,61);assert.equal(f.page.data.refusalNextBeforeId,null)
    await f.page.onRefusalScope(event(null,'ACTIVE'));late=deferred();const old=f.page.onMoreRefusals();await f.page.onRefusalScope(event(null,'ALL'))
    late.resolve(pageData(rows,{scope:'ACTIVE',beforeId:cursor},'order_id'));await old
    assert.equal(f.page.data.refusalScope,'ALL');assert.equal(f.page.data.refusals.length,50);assert.equal(f.page.data.refusalLoading,false)
  })
  await test('late return page and native freeze confirmation cannot act for a changed login',async()=>{
    const d=deferred(),rows=Array.from({length:51},(_,i)=>({id:i+1,type:2,stationId:1,status:1,active:true}))
    const t=returns({rows,api:{getReturnApplications:args=>args.beforeId?d.promise:pageData(rows,args,'id')}})
    await t.page.onShow();const pending=t.page.onMoreRecords();t.app._loginGeneration=1;d.resolve(pageData(rows,{scope:'ACTIVE',beforeId:t.page.data.nextBeforeId},'id'));await pending
    assert.equal(t.page.data.list.length,50)
    const f=refusals({rows:[{order_id:1,active:true,version:0,canFreeze:true}],api:{confirmFreeze:async id=>f.writes.push(id)}})
    await f.page.onShow();let modal;f.wx.showModal=o=>{modal=o};await f.page.action({currentTarget:{dataset:{id:1,action:'freeze'}}})
    assert(modal);assert.equal(f.page.data.refusalBusy,true);f.app.globalData.userInfo={staffId:18,stationId:1,role:'STATION_MANAGER'};await modal.success({confirm:true});assert.equal(f.writes.length,0)
  })
  await test('real API wrappers target bounded endpoints without accepting a client station',()=>{
    const Module=require('module'),file=path.join(ROOT,'miniapp-delivery/api/business-rules.js'),original=Module._load,calls=[]
    delete require.cache[require.resolve(file)]
    Module._load=function(request,parent,isMain){if(parent && parent.filename===file && request==='../utils/request')return {get:(url,args)=>calls.push({url,args})};return original.apply(this,arguments)}
    try {
      const api=require(file);api.getReturnApplications({scope:'ACTIVE',beforeId:9});api.getRefusals({scope:'ALL',orderId:1})
      assert.deepEqual(calls,[{url:'/api/manager/business-waiting/returns',args:{scope:'ACTIVE',beforeId:9}},{url:'/api/manager/refusal-cases/page',args:{scope:'ALL',orderId:1}}])
    } finally {Module._load=original;delete require.cache[require.resolve(file)]}
  })
  await test('an old resolution form cannot submit using another employee or login generation',async()=>{
    for(const change of [app=>{app.globalData.userInfo={staffId:18,stationId:1,role:'STATION_MANAGER'}},app=>{app._loginGeneration=1}]) {
      const f=refusals();await f.page.onShow();f.page.onOpenRefusalResolution({currentTarget:{dataset:{id:1,action:'REVOKE'}}})
      f.page.onRefusalReason({detail:{value:'原员工核实的理由'}});change(f.app);await f.page.onSubmitRefusalResolution();assert.equal(f.writes.length,0)
    }
  })
  await test('actual templates wire pagination, ID lookup, filters and complete return-list navigation',()=>{
    for(const [file,handlers] of [['barrel-return',['onScopeChange','onRecordSearch','onClearRecordSearch','onMoreRecords']],['business-waiting',['onRefusalScope','onRefusalSearch','onClearRefusalSearch','onMoreRefusals','onAllReturns']]]){
      const template=fs.readFileSync(path.join(ROOT,'miniapp-delivery/pages/station-mgmt',file,'index.wxml'),'utf8')
      const state={scope:'ALL',nextBeforeId:1,refusalScope:'ALL',refusalNextBeforeId:1,ready:{},counts:{},errors:{},list:[],refusals:[],returns:[],stock:[],barrels:[],tickets:[],recoveries:[]}
      const elements=[...renderElements(parseWxml(template),state),...renderElements(parseWxml(template),{...state,recordId:1,refusalOrderId:1})]
      for(const handler of handlers)assert(elements.some(e=>Object.values(e.attrs).includes(handler)),file+': '+handler+'必须是真实可达的绑定')
    }
    const t=refusals();t.page.onAllReturns();assert.equal(t.wx.__calls.nav.at(-1).url,'/pages/station-mgmt/barrel-return/index')
  })
  console.log('AQUAFLOW_SUITE_OK '+passed)
}
main().then(()=>done()).catch(err=>{done();console.error(err);process.exitCode=1})
