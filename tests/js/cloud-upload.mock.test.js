'use strict'
const fs = require('fs'), path = require('path'), vm = require('vm'), assert = require('assert')
const ROOT = process.env.AQUAFLOW_UPLOAD_SOURCE_ROOT ? path.resolve(process.env.AQUAFLOW_UPLOAD_SOURCE_ROOT)
  : [path.resolve(__dirname, '../..'),path.resolve(__dirname, '../source'),path.resolve(__dirname, '../miniapp-trial-cd4-r2/source')]
    .find(root => fs.existsSync(path.join(root,'miniapp-delivery/utils/upload.js')))
const tests = [], test = (name, fn) => tests.push({ name, fn })
const flush = async () => { for (let i = 0; i < 12; i++) await new Promise(r => setImmediate(r)) }
const png = () => Uint8Array.from([137,80,78,71,13,10,26,10,0,255,1,0]).buffer
// Actual common upload keeps URL data and now additionally returns its durable object key.
const ok = data => ({statusCode:200, data:{code:0,data,...(typeof data === 'string' ? {objectName:'public/common/synthetic.png'} : {})}})
const expired = () => ({statusCode:401})
function fixture(mode = 'cloudContainer', side = 'miniapp-delivery', mutateSource = (name, source) => source, storage = new Map()) {
  const calls = [], http = [], uploads = [], stats = [], reads = [], picks = [], toast = [], routes = [], timers = [], cache = {}
  const app = {_loginGeneration:1,globalData:{customerId:7,isLogin:true,accessToken:'synthetic-A',refreshToken:'synthetic-RA',userInfo:{staffId:1,role:'STATION_MANAGER',stationId:1}}}
  let initFail = false, initWait = null, releaseInit
  const second = png(); new Uint8Array(second)[11] = 2
  const files = new Map([['/synthetic.png', png()], ['/second.png', second]])
  const businessKey = key => typeof key === 'string' && (key === 'aq_delivery_uploadRecovery_v1' || key.startsWith('aq_delivery_adjustmentCreate_v1:')
    || key.startsWith('aq_product_creation_v1:') || key.startsWith('inventory-inbound-intent:'))
  const wx = {
    cloud:{Cloud:class {init() {return initFail ? Promise.reject(Error('synthetic')) : initWait || Promise.resolve()} callContainer(options) {
      return new Promise((resolve,reject) => calls.push({options,resolve,reject}))
    }}},
    getFileSystemManager:() => ({stat:options=>stats.push(options),readFile:options=>reads.push(options)}),
    chooseImage:options=>picks.push(options), showToast:o=>toast.push(o),
    request:options=>http.push(options), uploadFile:options=>uploads.push(options),
    setStorageSync(key,value){if(businessKey(key))storage.set(key,value)},removeStorageSync(key){if(businessKey(key))storage.delete(key)},getStorageSync(key){if(businessKey(key))return storage.get(key)||'';throw Error('cached credentials must not be borrowed')}
  }
  const config = {getTransportConfig:()=>mode==='cloudContainer'?{mode,env:'synthetic-env',service:'synthetic-service'}:{mode,baseUrl:'https://synthetic.invalid'},
    API:{REFRESH:'/api/auth/refresh',GENERAL_UPLOAD:'/api/common/upload',ORDER_IMAGE_UPLOAD:'/api/order-images/upload'}}
  const navigation = {sessionKey:()=>app._loginGeneration,invalidate(){},open:o=>routes.push(o)}
  function load(name) {
    if (cache[name]) return cache[name]
    const module = {exports:{}}
    vm.runInNewContext(mutateSource(name,fs.readFileSync(path.join(ROOT,side,'utils',name+'.js'),'utf8')), {
      module,exports:module.exports,wx,getApp:()=>app,ArrayBuffer,Uint8Array,DataView,Date,
      setTimeout:fn=>{const timer={fn};timers.push(timer);return timer},clearTimeout:timer=>{timer.cleared=true},
      console:{error(){},warn(){},log(){}},
      require(id) {if(id.includes('config/api')) return config; if(id.includes('storage-keys')) return {STORAGE_KEYS:{}}; if(id==='./navigation') return navigation;
        return load(id.replace('./',''))}
    },{filename:name+'.js'})
    return cache[name] = module.exports
  }
  const api = load(side === 'miniapp-delivery' ? 'upload' : 'request')
  function read(index=0) { const options=stats[index]; const bytes=files.get(options.path); options.success({stats:{size:bytes.byteLength}});
    if(reads.length) reads[reads.length-1].success({data:bytes}) }
  function switchAccount() {app._loginGeneration++;app.globalData={...app.globalData,accessToken:'synthetic-B',refreshToken:'synthetic-RB',userInfo:{staffId:2,role:'STATION_MANAGER',stationId:2}}}
  // Durable recovery assertions use adjustment evidence. Product photos have a separate retryable contract.
  const args = (extra={}) => {const options={url:config.API.GENERAL_UPLOAD,filePath:'/synthetic.png',intent:{kind:'customer-adjustment-evidence',id:42},...extra};if(options.url===config.API.GENERAL_UPLOAD && options.intent && ['product-image','customer-adjustment-evidence'].includes(options.intent.kind)) options.intent={...options.intent,draftId:options.intent.draftId||api.uploadDraft(options.intent)};return options}
  return {api,args,app,wx,calls,http,uploads,stats,reads,picks,toast,routes,timers,files,read,switchAccount,load,storage,config,failInit(){initFail=true},
    pauseInit(){initWait=new Promise(resolve=>{releaseInit=resolve})},releaseInit(){releaseInit()}}
}
const observe = promise => { const state={}; promise.then(value=>state.value=value,error=>state.error=error); return state }
function saveBody(name, options, fields) {
  const dir=path.join(__dirname,'sdk-synthetic-fixtures');fs.mkdirSync(dir,{recursive:true})
  fs.writeFileSync(path.join(dir,name+'.bin'),Buffer.from(options.data))
  fs.writeFileSync(path.join(dir,name+'.json'),JSON.stringify({contentType:options.header['Content-Type'],fields,filename:'upload.png',fileHex:Buffer.from(png()).toString('hex')},null,2))
}
test('cloud raw multipart bytes and JWT reach existing endpoint; no HTTP or direct COS', async()=>{
  const f=fixture(), result=observe(f.api.upload(f.args())); await flush(); f.read(); await flush()
  assert.equal(f.calls.length,1); const o=f.calls[0].options
  assert.equal(o.path,'/api/common/upload'); assert.equal(o.method,'POST'); assert.equal(o.header['X-WX-SERVICE'],'synthetic-service')
  assert.equal(o.header.Authorization,'Bearer synthetic-A'); assert.match(o.header['Content-Type'],/^multipart\/form-data; boundary=/)
  assert(o.data instanceof ArrayBuffer); assert(Buffer.from(o.data).includes(Buffer.from(png())))
  saveBody('common-runtime-body',o,{})
  assert.equal(f.http.length+f.uploads.length,0);f.calls[0].resolve(ok('/synthetic-result'));await flush();assert.equal(result.value.data,'/synthetic-result')
})
test('single 401 refresh and upload retry stay cloud with identical body/header/fields', async()=>{
  const f=fixture(), form={orderId:42,type:3}, r=observe(f.api.upload(f.args({url:'/api/order-images/upload',formData:form,header:{Authorization:'obsolete-synthetic'}})))
  form.orderId=99; await flush(); f.read(); await flush(); const first=f.calls[0].options
  assert(Buffer.from(first.data).includes(Buffer.from('\r\n42\r\n')))
  saveBody('order-runtime-body',first,{orderId:'42',type:'3'})
  f.calls[0].resolve(expired());await flush();assert.equal(f.calls[1].options.path,'/api/auth/refresh')
  f.calls[1].resolve(ok({accessToken:'synthetic-A2',refreshToken:'synthetic-RA2'}));await flush()
  assert.equal(f.calls.length,3); assert.deepEqual(Buffer.from(f.calls[2].options.data),Buffer.from(first.data))
  assert.equal(f.calls[2].options.header['Content-Type'],first.header['Content-Type']);assert.equal(f.calls[2].options.header.Authorization,'Bearer synthetic-A2')
  f.calls[2].resolve(expired());await flush();assert(r.error);assert.equal(f.calls.length,3);assert.equal(f.http.length,0)
})
test('concurrent different uploads and normal request share one refresh flight',async()=>{
  const f=fixture(), a=observe(f.api.upload(f.args())),b=observe(f.api.upload(f.args({filePath:'/second.png'})))
  await flush(); f.read(0);f.read(1);await flush(); const normal=observe(f.load('request').get('/synthetic-read'));await flush()
  for(let i=0;i<3;i++)f.calls[i].resolve(expired());await flush(); assert.equal(f.calls.length,4)
  f.calls[3].resolve(ok({accessToken:'synthetic-A2',refreshToken:'synthetic-RA2'}));await flush();assert.equal(f.calls.length,7)
  for(let i=4;i<7;i++)f.calls[i].resolve(ok('/synthetic'));await flush();assert(a.value&&b.value&&normal.value)
})
test('duplicate clicks coalesce before file read and submission',async()=>{
  const f=fixture(), a=f.api.upload(f.args()),b=f.api.upload(f.args());assert.strictEqual(a,b)
  const r=observe(a);await flush();assert.equal(f.stats.length,1);f.read();await flush();assert.equal(f.calls.length,1)
  f.calls[0].resolve(ok('/synthetic'));await flush();assert(r.value)
})
for(const failure of ['network','timeout','malformed','http500','code500','code1','missing-receipt'])test('ambiguous '+failure+' cannot trigger repeat upload',async()=>{
  const f=fixture(), r=observe(f.api.upload(f.args()));await flush();f.read();await flush()
  if(failure==='network')f.calls[0].reject(Error('synthetic'))
  else if(failure==='timeout') { const o=f.calls[0].options; assert.equal(o.timeout,15000); f.timers[0].fn() }
  else if(failure==='malformed')f.calls[0].resolve({statusCode:200,data:null})
  else if(failure==='missing-receipt')f.calls[0].resolve(ok(null))
  else f.calls[0].resolve(failure==='http500'?{statusCode:500}:{statusCode:200,data:{code:failure==='code1'?1:500}})
  await flush();assert.equal(r.error.uploadOutcomeUnknown,true)
  if(failure==='timeout'){f.calls[0].resolve(ok('/synthetic-late'));await flush();assert(!r.value)}
  await assert.rejects(f.api.upload(f.args()),e=>e.uploadOutcomeUnknown===true);assert.equal(f.calls.length,1);assert.equal(f.stats.length,1)
})
test('definite pre-handler HTTP rejection permits a manual retry without automatic replay',async()=>{
  const f=fixture(), r=observe(f.api.upload(f.args()));await flush();f.read();await flush();f.calls[0].resolve({statusCode:413});await flush()
  assert.equal(r.error.statusCode,413);assert.equal(r.error.uploadOutcomeUnknown,undefined)
  const r2=observe(f.api.upload(f.args()));await flush();f.read(1);await flush();assert.equal(f.calls.length,2);f.calls[1].resolve(ok('/synthetic'));await flush();assert(r2.value)
})
test('initialization failure is pre-dispatch and manually retryable',async()=>{
  const f=fixture();f.failInit();const r=observe(f.api.upload(f.args()));await flush();f.read();await flush();assert(r.error);assert(!r.error.uploadOutcomeUnknown);assert.equal(f.calls.length,0)
})
test('account switch during SDK initialization prevents even the original upload dispatch',async()=>{
  const f=fixture();f.pauseInit();const r=observe(f.api.upload(f.args()));await flush();f.read();await flush();assert.equal(f.calls.length,0)
  f.switchAccount();f.releaseInit();await flush();assert(r.error.sessionChanged);assert(!r.error.uploadOutcomeUnknown);assert.equal(f.calls.length,0)
})
test('initialization deadline expires before dispatch and late init cannot submit',async()=>{
  const f=fixture();f.pauseInit();const r=observe(f.api.upload(f.args()));await flush();f.read();await flush();f.timers[0].fn();await flush()
  assert(r.error);assert(!r.error.uploadOutcomeUnknown);f.releaseInit();await flush();assert.equal(f.calls.length,0)
})
test('account switch between 401 processing and cloud refresh dispatch suppresses old refresh',async()=>{
  const f=fixture(),r=observe(f.api.upload(f.args()));await flush();f.read();await flush()
  f.calls[0].resolve(expired());Promise.resolve().then(()=>f.switchAccount());await flush()
  assert(r.error.sessionChanged);assert.equal(f.calls.length,1);assert.equal(f.app.globalData.accessToken,'synthetic-B');assert.equal(f.routes.length,0)
})
for(const phase of ['stat','read','response','refresh-success','refresh-fail'])test('account switch at '+phase+' cannot upload under/overwrite new account',async()=>{
  const f=fixture(),r=observe(f.api.upload(f.args()));await flush()
  if(phase==='stat'){f.switchAccount();f.stats[0].success({stats:{size:12}})}
  else if(phase==='read'){f.stats[0].success({stats:{size:12}});f.switchAccount();f.reads[0].success({data:png()})}
  else {f.read();await flush(); if(phase==='response'){f.switchAccount();f.calls[0].resolve(ok('/synthetic-A-result'))}
    else {f.calls[0].resolve(expired());await flush();f.switchAccount();if(phase==='refresh-success')f.calls[1].resolve(ok({accessToken:'synthetic-old',refreshToken:'synthetic-old-refresh'}));else f.calls[1].reject(Error('synthetic'))}}
  await flush();assert(r.error.sessionChanged);assert.equal(f.app.globalData.accessToken,'synthetic-B');assert.equal(f.routes.length,0)
  assert.equal(f.calls.length,phase.startsWith('refresh')?2:phase==='response'?1:0)
})
for(const invalid of ['empty','badbytes','oversize','read-fail','stat-fail','mismatch','nonbinary'])test('local '+invalid+' rejects without a cloud call',async()=>{
  const f=fixture();if(invalid==='empty')f.files.set('/synthetic.png',new ArrayBuffer(0));if(invalid==='badbytes')f.files.set('/synthetic.png',Uint8Array.from([1,2,3]).buffer)
  const r=observe(f.api.upload(f.args()));await flush()
  if(invalid==='oversize')f.stats[0].success({stats:{size:5*1024*1024+1}})
  else if(invalid==='stat-fail')f.stats[0].fail({})
  else if(['read-fail','mismatch','nonbinary'].includes(invalid)){f.stats[0].success({stats:{size:12}}); if(invalid==='read-fail')f.reads[0].fail({});else f.reads[0].success({data:invalid==='mismatch'?png().slice(0,10):'synthetic'})}
  else f.read()
  await flush();assert(r.error.localUploadFailure);assert.equal(f.calls.length,0)
})
test('invalid target, field, order and file path reject before filesystem access',async()=>{
  const f=fixture()
  for(const args of [f.args({url:'/new-endpoint'}),f.args({filePath:''}),f.args({name:'other'}),f.args({formData:{extra:'x'}}),f.args({url:'/api/order-images/upload',formData:{orderId:-1,type:3}})])await assert.rejects(f.api.upload(args))
  assert.equal(f.stats.length+f.calls.length,0)
})
test('picker cancel releases lock, no file read/upload; repeated picker click is single-flight',async()=>{
  const f=fixture();let count=0;const opts={success:()=>{count++}}
  f.api.chooseImages(opts);f.api.chooseImages(opts);assert.equal(f.picks.length,1);f.picks[0].fail({errMsg:'chooseImage:fail cancel'});f.picks[0].complete()
  f.api.chooseImages(opts);assert.equal(f.picks.length,2);f.picks[1].success({tempFilePaths:['/synthetic.png']});f.picks[1].complete();await flush()
  assert.equal(count,1);assert.equal(f.toast.length+f.calls.length+f.stats.length,0)
})
test('picker callback is discarded after account switch',async()=>{
  const f=fixture();let invoked=false;f.api.chooseImages({success:()=>{invoked=true}});f.switchAccount();f.picks[0].success({tempFilePaths:['/synthetic.png']});await flush();assert(!invoked);assert.equal(f.stats.length,0)
})
test('binary snapshot handles both packages, offset views and caller/SDK mutation across 401',async()=>{
  const f=fixture(), body=png(), r=observe(f.load('request').request({url:'/synthetic-binary',method:'POST',data:body}))
  const original=Buffer.from(body).toString('hex');new Uint8Array(body).fill(7);await flush();assert.equal(Buffer.from(f.calls[0].options.data).toString('hex'),original)
  new Uint8Array(f.calls[0].options.data).fill(8);f.calls[0].resolve(expired());await flush();f.calls[1].resolve(ok({accessToken:'synthetic-A2',refreshToken:'synthetic-RA2'}));await flush()
  assert.equal(Buffer.from(f.calls[2].options.data).toString('hex'),original);f.calls[2].resolve(ok({}));await flush();assert(r.value)
  for(const pkg of ['miniapp-user','miniapp-delivery']){const {snapshot}=require(path.join(ROOT,pkg,'utils/snapshot'))
    const data=Uint8Array.from([9,1,2,3,9]),view=new Uint8Array(data.buffer,1,3),copy=snapshot(view);data.fill(8);assert.deepEqual(Array.from(copy),[1,2,3]);assert.equal(copy.buffer.byteLength,3)
    const ab=png(),copied=snapshot({ab,list:[ab]});new Uint8Array(ab).fill(0);assert.equal(new Uint8Array(copied.ab)[0],137);assert.equal(new Uint8Array(copied.list[0])[0],137)
  }
})
test('actual customer request and token modules preserve binary body through cloud 401 retry',async()=>{
  const f=fixture('cloudContainer','miniapp-user'),bytes=png(), original=Buffer.from(bytes).toString('hex')
  const r=observe(f.api.request({url:'/synthetic-binary',method:'POST',data:bytes}));new Uint8Array(bytes).fill(7);await flush()
  assert.equal(Buffer.from(f.calls[0].options.data).toString('hex'),original);new Uint8Array(f.calls[0].options.data).fill(8)
  f.calls[0].resolve(expired());await flush();assert.equal(f.calls[1].options.path,'/api/auth/refresh')
  f.calls[1].resolve(ok({accessToken:'synthetic-A2',refreshToken:'synthetic-RA2'}));await flush()
  assert.equal(Buffer.from(f.calls[2].options.data).toString('hex'),original);assert.equal(f.calls[2].options.header.Authorization,'Bearer synthetic-A2')
  f.calls[2].resolve(ok({}));await flush();assert(r.value)
})
test('order receipt retains object shape with URL for existing list consumers',async()=>{
  const f=fixture(),r=observe(f.api.upload(f.args({url:'/api/order-images/upload',formData:{orderId:42,type:3}})))
  await flush();f.read();await flush();f.calls[0].resolve(ok({id:1,orderId:42,type:3,url:'/synthetic-order-url'}));await flush();assert.equal(r.value.data.url,'/synthetic-order-url')
})
test('picker lock remains until async upload callback settles',async()=>{
  const f=fixture();let release;const pending=new Promise(resolve=>{release=resolve})
  f.api.chooseImages({success:()=>pending});f.picks[0].success({tempFilePaths:['/synthetic.png']});f.picks[0].complete();await flush()
  f.api.chooseImages({success(){}});assert.equal(f.picks.length,1);release();await flush();f.api.chooseImages({success(){}});assert.equal(f.picks.length,2)
  f.picks[1].fail({errMsg:'chooseImage:fail cancel'})
})
test('one failed image cannot release the picker while another batch upload is still pending',async()=>{
  const f=fixture()
  f.api.chooseImages({success:res=>Promise.all(res.tempFilePaths.map(filePath=>f.api.upload(f.args({filePath,session:res.uploadSession}))))})
  f.picks[0].success({tempFilePaths:['/synthetic.png','/second.png']});await flush();f.read(0);f.read(1);await flush()
  f.calls[0].reject(Error('synthetic'));await flush();f.api.chooseImages({success(){}});assert.equal(f.picks.length,1)
  f.calls[1].resolve(ok('/synthetic'));await flush();f.api.chooseImages({success(){}});assert.equal(f.picks.length,2);f.picks[1].fail({errMsg:'chooseImage:fail cancel'})
})
test('HTTPS uses native uploadFile; refresh once via wx.request; multipart boundary remains native',async()=>{
  const f=fixture('https'),r=observe(f.api.upload(f.args()));await flush();f.read();await flush();assert.equal(f.uploads.length,1);assert.equal(f.calls.length,0);assert(!f.uploads[0].header['Content-Type'])
  f.uploads[0].success({statusCode:401,data:''});f.uploads[0].complete?.();assert.equal(f.http.length,1)
  f.http[0].success(ok({accessToken:'synthetic-A2',refreshToken:'synthetic-RA2'}));f.http[0].complete?.();assert.equal(f.uploads.length,2)
  assert.equal(f.uploads[1].header.Authorization,'Bearer synthetic-A2');f.uploads[1].success({statusCode:200,data:JSON.stringify({code:0,data:'/synthetic',objectName:'public/common/synthetic.png'})});await flush();assert(r.value)
})
async function main(){const watchdog=setTimeout(()=>{console.error('MOCK_SUITE_TIMEOUT');process.exit(1)},30000);let passed=0
  try{for(const t of tests){await t.fn();passed++;console.log('PASS '+t.name)}
    fs.writeFileSync(path.join(__dirname,'mock-validation.json'),JSON.stringify({scope:'synthetic bytes and SDK/filesystem mocks only',passed,failed:0,realSdkTested:false,realImageUploaded:false,cosTested:false},null,2))
    console.log('AQUAFLOW_UPLOAD_MOCK_OK '+passed);console.log('AQUAFLOW_SUITE_OK '+passed)
  }finally{clearTimeout(watchdog)}}
if (require.main === module) main().catch(e=>{console.error(e.stack);process.exitCode=1})
module.exports = { fixture, flush, png, ok, expired, observe, ROOT }
