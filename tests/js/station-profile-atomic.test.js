'use strict'
const assert=require('node:assert/strict'),{loadPage,createWx,createApp}=require('./harness')
const tick=()=>new Promise(setImmediate)
function fixture(opts={}){
 const wx=createWx(),picks=[],writes=[],app=createApp({_loginGeneration:1,globalData:{isLogin:true,userInfo:{staffId:7,stationId:3,role:'STATION_MANAGER',bindStatus:'BOUND'}}})
 wx.chooseLocation=o=>picks.push(o)
 let station={id:3,name:'示例水站',phone:'123',address:'原地点 旧门牌',status:1,lat:30,lng:120,...opts.station}
 const p=loadPage('miniapp-delivery/pages/station-mgmt/station-info/index.js',{wx,app,stubs:{'config/api':{getTransportConfig:()=>({mode:'https',baseUrl:'https://synthetic.invalid'})},'utils/setup-progress':{initialModuleData:()=>({}),refreshModuleTasks:()=>{}},'api/station-mgmt':{
  getMyStation:async()=>({code:0,data:{...station}}),updateStationProfile:async(id,body)=>{writes.push({id,body});if(opts.wait)await opts.wait;if(opts.fail)throw opts.fail;station={...station,...body,lat:body.coordinateMode==='SET'?Number(body.lat.toFixed(6)):body.coordinateMode==='CLEAR'?null:station.lat,lng:body.coordinateMode==='SET'?Number(body.lng.toFixed(6)):body.coordinateMode==='CLEAR'?null:station.lng};return opts.result?opts.result(station):{code:0,data:{...station}}}
 }}})
 p.setData=(patch,cb)=>{for(const [key,value] of Object.entries(patch)){const ks=key.split('.');let q=p.data;for(const k of ks.slice(0,-1))q=q[k];q[ks.at(-1)]=value}if(cb)cb()}
 return {p,wx,picks,writes,app,get station(){return station}}
}
;(async()=>{
 {const t=fixture();await t.p.onShow();t.p.onSupplementInput({detail:{value:'2层'}});await t.p.onSave();assert.equal(t.writes.length,1);assert.deepEqual(t.writes[0].body,{address:'原地点 旧门牌 2层',coordinateMode:'KEEP'});assert.equal(t.p.data.coord.lat,30);assert.equal(t.p.data.form.address,'原地点 旧门牌 2层');assert.equal(t.p.data.addressSupplement,'')}
 {const t=fixture();await t.p.onShow();t.p.onPickCoordinates();t.picks[0].success({address:'新街',name:'新站',latitude:31.1234567,longitude:121.1234567});assert.equal(t.writes.length,0);t.p.onSupplementInput({detail:{value:'101'}});await t.p.onSave();assert.equal(t.writes.length,1);assert.equal(t.writes[0].body.coordinateMode,'SET');assert.equal(t.writes[0].body.address,'新街 新站 101');assert.equal(t.p.data.coord.lat,31.123457);assert.equal(t.p.data.coordinateMode,'KEEP')}
 {const t=fixture();await t.p.onShow();t.p.onAddressInput({detail:{value:'手填新地点'}});assert.equal(t.p.data.coordinateMode,'CLEAR');await t.p.onSave();assert.deepEqual(t.writes[0].body,{address:'手填新地点',coordinateMode:'CLEAR'});assert.equal(t.p.data.coord.lat,null);assert.equal(t.p.data.coord.lng,null)}
 for(const errMsg of ['chooseLocation:fail cancel','chooseLocation:fail auth deny']){const t=fixture();await t.p.onShow();t.p.onSupplementInput({detail:{value:'保留'}});t.p.onPickCoordinates();t.picks[0].fail({errMsg});assert.equal(t.p.data.addressSupplement,'保留');assert.equal(t.p.data.form.address,'原地点 旧门牌');assert.equal(t.writes.length,0);assert.equal(t.p.data.coordSaving,false)}
 {const t=fixture();await t.p.onShow();t.p.onPickCoordinates();t.picks[0].success({address:'不完整',latitude:NaN,longitude:120});assert.equal(t.p.data.form.address,'原地点 旧门牌');assert.equal(t.writes.length,0);assert(t.p.data.saveMessage.includes('不完整'))}
 {const t=fixture({fail:Object.assign(Error('not found'),{statusCode:404})});await t.p.onShow();t.p.onSupplementInput({detail:{value:'门牌'}});await t.p.onSave();assert.equal(t.writes.length,1);assert(t.p.data.saveMessage.includes('尚不支持'));assert.equal(t.p.data.addressSupplement,'门牌');assert.equal(t.p.data.form.address,'原地点 旧门牌')}
 {const t=fixture({fail:Object.assign(Error('无权'),{businessRejected:true})});await t.p.onShow();t.p.onAddressInput({detail:{value:'保留输入'}});await t.p.onSave();assert.equal(t.writes.length,1);assert.equal(t.p.data.form.address,'保留输入');assert.equal(t.p.data.coordinateMode,'CLEAR')}
 {const t=fixture();await t.p.onShow();t.app.globalData.userInfo.role='DELIVERY';await t.p.onSave();assert.equal(t.writes.length,0)}
 {const t=fixture({result:s=>({code:0,data:{...s,address:'不匹配'}})});await t.p.onShow();t.p.onSupplementInput({detail:{value:'保留'}});await t.p.onSave();assert(t.p.data.saveMessage.includes('未确认'));assert.equal(t.p.data.addressSupplement,'保留')}
 {const t=fixture({station:{lat:null,lng:null}});await t.p.onShow();t.p.onSupplementInput({detail:{value:'门牌'}});await t.p.onSave();assert.equal(t.writes[0].body.coordinateMode,'KEEP');assert.equal(t.p.data.coord.lat,null)}
 {let release;const wait=new Promise(resolve=>{release=resolve});const t=fixture({wait});await t.p.onShow();t.p.onSupplementInput({detail:{value:'101'}});const first=t.p.onSave();const second=t.p.onSave();assert.equal(t.writes.length,1);release();await Promise.all([first,second]);assert.equal(t.writes.length,1);assert.equal(t.p.data.infoDirty,false)}
 console.log('AQUAFLOW_SUITE_OK 12')
})().catch(e=>{console.error(e);process.exitCode=1})
