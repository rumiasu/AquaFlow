const assert = require('assert')
const { pageFixture, unknownFromPage, image } = require('./page-recovery.test')
const { flush, ok } = require('./cloud-upload.mock.test')
;(async () => {
  let count = 0
  for (const kind of ['complete', 'detail', 'adjustment']) {
    const f = pageFixture(kind)
    f.p.refreshUploadRecovery()
    assert.equal(f.p.data.uploadRecoveryCases.length, 0)
    f.p[f.pick](); f.picks[0].success({ tempFilePaths: ['/synthetic.png'] })
    await flush(); f.read(); await flush()
    f.calls[0].resolve(ok(kind === 'adjustment' ? '/synthetic-evidence' : image))
    await flush(); f.p.refreshUploadRecovery()
    assert.equal(f.p.data.uploadRecoveryCases.length, 0)
    assert.equal(f.p.data.uploadRecoveryExpanded, false)
    const failed = pageFixture(kind)
    await unknownFromPage(failed)
    assert.equal(failed.p.data.uploadRecoveryCases.length, 1)
    assert.equal(failed.p.data.uploadRecoveryExpanded, false)
    failed.p.onToggleUploadRecovery()
    assert.equal(failed.p.data.uploadRecoveryExpanded, true)
    assert.equal(failed.p.data.uploadRecoveryCases.length, 1, 'expanding never clears failure evidence')
    console.log('PASS ' + kind + ' successful photos have no verification task; actual unknown stays recoverable')
    count++
  }
  console.log('AQUAFLOW_SUITE_OK ' + count)
})().catch(error => { console.error(error); process.exitCode = 1 })
