// F-77：真实页面/App/token及适用的实际api/request旅程；可控transport/API替身不代表真实付款或MySQL。
const assert = require('assert'), path = require('path'), fs = require('fs')
const { createWx, loadPage, armWatchdog, ROOT } = require('./harness')
const { STORAGE_KEYS: K } = require('../../miniapp-user/utils/storage-keys')
const terminalBaseline = process.env.F77_TERMINAL_BASELINE === '1'
  ? path.join(ROOT, 'build/delegated-20261002/independent-purchase-recovery/terminal-record-fix/baseline') : null
const I = require(terminalBaseline ? path.join(terminalBaseline, 'miniapp-user/utils/independent-purchase-intent.js') : '../../miniapp-user/utils/independent-purchase-intent')
const tests = [], test = (name, run) => tests.push({ name, run })
const terminalTest = (name, run) => tests.push({ name, run, terminalProbe: true })
const flush = async () => { for (let i = 0; i < 12; i++) await Promise.resolve() }
const copy = I.clone
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
const event = key => ({ currentTarget: { dataset: { key } } })
function receipt(body, customerId = 7, status = 1, id = 81) {
  const purchase = { id, customerId, stationId: body.stationId, productId: body.productId, quantity: body.quantity,
    unitPrice: 30, amount: body.quantity * 30, paymentId: id + 900, idempotencyKey: body.idempotencyKey,
    status: status === 4 ? 'CANCELLED' : [2, 3].includes(status) ? 'PAID' : 'PENDING',
    statusText: status === 4 ? '已撤回，未收款' : [2, 3].includes(status) ? '已确认押金' : '等待水站收款确认' }
  return { code: 0, data: { purchase, paymentId: purchase.paymentId, amount: purchase.amount, status, statusText: status === 3 ? '已退款' : purchase.statusText } }
}
function setup(scenario = {}, wire = false) {
  const wx = createWx(), sent = [], calls = { purchase: [], history: [], quote: [], products: [] }, server = new Map()
  global.wx = wx; global.__wxConfig = { envVersion: 'develop' }; global.getCurrentPages = () => [{}]
  let app
  global.App = value => { app = value }; global.getApp = () => app
  for (const file of ['miniapp-user/app.js', 'miniapp-user/utils/token.js', 'miniapp-user/utils/request.js', 'miniapp-user/api/barrel.js']) delete require.cache[require.resolve(path.join(ROOT, file))]
  require(path.join(ROOT, 'miniapp-user/app.js'))
  const login = (id = 7, suffix = 'A') => { app.setLoginInfo('access-' + suffix, 'refresh-' + suffix, { customerId: id }); wx.setStorageSync(K.CUSTOMER_ID, id) }
  login()
  const token = require(path.join(ROOT, 'miniapp-user/utils/token'))
  const stubs = { 'api/product': { getStationProducts: async station => {
    calls.products.push(station)
    if (scenario.products) return scenario.products(station)
    return { data: [{ id: 5, category: 1, name: 'Water' }, { id: 6, category: 1, name: 'Other' }] }
  } } }
  if (!wire) stubs['api/barrel'] = {
    quoteBarrelRight: async (...args) => { calls.quote.push(args); return scenario.quote ? scenario.quote(...args) : { data: { amount: args[2] * 30, stationName: 'Original station' } } },
    getBarrelRightPurchases: async station => { calls.history.push(station); return scenario.history ? scenario.history(station) : { data: [...server.values()].filter(r => r.customerId === token.getCustomerId() && r.stationId === station) } },
    purchaseBarrelRight: async body => {
      calls.purchase.push(copy(body))
      if (scenario.purchase) return scenario.purchase(copy(body), calls, server)
      const existing = server.get(body.idempotencyKey)
      const result = receipt(body, Number(token.getCustomerId()), scenario.status === undefined ? 1 : scenario.status, existing ? existing.id : 81 + server.size)
      server.set(body.idempotencyKey, result.data.purchase)
      if (scenario.lost) throw Error('response unknown')
      return result
    }, withdrawBarrelRightPurchase: async () => ({})
  }
  if (wire) wx.request = options => {
    sent.push(options)
    if (options.url.includes('/barrel-rights/quote')) options.success({ statusCode: 200, data: { code: 0, data: { amount: 30, stationName: 'Station' } } })
    else if (options.method === 'GET' && options.url.includes('/barrel-rights')) {
      calls.history.push(options)
      if (!scenario.holdQuery) options.success({ statusCode: 200, data: { code: 0, data: [] } })
    } else if (options.url.endsWith('/barrel-rights/purchase')) calls.purchase.push(options)
  }
  if (terminalBaseline) Object.assign(stubs, {
    'utils/independent-purchase-intent': I, 'utils/token': token,
    'utils/storage': require('../../miniapp-user/utils/storage'),
    'utils/station': require('../../miniapp-user/utils/station')
  })
  if (terminalBaseline && wire) stubs['api/barrel'] = require('../../miniapp-user/api/barrel')
  const page = loadPage(terminalBaseline ? path.join(terminalBaseline, 'miniapp-user/pages/barrel/purchase.js') : 'miniapp-user/pages/barrel/purchase.js', { wx, app, stubs })
  return { wx, app, page, calls, sent, server, scenario, login, token }
}
const registry = (t, id = 7) => copy(t.wx.getStorageSync(I.PREFIX + id))
const original = t => registry(t).entries[0].body
async function startUnknown(t) { t.scenario.lost = true; await t.page.onLoad({ stationId: 1 }); await t.page.onPurchase(); return original(t) }

