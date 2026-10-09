const assert = require('assert')
const path = require('path')
const Module = require('module')
const { loadPage, createWx, createApp, armWatchdog, ROOT } = require('./harness')
const done = armWatchdog(30000)
const suite = []
const test = (name, fn) => suite.push([name, fn])
const ok = data => ({ code: 0, data })
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r }); return { promise, resolve } }
const A = { id: 11, name: '原下单站', status: 1, statusText: '正常站点' }
const B = { id: 22, name: '名称很长的历史资产水站第二营业服务中心', status: 2, statusText: '停业 · 可查看历史资产' }
const summary = (n=2) => ({ independentRights: true, rightBuckets: n+1, occupiedBuckets: n,
  heldBuckets: n+1, availableRights: n, owedBuckets: 0, pendingDeliveryBuckets: 0, pendingReturns: 0,
  storageBuckets: 1, depositBalance: n*60 })
const holding = { productId: 5, productName: '桶装水', independentRights:true, assetQty: 3, heldTotalQty: 3, inTransitQty: 0,
  occupiedQty: 2, owedQty: 0, storageQty: 1, availableRights: 1, deposit: 60 }
function context() {
  const wx = createWx(), app = createApp()
  wx.__storage.set('selectedStation', A)
  app.globalData.accessToken = 'customer-seven'
  delete require.cache[require.resolve(path.join(ROOT,'miniapp-user/utils/asset-view-station.js'))]
  return { wx, app }
}
function page(rel, api={}, env=context()) {
  const stubs = {
    'api/asset-stations': { getAssetStation: async id => ok(id===22?B:A), listAssetStations: api.list || (async () => ok({ stations:[A,B],hasMore:false,nextStationId:null })) },
    'api/barrel': { getBarrelSummary: api.summary || (async () => ok(summary())),
      getBarrelSummaryByType: api.holdings || (async () => ok([holding])), getBarrelRecords: api.records || (async () => ok([])),
      previewBarrelReturn: api.preview || (async () => ok({ refundAmount:60 })), requestBarrelReturn: api.submit || (async () => ok(null)) },
    'api/ticket': { getTicketAccounts: api.accounts || (async () => ok([{remainQuantity:4}])),
      getTicketRecords: api.records || (async () => ok([])), getTicketPackages: api.packages || (async () => ok([])) },
    'api/product': { getStationProducts: api.products || (async () => ok([{id:5,category:1,name:'水',price:20}])) },
    'api/company': { getCompanyInfo: async () => ok(null) },
    'utils/request': { get: api.deposit || (async () => ok([])) }
  }
  const p = loadPage('miniapp-user/pages/'+rel+'.js',{stubs,...env})
  return { p, env, stubs }
}
const select = (p, station=B) => p.onAssetStationChange({detail:station})

