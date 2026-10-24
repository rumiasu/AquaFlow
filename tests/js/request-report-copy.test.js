'use strict'
const assert = require('assert'), path = require('path')
const { createWx, armWatchdog, ROOT } = require('./harness')
const done = armWatchdog(), tests = [], test = (name, run) => tests.push({ name, run })
const COPY = '上报结果未确认，请稍后核对'
function assertSemanticCopy(actual, expected) {
  assert.equal(actual, expected)
  assert(!/[A-Za-z]/.test(actual), 'visible report copy must not expose English technical details')
}
function setup() {
  const wx = createWx(), sent = []; wx.request = options => sent.push(options)
  wx.showModal = options => wx.__calls.modal.push(options)
  global.wx = wx; global.__wxConfig = { envVersion: 'develop' }
  let app; global.App = value => { app = value }; global.getApp = () => app
  global.getCurrentPages = () => [{ route: 'pages/home/index' }]
  for (const name of ['miniapp-user/app.js','miniapp-user/utils/token.js','miniapp-user/utils/request.js']) delete require.cache[require.resolve(path.join(ROOT,name))]
  require(path.join(ROOT,'miniapp-user/app.js'))
  const request = require(path.join(ROOT,'miniapp-user/utils/request.js'))
  const login = id => app.setLoginInfo('synthetic-access-'+id,'synthetic-refresh-'+id,{customerId:id})
  login(7); return { wx, sent, request, login }
}
async function prompt(t) {
  const body = { idempotencyKey:'original-synthetic-key', quantity:2 }, before = JSON.stringify(body)
  const pending = t.request.post('/synthetic/report-copy',body).then(() => { throw Error('original request must remain rejected') },error => error)
  const result = {statusCode:200,data:{code:500,message:'SYNTH_INTERNAL_ERROR <original>'}}
  const resultBefore = JSON.stringify(result); t.sent.at(-1).success(result)
  const error = await pending
  assert(error instanceof Error); assert.equal(error.originalMessage,result.data.message)
  assert.equal(error.message,'请求失败'); assert.equal(error.backendCode,500)
  assert.equal(JSON.stringify(result),resultBefore); assert.equal(JSON.stringify(body),before)
  assert.equal(t.sent.filter(x=>x.url.endsWith('/feedback')).length,0,'report waits for explicit confirmation')
  return { error, descriptors:Object.getOwnPropertyDescriptors(error), originalRequest:t.sent.at(-1) }
}
const outcomes = [
  {name:'business rejection',response:{statusCode:200,data:{code:1,message:'请重新登录后上报'}},expected:'上报未被接受：请重新登录后上报'},
  {name:'HTTP 401',response:{statusCode:401,data:null},expected:'登录已过期，请重新登录后上报'},
  {name:'HTTP 500',response:{statusCode:500,data:{code:0}},expected:'服务暂时不可用，'+COPY},
  {name:'business 500',response:{statusCode:200,data:{code:500,message:'水站暂时不可用'}},expected:'水站暂时不可用，'+COPY},
  {name:'missing response',response:null,expected:'收到的数据不完整，'+COPY},
  {name:'malformed body',response:{statusCode:200,data:null},expected:'收到的数据不完整，'+COPY},
  {name:'network timeout',network:{errMsg:'request:fail time out',errno:42},expected:'网络超时，'+COPY}
]
const unsafeOutcomes = [
  {name:'mixed Chinese business rejection',response:{statusCode:200,data:{code:1,message:'请登录 HTTP503 https://internal.example/trace'}},expected:'上报未被接受，请稍后核对'},
  {name:'mixed Chinese business 500 stack',response:{statusCode:200,data:{code:500,message:'水站异常 TypeError\n at internal https://internal.example/trace'}},expected:COPY},
  {name:'mixed Chinese network error',network:{errMsg:'握手异常 TLS_CERTIFICATE https://internal.example/trace',errno:42},expected:'网络连接异常，'+COPY}
]
for (const outcome of [...outcomes,...unsafeOutcomes]) test('report '+outcome.name+' retains its visible meaning without touching errors or retrying',async()=>{
  const t=setup(), original=await prompt(t); assert.equal(t.sent.length,1)
  t.wx.__calls.modal[0].success({confirm:true}); assert.equal(t.sent.length,2)
  const report=t.sent[1], requestBefore=JSON.stringify({data:report.data,header:report.header,method:report.method,timeout:report.timeout})
  assert(report.url.endsWith('/feedback')); assert.equal(report.method,'POST')
  assert.equal(report.data.category,'错误报告'); assert(report.data.content.includes(original.error.originalMessage))
  assert.equal(report.data.anonymous,undefined); assert.equal(report.header.Authorization,'Bearer synthetic-access-7')
  const callbackInput=outcome.network||outcome.response, inputBefore=JSON.stringify(callbackInput)
  if(outcome.network)report.fail(outcome.network);else report.success(outcome.response)
  assert.equal(t.wx.__calls.toast.length,1)
  assertSemanticCopy(t.wx.__calls.toast[0].title,outcome.expected)
  assert.equal(t.sent.length,2,'report failure must never resubmit automatically'); assert.equal(t.wx.__calls.modal.length,1,'report itself must never recurse')
  assert.deepStrictEqual(Object.getOwnPropertyDescriptors(original.error),original.descriptors)
  assert.equal(JSON.stringify(callbackInput),inputBefore)
  assert.equal(JSON.stringify({data:report.data,header:report.header,method:report.method,timeout:report.timeout}),requestBefore)
  assert.equal(original.originalRequest.data.idempotencyKey,'original-synthetic-key')
})

