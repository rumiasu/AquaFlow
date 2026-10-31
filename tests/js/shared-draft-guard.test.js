'use strict'
const assert = require('assert'), path = require('path'), fs = require('fs')
for (const app of ['miniapp-user', 'miniapp-delivery']) {
  const { createDraftGuard } = require(path.resolve(__dirname, '../../', app, 'utils/draft-guard.js'))
  const saved = new Map(), scope = { app, actor: 7, station: 1, route: 'pages/template/index', entity: 'new', generation: 1 }
  let failedWrite = false, failedRead = false
  const storage = { getStorageSync: key => { if (failedRead) throw Error('synthetic'); return saved.get(key) },
    setStorageSync: (key, value) => { saved.set(key, value); if (failedWrite) throw Error('lost acknowledgement') }, removeStorageSync: key => saved.delete(key) }
  const guard = createDraftGuard(() => scope, storage), token = guard.begin(), original = { quantity: '', cost: '0', note: '原输入', enabled: false }
  assert.equal(guard.read(token).state, 'empty')
  assert.equal(guard.save(token, original).state, 'saved')
  original.note = 'changed later'; assert.equal(guard.read(token).draft.note, '原输入')
  scope.generation++; assert.equal(guard.save(token, { note: 'late callback' }).state, 'stale')
  const resumed = guard.begin(); assert.equal(guard.read(resumed).draft.quantity, ''); assert.equal(guard.read(resumed).draft.cost, '0')
  scope.actor = 8; assert.equal(guard.read(guard.begin()).state, 'empty'); assert.equal(guard.clear(resumed).state, 'stale')
  scope.actor = 7; scope.station = 2; assert.equal(guard.read(guard.begin()).state, 'empty')
  scope.station = 1; const back = guard.begin(); assert.equal(guard.read(back).draft.enabled, false)
  failedWrite = true; assert.equal(guard.save(back, { note: 'result unknown' }).state, 'unknown')
  failedWrite = false; assert.equal(guard.read(back).draft.note, 'result unknown', 'do not erase a write whose acknowledgement was lost')
  failedRead = true; assert.equal(guard.read(back).state, 'unknown'); failedRead = false
  saved.set(back.key, { version: 100 }); assert.equal(guard.read(back).state, 'unknown'); assert(saved.has(back.key))
  saved.set('aquaflow:order-write-recovery:original', { requestId: 'keep' }); guard.clear(back)
  assert(saved.has('aquaflow:order-write-recovery:original'))
  scope.actor = null; assert.equal(guard.begin(), null); assert.equal(guard.save(null, {}).state, 'stale')
  console.log('PASS ' + app + ' scoped persistent UI drafts preserve empty/zero, original data and unknown results across navigation/session changes')
}
assert.equal(fs.readFileSync(path.resolve(__dirname, '../../miniapp-user/utils/draft-guard.js'), 'utf8'), fs.readFileSync(path.resolve(__dirname, '../../miniapp-delivery/utils/draft-guard.js'), 'utf8'))
console.log('AQUAFLOW_SUITE_OK 2')
