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
 const base={items:[],totalIncome:100,totalRevenue:80,orderRevenue:80,ticketRevenue:0,deliveryFee:10,floorFee:10,wage:0,orderCount:1,profitBasisNote:'原收入口径',helpNote:'1. 原统计范围\n6. 记录成本'}
 await test('incomplete period shows total income without profit, chart or cost editor',async()=>{report={...base,costComplete:false,totalCost:null,totalProfit:null,netProfit:null};await page.load();assert.equal(page.data.profitView.totalIncomeText,'¥100');assert.equal(page.data.costComplete,false);assert.equal(page.data.profitChart.length,0);assert.equal(calls.length,1);const visible=texts();assert(!visible.includes('利润'));assert(!visible.includes('进货成本'));assert(!visible.includes('销售净利'));assert(!visible.includes('本期金额对比'))})
 await test('explicit zero cost is complete and visible',async()=>{report={...base,costComplete:true,totalCost:0,totalProfit:80,netProfit:100};await page.load();assert(page.data.costComplete);assert(page.data.profitChart.length===3);assert(texts().includes('利润'));assert(texts().includes('¥0'))})
 await test('legacy response cannot fabricate completeness',async()=>{report={...base,totalCost:0,totalProfit:80,netProfit:100};await page.load();assert.equal(page.data.costComplete,false);assert.equal(page.data.profitChart.length,0)})
 console.log('AQUAFLOW_SUITE_OK '+n)
}
main().catch(e=>{console.error(e);process.exitCode=1})
