// Execute customer handlers and WXML with controlled API responses; no live server or payment claims.
const assert=require('assert'),fs=require('fs'),path=require('path')
const {loadPage,createWx,createApp,armWatchdog,ROOT}=require('./harness')
const {parseWxml,renderElements}=require('./wxml-tree')
const event={currentTarget:{dataset:{id:77}}},tests=[],test=(name,run)=>tests.push({name,run})
function setup(extra={}) {
  const wx=createWx(),app=createApp(),calls={change:[],confirm:[],orders:[],sheets:[],reads:0}
  const row={id:77,stationId:1,quantity:2,depositRefund:60,returnDetail:{status:'APPROVED',pickupMode:'COMBINED',pickupModeText:'随送水订单收桶',companionOrderId:99,arrangementVersion:2,pickupFee:0,requiredBarrels:2,customerConfirmationRequired:false,customerConfirmationCurrent:false,...extra.detail}}
  const choices=(extra.choices || [0]).slice()
  wx.showActionSheet=o=>{calls.sheets.push(o);if(choices.length)o.success({tapIndex:choices.shift()});else o.fail({errMsg:'cancel'})}
  wx.showModal=o=>{wx.__calls.modal.push(o);if(extra.manualModal) return;if(extra.cancelModal)o.success({confirm:false});else o.success({confirm:true,content:extra.reason || '原送水单取消，改为到店'})}
  const page=loadPage('miniapp-user/pages/barrel/index.js',{wx,app,stubs:{
    'api/barrel':{changeBarrelReturnArrangement:async(id,body)=>{calls.change.push({id,body});if(extra.writeError)throw Error(extra.writeError);return {code:0}},confirmBarrelReturn:async(id,version)=>{calls.confirm.push({id,version});return {code:0}}},
    'api/order':{getOrders:async body=>{calls.orders.push(body);return {data:extra.orders || []}}}
  }})
  page.setData({records:[row]});page.loadData=async()=>{calls.reads++}
  return {page,wx,app,row,calls}
}
test('original return record and amount survive explicit change; expected version is captured',async()=>{
  const t=setup();await t.page.onReturnArrangement(event)
  assert.equal(t.calls.change.length,1);assert.equal(t.calls.change[0].id,77)
  assert.deepEqual({...t.calls.change[0].body,idempotencyKey:undefined},{pickupMode:'STORE',companionOrderId:null,expectedVersion:2,reason:'原送水单取消，改为到店',idempotencyKey:undefined})
  assert.equal(t.row.quantity,2);assert.equal(t.row.depositRefund,60);assert.equal(t.calls.confirm.length,0)
})
test('combined mode does not choose an order until the second picker explicitly selects one',async()=>{
  const t=setup({choices:[2,1],orders:[{id:10,stationId:1,status:2},{id:11,stationId:1,status:1},{id:12,stationId:1,status:5},{id:13,stationId:2,status:2}]})
  await t.page.onReturnArrangement(event);assert.equal(t.calls.sheets.length,2);assert.deepEqual(t.calls.sheets[1].itemList,['订单 10','订单 11']);assert.equal(t.calls.change[0].body.companionOrderId,11)
})
test('cancelled picker, empty reason, absent orders, paid fee and received request make no writes',async()=>{
  for(const options of [{choices:[]},{choices:[2],orders:[]},{reason:'   '},{cancelModal:true},{detail:{feePaymentStatus:2}},{detail:{status:'RECEIVED'}}]) {
    const t=setup(options);await t.page.onReturnArrangement(event);assert.equal(t.calls.change.length,0);assert.equal(t.calls.confirm.length,0)
  }
})
test('customer switch or hide/show during reason input discards the late action',async()=>{
  for(const hide of [false,true]) {
    const t=setup({manualModal:true}),promise=t.page.onReturnArrangement(event);await Promise.resolve();await Promise.resolve()
    if(hide){t.page.onHide();t.page.onShow()}else t.app.globalData.customerId=8
    t.wx.__calls.modal[0].success({confirm:true,content:'更改原因'});await promise;assert.equal(t.calls.change.length,0)
  }
})
test('fee or station-proposed arrangement confirmation sends the visible version; free original does not',async()=>{
  const free=setup();free.page.onReturnConfirm(event);assert.equal(free.calls.confirm.length,0)
  const fee=setup({detail:{customerConfirmationRequired:true,pickupFee:5}});fee.page.onReturnConfirm(event);await Promise.resolve();await Promise.resolve()
  assert.deepEqual(fee.calls.confirm,[{id:77,version:2}])
})
test('WXML exposes current arrangement, change entry and only required consent',()=>{
  const tree=parseWxml(fs.readFileSync(path.join(ROOT,'miniapp-user/pages/barrel/index.wxml'),'utf8'))
  for(const required of [false,true]) {
    const t=setup({detail:{customerConfirmationRequired:required}});const nodes=renderElements(tree,{...t.page.data,loading:false,showReturnModal:true,summary:{independentRights:true},customerBarrelAsset:[]},{includeText:true})
    assert(nodes.some(n=>n.attrs.bindtap==='onReturnArrangement'));assert.equal(nodes.some(n=>n.attrs.bindtap==='onReturnConfirm'),required)
    assert(nodes.some(n=>n.text.includes('关联送水单 #99')))
    // The request hint must describe the same free/required boundary as the action above.
    const hint=nodes.find(n=>n.tag==='text' && n.text.includes('未领桶的权益也可申请退还'))
    assert(hint && hint.text.includes('免费原安排无需再次确认'))
    assert(hint.text.includes('收费或水站新安排须先确认'))
  }
})
;(async()=>{const done=armWatchdog();let n=0;for(const t of tests){await t.run();console.log('PASS '+t.name);n++}done();console.log('AQUAFLOW_SUITE_OK '+n)})().catch(e=>{console.error(e);process.exit(1)})
