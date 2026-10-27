const assert = require('assert')
const fs = require('fs')
const { loadPage, createWx, createApp, armWatchdog } = require('./harness')
const { beginSession } = require('../../miniapp-user/utils/token')
const done = armWatchdog(), tests = [], test = (name, run) => tests.push({ name, run })
const ok = data => ({ code: 0, data })
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
const ev = (field, value, index) => ({ currentTarget: { dataset: { field, index, id: value } }, detail: { value } })
const product = { id: 11, name: '合成历史桶装水', spec: '18.5L', category: 1 }
const body = key => ({ stationId: 1, clientToken: key || 'synthetic-original', confirmed: true, depositBalance: '75.00',
  note: '合成历史清单', items: [{ productId: 11, rights: 2, held: 1, tickets: 3, unitPrice: '30.00' }] })
function record(b = body(), status = 'PENDING') {
  return { id: 9, customerId: 7, stationId: b.stationId, clientToken: b.clientToken, requestJson: b, status,
    statusText: { PENDING: '待本站核对', APPROVED: '已生效', REJECTED: '已驳回' }[status],
    reviewerId: status === 'PENDING' ? null : 5, reviewTime: status === 'PENDING' ? null : '2026-10-21T18:00:00',
    reviewNote: status === 'REJECTED' ? '合成差异：请核对持桶数' : null, createTime: '2026-10-21T17:00:00' }
}
function user(overrides = {}, reuse = {}) {
  const wx = reuse.wx || createWx(), app = reuse.app || createApp(), calls = []
  wx.setStorageSync('selectedStation', { id: 1, name: '合成一站' })
  const api = Object.assign({ mine: async () => ok([]), products: async () => ok([product]),
    submit: async b => { calls.push(JSON.parse(JSON.stringify(b))); return ok(record(b)) } }, overrides)
  const page = loadPage('miniapp-user/pages/mine/asset-claims/index.js', { wx, app, stubs: {
    'api/asset-claims': api, 'utils/claim-photo': reuse.photo || { lookup: async () => ({ exists: false }), upload: async () => ({ exists: true }) },
    'api/station': { getPublicStations: async () => ok([{ id: 1, name: '合成一站' }, { id: 2, name: '合成二站' }]) }
  } })
  const activate = () => { global.wx = wx; global.getApp = () => app }
  return { page, wx, app, calls, api, activate }
}
function manager(overrides = {}, reuse = {}) {
  const wx = reuse.wx || createWx(), app = reuse.app || createApp({ globalData: { isLogin: true, accessToken: 'synthetic-token',
    userInfo: { staffId: 5, stationId: 1, role: 'STATION_MANAGER', bindStatus: 'BOUND' } } }), calls = []
  const api = Object.assign({ pending: async () => ok([record()]), detail: async () => ok(record()),
    photo: async () => ok({ exists: false, url: null }),
    review: async (id, b) => { calls.push({ id, body: JSON.parse(JSON.stringify(b)) }); return ok(record(body(), b.approve ? 'APPROVED' : 'REJECTED')) } }, overrides)
  const page = loadPage('miniapp-delivery/pages/station-mgmt/asset-claims/index.js', { wx, app, stubs: {
    'api/asset-claims': api, 'api/station-mgmt': { getCatalog: async () => ok([product]) }
  } })
  const activate = () => { global.wx = wx; global.getApp = () => app }
  return { page, wx, app, calls, api, activate }
}
function fill(t) {
  t.page.setData({ form: { depositBalance: '75.00', note: '合成历史清单',
    items: [{ productId: 11, name: product.name, category: 1, rights: '2', held: '1', tickets: '3', unitPrice: '30.00' }] } })
}

