const assert = require('assert')
const Module = require('module')
const { armWatchdog } = require('./harness')
const done = armWatchdog(), calls = []
let mode = 'https', current = true, readCallback
global.wx = { getFileSystemManager: () => ({ readFile: options => { readCallback = options } }) }
const realLoad = Module._load
Module._load = function (name, parent, main) {
  if (name === './request') return { get: async () => ({ data: { exists: false } }), request: async options => { calls.push(options); return { data: { exists: true } } } }
  if (name === '../config/api') return { getTransportConfig: () => ({ mode }) }
  if (name === './token') return { captureSession: () => ({}), isCurrentSession: () => current }
  return realLoad.apply(this, arguments)
}
const photo = require('../../miniapp-user/utils/claim-photo')
Module._load = realLoad
const png = () => new Uint8Array([137,80,78,71,13,10,26,10,1]).buffer
;(async () => {
  let work = photo.upload('sample.png', 3, 'claim-a'); readCallback.success({ data: png() }); await work
  assert.equal(calls[0].filePath, 'sample.png'); assert.deepStrictEqual(calls[0].formData, { stationId: 3, clientToken: 'claim-a' })
  mode = 'cloudContainer'; work = photo.upload('sample.png', 3, 'claim-a'); readCallback.success({ data: png() }); await work
  assert(calls[1].data instanceof ArrayBuffer); assert.match(calls[1].header['Content-Type'], /^multipart\/form-data; boundary=/)
  const text = Buffer.from(calls[1].data).toString('latin1'); assert(text.includes('name="stationId"')); assert(text.includes('claim-a'))
  work = photo.upload('sample.png', 3, 'claim-a'); current = false; readCallback.success({ data: png() })
  await assert.rejects(work, /登录状态/); assert.equal(calls.length, 2)
  current = true; work = photo.upload('sample.png', 3, 'claim-a'); readCallback.success({ data: new Uint8Array([1,2,3]).buffer })
  await assert.rejects(work, /请选择/); assert.equal(calls.length, 2)
  done(); console.log('AQUAFLOW_SUITE_OK 4')
})().catch(error => { done(); console.error(error); process.exitCode = 1 })
