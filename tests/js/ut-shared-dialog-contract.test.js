'use strict'
// Run after applying F: exercise the real shared adapter through U09's page-owned wrapper.
const assert=require('node:assert/strict')
const {loadPage,createWx,createApp,armWatchdog}=require('./harness')
const done=armWatchdog()
;(async()=>{
  const wx=createWx(),app=createApp(),page=loadPage('miniapp-user/pages/barrel/index.js',{wx,app})
  const edit={title:'更改安排',editable:true,description:'水站将重新批准安排',initialValue:'',placeholderText:'原因'}
  const cancelled=await page.arrangementDialog(edit)
  assert.equal(cancelled,null);assert.equal(wx.__calls.modal.length,0)
  console.log('PASS U09 unsupported native edit cancels without opening a misleading editor')
  let captured
  global.getCurrentPages=()=>[{selectComponent:()=>({open:options=>{captured=options;options.success({confirm:true,content:'已填写原因'});return Promise.resolve({confirm:true,content:'已填写原因'})}})}]
  const confirmed=await page.arrangementDialog(edit)
  assert.equal(captured.initialValue,'');assert.equal(captured.description,edit.description);assert.equal(captured.content,undefined)
  assert.equal(confirmed.content,'已填写原因')
  console.log('PASS U09 supported dialog keeps description separate from empty input and returns content')
  let sheetCount=0;wx.showActionSheet=options=>{sheetCount++;options.success({tapIndex:1})}
  assert.equal((await page.arrangementDialog({itemList:['到店','上门']},true)).tapIndex,1);assert.equal(sheetCount,1)
  console.log('PASS U09 action sheet remains native')
  done();console.log('AQUAFLOW_SUITE_OK 3')
})().catch(e=>{done();console.error(e);process.exitCode=1})
