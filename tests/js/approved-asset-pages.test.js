const assert = require('assert')
const path = require('path')
const { loadPage, createWx, createApp, armWatchdog, ROOT } = require('./harness')
const { STORAGE_KEYS } = require('../../miniapp-user/utils/storage-keys')
const done = armWatchdog(30000)
let count = 0
async function test(name, fn) { await fn(); count++; console.log('PASS ' + name) }
const ok = data => ({ code: 0, data })
function purchase() {
  const wx = createWx(), app = createApp({ globalData: { isLogin: true } }), quotes = []
  wx.setStorageSync(STORAGE_KEYS.CUSTOMER_ID, 7)
  const p = loadPage('miniapp-user/pages/barrel/purchase.js', { wx, app, stubs: {
    'api/asset-stations': { getAssetStation: async () => ok({ id: 99, name: '错误水站' }) },
    'api/product': { getStationProducts: async () => ok([
      { id: 1, category: 1, name: '山泉水', spec: '大桶', deposit: 30 },
      { id: 2, category: 1, name: '纯净水', spec: '小桶', deposit: 20 }]) },
    'api/barrel': { quoteBarrelRight: async (station, product, qty) => { quotes.push({station, product, qty}); return ok({ amount: 20, stationId: station, stationName: '阳光水站', notice: '仅办理桶押金，本次不送水和桶；收到押金后生效。首次领取对应桶无需还桶，已有欠桶优先补足。' }) },
      getBarrelRightPurchases: async () => ok([]) }
  } })
  return { p, quotes }
}
function barrel(list) {
  const wx = createWx(), app = createApp()
  wx.__storage.set('selectedStation', { id: 11, name: '阳光水站' })
  app.globalData.accessToken = 'test-session'
  delete require.cache[require.resolve(path.join(ROOT, 'miniapp-user/utils/asset-view-station.js'))]
  const h = (id, availableRights) => ({ productId: id, productName: '测试桶' + id, independentRights: true,
    assetQty: availableRights, heldTotalQty: availableRights, inTransitQty: 0, occupiedQty: availableRights,
    owedQty: 0, storageQty: 0, availableRights, deposit: 30 })
  const p = loadPage('miniapp-user/pages/barrel/index.js', { wx, app, stubs: {
    'api/asset-stations': { getAssetStation: async () => ok({ id: 11, name: '阳光水站' }), listAssetStations: list },
    'api/barrel': { getBarrelSummary: async () => ok({ independentRights: true, rightBuckets: 2, occupiedBuckets: 2, heldBuckets: 2, availableRights: 2, owedBuckets: 0, pendingReturns: 0, storageBuckets: 0, depositBalance: 60 }),
      getBarrelSummaryByType: async () => ok([h(1, 0), h(2, 2)]), getBarrelRecords: async () => ok([]), previewBarrelReturn: async () => ok({refundAmount:30}) },
    'api/product': { getProductDetail: async () => ok(null) }
  } })
  return p
}
;(async () => {
  await test('商品搜索后按原 ID 选择并重新报价，未知规格不填造', async () => {
    const {p, quotes} = purchase(); await p.onLoad({stationId: 11})
    p.onOpenProductPicker(); p.onProductSearch({detail:{value:'小桶'}})
    assert.deepStrictEqual(p.data.filteredProductOptions.map(x=>x.id), [2])
    await p.onProductSelect({detail:{id:2}})
    assert.equal(p.data.index, 1); assert.equal(p.data.showProductPicker, false)
    assert.equal(quotes.at(-1).product, 2); assert.equal(p.data.stationName, '阳光水站')
    assert.equal(p.data.productOptions[0].spec, '大桶')
    assert(!p.data.quoteNotice.includes('收到押金后生效'))
    assert(p.data.quoteNotice.includes('首次领取对应桶无需还桶，已有欠桶优先补足'))
  })
  await test('原购买待核实时不能打开商品选择', async () => {
    const {p} = purchase(); await p.onLoad({stationId: 11})
    p.setData({originalActive:true}); p.syncRecovery = () => {}; p.onOpenProductPicker()
    assert.equal(p.data.showProductPicker, false)
  })
  await test('退还列表只显示有可退权益的真实桶资产并默认选中', async () => {
    const p = barrel(async () => ok({stations:[{id:11},{id:12}],hasMore:false}))
    p.onLoad({}); await p.onShow(); await Promise.resolve()
    assert.deepStrictEqual(p.data.returnProductOptions.map(x=>x.id), [2]); assert.equal(p.data.hasOtherAssetStation,true)
    p.onShowReturnModal(); assert.equal(p.data['returnForm.productId'],2)
  })
  await test('单站或查询失败都不展示假定的其他站', async () => {
    for (const list of [async () => ok({stations:[{id:11}],hasMore:false}), async () => {throw Error('离线')}]) {
      const p = barrel(list); p.onLoad({}); await p.onShow(); await Promise.resolve(); assert.equal(p.data.hasOtherAssetStation,false)
    }
  })
  console.log('AQUAFLOW_SUITE_OK ' + count); done()
})().catch(e => {done(); console.error(e.stack); process.exitCode=1})
