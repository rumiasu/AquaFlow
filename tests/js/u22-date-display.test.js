'use strict'
const assert=require('assert'),path=require('path')
const root=path.resolve(__dirname,'../..'),{loadPage,createWx,createApp}=require(path.join(root,'tests/js/harness'))
const {formatTime}=require(path.join(root,'miniapp-user/utils/format'))
const values=[{id:1,amount:'15.25',createTime:'2026-10-29T09:10:00'},{id:2,amount:'-7.05',createTime:null},{id:3,amount:'0',createTime:'bad date'},{id:4,amount:'3.00',createTime:''},{id:5,amount:'4.00',createTime:'   '},{id:6,amount:'-2.00',createTime:{}},{id:7,amount:'9.00',createTime:'2026-10-27T12:30:00'}]
const scope={stationId:11,session:{loggedIn:true,customerId:7}},wx=createWx(),app=createApp({globalData:{isLogin:true,customerId:7,accessToken:'synthetic'}})
const page=loadPage('miniapp-user/pages/deposit/records/index.js',{wx,app,stubs:{'utils/asset-view-station':{beginRead:()=>scope,current:()=>true},'utils/token':{isCurrentSession:()=>true},'utils/request':{get:async()=>({code:0,data:values})}}})
async function main(){await page.loadRecords();const a=page.data.records;assert.deepStrictEqual(a.map(x=>x.id),[1,2,3,4,5,6,7]);assert.equal(a[0].timeText,formatTime(values[0].createTime));assert.equal(a[6].timeText,formatTime(values[6].createTime));for(const r of a.slice(1,6)){assert.equal(r.timeText,'日期未提供');assert.equal(r.dateLabel,'日期未提供');assert(!r.timeText.includes('NaN'))};assert.deepStrictEqual(a.map(x=>x.showDateHeader),[true,true,false,false,false,false,true]);assert.deepStrictEqual(a.map(x=>x.amountText),['+15.25','-7.05','0.00','+3.00','+4.00','-2.00','+9.00']);console.log('AQUAFLOW_SUITE_OK 7')}
main().catch(e=>{console.error(e);process.exitCode=1})
