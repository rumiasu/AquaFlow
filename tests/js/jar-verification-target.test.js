'use strict'

// Check real CLI selection against independent ZIP fixtures; no real configuration is read.
const assert = require('assert')
const fs = require('fs')
const path = require('path')
const os = require('os')
const { spawnSync } = require('child_process')
const script = path.resolve(__dirname, '../../scripts/check-jar-no-local-config.js')
const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'aquaflow-jar-target-'))
let assertions = 0
function check(fn) { fn(); assertions++ }
function run(args) {
  const result = spawnSync(process.execPath, [script, ...args], { encoding: 'utf8', cwd: temp })
  assert.ifError(result.error)
  return result
}
try {
  const clean = path.join(temp, 'clean with spaces.jar')
  const contaminated = path.join(temp, 'contaminated.jar')
  fs.writeFileSync(clean, Buffer.from('UEsDBBQAAAAAAGxZQ10AAAAAAAAAAAAAAAAgAAAAQk9PVC1JTkYvY2xhc3Nlcy9hcHBsaWNhdGlvbi55bWxQSwMEFAAAAAAAbFlDXQAAAAAAAAAAAAAAACUAAABCT09ULUlORi9jbGFzc2VzL2FwcGxpY2F0aW9uLXByb2QueW1sUEsBAhQAFAAAAAAAbFlDXQAAAAAAAAAAAAAAACAAAAAAAAAAAAAAAIABAAAAAEJPT1QtSU5GL2NsYXNzZXMvYXBwbGljYXRpb24ueW1sUEsBAhQAFAAAAAAAbFlDXQAAAAAAAAAAAAAAACUAAAAAAAAAAAAAAIABPgAAAEJPT1QtSU5GL2NsYXNzZXMvYXBwbGljYXRpb24tcHJvZC55bWxQSwUGAAAAAAIAAgChAAAAgQAAAAAA', 'base64'))
  fs.writeFileSync(contaminated, Buffer.from('UEsDBBQAAAAAAGxZQ10AAAAAAAAAAAAAAAAgAAAAQk9PVC1JTkYvY2xhc3Nlcy9hcHBsaWNhdGlvbi55bWxQSwMEFAAAAAAAbFlDXQAAAAAAAAAAAAAAACUAAABCT09ULUlORi9jbGFzc2VzL2FwcGxpY2F0aW9uLXByb2QueW1sUEsDBBQAAAAAAGxZQ10tLzmdEAAAABAAAAAmAAAAQk9PVC1JTkYvY2xhc3Nlcy9hcHBsaWNhdGlvbi1sb2NhbC55bWxwbGFjZWhvbGRlciBvbmx5UEsBAhQAFAAAAAAAbFlDXQAAAAAAAAAAAAAAACAAAAAAAAAAAAAAAIABAAAAAEJPT1QtSU5GL2NsYXNzZXMvYXBwbGljYXRpb24ueW1sUEsBAhQAFAAAAAAAbFlDXQAAAAAAAAAAAAAAACUAAAAAAAAAAAAAAIABPgAAAEJPT1QtSU5GL2NsYXNzZXMvYXBwbGljYXRpb24tcHJvZC55bWxQSwECFAAUAAAAAABsWUNdLS85nRAAAAAQAAAAJgAAAAAAAAAAAAAAgAGBAAAAQk9PVC1JTkYvY2xhc3Nlcy9hcHBsaWNhdGlvbi1sb2NhbC55bWxQSwUGAAAAAAMAAwD1AAAA1QAAAAAA', 'base64'))
  check(() => { const r = run(['--jar', clean]); assert.equal(r.status, 0); assert.ok(r.stdout.includes(clean)) })
  check(() => { const r = run(['--jar', contaminated]); assert.equal(r.status, 1); assert.ok(r.stderr.includes('application-local.yml')) })
  check(() => assert.equal(run(['--jar', path.join(temp, 'missing.jar')]).status, 1))
  check(() => assert.equal(run(['--jar', temp]).status, 1))
  check(() => assert.equal(run(['--jar']).status, 1))
  check(() => assert.equal(run(['--unknown', clean]).status, 1))
  check(() => assert.equal(run(['--jar', clean, '--extra']).status, 1))
  const source = fs.readFileSync(contaminated)
  const eocd = source.lastIndexOf(Buffer.from('504b0506', 'hex'))
  const directory = source.readUInt32LE(eocd + 16)
  const second = directory + 46 + source.readUInt16LE(directory + 28)
    + source.readUInt16LE(directory + 30) + source.readUInt16LE(directory + 32)
  function rejectArchive(label, mutate) {
    check(() => {
      const bytes = Buffer.from(source)
      const changed = mutate(bytes) || bytes
      const target = path.join(temp, label + '.jar')
      fs.writeFileSync(target, changed)
      const result = run(['--jar', target])
      assert.equal(result.status, 1, label)
      assert.ok(result.stderr.includes('无法可信解析 JAR'), label)
      assert.ok(!result.stdout.includes('✓ 发布物不含本地开发配置'), label)
    })
  }
  // Independent byte mutations test early exits, bounded reads and complete-directory accounting.
  rejectArchive('first-signature', b => { b.writeUInt32LE(0, directory) })
  rejectArchive('later-signature', b => { b.writeUInt32LE(0, second) })
  rejectArchive('too-few-entries', b => { b.writeUInt16LE(2, eocd + 8); b.writeUInt16LE(2, eocd + 10) })
  rejectArchive('too-many-entries', b => { b.writeUInt16LE(4, eocd + 8); b.writeUInt16LE(4, eocd + 10) })
  rejectArchive('entry-count-mismatch', b => { b.writeUInt16LE(2, eocd + 8) })
  rejectArchive('directory-size-small', b => { b.writeUInt32LE(b.readUInt32LE(eocd + 12) - 1, eocd + 12) })
  rejectArchive('directory-size-large', b => { b.writeUInt32LE(b.readUInt32LE(eocd + 12) + 1, eocd + 12) })
  rejectArchive('directory-offset-outside', b => { b.writeUInt32LE(b.length + 100, eocd + 16) })
  rejectArchive('name-truncated', b => { b.writeUInt16LE(0xffff, directory + 28) })
  rejectArchive('extra-truncated', b => { b.writeUInt16LE(0xffff, directory + 30) })
  rejectArchive('entry-comment-truncated', b => { b.writeUInt16LE(0xffff, directory + 32) })
  rejectArchive('empty-name', b => { b.writeUInt16LE(0, directory + 28) })
  rejectArchive('eocd-comment-truncated', b => { b.writeUInt16LE(1, eocd + 20) })
  rejectArchive('eocd-missing', b => b.subarray(0, eocd))
  rejectArchive('eocd-truncated', b => b.subarray(0, b.length - 1))
  rejectArchive('trailing-data', b => Buffer.concat([b, Buffer.from([1])]))
  rejectArchive('entry-header-truncated', b => {
    const changed = Buffer.concat([b.subarray(0, directory + 20), b.subarray(eocd)])
    changed.writeUInt32LE(20, directory + 20 + 12)
    return changed
  })
  rejectArchive('zip64-eocd', b => { b.writeUInt16LE(0xffff, eocd + 8); b.writeUInt16LE(0xffff, eocd + 10) })
  rejectArchive('zip64-entry', b => { b.writeUInt32LE(0xffffffff, directory + 20) })
  rejectArchive('multiple-disks', b => { b.writeUInt16LE(1, eocd + 4) })
  rejectArchive('cross-disk-entry', b => { b.writeUInt16LE(1, directory + 34) })
  rejectArchive('encrypted-entry', b => { b.writeUInt16LE(1, directory + 8) })
  rejectArchive('unsupported-compression', b => { b.writeUInt16LE(99, directory + 10) })
  rejectArchive('ambiguous-name-encoding', b => { b[directory + 46] = 0xff })
  rejectArchive('invalid-utf8', b => { b.writeUInt16LE(0x0800, directory + 8); b[directory + 46] = 0xff })
  rejectArchive('nul-name', b => { b[directory + 46] = 0 })
  check(() => {
    const cleanBytes = fs.readFileSync(clean)
    const cleanEocd = cleanBytes.length - 22
    // A valid ZIP comment may contain EOCD signatures, including a fake empty EOCD at its end.
    const comment = Buffer.alloc(32)
    comment.writeUInt32LE(0x06054b50, 10)
    cleanBytes.writeUInt16LE(comment.length, cleanEocd + 20)
    const target = path.join(temp, 'comment-with-signature.jar')
    fs.writeFileSync(target, Buffer.concat([cleanBytes, comment]))
    assert.equal(run(['--jar', target]).status, 0)
  })
} finally {
  // Remove only this suite's newly created directory under the OS temporary root.
  assert.ok(path.resolve(temp).startsWith(path.resolve(os.tmpdir()) + path.sep))
  fs.rmSync(temp, { recursive: true })
}
console.log('AQUAFLOW_SUITE_OK ' + assertions)
