// Independent synthetic storage/page tests; no HTTP, database, payment or device claims.
const assert = require('assert')
const fs = require('fs'), path = require('path'), vm = require('vm')
const root = path.resolve(__dirname, '../..')
const helperPath = path.join(root, 'miniapp-delivery/utils/payroll-adjust-intent.js')
const pagePath = path.join(root, 'miniapp-delivery/pages/station-mgmt/payroll/index.js')
let passed = 0
const watchdog = setTimeout(() => { console.error('payroll intent suite did not complete'); process.exit(1) }, 30000)
function setup(post = async () => ({ code: 0 })) {
  const storage = new Map(), calls = []
  const wx = { getStorageSync: k => storage.get(k), setStorageSync: (k, v) => storage.set(k, JSON.parse(JSON.stringify(v))),
    removeStorageSync: k => storage.delete(k), showToast() {} }
  global.wx = wx
  delete require.cache[helperPath]
  const intent = require(helperPath)
  const app = { globalData: { userInfo: { staffId: 8, stationId: 3, role: 'STATION_MANAGER' } } }
  let definition
  vm.runInNewContext(fs.readFileSync(pagePath, 'utf8'), { Page: p => { definition = p }, wx, getApp: () => app,
    require: name => name.endsWith('payroll-adjust-intent') ? intent : name.endsWith('order-item-view') ? { itemUnit: () => '桶' }
      : { post: async (url, body) => { calls.push(JSON.parse(JSON.stringify(body))); return post(url, body) }, get: async () => ({ data: [] }) } }, { filename: pagePath })
  const page = Object.assign({}, definition)
  page.data = JSON.parse(JSON.stringify(definition.data))
  page.setData = update => Object.keys(update).forEach(key => {
    const parts = key.split('.'), last = parts.pop(); let current = page.data
    parts.forEach(part => { current = current[part] }); current[last] = update[key]
  })
  page.canManage = () => true; page.loadAll = async () => {}; page.loadSummary = async () => {}
  page.data.stationId = 3; page.data.isManager = true; page.data.loading = false
  return { page, intent, storage, calls, wx, app }
}
async function test(name, fn) { await fn(); passed++; console.log('PASS ' + name) }
;(async () => {
  await test('same intent survives module reload and changed content cannot replace it', () => {
    const { intent, storage } = setup()
    const first = intent.prepare('3:8', { staffId: '4', amount: '12.50', note: ' 补录 ' })
    delete require.cache[helperPath]; const reopened = require(helperPath)
    assert.equal(reopened.prepare('3:8', { staffId: 4, amount: 12.5, note: '补录' }).idempotencyKey, first.idempotencyKey)
    assert.throws(() => reopened.prepare('3:8', { staffId: 4, amount: 13, note: '补录' }), /上一笔工资/)
    assert.equal(storage.size, 1); assert.equal(reopened.payload(first).amount, 12.5)
  })
  await test('pending intent is isolated by station and operator', () => {
    const { intent } = setup(), input = { staffId: 4, amount: 10 }
    const a = intent.prepare('3:8', input), b = intent.prepare('9:8', input), c = intent.prepare('3:7', input)
    assert.notEqual(a.idempotencyKey, b.idempotencyKey); assert.notEqual(a.idempotencyKey, c.idempotencyKey)
    intent.clear(b); assert.equal(intent.pending('9:8'), null); assert.equal(intent.pending('3:8').idempotencyKey, a.idempotencyKey)
  })
  await test('timeout retains key and repeated submission sends the identical payload', async () => {
    let succeed = false
    const { page, calls, intent } = setup(async () => { if (!succeed) throw new Error('网络超时'); return {} })
    page.data.adjustForm = { staffId: 4, amount: '10', note: '补录' }
    await page.onAdjust(); await page.onAdjust()
    assert.equal(calls.length, 2); assert.deepStrictEqual(calls[0], calls[1]); assert.ok(intent.pending('3:8'))
    succeed = true; await page.onAdjust(); assert.equal(intent.pending('3:8'), null)
    page.data.adjustForm.amount = '10'; await page.onAdjust(); assert.notEqual(calls[3].idempotencyKey, calls[0].idempotencyKey)
  })
  await test('reopened page restores original free adjustment rather than making a new intent', async () => {
    const { page, calls, intent } = setup()
    const original = intent.prepare('3:8', { staffId: 4, amount: -5.5, note: '纠正多算' })
    page.restoreAdjustmentIntent(); assert.equal(page.data.tab, 'settle'); assert.equal(page.data.adjustForm.amount, '-5.5')
    await page.onAdjust(); assert.equal(calls[0].idempotencyKey, original.idempotencyKey); assert.equal(calls[0].amount, -5.5)
  })
  await test('unknown result can replay original item after item was disabled', async () => {
    const { page, calls, intent } = setup()
    const original = intent.prepare('3:8', { staffId: 4, itemId: 6, amount: 10, note: '补录' })
    page.data.items = [{ id: 6, name: '旧补贴', enabled: false, directionText: '加项' }]
    page.restoreAdjustmentIntent(); assert.equal(page.data.tab, 'items'); assert.equal(page.data.record.itemId, 6)
    await page.onRecordSubmit(); assert.equal(calls.length, 1); assert.equal(calls[0].idempotencyKey, original.idempotencyKey)
  })
  await test('known business rejection releases intent; unknown outcome blocks changed amount', async () => {
    let business = true
    const { page, calls, intent } = setup(async () => { const e = new Error('拒绝'); e.businessRejected = business; throw e })
    page.data.adjustForm = { staffId: 4, amount: '10', note: '' }; await page.onAdjust(); assert.equal(intent.pending('3:8'), null)
    business = false; await page.onAdjust(); const key = calls[1].idempotencyKey
    page.data.adjustForm.amount = '11'; await page.onAdjust()
    assert.equal(calls.length, 2); assert.equal(intent.pending('3:8').idempotencyKey, key)
  })
  await test('double click shares one in-flight request', async () => {
    let finish; const wait = new Promise(resolve => { finish = resolve })
    const { page, calls } = setup(() => wait); page.data.adjustForm = { staffId: 4, amount: '10', note: '' }
    const first = page.onAdjust(); await page.onAdjust(); assert.equal(calls.length, 1); finish({}); await first
  })
  await test('failed durable storage prevents network send', async () => {
    const { page, calls, wx } = setup(); wx.setStorageSync = () => { throw new Error('存储不可用') }
    page.data.adjustForm = { staffId: 4, amount: '10', note: '' }; await page.onAdjust(); assert.equal(calls.length, 0)
  })
  await test('missing authenticated operator prevents unscoped recording', async () => {
    const { page, calls, app } = setup(); app.globalData.userInfo.staffId = null
    page.data.adjustForm = { staffId: 4, amount: '10', note: '' }; await page.onAdjust(); assert.equal(calls.length, 0)
  })
  console.log(passed + ' payroll intent tests passed')
  console.log('AQUAFLOW_SUITE_OK ' + passed)
})().catch(error => { console.error(error.stack); process.exitCode = 1 }).finally(() => clearTimeout(watchdog))
