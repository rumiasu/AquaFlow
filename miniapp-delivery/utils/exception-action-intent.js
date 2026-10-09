// 2026-10-08：结果未知保留原请求；身份/对象/动作分别隔离，不能换键重复结案或纠错。
const keyOf = (actor, object, action) => 'exception-action:' + actor + ':' + object + ':' + action
function read(actor, object, action) {
  const value = wx.getStorageSync(keyOf(actor, object, action)) || null
  if (value && (value.actor !== actor || value.object !== object || value.action !== action ||
    typeof value.reason !== 'string' || !value.reason.trim() || !Number.isSafeInteger(value.expectedVersion) || value.expectedVersion < 0 ||
    typeof value.idempotencyKey !== 'string' || !value.idempotencyKey)) throw new Error('上次办理记录无法识别，请保留现场并联系负责人')
  return value
}
function prepare(actor, object, action, reason, expectedVersion) {
  const text = String(reason || '').trim()
  if (!text || text.length > 1000) throw new Error('请填写办理理由或结果（最多1000字）')
  if (!Number.isSafeInteger(Number(expectedVersion)) || Number(expectedVersion) < 0) throw new Error('请刷新记录后再办理')
  const old = read(actor, object, action)
  if (old) {
    if (old.reason !== text) throw new Error('上一条结果待确认，请先重试原请求')
    return old
  }
  const value = { actor, object, action, reason: text, expectedVersion: Number(expectedVersion),
    idempotencyKey: 'exception-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2) }
  wx.setStorageSync(keyOf(actor, object, action), value)
  return value
}
function payload(value) { return { reason: value.reason, expectedVersion: value.expectedVersion, idempotencyKey: value.idempotencyKey } }
function clear(value) {
  const old = read(value.actor, value.object, value.action)
  if (old && old.idempotencyKey === value.idempotencyKey) wx.removeStorageSync(keyOf(value.actor, value.object, value.action))
}
module.exports = { read, prepare, payload, clear }
