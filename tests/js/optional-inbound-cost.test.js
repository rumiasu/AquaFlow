const fs=require('fs'),path=require('path'),vm=require('vm'),assert=require('assert')
const root=path.resolve(__dirname,'../..'), store=new Map()
let currentRoute={mode:'cloudContainer',env:'synthetic-env',service:'synthetic-service'}
const load=(rel,requireMap,extra={})=>{let value;const module={exports:{}};vm.runInNewContext(fs.readFileSync(path.join(root,rel),'utf8'),{module,exports:module.exports,require:k=>requireMap[k],Page:p=>value=p,console,Date,Math,Number,JSON,Promise,...extra});return value||module.exports}
const intent=load('miniapp-delivery/utils/inbound-intent.js',{'../config/api':{getTransportConfig:()=>currentRoute}},{wx:{getStorageSync:k=>store.get(k),setStorageSync:(k,v)=>store.set(k,structuredClone(v)),removeStorageSync:k=>store.delete(k)}})
let n=0;const test=(name,fn)=>Promise.resolve().then(fn).then(()=>{n++;console.log('PASS',name)})
async function main(){
 await test('blank remains absent and exact receipt can replay',()=>{const a=intent.prepare('1:2',[{productId:3,quantity:2}]);assert(!('costAmount'in a.items[0]));assert(intent.acceptsReceipt(a,{code:0,data:{...a,status:'APPLIED'}}));intent.clear(a)})
 await test('zero persists and cannot be replaced while outcome is unknown',()=>{const a=intent.prepare('1:2',[{productId:3,quantity:2,costAmount:0}]);assert.equal(a.items[0].costAmount,0);const b=intent.prepare('1:2',[{productId:3,quantity:4,costAmount:99}]);assert.equal(b.clientRequestId,a.clientRequestId);assert.equal(b.items[0].costAmount,0);assert(!intent.acceptsReceipt(a,{code:0,data:{...a,items:[{productId:3,quantity:2}],status:'APPLIED'}}));intent.clear(a)})
 await test('invalid money never prepares a stock mutation',()=>{for(const costAmount of [-1,'1.001','abc'])assert.throws(()=>intent.prepare('1:2',[{productId:3,quantity:2,costAmount}]))})
 let report,calls=[];const page=load('miniapp-delivery/pages/station-mgmt/gross-profit/index.js',{'../../../utils/business-dialog':{showBusinessModal:()=>{}},'../../../utils/request':{get:async url=>{calls.push(url);return{code:0,data:report}}},'../../../config/api':{API:{MANAGER_GROSS_PROFIT:'/report'}}})
 page.setData=p=>Object.assign(page.data,p)
 const {parseWxml,renderElements}=require(path.join(root,'tests/js/wxml-tree'))
 const tree=parseWxml(fs.readFileSync(path.join(root,'miniapp-delivery/pages/station-mgmt/gross-profit/index.wxml'),'utf8'))
 const texts=()=>renderElements(tree,page.data,{includeText:true}).map(x=>x.text)
 const base={items:[],profitComparable:true,totalIncome:100,totalRevenue:80,orderRevenue:80,ticketRevenue:0,deliveryFee:10,floorFee:10,wage:0,orderCount:1,profitBasisNote:'原收入口径',helpNote:'1. 原统计范围\n6. 记录成本'}
 await test('incomplete costs still show product income and actionable cooperation balances',async()=>{report={...base,costComplete:false,totalCost:null,totalProfit:null,netProfit:null,items:[{productId:3,productName:'桶装水',soldQty:2,revenueTotal:80,costAmount:null,profitText:'成本未完整记录'}],cooperation:{rows:[{key:'pendingPayAmount',label:'合作款待付',amount:25},{key:'pendingReceiveAmount',label:'合作款待收',amount:5},{key:'recordedCashNetAmount',label:'登记净现金（不等于利润）',amount:10}]}};await page.load();assert.equal(page.data.profitView.totalIncomeText,'¥100');assert.equal(page.data.costComplete,false);assert.equal(page.data.profitChart.length,0);assert.equal(calls.length,1);page.onToggleIncomeDetails();const visible=texts();assert(!visible.includes('销售净利'));assert(!visible.includes('− 出库成本'));assert(!visible.includes('金额结构'));assert(visible.includes('订单收入（水费）'));assert(visible.includes('桶装水'));assert(visible.includes('合作款待付'));assert(visible.includes('合作款待收'));assert(!visible.includes('登记净现金（不等于利润）'),'cash net stays in the expandable detail')})
 await test('explicit zero cost is complete and visible',async()=>{
  report={...base,costComplete:true,totalCost:0,totalProfit:80,netProfit:100}
  await page.load()
  assert(page.data.costComplete)
  assert.equal(page.data.profitChart.length,4)
  assert.equal(page.data.profitChart.find(row=>row.key==='cost').text,'¥0')
  assert(texts().includes('销售净利'))
  assert(texts().includes('¥0'))
 })
 await test('legacy response cannot fabricate completeness',async()=>{report={...base,totalCost:0,totalProfit:80,netProfit:100};await page.load();assert.equal(page.data.costComplete,false);assert.equal(page.data.profitChart.length,0)})
 console.log('AQUAFLOW_SUITE_OK '+n)
}
main().catch(e=>{console.error(e);process.exitCode=1})
