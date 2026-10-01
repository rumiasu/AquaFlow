const assert = require('assert')
const { loadPage, createWx, createApp, armWatchdog } = require('./harness')
const { STORAGE_KEYS } = require('../../miniapp-user/utils/storage-keys')
let passed=0
const done=armWatchdog()
async function test(name,fn){await fn();passed++;console.log('  ✓ '+name)}
function setup(scenario={}) {
  const wx=createWx(),app=createApp({globalData:{isLogin:true}}),calls=[]
  wx.setStorageSync(STORAGE_KEYS.CUSTOMER_ID,7)
  const stubs={
    'api/product':{getStationProducts:async()=>({data:[{id:5,category:1,name:'水'}]})},
    'api/barrel':{
      quoteBarrelRight:async()=>({data:{amount:30,stationName:'原购买站'}}),
      getBarrelRightPurchases:async()=>({data:[]}),
      purchaseBarrelRight:async body=>{calls.push(body);if(scenario.fail)throw Error('网络未知，重试原请求');if(scenario.hold)return new Promise(r=>{scenario.release=r});return {data:{status:scenario.status===undefined?2:scenario.status}}},
      withdrawBarrelRightPurchase:async()=>({})
    },
    'utils/station':{resolveStationId:async()=>1}
  }
  return {page:loadPage('miniapp-user/pages/barrel/purchase.js',{wx,app,stubs}),wx,app,calls,scenario}
}
async function main(){
  await test('未确认现金不会提示权益已到账',async()=>{const t=setup({status:1});await t.page.onLoad({stationId:1});await t.page.onPurchase();assert(t.wx.__calls.modal.at(-1).content.includes('确认实际收款后'));assert(!t.wx.getStorageSync('barrel-right-intent'));assert.equal(t.page.data.busy,false)})
  await test('实际款确认提示下次送桶，不假装已经拿到桶',async()=>{const t=setup();await t.page.onLoad({stationId:1});await t.page.onPurchase();assert(t.wx.__calls.modal.at(-1).content.includes('下一次送水'));assert.equal(t.page.data.stationName,'原购买站')})
  await test('未知失败重试同一幂等键，换客户不用旧键',async()=>{const t=setup({fail:true});await t.page.onLoad({stationId:1});await t.page.onPurchase();await t.page.onPurchase();assert.equal(t.calls[0].idempotencyKey,t.calls[1].idempotencyKey);t.wx.setStorageSync(STORAGE_KEYS.CUSTOMER_ID,8);await t.page.onPurchase();assert.notEqual(t.calls[1].idempotencyKey,t.calls[2].idempotencyKey)})
  await test('连点只创建一笔独立押金意图',async()=>{const t=setup({hold:true});await t.page.onLoad({stationId:1});const first=t.page.onPurchase();await t.page.onPurchase();assert.equal(t.calls.length,1);t.scenario.release({data:{status:2}});await first;assert.equal(t.page.data.busy,false)})
  await test('未获得报价及退出登录不提交资产购买',async()=>{const t=setup();await t.page.onPurchase();assert.equal(t.calls.length,0);await t.page.onLoad({stationId:1});t.wx.removeStorageSync(STORAGE_KEYS.CUSTOMER_ID);await t.page.onPurchase();assert.equal(t.calls.length,0);assert.equal(t.wx.__calls.toast.at(-1).title,'请先登录')})
  for (const purpose of ['独立桶押金','上门收桶费']) {
    await test(purpose+'确认不伪称水票或订单已付款',async()=>{
      const wx=createWx();wx.__modalAutoConfirm=false;let confirms=0
      const page=loadPage('miniapp-delivery/pages/station-mgmt/payments/index.js',{wx,app:createApp(),stubs:{
        'api/station-mgmt':{getPendingPayments:async()=>({data:[{id:8,amount:30,purposeText:purpose}]}),confirmPayment:async()=>{confirms++}}
      }})
      await page.loadData();page.onConfirm({currentTarget:{dataset:{id:8}}})
      assert.equal(page.data.list[0].isTicketPurchase,false);assert(wx.__calls.modal.at(-1).content.includes(purpose))
      assert(!wx.__calls.modal.at(-1).content.includes('订单将标记'));assert(!wx.__calls.modal.at(-1).content.includes('张水票'))
      assert.equal(confirms,0)
    })
  }
  done();console.log('AQUAFLOW_SUITE_OK '+passed)
}
main().catch(err=>{done();console.error(err);process.exitCode=1})
