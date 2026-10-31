'use strict'
const assert = require('assert')
const { loadPage, createWx, createApp, armWatchdog } = require('./harness')
const done = armWatchdog(15000), wx = createWx(), app = createApp()
wx.setStorageSync('selectedStation', {id:1,name:'一站'})
const ok = data => ({code:0,data})
const p = loadPage('miniapp-user/pages/mine/asset-claims/index.js', { wx, app, stubs: {
 'api/asset-claims': { mine:async()=>ok([]), products:async()=>ok([{id:11,name:'合成商品',spec:'18L',category:1,imageUrl:'sample.png'}]) },
 'api/station': {getPublicStations:async()=>ok([{id:1,name:'一站'},{id:2,name:'二站'}])},
 'utils/claim-photo': {lookup:async()=>({exists:false}),upload:async()=>({exists:true})}
}})
;(async()=>{
 await p.onShow(); assert.equal(p.data.selectorCatalog[0].image,'sample.png'); assert.equal(p.data.selectorCatalog[0].priceText,'价格待确认')
 p.onSelectCatalog({detail:{id:11}}); assert.equal(p.data.form.items[0].productId,11); assert.equal(p.data.selectorCatalog[0].disabled,true)
 p.onSelectCatalog({detail:{id:11}}); assert.equal(p.data.form.items.length,1)
 p.onField({currentTarget:{dataset:{field:'rights',index:0}},detail:{value:''}}); assert.equal(p.data.form.items[0].rights,'')
 p.onRemoveProduct({currentTarget:{dataset:{index:0}}}); assert.equal(p.data.selectorCatalog[0].disabled,false)
 await p.onStation({detail:{value:'1'}});assert.equal(p.data.stationId,2);assert.equal(p.data.form.items.length,0)
 console.log('AQUAFLOW_SUITE_OK 4');done()
})().catch(e=>{console.error(e);done();process.exitCode=1})
