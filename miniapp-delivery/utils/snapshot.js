// Copy binary bodies as bytes; object enumeration and JSON stringify lose ArrayBuffer data.
function snapshot(value) {
  if (Object.prototype.toString.call(value) === '[object ArrayBuffer]') return value.slice(0)
  if (ArrayBuffer.isView(value)) {
    const buffer = value.buffer.slice(value.byteOffset, value.byteOffset + value.byteLength)
    return Object.prototype.toString.call(value) === '[object DataView]'
      ? new DataView(buffer) : new value.constructor(buffer)
  }
  if (Array.isArray(value)) return value.map(snapshot)
  if (value && typeof value === 'object') {
    const copy = {}
    Object.keys(value).forEach(key => { copy[key] = snapshot(value[key]) })
    return copy
  }
  return value
}
module.exports = { snapshot }
