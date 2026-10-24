'use strict'
// No loadPage/API module stubs: execute the complete real home JS closure in a native-style registry.
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),os=require('node:os'),vm=require('node:vm')
const {createWx,createApp,ROOT}=require('./harness')
const {requires,resolveId,analyzeProject,checkRepo}=require('../../scripts/check-miniapp-requires')
const tests=[],registrations=[],test=(name,run)=>tests.push({name,run})
function registerHome({omit=[]}={}) {
  const packageRoot=path.join(ROOT,'miniapp-user'), ids=new Set(checkRepo(ROOT)[0].files.filter(f=>!omit.includes(f))),cache=new Map(),loaded=[]
  const wx=createWx(),app=createApp();let page,network=0
  wx.request=()=>{network++;throw Error('registration must not make a business request')}
  const context=vm.createContext({wx,getApp:()=>app,getCurrentPages:()=>[],Page:value=>{page=value},Component(){},Behavior:value=>value,App(){},
    console:{log(){},warn(){},error(){}},setTimeout,clearTimeout,setInterval,clearInterval,__wxConfig:{envVersion:'develop'}})
  function load(id,args) {
    if(!ids.has(id))throw Error("module '"+id+"' is not defined, require args is '"+args+"'")
    if(cache.has(id))return cache.get(id).exports
    const module={exports:{}};cache.set(id,module);loaded.push(id)
    const source=fs.readFileSync(path.join(packageRoot,id),'utf8')
    const wrapped=vm.runInContext('(function(require,module,exports){\n'+source+'\n})',context,{filename:id})
    wrapped(spec=>load(resolveId(id,spec),spec),module,module.exports);return module.exports
  }
  try { load('pages/home/index.js','pages/home/index.js') }
  catch(error) { registrations.push({omitted:omit,loaded,error:error.message,networkRequests:network,pageRegistered:!!page});throw error }
  registrations.push({omitted:omit,loaded,networkRequests:network,pageRegistered:!!page})
  return {page,loaded,network}
}
test('both actual package trees have tracked, case-correct, nonexcluded literal require closures',()=>{
  const rows=checkRepo(ROOT);assert.equal(rows.length,2);assert(rows.every(r=>r.errors.length===0));assert(rows.every(r=>r.edges.length>0))
})
test('the actual complete home source registers without any business-module stubs',()=>{
  const f=registerHome();assert.equal(typeof f.page.onShow,'function');assert(f.loaded.includes('utils/station-view.js'));assert.equal(f.network,0)
})
test('omitting station-view from the actual module registry reproduces the reported native error',()=>{
  assert.throws(()=>registerHome({omit:['utils/station-view.js']}),/module 'utils\/station-view\.js' is not defined, require args is '\.\.\/\.\.\/utils\/station-view'/)
})
test('scanner ignores commented/sample require text but sees actual calls and flags dynamic resolution',()=>{
  const parsed=requires('// require("./absent")\nconst sample="require(\'./sample\')"; require("./real"); require(variable)')
  assert.deepEqual(parsed.found.map(r=>r.specifier),['./real']);assert.equal(parsed.unsupported.length,1)
})
for(const kind of ['missing','case','ignored','untracked'])test('package gate rejects '+kind+' newly required module',()=>{
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'af-native-require-')),project=path.join(dir,'miniapp-user')
  try{
    fs.mkdirSync(path.join(project,'pages/home'),{recursive:true});fs.mkdirSync(path.join(project,'utils'))
    fs.writeFileSync(path.join(project,'app.json'),JSON.stringify({pages:['pages/home/index']}));fs.writeFileSync(path.join(project,'app.js'),'App({})')
    fs.writeFileSync(path.join(project,'project.config.json'),JSON.stringify({packOptions:{ignore:kind==='ignored'?[{type:'file',value:'utils/added.js'}]:[]}}))
    fs.writeFileSync(path.join(project,'pages/home/index.js'),'require("../../utils/'+(kind==='case'?'Added':'added')+'")')
    if(kind!=='missing')fs.writeFileSync(path.join(project,'utils/added.js'),'module.exports={}')
    const tracked=new Set(['miniapp-user/app.js','miniapp-user/pages/home/index.js',...(kind==='untracked'?[]:['miniapp-user/utils/added.js'])])
    const r=analyzeProject(project,dir,tracked);assert(r.errors.length>0);assert(r.errors.some(e=>e.target?.toLowerCase()==='utils/added.js'))
  }finally{fs.rmSync(dir,{recursive:true,force:true})}
})
;(async()=>{const timer=setTimeout(()=>{console.error('NATIVE_HOME_CLOSURE_TIMEOUT');process.exit(1)},30000);let failed=0;const result=[]
  try{for(const t of tests){try{await t.run();result.push({name:t.name,pass:true});console.log('PASS '+t.name)}catch(e){failed++;result.push({name:t.name,pass:false,error:e.message});console.error('FAIL '+t.name+': '+e.stack)}}
    if(process.env.REVIEW_OUTPUT)fs.writeFileSync(process.env.REVIEW_OUTPUT,JSON.stringify({scope:'Native-style registry model with complete real source closure. Only platform/App registration is simulated; no business module stubs. Not a WeChat compiler test.',result,registrations},null,2))
    if(failed)process.exitCode=1;else console.log('AQUAFLOW_SUITE_OK '+tests.length)
  }finally{clearTimeout(timer)}
})().catch(e=>{console.error(e);process.exitCode=1})
