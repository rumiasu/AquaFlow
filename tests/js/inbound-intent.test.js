'use strict'
const assert = require('node:assert/strict'), fs = require('node:fs'), vm = require('node:vm'), path = require('node:path')
const store = new Map(); let failWrite = false, discardWrite = false, failRead = false, failRemove = false
const settings = { mode: 'cloudContainer', env: 'synthetic-env', service: 'synthetic-service' }
function fixture() {
  const module = { exports: {} }, wx = {
    getStorageSync: key => { if (failRead) throw Error('synthetic read failure'); return store.get(key) || '' },
    setStorageSync: (key, value) => { if (failWrite) throw Error('synthetic failure'); if (!discardWrite) store.set(key, JSON.parse(JSON.stringify(value))) },
    removeStorageSync: key => { if (failRemove) throw Error('synthetic remove failure'); store.delete(key) }
  }
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../../miniapp-delivery/utils/inbound-intent.js'), 'utf8'), {
    module, wx, Date, Math, require: () => ({ getTransportConfig: () => settings })
  })
  return module.exports
}
const tests = [], test = (name, run) => tests.push({ name, run })
const actor = '1:11', items = [{ productId: 42, quantity: 3 }]
test('original intent survives runtime replacement and changed input does not replace original quantity/key', () => {
  const a = fixture(), first = a.prepare(actor, items), second = fixture().prepare(actor, [{ productId: 42, quantity: 9 }])
  assert.equal(second.clientRequestId, first.clientRequestId); assert.equal(second.items[0].quantity, 3)
})
test('actor, station, product and transport environment remain isolated', () => {
  const a = fixture(), original = a.read(actor, 42)
  assert.equal(a.read('2:11', 42), null); assert.equal(a.read('1:12', 42), null); assert.equal(a.read(actor, 43), null)
  settings.env = 'synthetic-other'; assert.equal(a.read(actor, 42), null); settings.env = 'synthetic-env'
  assert.equal(a.read(actor, 42).clientRequestId, original.clientRequestId)
})
test('only original owner/key/content APPLIED receipt unlocks; JSON field order is irrelevant', () => {
  const a = fixture(), intent = a.read(actor, 42), data = { ...a.payload(intent), stationId: 11, status: 'APPLIED' }
  assert(a.acceptsReceipt(intent, { code: 0, data: { ...data, items: [{ quantity: 3, productId: 42 }] } }))
  for (const invalid of [null, { ...data, stationId: 12 }, { ...data, clientRequestId: 'INB-other-original-key' }, { ...data, status: 'UNKNOWN' }, { ...data, items: [{ productId: 42, quantity: 4 }] }, { ...data, items: [{ productId: '42', quantity: 3 }] }]) assert(!a.acceptsReceipt(intent, { code: 0, data: invalid }))
  assert(!a.acceptsReceipt(intent, { code: 0 })); assert(!a.acceptsReceipt(intent, { code: 1, data }))
})
test('failed or silently discarded first persistence never returns a dispatchable intent', () => {
  const a = fixture(); failWrite = true; assert.throws(() => a.prepare(actor, [{ productId: 77, quantity: 1 }])); failWrite = false
  discardWrite = true; assert.throws(() => a.prepare(actor, [{ productId: 77, quantity: 1 }])); discardWrite = false
})
test('failed read and malformed record fail closed without clearing original bytes', () => {
  const a = fixture(); failRead = true; assert.throws(() => a.read(actor, 42)); failRead = false
  const key = [...store.keys()][0], original = store.get(key); store.set(key, { ...original, items: [{ productId: 42, quantity: -1 }] })
  assert.throws(() => a.read(actor, 42)); assert.equal(store.get(key).items[0].quantity, -1); store.set(key, original)
})
test('cleanup failure keeps the original key and cannot create another physical receipt', () => {
  const a = fixture(), original = a.read(actor, 42); failRemove = true; assert.throws(() => a.clear(original)); failRemove = false
  assert.equal(a.prepare(actor, items).clientRequestId, original.clientRequestId)
  a.clear(original); assert.equal(a.read(actor, 42), null); assert.notEqual(a.prepare(actor, items).clientRequestId, original.clientRequestId)
})
test('invalid or fractional quantity and unsafe ids never create an intent', () => {
  const a = fixture()
  for (const item of [{ productId: 78, quantity: 0 }, { productId: 78, quantity: 1.5 }, { productId: 78, quantity: 2147483648 }, { productId: 0, quantity: 1 }]) assert.throws(() => a.prepare(actor, [item]))
})
test('keyed API uses a separate receipt route so old additive servers cannot ignore the original key', () => {
  const module = { exports: {} }, calls = []
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../../miniapp-delivery/api/station-mgmt.js'), 'utf8'), {
    module, require: id => id.includes('config/api') ? { API: { INVENTORY_INBOUND: '/legacy-inbound', INVENTORY_INBOUND_RECEIPT: '/receipt-inbound' } }
      : { post: (url, body) => calls.push({ url, body }) }
  })
  module.exports.inboundProducts(11, items, 'INB-original-key-0001')
  module.exports.inboundProducts(11, items)
  assert.equal(calls[0].url, '/api/inventory/inbound-with-cost-receipt?stationId=11'); assert.equal(calls[0].body.clientRequestId, 'INB-original-key-0001')
  assert.equal(calls[1].url, '/legacy-inbound?stationId=11'); assert.equal(calls[1].body.clientRequestId, undefined)
})
for (const t of tests) { t.run(); console.log('PASS ' + t.name) }
console.log('AQUAFLOW_SUITE_OK ' + tests.length)