test('natural entries and both native page registrations exist without a new core-page request', () => {
  for (const [root, entry] of [['miniapp-user', 'pages/mine/index.wxml'], ['miniapp-delivery', 'pages/station-mgmt/index.wxml']]) {
    const route = root === 'miniapp-user' ? 'pages/mine/asset-claims/index' : 'pages/station-mgmt/asset-claims/index'
    assert(JSON.parse(fs.readFileSync(root + '/app.json')).pages.includes(route))
    assert(fs.readFileSync(root + '/' + entry, 'utf8').includes('/' + route))
  }
})
test('customer reads a true empty history and can prepare a claim without a prior binding', async () => {
  const t = user(); await t.page.onShow(); assert.equal(t.page.data.ready, true); assert.deepStrictEqual(t.page.data.records, []); assert.equal(t.page.data.catalog.length, 1)
})
test('product cards preserve image and spec, and unavailable image falls back without losing the form', async () => {
  const t = user({ products: async () => ok([{ ...product, imageUrl: '/assets/product/example.png' }]) }); await t.page.onShow()
  assert.equal(t.page.data.catalog[0].imageUrl, '/assets/product/example.png')
  t.page.onAddProduct({ currentTarget: { dataset: { index: 0 } } })
  assert.equal(t.page.data.form.items[0].spec, '18.5L')
  t.page.onImageError({ currentTarget: { dataset: { index: 0 } } })
  assert.equal(t.page.data.catalog[0].imageUrl, ''); assert.equal(t.page.data.form.items.length, 1)
})
test('empty deposit is not treated as zero and drafts survive station changes and reopening', async () => {
  const t = user(); await t.page.onShow()
  t.page.onAddProduct({ currentTarget: { dataset: { index: 0 } } })
  t.page.onField(ev('rights', '2', 0)); t.page.onField(ev('note', '旧押金条'))
  await t.page.onSubmit(); assert.equal(t.calls.length, 0); assert(t.page.data.error)
  await t.page.onStation({ detail: { value: '1' } }); assert.equal(t.page.data.form.items.length, 0)
  await t.page.onStation({ detail: { value: '0' } }); assert.equal(t.page.data.form.items[0].rights, '2')
  t.page.onUnload(); const next = user({}, t); await next.page.onShow()
  assert.equal(next.page.data.form.note, '旧押金条'); assert.equal(next.page.data.form.depositBalance, '')
})
test('a failed draft save does not discard the form when changing station', async () => {
  const t = user(); await t.page.onShow(); fill(t)
  t.wx.setStorageSync = () => { throw Error('disk failure') }
  await t.page.onStation({ detail: { value: '1' } })
  assert.equal(t.page.data.stationId, 1); assert.equal(t.page.data.form.depositBalance, '75.00'); assert(t.page.data.notice)
})
test('photo failure preserves the form, blocks submit, and retry checks the original token', async () => {
  let calls = 0, token; const photo = { lookup: async (station, key) => { token = key; return { exists: false } },
    upload: async () => { if (++calls === 1) throw Error('network'); return { exists: true, url: null } } }
  const t = user({}, { photo }); await t.page.onShow(); fill(t)
  t.page.setData({ form: { ...t.page.data.form, photoPath: 'synthetic.jpg', photoToken: 'claim-photo-key', photoConfirmed: false } })
  await t.page.onRetryPhoto(); assert.equal(t.page.data.form.note, '合成历史清单'); assert(!t.page.data.form.photoConfirmed)
  await t.page.onSubmit(); assert.equal(t.calls.length, 0)
  await t.page.onRetryPhoto(); assert.equal(token, 'claim-photo-key'); assert(t.page.data.form.photoConfirmed)
  await t.page.onSubmit(); assert.equal(t.calls[0].clientToken, token)
})
test('changing identity during photo lookup never starts an upload', async () => {
  const gate = deferred(); let writes = 0
  const t = user({}, { photo: { lookup: () => gate.promise, upload: async () => { writes++; return { exists: true } } } })
  await t.page.onShow(); fill(t)
  t.page.setData({ form: { ...t.page.data.form, photoPath: 'synthetic.jpg', photoToken: 'claim-photo-key', photoConfirmed: false } })
  const work = t.page.onRetryPhoto(); t.app.globalData.customerId = 8; gate.resolve({ exists: false }); await work
  assert.equal(writes, 0)
})
test('removing an unconfirmed photo permits a no-photo submission with a new token', async () => {
  const t = user(); await t.page.onShow(); fill(t)
  t.page.setData({ form: { ...t.page.data.form, photoPath: 'synthetic.jpg', photoToken: 'old-photo-key', photoConfirmed: false } })
  t.page.onRemovePhoto(); await t.page.onSubmit(); assert.equal(t.calls.length, 1); assert.notEqual(t.calls[0].clientToken, 'old-photo-key')
})
test('manager must verify a photo before approving and may retry a failed read', async () => {
  let failed = true; const t = manager({ photo: async () => { if (failed) throw Error('network'); return ok({ exists: true, url: 'https://example.test/photo' }) } })
  await t.page.onShow(); await t.page.onSelect(ev('id', 9)); await t.page.onApprove(); assert.equal(t.calls.length, 0); assert(t.page.data.photoError)
  failed = false; await t.page.onRetryPhoto(); assert.equal(t.page.data.photoUrl, 'https://example.test/photo')
  await t.page.onApprove(); assert.equal(t.calls.length, 1)
})
test('submit is the customer confirmation; pending receipt is never labelled effective', async () => {
  const t = user(); await t.page.onShow(); fill(t); await t.page.onSubmit()
  assert.equal(t.calls.length, 1); assert.equal(t.calls[0].confirmed, true); assert.equal(t.calls[0].depositBalance, '75.00')
  assert.equal(t.calls[0].items[0].rights, 2); assert.equal(t.page.data.records[0].status, 'PENDING')
  assert(!t.page.data.notice.includes('已生效')); assert.equal(t.wx.__calls.modal.length, 0)
})
test('same visible double click sends once', async () => {
  const flight = deferred(); let count = 0; const t = user({ submit: () => { count++; return flight.promise } })
  await t.page.onShow(); fill(t); const a = t.page.onSubmit(), b = t.page.onSubmit(); assert.equal(count, 1)
  flight.resolve(ok(record(body(t.page._intent.body.clientToken)))); await Promise.all([a, b]); assert.equal(count, 1)
})
test('historical cash and capacity remain distinct, physical held and difference are explicit', async () => {
  const t = user({ mine: async () => ok([record(body(), 'APPROVED')]) }); await t.page.onShow()
  const r = t.page.data.records[0]; assert.equal(r.depositText, '75.00'); assert.equal(r.items[0].rights, 2); assert.equal(r.items[0].held, 1)
  assert.equal(r.items[0].over, -1); assert.equal(r.items[0].unitPriceText, '30.00'); assert.equal(t.page.data.canCreate, false)
})
test('rejection feedback is visible and a new application can be prepared', async () => {
  const t = user({ mine: async () => ok([record(body(), 'REJECTED')]) }); await t.page.onShow()
  assert(t.page.data.records[0].reviewNote.includes('持桶数')); assert.equal(t.page.data.canCreate, true)
})
test('server-side pending application blocks a second key even without local intent', async () => {
  const t = user({ mine: async () => ok([record()]) }); await t.page.onShow(); fill(t); await t.page.onSubmit(); assert.equal(t.calls.length, 0)
})
for (const value of ['1.5', '12junk', '-1', '', '2147483648']) test('quantity is not truncated: ' + JSON.stringify(value), async () => {
  const t = user(); await t.page.onShow(); fill(t); t.page.data.form.items[0].held = value; await t.page.onSubmit(); assert.equal(t.calls.length, 0)
})
for (const value of ['1.001', '12junk', '-1', '1e2']) test('money is not rounded or partially parsed: ' + value, async () => {
  const t = user(); await t.page.onShow(); fill(t); t.page.data.form.depositBalance = value; await t.page.onSubmit(); assert.equal(t.calls.length, 0)
})
test('blank note and duplicate product snapshots cannot be sent', async () => {
  const t = user(); await t.page.onShow(); fill(t); t.page.data.form.note = ''; await t.page.onSubmit()
  fill(t); t.page.data.form.items.push({ ...t.page.data.form.items[0] }); await t.page.onSubmit(); assert.equal(t.calls.length, 0)
})
test('deposit-only claim is available and does not fabricate a product', async () => {
  const t = user(); await t.page.onShow(); fill(t); t.page.data.form.items = []; await t.page.onSubmit(); assert.deepStrictEqual(t.calls[0].items, [])
})
for (const source of ['mine', 'products']) test('unsupported old backend ' + source + ' blocks new submissions without claiming empty success', async () => {
  const t = user({ [source]: async () => { throw Object.assign(Error('接口不存在'), { backendCode: 404 }) } }); await t.page.onShow(); fill(t); await t.page.onSubmit()
  assert.equal(t.calls.length, 0); assert(t.page.data.error); assert.equal(t.page.data.canCreate, false)
})
for (const data of [null, {}, [{ id: 9 }], [{ ...record(), stationId: 2 }], [{ ...record(), customerId: 8 }]]) test('malformed or foreign customer list is not treated as valid empty history ' + JSON.stringify(data).slice(0, 28), async () => {
  const t = user({ mine: async () => ok(data) }); await t.page.onShow(); assert.equal(t.page.data.ready, false); assert(t.page.data.error)
})
test('storage failure prevents network submission', async () => {
  const t = user(); await t.page.onShow(); fill(t); t.wx.setStorageSync = () => { throw Error('synthetic disk failure') }; await t.page.onSubmit(); assert.equal(t.calls.length, 0)
})
test('unknown submit survives reopening and retries the exact original body and key', async () => {
  let original; const t = user({ submit: async b => { original = JSON.parse(JSON.stringify(b)); throw Error('synthetic timeout') } })
  await t.page.onShow(); fill(t); await t.page.onSubmit(); assert(t.page.data.pending); t.page.onUnload()
  const next = user({}, t); await next.page.onShow(); assert(next.page.data.pending); assert.equal(next.calls.length, 0)
  next.page.onField(ev('note', 'changed')); await next.page.onRecover(); assert.deepStrictEqual(next.calls[0], original)
})
test('malformed submit success keeps the durable original request', async () => {
  const t = user({ submit: async () => ok({ accepted: true }) }); await t.page.onShow(); fill(t); await t.page.onSubmit()
  assert(t.page.data.pending); assert(!t.page.data.notice.includes('已生效')); assert.equal(t.page.data.records.length, 0)
})
test('known business rejection releases only this original request and remains an error', async () => {
  const t = user({ submit: async () => { throw Object.assign(Error('合成商品已变更'), { businessRejected: true }) } }); await t.page.onShow(); fill(t); await t.page.onSubmit()
  assert.equal(t.page.data.pending, false); assert(t.page.data.error || t.page.data.notice); assert.equal(t.page.data.records.length, 0)
})
for (const kind of ['account', 'login', 'hide', 'unload', 'station']) test('customer old list cannot write after ' + kind, async () => {
  const old = deferred(); const t = user({ mine: () => old.promise }); const work = t.page.onShow()
  await Promise.resolve(); await Promise.resolve()
  if (kind === 'account') t.app.globalData.customerId = 8
  if (kind === 'login') beginSession()
  if (kind === 'hide') t.page.onHide()
  if (kind === 'unload') t.page.onUnload()
  if (kind === 'station') { t.page._stationId = 2; t.page._epoch++ }
  const before = JSON.stringify(t.page.data); old.resolve(ok([record()])); await work; assert.equal(JSON.stringify(t.page.data), before)
})
test('older customer refresh cannot clear the newest loading state', async () => {
  const a = deferred(), b = deferred(); let n = 0; const t = user({ mine: () => ++n === 1 ? a.promise : b.promise })
  const first = t.page.onShow(); await Promise.resolve(); const second = t.page.load(); a.resolve(ok([])); await first; assert(t.page.data.loading)
  b.resolve(ok([])); await second; assert.equal(t.page.data.loading, false)
})
test('guest does not read or submit claims', async () => {
  let n = 0; const t = user({ mine: async () => { n++; return ok([]) } }); t.app.globalData.isLogin = false; await t.page.onShow(); await t.page.onSubmit(); assert.equal(n, 0); assert.equal(t.calls.length, 0)
})
test('customer history reuses the current authorized station name without changing the claim identity', async () => {
  const response = record(), before = JSON.stringify(response)
  const t = user({ mine: async () => ok([response]) }); await t.page.onShow()
  const { parseWxml, renderElements } = require('./wxml-tree')
  const tree = parseWxml(fs.readFileSync(require('path').join(__dirname, '../../miniapp-user/pages/mine/asset-claims/index.wxml'), 'utf8'))
  const rows = renderElements(tree, t.page.data, { includeText: true })
  assert(rows.some(row => row.className === 'claim-row' && row.text.startsWith('合成一站 ·')))
  assert.equal(t.page.data.records[0].stationId, 1); assert.equal(JSON.stringify(response), before)
  assert.equal(t.calls.length, 0)
})
test('manager sees original figures and customer-provided material', async () => {
  const t = manager(); await t.page.onShow(); assert.equal(t.page.data.records[0].depositText, '75.00'); await t.page.onSelect(ev('id', 9)); assert.equal(t.page.data.selected.items[0].held, 1)
})
test('manager approval needs no customer third confirmation and duplicate click writes once', async () => {
  const flight = deferred(); let n = 0; const t = manager({ review: () => { n++; return flight.promise } }); await t.page.onShow(); await t.page.onSelect(ev('id', 9))
  const a = t.page.onApprove(), b = t.page.onApprove(); assert.equal(n, 1); flight.resolve(ok(record(body(), 'APPROVED'))); await Promise.all([a, b])
  assert.equal(t.page.data.selected.status, 'APPROVED'); assert.equal(t.page.data.records.length, 0); assert.equal(t.wx.__calls.modal.length, 0)
})
test('manager rejection requires a reason and displays the returned rejection', async () => {
  const t = manager(); await t.page.onShow(); await t.page.onSelect(ev('id', 9)); await t.page.onReject(); assert.equal(t.calls.length, 0)
  t.page.onNote(ev('note', '合成差异：请核对持桶数')); await t.page.onReject(); assert.equal(t.calls.length, 1); assert.equal(t.page.data.selected.status, 'REJECTED')
})
test('manager pending response cannot be presented as completed approval', async () => {
  const t = manager({ review: async () => ok(record()) }); await t.page.onShow(); await t.page.onSelect(ev('id', 9)); await t.page.onApprove(); assert(t.page.data.pending); assert.equal(t.page.data.selected.status, 'PENDING')
})
test('manager unknown decision restores by original id before any new write', async () => {
  const t = manager({ review: async () => { throw Error('synthetic timeout') } }); await t.page.onShow(); await t.page.onSelect(ev('id', 9)); await t.page.onApprove(); assert(t.page.data.pending); t.page.onUnload()
  const next = manager({ pending: async () => ok([]), detail: async () => ok(record(body(), 'APPROVED')) }, t)
  await next.page.onShow(); assert.equal(next.page.data.selected.status, 'APPROVED'); assert.equal(next.calls.length, 0); assert.equal(next.page.data.pending, false)
})
test('manager pending original decision retries with unchanged note and action', async () => {
  let first; const t = manager({ review: async (id, b) => { first = JSON.parse(JSON.stringify(b)); throw Error('synthetic timeout') } })
  await t.page.onShow(); await t.page.onSelect(ev('id', 9)); t.page.onNote(ev('note', '合成原核对')); await t.page.onApprove(); t.page.onNote(ev('note', '不应覆盖'))
  t.api.review = async (id, b) => { t.calls.push({ id, body: b }); return ok(record(body(), 'APPROVED')) }
  await t.page.onRecover(); await t.page.onRetryReview(); assert.deepStrictEqual(t.calls[0].body, first)
})
for (const role of ['DELIVERY', 'UNSELECTED', null]) test('manager claims reject local role ' + role + ' before reading', async () => {
  let n = 0; const t = manager({ pending: async () => { n++; return ok([]) } }); t.app.globalData.userInfo.role = role; await t.page.onShow(); assert.equal(n, 0); assert(t.page.data.error)
})
for (const kind of ['identity', 'station', 'hide', 'unload']) test('manager late pending response cannot write after ' + kind, async () => {
  const old = deferred(); const t = manager({ pending: () => old.promise }); const work = t.page.onShow(); await Promise.resolve()
  if (kind === 'identity') t.app._loginGeneration = 1
  if (kind === 'station') t.app.globalData.userInfo.stationId = 2
  if (kind === 'hide') t.page.onHide()
  if (kind === 'unload') t.page.onUnload()
  const before = JSON.stringify(t.page.data); old.resolve(ok([record()])); await work; assert.equal(JSON.stringify(t.page.data), before)
})
test('manager malformed, unauthorized and unsupported list remain failed, not zero pending', async () => {
  for (const pending of [async () => ok(null), async () => ok([{ ...record(), stationId: 2 }]), async () => { throw Object.assign(Error('old version'), { statusCode: 404 }) }]) {
    const t = manager({ pending }); await t.page.onShow(); assert.equal(t.page.data.ready, false); assert(t.page.data.error)
  }
})
test('manager wrong claim response cannot complete or clear original decision', async () => {
  const t = manager({ review: async () => ok({ ...record(body(), 'APPROVED'), id: 10 }) }); await t.page.onShow(); await t.page.onSelect(ev('id', 9)); await t.page.onApprove(); assert(t.page.data.pending)
})
test('station selection is page-local and cannot move the order cart station', async () => {
  const t = user({ mine: async () => ok([]), products: async () => ok([product]) }); await t.page.onShow(); await t.page.onStation(ev('station', '1'))
  assert.equal(t.page.data.stationId, 2); assert.equal(t.wx.getStorageSync('selectedStation').id, 1)
})
test('manager reopening preserves original note even if the original lookup fails, and cannot write', async () => {
  const t = manager({ review: async () => { throw Error('synthetic timeout') } }); await t.page.onShow(); await t.page.onSelect(ev('id', 9))
  t.page.onNote(ev('note', '合成原说明')); await t.page.onApprove(); t.page.onUnload()
  const next = manager({ detail: async () => { throw Error('synthetic lookup failure') } }, t); await next.page.onShow()
  assert.equal(next.page.data.reviewNote, '合成原说明'); assert.equal(next.page.data.originalDecision, '核对通过')
  await next.page.onRetryReview(); assert.equal(next.calls.length, 0); assert(next.page.data.pending)
})
test('a malformed false storage value is preserved and blocks a new key', async () => {
  const t = user(); t.wx.setStorageSync('asset-claim-intent:customer:7:station:1', false); await t.page.onShow(); fill(t); await t.page.onSubmit()
  assert.equal(t.page.data.canCreate, false); assert.equal(t.calls.length, 0); assert.strictEqual(t.wx.getStorageSync('asset-claim-intent:customer:7:station:1'), false)
})
test('silent storage write failure never sends the new application', async () => {
  const t = user(); await t.page.onShow(); fill(t); t.wx.setStorageSync = () => {}; await t.page.onSubmit(); assert.equal(t.calls.length, 0)
})
test('late hidden submit does not write UI or clear receipt; original GET resolves on return without reposting', async () => {
  const flight = deferred(); let saved
  const t = user({ submit: b => { saved = JSON.parse(JSON.stringify(b)); return flight.promise } }); await t.page.onShow(); fill(t)
  const work = t.page.onSubmit(); t.page.onHide(); const before = JSON.stringify(t.page.data); flight.resolve(ok(record(saved))); await work
  assert.equal(JSON.stringify(t.page.data), before)
  const next = user({ mine: async () => ok([record(saved)]) }, t); await next.page.onShow()
  assert.equal(next.calls.length, 0); assert.equal(next.page.data.pending, false); assert.equal(next.page.data.records[0].clientToken, saved.clientToken)
})
test('different customer cannot see or replay the previous customer pending intent', async () => {
  const t = user({ submit: async () => { throw Error('synthetic timeout') } }); await t.page.onShow(); fill(t); await t.page.onSubmit(); t.page.onUnload()
  t.app.globalData.customerId = 8; const next = user({}, t); await next.page.onShow(); assert.equal(next.page.data.pending, false); assert.equal(next.calls.length, 0)
  t.app.globalData.customerId = 7; const original = user({}, t); await original.page.onShow(); assert.equal(original.page.data.pending, true)
})
test('rejected original replay clears the locked form so hidden old quantities cannot enter the new application', async () => {
  const t = user({ submit: async () => { throw Error('synthetic timeout') } }); await t.page.onShow(); fill(t); await t.page.onSubmit(); t.page.onUnload()
  const next = user({ submit: async b => ok(record(b, 'REJECTED')) }, t); await next.page.onShow(); await next.page.onRecover()
  assert.equal(next.page.data.pending, false); assert.equal(next.page.data.canCreate, true); assert.deepStrictEqual(next.page.data.form.items, [])
  assert(next.page.data.records[0].reviewNote.includes('持桶数'))
})
test('incomplete or different successful receipt cannot clear an original customer request', async () => {
  for (const mutate of [r => ({ ...r, reviewerId: null }), r => ({ ...r, requestJson: { ...r.requestJson, depositBalance: '76.00' } })]) {
    const t = user({ submit: async b => ok(mutate(record(b, 'APPROVED'))) }); await t.page.onShow(); fill(t); await t.page.onSubmit()
    assert(t.page.data.pending); assert.equal(t.page.data.records.length, 0)
  }
})
test('unreadable new manager cache cannot borrow or clear the previous manager original intent', async () => {
  let lookups = 0
  const t = manager({ review: async () => { throw Error('synthetic timeout') }, detail: async () => { lookups++; return ok(record(body(), 'APPROVED')) } })
  await t.page.onShow(); await t.page.onSelect(ev('id', 9)); await t.page.onApprove()
  const original = t.wx.getStorageSync('asset-claim-intent:manager:5:1:reviews:1')
  t.app.globalData.userInfo.staffId = 6; t.wx.setStorageSync('asset-claim-intent:manager:6:1:reviews:1', { version: 99 })
  await t.page.onShow(); await t.page.onRecover()
  assert.equal(lookups, 0); assert.deepStrictEqual(t.wx.getStorageSync('asset-claim-intent:manager:5:1:reviews:1'), original)
})
test('two real page handlers complete submit, reject feedback, corrected reapply and approval without third customer confirmation', async () => {
  const history = [], submitted = [], decisions = []
  const u = user({ mine: async () => ok(history.slice().reverse()), submit: async b => {
    submitted.push(JSON.parse(JSON.stringify(b))); const r = { ...record(b), id: 9 + history.length }; history.push(r); return ok(r)
  } })
  await u.page.onShow(); fill(u); await u.page.onSubmit(); assert.equal(u.page.data.records[0].status, 'PENDING')
  const m = manager({ pending: async () => ok(history.filter(r => r.status === 'PENDING')), review: async (id, b) => {
    decisions.push(b); const old = history.find(r => r.id === id)
    const next = { ...record(old.requestJson, b.approve ? 'APPROVED' : 'REJECTED'), id, reviewNote: b.note }
    history.splice(history.indexOf(old), 1, next); return ok(next)
  } })
  await m.page.onShow(); await m.page.onSelect(ev('id', 9)); m.page.onNote(ev('note', '合成差异：请核对持桶数')); await m.page.onReject()
  u.activate(); await u.page.onShow(); assert.equal(u.page.data.records[0].status, 'REJECTED'); assert(u.page.data.canCreate)
  fill(u); u.page.data.form.items[0].held = '2'; u.page.data.form.note = '合成更正清单'; await u.page.onSubmit()
  m.activate(); await m.page.onShow(); await m.page.onSelect(ev('id', 10)); await m.page.onApprove()
  u.activate(); await u.page.onShow(); assert.equal(u.page.data.records[0].status, 'APPROVED'); assert.equal(u.page.data.records[0].items[0].held, 2)
  assert.equal(u.page.data.canCreate, false); assert.equal(submitted.length, 2); assert(submitted.every(b => b.confirmed === true))
  assert.equal(decisions.length, 2); assert.equal(decisions[0].approve, false); assert.equal(decisions[1].approve, true)
})
test('f76859f missing catalog route parameter error is shown as unavailable service without developer wording', async () => {
  const t = user({ products: async () => { throw Object.assign(Error('参数格式不正确：id'), { businessRejected: true }) } })
  await t.page.onShow(); assert.equal(t.page.data.canCreate, false); assert(!t.page.data.error.includes('参数格式')); assert(!t.page.data.error.includes('id'))
  fill(t); await t.page.onSubmit(); assert.equal(t.calls.length, 0)
})
test('customer page uses the existing session identity when the logged-in customer id is restored from cache', async () => {
  const t = user(); t.app.globalData.customerId = null; t.wx.setStorageSync('aq_user_customerId', 7)
  await t.page.onShow(); assert.equal(t.page.data.ready, true); fill(t); await t.page.onSubmit(); assert.equal(t.calls.length, 1)
})

;(async () => {
  let failures = 0
  for (const { name, run } of tests) { try { await run(); console.log('PASS ' + name) } catch (e) { failures++; console.error('FAIL ' + name + '\n' + e.stack) } }
  console.log('HISTORICAL_ASSET_CLAIM_RESULT ' + JSON.stringify({ total: tests.length, passed: tests.length - failures, failed: failures }))
  if (!failures) console.log('AQUAFLOW_SUITE_OK ' + tests.length)
  done(); process.exitCode = failures ? 1 : 0
})().catch(e => { done(); console.error(e); process.exitCode = 1 })
