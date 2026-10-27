'use strict'
// Execute production handlers and project the native single-container contract. This is
// a regression guard for template/host wiring, not a WeChat renderer or hardware test.
const assert = require('node:assert/strict'), fs = require('node:fs'), path = require('node:path'), vm = require('node:vm')
const { fixture } = require('./extreme-product-save.test')
const { ROOT, armWatchdog } = require('./harness')
const { parseWxml, renderElements } = require('./wxml-tree')
const repo = process.env.AQUAFLOW_REPO || ROOT
const source = fs.readFileSync(path.join(repo, 'miniapp-delivery/pages/station-mgmt/products/index.wxml'), 'utf8')
const tree = parseWxml(source), tests = [], test = (name, run) => tests.push({ name, run })
function find(node, predicate, ancestors = [], result = []) {
  if (predicate(node)) result.push({ node, ancestors })
  for (const child of node.children || []) find(child, predicate, [...ancestors, node], result)
  return result
}
const hosts = find(tree, n => n.tag === 'page-container')
const meta = find(tree, n => n.tag === 'page-meta')[0].node
const bind = (value, data) => vm.runInNewContext(value.replace(/^{{|}}$/g, ''), { ...data })
const row = (extra = {}) => ({ id: 17, ownerStationId: 11, status: 1, selected: true, name: '合成库存商品', displayName: '合成库存商品',
  category: 1, quantity: 10, price: 12, deposit: 50, salePrice: 12, enabled: 1, ticketEnabled: 0, ...extra })
const event = item => ({ currentTarget: { dataset: { item, id: item.id } } })
function setup(extra) {
  const t = fixture(), item = row(extra); let hidden = 0
  t.wx.getWindowInfo = () => ({ windowHeight: 568 })
  t.wx.hideKeyboard = () => hidden++
  t.page.setData({ list: [item], viewList: [item], loading: false })
  return { ...t, item, hidden: () => hidden }
}
// WeChat only honors the first native container; the old generic WXML projection
// walked the hidden second host's children and incorrectly claimed inventory was visible.
const nativeView = t => bind(hosts[0].node.attrs.show, t.page.data)
  ? renderElements(hosts[0].node, t.page.data, { includeText: true }) : []
const locked = t => bind(meta.attrs['page-style'], t.page.data).includes('hidden')
const open = t => t.page.onOpenInventory(event(t.item))

test('inventory card tap opens visible content in the single native host, with unobstructed scroll body and footer', () => {
  assert.equal(hosts.length, 1, 'a second native page-container silently fails to open')
  const t = setup(), button = renderElements(tree, t.page.data, { includeText: true }).find(n => n.text === '库存操作')
  assert.equal(button.attrs.catchtap, 'onOpenInventory')
  t.page[button.attrs.catchtap](event(t.item))
  assert(locked(t)); const visible = nativeView(t)
  assert(visible.some(n => n.text === '商品库存')); assert(visible.some(n => n.attrs.bindtap === 'onSubmitStock'))
  const body = find(hosts[0].node, n => (n.attrs?.class || '').includes('inventory-body'))[0]
  assert.equal(body.node.tag, 'scroll-view'); assert('scroll-y' in body.node.attrs)
  assert(!body.ancestors.some(n => n.attrs && 'catchtouchmove' in n.attrs))
  assert(!find(body.node, n => (n.attrs?.class || '').includes('product-edit-footer')).length)
  assert.equal(t.calls.length, 0)
})

