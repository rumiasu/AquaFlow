const { STORAGE_KEYS } = require('./storage-keys')

// 2026-10-17：现金已在确认前交付；未知结果不能因离页/重启丢失，再提示交一次钱。
// 保存原申请、原渠道/金额及本机办理轮次；不保存客户或登录凭据，不自动重发退款。
let sequence = 0
const attempt = () => 'r-' + Date.now().toString(36) + '-' + (++sequence).toString(36) + '-' + (Math.random().toString(36).slice(2) || '0')
function keyOf(actor) {
  if (!/^[1-9]\d*:[1-9]\d*$/.test(actor || '')) throw new Error('请先核对员工与水站身份')
  return STORAGE_KEYS.BARREL_REFUND_RECOVERY + actor
}
function pending(actor) {
  const value = wx.getStorageSync(keyOf(actor))
  if (value === '' || value == null) return null
  if (!value || value.version !== 1 || value.actor !== actor || !/^[1-9]\d*$/.test(value.recordId || '')
      || value.writeRejected !== undefined && typeof value.writeRejected !== 'boolean'
      || value.attempt !== undefined && !/^r-[a-z0-9]+-[a-z0-9]+(?:-[a-z0-9]+)?$/.test(value.attempt)
      || value.channel !== undefined && !['CASH', 'ONLINE'].includes(value.channel)
      || value.amount !== undefined && (typeof value.amount !== 'string' || !/^\d+\.\d{2}$/.test(value.amount))) {
    throw new Error('上次退款记录无法识别，请保留现场并联系负责人，勿再次交款')
  }
  return value
}
function sameAttempt(a, b) {
  return a && b && a.actor === b.actor && a.recordId === b.recordId && a.attempt === b.attempt
}
function current(value) {
  const old = pending(value.actor)
  if (!sameAttempt(old, value)) throw Object.assign(new Error('原办理记录已变化，请重新核对原申请'), { staleRecovery: true })
  return old
}
function save(value) {
  wx.setStorageSync(keyOf(value.actor), value)
  const saved = pending(value.actor)
  if (!sameAttempt(saved, value) || !['version', 'channel', 'amount', 'writeRejected'].every(key => saved[key] === value[key])) throw new Error('退款核对记录未能保存，请勿交款并重试')
  return saved
}
// 2026-10-17：明确业务拒绝只留内存，会在失败回读后重开时变回未知并卡住办理。
function rejected(value) {
  return save(Object.assign({}, current(value), { writeRejected: true }))
}
function prepare(actor, id, eligibility) {
  const old = pending(actor)
  if (old) throw new Error('上次退款结果仍待核对，请先查询原申请，勿再次交款')
  const value = { version: 1, actor, recordId: String(id), attempt: attempt() }
  if (eligibility) { value.channel = eligibility.channel; value.amount = Number(eligibility.refundAmount).toFixed(2) }
  if (!/^[1-9]\d*$/.test(value.recordId)) throw new Error('请先核对原申请编号')
  return save(value)
}
// 2026-10-17：人工补同步另建本机轮次，旧页迟到的拒绝不能结束正在补同步的新请求。
function retry(value, eligibility) {
  const old = current(value)
  if (old.writeRejected) throw new Error('原请求已明确拒绝，请先重新核对原申请')
  const next = Object.assign({}, old, { attempt: attempt(), writeRejected: false })
  if (next.channel === undefined && eligibility) next.channel = eligibility.channel
  if (next.amount === undefined && eligibility) next.amount = Number(eligibility.refundAmount).toFixed(2)
  return save(next)
}
function clear(value) {
  const old = pending(value.actor)
  if (old) current(value)
  wx.removeStorageSync(keyOf(value.actor))
  if (pending(value.actor)) throw new Error('退款核对记录未能更新，请重试核对原申请，勿再次交款')
}
module.exports = { pending, prepare, rejected, retry, sameAttempt, clear }
