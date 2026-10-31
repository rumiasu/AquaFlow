'use strict'
// Synthetic source/handler checks; no HTTP, native runtime, orders or financial writes.
const assert=require('assert'),fs=require('fs'),path=require('path'),vm=require('vm')
const {loadPage,createWx,createApp,armWatchdog}=require('./harness')
const {parseWxml,validateConditionalSiblings,renderElements}=require('./wxml-tree')
const ROOT=path.resolve(__dirname,'../..'),done=armWatchdog();let count=0
function test(name,fn){fn();count++;console.log('PASS '+name)}
function fixture(route){const wx=createWx(),app=createApp({globalData:{isLogin:true,userInfo:{staffId:7,stationId:3,role:'STATION_MANAGER',bindStatus:'BOUND'}}});return {page:loadPage(route,{wx,app}),wx}}
const coord='miniapp-delivery/pages/coordination/index',manage='miniapp-delivery/pages/station-mgmt/index',mine='miniapp-delivery/pages/mine/index'
const tree=route=>{const result=parseWxml(fs.readFileSync(path.join(ROOT,route+'.wxml'),'utf8'));validateConditionalSiblings(result);return result}
const ct=tree(coord),mt=tree(manage),mineTree=tree(mine),view=(t,p)=>renderElements(t,p.data,{includeText:true}),has=(nodes,c)=>nodes.filter(n=>n.className?.split(/\s+/).includes(c))
const ready={pending:true,approvalCustomer:true,approvalStation:true,pool:true,directedIncoming:true,dispatchPool:true,dispatchDirected:true}
function cp(){const f=fixture(coord+'.js');f.page.setData({isManager:true,queuesLoading:false,sectionRead:ready,showGuide:false});return f}
try{
test('line Icon keeps explicit semantic color; optional duo fill never overrides the stroke',()=>{
 for(const app of ['miniapp-user','miniapp-delivery']){
  let def;vm.runInNewContext(fs.readFileSync(path.join(ROOT,app,'components/Icon/index.js'),'utf8'),{Component:d=>{def=d}})
  const make=(name,variant)=>{const data=Object.fromEntries(Object.entries(def.properties).map(([k,v])=>[k,v.value]));Object.assign(data,{name,variant,color:'#A83D37',fillColor:'#D8E8F4'});const instance={data,setData:p=>Object.assign(data,p)};def.lifetimes.attached.call(instance);return decodeURIComponent(data.src.split(',')[1])}
  const line=make('barrel','line'),duo=make('barrel','duotone');assert(line.includes('xmlns="http://www.w3.org/2000/svg"'));assert(line.includes('#A83D37'));assert(!line.includes('#D8E8F4'));assert(duo.includes('#D8E8F4'));assert(duo.includes('#A83D37'))
  const state={name:'bill',color:'#1264D6',variant:'line',fillColor:'#EAF2FF'},instance={data:state,setData:p=>Object.assign(state,p)}
  def.lifetimes.attached.call(instance);def.methods.onImageError.call(instance);assert.equal(state.iconFailed,true);assert.equal(state.fallback,'账')
  def.observers['name, color, variant, fillColor'].call(instance,'chat','#1264D6','line','#EAF2FF');assert.equal(state.iconFailed,false);assert.equal(state.fallback,'讯')
  assert.equal(make('home','duotone'),make('home','line'),'unmapped semantic names retain their original icon')
  for(const name of ['ticket','wallet','barrel','bill','location','chat','products','user','truck','chart','notice','settings'])assert(make(name,'duotone').includes('#D8E8F4'),name)
 }
})
test('workbench navigation exists once in manager Mine and never duplicates on the dispatch homepage',()=>{
 for(const state of [{todo:null,todoError:'',todoLoading:false},{todo:null,todoError:'synthetic-read-error',todoLoading:false},{todo:{items:[]},todoError:'',todoLoading:false}]){const {page}=cp();page.setData(state);assert.equal(view(ct,page).filter(n=>n.attrs.bindtap==='onGoStationMgmt').length,0)}
 const {page,wx}=fixture(mine+'.js');page.setData({isLogin:true,isManager:true,userInfo:{staffId:7,stationId:3,role:'STATION_MANAGER'},displayImageErrors:{}});assert.equal(view(mineTree,page).filter(n=>n.attrs.bindtap==='onGoStationMgmt').length,1);page.onGoStationMgmt();assert.equal(wx.__calls.nav.filter(n=>n.url==='/pages/station-mgmt/index').length,1)
 page.setData({isManager:false});assert.equal(view(mineTree,page).filter(n=>n.attrs.bindtap==='onGoStationMgmt').length,0);page.onGoStationMgmt();assert.equal(wx.__calls.nav.filter(n=>n.url==='/pages/station-mgmt/index').length,1)
 page.setData({isLogin:false,isManager:true});assert.equal(view(mineTree,page).filter(n=>n.attrs.bindtap==='onGoStationMgmt').length,0)
})
test('collapsed todo discloses the original first item and expands all original amounts without summing facts',()=>{
 const {page}=cp(),items=[{key:'overdueReceivable',label:'合成应收',count:2,amountText:'123.45',hasCount:true},{key:'returnRefund',label:'合成退款',count:3,amountText:'0.00',hasCount:true}];page.setData({todo:{items},todoError:'合成未核对'});const before=JSON.stringify(page.data.todo)
 let nodes=view(ct,page);assert.equal(has(nodes,'todo-item').length,0);assert(nodes.some(n=>n.text==='合成应收'));assert(nodes.some(n=>n.text==='合成未核对'));assert(nodes.some(n=>n.attrs.bindtap==='onRetryTodo'))
 page.onToggleV2Todo();nodes=view(ct,page);assert.equal(has(nodes,'todo-item').length,2);assert(nodes.some(n=>n.text==='¥123.45'));assert(nodes.some(n=>n.text==='¥0.00'));assert.equal(JSON.stringify(page.data.todo),before)
})
test('queue selection distinguishes unread counts, loading and confirmed zero',()=>{
 const {page}=cp();page.setData({sectionRead:{}});assert(has(view(ct,page),'tab-count').every(n=>n.text==='—'));page.setData({queuesLoading:true});assert(has(view(ct,page),'tab-count').every(n=>n.text==='…'));page.setData({queuesLoading:false,sectionRead:ready});assert(has(view(ct,page),'tab-count').every(n=>n.text==='0'))
})
test('scene and empty illustration failures preserve verified empty text without inventing navigation',()=>{
 const {page}=cp();page.onV2SceneError();page.onV2EmptyImageError();const nodes=view(ct,page);assert(!nodes.some(n=>n.attrs.src==='/assets/ui/scene-station-delivery.webp'));assert(!nodes.some(n=>n.attrs.src==='/assets/ui/empty-order.webp'));assert.equal(nodes.filter(n=>n.attrs.bindtap==='onGoStationMgmt').length,0);assert(nodes.some(n=>n.text==='暂无待分配/转单订单'))
 page.setData({sectionRead:{},queuesLoading:false});assert(!view(ct,page).some(n=>n.text==='暂无待分配/转单订单'),'unverified read cannot become empty')
})
test('twenty original management targets remain reachable after expanding groups; dashboard exists exactly once',()=>{
 const {page}=fixture(manage+'.js');for(const group of ['aftercare','assets','settings','reports'])page.onToggleGroup({currentTarget:{dataset:{group}}});page.setData({receivablesBadgeCount:0,businessWaitingBadgeCount:0,barrelReturnBadgeCount:0,refundDisputeBadgeCount:0})
 const nodes=view(mt,page),urls=nodes.filter(n=>n.attrs.bindtap==='onNavigate').map(n=>n.attrs['data-url']);assert.equal(new Set(urls).size,20);assert.equal(urls.length,20);assert.equal(urls.filter(u=>u==='/pages/station-mgmt/dashboard/index').length,1)
})
test('management unknown badges remain unknown while confirmed all-zero removes pending cards',()=>{
 const {page}=fixture(manage+'.js');assert.equal(has(view(mt,page),'pending-card').length,4);assert.equal(has(view(mt,page),'pending-value').length,0)
 page.setData({receivablesBadgeCount:0,businessWaitingBadgeCount:0,barrelReturnBadgeCount:0,refundDisputeBadgeCount:0});assert.equal(has(view(mt,page),'pending-card').length,0)
 page.onV2SceneError();assert(!view(mt,page).some(n=>n.attrs.src==='/assets/ui/scene-station-delivery.webp'))
})
test('completed setup uses name plus check while optional tasks are never labelled unfinished',()=>{
 const template=parseWxml(fs.readFileSync(path.join(ROOT,'miniapp-delivery/templates/setup-module-tasks.wxml'),'utf8'));validateConditionalSiblings(template)
 const render=item=>renderElements(template,{ready:true,loading:false,error:'',tasks:[item]},{includeText:true})
 const done=render({key:'pricing',label:'价格设置',done:true,levelText:'必填',why:'重复完成说明',completionEvidence:'冗余完成依据'});assert(done.some(n=>n.text==='✓'));assert(done.some(n=>n.text==='价格设置'));assert(!done.some(n=>n.text==='已完成'||n.text==='重复完成说明'||n.text==='冗余完成依据'));assert(done.some(n=>n.attrs.bindtap==='onModuleSetupTask'&&n.attrs['data-key']==='{{item.key}}'))
 const optional=render({key:'ticketPackage',label:'水票套餐',done:false,levelText:'选填'});assert(optional.some(n=>n.text==='选填'));assert(!optional.some(n=>n.text==='待填写'||n.text==='○'))
 const pending=render({key:'catalogOnShelf',label:'商品上架',done:false,levelText:'必填',suggestion:'补齐可售商品'});assert(pending.some(n=>n.text==='待填写'));assert(pending.some(n=>n.text==='补齐可售商品'));assert(pending.some(n=>n.text==='去上架商品'))
})
console.log('AQUAFLOW_SUITE_OK '+count)
}finally{done()}