test('daily stock actions stay primary and shortage/report loss stays a separate reviewable exception', () => {
  const t = setup(); open(t)
  const primary = find(hosts[0].node, n => n.attrs?.class === 'inventory-primary-nav')[0]
  const primaryButtons = renderElements(primary.node, t.page.data, { includeText: true }).filter(n => n.tag === 'button')
  assert.deepEqual(primaryButtons.map(n => n.text), ['补货入库', '盘点校正'])
  t.page.onStockInput({ detail: { value: '4' } })
  const visible = nativeView(t)
  const operationIndex = visible.findIndex(n => n.attrs.class === 'inventory-operation-card')
  const lossIndex = visible.findIndex(n => n.attrs.class === 'inventory-loss-entry')
  assert(operationIndex >= 0 && lossIndex > operationIndex, 'normal stock action must precede secondary exception area')
  const changePreview = find(hosts[0].node, n => n.attrs?.class === 'inventory-change-preview')[0]
  assert(changePreview && changePreview.ancestors.some(a => a.attrs?.class === 'inventory-summary'))
  assert(visible.some(n => n.attrs.class === 'inventory-change-preview'))
  assert(visible.some(n => n.text === '本次预计变化 +4 件'))
  assert(visible.some(n => n.text === '调整后库存 14 件'))
  assert(!visible.some(n => n.attrs.class === 'stock-preview' && n.text.includes('本次变化')))
  const lossTrigger = find(hosts[0].node, n => n.attrs?.class === 'inventory-loss-trigger')[0]
  assert(lossTrigger.ancestors.some(n => n.attrs?.class === 'inventory-loss-entry'))
  assert(!lossTrigger.ancestors.some(n => n.attrs?.class === 'inventory-primary-nav'))
  assert(visible.some(n => n.text.includes('异常处理')))

  t.page.onStockModeChange({ currentTarget: { dataset: { mode: 'loss' } } })
  const lossContext = find(hosts[0].node, n => n.attrs?.class === 'inventory-loss-context')[0]
  assert(lossContext.ancestors.some(n => n.tag === 'scroll-view'))
  const exceptionView = nativeView(t)
  assert(exceptionView.some(n => n.text.includes('按订单需要顺序减少预留')))
  assert(exceptionView.some(n => n.text.includes('补货、协商延期或取消退款')))
  assert(exceptionView.some(n => n.text === '核对影响并登记'))
  assert(exceptionView.some(n => n.attrs.class === 'inventory-return-trigger'))
  assert(!exceptionView.some(n => n.attrs?.class === 'inventory-primary-nav'))
  assert(!exceptionView.some(n => n.attrs?.class === 'inventory-loss-entry'))
})

test('deposit reference uses only a real positive catalog value and reserves the matching helper line when absent', () => {
  const t = setup({ ownerStationId: null, deposit: 50, refDepositText: '50.00' })
  t.page.onOpenSetting(event(t.item))
  assert.equal(t.page.data.setting.hasRefDeposit, true)
  assert(nativeView(t).some(n => n.text === '参考押金 ¥50.00'))
  const noReference = row({ ownerStationId: null, deposit: 0, refDepositText: '0.00' })
  t.page.onOpenSetting(event(noReference))
  assert.equal(t.page.data.setting.hasRefDeposit, false)
  const visible = nativeView(t)
  assert(!visible.some(n => (n.text || '').includes('参考押金')))
  assert(visible.some(n => n.attrs.class === 'product-field-help product-field-help-slot'))
})

test('ticket setup task filters enabled ticket products and scrolls to the editable catalog', () => {
  const t = setup({ ticketEnabled: 1 }), scrolls = []
  t.wx.pageScrollTo = options => scrolls.push(options)
  t.page.setData({ moduleSetupTasks: [{ key: 'ticketPackage', done: false }] })
  t.page.onModuleSetupTask({ currentTarget: { dataset: { key: 'ticketPackage' } } })
  assert.equal(t.page.data.tab, 'selected')
  assert.equal(t.page.data.quick, 'ticket')
  assert.deepEqual(t.page.data.viewList.map(item => item.id), [t.item.id])
  assert.deepEqual(scrolls, [{ selector: '#setup-products', duration: 180 }])
})

