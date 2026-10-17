const { STORAGE_KEYS } = require('./storage-keys')

// 2026-10-17：现金已在确认前交付；未知结果不能因离页/重启丢失，再提示交一次钱。
// 只保留员工/站别和原申请编号，不保存客户、金额或登录凭据，也不重发退款。
function keyOf(actor) {
  if (!/^[1-9]\d*:[1-9]\d*$/.test(actor || '')) throw new Error('请先核对员工与水站身份')
  return STORAGE_KEYS.BARREL_REFUND_RECOVERY + actor
}
function pending(actor) {
  const value = wx.getStorageSync(keyOf(actor))
  if (value === '' || value == null) return null
  if (!value || value.version !== 1 || value.actor !== actor || !/^[1-9]\d*$/.test(value.recordId || '')) {
    throw new Error('上次退款记录无法识别，请保留现场并联系负责人，勿再次交款')
  }
  return value
}
function prepare(actor, id) {
  const old = pending(actor)
  if (old) throw new Error('上次退款结果仍待核对，请先查询原申请，勿再次交款')
  const value = { version: 1, actor, recordId: String(id) }
  if (!/^[1-9]\d*$/.test(value.recordId)) throw new Error('请先核对原申请编号')
  wx.setStorageSync(keyOf(actor), value)
  const saved = pending(actor)
  if (!saved || saved.recordId !== value.recordId) throw new Error('退款核对记录未能保存，请勿交款并重试')
  return value
}
function clear(value) {
  const old = pending(value.actor)
  if (old && old.recordId !== value.recordId) throw new Error('上次退款记录已变化，请先核对原申请')
  wx.removeStorageSync(keyOf(value.actor))
  if (pending(value.actor)) throw new Error('退款核对记录未能更新，请重试核对原申请，勿再次交款')
}
module.exports = { pending, prepare, clear }
