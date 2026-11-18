'use strict'
const assert = require('node:assert/strict')
const { WIDTH, HEIGHT, posterData, priceText, drawPoster } = require('../../miniapp-delivery/components/ProductPromotionPoster/art')
let definition
global.Component = value => { definition = value }
require('../../miniapp-delivery/components/ProductPromotionPoster')
const flush = () => new Promise(resolve => setImmediate(resolve))
const product = { name: '普利思 天然泉水', brand: '普利思', category: 1, spec: '16.8L', effectivePrice: 14.5,
  regularPrice: 18, promotionActive: true, imageUrl: '/assets/product/barrel-neutral-template.webp' }

function fixture() {
  const pending = [], drawn = [], exported = [], previews = []
  const ctx = { measureText: s => ({ width: Array.from(s).length * 30 }), fillText: t => drawn.push(t) }
  for (const method of ['save','restore','rect','clip','beginPath','fillRect','moveTo','lineTo','quadraticCurveTo','bezierCurveTo','arc','stroke','drawImage']) ctx[method] = () => {}
  const canvas = { getContext: () => ctx, createImage() { const img = {width:512,height:512}; pending.push(img); return img } }
  const page = { ...definition.methods, data: { ...definition.data }, properties:{ product, stationName:'测试站', saved:true },
    createSelectorQuery: () => ({ select: () => ({ fields: () => ({ exec: callback => callback([{node:canvas}]) }) }) }),
    setData(patch) { Object.assign(this.data, patch) } }
  global.wx = { canvasToTempFilePath: opt => exported.push(opt), previewImage: opt => previews.push(opt) }
  definition.lifetimes.ready.call(page)
  return {page, pending, drawn, exported, previews, canvas, ctx}
}
async function run() {
  const a = fixture()
  assert.equal(priceText('0'), ''); assert.equal(priceText('16.801'), ''); assert.equal(priceText('16.80'), '16.8')
  assert.equal(posterData({ ...product, category:2, spec:'550mL×24' },'').unit,'箱')
  assert.equal(posterData({ ...product, effectivePrice:null },'').price,'')
  a.page.exportPoster(); assert.equal(a.exported.length,0)
  a.pending[0].onload(); await flush()
  a.page.exportPoster(); a.page.exportPoster()
  assert.equal(a.exported.length,1); assert.equal(a.exported[0].destWidth,WIDTH); assert.equal(a.exported[0].destHeight,HEIGHT)
  assert(a.drawn.includes('14.5')); assert(!a.drawn.includes('18'))
  a.exported[0].success({tempFilePath:'wxfile://test.png'})
  assert.deepEqual(a.previews[0].urls,['wxfile://test.png'])
  console.log('PASS saved price and photo drive the exported PNG')

  a.page.properties.product = { ...product, effectivePrice:12 }; a.page.refreshPoster()
  a.page.properties.product = { ...product, effectivePrice:18, promotionActive:false }; a.page.refreshPoster()
  a.pending[2].onload(); await flush()
  const count = a.drawn.length
  a.pending[1].onload(); await flush(); assert.equal(a.drawn.length,count)
  assert(a.drawn.includes('本站零售价')); assert(a.drawn.includes('18'))
  a.page.exportPoster(); definition.lifetimes.detached.call(a.page)
  a.exported[1].success({tempFilePath:'wxfile://stale.png'})
  assert.equal(a.previews.length,1)
  console.log('PASS changed price, ended promotion and late image/export callbacks')

  const b = fixture(); b.pending[0].onerror(); await flush()
  assert.equal(b.page.data.canRetry,true); b.page.exportPoster(); assert.equal(b.exported.length,0)
  b.page.properties.saved = false; b.page.refreshPoster(); b.pending[1].onload(); await flush()
  b.page.exportPoster(); assert.equal(b.exported.length,0)
  const unknown = posterData({...product,effectivePrice:null},'')
  drawPoster(b.ctx,unknown,null); assert(b.drawn.includes('售价待填写'))
  console.log('PASS failed photos, drafts and missing prices')
  console.log('AQUAFLOW_SUITE_OK 3')
}
run().catch(error => { console.error(error); process.exitCode = 1 })