test('product detail preparation separates optional tickets and collapses after required items are ready', async () => {
  const t = fixture(undefined, { freshSetupHelpers: true })
  t.state.setupGuide = { code: 0, data: { pendingCount: 1, p0PendingCount: 0, doneCount: 1, totalCount: 2, items: [
    { key: 'catalogOnShelf', route: '/pages/station-mgmt/products/index', level: 'P0', levelText: '必填', label: '上架商品', done: true, why: '已保存一个在售商品' },
    { key: 'ticketPackage', route: '/pages/station-mgmt/ticket-packages/index', level: 'P1', levelText: '选填', label: '水票档位', done: false, why: '未启用水票' }
  ] } }
  await t.page.refreshModuleSetup()
  assert.equal(t.page.data.productSetupRequiredPending, 0, 'optional water tickets do not count as required pending work')
  assert.equal(t.page.data.productSetupGuideExpanded, false, 'the required-ready guide starts collapsed')
  const guide = find(tree, node => node.attrs?.class === 'product-setup-guide')[0].node
  let view = renderElements(guide, t.page.data, { includeText: true })
  assert.equal(view.find(node => node.attrs?.class === 'product-setup-guide-toggle').text, '展开准备事项')
  t.page.onToggleProductSetupGuide()
  assert.equal(t.page.data.productSetupGuideExpanded, true)
  view = renderElements(guide, t.page.data, { includeText: true })
  const optionalTemplate = find(guide, node => node.attrs?.class === 'product-setup-task product-setup-optional-row')[0].node
  const optionalView = renderElements(optionalTemplate, t.page.data, { includeText: true })
  assert(view.some(node => node.attrs?.class === 'product-setup-task product-setup-optional-row'))
  assert(optionalView.some(node => node.text === '选填'))
  assert(!optionalView.some(node => node.text === '已完成' || node.text === '待完善'), 'optional water tickets have no completion state')
  assert(t.page.data.productSetupRequiredTasks[0].completionEvidence)
})

test('repeated inventory taps preserve the typed quantity, host and operation generation without writes', () => {
  const t = setup(); open(t); t.page.onStockInput({ detail: { value: '4' } })
  const epoch = t.page._productSettingEpoch
  for (let i = 0; i < 20; i++) open(t)
  assert.equal(t.page.data.stockInput, '4'); assert.equal(t.page._productSettingEpoch, epoch)
  assert(nativeView(t).some(n => n.text === '商品库存')); assert.equal(t.calls.length, 0)
})

for (const action of ['closeInventory', 'closeProductPanel', 'onProductPanelBeforeLeave']) test(action + ' closes inventory, releases page scroll lock and hides keyboard', () => {
  const t = setup(); open(t); t.page.onProductKeyboardHeightChange({ detail: { height: 260 } })
  t.page[action]()
  assert(!locked(t)); assert.equal(nativeView(t).length, 0); assert.equal(t.page.data.showInventory, false)
  assert.equal(t.page._productKeyboardHeight, 0); assert.equal(t.hidden(), 1); assert.equal(t.wx.__calls.nav.length, 0)
})

test('canceling stock confirmation writes nothing and closing afterwards restores page scrolling', () => {
  const t = setup(); open(t); let modal; t.wx.showModal = o => { modal = o }
  t.page.onStockModeChange({ currentTarget: { dataset: { mode: 'check' } } })
  t.page.onStockInput({ detail: { value: '8' } }); t.page.onSubmitStock()
  modal.success({ confirm: false }); assert.equal(t.page.data.stockConfirmPending, false); assert.equal(t.calls.length, 0)
  assert(nativeView(t).some(n => n.text === '商品库存'))
  t.page.closeProductPanel(); assert(!locked(t))
})

test('inventory to product editor switching shares one host and restores the unsubmitted stock draft', () => {
  const t = setup(); open(t); t.page.onStockInput({ detail: { value: '4' } })
  t.page.onOpenSetting(event(t.item))
  assert.equal(t.page.data.showInventory, false); assert.equal(t.page.data.showEdit, true)
  assert(!nativeView(t).some(n => n.text === '商品库存'))
  t.page[hosts[0].node.attrs.bindbeforeleave](); assert(!locked(t))
  open(t); assert.equal(t.page.data.stockInput, '4'); assert(nativeView(t).some(n => n.text === '商品库存'))
  t.page[hosts[0].node.attrs.bindclickoverlay](); assert(!locked(t))
})

