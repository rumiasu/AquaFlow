// 同一录入结果未知时保留原内容和编号，页面重开也只能重试原意图。
const keyOf = actor => 'payroll-adjust-intent:' + actor
function pending(actor) { return wx.getStorageSync(keyOf(actor)) || null }
function prepare(actor, input) {
  if (!/^[1-9]\d*:[1-9]\d*$/.test(actor)) throw new Error('请重新登录后录入工资')
  const normalized = { staffId: Number(input.staffId), itemId: input.itemId == null ? null : Number(input.itemId),
    amount: Number(input.amount), note: String(input.note || '').trim() || null }
  const digest = JSON.stringify(normalized)
  const old = wx.getStorageSync(keyOf(actor))
  if (old) {
    if (old.actor !== actor || typeof old.idempotencyKey !== 'string' || !old.idempotencyKey || old.digest !== digest) {
      throw new Error('上一笔工资结果待确认，请恢复原员工、条目、金额和说明后重试')
    }
    return old
  }
  const value = { actor, digest, input: normalized,
    idempotencyKey: 'payroll-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2) }
  wx.setStorageSync(keyOf(actor), value)
  return value
}
function payload(value) { return Object.assign({}, value.input, { idempotencyKey: value.idempotencyKey }) }
function clear(value) {
  const old = wx.getStorageSync(keyOf(value.actor))
  if (old && old.idempotencyKey === value.idempotencyKey) wx.removeStorageSync(keyOf(value.actor))
}
module.exports = { prepare, payload, clear, pending }
