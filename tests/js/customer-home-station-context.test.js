const assert = require('assert')
const { loadPage, createWx, createApp, armWatchdog } = require('./harness')

const deferred = () => {
  let resolve, reject
  const promise = new Promise((ok, fail) => { resolve = ok; reject = fail })
  return { promise, resolve, reject }
}
const station = id => ({ id, name: 'SYNTH station ' + id, status: 1 })
const status = text => ({ code: 0, data: { statusText: text, note: text, customerHint: text } })
function fixture(api = {}) {
  const wx = createWx(), app = createApp(), loaded = [], publicReads = []
  const page = loadPage('miniapp-user/pages/home/index.js', { wx, app, stubs: {
    'api/order': { getMyLatestStation: api.restore || (async () => ({ code: 0, data: null })) },
    'api/station': {
      getPublicStations: async () => { publicReads.push(true); return api.list ? api.list() : { code: 0, data: [] } },
      getStationStatus: api.status || (async () => status('SYNTH current'))
    }
  } })
  // Keep the actual discovery/selection handlers; unrelated overview reads are observed at their entry.
  page.loadData = async () => { loaded.push(page.data.currentStationId); page.setData({ state: 'ready', loading: false }) }
  return { page, wx, app, loaded, publicReads }
}
const tests = []
const test = (name, run) => tests.push({ name, run })