test('failed inbound releases write lock and leaves a visible error and usable close control', async () => {
  const t = setup(); open(t)
  t.state.inbound = async () => { throw Object.assign(Error('合成入库失败'), { businessRejected: true }) }
  t.page.onStockInput({ detail: { value: '2' } }); await t.page.onSubmitStock()
  assert.equal(t.page.data.inboundBusy, false); assert.equal(t.page.data.showInventory, true)
  assert(t.page.data.inboundError); assert(nativeView(t).some(n => n.attrs.bindtap === 'closeInventory'))
  t.page.closeProductPanel(); assert(!locked(t))
})

test('unknown stock is visibly unread and cannot enable quantity input or a stock submission', () => {
  const t = setup({ quantity: null }); open(t)
  const visible = nativeView(t), input = visible.find(n => n.tag === 'input' && n.attrs.bindinput === 'onStockInput')
  assert(bind(input.attrs.disabled, t.page.data))
  const submit = visible.find(n => n.attrs.bindtap === 'onSubmitStock'); assert(bind(submit.attrs.disabled, t.page.data))
  assert(visible.some(n => n.text.includes('尚未读取'))); t.page.closeProductPanel(); assert(!locked(t))
})

test('inventory blur without height-zero callback and a later edit reopen recover full sheet geometry', async () => {
  const t = setup(); open(t); t.page.onProductKeyboardHeightChange({ detail: { height: 260 } })
  assert(t.page.data.productEditSheetStyle.includes('bottom:260px'))
  t.page.onProductKeyboardBlur(); await new Promise(resolve => setTimeout(resolve, 220))
  assert(t.page.data.productEditSheetStyle.includes('bottom:0px'))
  assert(t.page.data.productEditSheetStyle.includes('height:512px'))
  t.page.closeProductPanel(); t.page.onOpenSetting(event(t.item))
  assert(t.page.data.productEditSheetStyle.includes('bottom:0px'))
  t.page.closeProductPanel(); assert(!locked(t))
})

test('inventory history switches within one native host and Back releases its page lock', () => {
  const t = setup(); open(t); t.page.loadRecords = () => {}
  t.page.onFilterRecordsByCurrent()
  assert.equal(t.page.data.showRecords, true); assert(nativeView(t).some(n => n.text === '库存流水')); assert(locked(t))
  assert.equal(t.page.data.recordsProductId, t.item.id)
  t.page[hosts[0].node.attrs.bindbeforeleave](); assert.equal(t.page.data.showRecords,false); assert(!locked(t))
  assert.equal(t.wx.__calls.nav.length,0)
})

test('inventory to records handoff never closes the native host between tasks', async () => {
  const t = setup(); open(t); t.page.onStockInput({ detail: { value: '4' } })
  const frames = [], set = t.page.setData.bind(t.page)
  t.page.setData = (patch, callback) => { set(patch, callback); frames.push(nativeView(t).length > 0) }
  t.page.onFilterRecordsByCurrent()
  assert(frames.every(Boolean), 'an intermediate hidden host emits a late native beforeleave')
  assert.equal(t.page.data.showInventory, false); assert.equal(t.page.data.showRecords, true)
  t.page.closeRecords(); assert(!locked(t)); open(t); assert.equal(t.page.data.stockInput, '4')
})

async function main() {
  const done = armWatchdog(30000); let failed = 0
  try {
    for (const t of tests) { try { await t.run(); console.log('PASS ' + t.name) } catch (e) { failed++; console.error('FAIL ' + t.name + ': ' + e.stack) } }
    if (failed) process.exitCode = 1; else console.log('AQUAFLOW_SUITE_OK ' + tests.length)
  } finally { done() }
}
if (require.main === module) main().catch(e => { console.error(e); process.exitCode = 1 })
