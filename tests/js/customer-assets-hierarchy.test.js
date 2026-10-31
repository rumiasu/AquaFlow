// Render the actual asset templates with page state; navigation uses the real handler.
const assert = require('assert'), fs = require('fs'), path = require('path')
const {loadPage,createWx,createApp,armWatchdog,ROOT}=require('./harness')
const {parseWxml,renderElements}=require('./wxml-tree')
const done=armWatchdog(30000), tests=[]
const test=(name,fn)=>tests.push([name,fn])
const mine=parseWxml(fs.readFileSync(path.join(ROOT,'miniapp-user/pages/mine/index.wxml'),'utf8'))
const scope=parseWxml(fs.readFileSync(path.join(ROOT,'miniapp-user/components/AssetStationView/index.wxml'),'utf8'))
const hasClass=(e,c)=>e.className.split(/\s+/).includes(c)
function setup(){
 const wx=createWx(),app=createApp({globalData:{isLogin:true,customerId:7,accessToken:'asset-test',userInfo:{nickName:'测试'}}})
 wx.__storage.set('selectedStation',{id:11,name:'下单站'})
 let fail=false
 const page=loadPage('miniapp-user/pages/mine/index.js',{wx,app,stubs:{
  'api/barrel':{getBarrelSummary:async()=>{if(fail)throw Error('无权查看');return{code:0,data:{independentRights:true,depositBalance:300,occupiedBuckets:3}}}},
  'api/ticket':{getTicketAccounts:async()=>{if(fail)throw Error('无权查看');return{code:0,data:[{remainQuantity:28}]}}},
  'api/company':{getCompanyInfo:async()=>({code:0,data:null})}
 }})
 return{page,wx,fail:()=>{fail=true}}
}
test('asset station stays explicit and full ownership rules remain in the existing selector',()=>{
 const data={stationId:22,stationName:'历史资产站',statusText:'停业 · 可查看历史资产',orderStationName:'下单站',busy:false,open:false}
 const closed=renderElements(scope,data,{includeText:true})
 assert(closed.some(e=>hasClass(e,'scope-caption')&&e.text==='资产水站'))
 assert(closed.some(e=>hasClass(e,'scope-name')&&e.text==='历史资产站'))
 assert(closed.some(e=>hasClass(e,'scope-row')&&e.attrs.bindtap==='openPicker'))
 assert(closed.some(e=>e.text==='停业 · 可查看历史资产'))
 assert(!closed.some(e=>hasClass(e,'scope-tip')))
 const open=renderElements(scope,{...data,open:true,stations:[],errorText:'',loading:false,hasMore:false},{includeText:true})
 assert(open.some(e=>e.text.includes('不能跨站使用')))
 assert(open.some(e=>e.text.includes('下单站')&&e.text.includes('不会更改')))
})
test('water tickets lead, deposit and barrels remain distinct actionable facts',async()=>{
 const {page}=setup();await page.onShow()
 const rendered=renderElements(mine,page.data,{includeText:true}),cards=rendered.filter(e=>hasClass(e,'asset-cell'))
 assert.deepStrictEqual(cards.map(e=>e.attrs['data-url']),['/pages/ticket/index','/pages/deposit/records/index','/pages/barrel/index'])
 assert(hasClass(cards[0],'asset-cell-primary'))
 assert.deepStrictEqual(rendered.filter(e=>hasClass(e,'asset-value')).map(e=>e.text),['28','¥300','3'])
})
test('all three cards carry the asset station without changing the order station',async()=>{
 const {page,wx}=setup();await page.onShow();await page.onAssetStationChange({detail:{id:22,name:'历史资产站'}})
 const cards=renderElements(mine,page.data).filter(e=>hasClass(e,'asset-cell'))
 for(const card of cards)page.onMenuTap({currentTarget:{dataset:{url:card.attrs['data-url']}}})
 assert(wx.__calls.nav.every(n=>n.url.endsWith('?stationId=22')))
 assert.strictEqual(wx.__storage.get('selectedStation').id,11)
})
test('permission failure removes numbers in the same visible cards and offers real retry',async()=>{
 const {page,fail}=setup();await page.onShow();fail();await page.onRetryAssets()
 const rendered=renderElements(mine,page.data,{includeText:true}),values=rendered.filter(e=>hasClass(e,'asset-value'))
 assert.strictEqual(values.length,3);assert(values.every(e=>e.text==='暂未加载'&&hasClass(e,'asset-value-unloaded')))
 assert(rendered.some(e=>hasClass(e,'assets-unloaded-tip')&&e.attrs.bindtap==='onRetryAssets'))
 assert.strictEqual(page.data.staleAssetsText,'')
})
;(async()=>{for(const [name,fn]of tests){await fn();console.log('PASS '+name)}console.log('AQUAFLOW_SUITE_OK '+tests.length);done()})().catch(e=>{done();console.error(e.stack);process.exitCode=1})
