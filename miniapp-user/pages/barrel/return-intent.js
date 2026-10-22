// 2026-10-22：超时不证明申请未建立；原完整内容和编号按本人、资产站分别保存。
const positive = value => Number.isSafeInteger(Number(value)) && Number(value) > 0
const keyOf = (customerId, stationId) => 'barrel-return-intent.v2:' + customerId + ':' + stationId
function read(customerId, stationId) {
  if (!positive(customerId) || !positive(stationId)) throw new Error('请先核对登录账号和办理水站')
  const saved = wx.getStorageSync(keyOf(customerId, stationId)) || null
  if (saved) {
    const b = saved.body
    if (String(saved.customerId) !== String(customerId) || !b || Number(b.stationId) !== Number(stationId)
      || !positive(b.productId) || b.waterTypeId !== b.productId || !positive(b.quantity)
      || typeof b.note !== 'string' || !['STORE', 'PICKUP', 'COMBINED'].includes(b.pickupMode)
      || (b.pickupMode === 'COMBINED' ? !positive(b.companionOrderId) : b.companionOrderId !== null)
      || typeof b.idempotencyKey !== 'string' || !b.idempotencyKey) {
      throw new Error('原退还申请凭据无法完整读取，请保留记录并联系原办理水站核对')
    }
  }
  // 旧版没有账号和完整原内容，不能猜归属、补内容或覆盖凭据后发新申请。
  const legacy = wx.getStorageSync('barrel-return-intent')
  if (!saved && legacy && (!legacy.intent || Number(String(legacy.intent).split(':')[0]) === Number(stationId))) {
    throw new Error('本机有一笔旧退还申请待核对，原账号或内容不完整，请联系原办理水站核对后再办理')
  }
  return saved
}
function prepare(customerId, body, pickupModeText) {
  const old = read(customerId, body.stationId)
  if (old) return old
  const saved = { customerId, pickupModeText: pickupModeText || '', body: { ...body,
    waterTypeId: body.productId, idempotencyKey: 'ret-' + Date.now() + '-' + Math.random().toString(36).slice(2) } }
  wx.setStorageSync(keyOf(customerId, body.stationId), saved)
  // 存储失败或未实际保存时不得发出无法恢复的申请。
  const stored = read(customerId, body.stationId)
  if (!stored || stored.body.idempotencyKey !== saved.body.idempotencyKey) throw new Error('原申请未能安全保存，请稍后重试')
  return stored
}
function clear(saved) {
  const old = read(saved.customerId, saved.body.stationId)
  if (old && old.body.idempotencyKey === saved.body.idempotencyKey) wx.removeStorageSync(keyOf(saved.customerId, saved.body.stationId))
}
module.exports = { read, prepare, clear }
