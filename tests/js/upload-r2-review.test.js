'use strict'

const assert = require('assert')
const fs = require('fs')
const path = require('path')
const os = require('os')
const { spawnSync } = require('child_process')
const { flush, ok, observe, ROOT } = require('./cloud-upload.mock.test')
const { pageFixture, unknownFromPage, event, image } = require('./page-recovery.test')
const { select, finalRead } = require('./persistence-page-boundaries.test')
const KEY = 'aq_delivery_uploadRecovery_v1'
const tests = []
const test = (name, run) => tests.push({ name, run })

for (const kind of ['complete', 'adjustment']) {
  test(kind + ' refuses an acknowledged storage write that silently discards the marker', async () => {
    const f = pageFixture(kind)
    const write = f.wx.setStorageSync
    f.wx.setStorageSync = (key, value) => {
      const registry = JSON.parse(value)
      if (Object.values(registry.owners).some(b => Object.values(b.cases).some(rows => rows.length))) return
      write(key, value)
    }
    f.p[f.pick]()
    if (f.picks.length) {
      f.picks[0].success({ tempFilePaths: ['/synthetic.png'] }); await flush()
      if (f.stats.length) { f.read(); await flush() }
    }
    assert.equal(f.calls.length, 0)
  })
}

test('a selected image and an ordinary confirmation cannot resolve a different final object', async () => {
  const f = pageFixture('complete'), id = await unknownFromPage(f)
  await select(f, id)
  const before = f.storage.get(KEY)
  await finalRead(f)
  f.calls[2].resolve(ok([{ ...image, objectName: 'private/order/42/different.png' }]))
  await flush()
  assert.equal(f.storage.get(KEY), before)
  assert.equal(f.api.uploadCases().length, 1)
  assert.equal(f.p.data.uploadRecoverySelection, null)
})

for (const dimension of ['account', 'station', 'order', 'entry', 'customer', 'route']) {
  test('valid unknown metadata keeps an independent ' + dimension + ' upload available', async () => {
    const kind = dimension === 'product' ? 'product' : dimension === 'customer' ? 'adjustment' : 'complete'
    const storage = new Map(), f = pageFixture(kind, storage)
    const id = await unknownFromPage(f)
    const r = pageFixture(kind, storage, (name, source) => dimension === 'route' && ['upload', 'transport'].includes(name)
      ? source.replaceAll('getTransportConfig()', "({ ...getTransportConfig(), env: 'different-env' })") : source)
    if (dimension === 'account') { r.app.globalData.userInfo.staffId = 2; r.app._loginGeneration++ }
    if (dimension === 'station') { r.app.globalData.userInfo.stationId = 2; r.app._loginGeneration++ }
    if (dimension === 'order') r.p.data.orderId = 43
    if (dimension === 'product') { r.p.data.isAdd = false; r.p.data.editId = 43 }
    if (dimension === 'customer') { r.p.data.customerId = 43; r.p._customerSeq++ }
    if (dimension === 'entry') {
      const result = observe(r.api.upload(r.args({ url: '/api/order-images/upload', formData: { orderId: 42, type: 1 } })))
      await flush(); r.read(); await flush(); r.calls[0].resolve(ok({ ...image, type: 1 })); await flush(); assert(result.value)
    } else {
      r.p[r.pick](); r.picks[0].success({ tempFilePaths: ['/second.png'] }); await flush(); r.read(); await flush()
      assert.equal(r.calls.length, 1)
      r.calls[0].resolve(ok(kind === 'complete' ? { ...image, orderId: r.p.data.orderId } : '/synthetic-independent'))
      await flush()
    }
    assert.equal(r.calls.filter(call => call.options.method === 'POST').length, 1)
    assert(f.api.uploadCases().some(row => row.id === id))
  })
}

for (const kind of ['complete', 'detail']) {
  for (const outcome of ['network', 'empty', 'cancel']) {
    test(kind + ' clears the previous image when another recovery read ends in ' + outcome, async () => {
      const f = pageFixture(kind), id = await unknownFromPage(f)
      await select(f, id)
      assert(f.p.data.uploadRecoverySelection)
      const before = f.storage.get(KEY)
      const r = observe(f.p.onUploadRecover(event(id)))
      await flush()
      if (outcome === 'network') f.calls[2].reject(Error('synthetic read failure'))
      else f.calls[2].resolve(ok(outcome === 'empty' ? [] : [image]))
      await flush()
      if (outcome === 'cancel') { f.actions[1].fail({ errMsg: 'cancel' }); await flush() }
      assert(!r.error)
      assert.equal(f.p.data.uploadRecoverySelection, null)
      assert.equal(f.p._uploadRecoverySelection, null)
      assert.equal(f.storage.get(KEY), before)
      const n = f.calls.length
      await f.p.onUploadConfirmSelected()
      assert.equal(f.calls.length, n)
      assert.equal(f.modals.length, 0)
    })
  }
  test(kind + ' clears the selected image when final verification GET fails', async () => {
    const f = pageFixture(kind), id = await unknownFromPage(f)
    await select(f, id)
    const before = f.storage.get(KEY), r = await finalRead(f)
    f.calls[2].reject(Error('synthetic final read failure'))
    await flush()
    assert(!r.error)
    assert.equal(f.p.data.uploadRecoverySelection, null)
    assert.equal(f.storage.get(KEY), before)
    assert.equal(f.api.uploadCases().length, 1)
  })
}

