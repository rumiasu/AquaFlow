'use strict'
// Real product handlers + WXML projection; synthetic API/host callbacks, no live writes or native rendering.
const assert = require('node:assert/strict'), fs = require('node:fs'), path = require('node:path')
const { fixture } = require('./extreme-product-save.test')
const { ROOT, armWatchdog } = require('./harness')
const { parseWxml, renderElements, validateConditionalSiblings } = require('./wxml-tree')
const repo = process.env.AQUAFLOW_REPO || ROOT
const tree = parseWxml(fs.readFileSync(path.join(repo, 'miniapp-delivery/pages/station-mgmt/products/index.wxml'), 'utf8'))
const tests = [], test = (name, run) => tests.push({ name, run })
const item = (extra = {}) => ({ id: 17, selected: true, ownerStationId: 11, name: '合成商品', displayName: '合成商品', category: 1,
  brand: '合成品牌', spec: '18.9L', categoryLabel: '桶装水', price: 12, salePrice: 15, deposit: 40, depositPrice: 50,
  quantity: 10, enabled: 1, priorityDisplay: 1, ticketEnabled: 0, refPriceText: '12.00', refDepositText: '40.00', ...extra })
const event = row => ({ currentTarget: { dataset: { item: row, id: row.id } } })
const visible = t => renderElements(tree, t.page.data, { includeText: true })
const openOwned = t => { t.page.onOpenSetting(event(item())); t.page.setData({ pkgReady: true }) }
const deferred = () => { let resolve; return { promise: new Promise(r => { resolve = r }), resolve } }
function sheetGeometry(page) { const s = page.data.productEditSheetStyle; return { height: Number(/height:(\d+)px/.exec(s)[1]), bottom: Number(/bottom:(\d+)px/.exec(s)[1]) } }

test('selected product cards expose identical edit and more actions for both sources', () => {
  validateConditionalSiblings(tree)
  const t = fixture(), rows = [item(), item({ id: 18, ownerStationId: null })]
  t.page.setData({ loading: false, viewList: rows, list: rows })
  const controls = visible(t)
  assert.equal(controls.filter(n => n.tag === 'button' && n.text === '编辑商品').length, 2)
  assert.equal(controls.filter(n => n.attrs.catchtap === 'onProductMore').length, 2)
  assert(!controls.some(n => ['openEdit', 'onDeactivate', 'onSubmitToPlatform'].includes(n.attrs.catchtap)))
  assert(!controls.some(n => n.text === '设置'))
})
test('owned catalog entry opens one complete editor with effective price and display state', () => {
  const t = fixture(); openOwned(t)
  assert.equal(t.page.data.showEdit, true); assert.equal(t.page.data.showSetting, false)
  assert.equal(t.page.data.editId, 17); assert.equal(t.page.data.setting.id, 17)
  assert.equal(t.page.data.editForm.price, '15'); assert.equal(t.page.data.editForm.deposit, '50')
  assert.equal(t.page.data.editForm.priorityDisplay, true)
  const controls = visible(t)
  assert(controls.some(n => n.attrs.bindtap === 'onSubmitStock'))
  assert(!controls.some(n => n.attrs['data-field'] === 'quantity'), 'existing inventory is managed with explicit stock commands')
})
test('one owned PUT saves visible prices and priority without overwriting live inventory', async () => {
  const t = fixture(); openOwned(t)
  t.page.onEditFieldInput({ currentTarget: { dataset: { field: 'name' } }, detail: { value: '新名称' } })
  await t.page.onSaveEdit()
  assert.equal(t.calls.length, 1); const [kind, id, body] = t.calls[0]
  assert.equal(kind, 'edit'); assert.equal(id, 17); assert.equal(body.name, '新名称')
  assert.equal(body.price, 15); assert.equal(body.deposit, 50)
  assert.equal(body.salePrice, 0); assert.equal(body.depositPrice, 0); assert.equal(body.priorityDisplay, 1)
  assert(!Object.hasOwn(body, 'quantity')); assert.equal(body.enabled, 1)
})
test('platform products use the shared image and edit layout while saving only station configuration', async () => {
  const t = fixture(); t.page.onOpenSetting(event(item({ ownerStationId: null, stationImageEditable: true })))
  t.page.setData({ pkgReady: true })
  assert.equal(t.page.data.showSetting, true); assert.equal(t.page.data.showEdit, true)
  const controls = visible(t)
  assert(controls.some(n => n.attrs.class === 'modal-img product-edit-image'))
  assert(controls.some(n => (n.text || '').includes('图片可更换为本站图片')))
  for (const field of ['name','brand','spec','description']) assert(controls.some(n => n.attrs['data-field'] === field && n.attrs.disabled))
  assert.equal(controls.find(n => n.attrs.bindtap === 'uploadImage').attrs.disabled, '{{productInactive || saving || (showSetting && !stationImageEditable)}}'); assert.equal(t.page.data.saving, false)
  assert(!controls.some(n => n.attrs.bindtap === 'onRemoveProduct'))
  t.page.onEditFieldInput({ currentTarget: { dataset: { field: 'name' } }, detail: { value: 'must not change public template' } })
  assert.equal(t.page.data.editForm.name, '合成商品')
  t.page.onEditFieldInput({ currentTarget: { dataset: { field: 'price' } }, detail: { value: '16' } })
  await t.page.onSaveEdit()
  assert.equal(t.calls.length, 1); assert.equal(t.calls[0][0], 'setting'); assert.equal(t.calls[0][2].salePrice, 16)
  assert(!['name', 'brand', 'spec', 'imageObjectName', 'price', 'quantity'].some(k => Object.hasOwn(t.calls[0][2], k)))
})
test('late platform form mutation cannot attach packages or close the changed editor', async () => {
  const t = fixture(), gate = deferred(); t.page.onOpenSetting(event(item({ ownerStationId: null, ticketEnabled: 1 })))
  t.page.setData({ pkgReady: true }); t.state.setting = () => gate.promise
  const save = t.page.onSaveEdit(); t.page.setData({ 'editForm.price': '99' }); gate.resolve({ code: 0 }); await save
  assert.equal(t.packages.length, 0); assert.equal(t.page.data.showEdit, true)
})

