'use strict'
const assert = require('assert'), fs = require('fs'), path = require('path'), Module = require('module')
const { loadPage, createWx, createApp, armWatchdog, ROOT } = require('./harness')
const { parseWxml, renderElements } = require('./wxml-tree')
const done = armWatchdog(), tests = [], test = (name, run) => tests.push({ name, run })
const ok = data => ({ code: 0, data })
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
const clone = value => value == null ? value : JSON.parse(JSON.stringify(value))
const read = file => fs.readFileSync(path.join(ROOT, file), 'utf8')
function component(name, api = {}, extraWx = {}) {
  const wx = Object.assign(createWx(), extraWx), app = createApp(), events = [], calls = []
  let config; global.wx = wx; global.getApp = () => app; global.Component = c => { config = c }
  const old = Module._load
  Module._load = function (request) {
    if (request.endsWith('/api/asset-stations')) return { listAssetStations: (...args) => { calls.push(args); return api.stations ? api.stations(...args) : Promise.resolve(ok({ stations: [], hasMore: false, nextStationId: null })) } }
    if (request.endsWith('/api/order')) return { cancelOrder() { calls.push('cancel'); return Promise.resolve(ok(null)) }, getOrderDetail() { calls.push('detail'); return Promise.resolve(ok(null)) } }
    return old.apply(this, arguments)
  }
  try { const file = path.join(ROOT, 'miniapp-user/components', name, 'index.js'); delete require.cache[file]; require(file) } finally { Module._load = old }
  const properties = Object.fromEntries(Object.entries(config.properties || {}).map(([key, value]) => [key, clone(value.value)]))
  const p = { data: { ...clone(properties), ...clone(config.data || {}) }, properties, setData(v) { Object.assign(this.data, v) }, triggerEvent(name, detail) { events.push({ name, detail }) } }
  for (const [key, fn] of Object.entries(config.methods || {})) p[key] = fn.bind(p)
  p.setProps = values => {
    Object.assign(p.properties, values); Object.assign(p.data, values)
    for (const [keys, fn] of Object.entries(config.observers || {})) {
      const names = keys.split(',').map(v => v.trim()); if (names.some(k => k in values)) fn.call(p, ...names.map(k => p.data[k]))
    }
  }
  if (config.lifetimes && config.lifetimes.attached) config.lifetimes.attached.call(p)
  const tree = parseWxml(read('miniapp-user/components/' + name + '/index.wxml'))
  const nodes = () => renderElements(tree, p.data, { includeText: true })
  return { p, wx, app, config, events, calls, nodes, text: () => nodes().map(x => x.text || '').join('\n') }
}
function notice(handler) {
  const wx = createWx(), calls = []
  const p = loadPage('miniapp-user/pages/home/notice.js', { wx, app: createApp(), stubs: {
    'api/notice': { getNoticeDetail: id => { calls.push(id); return handler(id, calls.length) } }
  } })
  const tree = parseWxml(read('miniapp-user/pages/home/notice.wxml'))
  const nodes = () => renderElements(tree, p.data, { includeText: true })
  return { p, wx, calls, nodes, text: () => nodes().map(x => x.text || '').join('\n') }
}
const row = (extra = {}) => ({ id: 12, title: '服务通知', content: '第一行\n第二行 <原文> & 保留', createTime: '2026-10-22T09:30:00', ...extra })
test('notice preserves long original title/plain text and formats a known publication time', async () => {
  const original = row({ title: '原公告 Acme '.repeat(18), content: '原文\n<标签>&\n'.repeat(30) })
  const t = notice(async () => ok(original)); await t.p.onLoad({ id: '12' })
  assert.equal(t.p.data.notice.title, original.title); assert.equal(t.p.data.notice.content, original.content)
  assert(t.text().includes(original.title.trim())); assert(t.text().includes(original.content.trim())); assert(t.text().includes('发布于 2026-10-22 09:30'))
  assert(!read('miniapp-user/pages/home/notice.wxml').includes('rich-text'))
})
test('invalid notice entry issues no request and offers the existing notice list route', async () => {
  const t = notice(async () => { throw Error('must not read') }); await t.p.onLoad({ id: '../12' })
  assert.equal(t.calls.length, 0); assert.equal(t.p.data.canRetry, false); assert(t.text().includes('返回公告列表'))
  t.p.onReturnToList(); assert.equal(t.wx.__calls.nav[0].url, '/pages/notice/index')
})
test('notice English transport errors are not primary user copy and meaningful Chinese rejection is preserved', async () => {
  const t = notice(async () => { throw Error('request:fail timeout') }); await t.p.onLoad({ id: 12 })
  assert(t.p.data.error.includes('暂时无法加载')); assert(!t.text().includes('request:fail'))
  const rejected = notice(async () => { throw Error('公告不存在') }); await rejected.p.onLoad({ id: 12 })
  assert(rejected.p.data.error.includes('公告不存在'))
})
test('notice empty content and unknown timestamp are explicit instead of a blank body or invented date', async () => {
  const t = notice(async () => ok(row({ title: '  ', content: '\n  ', createTime: 'not-a-date' })))
  await t.p.onLoad({ id: 12 }); assert.equal(t.p.data.titleText, '公告'); assert.equal(t.p.data.hasContent, false)
  assert(t.text().includes('这条公告暂无正文')); assert(t.text().includes('发布时间暂未提供')); assert(!t.text().includes('NaN'))
})
test('failed notice refresh marks the retained original record and keeps the error while retry is pending', async () => {
  let phase = 0; const gate = deferred()
  const t = notice(async () => phase === 0 ? ok(row()) : phase === 1 ? Promise.reject(Error('offline')) : gate.promise)
  await t.p.onLoad({ id: 12 }); phase = 1; await t.p.loadNotice(); assert(t.p.data.error.includes('刷新失败'))
  const previous = t.p.data.notice.content; phase = 2; const a = t.p.onRetryTap(), b = t.p.onRetryTap()
  assert.equal(t.calls.length, 3); assert(t.p.data.error.includes('刷新失败')); assert(t.text().includes('正在重试'))
  assert.equal(t.p.data.notice.content, previous); gate.resolve(ok(row({ content: '确认的新正文' }))); await Promise.all([a, b])
  assert.equal(t.p.data.error, ''); assert.equal(t.p.data.notice.content, '确认的新正文')
})
test('the actual notice retry WXML dispatches two taps through onRetry but only issues one read', async () => {
  const gate = deferred(), t = notice((id, count) => count === 1 ? Promise.reject(Error('暂时断网')) : gate.promise)
  await t.p.onLoad({ id: 12 })
  const retry = t.nodes().find(n => n.tag === 'button' && n.className === 'page-state-retry')
  assert(retry); assert.equal(retry.attrs.bindtap, 'onRetry')
  const tap = { type: 'tap', currentTarget: { dataset: { id: 99 } }, target: { dataset: {} } }
  const first = t.p[retry.attrs.bindtap](tap), second = t.p[retry.attrs.bindtap](tap)
  assert.deepStrictEqual(t.calls, ['12', '12']); assert.equal(t.p.data.loading, true)
  assert(t.text().includes('暂时断网')); assert(t.text().includes('正在重试'))
  gate.resolve(ok(row({ content: '重试取得的原公告' }))); await Promise.all([first, second])
  assert.equal(t.p.data.notice.id, 12); assert.equal(t.p.data.notice.content, '重试取得的原公告')
  assert.equal(t.p.data.loading, false); assert.equal(t.p.data.error, '')
})
test('mixed Chinese HTTP failures show friendly copy and log the unmodified original error with metadata', async () => {
  const originalWarn = console.warn, warnings = []
  console.warn = (...args) => warnings.push(args)
  try {
    for (const message of ['服务暂时不可用（HTTP 503），请稍后重试', '水站读取失败：HTTP503']) {
      const error = Object.assign(Error(message), { statusCode: 503, requestId: 'synthetic-request', metadata: { retryable: true } })
      const before = { message: error.message, stack: error.stack, statusCode: error.statusCode, requestId: error.requestId, metadata: clone(error.metadata) }
      const n = notice(async () => { throw error }); await n.p.onLoad({ id: 12 })
      assert.equal(n.p.data.error, '公告详情加载失败：暂时无法加载，请重试。'); assert(!n.text().includes('HTTP'))
      const a = component('AssetStationView', { stations: async () => { throw error } }); await a.p.openPicker()
      assert.equal(a.p.data.errorText, '水站列表暂未加载，请重试'); assert(!a.text().includes('HTTP'))
      assert(warnings.some(args => args[0] === '[notice] 公告读取失败:' && args[1] === error))
      assert(warnings.some(args => args[0] === '[assetStationView] 水站列表加载失败:' && args[1] === error))
      assert.deepStrictEqual({ message: error.message, stack: error.stack, statusCode: error.statusCode, requestId: error.requestId, metadata: error.metadata }, before)
    }
  } finally { console.warn = originalWarn }
})
test('actual request wrappers preserve Chinese business rejections and hide HTTP503 details in both views', async () => {
  const previousEnv = global.__wxConfig
  global.__wxConfig = { envVersion: 'develop' }
  try {
    const noticeApi = require(path.join(ROOT, 'miniapp-user/api/notice.js'))
    const assetApi = require(path.join(ROOT, 'miniapp-user/api/asset-stations.js'))
    for (const failure of ['http', 'business']) {
      const reply = options => options.success(failure === 'http' ? { statusCode: 503, data: null }
        : { statusCode: 200, data: { code: 1, message: '无权查看此记录' } })
      const n = notice(id => noticeApi.getNoticeDetail(id)); n.wx.request = reply; await n.p.onLoad({ id: 12 })
      const a = component('AssetStationView', { stations: (...args) => assetApi.listAssetStations(...args) }, { request: reply }); await a.p.openPicker()
      for (const copy of [n.text(), a.text()]) {
        assert(!copy.includes('HTTP')); assert(!copy.includes('503'))
        if (failure === 'business') assert(copy.includes('无权查看此记录'))
        else { assert(copy.includes('服务暂时不可用')); assert(/(?:请|稍后)重试/.test(copy)) }
      }
    }
  } finally { global.__wxConfig = previousEnv }
})
test('older notice read cannot replace a newer confirmed record', async () => {
  const old = deferred(); let count = 0; const t = notice(() => ++count === 1 ? old.promise : Promise.resolve(ok(row({ title: '新标题' }))))
  const first = t.p.onLoad({ id: 12 }); await t.p.loadNotice(); old.reject(Error('old failure')); await first
  assert.equal(t.p.data.notice.title, '新标题'); assert.equal(t.p.data.error, '')
})
test('late notice response cannot repaint after unload', async () => {
  const gate = deferred(), t = notice(() => gate.promise), read = t.p.onLoad({ id: 12 }); t.p.onUnload()
  const before = JSON.stringify(t.p.data); gate.resolve(ok(row())); await read; assert.equal(JSON.stringify(t.p.data), before)
})
test('asset selector distinguishes no station from an unknown name and labels an active operation', () => {
  const t = component('AssetStationView'); assert(t.text().includes('请选择资产水站'))
  t.p.setProps({ stationId: 3 }); assert(t.text().includes('资产水站名称待核实')); assert(!t.text().includes('水站 #'))
  t.p.setProps({ busy: true }); t.p.openPicker(); assert(t.text().includes('办理中')); assert.equal(t.calls.length, 0)
})
test('asset initial pending read is not rendered as an empty relationship list', async () => {
  const gate = deferred(), t = component('AssetStationView', { stations: () => gate.promise })
  const read = t.p.openPicker(); assert(t.text().includes('正在加载水站')); assert(!t.text().includes('暂无可查看'))
  gate.resolve(ok({ stations: [], hasMore: false, nextStationId: null })); await read
  assert(t.text().includes('暂无可查看的资产水站'))
})
test('asset failed read has Chinese retry copy and remains visible through pending retry', async () => {
  let fail = true; const gate = deferred()
  const t = component('AssetStationView', { stations: () => fail ? Promise.reject(Error('NETWORK_TIMEOUT')) : gate.promise })
  await t.p.openPicker(); assert.equal(t.p.data.errorText, '水站列表暂未加载，请重试'); assert(!t.text().includes('NETWORK_TIMEOUT'))
  fail = false; const a = t.p.retry(), b = t.p.retry(); assert.equal(t.calls.length, 2)
  assert(t.text().includes('水站列表暂未加载')); assert(t.text().includes('正在重试'))
  gate.resolve(ok({ stations: [], hasMore: false, nextStationId: null })); await Promise.all([a, b]); assert.equal(t.p.data.errorText, '')
})
test('asset retry keeps successfully loaded rows and uses the original next cursor', async () => {
  let fail = true
  const t = component('AssetStationView', { stations: async cursor => {
    if (cursor === 0) return ok({ stations: [{ id: 1, name: '原资产站 Acme' }], hasMore: true, nextStationId: 1 })
    if (fail) throw Error('offline')
    return ok({ stations: [{ id: 2, name: '第二资产站' }], hasMore: false, nextStationId: null })
  } })
  await t.p.openPicker(); await t.p.more(); assert.equal(t.p.data.stations.length, 1); assert(t.p.data.errorText)
  fail = false; await t.p.retry(); assert.deepStrictEqual(t.calls.map(c => c[0]), [0, 1, 1])
  assert.equal(t.p.data.stations.length, 2); assert(t.text().includes('原资产站 Acme'))
})
test('closing asset selector invalidates its pending result', async () => {
  const gate = deferred(), t = component('AssetStationView', { stations: () => gate.promise })
  const read = t.p.openPicker(); t.p.close(); const before = JSON.stringify(t.p.data)
  gate.resolve(ok({ stations: [{ id: 1, name: '旧结果' }], hasMore: false, nextStationId: null })); await read
  assert.equal(JSON.stringify(t.p.data), before)
})
test('asset station selection emits only its original station and never changes order station storage', async () => {
  const station = { id: 1, name: '原名 Acme', statusText: '可查看历史资产' }
  const t = component('AssetStationView', { stations: async () => ok({ stations: [station], hasMore: false, nextStationId: null }) })
  await t.p.openPicker(); t.p.choose({ currentTarget: { dataset: { id: 1 } } })
  assert.deepStrictEqual(t.events, [{ name: 'change', detail: station }]); assert.equal(t.wx.__calls.storageSet.length, 0)
})
test('a different customer cannot choose the previous asset list', async () => {
  const t = component('AssetStationView', { stations: async () => ok({ stations: [{ id: 1, name: '原站' }], hasMore: false, nextStationId: null }) })
  await t.p.openPicker(); t.app.globalData.customerId = 8; t.p.choose({ currentTarget: { dataset: { id: 1 } } }); assert.equal(t.events.length, 0)
})
const orderRow = (extra = {}) => ({ id: 81, status: 1, statusText: '待配送', payState: 'PAID', payStateText: '已付', totalAmount: 20,
  itemSummary: '当前商品 2桶', addressDetail: '原配送地址', createTime: '2026-10-22T09:30:00', ...extra })