test('one generic sentence is rejected by all seven original semantic report outcomes',()=>{
  for(const outcome of outcomes) assert.throws(()=>assertSemanticCopy(COPY,outcome.expected),assert.AssertionError,outcome.name)
})
test('ordinary business errors retain original diagnostics and businessRejected outside visible copy',async()=>{
  const t=setup(), pending=t.request.post('/synthetic/business',{idempotencyKey:'business-key'}).catch(error=>error)
  t.sent[0].success({statusCode:200,data:{code:1,message:'SYNTH_BUSINESS_ORIGINAL'}})
  const error=await pending;assert.equal(error.originalMessage,'SYNTH_BUSINESS_ORIGINAL');assert.equal(error.message,'请求失败');assert.strictEqual(error.businessRejected,true)
  assert.equal(t.wx.__calls.toast.length,0);assert.equal(t.wx.__calls.modal.length,0);assert.equal(t.sent.length,1)
})
test('late response from a previous session keeps sessionChanged marker and sends no automatic report',async()=>{
  const t=setup(), pending=t.request.post('/synthetic/session',{idempotencyKey:'session-key'}).catch(error=>error)
  t.login(8);t.sent[0].success({statusCode:200,data:{code:500,message:'SYNTH_OLD_SESSION'}})
  const error=await pending;assert.strictEqual(error.sessionChanged,true);assert(error.message.includes('登录身份已变化'))
  assert.equal(t.sent.length,1);assert.equal(t.wx.__calls.modal.length,0);assert.equal(t.wx.__calls.toast.length,0)
})
test('a submitted report finishing after account change shows no old result to the new account',async()=>{
  const t=setup();await prompt(t);t.wx.__calls.modal[0].success({confirm:true});t.login(8)
  t.sent[1].fail({errMsg:'SYNTH_OLD_REPORT_FAILURE'});assert.equal(t.wx.__calls.toast.length,0);assert.equal(t.sent.length,2)
})
;(async()=>{let failed=0;try{for(const item of tests){try{await item.run();console.log('PASS '+item.name)}catch(error){failed++;console.error('FAIL '+item.name,error)}}if(failed)process.exitCode=1;else console.log('AQUAFLOW_SUITE_OK '+tests.length)}finally{done()}})().catch(error=>{console.error(error);process.exitCode=1})
