const assert = require('assert')
const { loadPage, createWx, createApp, armWatchdog } = require('./harness')
const done = armWatchdog(15000), ok = data => ({code:0,data})
const wx = createWx(), app = createApp({globalData:{isLogin:true, customerId:7, accessToken:'fake'}})
function page() {
  const p=loadPage('miniapp-user/pages/template/index.js',{wx,app,stubs:{'api/template':{getTemplates:async()=>ok([]),saveTemplate:async()=>ok({})},'api/product':{},'api/address':{},'utils/station':{resolveStationId:async()=>1}}})
  p.setData=function(patch,cb){ for(const [key,value] of Object.entries(patch)){const parts=key.replace(/\[(\d+)\]/g,'.$1').split('.');let x=this.data;for(let i=0;i<parts.length-1;i++)x=x[parts[i]];x[parts.at(-1)]=value} if(cb)cb() }
  p.loadData=async()=>{};p.loadFormData=async()=>{};p.onShow();p.setData({stationId:1,products:[{id:11,name:'桶装水',spec:'18L',imageUrl:'a.png'}]});return p
}
;(async()=>{
 const first=page();first.onAddTemplate();first.onNameInput({detail:{value:'上次输入'}});assert.equal(first.data.draftNotice,'')
 const row=first.data.form.items[0];first.onSelectProduct({currentTarget:{dataset:{index:0,rowKey:row.rowKey}},detail:{id:11}});assert.equal(first.data.form.items[0].productId,11)
 first.onQuantityInput({currentTarget:{dataset:{index:0,rowKey:row.rowKey}},detail:{value:''}});assert.equal(first.data.form.items[0].quantity,'')
 first.onUnload();const second=page();second.onAddTemplate();assert.equal(second.data.storedDraft,true);assert.equal(second.data.form.name,'')
 second.onNameInput({detail:{value:'本次输入'}});wx.__modalAutoConfirm=false;second.onRestoreStoredDraft();assert.equal(second.data.form.name,'本次输入')
 wx.__modalAutoConfirm=true;second.onRestoreStoredDraft();assert.equal(second.data.form.name,'上次输入');assert.equal(second.data.form.items[0].quantity,'');assert.equal(second.data.form.items[0].productId,11)
 second.onUnload();app.globalData.customerId=8;const other=page();other.onAddTemplate();assert.equal(other.data.storedDraft,false)
 other.onNameInput({detail:{value:'另一账号'}});other.onUnload();app.globalData.customerId=7;const back=page();back.setData({stationId:2});back.onAddTemplate();assert.equal(back.data.storedDraft,false)
 app.globalData.customerId=20; const a=page(), b=page(); a.onAddTemplate(); b.onAddTemplate()
 a.onNameInput({detail:{value:'窗口A'}}); b.onNameInput({detail:{value:'窗口B'}}); assert.equal(b.data.storedDraft,true); assert.match(b.data.draftNotice,/尚未确认保存/)
 b.onRestoreStoredDraft(); assert.equal(b.data.form.name,'窗口A'); b.onNameInput({detail:{value:'B已更新'}})
 a.onNameInput({detail:{value:'A新输入'}}); assert.equal(a.data.storedDraft,true); assert.match(a.data.draftNotice,/尚未确认保存/)
 app.globalData.customerId=22;const old=page();old.onAddTemplate();old.onNameInput({detail:{value:'旧草稿'}});old.onUnload()
 const pending=page();pending.onAddTemplate();pending.onNameInput({detail:{value:'当前输入'}});let delayed;const showModal=wx.showModal;wx.showModal=o=>{delayed=o}
 pending.onRestoreStoredDraft();pending.onHide();pending.onShow();delayed.success({confirm:true});assert.equal(pending.data.form.name,'当前输入');wx.showModal=showModal
 app.globalData.customerId=21;const cleanup=page();cleanup.onAddTemplate();cleanup.onNameInput({detail:{value:'已提交'}})
 const cleanupRow=cleanup.data.form.items[0];cleanup.onSelectProduct({currentTarget:{dataset:{index:0,rowKey:cleanupRow.rowKey}},detail:{id:11}})
 const remove=wx.removeStorageSync;wx.removeStorageSync=()=>{throw Error('disk')};await cleanup.onSaveTemplate()
 assert.equal(cleanup.data.showEditModal,true);assert.equal(cleanup._draftCleanupPending,true);assert.match(cleanup.data.draftNotice,/尚未确认清除/)
 wx.removeStorageSync=remove;cleanup.onRetryFormDraft();assert.equal(cleanup._draftCleanupPending,false);assert.equal(cleanup.data.showEditModal,false)
 const broken=page();wx.setStorageSync=()=>{throw Error('disk')};broken.onAddTemplate();broken.onNameInput({detail:{value:'不能保存'}});assert.match(broken.data.draftNotice,/未确认保存/)
 console.log('AQUAFLOW_SUITE_OK 10');done()
})().catch(e=>{console.error(e);done();process.exitCode=1})