test('order valid zero amount does not fall back to a different total', () => {
  const t = component('OrderCard'); t.p.setProps({ order: orderRow({ amount: 0, totalAmount: 999 }) })
  assert.equal(t.p.data.amountKnown, true); assert.equal(t.p.data.amountText, '0.00'); assert(t.text().includes('¥0.00')); assert(!t.text().includes('999'))
})
test('order missing or malformed amounts are unknown and never a fabricated zero', () => {
  for (const amount of [undefined, null, '', 'NaN', false, [], {}, Infinity, NaN]) {
    const t = component('OrderCard'); t.p.setProps({ order: orderRow({ amount, totalAmount: undefined }) })
    assert.equal(t.p.data.amountKnown, false); assert(t.text().includes('金额待核实')); assert(!t.text().includes('¥0'))
  }
})
test('order known amount and timestamp remain complete and readable', () => {
  const t = component('OrderCard'); t.p.setProps({ order: orderRow({ totalAmount: '12345.60' }) })
  assert.equal(t.p.data.amountText, '12345.60'); assert(t.text().includes('¥12345.60')); assert(t.text().includes('下单于 2026-10-22 09:30'))
})
test('clearing a reused order card removes prior facts, preview and actions', () => {
  const t = component('OrderCard'); t.p.setProps({ order: orderRow({ customerNote: '原备注', canRepay: true }) }); t.p.setProps({ order: null })
  assert.equal(t.p.data.customerNotePreview, ''); assert.equal(t.p.data.statusText, ''); assert.equal(t.p.data.payStatusText, '')
  assert.equal(t.p.data.amountKnown, false); assert.equal(t.p.data.canRepay, false); assert(t.text().includes('订单信息暂未加载'))
  assert(!t.nodes().some(n => n.tag === 'button')); assert(!t.text().includes('原备注'))
})
test('order customer note is still the sole preview source and original text is untouched', () => {
  const t = component('OrderCard'), original = orderRow({ customerNote: ' 门铃坏了\n先电话联系 <门口> & ', specialNote: '[处理记录] 不应预览' })
  const before = JSON.stringify(original); t.p.setProps({ order: original })
  assert.equal(JSON.stringify(original), before); assert.equal(t.p.data.customerNotePreview, '门铃坏了 先电话联系 <门口> &')
  assert(!t.text().includes('不应预览')); t.p.setProps({ order: orderRow({ customerNote: undefined, specialNote: '旧处理记录' }) })
  assert.equal(t.p.data.customerNotePreview, '')
})
test('order payment capability stays backend-driven and button taps retain their original events', () => {
  const t = component('OrderCard'); t.p.setProps({ order: orderRow({ canRepay: false }) })
  assert(!t.nodes().some(n => n.attrs.catchtap === 'onPayNow'))
  t.p.setProps({ order: orderRow({ canRepay: true, repayLabel: '继续支付' }) })
  assert(t.nodes().some(n => n.attrs.catchtap === 'onPayNow' && n.text === '继续支付'))
  t.p.onPayNow(); t.p.onReorder(); assert.deepStrictEqual(t.events.map(e => e.name), ['pay', 'reorder'])
  t.p.onOrderTap(); assert.equal(t.wx.__calls.nav[0].url, '/pages/order/detail?id=81'); assert.equal(t.calls.length, 0)
})
test('empty, loading and icon components retain natural copy, hide contract and safe icon fallback', () => {
  const empty = component('Empty'); assert(empty.text().includes('暂无数据'))
  const long = '暂无与你关联的记录 <原文> '.repeat(25); empty.p.setProps({ text: long }); assert(empty.text().includes(long.trim()))
  const loading = component('Loading'); assert(loading.text().includes('加载中')); loading.p.setProps({ show: false }); assert.equal(loading.nodes().length, 0)
  const icon = component('Icon'); icon.p.setProps({ name: 'missing-icon', color: '#2E4A68' }); assert(icon.p.data.src.startsWith('data:image/svg+xml,')); assert(!icon.p.data.src.startsWith('http'))
})
test('privacy dialog resolves explicit disagree and native agree events with the platform contract', () => {
  let need; const results = []
  const t = component('PrivacyPopup', {}, { onNeedPrivacyAuthorization: fn => { need = fn } })
  need(result => results.push(result)); assert.equal(t.p.data.show, true); assert.equal(results.length, 0)
  assert(t.nodes().some(n => n.attrs['open-type'] === 'openPrivacyContract'))
  t.p.onReject(); assert.deepStrictEqual(results, [{ event: 'disagree' }]); assert.equal(t.p.data.show, false)
  need(result => results.push(result))
  const agree = t.nodes().find(n => n.attrs.id === 'privacy-agree-btn')
  assert.equal(agree.tag, 'button'); assert.equal(agree.attrs['open-type'], 'agreePrivacyAuthorization')
  assert.equal(agree.attrs.bindtap, undefined)
  t.p[agree.attrs.bindagreeprivacyauthorization](); t.p[agree.attrs.bindagreeprivacyauthorization]()
  assert.deepStrictEqual(results, [{ event: 'disagree' }, { event: 'agree', buttonId: 'privacy-agree-btn' }])
})
test('already agreed users continue without a new privacy prompt or authorization request', () => {
  let need; const requests = [], picked = []
  const t = component('PrivacyPopup', {}, { onNeedPrivacyAuthorization: fn => { need = fn },
    requirePrivacyAuthorize: () => requests.push('authorize'), getPrivacySetting: () => requests.push('setting'),
    chooseImage: options => { picked.push(true); options.success({ tempFilePaths: ['/tmp/already-agreed.jpg'] }) } })
  // The platform does not emit onNeedPrivacyAuthorization for an already agreed user.
  assert.equal(typeof need, 'function'); let result
  t.config.pageLifetimes.show.call(t.p); t.config.pageLifetimes.show.call(t.p)
  t.wx.chooseImage({ success: value => { result = value } })
  assert.equal(picked.length, 1); assert.deepStrictEqual(result.tempFilePaths, ['/tmp/already-agreed.jpg'])
  assert.equal(t.p.data.show, false); assert.equal(t.nodes().length, 0); assert.deepStrictEqual(requests, [])
})
test('component styles reserve safe areas, scrolling and physical tap targets without changing financial data', () => {
  const picker = read('miniapp-user/components/AssetStationView/index.wxss'), privacy = read('miniapp-user/components/PrivacyPopup/index.wxss')
  assert(picker.includes('safe-area-inset-top')); assert(picker.includes('safe-area-inset-bottom')); assert(picker.includes('min-height: 44px'))
  assert(privacy.includes('overflow-y: auto')); assert(privacy.includes('safe-area-inset-top')); assert(privacy.includes('safe-area-inset-bottom')); assert(privacy.includes('min-height: 48px'))
  assert(read('miniapp-user/components/OrderCard/index.wxss').includes('white-space: nowrap'))
  assert(read('miniapp-user/pages/home/notice.wxss').includes('white-space: pre-wrap'))
})
;(async () => {
  let failed = 0
  try {
    for (const t of tests) { try { await t.run(); console.log('PASS ' + t.name) } catch (e) { failed++; console.error('FAIL ' + t.name + ': ' + e.stack) } }
    if (failed) process.exitCode = 1
    else console.log('AQUAFLOW_SUITE_OK ' + tests.length)
  } finally { done() }
})().catch(e => { console.error(e); process.exitCode = 1 })
