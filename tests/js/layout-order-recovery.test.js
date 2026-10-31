'use strict'
const assert=require('assert'),path=require('path'),root=path.resolve(__dirname,'../..')
const {loadPage,createWx,createApp}=require(path.join(root,'tests/js/harness'))
const wx=createWx(),app=createApp({globalData:{isLogin:true,customerId:7,accessToken:'synthetic',refreshToken:'synthetic-refresh'}})
const token=require(path.join(root,'miniapp-user/utils/token'));token.beginSession()
const page=loadPage('miniapp-user/pages/order/create.js',{wx,app});let pending=[]
wx.createSelectorQuery=()=>{const q={in:()=>q,select:()=>q,boundingClientRect:cb=>{pending.push(cb);return q},exec:()=>{}};return q}
page.setData({stationId:11,submitState:'idle',unknownResultText:'',orderNavigationError:'',blocked:false,orderNavigationRecoveryStyle:''})
page._showOrderRecoveryBlocked('原提交结果尚未确认，请核对原记录');assert.equal(pending.length,1);pending[0]({height:220.2});assert.equal(page.data.orderNavigationRecoveryStyle,'padding-bottom:calc(221px + 24rpx)')
page.onResize();assert.equal(pending.length,2);page.setData({submitState:'idle',unknownResultText:''});page._measureOrderRecoveryBar();assert.equal(page.data.orderNavigationRecoveryStyle,'');pending[1]({height:400});assert.equal(page.data.orderNavigationRecoveryStyle,'')
page._showOrderRecoveryBlocked('长消息');const stale=pending[pending.length-1];page._quoteDestroyed=true;stale({height:300});assert.equal(page.data.orderNavigationRecoveryStyle,'')
console.log('AQUAFLOW_SUITE_OK 4')
