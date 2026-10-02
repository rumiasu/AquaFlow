'use strict'
// F-55: invoke production Page methods and real request wrappers with controlled wx/API callbacks.
const assert = require('assert')
const fs = require('fs')
const path = require('path')
const { loadPage, createWx, createApp, armWatchdog, ROOT } = require('./harness')
const done = armWatchdog()
let passed = 0
async function test(name, fn) { await fn(); passed++; console.log('PASS ' + name) }
function deferred() { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
const envelope = data => ({ code: 0, data })
const order = id => ({ id, quantity: 1, returnBucketQty: 1, barrelDiscrepancy: 0, createTime: '2026-10-02T10:00:00', updateTime: '2026-10-02T10:00:00' })
const owed = id => ({ customerId: id, productId: 5, overQty: 2, owedDaysText: '7天', urgent: true })
const notice = (id = 12, content = '真实正文') => ({ id, title: '真实公告', content, createTime: '2026-10-02T10:00:00' })
const specs = [
  { file: 'miniapp-delivery/pages/barrel-records/index', key: 'records', module: 'api/delivery', fn: 'getBarrelRecords', row: order, time: 'updateTime' },
  { file: 'miniapp-delivery/pages/history/index', key: 'orders', module: 'api/delivery', fn: 'getDeliveryHistory', row: order, time: 'createTime' },
  { file: 'miniapp-delivery/pages/transfer/index', key: 'orders', module: 'api/delivery', fn: 'getTransferRecords', row: order, time: 'updateTime' },
  { file: 'miniapp-delivery/pages/station-mgmt/owed-barrels/index', key: 'list', module: 'api/station-mgmt', fn: 'getOwedBarrels', row: owed }
]
function setup(spec, handler = () => envelope([spec.row(1)]), baseline = false) {
  const wx = createWx(), calls = [], state = { allowed: true, redirects: 0, stops: 0 }
  wx.getAccountInfoSync = () => ({ miniProgram: { envVersion: 'develop' } })
  wx.stopPullDownRefresh = () => state.stops++
  const app = createApp({ globalData: { userInfo: { staffId: 8, role: 'DELIVERY', stationId: 2, bindStatus: 'BOUND' } },
    canAccessStationBusiness: () => state.allowed, routeByRole: () => state.redirects++ })
  const page = loadPage((baseline ? 'build/delegated-20261002/page-failure-recovery/baseline/' : '') + spec.file + '.js', {
    wx, app, stubs: { [spec.module]: { [spec.fn]: (...args) => { calls.push(args); return handler(...args) } } }
  })
  return { page, wx, app, calls, state }
}
function setupNotice(handler = () => envelope(notice())) {
  const wx = createWx(), calls = [], state = { stops: 0 }
  wx.getAccountInfoSync = () => ({ miniProgram: { envVersion: 'develop' } })
  wx.stopPullDownRefresh = () => state.stops++
  const page = loadPage('miniapp-user/pages/home/notice.js', { wx, app: createApp(), stubs: {
    'api/notice': { getNoticeDetail: id => { calls.push(id); return handler(id) } }
  } })
  return { page, wx, calls, state }
}
const filter = value => ({ currentTarget: { dataset: { value } } })
async function main() {
  global.__wxConfig = { envVersion: 'develop' }
  for (const s of specs) {
    await test(s.fn + ' starts loading, lifecycle sends once, valid empty is loaded', async () => {
      const gate = deferred(), t = setup(s, () => gate.promise)
      assert.equal(t.page.data.loading, true); assert.equal(t.page.data.loaded, false)
      t.page.onLoad({}); const load = t.page.onShow()
      assert.equal(t.calls.length, 1); assert.equal(t.page.data.loading, true)
      gate.resolve(envelope([])); await load
      assert.equal(t.page.data.loaded, true); assert.equal(t.page.data.error, ''); assert.equal(t.page.data.loading, false)
      assert.equal(t.page.data[s.key].length, 0)
    })
    await test(s.fn + ' network and business rejection stay visible then retry', async () => {
      let fail = true
      const t = setup(s, () => { if (fail) throw Error('无权读取或网络暂不可用'); return envelope([s.row(4)]) })
      await t.page.onShow(); assert.equal(t.page.data.loaded, false); assert.match(t.page.data.error, /无权读取/)
      assert.equal(t.page.data.loading, false); assert.equal(t.page.data[s.key].length, 0)
      fail = false; await t.page.onRetry()
      assert.equal(t.page.data.error, ''); assert.equal(t.page.data.loaded, true); assert.equal(t.page.data[s.key].length, 1)
    })
    await test(s.fn + ' malformed and failed envelopes never become empty success', async () => {
      for (const response of [null, {}, { code: 1, data: [] }, envelope(null), envelope({}), envelope([null]), envelope([{}]), envelope([[]])]) {
        const t = setup(s, () => response); await t.page.onShow()
        assert.equal(t.page.data.loaded, false); assert(t.page.data.error); assert.equal(t.page.data.loading, false)
      }
      if (s.time) for (const value of [{}, 'broken-time']) {
        const t = setup(s, () => envelope([{ ...s.row(1), [s.time]: value }]))
        await t.page.onShow(); assert.equal(t.page.data.loaded, false); assert(t.page.data.error)
      }
    })
    await test(s.fn + ' same-context refresh preserves labeled prior rows', async () => {
      let fail = false
      const t = setup(s, () => { if (fail) throw Error('刷新断网'); return envelope([s.row(6)]) })
      await t.page.onShow(); const previous = JSON.stringify(t.page.data[s.key]); fail = true; await t.page.onRetry()
      assert.equal(JSON.stringify(t.page.data[s.key]), previous); assert.match(t.page.data.error, /刷新失败.*上次成功/)
      assert.equal(t.page.data.loaded, true); assert.equal(t.page.data.loading, false)
    })
    for (const oldFails of [false, true]) await test(s.fn + ' stale ' + (oldFails ? 'failure' : 'success') + ' and finally cannot stop new load', async () => {
      const old = deferred(), current = deferred(); let count = 0
      const t = setup(s, () => ++count === 1 ? old.promise : current.promise)
      const a = t.page.onShow(), b = t.page.onRetry()
      if (oldFails) old.reject(Error('旧错误')); else old.resolve(envelope([s.row(90)]))
      await a; assert.equal(t.page.data.loading, true); assert.equal(t.page.data.loaded, false); assert.equal(t.page.data.error, '')
      current.resolve(envelope([s.row(7)])); await b
      assert.equal(t.page.data.loading, false); assert.equal(t.page.data[s.key][0][s.key === 'list' ? 'customerId' : 'id'], 7)
    })
    await test(s.fn + ' stale arrival after current success cannot overwrite', async () => {
      const old = deferred(); let count = 0
      const t = setup(s, () => ++count === 1 ? old.promise : envelope([s.row(10)]))
      const a = t.page.onShow(); await t.page.onRetry(); const snapshot = JSON.stringify(t.page.data)
      old.resolve(envelope([s.row(99)])); await a; assert.equal(JSON.stringify(t.page.data), snapshot)
    })
    await test(s.fn + ' unload blocks pending response and later retries', async () => {
      const gate = deferred(), t = setup(s, () => gate.promise), load = t.page.onShow()
      t.page.onUnload(); const snapshot = JSON.stringify(t.page.data)
      gate.reject(Error('卸载后错误')); await load; await t.page.onRetry()
      assert.equal(JSON.stringify(t.page.data), snapshot); assert.equal(t.calls.length, 1)
    })
    await test(s.fn + ' every read path keeps existing role guard and ends pull', async () => {
      const t = setup(s); t.state.allowed = false
      await t.page.onShow(); await t.page.onRetry(); await t.page.loadData(); await t.page.onPullDownRefresh()
      if (s.key === 'list') await t.page.onFilter(filter(7))
      assert.equal(t.calls.length, 0); assert(t.state.redirects >= 4); assert.equal(t.state.stops, 1)
      assert.equal(t.page.data.loaded, false)
    })
    await test(s.fn + ' identity/station switch clears rows before failure', async () => {
      let fail = false
      const t = setup(s, () => { if (fail) throw Error('新身份读取失败'); return envelope([s.row(2)]) })
      await t.page.onShow(); t.app.globalData.userInfo.staffId = 9; t.app.globalData.userInfo.stationId = 3; fail = true
      await t.page.onRetry(); assert.equal(t.page.data[s.key].length, 0); assert.equal(t.page.data.loaded, false)
      assert(!t.page.data.error.startsWith('刷新失败'))
    })
    for (const oldFails of [false, true]) await test(s.fn + ' completed changed-context flight recovers without new request ' + oldFails, async () => {
      const gate = deferred(); let n = 0
      const t = setup(s, () => ++n === 1 ? gate.promise : envelope([s.row(3)])), load = t.page.onShow()
      t.app.globalData.userInfo.staffId = 9; t.app.globalData.userInfo.stationId = 3
      if (oldFails) gate.reject(Error('旧身份错误')); else gate.resolve(envelope([s.row(99)]))
      await load
      assert.equal(t.page.data.loading, false); assert.equal(t.page.data.loaded, false); assert.equal(t.page.data[s.key].length, 0)
      assert.match(t.page.data.error, /身份或水站已变化.*重试/); assert(!t.page.data.error.includes('旧身份错误'))
      if (s.key === 'records') assert.equal(t.page.data.totalReturn, 0)
      await t.page.onRetry(); assert.equal(t.page.data.error, ''); assert.equal(t.page.data.loaded, true)
      assert.equal(t.page.data[s.key][0][s.key === 'list' ? 'customerId' : 'id'], 3); assert.equal(t.calls.length, 2)
    })
    for (const oldFails of [false, true]) for (const currentFirst of [false, true]) await test(s.fn + ' context switch protects newer flight ' + oldFails + '/' + currentFirst, async () => {
      const old = deferred(), current = deferred(); let n = 0
      const t = setup(s, () => ++n === 1 ? old.promise : current.promise), a = t.page.onShow()
      t.app.globalData.userInfo.stationId = 3
      const b = t.page.onRetry()
      if (currentFirst) { current.resolve(envelope([s.row(3)])); await b }
      const snapshot = JSON.stringify(t.page.data)
      if (oldFails) old.reject(Error('旧水站错误')); else old.resolve(envelope([s.row(99)]))
      await a; assert.equal(JSON.stringify(t.page.data), snapshot)
      if (!currentFirst) { assert.equal(t.page.data.loading, true); current.resolve(envelope([s.row(3)])); await b }
      assert.equal(t.page.data.error, ''); assert.equal(t.page.data.loading, false)
      assert.equal(t.page.data[s.key][0][s.key === 'list' ? 'customerId' : 'id'], 3)
    })
    await test(s.fn + ' lost access settles latest pull and retry keeps role guard', async () => {
      const gate = deferred(); let n = 0
      const t = setup(s, () => ++n === 1 ? gate.promise : envelope([s.row(3)])), pull = t.page.onPullDownRefresh()
      t.state.allowed = false; gate.reject(Error('失去访问资格的旧错误')); await pull
      assert.equal(t.page.data.loading, false); assert.equal(t.page.data.loaded, false); assert(t.page.data.error)
      assert.equal(t.page.data[s.key].length, 0); assert.equal(t.state.stops, 1)
      await t.page.onRetry(); assert.equal(t.calls.length, 1); assert(t.state.redirects > 0)
      t.state.allowed = true; await t.page.onRetry(); assert.equal(t.page.data.error, ''); assert.equal(t.page.data.loaded, true)
    })
    await test(s.fn + ' completed refresh after identity switch removes previously loaded rows', async () => {
      const gate = deferred(); let n = 0
      const t = setup(s, () => ++n === 2 ? gate.promise : envelope([s.row(n)]))
      await t.page.onShow(); assert.equal(t.page.data.loaded, true)
      const refresh = t.page.onRetry(); assert.equal(t.page.data[s.key].length, 1)
      t.app.globalData.userInfo.staffId = 10; t.app.globalData.userInfo.role = 'STATION_MANAGER'
      gate.resolve(envelope([s.row(99)])); await refresh
      assert.equal(t.page.data[s.key].length, 0); assert.equal(t.page.data.loaded, false)
      assert.equal(t.page.data.loading, false); assert.match(t.page.data.error, /身份或水站已变化/)
      if (s.key === 'records') assert.equal(t.page.data.totalDeliveries, 0)
      await t.page.onRetry(); assert.equal(t.page.data.error, ''); assert.equal(t.page.data[s.key].length, 1)
    })
    await test(s.fn + ' pull failure and unload always stop refresh', async () => {
      const t = setup(s, () => { throw Error('断网') }); await t.page.onPullDownRefresh(); assert.equal(t.state.stops, 1)
      const gate = deferred(), u = setup(s, () => gate.promise), pull = u.page.onPullDownRefresh()
      u.page.onUnload(); assert.equal(u.state.stops, 1); gate.resolve(envelope([])); await pull; assert.equal(u.state.stops, 1)
    })
    await test(s.fn + ' older pull completion cannot stop newer pull', async () => {
      const a = deferred(), b = deferred(); let count = 0
      const t = setup(s, () => ++count === 1 ? a.promise : b.promise)
      const x = t.page.onPullDownRefresh(), y = t.page.onPullDownRefresh()
      a.resolve(envelope([])); await x; assert.equal(t.state.stops, 0); assert.equal(t.page.data.loading, true)
      b.resolve(envelope([])); await y; assert.equal(t.state.stops, 1)
    })
  }
  await test('owed filter switch removes old result and old success/finally cannot overwrite', async () => {
    const gates = new Map([[0, deferred()], [7, deferred()]])
    const s = specs[3], t = setup(s, days => gates.get(days).promise)
    const old = t.page.onShow(), current = t.page.onFilter(filter(7))
    assert.equal(t.page.data.minDays, 7); assert.equal(t.page.data.list.length, 0)
    gates.get(0).resolve(envelope([owed(9)])); await old; assert.equal(t.page.data.loading, true); assert.equal(t.page.data.list.length, 0)
    gates.get(7).reject(Error('新筛选断网')); await current
    assert.equal(t.page.data.list.length, 0); assert.equal(t.page.data.loaded, false); assert.match(t.page.data.error, /新筛选断网/)
    assert.deepStrictEqual(t.calls, [[0], [7]])
  })
  await test('owed old rejection cannot replace successful new filter, selected filter retries error', async () => {
    const old = deferred(); let fails = false
    const t = setup(specs[3], days => days === 0 ? old.promise : fails ? Promise.reject(Error('再次断网')) : envelope([owed(7)]))
    const a = t.page.onShow(); await t.page.onFilter(filter(7)); old.reject(Error('旧筛选失败')); await a
    assert.equal(t.page.data.list[0].customerId, 7); assert.equal(t.page.data.error, '')
    fails = true; await t.page.onRetry(); fails = false; await t.page.onFilter(filter(7)); assert.equal(t.page.data.error, '')
  })
  await test('barrel summary stays original numeric aggregation and clears with identity', async () => {
    const t = setup(specs[0], () => envelope([{ ...order(1), returnBucketQty: 2, barrelDiscrepancy: -1 }, order(2)]))
    await t.page.onShow(); assert.equal(t.page.data.totalDeliveries, 2); assert.equal(t.page.data.totalReturn, 3); assert.equal(t.page.data.totalDiscrepancy, -1)
    t.app.globalData.userInfo.stationId = 4; t.state.allowed = false; await t.page.onRetry(); assert.equal(t.page.data.totalReturn, 0)
  })
  await test('notice missing/invalid id never fetches or shows placeholder', async () => {
    for (const id of [undefined, '', 0, 'abc']) {
      const t = setupNotice(); await t.page.onLoad({ id }); await t.page.onRetry()
      assert.equal(t.calls.length, 0); assert.equal(t.page.data.notice, null); assert.equal(t.page.data.loading, false); assert.match(t.page.data.error, /公告编号/)
    }
  })
  await test('notice initial loading and legal empty/null content preserve actual title', async () => {
    for (const content of ['', null, undefined, '真实正文']) {
      const gate = deferred(), t = setupNotice(() => gate.promise)
      assert.equal(t.page.data.notice, null); const load = t.page.onLoad({ id: 12 }); assert.equal(t.page.data.loading, true)
      gate.resolve(envelope({ ...notice(), content })); await load
      assert.equal(t.page.data.error, ''); assert.equal(t.page.data.notice.title, '真实公告'); assert.equal(t.page.data.notice.content, content == null ? '' : content)
    }
  })
  await test('notice title string follows storage contract without new nonblank gate', async () => {
    const t = setupNotice(() => envelope({ ...notice(), title: '' })); await t.page.onLoad({ id: 12 }); assert.equal(t.page.data.error, '')
  })
  await test('notice failure and invalid payloads remain error, never invented missing-notice claim', async () => {
    for (const response of [null, {}, envelope(null), envelope({}), envelope([]), envelope({ ...notice(), id: 99 }), envelope({ ...notice(), title: {} }), envelope({ ...notice(), content: [] })]) {
      const t = setupNotice(() => response); await t.page.onLoad({ id: 12 })
      assert.equal(t.page.data.notice, null); assert(t.page.data.error); assert(!t.page.data.error.includes('公告不存在')); assert.equal(t.page.data.loading, false)
    }
  })
  await test('notice retry always queries original id despite event or argument', async () => {
    let fail = true
    const t = setupNotice(() => fail ? Promise.reject(Error('网络暂不可用')) : envelope(notice()))
    await t.page.onLoad({ id: 12 }); assert.match(t.page.data.error, /网络/); fail = false
    await t.page.onRetry({ currentTarget: { dataset: { id: 99 } } }); await t.page.loadNotice(99)
    assert.deepStrictEqual(t.calls, ['12', '12', '12']); assert.equal(t.page.data.notice.id, 12)
  })
  await test('notice refresh failure labels real retained detail', async () => {
    let fail = false; const t = setupNotice(() => fail ? Promise.reject(Error('断网')) : envelope(notice()))
    await t.page.onLoad({ id: 12 }); fail = true; await t.page.onRetry(); assert.equal(t.page.data.notice.title, '真实公告'); assert.match(t.page.data.error, /刷新失败.*上次成功/)
  })
  for (const oldFails of [false, true]) await test('notice stale success/failure/finally cannot write newer load ' + oldFails, async () => {
    const old = deferred(), current = deferred(); let n = 0
    const t = setupNotice(() => ++n === 1 ? old.promise : current.promise), a = t.page.onLoad({ id: 12 }), b = t.page.onRetry()
    if (oldFails) old.reject(Error('旧错误')); else old.resolve(envelope(notice()))
    await a; assert.equal(t.page.data.loading, true); assert.equal(t.page.data.notice, null); assert.equal(t.page.data.error, '')
    current.resolve(envelope(notice())); await b; assert.equal(t.page.data.loading, false)
  })
  await test('notice unload and overlapping pull completion do not mutate stale page', async () => {
    const gate = deferred(), t = setupNotice(() => gate.promise), load = t.page.onLoad({ id: 12 }); t.page.onUnload()
    const snapshot = JSON.stringify(t.page.data); gate.resolve(envelope(notice())); await load; await t.page.onRetry(); assert.equal(JSON.stringify(t.page.data), snapshot)
    const a = deferred(), b = deferred(); let n = 0
    const u = setupNotice(() => ++n === 1 ? envelope(notice()) : n === 2 ? a.promise : b.promise)
    await u.page.onLoad({ id: 12 }); const x = u.page.onPullDownRefresh(), y = u.page.onPullDownRefresh()
    a.reject(Error('旧失败')); await x; assert.equal(u.state.stops, 0); u.page.onUnload(); assert.equal(u.state.stops, 1)
    b.resolve(envelope(notice())); await y; assert.equal(u.state.stops, 1)
  })
  await test('notice pull missing id and failure finish', async () => {
    const t = setupNotice(() => Promise.reject(Error('断网'))); await t.page.onLoad({ id: 12 }); await t.page.onPullDownRefresh(); assert.equal(t.state.stops, 1)
    const u = setupNotice(); await u.page.onLoad({}); await u.page.onPullDownRefresh(); assert.equal(u.state.stops, 1)
  })
  // Actual shared wrappers and actual API functions; only wx transport is replaced.
  for (const s of specs) for (const kind of ['business', 'network', 'malformed']) await test(s.fn + ' real wrapper ' + kind, async () => {
    const api = require(path.join(ROOT, 'miniapp-delivery', s.module + '.js'))
    const t = setup(s, (...args) => api[s.fn](...args))
    t.wx.request = opt => kind === 'network' ? opt.fail({ errMsg: 'request:fail fail:time out' })
      : opt.success({ statusCode: 200, data: kind === 'business' ? { code: 1, message: '无权查看此记录' } : envelope(null) })
    await t.page.onShow(); assert.equal(t.page.data.loaded, false); assert(t.page.data.error); assert.equal(t.page.data.loading, false)
    if (kind === 'business') assert.match(t.page.data.error, /无权查看/)
  })
  for (const kind of ['business', 'network', 'malformed']) await test('notice real wrapper ' + kind, async () => {
    const api = require(path.join(ROOT, 'miniapp-user/api/notice.js'))
    const t = setupNotice(id => api.getNoticeDetail(id))
    t.wx.request = opt => kind === 'network' ? opt.fail({ errMsg: 'request:fail fail:time out' })
      : opt.success({ statusCode: 200, data: kind === 'business' ? { code: 1, message: '无权查看此公告' } : envelope(null) })
    await t.page.onLoad({ id: 12 }); assert.equal(t.page.data.notice, null); assert(t.page.data.error)
    if (kind === 'business') assert.match(t.page.data.error, /无权查看/)
  })
  await test('notice explicit business not-found remains a retryable failure, not empty body', async () => {
    const api = require(path.join(ROOT, 'miniapp-user/api/notice.js')), t = setupNotice(id => api.getNoticeDetail(id))
    t.wx.request = opt => opt.success({ statusCode: 200, data: { code: 1, message: '公告不存在' } })
    await t.page.onLoad({ id: 12 }); assert.equal(t.page.data.notice, null); assert.match(t.page.data.error, /公告详情加载失败：公告不存在/)
    assert.equal(t.page.data.canRetry, true)
  })
  await test('templates gate empty state, bind retry, remove notice placeholder, pull enabled', () => {
    for (const s of specs) {
      const wxml = fs.readFileSync(path.join(ROOT, s.file + '.wxml'), 'utf8')
      assert(wxml.includes('loaded && !loading && !error')); assert(wxml.includes('bindtap="onRetry"'))
      assert.equal(JSON.parse(fs.readFileSync(path.join(ROOT, s.file + '.json'), 'utf8')).enablePullDownRefresh, true)
    }
    const wxml = fs.readFileSync(path.join(ROOT, 'miniapp-user/pages/home/notice.wxml'), 'utf8')
    assert(wxml.includes('bindtap="onRetry"')); assert(!wxml.includes('公告不存在'))
  })
  console.log('AQUAFLOW_SUITE_OK ' + passed); done()
}
main().catch(err => { console.error(err); done(); process.exitCode = 1 })
