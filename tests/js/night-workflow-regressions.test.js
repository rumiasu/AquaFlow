const assert = require('assert')
const { loadPage, createWx, createApp, armWatchdog } = require('./harness')
const done = armWatchdog()
const deferred = () => { let resolve, reject; const promise = new Promise((a,b) => { resolve=a; reject=b }); return { promise, resolve, reject } }
const event = id => ({ currentTarget: { dataset: { id } } })
const actor = () => createApp({ _loginGeneration: 1, globalData: { isLogin:true, userInfo:{staffId:17,stationId:2,role:'STATION_MANAGER'} } })
let passed = 0
async function test(name, run) { await run(); passed++; console.log('PASS '+name) }
function exception(overrides={}) {
  const app=actor(),wx=createWx(),writes=[],dialogs=[];let status='STAFF_RECORDED',reads=0,refreshes=0
  const request={get:async()=>{reads++;return {code:0,data:{id:41,status,statusText:status==='EXECUTED'?'已完成':'待处理',suggestedTicketQty:2,adjustProductId:9}}},
    post:async(url,body)=>{writes.push([url,body]);status='EXECUTED';if(overrides.write)return overrides.write();return {code:0}}}
  if(overrides.read)request.get=overrides.read
  const page=loadPage('miniapp-delivery/pages/station-mgmt/exceptions/index.js',{app,wx,stubs:{'utils/request':request,
    'utils/business-dialog':{showBusinessModal:o=>dialogs.push(o)},'api/station-mgmt':{getAlerts:async()=>({data:[]})}}})
  page.askNote=async()=> '核实按建议补偿';page.load=async()=>{refreshes++}
  return {page,app,wx,writes,dialogs,reads:()=>reads,refreshes:()=>refreshes}
}
async function main() {
  await test('WXML projection still rejects unbounded expressions and invalid syntax',async()=>{
    const {parseWxml,renderElements}=require('./wxml-tree')
    assert.throws(()=>renderElements(parseWxml('<view wx:if="{{(()=>{while(true){}})()}}"></view>'),{}),/timed out/)
    assert.throws(()=>renderElements(parseWxml('<view wx:if="{{broken +}}"></view>'),{}),/Unexpected/)
    assert.equal(renderElements(parseWxml('<view wx:if="{{count === 0}}"></view>'),{count:0}).length,1)
  })
  await test('approved compensation executes once through handle and refreshes instead of calling execute',async()=>{
    const t=exception();await t.page.onApproveSuggested(event(41))
    assert.equal(t.writes.length,1);assert(t.writes[0][0].endsWith('/handle'));assert.equal(t.writes[0][1].refundTicketQty,2)
    assert.equal(t.refreshes(),1);assert.equal(t.dialogs.length,0);assert(t.page.data.actionNotice.includes('补偿已执行'))
  })
  await test('lost successful response reconciles original terminal record without repeating compensation',async()=>{
    const t=exception({write:async()=>{throw Error('network lost')}});await t.page.onApproveSuggested(event(41))
    assert.equal(t.writes.length,1);assert.equal(t.reads(),2);assert.equal(t.page.data.uncertainId,null)
    assert(t.page.data.actionNotice.includes('原记录显示'));assert.equal(t.refreshes(),1)
    await t.page.onApproveSuggested(event(41));assert.equal(t.writes.length,1)
  })
  await test('another manager advancing the case refreshes after a definite rejection without executing again',async()=>{
    const t=exception({write:async()=>{throw Object.assign(Error('该异常已被处理'),{businessRejected:true})}})
    await t.page.onApproveSuggested(event(41));assert.equal(t.writes.length,1);assert.equal(t.refreshes(),1)
    assert(t.page.data.actionNotice.includes('未获通过'));assert.equal(t.page.data.uncertainId,null)
  })
  await test('unavailable original read keeps explicit read-only recovery and cannot dispatch execute',async()=>{
    let reads=0
    const t=exception({read:async()=>{if(++reads>1)throw Error('offline');return {data:{id:41,status:'STAFF_RECORDED',suggestedTicketQty:2,adjustProductId:9}}},write:async()=>{throw Error('unknown')}})
    await t.page.onApproveSuggested(event(41));assert.equal(t.page.data.uncertainId,41)
    await t.page.onVerifyHandling();assert.equal(t.writes.length,1);assert(t.page.data.actionNotice.includes('勿再次补偿'))
  })
  await test('double approval and a receipt after hide do not issue another write or update a hidden page',async()=>{
    const d=deferred(),t=exception({write:()=>d.promise});const first=t.page.onApproveSuggested(event(41))
    await new Promise(r=>setImmediate(r));await t.page.onApproveSuggested(event(41));assert.equal(t.writes.length,1)
    t.page.onHide();d.resolve({code:0});await first;assert.equal(t.refreshes(),0);assert.equal(t.wx.__calls.toast.length,0)
  })
  await test('no-compensation terminal state uses backend result and completed progress',async()=>{
    const page=loadPage('miniapp-user/pages/exception/list/list.js',{app:actor(),wx:createWx()})
    const row=page.decorateException({id:41,status:'VERIFIED',statusText:'核实结案（无需补偿）',managerAction:'VERIFY',discrepancy:0})
    assert.equal(row.compensationText,'核实结案（无需补偿）');assert.equal(row.stepIndex,3);assert.equal(row.statusColor,'success')
  })
  await test('staff profile ignores an older response and refreshes after returning from an order',async()=>{
    const a=actor(),pending=[];let reads=0
    const page=loadPage('miniapp-delivery/pages/station-mgmt/staff/profile/index.js',{app:a,wx:createWx(),stubs:{'api/station-mgmt':{getStaffProfile:()=>{reads++;const d=deferred();pending.push(d);return d.promise}}}})
    const initial=page.onLoad({id:'12'});page.onShow();assert.equal(reads,1)
    const newer=page.onRetryProfile();pending[1].resolve({code:0,data:{name:'new',todayOrders:8}});await newer
    pending[0].resolve({code:0,data:{name:'old',todayOrders:3}});await initial;assert.equal(page.data.profile.todayOrders,8)
    page.onHide();const returned=page.onShow();pending[2].resolve({code:0,data:{todayOrders:9}});await returned;assert.equal(page.data.profile.todayOrders,9)
  })
  await test('staff profile cannot restore prior station data or navigate after identity changes',async()=>{
    const a=actor(),wx=createWx(),d=deferred();const page=loadPage('miniapp-delivery/pages/station-mgmt/staff/profile/index.js',{app:a,wx,stubs:{'api/station-mgmt':{getStaffProfile:()=>d.promise}}})
    const loading=page.onLoad({id:'12'});page.onShow();a.globalData.userInfo.stationId=3
    d.resolve({code:0,data:{name:'old station'}});await loading;page.onShow()
    assert.equal(page.data.profile,null);page.onOrderTap(event(41));assert.equal(wx.__calls.nav.length,0)
  })
  done();console.log('AQUAFLOW_SUITE_OK '+passed)
}
main().catch(e=>{console.error(e);process.exitCode=1;done()})
