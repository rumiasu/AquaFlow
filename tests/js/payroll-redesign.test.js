/** Real page handlers + WXML visibility: the fixtures never call a running server or database. */
const assert = require('assert')
const fs = require('fs')
const path = require('path')
const vm = require('vm')
const { createWx, createApp, loadPage } = require('./harness')
const { parseWxml, renderElements } = require('./wxml-tree')
const PAGE = 'miniapp-delivery/pages/station-mgmt/payroll/index'
const ROOT = path.resolve(__dirname, '../..')
const tap = (id, extra = {}) => ({ currentTarget: { dataset: { id, ...extra } } })
const input = (field, value) => ({ currentTarget: { dataset: { field } }, detail: { value } })
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
const turn = () => new Promise(resolve => setImmediate(resolve))

function fixture(overrides = {}) {
  const wx = createWx(), writes = [], routes = [], app = createApp({ globalData: { userInfo: { role: 'STATION_MANAGER', stationId: 1 } } })
  const data = {
    products: [
      { id: 11, name: '测试用很长的商品名称用于检查换行与勾选区域不会互相遮挡', spec: '18.9L / 多规格组合测试', category: 1, imageUrl: 'https://fixture.invalid/missing.png' },
      { id: 12, name: '测试瓶装水', spec: '550mL', category: 2 },
      { id: 13, name: '测试饮水器', spec: '立式', category: 3 },
      { id: 14, name: '测试其他商品', category: 99 }
    ],
    rates: [{ id: 1, productId: 0, perBucketAmount: 2.5, floorBonusPerLevel: 0.5, floorFreeLevel: 3 },
      { id: 2, productId: 11, perBucketAmount: 0, floorBonusPerLevel: 0, floorFreeLevel: 0 },
      { id: 3, productId: 12, perBucketAmount: 1.2, floorBonusPerLevel: 0, floorFreeLevel: 0 }],
    staff: [{ id: 7, name: '测试配送员的长姓名用于检查金额和状态布局' }],
    payrolls: [1, 2, 3, 99].map((status, i) => ({ id: i + 1, staffId: 7, status,
      statusText: ['草稿', '已确认', '已发放', '待核对'][i], totalAmount: i === 0 ? 0 : 1234.56,
      periodStart: '2026-09-01', periodEnd: '2026-09-15' })),
    items: [{ id: 41, name: '测试用二十字的长条目名用于窄屏折行检查', enabled: true, direction: 1, directionText: '加项', sort: 8 },
      { id: 42, name: '测试停用条目', enabled: false, direction: 2, directionText: '扣项', sort: 9 }],
    directions: [{ value: 1, text: '加项' }, { value: 2, text: '扣项' }]
  }
  wx.pageScrollTo = o => routes.push(o)
  const get = async url => {
    routes.push(url)
    if (overrides.get) { const result = overrides.get(url); if (result !== undefined) return result }
    if (url.includes('/earnings')) return { data: { earnings: [], itemSummary: [], unsettledTotal: 0 } }
    if (url.includes('/sale-by-station')) return { data: data.products }
    if (url.includes('/piece-rate')) return { data: { rates: data.rates } }
    if (url.includes('/payroll')) return { data: data.payrolls }
    if (url.includes('/directions')) return { data: data.directions }
    if (url.includes('/earning-items')) return { data: data.items }
    return { data: data.staff }
  }
  const write = method => async (url, body) => {
    writes.push({ method, url, body })
    if (overrides.write) return overrides.write(method, url, body)
    return { data: {} }
  }
  const page = loadPage(PAGE + '.js', { wx, app, stubs: { 'utils/request': { get, put: write('put'), post: write('post'), del: write('del') } } })
  // This page uses native setData paths; the shared harness intentionally only shallow-merges.
  page.setData = (patch, cb) => {
    for (const [key, value] of Object.entries(patch)) {
      const parts = key.replace(/\[(\d+)\]/g, '.$1').split('.')
      let target = page.data
      for (const part of parts.slice(0, -1)) target = target[part] || (target[part] = {})
      target[parts.at(-1)] = value
    }
    if (cb) cb()
  }
  page.setData({ stationId: 1, isManager: true })
  return { page, wx, app, data, writes, routes }
}
const tree = parseWxml(fs.readFileSync(path.join(ROOT, PAGE + '.wxml'), 'utf8'))
function visible(page, handler) {
  return renderElements(tree, page.data).filter(n => [n.attrs.bindtap, n.attrs.catchtap].includes(handler))
}
function disabled(node, page) { return !!vm.runInNewContext(node.attrs.disabled.slice(2, -2), { ...page.data, item: page.data.items.find(it => it.id === node.id) }) }

