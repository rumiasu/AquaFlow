'use strict'
const assert = require('assert'), fs = require('fs'), path = require('path')
const { loadPage, createWx, createApp, armWatchdog, ROOT } = require('./harness')
const done=armWatchdog(30000), tests=[], test=(name,run)=>tests.push({name,run})
async function fixture(order) {
 const wx=createWx(), calls={open:[],choose:[],modal:[],copy:[]}
 wx.openLocation=x=>calls.open.push(x);wx.chooseLocation=x=>calls.choose.push(x)
 wx.showModal=x=>calls.modal.push(x);wx.setClipboardData=x=>calls.copy.push(x)
 const app=createApp({_loginGeneration:1,globalData:{isLogin:true,accessToken:'a',userInfo:{role:'DELIVERY',staffId:8,stationId:1}}})
 const row={id:20,status:2,stationId:1,deliveryStaffId:8,items:[],...order}
 const page=loadPage('miniapp-delivery/pages/order/detail.js',{wx,app,stubs:{
  'api/delivery':{getOrderDetail:async()=>({data:row})},'utils/request':{get:async()=>({data:[]})}}})
 page.setData({orderId:20,order:row,loading:false,detailError:''})
 return {page,app,wx,calls}
}
test('display and copying do not fall back to the changed current address',async()=>{
 const t=await fixture({addressDetail:'客户修改后地址'})
 t.page.onCopyAddress();t.page.onNavigate()
 assert.strictEqual(t.calls.copy.length,0);assert.strictEqual(t.calls.open.length,0);assert.strictEqual(t.calls.choose.length,0)
 const w=fs.readFileSync(path.join(ROOT,'miniapp-delivery/pages/order/detail.wxml'),'utf8')
 assert.ok(!w.includes('order.addressSnapshot || order.addressDetail'))
 assert.ok(w.includes('订单地址快照缺失，请核实'))
})
test('snapshot coordinates and text agree even after the customer changed address',async()=>{
 const t=await fixture({addressSnapshot:'下单地址',addressDetail:'新地址',addressSnapshotLat:30,addressSnapshotLng:120})
 t.page.onCopyAddress();t.page.onNavigate()
 assert.strictEqual(t.calls.copy[0].data,'下单地址');assert.strictEqual(t.calls.open[0].address,'下单地址')
 assert.strictEqual(t.calls.open[0].latitude,30);assert.strictEqual(t.calls.open[0].longitude,120)
})
test('invalid or missing coordinates require manual selection after the snapshot is shown',async()=>{
 const t=await fixture({addressSnapshot:'下单地址',addressDetail:'新地址',addressSnapshotLat:999,addressSnapshotLng:120})
 t.page.onNavigate();assert.ok(t.calls.modal[0].content.includes('下单地址'));assert.strictEqual(t.calls.choose.length,0)
 t.calls.modal[0].success({confirm:true});assert.strictEqual(t.calls.choose.length,1)
 t.calls.choose[0].success({latitude:31,longitude:121,name:'手动选点',address:'员工选择'})
 assert.strictEqual(t.calls.open[0].name,'手动选点')
})
test('identity changes and new reads invalidate pending selection callbacks',async()=>{
 for(const phase of ['modal','map']){
  const t=await fixture({addressSnapshot:'下单地址'})
  t.page.onNavigate()
  if(phase==='map') t.calls.modal[0].success({confirm:true})
  t.app._loginGeneration++
  if(phase==='modal')t.calls.modal[0].success({confirm:true})
  else t.calls.choose[0].success({latitude:31,longitude:121})
  assert.strictEqual(t.calls.open.length,0)
  if(phase==='modal')assert.strictEqual(t.calls.choose.length,0)
 }
})
test('current floor is shown only when current address matches snapshot',async()=>{
 for(const same of [true,false]){
  const t=await fixture({addressSnapshot:'下单地址',addressDetail:same?'下单地址':'新地址',addressFloor:8,addressHasElevator:0})
  await t.page.loadOrderDetail(20)
  assert.strictEqual(!!t.page.data.order.floorText,same)
 }
})
;(async()=>{let failures=0;for(const t of tests){try{await t.run();console.log('PASS '+t.name)}catch(e){failures++;console.error('FAIL '+t.name,e)}}done();if(failures)process.exitCode=1;else console.log('AQUAFLOW_SUITE_OK '+tests.length)})().catch(e=>{done();console.error(e);process.exitCode=1})