test('late automatic restore cannot replace the station the customer just selected', async () => {
  const old = deferred(), t = fixture({ restore: () => old.promise })
  const pending = t.page.checkStation()
  t.page.setData({ stationList: [station(2)] })
  await t.page.confirmStation(2)
  old.resolve({ code: 0, data: { stationId: 1, stationName: 'SYNTH old' } })
  await pending
  assert.equal(t.wx.getStorageSync('selectedStation').id, 2)
  assert.equal(t.page.data.currentStationId, 2)
  assert.deepStrictEqual(t.loaded, [2])
})
test('latest automatic restore wins when two refreshes complete out of order', async () => {
  const old = deferred(); let calls = 0
  const t = fixture({ restore: () => ++calls === 1 ? old.promise : Promise.resolve({ code: 0, data: { stationId: 2 } }) })
  const pending = t.page.checkStation(); await t.page.checkStation()
  old.resolve({ code: 0, data: { stationId: 1 } }); await pending
  assert.equal(t.wx.getStorageSync('selectedStation').id, 2)
  assert.deepStrictEqual(t.loaded, [2])
})
test('a previous customer restore cannot persist a station after identity changes', async () => {
  const old = deferred(), t = fixture({ restore: () => old.promise })
  const pending = t.page.checkStation(); t.app.globalData.customerId = 8
  old.resolve({ code: 0, data: { stationId: 1 } }); await pending
  assert.equal(t.wx.getStorageSync('selectedStation'), '')
  assert.deepStrictEqual(t.loaded, [])
  assert.equal(t.publicReads.length, 0)
})
for (const lifecycle of ['onHide', 'onUnload']) {
  test(lifecycle + ' invalidates automatic restore before it can write the order station', async () => {
    const old = deferred(), t = fixture({ restore: () => old.promise })
    const pending = t.page.checkStation(); t.page[lifecycle]()
    old.resolve({ code: 0, data: { stationId: 1 } }); await pending
    assert.equal(t.wx.getStorageSync('selectedStation'), '')
    assert.deepStrictEqual(t.loaded, [])
    assert.equal(t.publicReads.length, 0)
  })
}
test('late restore failure cannot reopen no-station state after a successful selection', async () => {
  const old = deferred(), t = fixture({ restore: () => old.promise })
  const pending = t.page.checkStation()
  t.page.setData({ stationList: [station(2)] }); await t.page.confirmStation(2)
  old.reject(new Error('SYNTH old restore failure')); await pending
  assert.equal(t.page.data.state, 'ready')
  assert.equal(t.publicReads.length, 0)
})
test('valid automatic restore still persists the authoritative station and loads it once', async () => {
  const t = fixture({ restore: async () => ({ code: 0, data: { stationId: 3, stationName: 'SYNTH restored' } }) })
  await t.page.checkStation()
  assert.equal(t.wx.getStorageSync('selectedStation').id, 3)
  assert.deepStrictEqual(t.loaded, [3])
})
for (const change of ['identity', 'hide', 'refresh']) {
  test('pending station-switch dialog cannot write after ' + change, async () => {
    const t = fixture(); let confirm
    t.wx.setStorageSync('selectedStation', station(1))
    t.page.setData({ currentStationId: 1, currentStation: station(1), stationList: [station(2)] })
    t.wx.showModal = options => {
      t.wx.__calls.modal.push(options)
      if (!confirm) confirm = options.success
      else options.success({ confirm: false })
    }
    const pending = t.page.confirmStation(2)
    if (change === 'identity') t.app.globalData.customerId = 8
    if (change === 'hide') t.page.onHide()
    if (change === 'refresh') await t.page.checkStation()
    confirm({ confirm: true }); await pending
    assert.equal(t.wx.getStorageSync('selectedStation').id, 1)
    assert.equal(t.wx.__calls.modal.length, 1)
  })
}
test('confirmed station switch still follows both dialogs and loads the chosen station', async () => {
  const t = fixture()
  t.wx.setStorageSync('selectedStation', station(1))
  t.page.setData({ currentStationId: 1, currentStation: station(1), stationList: [station(2)] })
  await t.page.confirmStation(2)
  assert.equal(t.wx.__calls.modal.length, 2)
  assert.equal(t.wx.getStorageSync('selectedStation').id, 2)
  assert.deepStrictEqual(t.loaded, [2])
})
test('latest public list wins and late failure cannot replace it or raise an obsolete toast', async () => {
  const old = deferred(); let calls = 0
  const t = fixture({ list: () => ++calls === 1 ? old.promise : Promise.resolve({ code: 0, data: [station(2)] }) })
  const pending = t.page.loadStationList(); await t.page.loadStationList()
  old.reject(new Error('SYNTH old list failure')); await pending
  assert.deepStrictEqual(t.page.data.stationList.map(s => s.id), [2])
  assert.equal(t.page.data.stationListError, '')
  assert.equal(t.wx.__calls.toast.length, 0)
})
test('failed public refresh clears stale selectable rows and an empty successful retry stays empty', async () => {
  let fail = true
  const t = fixture({ list: async () => { if (fail) throw new Error('SYNTH list unavailable'); return { code: 0, data: [] } } })
  t.page.setData({ stationList: [station(1)] }); await t.page.loadStationList()
  assert.deepStrictEqual(t.page.data.stationList, [])
  assert.ok(t.page.data.stationListError)
  fail = false; await t.page.loadStationList()
  assert.deepStrictEqual(t.page.data.stationList, [])
  assert.equal(t.page.data.stationListError, '')
})
test('public list response from a previous login cannot replace the current list', async () => {
  const old = deferred(), t = fixture({ list: () => old.promise })
  const pending = t.page.loadStationList(); t.app.globalData.customerId = 8
  old.resolve({ code: 0, data: [station(1)] }); await pending
  assert.deepStrictEqual(t.page.data.stationList, [])
})
test('switching station clears the prior status before the next status is known', async () => {
  const next = deferred(), t = fixture({ status: () => next.promise })
  t.page.setData({ currentStationId: 2, stationStatusText: 'SYNTH previous', stationStatusHint: 'SYNTH previous' })
  const pending = t.page.loadStationStatus(2)
  assert.equal(t.page.data.stationStatusText, '')
  assert.equal(t.page.data.stationStatusHint, '')
  next.resolve(status('SYNTH next')); await pending
  assert.equal(t.page.data.stationStatusText, 'SYNTH next')
})
for (const reject of [false, true]) {
  test('late station A status ' + (reject ? 'failure' : 'success') + ' cannot overwrite station B', async () => {
    const old = deferred(), t = fixture({ status: id => id === 1 ? old.promise : Promise.resolve(status('SYNTH B')) })
    t.page.setData({ currentStationId: 1 }); const pending = t.page.loadStationStatus(1)
    t.page.setData({ currentStationId: 2 }); await t.page.loadStationStatus(2)
    if (reject) old.reject(new Error('SYNTH old status failure')); else old.resolve(status('SYNTH A'))
    await pending
    assert.equal(t.page.data.stationStatusText, 'SYNTH B')
  })
}
test('latest same-station status refresh wins over the previous response', async () => {
  const old = deferred(); let calls = 0
  const t = fixture({ status: () => ++calls === 1 ? old.promise : Promise.resolve(status('SYNTH fresh')) })
  t.page.setData({ currentStationId: 1 }); const pending = t.page.loadStationStatus(1)
  await t.page.loadStationStatus(1); old.resolve(status('SYNTH stale')); await pending
  assert.equal(t.page.data.stationStatusText, 'SYNTH fresh')
})
test('hidden page ignores pending status and public-list responses', async () => {
  const oldStatus = deferred(), oldList = deferred()
  const t = fixture({ status: () => oldStatus.promise, list: () => oldList.promise })
  t.page.setData({ currentStationId: 1 })
  const pending = [t.page.loadStationStatus(1), t.page.loadStationList()]
  t.page.onHide(); oldStatus.resolve(status('SYNTH hidden')); oldList.resolve({ code: 0, data: [station(1)] })
  await Promise.all(pending)
  assert.equal(t.page.data.stationStatusText, '')
  assert.deepStrictEqual(t.page.data.stationList, [])
})

;(async () => {
  const done = armWatchdog(30000)
  let passed = 0, failed = 0
  try {
    for (const item of tests) {
      try { await item.run(); passed++; console.log('PASS ' + item.name) }
      catch (error) { failed++; console.error('FAIL ' + item.name + ': ' + error.message) }
    }
    console.log('home station context: ' + passed + ' passed, ' + failed + ' failed')
    if (failed) process.exitCode = 1
    else console.log('AQUAFLOW_SUITE_OK ' + passed)
  } finally { done() }
})().catch(error => { console.error(error); process.exitCode = 1 })