async function run() {
  let passed = 0, failed = 0
  const watchdog = setTimeout(() => { console.error('PAYROLL_TEST_TIMEOUT'); process.exit(1) }, 20000)
  async function test(name, body) {
    try { await body(); console.log('PASS ' + name); passed++ } catch (e) { console.error('FAIL ' + name + '\n' + e.stack); failed++ }
  }
  await test('all products retain long names/specs and use actual category units, with a genuine zero override', async () => {
    const { page, data } = fixture(); await page.loadAll()
    assert.deepEqual(page.data.rateProducts.map(p => p.quantityUnit), ['桶', '瓶', '台', '件'])
    assert.equal(page.data.rateProducts[0].name, data.products[0].name)
    assert.equal(page.data.rateProducts[0].spec, data.products[0].spec)
    assert.equal(page.data.rateProducts[0].currentRateText, '当前 ¥0.00/桶')
    assert.equal(page.data.rateProducts[2].currentRateText, '当前 ¥2.50/台 · 默认价')
    assert.equal(page.data.rateForm.perBucketAmount, '2.5')
    page.onRateProduct(tap(11)); assert.equal(page.data.rateForm.perBucketAmount, '0'); assert.equal(page.data.rateForm.floorFreeLevel, '0')
    page.onRateProduct(tap(12)); assert.equal(page.data.selectedRateUnit, '瓶')
    page.onRateProduct(tap(13)); assert.equal(page.data.rateForm.perBucketAmount, ''); assert.equal(page.data.selectedRateUnit, '台')
  })
  await test('missing prices are distinguishable from zero and missing/failed images render placeholders', async () => {
    const { page, data } = fixture(); data.rates = []; await page.loadAll()
    assert.equal(page.data.defaultCurrentText, '未配置'); assert.ok(page.data.rateProducts.every(p => p.currentRateText.includes('未配置')))
    page.onProductImageError(tap(11))
    const elements = renderElements(tree, page.data)
    assert.equal(elements.filter(n => n.tag === 'image').length, 0)
    assert.equal(elements.filter(n => n.className.includes('product-placeholder')).length, 4)
    assert.equal(page.data.rateProducts[1].imageFailed, false)
  })
  await test('rate save preserves floor fields, blocks repeated clicks/selection changes and rebuilds card values', async () => {
    const pending = deferred(), f = fixture({ write: () => pending.promise }); await f.page.loadAll()
    f.page.onRateProduct(tap(12)); f.page.onRateInput(input('perBucketAmount', '0'))
    const save = f.page.onSaveRate(); f.page.onSaveRate(); f.page.onRateProduct(tap(13)); f.page.onRateInput(input('perBucketAmount', '9'))
    assert.equal(f.writes.length, 1); assert.equal(f.page.data.rateForm.productId, '12')
    assert.deepEqual(f.writes[0].body, { productId: 12, perBucketAmount: 0, floorBonusPerLevel: 0, floorFreeLevel: 0 })
    f.data.rates[2].perBucketAmount = 0; pending.resolve({ data: {} }); await save
    assert.equal(f.page.data.rateProducts[1].currentRateText, '当前 ¥0.00/瓶'); assert.ok(f.page.data.hasProductZero)
    assert.ok(f.page.data.rates.every(r => r.productName)); assert.equal(f.page.data.savingRate, false)
  })
  await test('payroll states expose only allowed primary actions and keep details for all states, including zero amount', async () => {
    const { page } = fixture(); await page.loadAll(); page.onTab(tap(null, { tab: 'settle' }))
    assert.deepEqual(visible(page, 'onConfirmPayroll').map(n => n.id), [1])
    assert.deepEqual(visible(page, 'onPayPayroll').map(n => n.id), [2])
    assert.equal(visible(page, 'onOpenPayroll').filter(n => n.tag === 'button').length, 4)
    assert.equal(page.data.payrolls[0].totalAmount, 0); assert.equal(page.data.payrolls[3].statusClass, 'pill-off')
    page.setData({ isManager: false }); assert.equal(visible(page, 'onPayPayroll').length, 0); assert.equal(visible(page, 'onConfirmPayroll').length, 0)
  })
  await test('nonmanager onShow routes away without requesting management data or writing', async () => {
    const f = fixture(); let redirects = 0; f.app.globalData.userInfo.role = 'DELIVERY'; f.app.routeByRole = () => redirects++
    f.page.onShow(); await f.page.onSaveRate(); await f.page.onPayPayroll(tap(2)); f.page.onItemMore(tap(41))
    assert.equal(redirects, 1); assert.equal(f.routes.length, 0); assert.equal(f.writes.length, 0); assert.equal(f.page.data.itemMenuId, null)
  })
  await test('payroll confirmation cancellation and duplicate callbacks never duplicate mutations; state advances on reread', async () => {
    const f = fixture(); await f.page.loadAll(); f.wx.showModal = o => f.wx.__calls.modal.push(o)
    await f.page.onConfirmPayroll(tap(1)); await f.page.onConfirmPayroll(tap(1)); assert.equal(f.wx.__calls.modal.length, 1)
    await f.wx.__calls.modal[0].success({ confirm: false }); assert.equal(f.page.data.payrollBusyId, null); assert.equal(f.writes.length, 0)
    await f.page.onConfirmPayroll(tap(1)); const modal = f.wx.__calls.modal[1]; f.data.payrolls[0].status = 2
    await Promise.all([modal.success({ confirm: true }), modal.success({ confirm: true })]); assert.equal(f.writes.length, 1)
    await f.page.onConfirmPayroll(tap(1)); assert.equal(f.wx.__calls.modal.length, 2)
    f.page.onTab(tap(null, { tab: 'settle' })); assert.deepEqual(visible(f.page, 'onPayPayroll').map(n => n.id), [1, 2])
    await f.page.onPayPayroll(tap(1)); assert.ok(f.wx.__calls.modal[2].content.includes('只记录发放时间'))
  })
  await test('generation validates date order and sends end date unchanged, short history stops without losing date filters', async () => {
    const f = fixture(); await f.page.loadAll()
    f.page.setData({ genForm: { staffId: 7, periodStart: '2026-09-16', periodEnd: '2026-09-15', note: '' } }); await f.page.onGenerate(); assert.equal(f.writes.length, 0)
    f.page.setData({ 'genForm.periodStart': '2026-09-01' }); await f.page.onGenerate(); assert.equal(f.writes[0].body.periodEnd, '2026-09-15')
    f.page.onPayrollFilterDate(input('payrollStart', '2026-09-15')); assert.equal(f.page.data.filteredPayrolls.length, 4)
    const reads = f.routes.length; await f.page.onExpandPayrollHistory(); assert.equal(f.routes.length, reads)
    assert.equal(f.page.data.payrollStart, '2026-09-15')
  })
  await test('more menu cancels, switches tabs safely, chooses a single item, and edits preserve sort/direction', async () => {
    const f = fixture(); await f.page.loadAll(); f.page.onTab(tap(null, { tab: 'items' }))
    f.page.onItemMore(tap(41)); f.page.onItemMore(tap(42)); assert.equal(f.page.data.itemMenuId, 41)
    assert.equal(visible(f.page, 'onItemMenuAction').length, 3)
    f.page.onItemMenuClose(); assert.equal(f.writes.length, 0); assert.equal(visible(f.page, 'onItemMenuAction').length, 0)
    f.page.onItemMore(tap(41)); f.page.onTab(tap(null, { tab: 'rate' })); assert.equal(f.page.data.itemMenuId, null)
    f.page.onItemMore(tap(41)); f.page.onItemMenuAction(tap(null, { action: 'edit' })); assert.equal(f.page.data.itemForm.sort, '8')
    f.page.onItemDirection(tap(null, { index: 1 })); assert.equal(f.page.data.itemForm.direction, 2)
    await f.page.onSaveItem(); assert.deepEqual(f.writes[0].body, { name: f.data.items[0].name, direction: 2, sort: 8 })
  })
  await test('toggle via menu locks through reread, displays disabled state, and forbids recording a stopped item', async () => {
    const pending = deferred(), f = fixture({ write: () => pending.promise }); await f.page.loadAll(); f.page.onTab(tap(null, { tab: 'items' }))
    f.page.onItemMore(tap(41)); const work = f.page.onItemMenuAction(tap(null, { action: 'toggle' }))
    f.page.onToggleItem(tap(41)); f.page.onItemMenuAction(tap(null, { action: 'toggle' })); assert.equal(f.writes.length, 1)
    f.data.items[0].enabled = false; pending.resolve({ data: {} }); await work
    assert.ok(disabled(visible(f.page, 'onRecordOpen').find(n => n.id === 41), f.page)); f.page.onRecordOpen(tap(41)); assert.equal(f.page.data.record, null)
    assert.equal(f.page.data.itemBusyId, null)
  })
  await test('delete retains confirmation and used-item server refusal; duplicate clicks and cancel are safe', async () => {
    const f = fixture({ write: () => { throw Error('已被工资流水用过的条目不能删') } }); await f.page.loadAll()
    f.wx.showModal = o => f.wx.__calls.modal.push(o)
    f.page.onDeleteItem(tap(41)); f.page.onDeleteItem(tap(41)); assert.equal(f.wx.__calls.modal.length, 1)
    f.wx.__calls.modal[0].success({ confirm: false }); assert.equal(f.writes.length, 0)
    f.page.onItemMore(tap(41)); f.page.onItemMenuAction(tap(null, { action: 'delete' })); const modal = f.wx.__calls.modal[1]
    modal.success({ confirm: true }); modal.success({ confirm: true }); await turn()
    assert.equal(f.writes.length, 1); assert.equal(f.page.data.items.length, 2); assert.equal(f.page.data.itemBusyId, null)
    assert.ok(f.wx.__calls.toast.some(o => o.title.includes('不能删')))
  })
  await test('record accepts positive amount only and protects in-flight fields and cancellation', async () => {
    const pending = deferred(), f = fixture({ write: () => pending.promise }); await f.page.loadAll(); f.page.onRecordOpen(tap(41))
    f.page.onRecordStaff({ detail: { value: 0 } }); f.page.onRecordInput(input('amount', '-3')); await f.page.onRecordSubmit(); assert.equal(f.writes.length, 0)
    f.page.onRecordInput(input('amount', '10')); const work = f.page.onRecordSubmit(); f.page.onRecordSubmit(); f.page.onRecordClose(); f.page.onRecordInput(input('amount', '20'))
    assert.equal(f.writes.length, 1); assert.equal(f.page.data.record.amount, '10'); assert.equal(f.writes[0].body.itemId, 41)
    pending.resolve({ data: {} }); await work; assert.equal(f.page.data.record, null); assert.equal(f.page.data.summaryStaffId, 7)
  })
  await test('load failures offer retry on every tab and do not appear as successful empty results', async () => {
    let fail = true; const f = fixture({ get: () => fail ? Promise.reject(Error('测试读取失败')) : undefined })
    await f.page.loadAll()
    for (const tab of ['rate', 'settle', 'items']) { f.page.onTab(tap(null, { tab })); assert.ok(visible(f.page, 'loadAll').length); assert.ok(f.page.data.payrollReadError) }
    assert.ok(disabled(visible(f.page, 'onSaveItem')[0], f.page)); fail = false; await f.page.loadAll(); assert.equal(f.page.data.payrollReadError, '')
  })
  await test('detail retry is visible and closing a loading detail ignores late completion', async () => {
    let state = 'fail'; const late = deferred(), f = fixture({ get: url => url.includes('/earnings') ? state === 'fail' ? Promise.reject(Error('明细失败')) : late.promise : undefined })
    await f.page.loadAll(); await f.page.onOpenPayroll(tap(1)); assert.ok(visible(f.page, 'onRetryDetail').length)
    state = 'late'; const work = f.page.onRetryDetail(); f.page.onCloseDetail(); late.resolve({ data: { earnings: [] } }); await work
    assert.equal(f.page.data.detail, null); assert.equal(f.page.data.detailError, '')
  })
  await test('summary selection rejects late previous staff results and failed summary remains retryable', async () => {
    const late = deferred(), f = fixture({ get: url => url.includes('/earnings?staffId=7') ? late.promise : url.includes('/earnings?staffId=8') ? Promise.reject(Error('汇总失败')) : undefined })
    await f.page.loadAll(); f.page.setData({ summaryStaffId: 7 }); const first = f.page.loadSummary()
    f.page.setData({ summaryStaffId: 8 }); await f.page.loadSummary(); late.resolve({ data: { earnings: [{ id: 999 }], unsettledTotal: 99 } }); await first
    assert.ok(f.page.data.summaryError); assert.equal(f.page.data.summaryEarnings.length, 0)
    f.page.onTab(tap(null, { tab: 'items' })); assert.ok(visible(f.page, 'loadSummary').length)
  })
  const historyRows = (count, stationId = 1) => Array.from({ length: count }, (_, i) => ({
    id: count - i, stationId, staffId: 7, status: 1, statusText: '草稿', totalAmount: 10,
    periodStart: '2026-09-01', periodEnd: '2026-09-30', createTime: '2026-10-07 12:00:00'
  }))
  const historyUrl = url => new URL(url, 'https://fixture.invalid')
  await test('503 fixed historical rows remain reachable, with interleaved stations and concurrent new rows', async () => {
    const rows = historyRows(503).map(p => ({ ...p, id: p.id * 2 }))
    rows.push(...historyRows(31, 2).map(p => ({ ...p, id: p.id * 2 + 1 })))
    const calls = [], f = fixture({ get: url => {
      if (!url.startsWith('/api/manager/payroll?')) return
      const q = historyUrl(url).searchParams, limit = Number(q.get('limit')), before = Number(q.get('beforeId')) || Infinity
      calls.push({ limit, before })
      return { data: rows.filter(p => p.stationId === 1 && p.id < before).sort((a, b) => b.id - a.id).slice(0, limit) }
    } })
    await f.page.loadAll(); rows.push({ ...rows[0], id: 3000 })
    for (let attempt = 0; attempt < 10 && f.page.data.payrollHasMore !== false; attempt++) await f.page.onExpandPayrollHistory()
    assert.equal(f.page.data.payrolls.length, 503)
    assert.equal(new Set(f.page.data.payrolls.map(p => p.id)).size, 503)
    assert.ok(f.page.data.payrolls.every(p => p.stationId === 1 && p.id !== 3000))
    assert.ok(calls.every(c => c.limit === 100)); assert.equal(calls.length, 6)
    assert.equal(f.page.data.payrollHasMore, false); assert.ok(f.page.data.payrollScope.includes('已加载的503张'))
    const count = calls.length; await f.page.onExpandPayrollHistory(); assert.equal(calls.length, count)
  })
  await test('an exact full final page requires one empty read before stopping', async () => {
    const rows = historyRows(200), calls = [], f = fixture({ get: url => {
      if (!url.startsWith('/api/manager/payroll?')) return
      calls.push(url); const q = historyUrl(url).searchParams, before = Number(q.get('beforeId')) || Infinity
      return { data: rows.filter(p => p.id < before).slice(0, Number(q.get('limit'))) }
    } })
    await f.page.loadAll(); await f.page.onExpandPayrollHistory()
    f.page.onTab(tap(null, { tab: 'settle' })); assert.equal(visible(f.page, 'onExpandPayrollHistory').length, 1)
    assert.equal(f.page.data.payrolls.length, 200); assert.equal(f.page.data.payrollHasMore, true)
    await f.page.onExpandPayrollHistory(); assert.equal(f.page.data.payrolls.length, 200)
    assert.equal(f.page.data.payrollHasMore, false); assert.ok(f.page.data.payrollScope.includes('历史末尾'))
    await f.page.onExpandPayrollHistory(); assert.equal(calls.length, 3)
  })
  await test('append deduplicates overlaps and preserves the already displayed newer status', async () => {
    const rows = historyRows(200); rows[0].status = 3; rows[0].statusText = '已发放'
    const f = fixture({ get: url => {
      if (!url.startsWith('/api/manager/payroll?')) return
      const before = Number(historyUrl(url).searchParams.get('beforeId'))
      if (!before) return { data: rows.slice(0, 100) }
      if (before === 101) return { data: [{ ...rows[0], status: 1 }, ...rows.slice(100, 199)] }
      return { data: rows.slice(199) }
    } })
    await f.page.loadAll(); await f.page.onExpandPayrollHistory(); await f.page.onExpandPayrollHistory()
    assert.equal(f.page.data.payrolls.length, 200); assert.equal(f.page.data.payrolls[0].status, 3)
    assert.equal(new Set(f.page.data.payrolls.map(p => p.id)).size, 200)
  })
  await test('a failed continuation preserves records and cursor, retries the exact request and blocks double clicks', async () => {
    const rows = historyRows(150), pending = deferred(), calls = []; let fail = true
    const f = fixture({ get: url => {
      if (!url.startsWith('/api/manager/payroll?')) return
      calls.push(url)
      if (!historyUrl(url).searchParams.has('beforeId')) return { data: rows.slice(0, 100) }
      if (fail) return Promise.reject(Error('更早记录读取失败'))
      return pending.promise
    } })
    await f.page.loadAll(); await f.page.onExpandPayrollHistory()
    assert.equal(f.page.data.payrolls.length, 100); assert.equal(f.page.data.payrollBeforeId, 51)
    f.page.onTab(tap(null, { tab: 'settle' }))
    assert.ok(f.page.data.payrollMoreError); assert.equal(f.page.data.payrollHasMore, true)
    fail = false; const retry = f.page.onExpandPayrollHistory(); await f.page.onExpandPayrollHistory()
    assert.equal(calls.length, 3); assert.equal(calls[1], calls[2]); assert.equal(f.page.data.payrollLoadingMore, true)
    assert.ok(disabled(visible(f.page, 'onExpandPayrollHistory')[0], f.page))
    pending.resolve({ data: rows.slice(100) }); await retry
    assert.equal(f.page.data.payrolls.length, 150); assert.equal(f.page.data.payrollMoreError, '')
    assert.equal(f.page.data.payrollLoadingMore, false)
    assert.equal(visible(f.page, 'onExpandPayrollHistory').length, 0)
  })
  for (const outcome of ['success', 'failure']) await test('refresh wins over a late continuation ' + outcome, async () => {
    const rows = historyRows(200), late = deferred(); let fresh = false
    // The old page never requests this promise; keep the red baseline's unused rejection observed.
    late.promise.catch(() => {})
    const f = fixture({ get: url => {
      if (!url.startsWith('/api/manager/payroll?')) return
      if (historyUrl(url).searchParams.has('beforeId')) return late.promise
      return { data: fresh ? [{ ...rows[0], id: 900 }] : rows.slice(0, 100) }
    } })
    await f.page.loadAll(); const old = f.page.onExpandPayrollHistory(); fresh = true; await f.page.loadAll()
    const toasts = f.wx.__calls.toast.length
    if (outcome === 'success') late.resolve({ data: rows.slice(100) }); else late.reject(Error('过期失败'))
    await old
    assert.deepEqual(f.page.data.payrolls.map(p => p.id), [900]); assert.equal(f.page.data.payrollMoreError, '')
    assert.equal(f.page.data.payrollLoadingMore, false); assert.equal(f.wx.__calls.toast.length, toasts)
  })
  await test('filter changes during continuation apply the current keyword and date to appended rows', async () => {
    const rows = historyRows(101), late = deferred(), f = fixture({ get: url => {
      if (!url.startsWith('/api/manager/payroll?')) return
      return historyUrl(url).searchParams.has('beforeId') ? late.promise : { data: rows.slice(0, 100) }
    } })
    await f.page.loadAll(); const old = f.page.onExpandPayrollHistory()
    f.page.onPayrollSearch(input('', '员工#8')); f.page.onPayrollFilterDate(input('payrollStart', '2026-09-30'))
    late.resolve({ data: [{ ...rows[100], staffId: 8 }] }); await old
    assert.equal(f.page.data.payrollKeyword, '员工#8'); assert.equal(f.page.data.payrollStart, '2026-09-30')
    assert.deepEqual(f.page.data.filteredPayrolls.map(p => p.id), [1])
    f.page.onClearPayrollFilter(); assert.equal(f.page.data.filteredPayrolls.length, 101)
  })
  for (const change of ['station', 'login']) await test('a ' + change + ' change clears old history and ignores its late page', async () => {
    const rows = historyRows(200), late = deferred(); let fresh = false
    const f = fixture({ get: url => {
      if (!url.startsWith('/api/manager/payroll?')) return
      if (historyUrl(url).searchParams.has('beforeId')) return late.promise
      return { data: fresh ? [{ ...rows[0], id: 800, stationId: change === 'station' ? 2 : 1 }] : rows.slice(0, 100) }
    } })
    await f.page.loadAll(); const old = f.page.onExpandPayrollHistory(); fresh = true
    if (change === 'station') f.app.globalData.userInfo.stationId = 2; else f.app._loginGeneration = 1
    f.page.onShow(); assert.equal(f.page.data.payrolls.length, 0); await turn()
    late.resolve({ data: rows.slice(100) }); await old
    assert.deepEqual(f.page.data.payrolls.map(p => p.id), [800]); assert.equal(f.page.data.payrollBeforeId, 800)
  })
  await test('unload makes late history callbacks inert and ends the loading indicator', async () => {
    const rows = historyRows(200), late = deferred(), f = fixture({ get: url => {
      if (!url.startsWith('/api/manager/payroll?')) return
      return historyUrl(url).searchParams.has('beforeId') ? late.promise : { data: rows.slice(0, 100) }
    } })
    await f.page.loadAll(); const old = f.page.onExpandPayrollHistory(); f.page.onUnload()
    let updates = 0; const set = f.page.setData; f.page.setData = (...args) => { updates++; set(...args) }
    late.resolve({ data: rows.slice(100) }); await old
    assert.equal(updates, 0); assert.equal(f.page.data.payrollLoadingMore, false)
  })
  await test('invalid ids or nonadvancing pages preserve the original retry boundary', async () => {
    const rows = historyRows(200); let response = [{ ...rows[0], id: 0 }]
    const f = fixture({ get: url => {
      if (!url.startsWith('/api/manager/payroll?')) return
      return { data: historyUrl(url).searchParams.has('beforeId') ? response : rows.slice(0, 100) }
    } })
    await f.page.loadAll()
    for (const bad of [[{ ...rows[0], id: 0 }], rows.slice(0, 100), { list: rows.slice(100) }]) {
      response = bad; await f.page.onExpandPayrollHistory()
      assert.equal(f.page.data.payrolls.length, 100); assert.equal(f.page.data.payrollBeforeId, 101)
      assert.ok(f.page.data.payrollMoreError); assert.equal(f.page.data.payrollHasMore, true)
    }
    response = rows.slice(100); await f.page.onExpandPayrollHistory(); assert.equal(f.page.data.payrolls.length, 200)
  })
  clearTimeout(watchdog)
  console.log(`payroll redesign: ${passed} passed, ${failed} failed`)
  if (failed) process.exitCode = 1
  else console.log('AQUAFLOW_SUITE_OK ' + passed)
}
module.exports = { fixture, tree, tap, PAGE, ROOT }
if (require.main === module) run().catch(e => { console.error(e.stack); process.exitCode = 1 })