test('view station changes never write global order station', async () => {
  const {p,env} = page('barrel/index')
  p.onLoad({}); await p.onShow(); await select(p)
  assert.strictEqual(p.data.viewStationId,22); assert.strictEqual(p.data.stationName,B.name)
  assert.strictEqual(env.wx.__storage.get('selectedStation').id,11)
  assert.strictEqual(env.wx.__calls.storageSet.length,0)
  assert.strictEqual(p.data.orderStationName,A.name)
})
test('detail links carry the viewed station to deposit and purchase', async () => {
  const {p,env} = page('barrel/index'); await select(p)
  p.onDepositRecords(); p.onPurchaseRights()
  assert.deepStrictEqual(env.wx.__calls.nav.map(n=>n.url),['/pages/deposit/records/index?stationId=22','/pages/barrel/purchase?stationId=22'])
})
test('session-local viewing scope survives asset navigation and order station change', async () => {
  const env=context(), first=page('barrel/index',{},env).p; await select(first)
  env.wx.__storage.set('selectedStation',{id:33,name:'新下单站'})
  const second=page('mine/index',{},env).p; await second.onShow()
  assert.strictEqual(second.data.viewStationId,22); assert.strictEqual(second.data.orderStationName,'新下单站')
  second.onMenuTap({currentTarget:{dataset:{url:'/pages/ticket/index'}}})
  assert.strictEqual(env.wx.__calls.nav.pop().url,'/pages/ticket/index?stationId=22')
})
test('same customer logout and relogin clears remembered viewing scope', async () => {
  const {p,env}=page('barrel/index'); await select(p)
  const token=require(path.join(ROOT,'miniapp-user/utils/token')); token.beginSession()
  const mine=page('mine/index',{},env).p; await mine.onShow()
  assert.strictEqual(mine.data.viewStationId,11)
})
test('returning to an existing page follows the later asset choice', async () => {
  const env=context(),mine=page('mine/index',{},env).p
  await select(mine,A);const detail=page('barrel/index',{},env).p
  detail.onLoad({stationId:11});await detail.onShow();await select(detail,B)
  await mine.onShow();assert.strictEqual(mine.data.viewStationId,22)
  assert.strictEqual(env.wx.__storage.get('selectedStation').id,11)
})
test('explicit route beats global default and reads the correct station', async () => {
  const calls=[], {p,env}=page('deposit/records/index',{deposit:async (_,query)=>{calls.push(query.stationId);return ok([])}})
  p.onLoad({stationId:'22'}); await p.onShow()
  assert.deepStrictEqual(calls,[22]); assert.strictEqual(env.wx.__storage.get('selectedStation').id,11)
})
test('late station A result cannot overwrite station B or its loading state', async () => {
  const slow=deferred(), {p}=page('barrel/index',{summary:id=>id===11?slow.promise:Promise.resolve(ok(summary(0)))})
  const first=p.loadData(); assert.strictEqual(p.data.summary,null)
  await select(p); slow.resolve(ok(summary(9))); await first
  assert.strictEqual(p.data.viewStationId,22); assert.strictEqual(p.data.summary.depositBalance,0); assert.strictEqual(p.data.loading,false)
})
test('latest same-station refresh wins over older response', async () => {
  const slow=deferred();let calls=0
  const {p}=page('barrel/index',{summary:()=>++calls===1?slow.promise:Promise.resolve(ok(summary(1)))})
  const old=p.loadData(); await p.loadData();slow.resolve(ok(summary(8)));await old
  assert.strictEqual(p.data.summary.depositBalance,60)
})
test('failed barrel refresh clears previous numbers and records instead of showing zero', async () => {
  let fail=false; const failOr = data => async()=>{if(fail)throw new Error('离线');return ok(data)}
  const {p}=page('barrel/index',{summary:failOr(summary()),holdings:failOr([holding]),records:failOr([{id:9}])})
  await p.loadData();fail=true;const next=p.loadData()
  assert.strictEqual(p.data.summary,null);assert.deepStrictEqual(p.data.records,[]);await next
  assert.strictEqual(p.data.summaryFailed,true);assert.strictEqual(p.data.holdingsFailed,true);assert.strictEqual(p.data.recordsFailed,true)
  assert.ok(p.data.loadError); assert.strictEqual(p.data.maxReturnQty,0)
})
test('missing barrel quantities cannot turn into invented zero', async () => {
  const {p}=page('barrel/index',{holdings:async()=>ok([{productId:5,assetQty:2}])});await p.loadData()
  assert.strictEqual(p.data.holdingsFailed,true);assert.deepStrictEqual(p.data.customerBarrelAsset,[])
})
test('malformed summary and deposit records are retryable failure, not zero', async () => {
  const {p}=page('barrel/index',{summary:async()=>ok({})});await p.loadData()
  assert.strictEqual(p.data.summary,null);assert.strictEqual(p.data.summaryFailed,true);assert.ok(p.data.loadError)
  const deposit=page('deposit/records/index',{deposit:async()=>ok([{id:1,amount:null}])}).p
  await deposit.loadRecords();assert.deepStrictEqual(deposit.data.records,[]);assert.ok(deposit.data.errorText)
})
test('successful zero and empty responses are not failure', async () => {
  const {p}=page('barrel/index',{summary:async()=>ok(summary(0)),holdings:async()=>ok([]),records:async()=>ok([])})
  await p.loadData();assert.strictEqual(p.data.summary.depositBalance,0);assert.strictEqual(p.data.recordsFailed,false);assert.strictEqual(p.data.holdingsFailed,false)
})
test('ticket station failure clears old accounts and purchase products', async () => {
  const {p}=page('ticket/index',{accounts:async id=>{if(id===22)throw new Error('离线');return ok([{remainQuantity:'4'}])},
    products:async id=>{if(id===22)throw new Error('离线');return ok([{id:5,category:1}])}})
  await p.loadData();assert.strictEqual(p.data.totalTickets,4);await select(p)
  assert.strictEqual(p.data.totalTickets,null);assert.deepStrictEqual(p.data.accounts,[]);assert.deepStrictEqual(p.data.buyProducts,[]);assert.strictEqual(p.data.accountsFailed,true)
})
test('invalid ticket quantity is failure while an empty account list is true zero', async () => {
  let empty=false;const {p}=page('ticket/index',{accounts:async()=>ok(empty?[]:[{remainQuantity:''}])})
  await p.loadData();assert.strictEqual(p.data.accountsFailed,true);assert.strictEqual(p.data.totalTickets,null)
  empty=true;await p.loadData();assert.strictEqual(p.data.accountsFailed,false);assert.strictEqual(p.data.totalTickets,0)
})
test('late deposit response is ignored after changing viewed station', async () => {
  const slow=deferred(),{p}=page('deposit/records/index',{deposit:(_,q)=>q.stationId===11?slow.promise:Promise.resolve(ok([{id:22,amount:-60}]))})
  const old=p.loadRecords();await select(p);slow.resolve(ok([{id:11,amount:900}]));await old
  assert.strictEqual(p.data.records[0].id,22);assert.strictEqual(p.data.records[0].amountState,'out')
})
test('hidden page does not publish pending asset read', async () => {
  const slow=deferred(),{p}=page('barrel/index',{summary:()=>slow.promise})
  const read=p.loadData();p.onHide();slow.resolve(ok(summary()));await read
  assert.strictEqual(p.data.summary,null)
})
test('identity change invalidates pending asset read', async () => {
  const slow=deferred(),{p,env}=page('mine/index',{summary:()=>slow.promise})
  const read=p.loadAssets();env.app.globalData.customerId=8;env.app.globalData.accessToken='customer-eight';slow.resolve(ok(summary()));await read
  assert.strictEqual(p.data.deposit,null)
})
test('return preview uses asset station and ignores a previous quantity result', async () => {
  const slow=deferred(),seen=[],{p}=page('barrel/index',{preview:(product,qty,station)=>{seen.push(station);return qty===1?slow.promise:Promise.resolve(ok({refundAmount:120}))}})
  await select(p);p.setData({returnForm:{productId:5,quantity:1}});const first=p.refreshPreview()
  p.setData({returnForm:{productId:5,quantity:2}});await p.refreshPreview();slow.resolve(ok({refundAmount:60}));await first
  assert.deepStrictEqual(seen,[22,22]);assert.strictEqual(p.data.preview.refundAmount,120)
})
test('switching scope closes unfinished forms and invalidates delayed price list', async () => {
  const slow=deferred(),{p}=page('ticket/index',{packages:()=>slow.promise})
  await p.loadData();p.setData({showPurchase:true,buyForm:{productId:5}});const prices=p.loadBuyPackages(5)
  await select(p);slow.resolve(ok([{price:99}]));await prices
  assert.strictEqual(p.data.showPurchase,false);assert.deepStrictEqual(p.data.buyPackages,[])
})
test('scope cannot change during existing money or arrangement request', async () => {
  const {p}=page('barrel/index');await p.loadData();p.setData({submitting:true});await select(p)
  assert.strictEqual(p.data.viewStationId,11);p.setData({submitting:false});p._arrangementFlight=true;await select(p)
  assert.strictEqual(p.data.viewStationId,11)
})

