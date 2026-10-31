'use strict'
// Deterministic multipart encoding. Backend continues to enforce authorization and validation.
const TYPES = { jpg: 'image/jpeg', jpeg: 'image/jpeg', png: 'image/png', webp: 'image/webp' }
function utf8(text) {
  const encoded = encodeURIComponent(text), bytes = []
  for (let i = 0; i < encoded.length; i++) {
    if (encoded[i] === '%') { bytes.push(parseInt(encoded.slice(i + 1, i + 3), 16)); i += 2 }
    else bytes.push(encoded.charCodeAt(i))
  }
  return new Uint8Array(bytes)
}
function includes(bytes, needle) {
  outer: for (let i = 0; i <= bytes.length - needle.length; i++) {
    for (let j = 0; j < needle.length; j++) if (bytes[i + j] !== needle[j]) continue outer
    return true
  }
  return false
}
function createMultipart({ fields = {}, filename, bytes, boundary, maxBodyBytes }) {
  if (!Number.isInteger(maxBodyBytes) || maxBodyBytes <= 0) throw Error('explicit body limit required')
  if (typeof boundary !== 'string' || !/^[A-Za-z0-9_-]{1,70}$/.test(boundary)) throw Error('invalid boundary')
  if (typeof filename !== 'string' || !/^[A-Za-z0-9_.-]{1,120}$/.test(filename)) throw Error('invalid filename')
  const ext = filename.split('.').pop().toLowerCase(), mime = TYPES[ext]
  if (!mime) throw Error('unsupported image extension')
  const file = bytes instanceof ArrayBuffer ? new Uint8Array(bytes)
    : bytes instanceof Uint8Array ? new Uint8Array(bytes.buffer, bytes.byteOffset, bytes.byteLength) : null
  if (!file || !file.length) throw Error('nonempty binary image required')
  if (!fields || typeof fields !== 'object' || Array.isArray(fields)) throw Error('invalid fields')
  const collision = utf8('--' + boundary)
  if (includes(file, collision)) throw Error('boundary collides with file data')
  const chunks = []
  for (const key of Object.keys(fields)) {
    if (!/^[A-Za-z][A-Za-z0-9_]{0,63}$/.test(key)) throw Error('invalid field name')
    const value = fields[key]
    if (!['string', 'number', 'boolean'].includes(typeof value) || (typeof value === 'number' && !Number.isFinite(value))) throw Error('invalid field value')
    const body = utf8(String(value))
    if (includes(body, collision)) throw Error('boundary collides with field data')
    chunks.push(utf8('--' + boundary + '\r\nContent-Disposition: form-data; name="' + key + '"\r\n\r\n'), body, utf8('\r\n'))
  }
  chunks.push(utf8('--' + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="' + filename + '"\r\nContent-Type: ' + mime + '\r\n\r\n'), file, utf8('\r\n--' + boundary + '--\r\n'))
  const length = chunks.reduce((n, chunk) => n + chunk.length, 0)
  if (length > maxBodyBytes) throw Error('multipart body exceeds explicit limit')
  const merged = new Uint8Array(length)
  let at = 0
  for (const chunk of chunks) { merged.set(chunk, at); at += chunk.length }
  return { body: merged.buffer, contentType: 'multipart/form-data; boundary=' + boundary, byteLength: length }
}
module.exports = { createMultipart }
