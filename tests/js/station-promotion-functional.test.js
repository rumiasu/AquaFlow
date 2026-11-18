'use strict'
const assert = require('node:assert/strict'), fs = require('fs'), path = require('path'), vm = require('vm')
const root = path.resolve(__dirname, '../..'), tests = []
const test = (name, run) => tests.push([name, run])
const flush = () => new Promise(resolve => setImmediate(resolve))
const deferred = () => { let resolve, reject; const promise = new Promise((a,b) => {resolve=a;reject=b}); return {promise,resolve,reject} }
function fixture() {
  let row = {id:7,selected:true,status:1,name:'本站水',spec:'18.9L',regularPrice:20,salePrice:20,
    effectivePrice:20,promotionActive:false,promotionPrice:null,imageUrl:'/assets/product/barrel-neutral-template.webp'}
  const calls=[], state={}, app={globalData:{isLogin:true,userInfo:{staffId:3,stationId:11,role:'STATION_MANAGER'}},canAccessStationBusiness:()=>true}
  const api = {
    getCatalog: async()=>state.read ? state.read() : {code:0,data:[row]},
    getMyStation: async()=>state.station ? state.station() : {code:0,data:{id:11,name:'本站'}},
    updateCatalogSetting: async(id,change)=>{calls.push([id,change]);if(state.save)return state.save(id,change)
      row={...row,...change};if(change.salePrice!=null)row.regularPrice=change.salePrice
      if(change.promotionActive===false)row.promotionPrice=null
      row.effectivePrice=row.promotionActive?row.promotionPrice:row.regularPrice;return {code:0}
    }
  }
  const sandbox={module:{exports:{}},require:id=>{assert.equal(id,'../../../api/station-mgmt');return api},getApp:()=>app}
  vm.runInNewContext(fs.readFileSync(path.join(root,'miniapp-delivery/pages/station-mgmt/products/promotion.js'),'utf8'),sandbox)
  const page={...sandbox.module.exports,data:{promotionVisible:false,promotionBusy:false,promotionDraft:null},
    productWriteLocked(){return !!this._promotionFlight||this.data.promotionBusy},setData(p){Object.assign(this.data,p)},
    loadData(){calls.push(['reload'])}}
  return {page,app,calls,state,setRow:r=>row={...row,...r},row:()=>row}
}
const open = f=>f.page.openPromotion({currentTarget:{dataset:{id:7}}})
const input=(f,field,value)=>f.page.editPromotion({currentTarget:{dataset:{field}},detail:{value}})
const toggle=(f,value)=>f.page.switchPromotion({detail:{value}})
test('starts, edits and ends promotion; only confirmed prices and saved photos enter poster',async()=>{
  const f=fixture();await open(f);toggle(f,true);input(f,'promoValue','15.25');await f.page.promotionPoster()
  assert.equal(f.calls.filter(x=>x[0]===7).length,1);assert.equal(f.page.data.posterProduct.effectivePrice,15.25)
  assert.equal(f.page.data.posterStationName,'本站');assert.equal(f.page.data.posterProduct.imageUrl,f.row().imageUrl)
  input(f,'value','21');await f.page.savePromotion();assert.equal(f.row().effectivePrice,15.25)
  input(f,'promoValue','14');await f.page.savePromotion();assert.equal(f.row().effectivePrice,14)
  toggle(f,false);await f.page.promotionPoster();assert.equal(f.row().effectivePrice,21);assert.equal(f.page.data.posterProduct.promotionActive,false)
  assert(f.calls.filter(x=>x[0]===7).every(x=>Object.keys(x[1]).every(k=>['salePrice','promotionActive','promotionPrice'].includes(k))))
})
test('validates money before requests',async()=>{
  for(const value of ['0','-1','20','20.001','12junk','1e1','']) {
    const f=fixture();await open(f);toggle(f,true);input(f,'promoValue',value);await f.page.promotionPoster()
    assert.equal(f.calls.length,0,value);assert(!f.page.data.posterProduct)
  }
})
test('repeated clicks share a single write and editing is locked while pending',async()=>{
  const f=fixture(),wait=deferred();await open(f);input(f,'value','22');f.state.save=()=>wait.promise
  const pending=f.page.savePromotion();await f.page.savePromotion();input(f,'value','30');assert.equal(f.calls.length,1)
  assert.equal(f.page.data.promotionDraft.value,'22');f.setRow({salePrice:22,regularPrice:22,effectivePrice:22});wait.resolve({code:0});await pending
  assert.equal(f.page.data.promotionDraft.current,22)
})
test('unknown writes reconcile without resending, including close and reopen',async()=>{
  const f=fixture();await open(f);input(f,'value','22');f.state.save=()=>{throw Error('timeout')}
  await f.page.savePromotion();assert(f.page.data.promotionDraft.checkValue)
  f.page.closePromotion();await open(f);f.setRow({salePrice:22,regularPrice:22,effectivePrice:22});await f.page.promotionPoster()
  assert.equal(f.calls.filter(x=>x[0]===7).length,1);assert.equal(f.page.data.posterProduct.effectivePrice,22)
})
test('definite rejection may be corrected; mismatching receipt never creates poster',async()=>{
  const f=fixture();await open(f);input(f,'value','22');f.state.save=()=>{throw Object.assign(Error('rejected'),{businessRejected:true})}
  await f.page.savePromotion();assert.equal(f.page.data.promotionDraft.checkValue,null)
  f.state.save=async()=>({code:0});await f.page.promotionPoster();assert(!f.page.data.posterProduct);assert.equal(f.page.data.promotionDraft.current,20)
})
test('switching station invalidates late read and late write receipts',async()=>{
  const f=fixture(),wait=deferred();f.state.read=()=>wait.promise;const loading=open(f);f.app.globalData.userInfo.stationId=12
  wait.resolve({code:0,data:[f.row()]});await loading;assert.equal(f.page.data.promotionDraft,null)
  const g=fixture(),save=deferred();await open(g);input(g,'value','22');g.state.save=()=>save.promise
  const pending=g.page.promotionPoster();g.app.globalData.userInfo.stationId=12;g.page.syncPromotionSession();save.resolve({code:0});await pending
  assert(!g.page.data.posterProduct);assert.equal(g.page.data.promotionDraft,null)
})
test('close, hide and destruction suppress late posters',async()=>{
  for(const action of ['close','hide','destroy']) {
    const f=fixture(),station=deferred();await open(f);f.state.station=()=>station.promise
    const pending=f.page.promotionPoster();await flush()
    if(action==='destroy')f.page._productDestroyed=true
    f.page.hidePromotion();station.resolve({code:0,data:{id:11,name:'本站'}});await pending
    assert(!f.page.data.posterProduct)
  }
})
test('foreign, unavailable and unsupported products cannot open pricing',async()=>{
  for(const patch of [{ownerStationId:12},{selected:false},{status:0},{promotionActive:undefined},{effectivePrice:0}]){
    const f=fixture();f.setRow(patch);await open(f);assert(!f.page.data.promotionDraft);assert(f.page.data.promotionError)
  }
})
test('no-edit poster rereads latest actual price; foreign station response is refused',async()=>{
  const f=fixture();await open(f);f.setRow({regularPrice:25,salePrice:25,effectivePrice:25});await f.page.promotionPoster()
  assert.equal(f.page.data.posterProduct.effectivePrice,25);assert.equal(f.calls.filter(x=>x[0]===7).length,0)
  f.state.station=async()=>({code:0,data:{id:12,name:'他站'}});await f.page.promotionPoster();assert(!f.page.data.posterProduct)
})
test('source integration is scoped to selected products and existing saved photos',()=>{
  const p=path.join(root,'miniapp-delivery/pages/station-mgmt/products')
  const js=fs.readFileSync(path.join(p,'index.js'),'utf8'),wxml=fs.readFileSync(path.join(p,'index.wxml'),'utf8')
  const conf=JSON.parse(fs.readFileSync(path.join(p,'index.json'),'utf8'))
  assert(js.includes("const promotionPage = require('./promotion')"));assert(js.includes('this.syncPromotionSession()'));assert(js.includes('this.hidePromotion()'))
  assert(js.includes('this._promotionFlight || this.data.promotionBusy'))
  assert(wxml.includes('catchtap="openPromotion"'));assert(wxml.includes('wx:if="{{posterProduct}}"'))
  assert(conf.usingComponents['product-promotion-poster'])
  const art=fs.readFileSync(path.join(root,'miniapp-delivery/components/ProductPromotionPoster/index.js'),'utf8')
  assert(!art.includes('publishedTemplates'));assert(!art.includes('product-choices'));assert(!art.includes('https://www.'))
})
;(async()=>{for(const [name,run] of tests){await run();console.log('PASS '+name)}console.log('AQUAFLOW_SUITE_OK '+tests.length)})().catch(e=>{console.error(e);process.exitCode=1})
