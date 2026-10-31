'use strict'
// Actual Page and station/session helpers with memory APIs. The WXML tree is a
// bounded text/condition projection, not native rendering or a WeChat compiler.
const assert = require('assert'), fs = require('fs'), path = require('path')
const { loadPage, createWx, createApp, armWatchdog, ROOT } = require('./harness')
const { parseWxml, renderElements } = require('./wxml-tree')
const done = armWatchdog(), tests = [], test = (name, run) => tests.push({ name, run })
const relative = 'miniapp-user/pages/barrel/index'
const source = fs.readFileSync(path.join(ROOT, relative + '.wxml'), 'utf8')
const tree = parseWxml(source)
function findRecordTree(node) {
  if (node.attrs && node.attrs['wx:for'] === '{{records}}') return node
  for (const child of node.children || []) { const found = findRecordTree(child); if (found) return found }
}
const recordTree = { tag: '#root', attrs: {}, children: [findRecordTree(tree)] }
assert.ok(recordTree.children[0])
const ok = data => ({ code: 0, data })
const pending = () => { let resolve; const promise = new Promise(r => { resolve = r }); return { resolve, promise } }
const event = id => ({ currentTarget: { dataset: { id } } })
const holding = (productId = 5, productName = '合成清泉水') => ({ productId, productName,
  assetQty: 2, heldTotalQty: 2, inTransitQty: 0, occupiedQty: 2, owedQty: 0, storageQty: 0,
  availableRights: 2, independentRights: true, deposit: 30 })
const record = (id = 201, stationId = 11, productId = 5) => ({ id, stationId, productId,
  type: 2, typeText: '退桶', quantity: 2, statusText: '等待确认', depositRefund: 60,
  returnDetail: { status: 'APPROVED', arrangementVersion: 3, pickupModeText: '到店办理',
    requiredBarrels: 2, pickupFee: 0, customerConfirmationRequired: true, customerConfirmationCurrent: false } })