const corruptions = [
  ['case-index-renamed', (value, owner, b) => { const k = Object.keys(b.cases)[0]; b.cases['wrong-index'] = b.cases[k]; delete b.cases[k] }],
  ['case-index-wrong-owner', (value, owner, b) => { const k = Object.keys(b.cases)[0], parts = JSON.parse(k); parts[0] = 'wrong-owner'; b.cases[JSON.stringify(parts)] = b.cases[k]; delete b.cases[k] }],
  ['owner-index-renamed', (value, owner, b) => { value.owners['wrong-owner'] = b; delete value.owners[owner] }],
  ['case-index-wrong-target', (value, owner, b) => { const k = Object.keys(b.cases)[0], parts = JSON.parse(k), inner = JSON.parse(parts[1]); inner[1] = 'wrong-target'; parts[1] = JSON.stringify(inner); b.cases[JSON.stringify(parts)] = b.cases[k]; delete b.cases[k] }],
  ['duplicate-case-id', (value, owner, b) => { const rows = Object.values(b.cases)[0]; rows.push({ ...rows[0] }) }]
]
for (const kind of ['complete', 'adjustment']) {
  const variants = corruptions.concat(kind === 'complete' ? [] : [
    ['current-index-missing', (value, owner, b) => { b.current = {} }],
    ['current-index-wrong-target', (value, owner, b) => { const k = Object.keys(b.current)[0]; b.current['wrong-target'] = b.current[k]; delete b.current[k] }]
  ])
  for (const [name, change] of variants) {
    test(kind + ' rejects ' + name + ' after restart without rewriting storage or dispatching', async () => {
      const storage = new Map(), f = pageFixture(kind, storage)
      await unknownFromPage(f)
      const value = JSON.parse(storage.get(KEY)), owner = Object.keys(value.owners)[0]
      change(value, owner, value.owners[owner])
      const raw = JSON.stringify(value)
      storage.set(KEY, raw)
      const r = pageFixture(kind, storage)
      assert.throws(() => r.api.uploadCases(), error => error.uploadStateFailure === true)
      r.p.refreshUploadRecovery()
      assert.equal(r.p.data.uploadRecoveryReadFailed, true)
      assert.equal(r.p.data.uploadRecoverySelection, null)
      r.p[r.pick]()
      if (r.picks.length) { r.picks[0].success({ tempFilePaths: ['/second.png'] }); await flush() }
      assert.equal(r.stats.length + r.calls.length, 0)
      assert.equal(storage.get(KEY), raw)
    })
  }
}

const bindings = {
  complete: ['onUploadRecover', 'onUploadPreviewSelected', 'onUploadConfirmSelected'],
  detail: ['onUploadRecover', 'onUploadPreviewSelected', 'onUploadConfirmSelected'],
  adjustment: ['onUploadRecover', 'onUploadResumeDraft', 'onUploadNewDraft']
}
test('all 9 retained evidence WXML bindings exist on the actual registered Page objects', async () => {
  let count = 0
  for (const [kind, names] of Object.entries(bindings)) {
    const f = pageFixture(kind)
    for (const name of names) { assert.equal(typeof f.p[name], 'function', kind + ':' + name); count++ }
  }
  assert.equal(count, 9)
})

test('wrapper binding audit detects missing implementation, unused imports and unrelated wrappers', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'aquaflow-r2-bindings-'))
  try {
    const helper = path.join(dir, 'miniapp-delivery/utils/upload-recovery-page.js')
    const page = path.join(dir, 'miniapp-delivery/pages/order/complete.js')
    fs.mkdirSync(path.dirname(helper), { recursive: true })
    fs.mkdirSync(path.dirname(page), { recursive: true })
    const source = fs.readFileSync(path.join(ROOT, 'miniapp-delivery/utils/upload-recovery-page.js'), 'utf8')
    const registered = "const { wrapUploadRecovery } = require('../../utils/upload-recovery-page')\nPage({\n  ...wrapUploadRecovery({ data: {} }, 'order')\n})\n"
    fs.writeFileSync(helper, source)
    fs.writeFileSync(page, registered)
    fs.writeFileSync(page.replace('.js', '.wxml'), '<button bindtap="onUploadRecover"/>')
    const audit = () => spawnSync('python3', [path.join(ROOT, 'audit_wxml_handlers.py'), '--root', dir], { encoding: 'utf8' })
    assert.equal(audit().status, 0)
    fs.writeFileSync(helper, source.replace('page.onUploadRecover =', 'page.noRecovery ='))
    assert.equal(audit().status, 1)
    fs.writeFileSync(helper, source)
    fs.writeFileSync(page, registered.replace('...wrapUploadRecovery(', '...unrelatedWrapper('))
    assert.equal(audit().status, 1)
    fs.writeFileSync(page, registered.replace("require('../../utils/upload-recovery-page')", "require('../../utils/unrelated')"))
    assert.equal(audit().status, 1)
  } finally { fs.rmSync(dir, { recursive: true, force: true }) }
})

async function main() {
  let passed = 0, failed = 0
  const timer = setTimeout(() => { console.error('R2_REVIEW_TIMEOUT'); process.exit(1) }, 45000)
  try {
    for (const t of tests) {
      try { await t.run(); passed++; console.log('PASS ' + t.name) }
      catch (error) { failed++; console.error('FAIL ' + t.name + '\n' + error.stack) }
    }
    console.log('R2_REVIEW_RESULT ' + JSON.stringify({ passed, failed }))
    assert.equal(failed, 0)
    console.log('AQUAFLOW_SUITE_OK ' + passed)
  } finally { clearTimeout(timer) }
}
if (require.main === module) main().catch(error => { console.error(error.stack); process.exitCode = 1 })
