const assert = require('assert')
const fs = require('fs')
const path = require('path')
const { loadPage, createWx, createApp, armWatchdog } = require('./harness')
const done = armWatchdog()
let passed = 0
const failures = []
async function test(name, fn) {
  try { await fn(); passed++; console.log('PASS ' + name) }
  catch (err) { failures.push(name + ': ' + err.message); console.log('FAIL ' + name + ': ' + err.message) }
}
const read = p => fs.readFileSync(path.resolve(__dirname, '../..', p), 'utf8')
const { orderActions } = require('../../miniapp-delivery/utils/order-actions')
const { parseWxml, validateConditionalSiblings, renderElements } = require('./wxml-tree')

function partitionCards(source, activeTab, acceptTab, dispatchTab, lists) {
  const elements = renderElements(parseWxml(source), { isManager: true, activeTab, acceptTab, dispatchTab, lists, tabs: [], dispatchCount: { pool: 1, directed: 1 } })
  return {
    ids: elements.filter(e => e.className.split(/\s+/).includes('order-card')).map(e => e.id),
    actions: elements.filter(e => e.tag === 'button' && e.id !== undefined).map(e => [e.attrs.catchtap, e.id])
  }
}

// Resolve the actual class cascade, including app/page source order. This caught the
// local .container override that a presence-only custom-tabbar-page check missed.
function paddingFor(classes, sources) {
  let winner = null
  for (const source of sources) {
    const css = source.replace(/\/\*[\s\S]*?\*\//g, '').replace(/@import\s+[^;]+;/g, '')
    for (const rule of css.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
      for (const selector of rule[1].split(',')) {
        const s = selector.trim()
        if (!/^(\.[\w-]+)+$/.test(s)) continue
        const names = s.slice(1).split('.')
        if (!names.every(n => classes.includes(n))) continue
        for (const declaration of rule[2].matchAll(/padding-bottom\s*:\s*([^;]+);/g)) {
          if (!winner || names.length >= winner.weight) winner = { weight: names.length, value: declaration[1] }
        }
      }
    }
  }
  assert(winner, 'missing bottom padding')
  return winner.value
}

async function main() {
  await test('root overlays retain their host and stacking order', () => {
    const tree = parseWxml(read('miniapp-delivery/pages/coordination/index.wxml'))
    const overlays = []
    const visit = (node, ancestors) => {
      if ((node.attrs && node.attrs.class || '').split(/\s+/).includes('modal-overlay')) overlays.push({ node, ancestors })
      for (const child of node.children || []) visit(child, [...ancestors, node])
    }
    visit(tree, [])
    assert.strictEqual(overlays.length, 2)
    const overlayCss = /\.modal-overlay\s*\{([^}]*)\}/.exec(read('miniapp-delivery/pages/coordination/index.wxss'))[1]
    const tabCss = /\.tabbar\s*\{([^}]*)\}/.exec(read('miniapp-delivery/custom-tab-bar/index.wxss'))[1]
    const overlayZ = Number(/z-index:\s*(\d+)/.exec(overlayCss)[1])
    const tabZ = Number(/z-index:\s*(\d+)/.exec(tabCss)[1])
    // QA's actual 320px guide button rectangle; its center overlaps the tabbar.
    const center = { x: (13 + 307) / 2, y: (506.3374938964844 + 554.9999961853027) / 2 }
    assert(center.x > 0 && center.x < 320 && center.y > 568 - 100 * 320 / 750 && center.y < 568)
    for (const { ancestors } of overlays) {
      const portal = ancestors.find(n => n.tag === 'root-portal')
      assert(portal && portal.attrs.enable === '{{true}}', 'page-local z-index cannot cover the separate custom tabbar host')
      assert.strictEqual(tree.children.includes(portal), true, 'portal must be outside the page content container')
      assert(overlayZ > tabZ, 'root-level overlay must paint above the tabbar')
    }
  })
  await test('modal buttons fit the actual available viewport rather than full screen height', () => {
    const wx = createWx()
    wx.getWindowInfo = () => ({ screenHeight: 568, windowHeight: 504 })
    const page = loadPage('miniapp-delivery/pages/coordination/index.js', { wx, stubs: { 'api/delivery': {} } })
    page.onOpenGuide()
    assert.strictEqual(page.data.modalBottomInset, 64)
    const measuredOldCenter = (506.3374938964844 + 554.9999961853027) / 2
    assert(measuredOldCenter > 504, 'the real GUI failure must exceed available height')
    assert(measuredOldCenter - page.data.modalBottomInset < 504)
    const wxml = read('miniapp-delivery/pages/coordination/index.wxml')
    assert.strictEqual((wxml.match(/style="padding-bottom: \{\{modalBottomInset\}\}px;"/g) || []).length, 2)
    const css = read('miniapp-delivery/pages/coordination/index.wxss')
    assert(/\.modal-overlay\s*\{[^}]*box-sizing:\s*border-box;/s.test(css))
    assert(/\.modal-content\s*\{[^}]*max-height:\s*85%;/s.test(css), 'panel height must follow the padded available viewport')
    wx.getWindowInfo = () => ({ screenHeight: 844, windowHeight: 753 })
    page.onResize()
    assert.strictEqual(page.data.modalBottomInset, 91)
    wx.getWindowInfo = () => ({ screenHeight: 568, windowHeight: 600 })
    page.updateModalViewport()
    assert.strictEqual(page.data.modalBottomInset, 0)
  })
  await test('background tabbar stays blocked until every modal closes and restores on page hide', () => {
    const wx = createWx()
    let blocked = false
    const page = loadPage('miniapp-delivery/pages/coordination/index.js', { wx, stubs: { 'api/delivery': {} } })
    page.getTabBar = () => ({ setModalBlocked: value => { blocked = value } })
    page.onOpenGuide()
    assert.strictEqual(blocked, true)
    page.onShowAssign({ currentTarget: { dataset: { id: 7 } } })
    page.onCloseGuide()
    assert.strictEqual(blocked, true, 'closing guide must not expose tabs under an open assignment')
    page.onCloseModal()
    assert.strictEqual(blocked, false)
    page.onOpenGuide(); page.onHide()
    assert.strictEqual(blocked, false)
    page.syncModalTabBar()
    assert.strictEqual(blocked, true, 'returning to a still-open modal must block again')
    page.onUnload()
    assert.strictEqual(blocked, false)
  })
  await test('custom tabbar rejects stale taps while blocked and resumes normal navigation afterward', () => {
    let config
    const previous = global.Component
    global.Component = value => { config = value }
    const modulePath = require.resolve('../../miniapp-delivery/custom-tab-bar/index.js')
    try { delete require.cache[modulePath]; require(modulePath) }
    finally { global.Component = previous }
    const bar = { data: { ...config.data }, setData(patch) { Object.assign(this.data, patch) } }
    for (const [key, method] of Object.entries(config.methods)) bar[key] = method.bind(bar)
    let navigations = 0
    global.wx = createWx()
    global.wx.switchTab = () => { navigations++ }
    const event = { currentTarget: { dataset: { path: '/pages/mine/index' } } }
    bar.setModalBlocked(true); bar.onTap(event)
    assert.strictEqual(navigations, 0)
    assert(read('miniapp-delivery/custom-tab-bar/index.wxml').includes('hidden="{{modalBlocked}}"'))
    bar.setModalBlocked(false); bar.onTap(event)
    assert.strictEqual(navigations, 1)
  })
  await test('backdrop catches background gestures while native modal list and original buttons remain usable', () => {
    const tree = parseWxml(read('miniapp-delivery/pages/coordination/index.wxml'))
    const pageMeta = tree.children.find(n => n.tag === 'page-meta')
    assert(pageMeta, 'touchmove alone does not stop actual desktop wheel events')
    assert.strictEqual(pageMeta.attrs['page-style'], "{{showGuide || showAssignModal ? 'overflow: hidden;' : ''}}", 'lock only this page while either modal is open and restore afterward')
    const overlays = []
    const visit = node => {
      if ((node.attrs && node.attrs.class || '') === 'modal-overlay') overlays.push(node)
      for (const child of node.children || []) visit(child)
    }
    visit(tree)
    for (const [index, overlay] of overlays.entries()) {
      const backdrop = overlay.children.find(n => n.attrs && n.attrs.class === 'modal-backdrop')
      const content = overlay.children.find(n => n.attrs && (n.attrs.class || '').split(' ').includes('modal-content'))
      const close = index === 0 ? 'onCloseModal' : 'onCloseGuide'
      assert(backdrop, 'a separate full-viewport backdrop must stop background touch movement')
      assert.strictEqual(backdrop.attrs.catchtap, close)
      assert.strictEqual(backdrop.attrs.catchtouchmove, 'stopPropagation')
      assert.strictEqual(content.attrs.catchtap, 'stopPropagation')
      assert(!('catchtouchmove' in overlay.attrs) && !('catchtouchmove' in content.attrs), 'do not block native scrolling by catching its ancestor movement')
      const list = content.children.find(n => n.tag === 'scroll-view')
      assert(list && 'scroll-y' in list.attrs && !('catchtouchmove' in list.attrs))
      const button = content.children.find(n => n.tag === 'button')
      assert.strictEqual(button.attrs.class, 'modal-cancel')
      assert.strictEqual(button.attrs.bindtap, close)
      assert(!('wx:if' in button.attrs) && !('hidden' in button.attrs), 'keep the required close action visible')
    }
    const css = read('miniapp-delivery/pages/coordination/index.wxss')
    assert(/\.modal-content\s*\{[^}]*position:\s*relative;[^}]*z-index:\s*1;/s.test(css), 'panel must paint above its sibling backdrop')
  })
  await test('closing either overlay preserves task view and does not navigate or reset scrolling', () => {
    const wx = createWx(); const stored = {}; let navigations = 0; let scrollResets = 0
    wx.setStorageSync = (k, v) => { stored[k] = v }
    wx.switchTab = () => { navigations++ }; wx.navigateTo = () => { navigations++ }; wx.pageScrollTo = () => { scrollResets++ }
    const page = loadPage('miniapp-delivery/pages/coordination/index.js', { wx, stubs: { 'api/delivery': {} } })
    page.setData({ activeTab: 'pool', acceptTab: 'directed', dispatchTab: 'directed', showAssignModal: true, currentOrderId: 7 })
    const listsBefore = JSON.stringify(page.data.lists)
    page.onOpenGuide(); page.onCloseGuide(); page.onCloseModal()
    assert.strictEqual(stored['delivery-coordination-guide-v2'], true)
    assert(!page.data.showGuide && !page.data.showAssignModal)
    assert.strictEqual(page.data.currentOrderId, null)
    assert.strictEqual(page.data.activeTab, 'pool'); assert.strictEqual(page.data.acceptTab, 'directed'); assert.strictEqual(page.data.dispatchTab, 'directed')
    assert.strictEqual(JSON.stringify(page.data.lists), listsBefore)
    assert.strictEqual(navigations, 0); assert.strictEqual(scrollResets, 0)
  })
  await test('actual WXML conditional siblings remain one if/elif/else chain', () => {
    validateConditionalSiblings(parseWxml(read('miniapp-delivery/pages/coordination/index.wxml')))
    for (const invalid of [
      '<view><block wx:else /></view>',
      '<view><block wx:if="{{ok}}"/><block wx:else/><!-- comment --><block wx:else/></view>',
      '<view><block wx:if="{{ok}}"/><view/><block wx:elif="{{other}}"/></view>',
      '<view><block wx:if="{{ok}}"/></view><view wx:else/>'
    ]) assert.throws(() => validateConditionalSiblings(parseWxml(invalid)), /without preceding sibling/)
    validateConditionalSiblings(parseWxml('<view><block wx:if="{{count > 0}}"/><!-- comment --><block wx:elif="{{other}}"/><block wx:else/></view>'))
  })
  await test('actual WXML renders incoming/accepted and outbound cards in their own partitions', () => {
    const source = read('miniapp-delivery/pages/coordination/index.wxml')
    const lists = {
      pending: [], approvalCustomer: [], approvalStation: [],
      pool: [{ id: 101 }],
      directedIncoming: [{ id: 201, status: 1, _dir: 'in' }],
      directedAccepted: [{ id: 301, status: 2, _dir: 'in' }, { id: 302, status: 3, _dir: 'in' }],
      dispatchPool: [{ id: 401, status: 1, _inPool: true }],
      dispatchDirected: [{ id: 501, status: 1, _dir: 'out' }]
    }
    const publicPool = partitionCards(source, 'pool', 'pool', 'pool', lists)
    assert.deepStrictEqual(publicPool.ids, [101])
    assert.deepStrictEqual(publicPool.actions, [['onSkipPool', 101], ['onClaimPool', 101]])
    const incoming = partitionCards(source, 'pool', 'directed', 'pool', lists)
    assert.deepStrictEqual(incoming.ids, [201, 301, 302])
    assert.deepStrictEqual(incoming.actions, [['onShowAssign', 201], ['onMediateReturn', 201], ['onMediateReturn', 301]])
    assert.deepStrictEqual(partitionCards(source, 'dispatch', 'directed', 'pool', lists).ids, [401])
    const outbound = partitionCards(source, 'dispatch', 'directed', 'directed', lists)
    assert.deepStrictEqual(outbound.ids, [501])
    assert.deepStrictEqual(outbound.actions, [['onCancelDispatch', 501], ['onReDispatch', 501]])
    for (const activeTab of ['pending', 'approval']) assert.deepStrictEqual(partitionCards(source, activeTab, 'directed', 'directed', lists).ids, [])
    const wronglyMapped = source.replace('wx:for="{{lists.directedAccepted}}"', 'wx:for="{{lists.dispatchPool}}"')
    assert.notDeepStrictEqual(partitionCards(wronglyMapped, 'pool', 'directed', 'pool', lists).ids, incoming.ids, 'valid WXML with a wrong list must change the rendered projection')
    const empty = Object.fromEntries(Object.keys(lists).map(k => [k, []]))
    assert.deepStrictEqual(partitionCards(source, 'pool', 'directed', 'pool', empty).ids, [])
  })
  await test('long coordination page clears fixed tab bar at 320/375/430px and safe area', () => {
    const value = paddingFor(['container', 'custom-tabbar-page'], [read('miniapp-delivery/app.wxss'), read('miniapp-delivery/pages/coordination/index.wxss')])
    for (const width of [320, 375, 430]) for (const safe of [0, 34]) {
      const expr = value.replace(/calc\((.*)\)/, '$1').replace(/env\(safe-area-inset-bottom\)/g, String(safe)).replace(/([\d.]+)rpx/g, (_, x) => String(Number(x) * width / 750)).replace(/([\d.]+)px/g, '$1')
      assert(/^[\d. +()-]+$/.test(expr), 'unsupported padding expression ' + expr)
      const bottom = Function('return (' + expr + ')')()
      assert(bottom >= 100 * width / 750 + safe + 12, `width=${width} safe=${safe} padding=${bottom}`)
    }
  })
  await test('incoming directed tasks are separate from my dispatch and public pool', async () => {
    const wx = createWx()
    const app = createApp({ globalData: { userInfo: { role: 'STATION_MANAGER', stationId: 8, id: 20 } } })
    const ok = data => Promise.resolve({ code: 0, data })
    const page = loadPage('miniapp-delivery/pages/coordination/index.js', { wx, app, stubs: {
      'utils/request': { get: () => ok([]) },
      'api/delivery': {
        getStaffList: () => ok([]), getPoolOrders: () => ok([{ id: 1 }]),
        getDirectedIncoming: () => ok([{ id: 2, status: 1 }]),
        getDispatchTracking: () => ok([{ id: 3, dispatchKind: 'DIRECTED' }, { id: 4, dispatchKind: 'POOL' }]),
        getPendingApprovals: () => ok({ customer: [], station: [] })
      }
    } })
    await page.loadAllData()
    assert.deepStrictEqual(page.data.lists.dispatchDirected.map(o => o.id), [3])
    assert.deepStrictEqual(page.data.lists.directedIncoming.map(o => o.id), [2])
    assert.deepStrictEqual(page.data.lists.pool.map(o => o.id), [1])
    assert.strictEqual(page.data.tabs.find(t => t.key === 'pool').label, '接单')
    assert.strictEqual(page.data.tabs.find(t => t.key === 'pool').count, 2)
    assert.strictEqual(page.data.tabs.find(t => t.key === 'dispatch').count, 2)
  })
  await test('completed detail cannot navigate to complete even through a stale tap', () => {
    const wx = createWx(); let navigations = 0
    wx.navigateTo = () => { navigations++ }
    const page = loadPage('miniapp-delivery/pages/order/detail.js', { wx, stubs: { 'api/delivery': {} } })
    page.setData({ orderId: 1, order: { status: 4, deliveryStaffId: 20, deliveryStationId: 8 } })
    page.onComplete()
    assert.strictEqual(navigations, 0)
  })
  await test('detail fixed actions retain a safe-area gap on the smallest supported width', () => {
    const value = paddingFor(['container'], [read('miniapp-delivery/app.wxss'), read('miniapp-delivery/pages/order/detail.wxss')])
    for (const width of [320, 375]) for (const safe of [0, 34]) {
      const expr = value.replace(/calc\((.*)\)/, '$1').replace(/env\(safe-area-inset-bottom\)/g, String(safe)).replace(/([\d.]+)rpx/g, (_, x) => String(Number(x) * width / 750))
      assert(/^[\d. +()-]+$/.test(expr))
      assert(Function('return (' + expr + ')')() >= 180 * width / 750 + safe + 8)
    }
  })
  await test('active owner can complete; target can only accept; other station cannot act', () => {
    const order = { status: 2, stationId: 7, deliveryStationId: 8, deliveryStaffId: 20 }
    assert(orderActions(order, { role: 'DELIVERY', stationId: 8, staffId: 20 }).canComplete)
    assert(orderActions(order, { role: 'STATION_MANAGER', stationId: 8, staffId: 30 }).canComplete)
    assert(!orderActions(order, { role: 'STATION_MANAGER', stationId: 7, staffId: 30 }).canComplete)
    const target = orderActions({ ...order, transferTarget: true, transferPending: true, transferPendingSubKind: 'TRANSFER' }, { role: 'DELIVERY', stationId: 8, staffId: 21 })
    assert(target.canAcceptTransfer); assert(!target.canComplete); assert(!target.canTransfer)
    const initiator = orderActions({ ...order, transferPending: true, transferPendingSubKind: 'TRANSFER' }, { role: 'DELIVERY', stationId: 8, staffId: 20 })
    assert(initiator.canWithdrawTransfer)
    assert(!orderActions({ ...order, transferPendingSubKind: 'TRANSFER' }, { role: 'STATION_MANAGER', stationId: 8, staffId: 30 }).canWithdrawTransfer)
    for (const status of [1, 3, 4, 5]) assert(!orderActions({ ...order, status }, { role: 'DELIVERY', stationId: 8, staffId: 20 }).canComplete)
  })
  await test('guide can be skipped and replayed without blocking task actions', () => {
    const wx = createWx(); const values = {}
    wx.setStorageSync = (key, value) => { values[key] = value }
    const page = loadPage('miniapp-delivery/pages/coordination/index.js', { wx, stubs: { 'api/delivery': {} } })
    page.onOpenGuide(); assert(page.data.showGuide)
    page.onCloseGuide(); assert(!page.data.showGuide); assert(values['delivery-coordination-guide-v2'])
    page.onOpenGuide(); assert(page.data.showGuide)
  })
  await test('staff transfer is excluded from manager daily queues while return/cancel remain', async () => {
    const ok = data => Promise.resolve({ code: 0, data })
    const rows = [{ id: 1, transferPendingSubKind: 'TRANSFER' }, { id: 2, transferPendingSubKind: 'RETURN_STATION' }, { id: 3, transferPendingSubKind: 'CANCEL_REQUEST' }]
    const page = loadPage('miniapp-delivery/pages/coordination/index.js', { stubs: {
      'utils/request': { get: url => ok(String(url).endsWith('station-transfer') ? rows : []) },
      'api/delivery': { getPoolOrders: () => ok([]), getDirectedIncoming: () => ok([]), getDispatchTracking: () => ok([]), getPendingApprovals: () => ok({ station: rows, customer: [] }) }
    } })
    await page.loadAllData()
    assert(!page.data.lists.pending.some(o => o.id === 1))
    assert(!page.data.lists.pending.some(o => o.id === 3))
    assert.deepStrictEqual(page.data.lists.approvalStation.map(o => [o.id, o.approvalAction]), [[2, 'return'], [3, 'cancel']])
    assert.strictEqual(page.data.tabs.find(t => t.key === 'approval').count, 2)
  })
  console.log(failures.length ? `AQUAFLOW_SUITE_FAIL passed=${passed} failed=${failures.length}` : `AQUAFLOW_SUITE_OK ${passed}`)
  done(); if (failures.length) process.exitCode = 1
}
main().catch(err => { console.error(err); done(); process.exitCode = 1 })