function fixture(options = {}) {
  const wx = createWx(), app = createApp(), rows = options.rows || [record(201), record(202)]
  wx.__storage.set('selectedStation', { id: 11, name: '合成资产站甲' })
  wx.showModal = opts => wx.__calls.modal.push(opts)
  const calls = { reads: [], confirm: [], change: [], withdraw: [], sheets: [] }
  wx.showActionSheet = opts => { calls.sheets.push(opts); opts.success({ tapIndex: 0 }) }
  // Fresh station memory per fixture; the real station/session guard stays loaded.
  delete require.cache[require.resolve(path.join(ROOT, 'miniapp-user/utils/asset-view-station'))]
  const read = (kind, id, fallback) => {
    calls.reads.push({ kind, id })
    return options[kind] ? options[kind](id) : Promise.resolve(ok(fallback))
  }
  const page = loadPage(relative + '.js', { app, wx, stubs: {
    // Supported shared-dialog fixture; F separately covers safe unsupported native fallback.
    'utils/business-dialog': { showBusinessModal: options => wx.showModal(options) },
    'api/asset-stations': { getAssetStation: async id => ok({ id, name: '合成资产站' }) },
    'api/barrel': {
      getBarrelSummary: id => read('summary', id, { independentRights: true, rightBuckets: 2,
        availableRights: 2, owedBuckets: 0, depositBalance: 60 }),
      getBarrelRecords: id => read('records', id, rows),
      getBarrelSummaryByType: id => read('holdings', id, [holding()]),
      confirmBarrelReturn: async (id, version) => { calls.confirm.push({ id, version }); return ok(null) },
      changeBarrelReturnArrangement: async (id, body) => { calls.change.push({ id, body }); return ok(null) },
      withdrawBarrelReturn: async id => { calls.withdraw.push(id); return ok(null) }
    }
  } })
  page.setData = (patch, callback) => {
    for (const [key, value] of Object.entries(patch)) {
      const parts = key.split('.'); let target = page.data
      for (const part of parts.slice(0, -1)) target = target[part] || (target[part] = {})
      target[parts[parts.length - 1]] = value
    }
    if (callback) callback()
  }
  page.onLoad({})
  return { page, app, wx, rows, calls }
}
function nodes(t) { return renderElements(recordTree, t.page.data, { includeText: true }) }
function identities(t) { return nodes(t).filter(n => n.tag === 'text' && n.text.startsWith('退还申请')).map(n => n.text) }
function names(t) { return nodes(t).filter(n => n.tag === 'text' && n.text.startsWith('商品：')).map(n => n.text) }
function onlyNumber(t) {
  assert.deepStrictEqual(identities(t), ['退还申请 #201', '退还申请 #202'])
  assert.deepStrictEqual(names(t), [])
  assert.ok(nodes(t).every(n => !String(n.text).includes('undefined')))
}
test('same-quantity records expose different original request IDs and the actual matched name', async () => {
  const t = fixture(); await t.page.loadData()
  assert.deepStrictEqual(identities(t), ['退还申请 #201', '退还申请 #202'])
  assert.deepStrictEqual(names(t), ['商品：合成清泉水', '商品：合成清泉水'])
  assert.ok(t.page.data.records.every(row => row.quantity === 2))
  assert.deepStrictEqual(t.calls.reads, [{ kind: 'summary', id: 11 }, { kind: 'records', id: 11 }, { kind: 'holdings', id: 11 }])
})
test('product lookup uses each record productId, not row position or current selected product', async () => {
  const t = fixture({ rows: [record(201, 11, 6), record(202, 11, 5)],
    holdings: async () => ok([holding(5, '合成甲水'), holding(6, '合成乙水')]) })
  t.page.data.returnForm.productId = 999; await t.page.loadData()
  assert.deepStrictEqual(names(t), ['商品：合成乙水', '商品：合成甲水'])
})
test('decimal string IDs match numeric station/product fields without changing original IDs', async () => {
  const t = fixture({ rows: [record('201', '11', '5'), record('202', 11, 5)] }); await t.page.loadData()
  assert.deepStrictEqual(identities(t), ['退还申请 #201', '退还申请 #202'])
  assert.strictEqual(t.page.data.records[0].id, '201')
  assert.strictEqual(names(t).length, 2)
})
for (const value of [undefined, null, '', '  ', 77, {}, '未知商品']) {
  test('absent/non-name value ' + JSON.stringify(value) + ' leaves only the honest request number', async () => {
    const h = holding(); if (value === undefined) delete h.productName; else h.productName = value
    const t = fixture({ holdings: async () => ok([h]) }); await t.page.loadData(); onlyNumber(t)
  })
}
test('an unrelated product cannot supply a name', async () => {
  const t = fixture({ holdings: async () => ok([holding(9, '其它商品')]) }); await t.page.loadData(); onlyNumber(t)
})
test('missing record productId cannot borrow another product name', async () => {
  const rows = [record(201), record(202)]; delete rows[0].productId; rows[1].productId = null
  const t = fixture({ rows }); await t.page.loadData(); onlyNumber(t)
})
for (const stationId of [undefined, 22]) {
  test('missing/foreign record station ' + stationId + ' cannot borrow the current station name', async () => {
    const rows = [record(201, stationId), record(202, stationId)]
    if (stationId === undefined) rows.forEach(row => { delete row.stationId })
    const t = fixture({ rows }); await t.page.loadData(); onlyNumber(t)
  })
}
test('explicit foreign station on a matching holding is not an acceptable name source', async () => {
  const t = fixture({ holdings: async () => ok([{ ...holding(), stationId: 22 }]) })
  await t.page.loadData(); onlyNumber(t)
})
test('conflicting same-product names are unknown instead of choosing the first', async () => {
  const t = fixture({ holdings: async () => ok([holding(5, '合成甲水'), holding(5, '合成乙水')]) })
  await t.page.loadData(); onlyNumber(t)
})
test('consistent duplicates and surrounding whitespace retain a single actual name', async () => {
  const t = fixture({ holdings: async () => ok([holding(5, ' 合成甲水 '), holding(5, '合成甲水')]) })
  await t.page.loadData(); assert.deepStrictEqual(names(t), ['商品：合成甲水', '商品：合成甲水'])
})
for (const mode of ['rejection', 'business failure', 'malformed quantities']) {
  test('holding ' + mode + ' preserves records without fabricating a name', async () => {
    const t = fixture({ holdings: async () => {
      if (mode === 'rejection') throw Error('synthetic offline')
      return mode === 'business failure' ? { code: 1, data: [holding()] } : ok([{ productId: 5, productName: '未核实数据' }])
    } })
    await t.page.loadData(); onlyNumber(t); assert.strictEqual(t.page.data.holdingsFailed, true)
  })
}
test('historical return after holdings became empty still retains its original number', async () => {
  const t = fixture({ holdings: async () => ok([]) }); await t.page.loadData(); onlyNumber(t)
  assert.strictEqual(t.page.data.holdingsFailed, false)
})
test('missing/invalid request IDs have an honest label, never a generated or undefined number', async () => {
  const rows = [record(null), record(0), record('bad'), record(1.5)]; delete rows[0].id
  const t = fixture({ rows }); await t.page.loadData()
  assert.deepStrictEqual(identities(t), Array(4).fill('退还申请编号未加载'))
})
test('non-return barrel movements do not acquire a false return-request label', async () => {
  const t = fixture({ rows: [{ ...record(201), type: 8, typeText: '配送收发' }] }); await t.page.loadData()
  assert.deepStrictEqual(identities(t), []); assert.deepStrictEqual(names(t), [])
})
test('display projection preserves every source record field and never mutates API rows', async () => {
  const t = fixture(), before = JSON.parse(JSON.stringify(t.rows)); await t.page.loadData()
  assert.deepStrictEqual(t.rows, before)
  for (let i = 0; i < before.length; i++) {
    const { returnIdentityText, returnProductName, ...original } = t.page.data.records[i]
    assert.deepStrictEqual(original, before[i])
  }
})
test('late A holding cannot pair its name with current B records of the same product', async () => {
  const a = pending(), t = fixture({ records: async id => ok([record(id === 11 ? 201 : 202, id)]),
    holdings: id => id === 11 ? a.promise : Promise.resolve(ok([holding(5, '合成乙站水')])) })
  const old = t.page.loadData()
  await t.page.onAssetStationChange({ detail: { id: 22, name: '合成资产站乙' } })
  a.resolve(ok([holding(5, '合成甲站水')])); await old
  assert.strictEqual(t.page.data.viewStationId, 22)
  assert.deepStrictEqual(identities(t), ['退还申请 #202'])
  assert.deepStrictEqual(names(t), ['商品：合成乙站水'])
})
test('station switch clears old name immediately and failed B holdings cannot reuse it', async () => {
  const b = pending(), t = fixture({ records: async id => ok([record(201, id), record(202, id)]),
    holdings: id => id === 11 ? Promise.resolve(ok([holding(5, '合成甲站水')])) : b.promise })
  await t.page.loadData(); const change = t.page.onAssetStationChange({ detail: { id: 22, name: '合成资产站乙' } })
  assert.deepStrictEqual(t.page.data.records, []); assert.deepStrictEqual(names(t), [])
  b.resolve({ code: 1, data: [holding(5, '不可信名称')] }); await change; onlyNumber(t)
})
test('same-station refresh keeps the latest record/name pair over an older response', async () => {
  const slow = pending(); let count = 0
  const t = fixture({ holdings: () => ++count === 1 ? slow.promise : Promise.resolve(ok([holding(5, '合成新名称')])) })
  const old = t.page.loadData(); await t.page.loadData(); slow.resolve(ok([holding(5, '合成旧名称')])); await old
  assert.deepStrictEqual(names(t), ['商品：合成新名称', '商品：合成新名称'])
})
for (const leave of ['hide', 'unload', 'login']) {
  test(leave + ' invalidates a pending record/name projection', async () => {
    const slow = pending(), t = fixture({ holdings: () => slow.promise }), task = t.page.loadData()
    if (leave === 'login') require(path.join(ROOT, 'miniapp-user/utils/token')).beginSession()
    else t.page[leave === 'hide' ? 'onHide' : 'onUnload']()
    slow.resolve(ok([holding()])); await task
    assert.deepStrictEqual(t.page.data.records, [])
  })
}
test('all original record action data-id bindings still point to their own visible request', async () => {
  const t = fixture(); await t.page.loadData()
  for (const action of ['onReturnConfirm', 'onReturnArrangement', 'onReturnWithdraw', 'onRefundFeedback']) {
    assert.deepStrictEqual(nodes(t).filter(n => n.attrs.bindtap === action).map(n => n.id), [201, 202])
  }
})
test('confirm sends selected B original ID and arrangement version with no display fields', async () => {
  const t = fixture(); await t.page.loadData(); await t.page.onReturnConfirm(event(202))
  assert.strictEqual(t.calls.confirm.length, 0)
  await t.wx.__calls.modal[0].success({ confirm: true })
  assert.deepStrictEqual(t.calls.confirm, [{ id: 202, version: 3 }])
})
test('confirmation is disabled while records refresh or fail and re-enabled after a successful read', async () => {
  const slow = pending(); let phase = 0
  const t = fixture({ records: () => phase === 0 ? Promise.resolve(ok([record()])) : phase === 1 ? slow.promise : Promise.resolve({ code: 1 }) })
  const confirmation = () => nodes(t).find(n => n.attrs.bindtap === 'onReturnConfirm')
  await t.page.loadData(); assert.strictEqual(confirmation().attrs.disabled, '{{refreshing || recordsFailed}}')
  assert.strictEqual(t.page.data.refreshing || t.page.data.recordsFailed, false)
  phase = 1; const read = t.page.loadData(); assert.strictEqual(t.page.data.refreshing, true)
  await t.page.onReturnConfirm(event(201)); assert.strictEqual(t.wx.__calls.modal.length, 0)
  slow.resolve(ok([record()])); await read; assert.strictEqual(t.page.data.refreshing || t.page.data.recordsFailed, false)
  phase = 2; await t.page.loadData(); assert.strictEqual(t.page.data.recordsFailed, true); assert.ok(confirmation())
  await t.page.onReturnConfirm(event(201)); assert.strictEqual(t.wx.__calls.modal.length, 0)
  phase = 0; await t.page.loadData(); assert.strictEqual(t.page.data.refreshing || t.page.data.recordsFailed, false)
  await t.page.onReturnConfirm(event(201)); assert.strictEqual(t.wx.__calls.modal.length, 1)
})
test('confirmation accepts a string record ID and explains an invalid arrangement version', async () => {
  const rows = [record('201')]; const t = fixture({ rows }); await t.page.loadData()
  await t.page.onReturnConfirm(event('201')); assert.strictEqual(t.wx.__calls.modal.length, 1)
  await t.wx.__calls.modal[0].success({ confirm: false }); t.page.data.records[0].returnDetail.arrangementVersion = null
  await t.page.onReturnConfirm(event('201')); assert.strictEqual(t.wx.__calls.modal.length, 1)
  assert.strictEqual(t.wx.__calls.toast.at(-1).title, '收桶安排未核实，请重新加载')
  assert.deepStrictEqual(t.calls.confirm, [])
})
test('arrangement change preserves B ID, expectedVersion and original explicit body', async () => {
  const t = fixture(); await t.page.loadData(); const task = t.page.onReturnArrangement(event(202))
  await Promise.resolve(); await Promise.resolve()
  t.wx.__calls.modal[0].success({ confirm: true, content: '合成变更原因' }); await task
  assert.strictEqual(t.calls.change.length, 1)
  const { id, body } = t.calls.change[0]
  assert.strictEqual(id, 202); assert.ok(body.idempotencyKey.startsWith('ret-arr-'))
  assert.deepStrictEqual({ ...body, idempotencyKey: undefined }, { pickupMode: 'STORE', companionOrderId: null,
    expectedVersion: 3, reason: '合成变更原因', idempotencyKey: undefined })
})
test('withdraw and refund feedback retain B original record target', async () => {
  const t = fixture(); await t.page.loadData(); t.page.onReturnWithdraw(event(202))
  await t.wx.__calls.modal[0].success({ confirm: true })
  assert.deepStrictEqual(t.calls.withdraw, [202])
  t.page.onRefundFeedback(event(202))
  assert.strictEqual(t.wx.__calls.nav[0].url, '/pages/service/index?refundType=BARREL_RETURN&refundId=202')
})
test('free original arrangement still does not demand a new confirmation', async () => {
  const rows = [record()]; rows[0].returnDetail.customerConfirmationRequired = false
  const t = fixture({ rows }); await t.page.loadData(); await t.page.onReturnConfirm(event(201))
  assert.strictEqual(t.calls.confirm.length, 0); assert.strictEqual(t.wx.__calls.modal.length, 0)
  assert.ok(!nodes(t).some(n => n.attrs.bindtap === 'onReturnConfirm'))
})
;(async () => {
  let failed = 0
  for (const { name, run } of tests) {
    try { await run(); console.log('PASS ' + name) }
    catch (err) { failed++; console.error('FAIL ' + name + ': ' + err.message) }
  }
  done()
  if (failed) { console.error(failed + ' failed'); process.exitCode = 1 }
  else console.log('AQUAFLOW_SUITE_OK ' + tests.length)
})().catch(err => { done(); console.error(err); process.exitCode = 1 })
