const assert = require('assert')
const Module = require('module')
const path = require('path')
const original = Module._load
let calls = [], now = Date.now()
Date.now = () => now
global.setTimeout = fn => ({ fn })
global.clearTimeout = () => {}
Module._load = function(request, parent, isMain) {
  if (request === './transport' && parent.filename.endsWith(path.join('utils', 'ui-scene.js'))) return {send: opts => calls.push(opts)}
  return original.apply(this, arguments)
}
const scene = require('../../miniapp-user/utils/ui-scene')
Module._load = original
function page() { return { data:{sceneImageUrl:scene.LOCAL, failed:false}, setData(patch) { Object.assign(this.data,patch) } } }
function respond(i, url, expiresAt) { calls[i].success({statusCode:200,data:{code:0,data:{url,expiresAt}}}) }
async function tick() { for(let i=0;i<5;i++) await Promise.resolve() }
;(async()=>{
  const a=page(), b=page()
  scene.show(a,'failed'); scene.show(b,'failed')
  assert.equal(calls.length,1); assert.equal(a.data.sceneImageUrl,scene.LOCAL)
  respond(0,'https://example.invalid/signed-one', now+3600000); await tick()
  assert.equal(a.data.sceneImageUrl,'https://example.invalid/signed-one'); assert.equal(b.data.sceneImageUrl,a.data.sceneImageUrl)
  const timer = a._sceneTimer; now += 3540001; timer.fn()
  assert.equal(calls.length,2); assert.equal(a.data.sceneImageUrl,'https://example.invalid/signed-one')
  respond(1,'https://example.invalid/refreshed',now+3600000); await tick()
  assert.equal(a.data.sceneImageUrl,'https://example.invalid/refreshed')
  const refresh=scene.resolve(true); assert.equal(calls.length,3); assert.equal(a.data.sceneImageUrl,'https://example.invalid/refreshed')
  respond(2,'https://example.invalid/signed-two',now+3600000); await refresh
  scene.imageError(a,{currentTarget:{dataset:{src:a.data.sceneImageUrl}},detail:{errMsg:'image load failed'}},'failed')
  assert.equal(calls.length,4); respond(3,'https://example.invalid/signed-three',now+3600000); await tick()
  assert.equal(a.data.sceneImageUrl,'https://example.invalid/signed-three')
  scene.imageError(a,{currentTarget:{dataset:{src:a.data.sceneImageUrl}},detail:{errMsg:'still failed'}},'failed')
  assert.equal(a.data.sceneImageUrl,scene.LOCAL); assert.equal(calls.length,4)
  scene.imageError(a,{currentTarget:{dataset:{src:scene.LOCAL}},detail:{errMsg:'local failed'}},'failed')
  assert.equal(a.data.failed,true)
  scene.hide(a); scene.hide(b)
  console.log('AQUAFLOW_SUITE_OK 5')
})().catch(e=>{console.error(e);process.exitCode=1})
