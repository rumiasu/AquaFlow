const { request, get } = require('./request')
const { getTransportConfig } = require('../config/api')
const { createMultipart } = require('./multipart-body')
const { captureSession, isCurrentSession } = require('./token')

function read(path) {
  return new Promise((resolve, reject) => {
    try {
      wx.getFileSystemManager().readFile({ filePath: path, success: r => {
        const bytes = r.data
        if (!(bytes instanceof ArrayBuffer) || !bytes.byteLength || bytes.byteLength > 5 * 1024 * 1024) return reject(Error('请选择不超过5MB的照片'))
        const b = new Uint8Array(bytes)
        let ext = ''
        if (b.length >= 3 && b[0] === 255 && b[1] === 216 && b[2] === 255) ext = 'jpg'
        else if (b.length >= 8 && [137,80,78,71,13,10,26,10].every((v,i) => b[i] === v)) ext = 'png'
        else if (b.length >= 12 && [82,73,70,70].every((v,i) => b[i] === v) && [87,69,66,80].every((v,i) => b[8+i] === v)) ext = 'webp'
        if (!ext) return reject(Error('请选择 JPG、PNG 或 WebP 照片'))
        resolve({ bytes, ext })
      }, fail: () => reject(Error('照片无法读取，请重新选择')) })
    } catch (_) { reject(Error('照片无法读取，请重新选择')) }
  })
}
function receipt(res) {
  if (!res || !res.data || typeof res.data.exists !== 'boolean') throw Error('照片结果未确认，请重试')
  return res.data
}
const lookup = async (stationId, clientToken) => receipt(await get('/api/asset-claims/photo', { stationId, clientToken }))
async function upload(path, stationId, clientToken) {
  const session = captureSession(), image = await read(path), fields = { stationId, clientToken }
  if (!isCurrentSession(session)) throw Error('登录状态已变化，请重新操作')
  let options = { url: '/api/asset-claims/photo', method: 'POST', filePath: path, name: 'file', formData: fields }
  if (getTransportConfig().mode === 'cloudContainer') {
    const multipart = createMultipart({ fields, bytes: image.bytes, filename: 'photo.' + image.ext,
      boundary: 'AquaFlow_' + Date.now() + '_' + Math.random().toString(36).slice(2), maxBodyBytes: 5 * 1024 * 1024 + 65536 })
    options = { url: options.url, method: 'POST', data: multipart.body, header: { 'Content-Type': multipart.contentType } }
  }
  if (!isCurrentSession(session)) throw Error('登录状态已变化，请重新操作')
  const result = receipt(await request(options))
  if (!result.exists) throw Error('照片未保存，请重试')
  return result
}
module.exports = { lookup, upload }
