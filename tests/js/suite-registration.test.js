'use strict'
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm')
const runner=path.join(__dirname,'run-all.js'),source=fs.readFileSync(runner,'utf8')
let children=0,exit=null
const fakeProcess={execPath:process.execPath,argv:[],pid:17,stdout:{write(){}},stderr:{write(){}},exitCode:0,exit(code){exit=code;throw Error('synthetic stop')}}
const fakeFs={...fs,readdirSync(dir,options){return path.resolve(dir)===path.resolve(__dirname)
  ? fs.readdirSync(__dirname,options).concat('synthetic-unregistered.test.js') : []}}
try {
  vm.runInNewContext(source,{__dirname,process:fakeProcess,console:{log(){},error(){}},require(id){
    if(id==='fs')return fakeFs
    if(id==='child_process')return {spawnSync(){children++;return{status:0,stdout:'AQUAFLOW_SUITE_OK 1',stderr:''}}}
    if(id.startsWith('./'))return require(path.join(__dirname,id))
    return require(id)
  }})
}catch(e){if(e.message!=='synthetic stop')throw e}
assert.equal(exit||fakeProcess.exitCode,1,'actual runner must reject an unregistered discovered test')
assert.equal(children,0,'registry preflight must run before starting any suite')
const {registryProblems,completion}=require('./suite-registry')
let count=1
const valid=[{file:'a.test.js'}],exclude=[{file:'old.test.js',reason:'canonical a.test.js covers the current contract'}],found=['a.test.js','old.test.js']
assert.deepEqual(registryProblems(valid,exclude,found,()=>true),[]);count++
for(const [registered,excluded,discovered]of[
 [valid.concat(valid),exclude,found], [valid,exclude.concat(exclude),found],
 [valid,[{file:'old.test.js',reason:''}],found], [valid,[{file:'a.test.js',reason:'duplicate'}],found],
 [valid,exclude,found.concat('new.test.js')], [valid,exclude,['a.test.js']]
]){assert(registryProblems(registered,excluded,discovered,()=>true).length>0);count++}
for(const [status,output,error,passed]of[[0,'AQUAFLOW_SUITE_OK 3',null,true],[0,'partial only',null,false],[1,'AQUAFLOW_SUITE_OK 3',null,false],[0,'AQUAFLOW_SUITE_OK 3','EPERM',false],[0,'AQUAFLOW_SUITE_OK 0',null,false]]){
 assert.equal(completion(status,output,error).passed,passed);count++
}
console.log('AQUAFLOW_SUITE_OK '+count)