function picker(api) {
  const env=context();global.wx=env.wx;global.getApp=()=>env.app
  let config;global.Component=c=>{config=c}
  const file=path.join(ROOT,'miniapp-user/components/AssetStationView/index.js'),original=Module._load
  Module._load=function(request){if(request.endsWith('api/asset-stations'))return{listAssetStations:api};return original.apply(this,arguments)}
  try{delete require.cache[require.resolve(file)];require(file)}finally{Module._load=original}
  const events=[],p={data:JSON.parse(JSON.stringify(config.data)),properties:{stationId:11,busy:false},
    setData(v){Object.assign(this.data,v)},triggerEvent(name,data){events.push({name,data})}}
  Object.entries(config.methods).forEach(([key,fn])=>{p[key]=fn.bind(p)});config.lifetimes.attached.call(p)
  return {p,events,env,config}
}
test('picker pages own stopped and zero-asset stations and chooses without writes', async () => {
  const seen=[],{p,events,env}=picker(async cursor=>{seen.push(cursor);return ok(cursor?{stations:[B],hasMore:false,nextStationId:null}:{stations:[A],hasMore:true,nextStationId:11})})
  await p.openPicker();await p.more();p.choose({currentTarget:{dataset:{id:'22'}}})
  assert.deepStrictEqual(seen,[0,11]);assert.strictEqual(events[0].data.status,2);assert.strictEqual(p.data.open,false)
  assert.strictEqual(env.wx.__calls.storageSet.length,0)
})
test('picker failure has retry, closed or previous-session responses are ignored', async () => {
  let slow=deferred(),{p,env}=picker(()=>slow.promise)
  const read=p.openPicker();p.close();slow.resolve(ok({stations:[B],hasMore:false,nextStationId:null}));await read
  assert.deepStrictEqual(p.data.stations,[])
  slow=deferred();const second=p.openPicker();env.app.globalData.accessToken='new-login';slow.resolve(ok({stations:[B],hasMore:false,nextStationId:null}));await second
  assert.deepStrictEqual(p.data.stations,[])
  const retry=picker(async()=>{throw new Error('列表离线')});await retry.p.openPicker()
  assert.strictEqual(retry.p.data.errorText,'列表离线');assert.strictEqual(retry.p.data.loading,false)
})

;(async()=>{
  let passed=0
  for(const [name,fn] of suite){await fn();passed++;console.log('PASS '+name)}
  done();console.log('AQUAFLOW_SUITE_OK '+passed)
})().catch(error=>{done();console.error(error);process.exitCode=1})
