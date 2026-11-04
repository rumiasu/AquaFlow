'use strict'
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { parseWxml, renderElements, validateConditionalSiblings } = require('./wxml-tree')
const { loadPage, createWx, createApp } = require('./harness')
const root=path.resolve(__dirname,'../..')
const wxml=fs.readFileSync(path.join(root,'miniapp-user/pages/order/create.wxml'),'utf8')
const css=fs.readFileSync(path.join(root,'miniapp-user/pages/order/create.wxss'),'utf8')
const start=wxml.indexOf('<view class="detail-popup '), end=wxml.indexOf('  <!-- ══ 综合确认弹窗',start)
assert(start>=0 && end>start)
const tree=parseWxml(wxml.slice(start,end)); validateConditionalSiblings(tree)
const page=loadPage('miniapp-user/pages/order/create.js',{wx:createWx(),app:createApp(),stubs:{'api/order':{},'api/station':{},'api/address':{},'api/product':{}}})
const render=state=>renderElements(tree,{...page.data,...state},{includeText:true})
const joined=nodes=>nodes.map(n=>n.text||'').join('|')
const pairs=(nodes,labelClass,valueClass)=>Object.fromEntries(nodes.flatMap((node,index)=>{
  if(!node.className.split(' ').includes(labelClass))return []
  const value=nodes.slice(index+1).find(next=>next.className.split(' ').includes(valueClass))
  assert(value,'missing amount for '+node.text)
  return [[node.text,value.text]]
}))
const itemRows=nodes=>pairs(nodes,'detail-item-name','detail-item-price')
const footerRows=nodes=>pairs(nodes,'detail-total-label','detail-total-value')
assert(!wxml.includes('class="section fee-summary"'))
page.onShowDetail(); assert.equal(page.data.showDetailPopup,true)
const quote={showDetailPopup:true,quoteReady:true,ticketCoverText:'',isTicketPay:false,totalWaterCostText:'20.00',extraDepositBuckets:1,extraDepositAmountText:'50.00',barrelPurchases:[{productId:1,quantity:1}],feeBreakdownReady:true,deliveryFeeText:'3.00',floorFeeText:'2.00',payableAmountText:'75.00'}
const normal=render(quote), text=joined(normal)
for(const value of ['水费','¥20.00','本次新增桶押金','本次新增 1 份','¥50.00','配送费','¥3.00','楼层费','¥2.00','¥75.00'])assert(text.includes(value),value)
assert(!text.includes('商品零售参考')&&!text.includes('小计'))
assert(normal.some(n=>n.attrs.bindtap==='onStandalonePurchase'))
const unknown=joined(render({...quote,feeBreakdownReady:false}));assert(unknown.includes('配送费、楼层费明细')&&unknown.includes('待核实'))
const ticketQuote={...quote,ticketCoverText:'使用 2 张水票 · 抵扣 ¥40.00',isTicketPay:true,
  totalWaterCostText:'40.00',extraDepositBuckets:0,barrelPurchases:[],deliveryFeeText:'5.00',floorFeeText:'2.00',
  ticketDeliveryCredit:0,ticketDeliveryCreditText:'0.00',payableAmountText:'7.00',payableAmountLabel:'本次现金需付'}
const ticket=render(ticketQuote)
assert.deepEqual(itemRows(ticket),{'配送费':'¥5.00','楼层费':'¥2.00'})
assert.deepEqual(footerRows(ticket),{'使用 2 张水票 · 抵扣 ¥40.00':'抵水费','本次现金需付':'¥7.00'})

// 优惠另列，原配送/楼层金额和实际余款全部沿用服务端展示值，不在视图中减一遍。
for(const [credit,payable] of [['5.00','2.00'],['3.00','4.00']]){
  const credited=render({...ticketQuote,ticketDeliveryCredit:Number(credit),ticketDeliveryCreditText:credit,payableAmountText:payable})
  assert.deepEqual(itemRows(credited),{'配送费':'¥5.00','楼层费':'¥2.00'})
  assert.equal(footerRows(credited)['本次配送费抵扣'],'−¥'+credit)
  assert.equal(footerRows(credited)['本次现金需付'],'¥'+payable)
}
const zero=render({...ticketQuote,deliveryFeeText:'',floorFeeText:'',payableAmountText:'0.00'})
assert.deepEqual(itemRows(zero),{'配送费、楼层费':'¥0.00'})
assert.equal(footerRows(zero)['本次现金需付'],'¥0.00')
assert(!joined(zero).includes('本次配送费抵扣'))
const floorOnly=render({...ticketQuote,deliveryFeeText:'',payableAmountText:'2.00'})
assert.deepEqual(itemRows(floorOnly),{'配送费':'¥0.00','楼层费':'¥2.00'})
const deliveryOnly=render({...ticketQuote,floorFeeText:'',payableAmountText:'5.00'})
assert.deepEqual(itemRows(deliveryOnly),{'配送费':'¥5.00'})

const ticketUnknown=render({...ticketQuote,feeBreakdownReady:false})
assert.deepEqual(itemRows(ticketUnknown),{'配送费、楼层费明细':'待核实'})
assert.equal(footerRows(ticketUnknown)['本次现金需付'],'¥7.00')
for(const quoteError of ['', '本次费用未能核实']){
  // 特意保留所有旧金额，证明报价失效后的抽屉不会把上次优惠当成本次已核实金额。
  const pending=render({...ticketQuote,quoteReady:false,quoteLoading:!quoteError,quoteError,
    ticketDeliveryCredit:5,ticketDeliveryCreditText:'5.00'})
  assert.deepEqual(itemRows(pending),{})
  assert.deepEqual(footerRows(pending),{'本次现金需付':'待核实'})
  assert(!joined(pending).includes('¥'))
  if(!quoteError)assert(joined(pending).includes('正在核实本次费用'))
}
const suppliedTotal=render({...ticketQuote,payableAmountText:'7.03'})
assert.equal(footerRows(suppliedTotal)['本次现金需付'],'¥7.03','视图必须保留服务端金额，不自行按分项重算')
assert(/\.detail-sheet-body\s*\{[^}]*overflow-y:\s*auto/s.test(css))
assert(/\.detail-sheet\s*\{[^}]*max-height:\s*75vh/s.test(css))
page.onDetailPopupClose();assert.equal(page.data.showDetailPopup,false)
assert.equal(render({...quote,showDetailPopup:false}).length,0)
console.log('AQUAFLOW_SUITE_OK 14')
