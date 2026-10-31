'use strict'
const assert = require('assert'), fs = require('fs'), path = require('path'), vm = require('vm')
const { loadPage, createWx, createApp, armWatchdog, ROOT } = require('./harness')
const { parseWxml } = require('./wxml-tree')
const done = armWatchdog(), tests = [], test = (name, run) => tests.push({ name, run })
const read = file => fs.readFileSync(path.join(ROOT, file), 'utf8')
const clone = value => JSON.parse(JSON.stringify(value))
const ok = data => ({ code: 0, data })
const templateTree = parseWxml(read('miniapp-user/pages/template/index.wxml'))
function find(node, predicate) {
  if (predicate(node)) return node
  for (const child of node.children || []) { const result = find(child, predicate); if (result) return result }
}
function binding(value, context) {
  const exact = /^{{([\s\S]*)}}$/.exec(value)
  const evaluate = text => vm.runInNewContext(text, { ...context }, { timeout: 100 })
  return exact ? evaluate(exact[1]) : value.replace(/{{([\s\S]*?)}}/g, (_, text) => evaluate(text))
}
// Project actual nested WXML datasets. This is not a native compiler or geometry test.
function rowEvents(page) {
  const list = find(templateTree, node => node.attrs && node.attrs.class === 'item-list'), events = []
  function visit(node, context) {
    if (node.tag === '#text') return
    const attrs = node.attrs || {}
    if (attrs['wx:for']) {
      binding(attrs['wx:for'], context).forEach((item, index) => {
        const next = { ...node, attrs: { ...attrs } }; delete next.attrs['wx:for']
        visit(next, { ...context, [attrs['wx:for-item'] || 'item']: item, [attrs['wx:for-index'] || 'index']: index })
      })
      return
    }
    if (attrs['wx:if'] && !binding(attrs['wx:if'], context)) return
    const handler = attrs.bindtap || attrs.bindinput || attrs['bind:change']
    if (handler) {
      const dataset = {}
      for (const [key, value] of Object.entries(attrs)) if (key.startsWith('data-')) {
        dataset[key.slice(5).replace(/-([a-z])/g, (_, letter) => letter.toUpperCase())] = binding(value, context)
      }
      events.push({ handler, dataset, tag: node.tag })
    }
    for (const child of node.children || []) visit(child, context)
  }
  visit(list, page.data)
  return events
}
function dispatch(page, rendered, detail = {}) {
  return page[rendered.handler]({ currentTarget: { dataset: rendered.dataset }, detail })
}
function selectorEvent(page, index) {
  const open = rowEvents(page).filter(node => node.handler === 'onOpenProduct')[index]
  if (page.data.selectingRowKey !== open.dataset.rowKey) dispatch(page, open)
  return rowEvents(page).find(node => node.handler === 'onSelectProduct' && Number(node.dataset.index) === index)
}
function selectProduct(page, index, id) { return dispatch(page, selectorEvent(page, index), { id }) }
function fixture() {
  const calls = [], wx = createWx(), app = createApp()
  const p = loadPage('miniapp-user/pages/template/index.js', { wx, app, stubs: {
    'api/template': { getTemplates: async () => ok([]), saveTemplate: async (...args) => { calls.push(args); return ok(null) } },
    'api/product': {}, 'api/address': {}, 'utils/station': { resolveStationId: async () => 9 }
  } })
  // The common harness is shallow; actual setData supports paths into arrays.
  p.setData = function (patch, callback) {
    for (const [key, value] of Object.entries(patch)) {
      const parts = key.replace(/\[(\d+)\]/g, '.$1').split('.'); let target = this.data
      for (let i = 0; i < parts.length - 1; i++) {
        if (target[parts[i]] == null) target[parts[i]] = /^\d+$/.test(parts[i + 1]) ? [] : {}
        target = target[parts[i]]
      }
      target[parts[parts.length - 1]] = value
    }
    if (callback) callback()
  }
  p.loadFormData = async () => {}; p.loadData = async () => {}
  p.onAddTemplate(); p.onAddItem()
  p.setData({ loading: false, formReady: true, stationId: 9, products: [{ id: 201, name: '甲' }, { id: 202, name: '乙' }, { id: 203, name: '丙' }] })
  return { p, wx, calls }
}
test('shared selector event changes only its selected row', () => {
  const { p } = fixture(), before = clone(p.data.form.items)
  selectProduct(p, 0, 203)
  assert.equal(p.data.form.items.length, 2); assert.equal(p.data.form.items[0].productId, 203)
  assert.deepStrictEqual(p.data.form.items[1], before[1]); assert.equal(p.data.form.items[0].quantity, 1)
  selectProduct(p, 1, 201); assert.deepStrictEqual(p.data.form.items.map(row => row.productId), [203, 201])
})
test('add/delete/select retain surviving row keys and reject an event captured before row deletion', () => {
  const { p } = fixture(), keys = p.data.form.items.map(row => row.rowKey)
  assert(keys.every(key => typeof key === 'string' && key.length)); assert.notEqual(keys[0], keys[1])
  const stale = selectorEvent(p, 1)
  dispatch(p, rowEvents(p).filter(node => node.handler === 'onRemoveItem')[0])
  assert.equal(p.data.form.items[0].rowKey, keys[1]); p.onAddItem(); const addedKey = p.data.form.items[1].rowKey
  assert(!keys.includes(addedKey)); const before = clone(p.data.form.items)
  dispatch(p, stale, { id: 201 }); assert.deepStrictEqual(p.data.form.items, before)
  selectProduct(p, 0, 201); assert.deepStrictEqual(p.data.form.items.map(row => row.productId), [201, null])
})
test('shared stepper change and removal preserve row identity', () => {
  const { p } = fixture(), input = rowEvents(p).filter(node => node.handler === 'onQuantityInput')[1]
  dispatch(p, input, { value: '7' }); assert.deepStrictEqual(p.data.form.items.map(row => row.quantity), [1, 7])
  dispatch(p, input, { value: '8' }); assert.equal(p.data.form.items[1].quantity, 8)
  const remove = rowEvents(p).filter(node => node.handler === 'onRemoveItem')[0]
  remove.dataset.index = String(remove.dataset.index); dispatch(p, remove)
  assert.equal(p.data.form.items.length, 1); assert.equal(p.data.form.items[0].quantity, 8)
})
test('invalid, absent and out-of-range row indices never write or throw across row handlers', () => {
  const { p } = fixture(), before = clone(p.data.form.items), rowKey = before[0].rowKey
  for (const index of [undefined, null, '', -1, 0.5, 2, 99, '0junk', '0].other', Infinity]) {
    const event = { currentTarget: { dataset: { index, rowKey, id: 201, type: 'add' } }, detail: { value: '9' } }
    for (const handler of ['onSelectProduct', 'onQuantityChange', 'onQuantityInput', 'onRemoveItem']) p[handler](event)
    assert.deepStrictEqual(p.data.form.items, before)
  }
})
test('missing/stale row key and unknown or malformed product identifiers are ignored', () => {
  const { p } = fixture(), before = clone(p.data.form.items), rowKey = before[0].rowKey
  for (const extra of [{ rowKey: undefined, id: 201 }, { rowKey: 'old-row', id: 201 },
    { rowKey, id: '201junk' }, { rowKey, id: 999 }, { rowKey, id: 0 }]) {
    p.onSelectProduct({ currentTarget: { dataset: { index: 0, ...extra } } })
    assert.deepStrictEqual(p.data.form.items, before)
  }
  p.onSelectProduct({ currentTarget: { dataset: { index: '0', rowKey, id: '202' } } })
  assert.equal(p.data.form.items[0].productId, 202)
})
test('editing repeated products creates distinct row keys, and saving sends only product and quantity', async () => {
  const { p, calls } = fixture()
  p.onEditTemplate({ currentTarget: { dataset: { template: { id: 31, name: '原配置', items: [{ productId: 201, quantity: 2 }, { productId: 201, quantity: 3 }] } } } })
  assert.notEqual(p.data.form.items[0].rowKey, p.data.form.items[1].rowKey)
  p.setData({ formReady: true })
  selectProduct(p, 1, 203); await p.onSaveTemplate()
  assert.equal(calls.length, 1); assert.equal(calls[0][1], 9)
  assert.deepStrictEqual(calls[0][0].items, [{ productId: 201, quantity: 2 }, { productId: 203, quantity: 3 }])
  assert.equal(calls[0][0].id, 31); assert.equal(p.data.showEditModal, false)
})
test('submitting still blocks every form-row mutation', () => {
  const { p } = fixture(), before = clone(p.data.form.items), events = rowEvents(p)
  p.setData({ submitting: true }); p.onAddItem()
  for (const event of events) dispatch(p, event, { value: '12' })
  assert.deepStrictEqual(p.data.form.items, before)
})
test('long save errors belong to the scrollable body while both footer actions remain separate', () => {
  const body = find(templateTree, node => node.attrs && node.attrs.class === 'modal-body')
  const footer = find(templateTree, node => node.attrs && node.attrs.class === 'modal-footer')
  const error = node => node.attrs && node.attrs.class === 'save-error'
  assert(find(body, error)); assert(!find(footer, error))
  assert(find(footer, node => node.attrs && node.attrs.bindtap === 'onCloseEditModal'))
  assert(find(footer, node => node.attrs && node.attrs.bindtap === 'onSaveTemplate'))
  const css = read('miniapp-user/pages/template/index.wxss')
  assert(/\.modal-body\s*\{[^}]*min-height:\s*0;[^}]*overflow-y:\s*auto/s.test(css))
  assert(/\.modal-footer\s*\{[^}]*flex-shrink:\s*0/s.test(css))
})
function privacyFixture(side, platform = {}) {
  const source = read(side + '/components/PrivacyPopup/index.js'), tree = parseWxml(read(side + '/components/PrivacyPopup/index.wxml'))
  const requests = platform.requests || (platform.requests = []), results = []; let config
  const wx = platform.wx || (platform.wx = {
    onNeedPrivacyAuthorization: listener => { platform.need = listener; (platform.listeners || (platform.listeners = [])).push(listener) },
    requirePrivacyAuthorize: () => requests.push('authorize'), getPrivacySetting: () => requests.push('setting'),
    chooseImage: options => options.success({ tempFilePaths: ['already-authorized.jpg'] })
  })
  vm.runInNewContext(source, { wx, Component: value => { config = value } })
  const writes = [], p = { data: clone(config.data), setData(patch) { writes.push(clone(patch)); Object.assign(this.data, patch) } }
  for (const [key, value] of Object.entries(config.methods)) p[key] = value.bind(p)
  config.lifetimes.attached.call(p)
  const agree = find(tree, node => node.attrs && node.attrs.id === 'privacy-agree-btn')
  return { p, wx, tree, agree, requests, results, writes, platform,
    show: () => config.pageLifetimes.show.call(p), detach: () => config.lifetimes.detached.call(p),
    need: () => platform.need(result => results.push({ value: clone(result), visibleAtResolve: p.data.show })) }
}
for (const side of ['miniapp-user', 'miniapp-delivery']) {
  test(side + ': only the native agree button event reports event=agree with its real button id', () => {
    const t = privacyFixture(side)
    assert.equal(t.p.data.show, false); t.need(); assert.equal(t.p.data.show, true); assert.equal(t.results.length, 0)
    assert.equal(t.agree.tag, 'button'); assert.equal(t.agree.attrs['open-type'], 'agreePrivacyAuthorization')
    assert.equal(t.agree.attrs.bindtap, undefined); assert.equal(t.agree.attrs.bindagreeprivacyauthorization, 'onAgree')
    // Dispatch the handler declared for the native event; this does not emulate native permission state.
    t.p[t.agree.attrs.bindagreeprivacyauthorization]({ type: 'agreeprivacyauthorization' })
    assert.deepStrictEqual(t.results, [{ value: { event: 'agree', buttonId: t.agree.attrs.id }, visibleAtResolve: true }])
    assert.equal(t.p.data.show, false); t.p.onAgree(); assert.equal(t.results.length, 1)
  })
  test(side + ': rejection is explicit disagree, consumes pending once, and preserves contract entry', () => {
    const t = privacyFixture(side), reject = find(t.tree, node => node.attrs && node.attrs.bindtap === 'onReject')
    assert(reject); assert(find(t.tree, node => node.attrs && node.attrs['open-type'] === 'openPrivacyContract'))
    t.need(); t.p[reject.attrs.bindtap](); t.p[reject.attrs.bindtap](); t.p.onAgree()
    assert.deepStrictEqual(t.results, [{ value: { event: 'disagree' }, visibleAtResolve: true }]); assert.equal(t.p.data.show, false)
  })
  test(side + ': returning from page B reclaims page A listener and detached callbacks cannot repaint or consent', () => {
    const platform = {}, a = privacyFixture(side, platform), listenerA = platform.need
    a.need(); const b = privacyFixture(side, platform), listenerB = platform.need
    assert.notEqual(listenerA, listenerB); b.detach()
    const writes = b.writes.length, ignored = []
    listenerB(result => ignored.push(result)); b.p.onAgree(); b.p.onReject()
    assert.equal(b.writes.length, writes); assert.deepStrictEqual(clone(ignored), [{ event: 'disagree' }])
    a.show(); assert.equal(platform.need, listenerA); a.need()
    a.p[a.agree.attrs.bindagreeprivacyauthorization]()
    assert.equal(a.results.length, 2); assert(a.results.every(result => result.value.event === 'agree'))
    assert.equal(a.p.data.show, false); assert.equal(b.p.data.show, false)
    const registrations = platform.listeners.length
    b.show(); assert.equal(platform.listeners.length, registrations); assert.equal(platform.need, listenerA)
  })
  test(side + ': one explicit native choice answers every pending operation once for both agree and disagree', () => {
    const t = privacyFixture(side)
    for (const event of ['agree', 'disagree']) {
      const before = t.results.length
      t.need(); t.need(); t.need(); assert.equal(t.results.length, before)
      const handler = event === 'agree' ? t.agree.attrs.bindagreeprivacyauthorization : 'onReject'
      t.p[handler]()
      assert.equal(t.results.length, before + 3)
      assert(t.results.slice(before).every(result => result.value.event === event && result.visibleAtResolve))
      assert.equal(t.p.data.show, false); t.p[handler](); assert.equal(t.results.length, before + 3)
    }
  })
  test(side + ': already authorized users have no new prompt or authorization call', () => {
    const t = privacyFixture(side); let picked
    t.show(); t.show()
    // An already authorized native flow has no onNeed event to deliver.
    t.wx.chooseImage({ success: result => { picked = result.tempFilePaths[0] } })
    assert.equal(picked, 'already-authorized.jpg'); assert.equal(t.p.data.show, false)
    assert.deepStrictEqual(t.requests, []); assert.deepStrictEqual(t.results, [])
    t.p.onAgree(); assert.equal(t.p.data.show, false); assert.equal(t.results.length, 0)
  })
  test(side + ': leaving a page rejects its pending privacy operations once without granting consent', () => {
    const t = privacyFixture(side)
    t.need(); t.need(); assert.equal(t.results.length, 0)
    t.detach()
    assert.equal(t.results.length, 2); assert(t.results.every(result => result.value.event === 'disagree'))
    t.p.onAgree(); t.p.onReject(); t.detach(); assert.equal(t.results.length, 2)
  })
}
test('template quantity keeps empty and invalid input without sending a save', async () => {
  const { p, calls } = fixture(); selectProduct(p, 0, 201)
  const input = rowEvents(p).find(node => node.handler === 'onQuantityInput')
  for (const raw of ['', '0', '1.5', '12junk']) {
    dispatch(p, input, { value: raw }); assert.equal(String(p.data.form.items[0].quantity), raw)
    await p.onSaveTemplate(); assert.equal(calls.length, 0); assert(p.data.saveError)
  }
  dispatch(p, input, { value: '2' }); await p.onSaveTemplate(); assert.equal(calls.length, 1); assert.equal(calls[0][0].items[0].quantity, 2)
})
;(async () => {
  let failed = 0
  try {
    for (const t of tests) { try { await t.run(); console.log('PASS ' + t.name) } catch (error) { failed++; console.error('FAIL ' + t.name + ': ' + error.stack) } }
    if (failed) process.exitCode = 1
    else console.log('AQUAFLOW_SUITE_OK ' + tests.length)
  } finally { done() }
})().catch(error => { console.error(error); process.exitCode = 1 })
