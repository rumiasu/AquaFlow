'use strict'
const assert = require('node:assert/strict')
const { stationText, stationName } = require('../../miniapp-user/utils/station-view')
const { fixture } = require('./customer-home-recovery-view.test')
const { loadPage, createWx, createApp } = require('./harness')
const tests = [], test = (name, run) => tests.push({ name, run })
for (const name of ['AquaFlow Service Station', 'ABC社区水站', 'SPRING WATER 24', 'Station 7']) test('preserves a genuine station name: ' + name, async () => {
  const source = { id: 7, name }, f = fixture(); f.wx.setStorageSync('selectedStation', source)
  f.page.loadData = async () => {}; await f.page.checkStation()
  assert.equal(f.page.data.currentStationName, name); assert.equal(f.page.data.currentStation.name, name)
  assert.equal(f.wx.getStorageSync('selectedStation').name, name); assert.equal(f.page.data.currentStationId, 7)
})
for (const text of ['request:fail timeout', 'connect ECONNREFUSED 127.0.0.1:8080', 'TypeError: Cannot read property name', '[object Object]', 'undefined', 'HTTP 503 Service Unavailable']) test('technical name stays a visible read problem, not a missing station: ' + text, async () => {
  const source = { id: 7, name: text }, f = fixture(); f.wx.setStorageSync('selectedStation', source)
  f.page.loadData = async () => {}; await f.page.checkStation()
  assert.equal(f.page.data.currentStationName, '水站名称暂未加载'); assert.equal(f.page.data.currentStationId, 7)
  assert.equal(f.page.data.currentStation.name, text); assert.equal(f.wx.getStorageSync('selectedStation').name, text)
})
test('public chooser labels are sanitized without changing response names or IDs', async () => {
  const source = [{ id: 1, status: 1, name: 'request:fail timeout' }, { id: 2, status: 1, name: 'ABC Water Station' }]
  const f = fixture({ stations: async () => ({ code: 0, data: source }) }); await f.page.loadStationList()
  assert.equal(f.page.data.stationList[0].displayName, '水站名称暂未加载')
  assert.equal(f.page.data.stationList[1].displayName, 'ABC Water Station')
  assert.equal(source[0].name, 'request:fail timeout'); assert.equal(source[0].displayName, undefined)
})
test('technical station notes remain explicit read errors while real English notes are preserved', async () => {
  const wx = createWx(), app = createApp()
  const p = loadPage('miniapp-user/pages/home/index.js', { wx, app, stubs: { 'api/station': {
    getPublicStations: async () => ({ code: 0, data: [] }),
    getStationStatus: async () => ({ code: 0, data: { statusText: '正常营业', note: 'request:fail timeout', customerHint: 'Closed for lunch' } })
  } } })
  p.setData({ currentStationId: 7 }); await p.loadStationStatus(7)
  assert.equal(p.data.stationStatusText, '正常营业')
  assert.equal(p.data.stationStatusNote, '水站说明暂未加载，请稍后重试')
  assert.equal(p.data.stationStatusHint, 'Closed for lunch')
  assert.equal(stationName(null), '水站名称暂未加载'); assert.equal(stationText('配送稍有延迟', ''), '配送稍有延迟')
})
;(async () => {
  const timer = setTimeout(() => { console.error('STATION_VIEW_TIMEOUT'); process.exit(1) }, 30000); let failed = 0
  try { for (const t of tests) { try { await t.run(); console.log('PASS ' + t.name) } catch (e) { failed++; console.error('FAIL ' + t.name + ': ' + e.stack) } }
    if (failed) process.exitCode = 1; else console.log('AQUAFLOW_SUITE_OK ' + tests.length)
  } finally { clearTimeout(timer) }
})().catch(e => { console.error(e); process.exitCode = 1 })
