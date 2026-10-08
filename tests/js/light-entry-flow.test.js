const assert = require('assert')
const fs = require('fs'), path = require('path')
const { loadPage, createWx, createApp, armWatchdog, ROOT } = require('./harness')
const done = armWatchdog(), tests = []
const test = (name, run) => tests.push({ name, run })
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r }); return { promise, resolve } }
function home(summary = async () => ({ data: {} }), products = []) {
  const wx = createWx(), app = createApp()
  const page = loadPage('miniapp-user/pages/home/index.js', { wx, app, stubs: {
    'api/barrel': { getBarrelSummary: summary, getBarrelSummaryByType: async () => ({ data: [] }) },
    'api/product': { getStationProducts: async () => ({ data: products }) },
    'api/address': { getAddresses: async () => ({ data: [] }) },
    'api/order': { getOrders: async () => ({ data: [] }), getOrderDetail: async () => ({ data: null }) },
    'api/station': { getStationStatus: async () => ({ data: {} }) },
    'api/notification': { getUnreadNotifications: async () => ({ data: [] }) },
    'api/payment': { getQuote: async () => { throw new Error('No quote expected') } }
  } })
  page.loadStationStatus = async () => {}
  page.loadHomeOrders = async () => ({ data: [] })
  page.setData({ isLogin: true, state: 'ready', currentStationId: 11 })
  return { page, wx }
}
test('zero assets retain the selected-station explanation and existing barrel route', async () => {
  const {page,wx}=home(async()=>({data:{rightBuckets:0,owedBuckets:0,depositBalance:0}}))
  await page.loadData()
  assert.equal(page.data.barrelVisible,true)
  assert.equal(page.data.barrelLine2,'查看说明与办理入口')
  assert.ok(!page.data.barrelLine2.includes('可退'))
  page.onGoBarrel()
  assert.equal(wx.__calls.nav.at(-1).url,'/pages/barrel/index')
})
test('failed asset reading is not treated as a successful zero balance', async () => {
  const {page}=home(async()=>{throw new Error('synthetic network failure')})
  await page.loadData()
  assert.equal(page.data.barrelVisible,false)
  assert.equal(page.data.barrelLine2,'')
  assert.ok(page.data.loadError.includes('桶账'))
})
test('stationless page does not expose a station asset entry', async () => {
  const {page}=home(); page.setData({currentStationId:null})
  page.renderBarrelLine({depositBalance:30})
  assert.equal(page.data.barrelVisible,false)
})
test('existing debt reminders and actual deposit amount remain visible', async () => {
  const {page}=home(async()=>({data:{owedBuckets:2,depositBalance:60}})); await page.loadData()
  assert.ok(page.data.barrelLine2.includes('欠 2 个空桶'))
  assert.ok(page.data.barrelLine2.includes('押金余额 ¥60'))
})
test('late station response cannot restore the prior station asset entry', async () => {
  const wait=deferred(), {page}=home(()=>wait.promise)
  const pending=page.loadData(); page.setData({currentStationId:12})
  wait.resolve({data:{depositBalance:60}}); await pending
  assert.equal(page.data.barrelVisible,false)
})
test('home prices use the same product units as order details', async () => {
  const {page}=home(undefined,[{id:1,category:1,price:12},{id:2,category:2,price:2},{id:3,category:3,price:99},{id:4,price:9}])
  await page.loadData()
  assert.deepStrictEqual(page.data.productsView.map(p=>p.quantityUnit),['桶','瓶','台','件'])
})
test('completed contact uses the receiver snapshot and catches the card tap', async () => {
  const text=fs.readFileSync(path.join(ROOT,'miniapp-delivery/pages/home/index.wxml'),'utf8')
  const completed=text.slice(text.indexOf('<!-- 已完成列表 -->'),text.indexOf('<view class="loading-state"'))
  assert.ok(completed.includes('catchtap="onCallCustomer" data-phone="{{item.receiverPhone || item.customerPhone}}"'))
  const wx=createWx(), calls=[]; wx.makePhoneCall=options=>calls.push(options.phoneNumber)
  const page=loadPage('miniapp-delivery/pages/home/index.js',{wx,app:createApp()})
  page.onCallCustomer({currentTarget:{dataset:{phone:'synthetic-receiver'}}})
  assert.deepStrictEqual(calls,['synthetic-receiver'])
  page.onCallCustomer({currentTarget:{dataset:{phone:''}}})
  assert.equal(calls.length,1); assert.ok(wx.__calls.toast.at(-1).title.includes('没有可拨'))
})
;(async()=>{let passed=0;for(const t of tests){try{await t.run();passed++;console.log('PASS '+t.name)}catch(e){console.error('FAIL '+t.name,e);process.exitCode=1}}done();if(passed===tests.length)console.log('AQUAFLOW_SUITE_OK '+passed)})()
