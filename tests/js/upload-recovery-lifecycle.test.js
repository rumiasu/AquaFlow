'use strict'
// The first nine cases preserve the independent review's checks (Library libfile_1ce6ad2160e08191ad1bd797dce9e81b v0).
// Review text SHA256: aea0b2cbfa2d7c7e030dbebeb9fc3f1259369992b3367c567315954e8c81c69d.
const assert = require('assert')
const { fixture, flush, ok, observe } = require('./cloud-upload.mock.test')
const { pageFixture, unknownFromPage, event, image } = require('./page-recovery.test')
const { select, finalRead } = require('./persistence-page-boundaries.test')
const KEY = 'aq_delivery_uploadRecovery_v1'
const tests = []
const test = (name, run) => tests.push({name, run})
test('two unknown images: verifying one retains the other lock and blocks another POST', async () => {
  const f = fixture()
  const options = filePath => f.args({url:'/api/order-images/upload',filePath,formData:{orderId:42,type:3}})
  const a = observe(f.api.upload(options('/synthetic.png'))), b = observe(f.api.upload(options('/second.png')))
  await flush(); f.read(0); f.read(1); await flush()
  assert.equal(f.calls.length,2)
  f.calls.forEach(c=>c.reject(Error('synthetic timeout'))); await flush()
  assert(a.error.uploadOutcomeUnknown && b.error.uploadOutcomeUnknown)
  const cases = f.api.uploadCases(); assert.equal(cases.length,2)
  const r = observe(f.api.recoverOrderUpload(options('/synthetic.png'),{caseId:cases[0].id,resolution:'present',confirmedSameImage:true,imageId:101,objectName:image.objectName}))
  await flush(); f.calls[2].resolve(ok([image])); await flush()
  assert(r.value); assert.equal(r.value.remainingUnknown,1)
  assert.equal(f.api.uploadCases()[0].id,cases[1].id)
  await assert.rejects(f.api.upload(options('/synthetic.png')),e=>e.uploadOutcomeUnknown)
  assert.equal(f.calls.length,3); assert.equal(f.stats.length,2)
})
test('valid-shaped marker alteration during acknowledged write prevents dispatch and preserves a prior case', async () => {
  const f = pageFixture('complete'), oldId = await unknownFromPage(f)
  const write = f.wx.setStorageSync
  f.wx.setStorageSync = (key, raw) => {
    const value = JSON.parse(raw)
    for (const owner of Object.values(value.owners)) for (const rows of Object.values(owner.cases))
      for (const row of rows) if (row.fields.orderId === 43) row.fingerprint = '0'.repeat(64)
    write(key,JSON.stringify(value))
  }
  f.p.data.orderId = 43
  f.p.onAddFloorPhoto(); f.picks[1].success({tempFilePaths:['/second.png']}); await flush(); f.read(1); await flush()
  assert.equal(f.calls.length,1); assert(f.api.uploadCases().some(c=>c.id===oldId))
})
test('readback exception after marker write prevents a POST; restart retains the written lock', async () => {
  const shared = new Map(), f = pageFixture('complete',shared)
  const write = f.wx.setStorageSync, read = f.wx.getStorageSync
  let marker = false
  f.wx.setStorageSync = (key,raw) => {write(key,raw);marker=true}
  f.wx.getStorageSync = key => {if(marker)throw Error('synthetic readback failure');return read(key)}
  f.p.onAddFloorPhoto(); f.picks[0].success({tempFilePaths:['/synthetic.png']}); await flush(); f.read(); await flush()
  assert.equal(f.calls.length,0)
  const r = pageFixture('complete',shared)
  assert.equal(r.api.uploadCases().length,1)
  r.p.onAddFloorPhoto(); r.picks[0].success({tempFilePaths:['/second.png']}); await flush()
  assert.equal(r.stats.length+r.calls.length,0)
})
test('silent cleanup discard after verified GET cannot unlock, announce success or keep a confirmable selection', async () => {
  const f = pageFixture('detail'), id = await unknownFromPage(f)
  await select(f,id); const r = await finalRead(f), raw = f.storage.get(KEY)
  f.wx.setStorageSync = ()=>{}
  f.calls[2].resolve(ok([image])); await flush()
  assert(!r.error); assert.equal(f.storage.get(KEY),raw); assert.equal(f.api.uploadCases().length,1)
  assert.equal(f.p.data.uploadRecoverySelection,null)
  assert(!f.toast.some(t=>t.title==='已核实原图片'))
})
for (const kind of ['product','adjustment']) test(kind + ' two unknown drafts survive restart, resume individually, and permit a third independent draft', async () => {
  const shared = new Map(), f = pageFixture(kind,shared), first = await unknownFromPage(f), d1 = f.p._uploadDraftId
  f.p.onUploadNewDraft(); f.modals[0].success({confirm:true}); await flush()
  const nextPick = f.picks.length
  f.p[f.pick](); f.picks[nextPick].success({tempFilePaths:['/second.png']}); await flush(); f.read(f.stats.length-1); await flush()
  f.calls[f.calls.length-1].resolve({statusCode:200,data:{code:1}}); await flush()
  assert.equal(f.api.uploadCases().length,2)
  const second = f.api.uploadCases().find(c=>c.id!==first).id, d2 = f.p._uploadDraftId
  assert.notEqual(d1,d2); assert.notEqual(first,second)
  const r = pageFixture(kind,shared); r.p.refreshUploadRecovery()
  assert.equal(r.api.uploadCases().length,2)
  for (const [id,draft] of [[first,d1],[second,d2]]) {
    r.p.onUploadResumeDraft(event(id)); assert.equal(r.p._uploadDraftId,draft)
    const n = r.picks.length; r.p[r.pick](); r.picks[n].success({tempFilePaths:['/synthetic.png']}); await flush()
    assert.equal(r.stats.length+r.calls.length,0)
  }
  r.p.onUploadNewDraft(); r.modals[0].success({confirm:true}); await flush()
  const n = r.picks.length; r.p[r.pick](); r.picks[n].success({tempFilePaths:['/synthetic.png']}); await flush(); r.read(); await flush()
  assert.equal(r.calls.length,1); r.calls[0].resolve(ok('/synthetic-third-draft')); await flush()
  assert.equal(r.api.uploadCases().length,2)
  assert.deepEqual(r.api.uploadCases().map(c=>c.id).sort(),[first,second].sort())
})
test('same staff moving station during final verification keeps original case and hides the selected URL', async () => {
  const f = pageFixture('complete'), id = await unknownFromPage(f)
  await select(f,id); const r = await finalRead(f), raw = f.storage.get(KEY)
  f.app.globalData.userInfo.stationId = 2
  f.calls[2].resolve(ok([image])); await flush(); f.p.refreshUploadRecovery()
  assert(!r.error); assert.equal(f.storage.get(KEY),raw)
  assert.equal(f.p.data.uploadRecoverySelection,null); assert.equal(f.api.uploadCases().length,0)
  f.app.globalData.userInfo.stationId = 1
  assert.equal(f.api.uploadCases()[0].id,id)
})
for (const kind of ['complete','detail']) test(kind+' failed final GET after hide/show clears the old image before it can be confirmed again', async () => {
  const f = pageFixture(kind), id = await unknownFromPage(f)
  await select(f,id); const r = await finalRead(f), raw = f.storage.get(KEY)
  f.p.onHide(); f.calls[2].reject(Error('synthetic final GET failure')); await flush(); f.p.onShow()
  assert(!r.error); assert.equal(f.storage.get(KEY),raw); assert.equal(f.api.uploadCases().length,1)
  const stale = f.p.data.uploadRecoverySelection
  const next = observe(f.p.onUploadConfirmSelected()); await flush()
  console.log('HIDDEN_GET_EVIDENCE '+JSON.stringify({kind,selectionAfterShow:stale,unknownCases:f.api.uploadCases().length,storageUnchanged:f.storage.get(KEY)===raw,posts:f.calls.filter(c=>c.options.method==='POST').length,newConfirmationModal:f.modals.length===2}))
  f.modals[f.modals.length-1].success({confirm:false}); await flush(); assert(!next.error)
  assert.equal(stale,null)
})

