'use strict'
const assert = require('node:assert/strict')
const {loadPage,createWx,createApp} = require('./harness')
const tick = () => new Promise(setImmediate)
function setup() {
  let resolveWrite, rejectWrite, writes=0, dialogs=[]
  const wait=new Promise((resolve,reject)=>{resolveWrite=resolve;rejectWrite=reject})
  const wx=createWx(),app=createApp({_loginGeneration:1,globalData:{isLogin:true,userInfo:{staffId:7,stationId:11,role:'STATION_MANAGER'}}})
  const p=loadPage('miniapp-delivery/pages/station-mgmt/payments/index.js',{wx,app,stubs:{'utils/business-dialog':{showBusinessModal:o=>dialogs.push(o)},'api/station-mgmt':{confirmPayment:async()=>{writes++;return wait},getPendingPayments:async()=>({code:0,data:[]})}}})
  p._paymentsSeq=1;p._paymentsHidden=false;p.setData({view:'pending',list:[{id:42,orderId:44,amountText:'25.00'}]})
  return {p,wx,app,dialogs,resolveWrite,rejectWrite,get writes(){return writes}, e:{currentTarget:{dataset:{id:42}}}}
}
;(async()=>{
  {
    const t=setup();let reads=0;t.p.loadData=()=>{reads++;t.p._paymentsSeq++};t.p.onConfirm(t.e);t.p.onConfirm(t.e);assert.equal(t.dialogs.length,1)
    const cb=t.dialogs[0],write=cb.success({confirm:true});cb.complete();cb.success({confirm:true});t.p.onConfirm(t.e)
    assert.equal(t.writes,1);assert.equal(t.dialogs.length,1);t.resolveWrite();await write;assert.equal(t.p._confirmFlight,null);assert.equal(reads,1)
  }
  {
    const t=setup();t.p.onConfirm(t.e);t.dialogs[0].success({confirm:false});t.dialogs[0].complete();t.p.onConfirm(t.e);assert.equal(t.dialogs.length,2);assert.equal(t.writes,0)
  }
  {
    const t=setup();t.p.onConfirm(t.e);t.app.globalData.userInfo={staffId:8,stationId:12,role:'STATION_MANAGER'};await t.dialogs[0].success({confirm:true});assert.equal(t.writes,0);assert.equal(t.p._confirmFlight,null)
  }
  {
    const t=setup();t.p.onConfirm(t.e);const write=t.dialogs[0].success({confirm:true});t.p.onHide();t.resolveWrite();await write
    assert.equal(t.writes,1);assert(!t.wx.__calls.toast.some(x=>x.title==='已确认到账'));assert.equal(t.p._confirmFlight,null)
  }
  {
    const t=setup();let reads=0;t.p.loadData=()=>{reads++};t.p.onConfirm(t.e);const write=t.dialogs[0].success({confirm:true})
    t.p.onHide();t.p._paymentsHidden=false;t.p._paymentsSeq++;t.resolveWrite();await write
    assert.equal(reads,1);assert(!t.wx.__calls.toast.some(x=>x.title==='已确认到账'))
  }
  {
    const t=setup();t.p.onConfirm(t.e);const write=t.dialogs[0].success({confirm:true});t.rejectWrite(Error('合成失败'));await write;await tick()
    assert.equal(t.writes,1);assert.equal(t.p._confirmFlight,null);assert(t.wx.__calls.toast.some(x=>x.title==='合成失败'))
  }
  {
    const t=setup();t.p.onConfirm(t.e);const write=t.dialogs[0].success({confirm:true});t.rejectWrite(Object.assign(Error('网络超时，请重试'),{requestMayHaveOccurred:true}));await write
    assert(t.wx.__calls.toast.some(x=>x.title==='结果待核实，请查看收款记录'));assert(!t.wx.__calls.toast.some(x=>x.title==='已确认到账'))
  }
  console.log('AQUAFLOW_SUITE_OK 7')
})().catch(e=>{console.error(e);process.exitCode=1})
