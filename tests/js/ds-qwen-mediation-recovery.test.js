'use strict'
const assert=require('node:assert/strict'),{loadPage,createWx,createApp}=require('./harness')
const tick=()=>new Promise(setImmediate),event=id=>({currentTarget:{dataset:{id}}})
function fixture(){const wx=createWx(),dialogs=[],app=createApp({_loginGeneration:1,globalData:{isLogin:true,userInfo:{role:'DELIVERY',staffId:8,stationId:1,bindStatus:'BOUND'}}});let order={id:20,status:2,deliveryStaffId:8,transferPending:false},writes=0,resolve,reject;const pending=new Promise((a,b)=>{resolve=a;reject=b});const p=loadPage('miniapp-delivery/pages/home/index.js',{wx,app,stubs:{'utils/business-dialog':{showBusinessModal:o=>dialogs.push(o)},'api/delivery':{getOrderDetail:async()=>({data:order}),returnToStation:()=>{writes++;return pending},getStaffList:async()=>({data:[{id:9,name:'同事'}]}),transferOrder:()=>{writes++;return pending}},'utils/tabbar':{syncTabBar(){}}}});p.setData({deliveringOrders:[order]});p.loadData=async()=>{};return {wx,app,p,dialogs,pending,resolve,reject,setOrder:o=>order=o,get writes(){return writes}}}
;(async()=>{
 for(const action of ['station','colleague']){
  const t=fixture();t.p.onMediateOrder(event(20));assert(t.p.data.showMediateModal)
  if(action==='station')t.p.onMediateToStation();else{t.wx.showActionSheet=o=>o.success({tapIndex:0});await t.p.onMediateToColleague()}
  assert.equal(t.dialogs.length,1);const first=t.dialogs[0].success({confirm:true});await tick();assert.equal(t.writes,1)
  const second=t.dialogs[0].success({confirm:true});await tick();assert.equal(t.writes,1,'duplicate confirmation submits once')
  t.reject(Object.assign(Error('timeout'),{requestMayHaveOccurred:true}));await Promise.all([first,second]);assert.equal(t.p.data.collectionRecoveryItems.length,1);assert(!t.wx.__calls.toast.some(x=>x.title==='转单失败'||x.title==='退回失败'))
  t.p.onMediateOrder(event(20));assert.equal(t.p.data.showMediateModal,false);await t.p.onVerifyCollection(event(20));assert.equal(t.p.data.collectionRecoveryItems.length,1,'unchanged GET cannot prove original write failed')
  t.setOrder({id:20,status:2,deliveryStaffId:8,transferPending:true,transferPendingSubKind:action==='station'?'RETURN_STATION':'TRANSFER'});await t.p.onVerifyCollection(event(20));assert.equal(t.p.data.collectionRecoveryItems.length,0,'matching application resolves unknown')
 }
 {const t=fixture();t.p.onMediateOrder(event(20));t.p.onMediateToStation();t.p.setData({currentOrderId:21});await t.dialogs[0].success({confirm:true});assert.equal(t.writes,0,'old order dialog cannot submit')}
 {const t=fixture();t.p.onMediateOrder(event(20));t.p.onMediateToStation();t.app.globalData.userInfo={role:'DELIVERY',staffId:9,stationId:2,bindStatus:'BOUND'};await t.dialogs[0].success({confirm:true});assert.equal(t.writes,0,'old session dialog cannot submit')}
 {const t=fixture();t.p.onMediateOrder(event(20));t.p.onMediateToStation();const op=t.dialogs[0].success({confirm:true});await tick();t.resolve({code:0});await op;assert.equal(t.p.data.collectionRecoveryItems.length,0);assert(t.wx.__calls.toast.some(x=>x.title.includes('已申请退回')))}
 {const t=fixture();t.p.onMediateOrder(event(20));t.p.onMediateToStation();const op=t.dialogs[0].success({confirm:true});await tick();t.reject(Object.assign(Error('业务拒绝'),{businessRejected:true}));await op;assert.equal(t.p.data.collectionRecoveryItems.length,0);assert(t.wx.__calls.toast.some(x=>x.title==='业务拒绝'))}
 console.log('AQUAFLOW_SUITE_OK 14')
})().catch(e=>{console.error(e);process.exitCode=1})
