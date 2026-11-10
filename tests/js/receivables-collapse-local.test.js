const assert=require('node:assert/strict'),fs=require('fs'),path=require('path')
const {loadPage,createApp,createWx,ROOT}=require('./harness')
const {parseWxml,renderElements}=require('./wxml-tree')
const {createNavigator}=require('../../miniapp-delivery/utils/navigation')
const file=path.join(ROOT,'miniapp-delivery/pages/station-mgmt/receivables/index.wxml')
const tree=parseWxml(fs.readFileSync(file,'utf8'))
function nodes(node,parents=[],out=[]){if(node.tag!=='#text')out.push({node,parents});for(const c of node.children||[])nodes(c,[...parents,node],out);return out}
const all=nodes(tree), action=name=>all.find(x=>x.node.attrs&&(x.node.attrs.bindtap===name||x.node.attrs.catchtap===name))
assert(action('onPickCustomer'));assert(action('onBack'));assert.equal(all.filter(x=>x.node.attrs&&x.node.attrs.bindtap==='onBack').length,1)
assert(!fs.readFileSync(file,'utf8').includes('返回列表'))
for(const name of ['onEditTerms','onShowTermsHelp','onToggleOrder','onSelectAll','onSettle']){
 const a=action(name);assert(a,name);assert(!a.parents.some(p=>p.attrs&&p.attrs.bindtap==='onBack'),name+' must not collapse the customer')
}
let resolveA, posts=0
const waitA=new Promise(r=>resolveA=r), A={customerId:1,customerName:'A',outstandingAmount:5,overdueAmount:5,overdueOrderCount:1,earliestDueDate:'2026-11-09T08:00:00.000+08:00'},B={customerId:2,customerName:'B',outstandingAmount:6,overdueAmount:0,overdueOrderCount:0}
const app=createApp({_loginGeneration:1,globalData:{isLogin:true,accessToken:'test',userInfo:{staffId:1,stationId:11,role:'STATION_MANAGER'}}})
const p=loadPage('miniapp-delivery/pages/station-mgmt/receivables/index.js',{wx:createWx(),app,stubs:{'utils/request':{
 get:async url=>url.includes('customerId=1')?waitA:url.includes('/orders?')?{data:[{orderId:2,customerId:2,totalAmount:6,status:3,paymentStatus:1,settlementStatus:1}]}:{data:{customers:[A,B],outstandingAmount:11,overdueAmount:5}},post:async()=>{posts++;return {data:{}}},put:async()=>({data:{}})
}}})
;(async()=>{
 await p.load()
 const overviewDates=renderElements(tree,p.data,{includeText:true}).map(n=>n.text||'').join(' ')
 assert(overviewDates.includes('最早 2026-11-09 到期'))
 assert(!overviewDates.includes('08:00:00'))
 const pending=p.onPickCustomer({currentTarget:{dataset:{id:1}}});assert.equal(p.data.customer.customerId,1)
 p.setData({orders:[{orderId:1,customerId:1,checked:true}],selectedCount:1,selectedAmountText:'¥5.00'})
 p.onBack();assert.equal(p.data.customer,null);assert.deepEqual(p.data.orders,[]);assert.equal(p.data.selectedCount,0)
 resolveA({data:[{orderId:1,customerId:1,totalAmount:5,status:3,paymentStatus:1,settlementStatus:1}]});await pending;await new Promise(setImmediate)
 assert.equal(p.data.customer,null);assert.deepEqual(p.data.orders,[]);await p.onPickCustomer({currentTarget:{dataset:{id:2}}})
 assert.equal(p.data.customer.customerId,2);assert.equal(p.data.selectedCount,0);assert.equal(p.data.orders[0].checked,false);assert.equal(posts,0)
 const rendered=renderElements(tree,p.data);assert(rendered.some(n=>n.attrs.bindtap==='onBack'));assert(rendered.some(n=>n.attrs.bindtap==='onEditTerms'));assert(rendered.some(n=>n.attrs.bindtap==='onShowTermsHelp'))
 p.onBack();await new Promise(setImmediate);p.onFilterOverdue();assert.equal(p.data.customers.length,1);assert.equal(p.data.customers[0].customerId,1);assert.equal(posts,0)
 const customerWithoutDueDate={...B,orderCount:1,dueDays:30,earliestDueDate:null}
 const overview=renderElements(tree,{...p.data,customer:null,onlyOverdue:false,loading:false,loadError:'',customers:[customerWithoutDueDate]},{includeText:true})
 assert(overview.some(n=>(n.text||'').includes('待收订单无到期日')),'订单到期日缺失不得被说成客户未设账期')
 // A visible page must acknowledge a native push even while its report read is pending.
 const wx=createWx(), stack=[], feedback=[]
 let timeout, resolveOverview
 wx.navigateTo=()=>{}
 const navigation=createNavigator({wx,pages:()=>stack,feedback:failure=>feedback.push(failure),
  setTimer(fn){timeout=fn;return 1},clearTimer(){}})
 const owner={route:'pages/station-mgmt/index'}
 const receivables=Object.assign(loadPage('miniapp-delivery/pages/station-mgmt/receivables/index.js',{wx,app,stubs:{
  'utils/navigation':navigation,'utils/request':{get:()=>new Promise(resolve=>{resolveOverview=resolve})}
 }}),{route:'pages/station-mgmt/receivables/index',options:{}})
 stack.push(owner)
 navigation.open('/'+receivables.route,{owner})
 navigation.hide(owner);stack.push(receivables);receivables.onShow()
 timeout()
 assert.deepEqual(feedback,[],'visible receivables must not show a page-open timeout')
 receivables.onUnload();stack.pop();navigation.show(owner)
 resolveOverview({data:{customers:[],outstandingAmount:0,overdueAmount:0}})
 await new Promise(setImmediate)
 assert.equal(receivables.data.overview,null)
 console.log('AQUAFLOW_SUITE_OK 7')
})().catch(e=>{console.error(e);process.exitCode=1})
