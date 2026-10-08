const { STORAGE_KEYS } = require('./storage-keys')
const keyOf = (actor, type, id) => STORAGE_KEYS.REFUND_NOTE_INTENT + actor + ':' + type + ':' + id
function read(actor, type, id) {
  const value = wx.getStorageSync(keyOf(actor, type, id))
  if (!value) return null
  if (value.actor !== actor || value.refundType !== type || value.refundId !== id ||
      typeof value.idempotencyKey !== 'string' || !value.idempotencyKey ||
      typeof value.content !== 'string' || typeof value.contact !== 'string') {
    throw new Error('上次说明记录无法识别，请保留现场并联系水站')
  }
  return value
}
function prepare(actor, type, id, content, contact) {
  const input = { content: (content || '').trim(), contact: (contact || '').trim() }
  const old = read(actor, type, id)
  if (old) {
    if (old.content !== input.content || old.contact !== input.contact) throw new Error('上一条结果待确认，请先重试原说明，再另写补充')
    return old
  }
  const value = Object.assign({ actor, refundType: type, refundId: id,
    idempotencyKey: 'note-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2) }, input)
  wx.setStorageSync(keyOf(actor, type, id), value)
  return value
}
function payload(value) {
  return { refundType: value.refundType, refundId: value.refundId, idempotencyKey: value.idempotencyKey,
    content: value.content, contact: value.contact }
}
function clear(value) {
  const old = read(value.actor, value.refundType, value.refundId)
  if (old && old.idempotencyKey === value.idempotencyKey) wx.removeStorageSync(keyOf(value.actor, value.refundType, value.refundId))
}
module.exports = { read, prepare, payload, clear }
