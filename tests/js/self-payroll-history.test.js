const assert=require('assert')
const {loadPage,createWx,createApp,armWatchdog}=require('./harness')
const done=armWatchdog(),defer=()=>{let resolve;const promise=new Promise(r=>{resolve=r});return{resolve,promise}}
const row=id=>({id,staffId:7,payrollNo:'SYN-'+id,periodStart:'2020-01-01',periodEnd:'2020-01-31',totalAmount:'12.00',statusText:'已发放'})
function fixture(overrides={}) {
 const calls=[],wx=createWx(),app=createApp({_loginGeneration:1,globalData:{isLogin:true,userInfo:{staffId:7,stationId:2,role:'DELIVERY'}}})
 const initial={items:[],payrolls:Array.from({length:20},(_,n)=>row(100-n)),periodTotal:0,unsettledTotal:12}
 const page=loadPage('miniapp-delivery/pages/my-earnings/index.js',{wx,app,stubs:{'utils/request':{get:async(url,args)=>{calls.push({url,args});if(overrides.get)return overrides.get(url,args,initial);return{data:initial}},post:()=>{throw Error('read-only feature must never write')}}}})
 return{page,app,wx,calls,initial}
}
let passed=0
async function test(name,run){await run();passed++;console.log('PASS '+name)}
async function main(){
 await test('explicit past dates query historical earnings and keep all-time unsettled separate',async()=>{
  const t=fixture();await t.page.onShow();t.page.onFromChange({detail:{value:'2020-01-01'}});t.page.onToChange({detail:{value:'2020-01-31'}});await t.page.onApplyPeriod()
  assert.deepStrictEqual(t.calls[1].args,{from:'2020-01-01',to:'2020-01-31'});assert.equal(t.page.data.periodTotalText,'0.00');assert.equal(t.page.data.unsettledTotalText,'12.00')
  t.page.onFromChange({detail:{value:'2020-02-01'}});await t.page.onApplyPeriod();assert.equal(t.calls.length,2);assert(t.page.data.rangeError)
 })
 await test('older payrolls append using last original id and exact terminal page stops loading',async()=>{
  const t=fixture({get:async(url,args,initial)=>url.endsWith('/payrolls')?{data:{payrolls:[row(80),row(79)],hasMore:false,nextBeforeId:null}}:{data:initial}})
  await t.page.onShow();await t.page.onMorePayrolls();assert.equal(t.calls[1].args.beforeId,81);assert.equal(t.page.data.payrolls.length,22);assert.equal(t.page.data.payrolls[21].id,79)
  await t.page.onMorePayrolls();assert.equal(t.calls.length,2);assert.equal(t.page.data.payrollHasMore,false)
 })
 await test('bad page boundaries and a missing next cursor preserve verified payrolls and expose retry',async()=>{
  for(const data of [{payrolls:[row(81)],hasMore:false},{payrolls:[row(80)],hasMore:true,nextBeforeId:79},{payrolls:[],hasMore:true,nextBeforeId:80}]){
   const t=fixture({get:async(url,args,initial)=>url.endsWith('/payrolls')?{data}:{data:initial}});await t.page.onShow();await t.page.onMorePayrolls()
   assert.equal(t.page.data.payrolls.length,20);assert(t.page.data.payrollError);assert.equal(t.page.data.payrollHasMore,true)
  }
 })
 await test('double more tap sends one query and an identity switch discards its late page',async()=>{
  const d=defer(),t=fixture({get:async(url,args,initial)=>url.endsWith('/payrolls')?d.promise:{data:initial}})
  await t.page.onShow();const request=t.page.onMorePayrolls();await t.page.onMorePayrolls();assert.equal(t.calls.length,2)
  t.app.globalData.userInfo.staffId=8;d.resolve({data:{payrolls:[row(80)],hasMore:false}});await request;await t.page.onShow()
  assert.equal(t.page.data.payrolls.length,0);assert(t.page.data.loadError)
 })
 await test('own payroll detail keeps exact zero and never opens a payment action',async()=>{
  const t=fixture({get:async(url,args,initial)=>url.endsWith('/100')?{data:{payroll:row(100),items:[{id:1,displayText:'调整',amount:0,note:'核对说明'}]}}:{data:initial}})
  await t.page.onShow();await t.page.onOpenPayroll({currentTarget:{dataset:{id:100}}});assert.equal(t.page.data.detailItems[0].amountText,'0.00');assert.equal(t.page.data.selectedPayroll.id,100);assert.equal(t.calls[1].url,'/api/delivery/earnings/payrolls/100')
  t.page.onClosePayroll();assert.equal(t.page.data.selectedPayroll,null)
 })
 await test('mismatched case or staff in detail is rejected while selected original header stays',async()=>{
  for(const payroll of [row(99),{...row(100),staffId:8}]){
   const t=fixture({get:async(url,args,initial)=>url.endsWith('/100')?{data:{payroll,items:[]}}:{data:initial}})
   await t.page.onShow();await t.page.onOpenPayroll({currentTarget:{dataset:{id:100}}});assert(t.page.data.detailError);assert.equal(t.page.data.detailItems.length,0);assert.equal(t.page.data.selectedPayroll.id,100)
  }
 })
 await test('closed detail and hidden page ignore late records, return reloads the current period',async()=>{
  const d=defer(),t=fixture({get:async(url,args,initial)=>url.endsWith('/100')?d.promise:{data:initial}})
  await t.page.onShow();const read=t.page.onOpenPayroll({currentTarget:{dataset:{id:100}}});t.page.onClosePayroll();t.page.onHide();d.resolve({data:{payroll:row(100),items:[{id:1,amount:99}]}});await read
  assert.equal(t.page.data.selectedPayroll,null);await t.page.onShow();assert.equal(t.calls.length,3)
 })
 done();console.log('AQUAFLOW_SUITE_OK '+passed)
}
main().catch(e=>{console.error(e);process.exitCode=1;done()})