test('a different station owner cannot enter the local editor', () => {
  const t = fixture(); t.page.onOpenSetting(event(item({ ownerStationId: 22 })))
  assert.equal(t.page.data.showEdit, false); assert.equal(t.page.data.showSetting, false); assert.equal(t.calls.length, 0)
})
for (const kind of ['platform', 'owned', 'resume']) test('reopening ' + kind + ' clears stale keyboard offset and reaches viewport bottom', () => {
  const t = fixture(); t.wx.getWindowInfo = () => ({ windowHeight: 568 }); t.openAdd()
  t.page.onProductKeyboardHeightChange({ detail: { height: 260 } }); t.page.closeEdit()
  // Some hosts omit the final height=0 callback when its focused input is removed.
  if (kind === 'resume') t.page.onResumeProductEditor()
  else t.page.onOpenSetting(event(item({ ownerStationId: kind === 'platform' ? null : 11 })))
  const g = sheetGeometry(t.page); assert.equal(g.bottom, 0); assert.equal(g.height + g.bottom, 568 - 56)
})
test('physical shortage uses remaining quantity and keeps order-impact confirmation', () => {
  const t = fixture(); openOwned(t); let modal; t.wx.showModal = o => { modal = o }
  t.page.onStockModeChange({ currentTarget: { dataset: { mode: 'loss' } } })
  t.page.onStockInput({ detail: { value: '8' } }); t.page.onSubmitStock()
  assert.equal(modal.title, '登记商品短缺'); assert.equal(modal.confirmText, '登记短缺')
  assert(modal.content.includes('10 件改为 8 件')); assert(modal.content.includes('缺货待补'))
  assert.equal(modal.editable, true); assert.equal(t.calls.length, 0)
  assert.equal(t.page.data.stockAfterText, '核对后 8 件（少 2 件）')
  assert(!visible(t).some(n => /实盘亏损|＝ 盘点/.test(n.text || '')))
})
test('both sources have one removal entry under more and stale account callbacks do nothing', () => {
  const t = fixture(); let menu, owned = 0, selected = 0
  t.wx.showActionSheet = o => { menu = o }; t.page.onDeactivate = () => owned++; t.page.onRemoveProduct = () => selected++
  t.page.onProductMore(event(item())); assert.deepEqual(Array.from(menu.itemList), ['移除商品']); assert.equal(menu.itemColor, '#BD3328')
  menu.success({ tapIndex: 0 }); assert.equal(owned, 1)
  t.page.onProductMore(event(item({ ownerStationId: null }))); menu.success({ tapIndex: 0 }); assert.equal(selected, 1)
  t.page.onProductMore(event(item())); t.app.globalData.userInfo.staffId = 2; menu.success({ tapIndex: 0 })
  assert.equal(owned, 1)
})

test('closing a platform editor releases the page scroll lock and restores its save mode on resume', () => {
  const t = fixture(); t.page.onOpenSetting(event(item({ ownerStationId: null }))); t.page.closeEdit()
  assert.equal(t.page.data.showEdit, false); assert.equal(t.page.data.showSetting, false)
  t.page.onResumeProductEditor(); assert.equal(t.page.data.showEdit, true); assert.equal(t.page.data.showSetting, true)
})

test('opening new product after a platform editor resets the shared save mode', async () => {
  const t = fixture(); t.page.onOpenSetting(event(item({ ownerStationId: null }))); t.page.closeEdit(); t.openAdd()
  assert.equal(t.page.data.showSetting, false)
  await t.page.onSaveEdit(); assert.equal(t.calls[0][0], 'create')
})

