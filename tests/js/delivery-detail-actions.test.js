const assert = require('assert'), fs = require('fs'), path = require('path')
const { loadPage, createWx, createApp, armWatchdog, ROOT } = require('./harness')
const done = armWatchdog(30000), tests = []
const test = (name, run) => tests.push({ name, run })
const tick = () => new Promise(resolve => setImmediate(resolve))
const deferred = () => { let resolve, reject; const promise = new Promise((a,b) => { resolve=a; reject=b }); return { promise, resolve, reject } }
const source = 'miniapp-delivery/pages/order/detail.js'
function fixture(extra = {}) {
  const wx = createWx(), modals=[], sheets=[], calls=[]
  wx.showModal = o => modals.push(o); wx.showActionSheet = o => sheets.push(o)
  const app = createApp({ _loginGeneration: 1, globalData: { isLogin:true, accessToken:'a', userInfo:{ role:'DELIVERY',staffId:8,stationId:1 } }, isStationManager() {return this.globalData.userInfo.role==='STATION_MANAGER'} })
  const order = { id:20,status:2,stationId:1,deliveryStaffId:8,items:[],paymentMethod:2,paymentStatus:1,needCollect:true,totalAmount:48,payStateText:'待收款', ...extra.order }
  const api = { getOrderDetail: async () => ({data:order}), getStaffList:async()=>({data:[{id:9,name:'同事'}]}) }
  for(const k of ['confirmCollection','cancelTransferOrder','transferOrder','returnToStation','requestCancel','reportOrder'])api[k]=async(...args)=>{calls.push({name:k,args});return {data:{}}}
  Object.assign(api,extra.api)
  const request={ get:async()=>({data:[]}),post:async(...args)=>{calls.push({name:'post',args});return {data:{id:70}}}, ...extra.request }
  delete require.cache[require.resolve(path.join(ROOT,'miniapp-delivery/utils/delivery-problem.js'))]
  const page=loadPage(source,{wx,app,stubs:{'api/delivery':api,'utils/request':request}})
  page.setData({orderId:20,order,loading:false,isManager:false,hasMoreActions:true})
  return {page,app,wx,order,api,modals,sheets,calls}
}
const more = (t,act) => {t.page.onOpenMoreActions();return t.page.onMoreAction({currentTarget:{dataset:{act}}})}
for(const [name,change] of [['hide',t=>t.page.onHide()],['unload',t=>t.page.onUnload()],['login generation',t=>t.app._loginGeneration++],['staff switch',t=>t.app.globalData.userInfo.staffId=99],['station switch',t=>t.app.globalData.userInfo.stationId=2],['order switch',t=>t.page.setData({orderId:21})]]) {
 test('late detail discarded after '+name,async()=>{const d=deferred(),t=fixture({api:{getOrderDetail:()=>d.promise}});const pending=t.page.loadOrderDetail(20);change(t);d.resolve({data:{...t.order,orderNo:'late'}});await pending;assert.notEqual(t.page.data.order.orderNo,'late')})
}
test('new detail wins over slower older success',async()=>{const a=deferred(),b=deferred();let n=0;const t=fixture({api:{getOrderDetail:()=>++n===1?a.promise:b.promise}});const p=t.page.loadOrderDetail(20),q=t.page.loadOrderDetail(20);b.resolve({data:{...t.order,orderNo:'new'}});await q;a.resolve({data:{...t.order,orderNo:'old'}});await p;assert.equal(t.page.data.order.orderNo,'new')})
test('old failure cannot toast or clear latest loading',async()=>{const a=deferred(),b=deferred();let n=0;const t=fixture({api:{getOrderDetail:()=>++n===1?a.promise:b.promise}});const p=t.page.loadOrderDetail(20),q=t.page.loadOrderDetail(20);a.reject(Error('old'));await p;assert.equal(t.page.data.loading,true);assert.equal(t.wx.__calls.toast.length,0);b.resolve({data:t.order});await q})
test('token renewal preserves current read',async()=>{const d=deferred(),t=fixture({api:{getOrderDetail:()=>d.promise}});const p=t.page.loadOrderDetail(20);t.app.globalData.accessToken='b';d.resolve({data:{...t.order,orderNo:'renewed'}});await p;assert.equal(t.page.data.order.orderNo,'renewed')})
test('onLoad and onShow initiate only one initial read',async()=>{let n=0;const t=fixture({api:{getOrderDetail:async()=>{n++;return {data:t.order}}}});t.page.onLoad({id:'20'});await t.page.onShow();assert.equal(n,1)})
for(const method of ['loadFloorPhotos','loadDeliveryPhotos','loadPayments']) {
 test(method+' rejects stale supplement after newer detail',async()=>{const d=deferred();let n=0;const t=fixture({request:{get:()=>++n===1?d.promise:Promise.resolve({data:[]})}});t.app.globalData.userInfo.role='STATION_MANAGER';const p=t.page[method](20);await t.page.loadOrderDetail(20);d.resolve({data:[{id:1,type:method==='loadFloorPhotos'?3:1,url:'stale',status:2}]});await p;await tick();assert.ok(!(t.page.data.floorPhotos||[]).includes('stale'));assert.ok(!(t.page.data.deliveryPhotos||[]).includes('stale'));assert.ok(!(t.page.data.payments||[]).some(x=>x.id===1))})
}
test('more cannot call an arbitrary method name',()=>{const t=fixture();let called=0;t.page.onDispatchOrder=()=>called++;more(t,'onDispatchOrder');assert.equal(called,0)})
test('closed sheet ignores a queued second tap',()=>{const t=fixture();let n=0;t.page.onReport=()=>n++;more(t,'onReport');t.page.onMoreAction({currentTarget:{dataset:{act:'onReport'}}});assert.equal(n,1)})
test('sheet closes before original action runs',()=>{const t=fixture();t.page.onReport=()=>assert.equal(t.page.data.showMoreActions,false);more(t,'onReport')})
test('permission changes while sheet open block dispatch',()=>{const t=fixture();let n=0;t.page.onReport=()=>n++;t.page.onOpenMoreActions();t.app.globalData.userInfo.stationId=2;t.page.onMoreAction({currentTarget:{dataset:{act:'onReport'}}});assert.equal(n,0)})
test('loading does not open a stale action sheet',()=>{const t=fixture();t.page.setData({loading:true});t.page.onOpenMoreActions();assert.equal(t.page.data.showMoreActions,false)})
for(const life of ['onHide','onUnload'])test(life+' closes sheet and blocks its old callback',async()=>{const t=fixture();const p=more(t,'onReturnToStation');assert.equal(t.modals.length,1);t.page[life]();t.modals[0].success({confirm:true});await p;assert.equal(t.page.data.showMoreActions,false);assert.equal(t.calls.length,0)})
test('return modal is single flight and can retry after cancel',async()=>{const t=fixture();const p=more(t,'onReturnToStation');more(t,'onReturnToStation');assert.equal(t.modals.length,1);t.modals[0].success({confirm:false});await p;const q=more(t,'onReturnToStation');assert.equal(t.modals.length,2);t.modals[1].success({confirm:false});await q})
test('transfer delayed staff lookup cannot open on old page',async()=>{const d=deferred(),t=fixture({api:{getStaffList:()=>d.promise}});const p=more(t,'onTransfer');t.page.onHide();d.resolve({data:[{id:9,name:'同事'}]});await p;assert.equal(t.sheets.length,0);assert.equal(t.modals.length,0)})
test('cancel request callback from previous login cannot post',async()=>{const t=fixture();const p=more(t,'onRequestCancel');t.app._loginGeneration++;t.modals[0].success({confirm:true});await p;assert.equal(t.calls.length,0);assert.equal(t.modals.length,1)})
test('withdraw stays available only for personal transfer',async()=>{for(const kind of ['TRANSFER','RETURN','DIRECTED']){const t=fixture({order:{transferPending:true,transferPendingSubKind:kind}});await t.page.loadOrderDetail(20);assert.equal(t.page.data.order.canWithdrawTransfer,kind==='TRANSFER');assert.equal(t.page.data.order.canComplete,true)}})
test('manager does not see personal transfer intermediate label',async()=>{const t=fixture({order:{transferPending:true,transferPendingSubKind:'TRANSFER',transferText:'转单中'}});t.app.globalData.userInfo.role='STATION_MANAGER';await t.page.loadOrderDetail(20);assert.ok(!t.page.data.order.labels.some(x=>x.type==='transfer'))})
for(const [name,change,allowed] of [['cash delivery',{},true],['cash delivered',{status:3},true],['pending',{status:1},false],['completed',{status:4},false],['cancelled',{status:5},false],['wechat',{paymentMethod:1},false],['tickets',{paymentMethod:3},false],['paid',{paymentStatus:2,needCollect:false},false],['refunded',{paymentStatus:3},false],['payment cancelled',{paymentStatus:4},false],['foreign fulfillment',{deliveryStationId:2},false],['other courier',{deliveryStaffId:9},false],['unassigned',{deliveryStaffId:null},false]]) {
 test('collection availability: '+name,async()=>{const t=fixture({order:change});await t.page.loadOrderDetail(20);assert.equal(t.page.data.order.canCollect,allowed)})
}
test('manager may collect a delivered order of current fulfillment station',async()=>{const t=fixture({order:{status:3,deliveryStaffId:9}});t.app.globalData.userInfo.role='STATION_MANAGER';await t.page.loadOrderDetail(20);assert.equal(t.page.data.order.canCollect,true)})
test('collection posts once and refreshes backend facts',async()=>{const t=fixture({order:{status:3}});await t.page.loadOrderDetail(20);const p=t.page.onConfirmCollection();t.page.onConfirmCollection();assert.equal(t.modals.length,1);t.modals[0].success({confirm:true});await p;assert.equal(t.calls.filter(x=>x.name==='confirmCollection').length,1);assert.deepStrictEqual(t.calls[0].args,[20])})
test('collection modal callback after identity change does not post',async()=>{const t=fixture({order:{status:3}});await t.page.loadOrderDetail(20);const p=t.page.onConfirmCollection();t.app.globalData.userInfo.stationId=2;t.modals[0].success({confirm:true});await p;assert.equal(t.calls.length,0)})
test('layout retains all contextual actions and uses a bounded scroll sheet',()=>{const w=fs.readFileSync(path.join(ROOT,source.replace('.js','.wxml')),'utf8'),css=fs.readFileSync(path.join(ROOT,source.replace('.js','.wxss')),'utf8');for(const act of ['onReport','onTransfer','onCancelTransfer','onReturnToStation','onRequestCancel','onRefusalWriteOff'])assert.ok(w.includes('data-act="'+act+'"'));assert.ok(w.includes('bindtap="onConfirmCollection"'));assert.ok(w.includes('class="sheet-actions" scroll-y'));assert.ok(w.includes('catchtouchmove="onSheetTouchMove"'));assert.ok(!w.includes('aux-offline" wx:if'));assert.match(css,/\.sheet-actions\s*\{[^}]*max-height:\s*60vh/s);assert.match(css,/\.action-complete\s*\{[^}]*background:\s*var\(--primary-color\)/s)})

