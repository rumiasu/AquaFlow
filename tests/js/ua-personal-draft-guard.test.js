'use strict'
const assert = require('assert')
const { loadPage, createWx, createApp, armWatchdog } = require('./harness')
const finish = armWatchdog(15000)
function deepData(p) { p.setData = (patch, cb) => { for (const [key,value] of Object.entries(patch)) { const parts=key.replace(/\[(\d+)\]/g,'.$1').split('.'); let t=p.data; for (const part of parts.slice(0,-1)) t=t[part]; t[parts.at(-1)]=value } if(cb) cb() }; return p }
const input = (field, value) => ({currentTarget:{dataset:{field}},detail:{value}})
const wx=createWx(), app=createApp({globalData:{isLogin:true,customerId:7,accessToken:'synthetic-token',userInfo:{nickname:'原名',phone:''}}})
const profile = () => deepData(loadPage('miniapp-user/pages/mine/edit.js',{wx,app,stubs:{'api/auth':{updateProfile:async()=>({code:0})}}}))
const addr = () => deepData(loadPage('miniapp-user/pages/address/edit.js',{wx,app,stubs:{'api/address':{createAddress:async()=>({data:{id:1}}),updateAddress:async()=>({}),getAddressDetail:async id=>({data:{id,name:'原名',detail:'原门牌'}}),getAddresses:async()=>({data:[]})}}}))
const company = () => deepData(loadPage('miniapp-user/pages/mine/company.js',{wx,app,stubs:{'api/company':{getCompanyInfo:async()=>({data:{companyName:'原公司',dueDays:0}}),updateCompanyInfo:async f=>({data:f})}}}))
async function run() {
  let p=profile(); p.onLoad(); p.onNicknameInput({detail:{value:'历史昵称'}}); p.onPhoneInput({detail:{value:''}}); assert.equal(p.data.draftState,'active'); p.onUnload()
  p=profile(); p.onLoad(); assert.equal(p.data.draftState,'saved'); assert.equal(p.data.nickname,'原名'); p.onRestoreDraft(); assert.equal(p.data.nickname,'历史昵称'); assert.equal(p.data.phone,'')
  p.onUnload(); p=profile(); p.onLoad(); p.onNicknameInput({detail:{value:'新输入'}}); assert.equal(p.data.draftState,'saved'); assert.match(p.data.draftMessage,/尚未确认保存/)
  wx.__modalAutoConfirm=false; p.onRestoreDraft(); assert.equal(p.data.nickname,'新输入'); wx.__modalAutoConfirm=true; p.onDiscardSavedDraft(); assert.equal(p.data.nickname,'新输入'); assert.equal(p.data.draftState,'active')
  p.onUnload(); p=profile(); p.onLoad(); p.onRestoreDraft(); assert.equal(p.data.nickname,'新输入'); await p.onSubmit(); assert.equal(p.data.draftState,'empty')
  let a=addr(); a.onLoad({}); a.onShow(); a.onInputChange(input('name','张三')); a.onInputChange(input('floor','0')); a.onElevatorSelect({currentTarget:{dataset:{value:0}}}); a.onUnload()
  a=addr(); a.onLoad({}); a.onShow(); assert.equal(a.data.draftState,'saved'); assert.equal(a.data.formData.name,''); a.onRestoreDraft(); assert.equal(a.data.formData.name,'张三'); assert.equal(a.data.formData.floor,'0'); assert.equal(a.data.formData.hasElevator,0)
  let c=company(); c.onShow(); await c.loadInfo(); c.onStartEdit(); c.onInput(input('companyName','新公司')); assert.equal(c.data.draftState,'active'); c.onUnload()
  c=company(); c.onShow(); await c.loadInfo(); assert.equal(c.data.draftState,'saved'); assert.equal(c.data.form.companyName,'原公司'); c.onRestoreDraft(); assert.equal(c.data.form.companyName,'新公司'); assert.equal(c.data.form.dueDays,'0')
  wx.__modalAutoConfirm=false; c.onCancelEdit(); assert.equal(c.data.editing,true); wx.__modalAutoConfirm=true; c.onCancelEdit(); assert.equal(c.data.editing,false); assert.equal(c.data.draftState,'empty')
  let bad=profile(); bad.onLoad(); const write=wx.setStorageSync; wx.setStorageSync=()=>{throw Error('unavailable')}; bad.onNicknameInput({detail:{value:'保留输入'}}); assert.equal(bad.data.nickname,'保留输入'); assert.match(bad.data.draftMessage,/尚未确认保存/); wx.setStorageSync=write; bad.onRetryDraft(); assert.equal(bad.data.draftState,'active')
  app.globalData.customerId=9; app.globalData.userInfo={nickname:'九号',phone:''}
  const cleanup=profile(); cleanup.onLoad(); cleanup.onNicknameInput({detail:{value:'已保存待清理'}})
  const remove=wx.removeStorageSync; wx.removeStorageSync=()=>{throw Error('unavailable')}; await cleanup.onSubmit()
  assert.match(cleanup.data.draftMessage,/未确认清除/); assert.equal(cleanup._saveCompleted,true)
  wx.removeStorageSync=remove; cleanup.onRetryDraft(); assert.equal(cleanup.data.draftState,'empty')
  app.globalData.customerId=10; app.globalData.userInfo={nickname:'十号',phone:''}
  const first=profile(); first.onLoad(); first.onNicknameInput({detail:{value:'窗口一'}})
  const second=profile(); second.onLoad(); second.onRestoreDraft(); second.onNicknameInput({detail:{value:'窗口二'}})
  await first.onSubmit(); assert.match(first.data.draftMessage,/已有变化/); assert.equal(second._draftGuard.read(second._draftToken).draft.nickname,'窗口二')
  app.globalData.customerId=7; app.globalData.userInfo={nickname:'新输入',phone:''}
  bad.onUnload(); bad=profile(); bad.onLoad(); bad.onNicknameInput({detail:{value:'当前输入'}}); let modal; wx.showModal=o=>{modal=o}; bad.onRestoreDraft(); app.globalData.customerId=8; modal.success({confirm:true}); assert.equal(bad.data.nickname,'当前输入'); assert.equal(bad.data.draftState,'saved')
  app.globalData.userInfo={nickname:'另一用户',phone:''}; const other=profile(); other.onLoad(); assert.equal(other.data.draftState,'empty'); assert.equal(other.data.nickname,'另一用户')
  console.log('AQUAFLOW_SUITE_OK 10'); finish()
}
run().catch(e=>{console.error(e);finish();process.exitCode=1})