for (const kind of ['complete', 'detail']) {
  test(kind + ' consumes the confirmed choice before final GET while retaining the durable case', async () => {
    const f = pageFixture(kind), id = await unknownFromPage(f)
    await select(f, id)
    const raw = f.storage.get(KEY), r = await finalRead(f)
    assert.equal(f.calls[2].options.method, 'GET')
    assert.equal(f.p._uploadRecoverySelection, null)
    assert.equal(f.p.data.uploadRecoverySelection, null)
    assert.equal(f.p.data.uploadRecovering, true)
    assert.equal(f.storage.get(KEY), raw)
    f.calls[2].reject(Error('synthetic final GET failure')); await flush()
    assert(!r.error); assert.equal(f.p.data.uploadRecovering, false)
  })
  test(kind + ' normal preview hide/show and cancelled confirmation retain an unconsumed choice', async () => {
    const f = pageFixture(kind), id = await unknownFromPage(f)
    await select(f, id)
    const choice = f.p._uploadRecoverySelection, raw = f.storage.get(KEY)
    f.p.onUploadPreviewSelected(); f.p.onHide(); f.p.onShow(); await flush()
    assert.equal(f.previews.length, 1)
    assert.strictEqual(f.p._uploadRecoverySelection, choice)
    const readsBeforeCancel = f.calls.length
    const r = observe(f.p.onUploadConfirmSelected()); await flush()
    f.modals[0].success({ confirm: false }); await flush()
    assert(!r.error); assert.strictEqual(f.p._uploadRecoverySelection, choice)
    assert.equal(f.p.data.uploadRecoverySelection.imageId, image.id)
    assert.equal(f.calls.length, readsBeforeCancel); assert.equal(f.storage.get(KEY), raw)
  })
  test(kind + ' current successful final GET resolves only the original case and restores the page state', async () => {
    const f = pageFixture(kind), id = await unknownFromPage(f)
    await select(f, id); const r = await finalRead(f)
    f.calls[2].resolve(ok([image])); await flush()
    assert(!r.error); assert.equal(f.api.uploadCases().length, 0)
    assert.equal(f.p.data.uploadRecoverySelection, null)
    assert.equal(f.p.data.uploadRecovering, false)
    assert(f.p.data.floorPhotos.includes(image.url))
    assert(f.toast.some(row => row.title === '已核实原图片'))
    assert.equal(f.calls.filter(c => c.options.method === 'POST').length, 1)
  })
  for (const outcome of ['failure', 'success']) {
    test(kind + ' late final GET ' + outcome + ' after hide/show cannot clear a newer selected image or release unknown', async () => {
      const f = pageFixture(kind), id = await unknownFromPage(f)
      await select(f, id); const r = await finalRead(f), raw = f.storage.get(KEY)
      f.p.onHide(); f.p.onShow(); await flush()
      assert.equal(f.p.data.uploadRecoverySelection, null)
      const newerImage = { ...image, id: 202, objectName: 'private/order/42/newer.png', url: '/synthetic-newer' }
      await select(f, id, [newerImage])
      const newer = f.p._uploadRecoverySelection
      assert.equal(newer.imageId, 202)
      if (outcome === 'failure') f.calls[2].reject(Error('synthetic old GET failure'))
      else f.calls[2].resolve(ok([image]))
      await flush()
      assert(!r.error); assert.strictEqual(f.p._uploadRecoverySelection, newer)
      assert.equal(f.p.data.uploadRecoverySelection.imageId, 202)
      assert.equal(f.p.data.uploadRecovering, false)
      assert.equal(f.storage.get(KEY), raw); assert.equal(f.api.uploadCases().length, 1)
      assert(!f.toast.some(row => row.title === '已核实原图片'))
      assert.equal(f.calls.filter(c => c.options.method === 'POST').length, 1)
    })
  }
  test(kind + ' late failed verification of the old order preserves the new order choice and both cases', async () => {
    const f = pageFixture(kind), id = await unknownFromPage(f)
    await select(f, id); const r = await finalRead(f)
    f.p.onHide(); f.p.data.orderId = 43; f.p.onShow(); await flush()
    const newId = await unknownFromPage(f, '/second.png')
    await select(f, newId, [{ ...image, id: 203, orderId: 43, objectName: 'private/order/43/new.png', url: '/synthetic-order-43' }])
    const choice = f.p._uploadRecoverySelection, raw = f.storage.get(KEY)
    f.calls[2].reject(Error('synthetic old order GET failure')); await flush()
    assert(!r.error); assert.strictEqual(f.p._uploadRecoverySelection, choice)
    assert.equal(f.p.data.uploadRecoverySelection.imageId, 203)
    assert.equal(f.storage.get(KEY), raw)
    assert.deepEqual(f.api.uploadCases().map(row => row.id).sort(), [id, newId].sort())
    assert.equal(f.p.data.uploadRecoveryCases.length, 1)
    assert.equal(f.p.data.uploadRecoveryCases[0].fields.orderId, 43)
    assert.equal(f.calls.filter(c => c.options.method === 'POST').length, 2)
  })
}

async function main() {
  let passed=0,failed=0
  const watchdog=setTimeout(()=>{console.error('INDEPENDENT_TIMEOUT');process.exit(1)},30000)
  try {
    for(const t of tests) {try {await t.run();passed++;console.log('PASS '+t.name)}catch(e){failed++;console.error('FAIL '+t.name+'\n'+e.stack)}}
    console.log('INDEPENDENT_RESULT '+JSON.stringify({passed,failed}))
    if(failed)process.exitCode=1
    else { console.log('AQUAFLOW_INDEPENDENT_OK '+passed); console.log('AQUAFLOW_SUITE_OK '+passed) }
  } finally {clearTimeout(watchdog)}
}
main().catch(e=>{console.error(e.stack);process.exitCode=1})