test('native modal failure releases action and allows retry',async()=>{const t=fixture();const p=more(t,'onReturnToStation');t.modals[0].fail();await p;assert.equal(t.page.data.actionBusy,false);const q=more(t,'onReturnToStation');assert.equal(t.modals.length,2);t.modals[1].success({confirm:false});await q})
test('stale same-identity login cannot open actions from old facts',async()=>{const t=fixture();await t.page.loadOrderDetail(20);t.app._loginGeneration++;t.page.onOpenMoreActions();assert.equal(t.page.data.showMoreActions,false);await t.page.onConfirmCollection();assert.equal(t.modals.length,0)})
test('fresh read cancels previous identity modal without trapping busy state',async()=>{const t=fixture();await t.page.loadOrderDetail(20);const p=t.page.onConfirmCollection();t.app._loginGeneration++;await t.page.loadOrderDetail(20);await p;assert.equal(t.page.data.actionBusy,false);t.modals[0].success({confirm:true});assert.equal(t.calls.length,0);const q=t.page.onConfirmCollection();assert.equal(t.modals.length,2);t.modals[1].success({confirm:false});await q})
test('collection failure shows server message and permits retry',async()=>{const t=fixture({api:{confirmCollection:async()=>{throw Error('真实拒绝原因')}}});const p=t.page.onConfirmCollection();t.modals[0].success({confirm:true});await p;assert.equal(t.wx.__calls.toast.at(-1).title,'真实拒绝原因');assert.equal(t.page.data.actionBusy,false)})
test('late collection result does not toast or repaint old page',async()=>{const d=deferred(),t=fixture({api:{confirmCollection:()=>d.promise}});const p=t.page.onConfirmCollection();t.modals[0].success({confirm:true});await tick();t.page.onUnload();let writes=0;t.page.setData=()=>writes++;d.resolve({});await p;assert.equal(writes,0);assert.equal(t.wx.__calls.toast.length,0)})
test('report still uses shared reason keys and original payload',async()=>{const t=fixture();const p=more(t,'onReport');t.sheets[0].success({tapIndex:2});await tick();assert.equal(t.modals.length,1);t.modals[0].success({confirm:true});await p;assert.deepStrictEqual(t.calls[0],{name:'reportOrder',args:[20,{reason:'客户拒收',reasonKey:'customer_refuse'}]});assert.equal(t.page.data.actionBusy,false)})
test('report reason callback after hide cannot display confirmation or post',async()=>{const t=fixture();const p=more(t,'onReport');t.page.onHide();t.sheets[0].success({tapIndex:2});await p;assert.equal(t.modals.length,0);assert.equal(t.calls.length,0)})
test('shared homepage report keeps default dialogs and onDone',async()=>{const t=fixture();let n=0;const report=require(path.join(ROOT,'miniapp-delivery/utils/delivery-problem.js')).reportDeliveryProblem;const p=report(20,{onDone:()=>n++});t.sheets[0].success({tapIndex:0});await tick();t.modals[0].success({confirm:true});await p;assert.equal(n,1);assert.deepStrictEqual(t.calls[0].args,[20,{reason:'客户不接电话',reasonKey:'customer_unreachable'}])})
function withdrawal(extra = {}) {
 const reads=[], writes=[], fresh={transferPending:false,transferPendingSubKind:null,transferText:'',transferTarget:false}
 let applied=false
 const t=fixture({order:{transferPending:true,transferPendingSubKind:'TRANSFER',transferText:'转单中',transferTarget:false},api:{
  getOrderDetail:async id=>{reads.push(id);if(extra.read) return extra.read(t,applied,id);return {data:{...t.order,...(applied?fresh:{})}}},
  cancelTransferOrder:async id=>{writes.push(id);if(extra.write) await extra.write();applied=true}
 }})
 return Object.assign(t,{reads,writes,fresh})
}
async function startWithdrawal(t) {const p=t.page.onCancelTransfer();assert.equal(t.modals.length,1);t.modals[0].success({confirm:true});await tick();return {pending:p}}
test('withdraw sends exactly one write, refreshes same order and replaces pending actions and label',async()=>{
 const t=withdrawal();await t.page.loadOrderDetail(20)
 assert.equal(t.page.data.order.canWithdrawTransfer,true);assert.equal(t.page.data.order.canTransfer,false)
 assert.ok(t.page.data.order.labels.some(x=>x.type==='transfer'&&x.text==='转单中'))
 assert.equal(t.page.data.order.canComplete,true);t.page.onComplete();assert.equal(t.wx.__calls.nav.at(-1).url,'/pages/order/complete?id=20')
 const p=more(t,'onCancelTransfer');more(t,'onCancelTransfer');assert.equal(t.modals.length,1)
 t.modals[0].success({confirm:true});await p
 assert.deepStrictEqual(t.writes,[20]);assert.deepStrictEqual(t.reads,[20,20])
 for(const [k,v] of Object.entries({transferPending:false,transferPendingSubKind:null,canWithdrawTransfer:false,canTransfer:true,canReturn:true,canRequestCancel:true,canComplete:true}))assert.equal(t.page.data.order[k],v,k)
 assert.equal(t.page.data.order.labels.some(x=>x.type==='transfer'),false);assert.equal(t.page.data.cancelTransferBusy,false)
 t.page.onComplete();assert.equal(t.wx.__calls.nav.length,2)
})
test('manager receives no personal intermediate label or withdraw action before and after fresh response',async()=>{
 let response;const t=withdrawal({read:t=>({data:response||t.order})});t.app.globalData.userInfo={role:'STATION_MANAGER',staffId:99,stationId:1}
 for(const data of [t.order,{...t.order,...t.fresh}]){response=data;await t.page.loadOrderDetail(20);assert.equal(t.page.data.order.canWithdrawTransfer,false);assert.equal(t.page.data.order.labels.some(x=>x.type==='transfer'),false);await t.page.onCancelTransfer()}
 assert.equal(t.modals.length,0);assert.equal(t.writes.length,0)
})
test('successful withdraw and failed refresh retain accurate outcome and GET-only recovery',async()=>{
 let broken=true;const t=withdrawal({read:(t,applied)=>{if(applied&&broken)throw Error('详情暂不可用');return {data:{...t.order,...(applied?t.fresh:{})}}}})
 await t.page.loadOrderDetail(20);const {pending}=await startWithdrawal(t);await pending
 assert.deepStrictEqual(t.writes,[20]);assert.equal(t.page.data.detailError,'详情暂不可用');assert.equal(t.page.data.detailOutcomeText,'已撤回转单，订单仍由你配送')
 assert.equal(t.wx.__calls.toast.at(-1).title,'已撤回，但详情刷新失败，请重新加载');assert.equal(t.page.data.cancelTransferBusy,false)
 await t.page.onCancelTransfer();assert.equal(t.modals.length,1)
 await t.page.onRetryDetail();assert.equal(t.page.data.detailError,'详情暂不可用');assert.deepStrictEqual(t.writes,[20])
 broken=false;await t.page.onRetryDetail();assert.deepStrictEqual(t.reads,[20,20,20,20]);assert.deepStrictEqual(t.writes,[20]);assert.equal(t.page.data.detailError,'');assert.equal(t.page.data.detailOutcomeText,'');assert.equal(t.page.data.order.canWithdrawTransfer,false)
})
test('retry entry is present and stale footer is hidden after detail failure',()=>{const w=fs.readFileSync(path.join(ROOT,source.replace('.js','.wxml')),'utf8');assert.ok(w.includes('bindtap="onRetryDetail"'));assert.ok(w.includes('{{detailOutcomeText}}'));assert.ok(w.includes('order.id && !loading && !detailError && !order.transferTarget'));assert.ok(w.includes('!loading && !detailError && order.canAcceptTransfer'))})
test('pending withdraw survives hide/show with feedback, no second modal or write and refreshes on settlement',async()=>{
 const d=deferred(),t=withdrawal({write:()=>d.promise});await t.page.loadOrderDetail(20);const {pending}=await startWithdrawal(t)
 t.page.onHide();const hiddenBusy=t.page.data.cancelTransferBusy;await t.page.onShow()
 const shown={action:t.page.data.actionBusy,cancel:t.page.data.cancelTransferBusy,pending:t.page.data.writePending}
 const second=t.page.onCancelTransfer();if(t.modals.length>1){t.modals[1].success({confirm:true});await tick()}
 t.page.onOpenMoreActions();t.page.onComplete();const feedback=t.wx.__calls.toast.at(-1)
 d.resolve();await Promise.all([pending,second])
 assert.deepStrictEqual(t.writes,[20],JSON.stringify({hiddenBusy,shown,writes:t.writes,modals:t.modals.length}));assert.equal(hiddenBusy,false);assert.deepStrictEqual(shown,{action:true,cancel:true,pending:true});assert.equal(t.modals.length,1);assert.deepStrictEqual(t.writes,[20]);assert.equal(feedback.title,'操作仍在处理中，请稍候');assert.equal(t.wx.__calls.nav.length,0)
 assert.deepStrictEqual(t.reads,[20,20,20]);assert.equal(t.page.data.writePending,false);assert.equal(t.page.data.actionBusy,false);assert.equal(t.page.data.cancelTransferBusy,false);assert.equal(t.page.data.order.canWithdrawTransfer,false);assert.equal(t.page.data.order.canComplete,true)
})
test('failed pending withdraw after hide/show releases busy, shows failure and allows a fresh retry',async()=>{
 const d=deferred(),t=withdrawal({write:()=>d.promise});await t.page.loadOrderDetail(20);const {pending}=await startWithdrawal(t);t.page.onHide();await t.page.onShow();d.reject(Error('撤回未成功'));await pending
 assert.equal(t.page.data.actionBusy,false);assert.equal(t.page.data.cancelTransferBusy,false);assert.equal(t.page.data.writePending,false);assert.equal(t.page.data.order.canWithdrawTransfer,true);assert.equal(t.wx.__calls.toast.at(-1).title,'撤回未成功')
 const retry=t.page.onCancelTransfer();assert.equal(t.modals.length,2);t.modals[1].success({confirm:false});await retry;assert.deepStrictEqual(t.writes,[20])
})
test('write settling while hidden clears busy on return and reads backend state once',async()=>{
 const d=deferred(),t=withdrawal({write:()=>d.promise});await t.page.loadOrderDetail(20);const {pending}=await startWithdrawal(t);t.page.onHide();d.resolve();await pending;assert.deepStrictEqual(t.reads,[20]);await t.page.onShow();assert.deepStrictEqual(t.reads,[20,20]);assert.equal(t.page.data.actionBusy,false);assert.equal(t.page.data.cancelTransferBusy,false);assert.equal(t.page.data.order.canWithdrawTransfer,false)
})
for(const kind of ['unload','identity','order'])test('settled old withdraw cannot refresh or repaint after '+kind,async()=>{
 const d=deferred(),t=withdrawal({write:()=>d.promise});await t.page.loadOrderDetail(20);const {pending}=await startWithdrawal(t)
 if(kind==='unload')t.page.onUnload();else if(kind==='identity'){t.page.onHide();t.app._loginGeneration++;await t.page.onShow()}else {t.page.onHide();t.page.setData({orderId:21});await t.page.onShow()}
 const before=t.reads.length;let paints=0;t.page.setData=()=>paints++;d.resolve();await pending;assert.equal(t.reads.length,before);assert.equal(paints,0);assert.equal(t.wx.__calls.toast.length,0)
})
test('token renewal keeps pending write gate for the same staff and order',async()=>{
 const d=deferred(),t=withdrawal({write:()=>d.promise});await t.page.loadOrderDetail(20);const {pending}=await startWithdrawal(t);t.page.onHide();t.app.globalData.accessToken='renewed';await t.page.onShow();const second=t.page.onCancelTransfer();if(t.modals.length>1)t.modals[1].success({confirm:false});d.resolve();await Promise.all([pending,second]);assert.deepStrictEqual(t.writes,[20]);assert.equal(t.modals.length,1)
})
test('refusal pending first step clears on hide and resumes busy until that write settles',async()=>{
 const d=deferred(),t=fixture({order:{status:3},request:{post:()=>d.promise}});t.app.globalData.userInfo.role='STATION_MANAGER';await t.page.loadOrderDetail(20);const p=t.page.onRefusalWriteOff();t.modals[0].success({confirm:true});await tick();assert.equal(t.page.data.refusalBusy,true);t.page.onHide();assert.equal(t.page.data.refusalBusy,false);await t.page.onShow();assert.equal(t.page.data.refusalBusy,true);d.resolve({data:{id:70}});await p;assert.equal(t.page.data.refusalBusy,false);assert.equal(t.page.data.actionBusy,false);assert.equal(t.modals.length,1)
})
test('resumed successful withdraw with failed fresh read retains outcome and recovers without another write',async()=>{
 const d=deferred();let broken=true;const t=withdrawal({write:()=>d.promise,read:(t,applied)=>{if(applied&&broken)throw Error('刷新断网');return {data:{...t.order,...(applied?t.fresh:{})}}}})
 await t.page.loadOrderDetail(20);const {pending}=await startWithdrawal(t);t.page.onHide();await t.page.onShow();d.resolve();await pending
 assert.deepStrictEqual(t.writes,[20]);assert.deepStrictEqual(t.reads,[20,20,20]);assert.equal(t.page.data.writePending,false);assert.equal(t.page.data.cancelTransferBusy,false);assert.equal(t.page.data.detailError,'刷新断网');assert.equal(t.page.data.detailOutcomeText,'已撤回转单，订单仍由你配送');assert.equal(t.wx.__calls.toast.at(-1).title,'已撤回，但详情刷新失败，请重新加载')
 broken=false;await t.page.onRetryDetail();assert.deepStrictEqual(t.writes,[20]);assert.deepStrictEqual(t.reads,[20,20,20,20]);assert.equal(t.page.data.order.canWithdrawTransfer,false)
})
test('fresh response after resumed write wins over the earlier onShow read',async()=>{
 const write=deferred(),read=deferred();let n=0;const t=withdrawal({write:()=>write.promise,read:(t,applied)=>++n===2?read.promise:Promise.resolve({data:{...t.order,...(applied?t.fresh:{})}})})
 await t.page.loadOrderDetail(20);const {pending}=await startWithdrawal(t);t.page.onHide();const showing=t.page.onShow();write.resolve();await pending;read.resolve({data:t.order});await showing
 assert.deepStrictEqual(t.writes,[20]);assert.deepStrictEqual(t.reads,[20,20,20]);assert.equal(t.page.data.order.transferPending,false);assert.equal(t.page.data.order.canWithdrawTransfer,false);assert.equal(t.page.data.order.canComplete,true);assert.equal(t.page.data.cancelTransferBusy,false)
})
test('return still posts original reason and order id once',async()=>{const t=fixture();const oldTimeout=global.setTimeout;global.setTimeout=()=>0;try{const p=more(t,'onReturnToStation');t.modals[0].success({confirm:true});await p;assert.deepStrictEqual(t.calls[0],{name:'returnToStation',args:[20,{reason:'配送员退回站长'}]})}finally{global.setTimeout=oldTimeout}})
for(const independent of [false,true])test('refusal two-step contract independent='+independent,async()=>{const t=fixture({order:{status:3,independentBusinessRules:independent}});t.app.globalData.userInfo.role='STATION_MANAGER';const p=more(t,'onRefusalWriteOff');assert.equal(t.calls.length,0);t.modals[0].success({confirm:true});await tick();assert.equal(t.calls.length,1);assert.equal(t.modals.length,2);assert.ok(t.modals[1].content.includes(independent?'欠款继续保留':'应收出账'));t.modals[1].success({confirm:false});await p;assert.equal(t.calls.length,1);assert.equal(t.page.data.refusalBusy,false)})
test('refusal second confirmation after logout cannot write off',async()=>{const t=fixture({order:{status:3,independentBusinessRules:true}});t.app.globalData.userInfo.role='STATION_MANAGER';const p=more(t,'onRefusalWriteOff');t.modals[0].success({confirm:true});await tick();t.page.onHide();t.modals[1].success({confirm:true});await p;assert.equal(t.calls.length,1)})

;(async()=>{let failed=0;for(const t of tests){try{await t.run();console.log('PASS '+t.name)}catch(e){failed++;console.error('FAIL '+t.name+' '+e.stack)}}done();console.log('RESULT '+(tests.length-failed)+'/'+tests.length);if(failed)process.exitCode=1;else console.log('AQUAFLOW_SUITE_OK '+tests.length)})()