test('shared editor resumes the most recently closed product with its own stock and mode', () => {
  const t = fixture(); openOwned(t); t.page.onStockInput({ detail: { value: '3' } })
  t.page.closeEdit(); t.page.onOpenSetting(event(item({ id: 18, ownerStationId: null, quantity: 99 }))); t.page.closeSetting()
  t.page.onResumeProductEditor()
  assert.equal(t.page.data.editId, 18); assert.equal(t.page.data.setting.id, 18)
  assert.equal(t.page.data.setting.quantity, 99); assert.equal(t.page.data.stockInput, ''); assert.equal(t.page.data.showSetting, true)
})
test('late inbound receipt after native Back updates the resumed draft and price save never rewrites that quantity', async () => {
  const t = fixture(), gate = deferred(); openOwned(t)
  t.state.inbound = async (station, items, key) => { await gate.promise; t.state.stock = 15; return { code: 0, data: { status: 'APPLIED', stationId: station, items, clientRequestId: key } } }
  const pending = t.page.doInbound(5); t.page.onProductEditorBeforeLeave()
  assert.equal(t.page.data.showEdit, false); assert.equal(t.page.data.inboundBusy, true)
  gate.resolve(); await pending; t.page.onResumeProductEditor()
  assert.equal(t.page.data.setting.quantity, 15); assert.equal(t.page.data.editForm.price, '15')
  await t.page.onSaveEdit(); const saved = t.calls.find(c => c[0] === 'edit')
  assert(saved); assert(!Object.hasOwn(saved[2], 'quantity')); assert.equal(t.state.stock, 15)
})


function imageEditor(t) { t.page.onOpenSetting(event(item({ownerStationId:null,stationImageEditable:true,stationImageObjectName:'public/common/old.jpg',imageObjectName:'public/common/old.jpg',imageUrl:'https://example.com/old.jpg',platformImageObjectName:'catalog-sample-original.png',platformImageUrl:'https://example.com/base.png'})));t.page.setData({pkgReady:true}) }
test('platform image saves a durable station key and requires a stored receipt',async()=>{const t=fixture();imageEditor(t);t.page.setData({'editForm.imageObjectName':'public/common/new.jpg'});t.state.setting=async(id,body)=>({code:0,data:{stationImageSaved:true,stationImageRef:body.imageObjectName}});await t.page.onSaveEdit();assert.equal(t.calls[0][0],'setting');assert.equal(t.calls[0][2].imageObjectName,'public/common/new.jpg');assert(!Object.hasOwn(t.calls[0][2],'name'));assert.equal(t.page.data.showEdit,false)})
test('old backend or mismatching receipt leaves image result visibly unconfirmed',async()=>{for(const response of [{code:0},{code:0,data:{stationImageSaved:true,stationImageRef:'public/common/other.jpg'}}]){const t=fixture();imageEditor(t);t.page.setData({'editForm.imageObjectName':'public/common/new.jpg'});t.state.setting=async()=>response;await t.page.onSaveEdit();assert.equal(t.page.data.showEdit,true);assert(t.page.data.productSaveError.includes('图片保存尚未确认'))}})
test('restore sends explicit empty override and does not alter public metadata',async()=>{const t=fixture();imageEditor(t);t.page.onRestorePlatformImage();assert.equal(t.page.data.editForm.imageUrl,'https://example.com/base.png');t.state.setting=async(id,body)=>({code:0,data:{stationImageSaved:true,stationImageRef:''}});await t.page.onSaveEdit();assert.equal(t.calls[0][2].imageObjectName,'');assert.equal(t.calls[0][0],'setting')})
test('an unready capability blocks changed images while unchanged settings remain editable',async()=>{const t=fixture();imageEditor(t);t.page.setData({stationImageEditable:false,'editForm.imageObjectName':'public/common/new.jpg'});await t.page.onSaveEdit();assert.equal(t.calls.length,0);t.page.setData({'editForm.imageObjectName':'public/common/old.jpg'});await t.page.onSaveEdit();assert.equal(t.calls.length,1);assert(!Object.hasOwn(t.calls[0][2],'imageObjectName'))})

;(async () => {
  const done = armWatchdog(30000), results = []
  try {
    for (const t of tests) { try { await t.run(); results.push({ name: t.name, pass: true }); console.log('PASS ' + t.name) }
      catch (e) { results.push({ name: t.name, pass: false, error: e.message }); console.log('FAIL ' + t.name + ': ' + e.message) } }
    if (process.env.REVIEW_OUTPUT) fs.writeFileSync(process.env.REVIEW_OUTPUT, JSON.stringify(results, null, 2))
    if (results.some(r => !r.pass)) process.exitCode = 1
    else console.log('AQUAFLOW_SUITE_OK ' + tests.length)
  } finally { done() }
})().catch(e => { console.error(e); process.exitCode = 1 })