for (const field of ['quantity', 'product', 'station', 'paymentMethod']) test('未知结果改' + field + '仍重放完整原body，不覆盖key', async () => {
  const t = setup(), body = await startUnknown(t)
  if (field === 'product') t.page.data.index = 1
  else t.page.data[field === 'station' ? 'stationId' : field] = field === 'quantity' ? 9 : field === 'paymentMethod' ? 1 : 8
  await t.page.onPurchase()
  assert.deepStrictEqual(t.calls.purchase, [body, body]); assert.equal(registry(t).entries.length, 1)
  assert.equal(registry(t).activeKey, body.idempotencyKey)
})
test('未知原件使三个编辑handler保守阻断', async () => {
  const t = setup(), body = await startUnknown(t)
  await t.page.onQuantity({ detail: { value: '9' } }); await t.page.onProduct({ detail: { value: 1 } }); t.page.onMethod({ currentTarget: { dataset: { method: 1 } } })
  assert.equal(t.page.data.quantity, 1); assert.equal(t.page.data.index, 0); assert.equal(t.page.data.paymentMethod, 2)
  assert.deepStrictEqual(original(t), body)
})
test('响应丢失后原请求重放只恢复同一已登记款，现价不参与重放', async () => {
  const t = setup(), body = await startUnknown(t); t.scenario.lost = false
  Object.assign(t.page.data, { quote: null, products: [], productsLoaded: false, productsError: 'down', stationId: 8 })
  await t.page.onRetryOriginal(event(body.idempotencyKey))
  assert.equal(t.server.size, 1); assert.deepStrictEqual(t.calls.purchase, [body, body])
  assert.equal(registry(t).entries[0].record.amount, 30); assert.equal(t.page.data.canAnother, true)
})
test('业务拒绝保留原请求，空查询/另买调用不能创建新key', async () => {
  const t = setup({ purchase: async () => { throw Error('ordinary business rejection') } })
  await t.page.onLoad({ stationId: 1 }); await t.page.onPurchase(); const body = original(t)
  await t.page.onQueryOriginal(); await t.page.onAnotherPurchase(); await t.page.onPurchase()
  assert.deepStrictEqual(t.calls.purchase, [body, body]); assert.equal(registry(t).entries[0].record, null)
  assert.equal(t.page.data.canAnother, false); assert(t.page.data.recoveryEntries.length)
})
test('查不到原记录保留body/key且只读原站，不拿当前站空列表清原件', async () => {
  const t = setup(), body = await startUnknown(t); t.server.clear(); t.page.data.stationId = 9
  await t.page.onQueryOriginal()
  assert.equal(t.calls.history.at(-1), 1); assert.deepStrictEqual(original(t), body)
  assert(t.page.data.recoveryHint.includes('不代表没有提交'))
})
test('查询失败保留未知原件，不发款', async () => {
  const t = setup(), body = await startUnknown(t), count = t.calls.purchase.length
  t.scenario.history = async () => { throw Error('query unavailable') }; await t.page.onQueryOriginal()
  assert.deepStrictEqual(original(t), body); assert.equal(t.calls.purchase.length, count); assert.equal(t.page.data.recoveryError, 'query unavailable')
})
for (const status of [1, 2, 4]) test('真实形状登记状态' + status + '原件保留，终态不误提示待交钱', async () => {
  const t = setup({ status }); await t.page.onLoad({ stationId: 1 }); await t.page.onPurchase()
  assert(registry(t).entries[0].record); assert(t.page.data.canAnother)
  const content = t.wx.__calls.modal.at(-1).content
  if (status === 4) { assert(content.includes('已撤回')); assert(!content.includes('请向该水站交付押金')) }
  if (status === 2) assert(content.includes('当前可用权益以资产页为准'))
})
test('退款原款状态不假提示当前权益已可用', async () => {
  const t = setup({ status: 3 }); await t.page.onLoad({ stationId: 1 }); await t.page.onPurchase()
  assert(t.wx.__calls.modal.at(-1).content.includes('已退款')); assert(!t.wx.__calls.modal.at(-1).content.includes('才可以使用'))
})
for (const status of [2, 4]) test('已知终态' + status + '恢复按钮只查询，不重发POST', async () => {
  const t = setup({ status }); await t.page.onLoad({ stationId: 1 }); await t.page.onPurchase()
  const count = t.calls.purchase.length; await t.page.onRetryOriginal(); await t.page.onPurchase()
  assert.equal(t.calls.purchase.length, count)
})
test('明确另买经确认与实时查回后启用新key，旧未解决PENDING完整保留', async () => {
  const t = setup(); await t.page.onLoad({ stationId: 1 }); await t.page.onPurchase(); const body = original(t)
  const reads = t.calls.history.length; await t.page.onAnotherPurchase()
  assert.equal(t.calls.history.length, reads + 1); assert.equal(registry(t).activeKey, null)
  await t.page.onQuantity({ detail: { value: '2' } }); await t.page.onPurchase()
  const state = registry(t); assert.equal(state.entries.length, 2); assert.deepStrictEqual(state.entries[0].body, body)
  assert.equal(state.entries[0].record.status, 'PENDING'); assert.notEqual(state.activeKey, body.idempotencyKey)
})
test('取消另买不查询、不改当前选择或原件', async () => {
  const t = setup(); await t.page.onLoad({ stationId: 1 }); await t.page.onPurchase()
  const before = registry(t), reads = t.calls.history.length; t.wx.__modalAutoConfirm = false
  await t.page.onAnotherPurchase(); assert.deepStrictEqual(registry(t), before); assert.equal(t.calls.history.length, reads)
})
test('完整伪缓存且active为空不能直接发新款，实时空查询保住原件', async () => {
  const t = setup(), body = { stationId: 1, productId: 5, quantity: 1, paymentMethod: 2, idempotencyKey: 'unknown-original' }
  t.wx.setStorageSync(I.PREFIX + '7', { version: 1, customerId: 7, activeKey: null, entries: [{ body, record: receipt(body).data.purchase }] })
  await t.page.onLoad({ stationId: 1 }); await t.page.onPurchase()
  assert.equal(t.calls.purchase.length, 0); assert.deepStrictEqual(original(t), body); assert.equal(registry(t).activeKey, body.idempotencyKey)
  assert.deepStrictEqual(registry(t).entries[0].record, receipt(body).data.purchase)
  assert.equal(t.page.data.canAnother, false); assert(t.page.data.recoveryError)
})
test('实际新购买前核实所有保留原件，历史一件失联也不生成新key', async () => {
  const t = setup(); await t.page.onLoad({ stationId: 1 }); await t.page.onPurchase(); const body = original(t)
  await t.page.onAnotherPurchase(); const before = registry(t); t.server.clear(); await t.page.onPurchase()
  assert.equal(t.calls.purchase.length, 1); assert.equal(registry(t).entries.length, before.entries.length)
  assert.deepStrictEqual(original(t), body); assert.equal(registry(t).activeKey, body.idempotencyKey)
})
for (const outcome of ['empty', 'failure', 'wrong']) test('另买实时查回' + outcome + '不放行，不抹原件', async () => {
  const t = setup(); await t.page.onLoad({ stationId: 1 }); await t.page.onPurchase(); const body = original(t)
  t.scenario.history = async () => {
    if (outcome === 'failure') throw Error('query unavailable')
    return { data: outcome === 'empty' ? [] : [{ ...receipt(body).data.purchase, quantity: 3 }] }
  }
  await t.page.onAnotherPurchase(); assert.equal(registry(t).activeKey, body.idempotencyKey); assert.deepStrictEqual(original(t), body)
  const count = t.calls.purchase.length; await t.page.onQuantity({ detail: { value: '9' } }); assert.equal(t.calls.purchase.length, count)
})
for (const field of ['customerId', 'stationId', 'productId', 'quantity', 'paymentId', 'amount', 'idempotencyKey']) test('原响应错误' + field + '不登记成功或丢原key', async () => {
  const t = setup({ purchase: async body => {
    const r = receipt(body); r.data.purchase[field] = field === 'idempotencyKey' ? 'wrong' : field === 'amount' ? 99 : 999
    return r
  } }); await t.page.onLoad({ stationId: 1 }); await t.page.onPurchase()
  assert.equal(registry(t).entries[0].record, null); assert.equal(t.page.data.canAnother, false); assert.equal(t.wx.__calls.modal.length, 0)
})
for (const result of [{}, { status: 2 }, false, [], { purchase: {} }]) test('不完整原响应' + JSON.stringify(result) + '保留未知原件', async () => {
  const t = setup({ purchase: async () => ({ data: result }) }); await t.page.onLoad({ stationId: 1 }); await t.page.onPurchase()
  assert.equal(registry(t).entries[0].record, null); assert.equal(t.page.data.canAnother, false)
})
for (const kind of ['throw', 'noop']) test('首次持久化' + kind + '不发款，恢复写入仍沿内存原key', async () => {
  const t = setup(); await t.page.onLoad({ stationId: 1 })
  const write = t.wx.setStorageSync.bind(t.wx)
  t.wx.setStorageSync = (key, value) => { if (key.startsWith(I.PREFIX)) { if (kind === 'throw') throw Error('write failed'); return } write(key, value) }
  await t.page.onPurchase(); assert.equal(t.calls.purchase.length, 0)
  const key = t.page.data.recoveryEntries[0].key
  t.wx.setStorageSync = write; await t.page.onRetryOriginal(event(key))
  assert.equal(t.calls.purchase.length, 1); assert.equal(t.calls.purchase[0].idempotencyKey, key)
})
test('写后抛错也保留原key且首次不发款', async () => {
  const t = setup(); await t.page.onLoad({ stationId: 1 }); const write = t.wx.setStorageSync.bind(t.wx)
  t.wx.setStorageSync = (key, value) => { write(key, value); if (key.startsWith(I.PREFIX)) throw Error('write after effect') }
  await t.page.onPurchase(); assert.equal(t.calls.purchase.length, 0); const body = original(t)
  t.wx.setStorageSync = write; await t.page.onRetryOriginal(); assert.deepStrictEqual(t.calls.purchase[0], body)
})
test('结果保存失败仍保留原件，重放同key不创建第二原款', async () => {
  const t = setup(); await t.page.onLoad({ stationId: 1 }); const write = t.wx.setStorageSync.bind(t.wx)
  t.scenario.purchase = async body => { const r = receipt(body); t.server.set(body.idempotencyKey, r.data.purchase)
    t.wx.setStorageSync = (key, value) => { if (key.startsWith(I.PREFIX)) throw Error('result write failed'); write(key, value) }; return r }
  await t.page.onPurchase(); const body = original(t); assert.equal(registry(t).entries[0].record, null)
  t.wx.setStorageSync = write; t.scenario.purchase = undefined; await t.page.onRetryOriginal()
  assert.equal(t.server.size, 1); assert.deepStrictEqual(t.calls.purchase, [body, body])
})
test('另买保存无效不启用新购买，删除API抛错也不丢原件', async () => {
  const t = setup(); await t.page.onLoad({ stationId: 1 }); await t.page.onPurchase(); const body = original(t)
  let deletes = 0; t.wx.removeStorageSync = () => { deletes++; throw Error('delete forbidden') }
  const write = t.wx.setStorageSync.bind(t.wx); t.wx.setStorageSync = (key, value) => { if (!key.startsWith(I.PREFIX)) write(key, value) }
  await t.page.onAnotherPurchase(); assert.equal(registry(t).activeKey, body.idempotencyKey); assert.equal(deletes, 0)
  t.wx.setStorageSync = write; await t.page.onAnotherPurchase(); assert.equal(registry(t).entries.length, 1); assert.equal(deletes, 0)
})
for (const target of [I.PREFIX + '7', I.LEGACY]) test('读取' + target + '抛错阻止新款并保留存储', async () => {
  const t = setup(), read = t.wx.getStorageSync.bind(t.wx); t.wx.setStorageSync(target, { evidence: 'keep' })
  t.wx.getStorageSync = key => { if (key === target) throw Error('read failed'); return read(key) }
  await t.page.onLoad({ stationId: 1 }); await t.page.onPurchase(); assert.equal(t.calls.purchase.length, 0)
  assert.deepStrictEqual(t.wx.__storage.get(target), { evidence: 'keep' })
})
test('所有存储读取不可用时安全阻断，无未捕获异常或新款', async () => {
  const t = setup(); t.wx.getStorageSync = () => { throw Error('storage unavailable') }
  await t.page.onLoad({ stationId: 1 }); await t.page.onPurchase(); assert.equal(t.calls.purchase.length, 0); assert(t.page.data.safetyBlocked)
})
for (const raw of [null, false, [], {}, { version: 2 }, { version: 1, customerId: 8, entries: [], activeKey: null }]) test('损坏新存储' + JSON.stringify(raw) + '原样保护', async () => {
  const t = setup(); t.wx.setStorageSync(I.PREFIX + '7', raw); await t.page.onLoad({ stationId: 1 }); await t.page.onPurchase()
  assert.equal(t.calls.purchase.length, 0); assert.deepStrictEqual(t.wx.getStorageSync(I.PREFIX + '7'), raw); assert(t.page.data.safetyBlocked)
})
test('完整legacy迁移保留全局原件，原站/商品下架仍重放完整body', async () => {
  const t = setup({ products: async () => ({ data: [] }) }), old = { intent: '7:9:6:2:1', key: 'old-key' }
  t.wx.setStorageSync(I.LEGACY, old); await t.page.onLoad({ stationId: 1 }); await t.page.onRetryOriginal()
  assert.deepStrictEqual(t.calls.purchase[0], { stationId: 9, productId: 6, quantity: 2, paymentMethod: 1, idempotencyKey: 'old-key' })
  assert.deepStrictEqual(t.wx.getStorageSync(I.LEGACY), old); assert.equal(registry(t).entries.length, 1)
})
test('另一客户合法legacy不阻断本客户，也不串用原件', async () => {
  const t = setup(), old = { intent: '8:9:6:2:1', key: 'other-key' }; t.wx.setStorageSync(I.LEGACY, old)
  await t.page.onLoad({ stationId: 1 }); await t.page.onPurchase(); assert.notEqual(t.calls.purchase[0].idempotencyKey, old.key)
  assert.deepStrictEqual(t.wx.getStorageSync(I.LEGACY), old); assert.equal(registry(t).customerId, 7)
})
for (const old of [{}, { intent: '7:1:5:2', key: 'k' }, { intent: '7:1:5:2:2', key: false }, { intent: '7:1:5:0:2', key: 'k' }, { intent: '7:1:5:2:3', key: 'k' }]) test('不可识别legacy ' + JSON.stringify(old) + '不清不买', async () => {
  const t = setup(); t.wx.setStorageSync(I.LEGACY, old); await t.page.onLoad({ stationId: 1 }); await t.page.onPurchase()
  assert.equal(t.calls.purchase.length, 0); assert.deepStrictEqual(t.wx.getStorageSync(I.LEGACY), old); assert(t.page.data.safetyBlocked)
})
test('缺客户不提交购买，旧owner凭据仍留', async () => {
  const t = setup(); const body = await startUnknown(t); t.app.clearLoginInfo(); await t.page.onPurchase()
  assert.equal(t.calls.purchase.length, 1); assert.deepStrictEqual(original(t), body); assert.equal(t.page.data.recoveryEntries.length, 0)
})
for (const outcome of ['success', 'failure']) test('A到B到A后旧购买' + outcome + '不擦当前原件或状态', async () => {
  const gate = deferred(), t = setup({ purchase: () => gate.promise }); await t.page.onLoad({ stationId: 1 })
  const pending = t.page.onPurchase(), body = original(t); t.login(8, 'B'); t.page.syncRecovery(); t.login(); t.page.syncRecovery()
  t.page.setData({ recoveryError: 'current state' })
  if (outcome === 'success') gate.resolve(receipt(body)); else gate.reject(Error('old failure'))
  await pending; assert.equal(registry(t).entries[0].record, null); assert.equal(t.page.data.recoveryError, 'current state')
})
test('同客户退出重登后旧查询不登记结果、不擦新状态', async () => {
  const t = setup(), body = await startUnknown(t), gate = deferred(); t.scenario.history = () => gate.promise
  const query = t.page.onQueryOriginal(); t.app.clearLoginInfo(); t.login(); t.page.syncRecovery(); t.page.setData({ recoveryHint: 'current hint' })
  gate.resolve({ data: [receipt(body).data.purchase] }); await query
  assert.equal(registry(t).entries[0].record, null); assert.equal(t.page.data.recoveryHint, 'current hint')
})
test('另买确认弹窗跨周期回来不得查询或改当前选择', async () => {
  const t = setup(); await t.page.onLoad({ stationId: 1 }); await t.page.onPurchase(); const before = registry(t), count = t.calls.history.length
  t.wx.showModal = options => t.wx.__calls.modal.push(options)
  const prompt = t.page.onAnotherPurchase(); t.login(8, 'B'); t.login(); t.page.syncRecovery()
  t.wx.__calls.modal.at(-1).success({ confirm: true }); await prompt
  assert.deepStrictEqual(registry(t), before); assert.equal(t.calls.history.length, count)
})
test('同周期另一页面已选择新原件，迟到原响应只更新原条目，不擦新key', async () => {
  const gate = deferred(), t = setup({ purchase: () => gate.promise }); await t.page.onLoad({ stationId: 1 })
  const pending = t.page.onPurchase(), body = original(t), state = registry(t)
  const second = { ...body, quantity: 2, idempotencyKey: 'new-explicit-key' }
  state.entries.push({ body: second, record: null }); state.activeKey = second.idempotencyKey; t.wx.setStorageSync(I.PREFIX + '7', state)
  gate.resolve(receipt(body)); await pending
  assert.equal(registry(t).activeKey, second.idempotencyKey); assert.deepStrictEqual(registry(t).entries[1].body, second)
})
test('实际api/request：旧客户迟到401不刷新、不重发或擦原件', async () => {
  const t = setup({}, true); await t.page.onLoad({ stationId: 1 }); const pending = t.page.onPurchase(), body = original(t)
  const old = t.calls.purchase[0]; t.login(8, 'B'); t.page.syncRecovery()
  old.success({ statusCode: 401, data: { code: 401 } }); await pending
  assert.equal(t.calls.purchase.length, 1); assert.equal(t.sent.filter(o => o.url.endsWith('/auth/refresh')).length, 0)
  assert.deepStrictEqual(original(t), body); assert.equal(t.wx.getStorageSync(I.PREFIX + '8'), '')
})
test('实际api/request：同周期正常续期重发原body一次并保留完整原件', async () => {
  const t = setup({}, true); await t.page.onLoad({ stationId: 1 }); const pending = t.page.onPurchase(), body = original(t)
  t.calls.purchase[0].success({ statusCode: 401, data: { code: 401 } })
  const refresh = t.sent.find(o => o.url.endsWith('/auth/refresh'))
  refresh.success({ statusCode: 200, data: { code: 0, data: { accessToken: 'access-A2', refreshToken: 'refresh-A2' } } }); await flush()
  assert.equal(t.calls.purchase.length, 2); assert.deepStrictEqual(t.calls.purchase[1].data, body)
  t.calls.purchase[1].success({ statusCode: 200, data: receipt(body) }); await pending
  assert.equal(registry(t).entries[0].record.status, 'PENDING'); assert.equal(t.app.globalData.accessToken, 'access-A2')
})
test('实际api/request：同客户重登后旧成功响应不写入购买结果', async () => {
  const t = setup({}, true); await t.page.onLoad({ stationId: 1 }); const pending = t.page.onPurchase(), body = original(t)
  t.app.clearLoginInfo(); t.login(); t.page.syncRecovery()
  t.calls.purchase[0].success({ statusCode: 200, data: receipt(body) }); await pending
  assert.equal(registry(t).entries[0].record, null); assert.equal(t.wx.__calls.modal.length, 0)
})
test('实际api/request：A到B到A旧查询不应用购买结果', async () => {
  const t = setup({}, true); await t.page.onLoad({ stationId: 1 }); const pending = t.page.onPurchase(), body = original(t)
  t.calls.purchase[0].fail({ errMsg: 'request:fail timeout' }); await pending
  t.scenario.holdQuery = true; const query = t.page.onQueryOriginal(), old = t.calls.history.at(-1)
  t.login(8, 'B'); t.page.syncRecovery(); t.login(); t.page.syncRecovery(); t.page.setData({ recoveryHint: 'current hint' })
  old.success({ statusCode: 200, data: { code: 0, data: [receipt(body).data.purchase] } }); await query
  assert.equal(registry(t).entries[0].record, null); assert.equal(t.page.data.recoveryHint, 'current hint')
})
test('WXML恢复按钮可达且不依赖报价，终态无重复POST按钮，页面与工具无BOM', async () => {
  const t = setup(), wxml = fs.readFileSync(path.join(ROOT, 'miniapp-user/pages/barrel/purchase.wxml'), 'utf8')
  for (const match of wxml.matchAll(/\b(?:bind|catch)(?:\w+|:\w+)="([\w]+)"/g)) assert.equal(typeof t.page[match[1]], 'function')
  for (const name of ['onQueryOriginal', 'onRetryOriginal', 'onAnotherPurchase']) assert(wxml.includes('bindtap="' + name + '"'))
  assert(wxml.includes("!item.known || item.record.status === 'PENDING'"))
  for (const file of ['miniapp-user/pages/barrel/purchase.js', 'miniapp-user/pages/barrel/purchase.wxml', 'miniapp-user/pages/barrel/purchase.wxss', 'miniapp-user/utils/independent-purchase-intent.js']) assert.notEqual(fs.readFileSync(path.join(ROOT, file)).subarray(0, 3).toString('hex'), 'efbbbf')
})
function terminalSeed(status, active = true) {
  const t = setup(), body = { stationId: 1, productId: 5, quantity: 1, paymentMethod: 2, idempotencyKey: 'terminal-original' }
  const record = receipt(body, 7, status).data.purchase
  t.wx.setStorageSync(I.PREFIX + '7', { version: 1, customerId: 7, activeKey: active ? body.idempotencyKey : null, entries: [{ body, record }] })
  t.page.syncRecovery()
  t.page.setData({ stationId: 1, products: [{ id: 5, category: 1 }], productsLoaded: true, quote: { amount: 30 } })
  return { t, body, record }
}
for (const status of [2, 4]) {
  terminalTest('终态' + status + '空GET后原凭据不消失且恢复始终只读', async () => {
    const { t, body, record } = terminalSeed(status)
    t.scenario.history = async () => ({ data: [] })
    await t.page.onQueryOriginal(); assert.deepStrictEqual(registry(t).entries[0].record, record)
    assert.equal(t.page.data.canAnother, false); assert(t.page.data.recoveryHint.includes('暂未查到'))
    await t.page.onRetryOriginal(); await t.page.onPurchase()
    assert.equal(t.calls.purchase.length, 0); assert.deepStrictEqual(original(t), body)
  })
  for (const observed of [1, status === 2 ? 4 : 2]) {
    terminalTest('终态' + status + '收到状态' + observed + '不倒滚且提示核实', async () => {
      const { t, body, record } = terminalSeed(status)
      t.scenario.history = async () => ({ data: [receipt(body, 7, observed).data.purchase] })
      await t.page.onQueryOriginal(); assert.deepStrictEqual(registry(t).entries[0].record, record)
      assert(t.page.data.recoveryError); assert.equal(t.page.data.canAnother, false)
      await t.page.onRetryOriginal(); assert.equal(t.calls.purchase.length, 0)
    })
  }
  for (const route of ['another', 'preflight']) for (const observed of ['empty', 1, status === 2 ? 4 : 2]) {
    terminalTest('终态' + status + '经' + route + '遇' + observed + '保原件并阻新款', async () => {
      const { t, body, record } = terminalSeed(status, route === 'another')
      t.scenario.history = async () => ({ data: observed === 'empty' ? [] : [receipt(body, 7, observed).data.purchase] })
      if (route === 'another') await t.page.onAnotherPurchase(); else await t.page.onPurchase()
      assert.deepStrictEqual(registry(t).entries[0].record, record); assert.deepStrictEqual(original(t), body)
      assert(t.page.data.recoveryError); assert.equal(t.calls.purchase.length, 0)
      if (route === 'another') assert.equal(registry(t).activeKey, body.idempotencyKey)
    })
  }
  for (const observed of ['empty', 1, status === 2 ? 4 : 2]) {
    terminalTest('跨页较新终态' + status + '不被迟到查询' + observed + '覆盖', async () => {
      const { t, body, record } = terminalSeed(1), gate = deferred()
      t.scenario.history = () => gate.promise; const pending = t.page.onQueryOriginal()
      const newer = receipt(body, 7, status).data.purchase, state = registry(t)
      state.entries[0].record = newer; t.wx.setStorageSync(I.PREFIX + '7', state)
      gate.resolve({ data: observed === 'empty' ? [] : [observed === 1 ? record : receipt(body, 7, observed).data.purchase] })
      await pending; assert.deepStrictEqual(registry(t).entries[0].record, newer)
      t.scenario.history = async () => ({ data: [newer] }); await t.page.onRetryOriginal()
      assert.equal(t.calls.purchase.length, 0)
    })
  }
  for (const observed of [null, 1]) {
    terminalTest('工具save终态' + status + '候选' + observed + '保原凭据', async () => {
      const { t, body, record } = terminalSeed(status), state = I.load(7), next = copy(state.registry)
      next.entries[0].record = observed === null ? null : receipt(body, 7, observed).data.purchase
      I.save(state, next); assert.deepStrictEqual(registry(t).entries[0].record, record)
    })
  }
  terminalTest('终态' + status + '结果保存失败后内存凭据胜过磁盘旧PENDING', async () => {
    const { t, body } = terminalSeed(1), write = t.wx.setStorageSync.bind(t.wx)
    t.wx.setStorageSync = () => { throw Error('result write failed') }
    assert.throws(() => t.page.rememberRecord(body, receipt(body, 7, status).data.purchase, 7))
    t.wx.setStorageSync = write
    const loaded = I.load(7); assert.equal(loaded.registry.entries[0].record.status, status === 2 ? 'PAID' : 'CANCELLED')
    t.page.syncRecovery(); t.scenario.history = async () => ({ data: [] }); await t.page.onRetryOriginal()
    assert.equal(t.calls.purchase.length, 0)
  })
  terminalTest('磁盘较新终态' + status + '胜过失败写回内存旧PENDING', async () => {
    const { t, body } = terminalSeed(1), state = I.load(7), next = copy(state.registry), write = t.wx.setStorageSync.bind(t.wx)
    t.wx.setStorageSync = () => { throw Error('write failed') }; assert.throws(() => I.save(state, next))
    t.wx.setStorageSync = write; next.entries[0].record = receipt(body, 7, status).data.purchase; write(I.PREFIX + '7', next)
    assert.equal(I.load(7).registry.entries[0].record.status, status === 2 ? 'PAID' : 'CANCELLED')
  })
}
terminalTest('迟到POST待确认结果不能覆盖另一页已撤回原件', async () => {
  const { t, body } = terminalSeed(1), gate = deferred()
  t.scenario.purchase = () => gate.promise; const pending = t.page.onRetryOriginal()
  const state = registry(t), cancelled = receipt(body, 7, 4).data.purchase
  state.entries[0].record = cancelled; t.wx.setStorageSync(I.PREFIX + '7', state)
  gate.resolve(receipt(body)); await pending
  assert.deepStrictEqual(registry(t).entries[0].record, cancelled); assert(t.page.data.recoveryError)
  assert.equal(t.wx.__calls.modal.length, 0)
  t.scenario.history = async () => ({ data: [cancelled] }); await t.page.onRetryOriginal(); assert.equal(t.calls.purchase.length, 1)
})
terminalTest('矛盾终态保存不能选资金真值，磁盘和失败内存原件保留', async () => {
  const { t, body } = terminalSeed(1), write = t.wx.setStorageSync.bind(t.wx)
  t.wx.setStorageSync = () => { throw Error('write failed') }
  assert.throws(() => t.page.rememberRecord(body, receipt(body, 7, 2).data.purchase, 7))
  t.wx.setStorageSync = write; const state = registry(t)
  state.entries[0].record = receipt(body, 7, 4).data.purchase; write(I.PREFIX + '7', state)
  assert.throws(() => I.load(7)); assert.deepStrictEqual(registry(t), state)
  state.entries[0].record = receipt(body, 7, 1).data.purchase; write(I.PREFIX + '7', state)
  assert.equal(I.load(7).registry.entries[0].record.status, 'PAID')
})
terminalTest('再次失败的旧快照写回不能抹内存已确认凭据', async () => {
  const { t, body } = terminalSeed(1), old = I.load(7), write = t.wx.setStorageSync.bind(t.wx)
  t.wx.setStorageSync = () => { throw Error('write failed') }
  assert.throws(() => t.page.rememberRecord(body, receipt(body, 7, 2).data.purchase, 7))
  assert.throws(() => I.save(old, copy(old.registry)))
  t.wx.setStorageSync = write; assert.equal(I.load(7).registry.entries[0].record.status, 'PAID')
})
for (const field of ['id', 'paymentId', 'unitPrice']) terminalTest('同key凭据' + field + '冲突不换原凭据或放行另买', async () => {
  const { t, body, record } = terminalSeed(2), bad = copy(record)
  bad[field]++; if (field === 'unitPrice') bad.amount = bad.unitPrice * body.quantity
  t.scenario.history = async () => ({ data: [bad] }); await t.page.onAnotherPurchase()
  assert.deepStrictEqual(registry(t).entries[0].record, record); assert.equal(registry(t).activeKey, body.idempotencyKey)
  assert(t.page.data.recoveryError); assert.equal(t.calls.purchase.length, 0)
})
const done = armWatchdog(30000)
;(async () => {
  const selected = tests, results = []
  for (const entry of selected) {
    try { await entry.run(); results.push({ name: entry.name, passed: true }); console.log('PASS ' + entry.name) }
    catch (error) { results.push({ name: entry.name, passed: false, error: error.message }); console.error('FAIL ' + entry.name, error) }
  }
  done()
  if (process.env.F77_TERMINAL_RESULTS) fs.writeFileSync(process.env.F77_TERMINAL_RESULTS, JSON.stringify({ baseline: !!terminalBaseline, count: selected.length, failed: results.filter(result => !result.passed).length, results }, null, 2))
  if (results.some(result => !result.passed)) { console.log('AQUAFLOW_F77_TERMINAL_FAILED ' + results.filter(result => !result.passed).length); process.exitCode = 1 }
  else console.log('AQUAFLOW_SUITE_OK ' + selected.length)
})().catch(error => { done(); console.error(error); process.exitCode = 1 })
