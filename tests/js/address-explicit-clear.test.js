'use strict'
const assert = require('assert')
const { fixture, event } = require('./ua-customer-profile-visible-fixture')
const { armWatchdog } = require('./harness')
const finish = armWatchdog(20000)
const row = () => ({ id: 5, name: '合成收件人', phone: '13800000000', province: '', city: '', district: '', label: '', detail: '合成旧门牌', floor: 6, hasElevator: 0, lat: 30, lng: 120, isDefault: 1 })
async function page(updateAddress) {
  const f = fixture('edit', { api: { getAddressDetail: async () => ({ data: row() }), updateAddress } })
  f.p.onLoad({ id: 5 }); await new Promise(setImmediate); return f
}
async function run() {
  let sent
  const f = await page(async (id, p) => { sent = p; return { data: { ...row(), ...p, id } } })
  f.p.onInputChange(event('floor', '')); f.p.setData({ 'formData.hasElevator': null })
  f.p.onInputChange(event('detail', '手填新地点')); await f.p.onSubmit()
  assert.equal(sent.clearFloor, true); assert.equal(sent.clearElevator, true); assert.equal(sent.clearCoordinates, true)
  assert.equal(sent.floor, null); assert.equal(sent.hasElevator, null); assert.equal(sent.lat, null); assert.equal(sent.lng, null)
  assert.equal(f.p.data.saveSucceeded, true)
  const z = await page(async (id, p) => { sent = p; return { data: { ...row(), ...p, id } } })
  z.p.onInputChange(event('floor', '0')); await z.p.onSubmit()
  assert.equal(sent.floor, 0); assert.equal(sent.hasElevator, 0)
  assert.equal(sent.clearFloor, false); assert.equal(sent.clearElevator, false); assert.equal(sent.clearCoordinates, false)
  assert.equal(z.p.data.saveSucceeded, true)
  for (const response of [undefined, {}, { data: null }, { data: row() }, { data: { ...row(), floor: null, id: 99 } }]) {
    const bad = await page(async () => response); bad.p.onInputChange(event('floor', '')); await bad.p.onSubmit()
    assert.equal(bad.p.data.saveSucceeded, false); assert.match(bad.p.data.saveMessage, /未确认|未收到/)
    assert.equal(bad.p.data.formData.floor, '')
  }
  let writes = 0
  const old = await page(async () => { writes++; throw Object.assign(Error('接口不存在'), { code: 404, businessRejected: true }) })
  old.p.onInputChange(event('floor', '')); await old.p.onSubmit()
  assert.equal(writes, 1); assert.equal(old.p.data.saveSucceeded, false); assert.match(old.p.data.saveMessage, /服务需更新/)
  // The real API wrapper must choose the versioned route, with no legacy fallback write.
  const Module = require('module'), path = require('path'), original = Module._load
  let route
  Module._load = function (request) {
    if (request === '../utils/request') return { put: async url => { route = url; return { data: {} } } }
    return original.apply(this, arguments)
  }
  try {
    const file = path.resolve(__dirname, '../../miniapp-user/api/address.js'); delete require.cache[file]
    await require(file).updateAddress(5, { clearFloor: true }); assert.equal(route, '/api/addresses/5/profile')
  } finally { Module._load = original }
  console.log('AQUAFLOW_SUITE_OK 9'); finish()
}
run().catch(e => { finish(); console.error(e); process.exitCode = 1 })
