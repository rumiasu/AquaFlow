const assert=require('assert'),fs=require('fs'),path=require('path')
const { fixture }=require('./extreme-product-save.test')
const { parseWxml,renderElements }=require('./wxml-tree')
const tree=parseWxml(fs.readFileSync(path.join(__dirname,'../../miniapp-delivery/pages/station-mgmt/products/index.wxml'),'utf8'))
const row=(saved,extra={})=>({id:17,ownerStationId:null,name:'很长的本站商品名称'.repeat(4),displayName:'本站商品',selected:true,status:1,category:1,price:8,deposit:0,
 salePrice:null,depositPrice:null,effectivePriceText:'8.00',effectiveDepositText:'0.00',refPriceText:'8.00',refDepositText:'0.00',enabled:1,ticketEnabled:0,pricingConfirmed:saved,...extra})
const event=item=>({currentTarget:{dataset:{item,id:item.id}}})
const texts=t=>renderElements(tree,t.page.data,{includeText:true}).filter(x=>x.tag==='text').map(x=>x.text).join('\n')
;(async()=>{
 for(const saved of [0,1]){
  const t=fixture(),item=row(saved)
  t.page.setData({loading:false,list:[item],viewList:[item]})
  assert.equal(texts(t).includes('参考价'),saved===0)
  t.page.onOpenSetting(event(item));t.page.setData({pkgReady:true,viewList:[]})
  assert.equal(texts(t).includes('参考价'),saved===0)
  assert.equal(t.page.data.setting.pricingConfirmed,saved===1)
  if(saved===1)assert.ok(texts(t).includes('本站已保存 ¥0.00'))
 }
 const t=fixture(),item=row(0)
 t.page.onOpenSetting(event(item));t.page.setData({pkgReady:true,'editForm.price':'','editForm.deposit':''})
 t.state.setting=async()=>{throw Error('合成保存失败')}
 await t.page.onSaveEdit()
 assert.equal(t.page.data.setting.pricingConfirmed,false)
 const body=t.calls.find(call=>call[0]==='setting')[2]
 assert.ok(!Object.hasOwn(body,'salePrice')&&!Object.hasOwn(body,'depositPrice')&&!Object.hasOwn(body,'pricingConfirmed'))
 t.state.setting=async()=>({code:0})
 t.page.loadData=async()=>t.page.setData({list:[row(1)],viewList:[row(1)]})
 await t.page.onSaveEdit()
 t.page.onOpenSetting(event(t.page.data.list[0]));assert.equal(t.page.data.setting.pricingConfirmed,true)
 t.page.closeSetting();t.page.onOpenSetting(event(row(0,{id:18})))
 assert.equal(t.page.data.setting.pricingConfirmed,false,'another station/product must use its own response state')
 console.log('PASS durable response controls first/reference and saved/zero-deposit views; failed save and blank fields remain unchanged')
 console.log('AQUAFLOW_SUITE_OK 3')
})().catch(error=>{console.error(error);process.exitCode=1})
