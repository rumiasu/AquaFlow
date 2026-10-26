const assert = require('assert')
const fs = require('fs'), path = require('path')
const { loadPage, createWx, createApp, armWatchdog, ROOT } = require('./harness')
const { parseWxml, renderElements } = require('./wxml-tree')
const done = armWatchdog(), tests = []
const test = (name, run) => tests.push({ name, run })
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r }); return { promise, resolve } }
const ok = data => ({ code: 0, data })
const tree = parseWxml(fs.readFileSync(path.join(ROOT, 'miniapp-user/pages/home/index.wxml'), 'utf8'))
const visible = page => renderElements({ ...tree, children: tree.children.filter(n => n.tag !== 'template') }, page.data, { includeText: true })
function home(summary = async () => ok({}), products = [], productResponse) {
  const wx = createWx(), app = createApp(), productCalls = []
  const page = loadPage('miniapp-user/pages/home/index.js', { wx, app, stubs: {
    'api/barrel': { getBarrelSummary: summary, getBarrelSummaryByType: async () => ({ data: [] }) },
    'api/product': { getStationProducts: async id => { productCalls.push(id); return productResponse || ok(products) } },
    'api/address': { getAddresses: async () => ({ data: [] }) },
    'api/order': { getOrders: async () => ({ data: [] }), getOrderDetail: async () => ({ data: null }) },
    'api/station': { getStationStatus: async () => ({ data: {} }) },
    'api/notification': { getUnreadNotifications: async () => ({ data: [] }) },
    'api/payment': { getQuote: async () => { throw new Error('No quote expected') } }
  } })
  page.loadStationStatus = async () => {}
  page.loadHomeOrders = async () => ({ data: [] })
  page.setData({ isLogin: true, state: 'ready', currentStationId: 11, currentStation: {id:11,name:'当前选择水站'} })
  return { page, wx, productCalls }
}
test('zero assets retain the selected-station explanation and existing barrel route', async () => {
  const {page,wx}=home(async()=>({data:{rightBuckets:0,owedBuckets:0,depositBalance:0}}))
  await page.loadData()
  assert.equal(page.data.barrelVisible,true)
  assert.equal(page.data.barrelLine2,'查看说明与办理入口')
  assert.ok(!page.data.barrelLine2.includes('可退'))
  page.onGoBarrel()
  assert.equal(wx.__calls.nav.at(-1).url,'/pages/barrel/index')
})
test('failed asset reading is not treated as a successful zero balance', async () => {
  const {page}=home(async()=>{throw new Error('synthetic network failure')})
  await page.loadData()
  assert.equal(page.data.barrelVisible,false)
  assert.equal(page.data.barrelLine2,'')
  assert.ok(page.data.loadError.includes('桶资产'))
  assert.equal(page.data.loadImpactSummary, '桶资产暂未核实')
  assert.ok(page.data.purchaseHint.includes('核对后才能提交订单'))
  const nodes = visible(page)
  assert.ok(nodes.some(n => n.className === 'home-feedback-sub' && n.text === '桶资产暂未核实'))
  assert.ok(!nodes.some(n => n.className.split(/\s/).includes('barrel-line')))
})
test('stationless page does not expose a station asset entry', async () => {
  const {page}=home(); page.setData({currentStationId:null})
  page.renderBarrelLine({depositBalance:30})
  assert.equal(page.data.barrelVisible,false)
})
test('existing debt reminders and actual deposit amount remain visible', async () => {
  const {page}=home(async()=>({data:{owedBuckets:2,depositBalance:60}})); await page.loadData()
  assert.ok(page.data.barrelLine2.includes('欠 2 个空桶'))
  assert.ok(page.data.barrelLine2.includes('押金余额 ¥60'))
})
test('late station response cannot restore the prior station asset entry', async () => {
  const wait=deferred(), {page}=home(()=>wait.promise)
  const pending=page.loadData(); page.setData({currentStationId:12})
  wait.resolve({data:{depositBalance:60}}); await pending
  assert.equal(page.data.barrelVisible,false)
})
test('home prices use the same product units as order details', async () => {
  const {page}=home(undefined,[{id:1,category:1,price:12},{id:2,category:2,price:2},{id:3,category:3,price:99},{id:4,price:9}])
  await page.loadData()
  assert.deepStrictEqual(page.data.productsView.map(p=>p.quantityUnit),['桶','瓶','台','件'])
})
test('a stationless catalog read does not request or expose station products', async () => {
  const {page,productCalls}=home(undefined,[{id:1,category:1,price:12}]); page.setData({currentStationId:null})
  await page.loadData()
  assert.deepStrictEqual(productCalls, []); assert.deepStrictEqual(page.data.productsView, [])
})

test('a rejected catalog response cannot masquerade as successfully priced products', async () => {
  const {page,productCalls}=home(undefined,[],{code:1,data:[{id:1,category:1,price:12}]})
  await page.loadData()
  assert.deepStrictEqual(productCalls,[11]); assert.equal(page.data.catalogState,'error')
  assert.deepStrictEqual(page.data.productsView,[]); assert.ok(page.data.loadImpactSummary.includes('商品暂未加载'))
})

test('completed contact uses the receiver snapshot and catches the card tap', async () => {
  const text=fs.readFileSync(path.join(ROOT,'miniapp-delivery/pages/home/index.wxml'),'utf8')
  // 业务注释不是定位接口；以实际completed分支渲染收件快照与客户档案冲突的场景。
  const findCompleted = node => node.attrs?.['wx:elif']?.replace(/\s/g, '') === "{{activeTab==='completed'}}"
    ? node : (node.children || []).map(findCompleted).find(Boolean)
  const completed = findCompleted(parseWxml(text))
  assert.ok(completed, 'completed列表必须存在')
  const wx=createWx(), calls=[]; wx.makePhoneCall=options=>calls.push(options.phoneNumber)
  const page=loadPage('miniapp-delivery/pages/home/index.js',{wx,app:createApp()})
  for (const [receiverPhone, customerPhone, expected] of [
    ['synthetic-receiver', 'synthetic-profile', 'synthetic-receiver'],
    ['', 'synthetic-profile', 'synthetic-profile'], ['', '', null]
  ]) {
    const nodes = renderElements(completed, { ...page.data, completedOrders: [{ id: 7, receiverPhone, customerPhone }] }, { includeText: true })
    const contacts = nodes.filter(n => n.attrs.catchtap === 'onCallCustomer')
    assert.equal(contacts.length, expected ? 1 : 0)
    if (expected) {
      assert.equal(contacts[0].text, expected)
      assert.equal(contacts[0].attrs['data-phone'].replace(/\s/g, ''), '{{item.receiverPhone||item.customerPhone}}')
    }
  }
  page.onCallCustomer({currentTarget:{dataset:{phone:'synthetic-receiver'}}})
  assert.deepStrictEqual(calls,['synthetic-receiver'])
  page.onCallCustomer({currentTarget:{dataset:{phone:''}}})
  assert.equal(calls.length,1); assert.ok(wx.__calls.toast.at(-1).title.includes('没有可拨'))
})
;(async()=>{let passed=0;for(const t of tests){try{await t.run();passed++;console.log('PASS '+t.name)}catch(e){console.error('FAIL '+t.name,e);process.exitCode=1}}done();if(passed===tests.length)console.log('AQUAFLOW_SUITE_OK '+passed)})()
