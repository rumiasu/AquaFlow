'use strict'
const assert = require('node:assert/strict'), fs = require('node:fs'), path = require('node:path')
const { fixture, visible, texts } = require('./customer-home-recovery-view.test')
const { loadPage, createWx, createApp, ROOT } = require('./harness')
const { parseWxml, renderElements, validateConditionalSiblings } = require('./wxml-tree')
const { stationName } = require('../../miniapp-user/utils/station-view')
const tests = [], test = (name, run) => tests.push({ name, run })
const deliveryTree = parseWxml(fs.readFileSync(path.join(ROOT, 'miniapp-delivery/pages/home/index.wxml'), 'utf8'))
const navTree = parseWxml(fs.readFileSync(path.join(ROOT, 'miniapp-delivery/templates/station-navbar.wxml'), 'utf8'))
function delivery() {
  const wx = createWx(), app = createApp({ canAccessStationBusiness: () => true, globalData: { isLogin:true, userInfo:{staffId:1,stationId:1,role:'DELIVERY'} } })
  const page = loadPage('miniapp-delivery/pages/home/index.js', { wx, app, stubs: { 'api/delivery':{}, 'utils/request':{get:async()=>({code:0,data:{}})} } })
  page.setData({ loading:false, loadError:'5项信息暂未加载', readReady:{assigned:false,delivering:false,completed:false,unpaid:false,transfers:false} })
  return {page,wx,app}
}
test('only the exact confirmed scenario-test station gets a Chinese test label', async () => {
  const f=fixture(), station={id:7,name:'SYNTH Task5 Scenario Station'}
  f.wx.setStorageSync('selectedStation',station); f.page.loadData=async()=>{}; await f.page.checkStation()
  assert(texts(f.page).includes('示例水站（测试）')); assert(!texts(f.page).includes(station.name))
  assert.equal(f.page.data.currentStationId,7); assert.equal(f.wx.getStorageSync('selectedStation').name,station.name)
  for(const name of ['ABC Water Station','SYNTH Water Station','Task5 Community Station','constructor','toString'])assert.equal(stationName({name}),name)
})
test('customer combines five missing categories with one retry and keeps full details and saved quantities', () => {
  const f=fixture(); f.page.setData({state:'ready',catalogState:'error'})
  f.page._noteLoadError('商品、订单信息、桶资产、配送地址暂未加载','overview'); f.page._noteLoadError('提醒暂未加载','notifications')
  assert.equal(f.page.data.loadImpactSummary,'5项信息暂未加载')
  assert.equal(visible(f.page).filter(n=>n.tag==='button'&&n.attrs.bindtap==='onRetryHome').length,1)
  assert(!visible(f.page).some(n=>n.className.split(' ').includes('catalog-state')))
  assert(texts(f.page).includes('原选购数量已保留')); assert.deepEqual(f.carts[1],{10:2})
  f.page.onToggleLoadDetails(); assert(texts(f.page).includes('商品、订单信息、桶资产、配送地址暂未加载'))
})
test('catalog failure retains a standalone retry when the shared overview has no failure banner', () => {
  const f=fixture(); f.page.setData({state:'ready',catalogState:'error',loadError:''})
  assert(visible(f.page).some(n=>n.className.split(' ').includes('catalog-state')))
  assert.equal(visible(f.page).filter(n=>n.tag==='button'&&n.attrs.bindtap==='onRetryHome').length,1)
})
test('delivery merges ordinary and financial read failures, with a separate action and short unknown counts', () => {
  validateConditionalSiblings(deliveryTree); const f=delivery(), nodes=renderElements(deliveryTree,f.page.data,{includeText:true})
  assert.equal(nodes.filter(n=>n.className==='home-read-feedback').length,1)
  assert.equal(nodes.filter(n=>n.tag==='button'&&n.attrs.bindtap==='onRetryVisibleData').length,1)
  assert(nodes.some(n=>n.text==='待收款未加载，不代表结清。'))
  const units=nodes.filter(n=>n.className==='stat-unit'); assert.equal(units.length,3); assert(units.every(n=>n.text==='—'))
  assert.equal(f.page.data.readReady.unpaid,false)
})
test('confirmed zero and unknown financial data stay distinguishable', () => {
  const f=delivery(); f.page.setData({loadError:'',readReady:{assigned:true,delivering:true,completed:true,unpaid:false,transfers:true}})
  const nodes=renderElements(deliveryTree,f.page.data,{includeText:true})
  assert(nodes.filter(n=>n.className==='stat-unit').every(n=>n.text==='0 单'))
  assert(nodes.some(n=>n.text==='待收款未加载，不代表结清。'))
  f.page.setData({readReady:{assigned:true,delivering:true,completed:true,unpaid:true,transfers:true}})
  assert(!renderElements(deliveryTree,f.page.data).some(n=>n.className==='home-read-feedback'))
})
test('unread station status is neutral while a real business warning retains its warning class', () => {
  validateConditionalSiblings(navTree); const f=delivery(); f.page.setData({stationStatusError:'合成读取失败',stationStatusText:'',stationStatusHint:'',stationPendingCount:0})
  let nodes=renderElements(navTree,f.page.data,{includeText:true}), chip=nodes.find(n=>n.className.split(' ').includes('status-chip'))
  assert(chip.className.includes('status-chip-unloaded')); assert(!chip.className.includes('status-chip-warn'))
  assert(nodes.some(n=>n.text==='状态未加载'))
  f.page.setData({stationStatusError:'',stationStatusText:'配送延迟',stationStatusHint:'请留意配送安排'})
  nodes=renderElements(navTree,f.page.data,{includeText:true}); chip=nodes.find(n=>n.className.split(' ').includes('status-chip'))
  assert(chip.className.includes('status-chip-warn')); assert(nodes.some(n=>n.text==='配送延迟'))
})
;(async()=>{const timer=setTimeout(()=>{console.error('HOME_FAILURE_TIMEOUT');process.exit(1)},30000);let failed=0
  try{for(const t of tests){try{await t.run();console.log('PASS '+t.name)}catch(e){failed++;console.error('FAIL '+t.name+': '+e.stack)}}
    if(failed)process.exitCode=1;else console.log('AQUAFLOW_SUITE_OK '+tests.length)
  }finally{clearTimeout(timer)}
})().catch(e=>{console.error(e);process.exitCode=1})
