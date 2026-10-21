// 2026-10-08：结果未知保留原请求；身份/对象/动作分别隔离，不能换键重复结案或纠错。
const keyOf = (actor, object, action) => 'exception-action:' + actor + ':' + object + ':' + action
function scopeItems(items) {
  if (!Array.isArray(items) || !items.length || items.length > 200) throw new Error('请填写原案争议商品及金额')
  const ids = new Set()
  return items.map(item => {
    const id = item && Number(item.productId), text = item && String(item.amount)
    if (!Number.isSafeInteger(id) || id <= 0 || ids.has(id) || !/^\d+(?:\.\d{1,2})?$/.test(text) || Number(text) <= 0 || Number(text) > 99999999.99) throw new Error('争议商品或金额未能核对')
    ids.add(id); return { productId: id, amount: Number(Number(text).toFixed(2)) }
  }).sort((a, b) => a.productId - b.productId)
}
function read(actor, object, action) {
  const raw = wx.getStorageSync(keyOf(actor, object, action))
  const value = raw === '' || raw == null ? null : raw
  if (value !== null && (typeof value !== 'object' || value.actor !== actor || value.object !== object || value.action !== action ||
    typeof value.reason !== 'string' || !value.reason.trim() || !Number.isSafeInteger(value.expectedVersion) || value.expectedVersion < 0 ||
    typeof value.idempotencyKey !== 'string' || !value.idempotencyKey)) throw new Error('上次办理记录无法识别，请保留现场并联系负责人')
  if (value && action === 'SCOPE') scopeItems(value.items)
  return value
}
function prepare(actor, object, action, reason, expectedVersion, items) {
  const text = String(reason || '').trim()
  if (!text || text.length > 1000) throw new Error('请填写办理理由或结果（最多1000字）')
  if (!Number.isSafeInteger(Number(expectedVersion)) || Number(expectedVersion) < 0) throw new Error('请刷新记录后再办理')
  const old = read(actor, object, action)
  const normalized = action === 'SCOPE' ? scopeItems(items) : null
  if (old) {
    if (old.reason !== text || normalized && JSON.stringify(scopeItems(old.items)) !== JSON.stringify(normalized)) throw new Error('上一条结果待确认，请先重试原请求')
    return old
  }
  const value = { actor, object, action, reason: text, expectedVersion: Number(expectedVersion),
    idempotencyKey: 'exception-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2) }
  if (normalized) value.items = normalized
  wx.setStorageSync(keyOf(actor, object, action), value)
  if (normalized) {
    const saved = read(actor, object, action)
    if (!saved || saved.idempotencyKey !== value.idempotencyKey || saved.reason !== value.reason || saved.expectedVersion !== value.expectedVersion || JSON.stringify(scopeItems(saved.items)) !== JSON.stringify(normalized)) throw new Error('原范围请求未能保存，请勿提交')
  }
  return value
}
function payload(value) {
  const data = { reason: value.reason, expectedVersion: value.expectedVersion, idempotencyKey: value.idempotencyKey }
  if (value.action === 'SCOPE') data.items = scopeItems(value.items)
  return data
}
function clear(value) {
  const old = read(value.actor, value.object, value.action)
  if (old && old.idempotencyKey === value.idempotencyKey) wx.removeStorageSync(keyOf(value.actor, value.object, value.action))
}
module.exports = { read, prepare, payload, clear }
