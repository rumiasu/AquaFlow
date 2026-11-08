// 2026-11-04：退票请求一旦发出，超时/离页/重启都可能让结果未知。
// 未知结果必须原键、原数量、原金额重试；不能生成新键，也不能把“重新打开页面”当成没发生过。
// 只保存办理所需的最小内容，不保存登录凭据；按员工+水站隔离。
const { STORAGE_KEYS } = require('./storage-keys')

const PREFIX = STORAGE_KEYS.TICKET_EXIT_RECOVERY
const active = new Map()
let sequence = 0

function actorOf(session) {
  const staffId = session && String(session.staffId)
  const stationId = session && String(session.stationId)
  if (!/^[1-9]\d*$/.test(staffId || '') || !/^[1-9]\d*$/.test(stationId || '')) {
    throw new Error('请先核对员工与水站身份')
  }
  return staffId + ':' + stationId
}
function keyOf(actor) { return PREFIX + actor }
function newKey() {
  return 'ticket-exit-' + Date.now().toString(36) + '-' + (++sequence).toString(36) + '-' + (Math.random().toString(36).slice(2, 10) || '0')
}
function normalized(session, value) {
  const actor = actorOf(session)
  const paymentId = String(value && value.paymentId)
  const quantity = Number(value && (value.quantity !== undefined ? value.quantity : value.remainingQty))
  const amount = Number(value && (value.amount !== undefined ? value.amount : value.refundAmount))
  if (!/^[1-9]\d*$/.test(paymentId) || !Number.isInteger(quantity) || quantity <= 0
      || !Number.isFinite(amount) || amount < 0 || amount > 99999999.99) {
    throw new Error('退票批次、数量或金额无法识别，请重新核对')
  }
  return { actor, paymentId, quantity, amount: amount.toFixed(2) }
}
function valid(value, actor) {
  return value && value.version === 1 && value.actor === actor
    && /^[1-9]\d*$/.test(String(value.paymentId || ''))
    && Number.isInteger(value.quantity) && value.quantity > 0
    && typeof value.amount === 'string' && /^\d+\.\d{2}$/.test(value.amount)
    && typeof value.requestKey === 'string' && /^ticket-exit-[a-z0-9-]+$/.test(value.requestKey)
    && value.requestKey.length <= 64
}
function readStored(actor) {
  let value
  try { value = wx.getStorageSync(keyOf(actor)) } catch (_) {
    throw new Error('上次退票记录读取失败，请保留记录并联系负责人，勿重复提交')
  }
  if (value === '' || value == null) return null
  if (!valid(value, actor)) throw new Error('上次退票记录无法识别，请保留记录并联系负责人，勿重复提交')
  return value
}
function read(session) {
  const actor = actorOf(session)
  const stored = readStored(actor)
  const memory = active.get(actor)
  if (memory) {
    // 内存只有在磁盘记录仍与它一致时才算“已可靠保存”；否则不能拿它去发请求。
    if (!stored || stored.requestKey !== memory.requestKey || stored.actor !== memory.actor) {
      throw new Error('退票恢复记录与已保存内容不一致，请保留记录并联系负责人，勿重复提交')
    }
    return Object.assign({}, memory)
  }
  if (!stored) return null
  // 重启后磁盘上的 pending 不能被当成“仍在处理”：它可能早已超时，必须按未知结果核实。
  return Object.assign({}, stored, { phase: 'unknown' })
}
function sameContent(a, b) {
  const qty = value => Number(value && (value.quantity !== undefined ? value.quantity : value.remainingQty))
  const amount = value => Number(value && (value.amount !== undefined ? value.amount : value.refundAmount))
  return !!a && !!b && String(a.paymentId) === String(b.paymentId)
    && qty(a) === qty(b) && amount(a) === amount(b)
}
function save(value) {
  try {
    wx.setStorageSync(keyOf(value.actor), value)
    const saved = wx.getStorageSync(keyOf(value.actor))
    if (!saved || saved.requestKey !== value.requestKey || saved.actor !== value.actor
        || String(saved.paymentId) !== String(value.paymentId) || Number(saved.quantity) !== Number(value.quantity)
        || Number(saved.amount) !== Number(value.amount) || saved.phase !== value.phase) throw new Error('not saved')
  } catch (_) {
    // 写盘/读回未成功前绝不能把记录放进 active：否则下一次 prepare 会绕过落盘校验。
    active.delete(value.actor)
    throw new Error('退票请求记录未能保存，尚未提交，请稍后重试')
  }
  // 只有写盘并读回一致后才允许本会话复用该记录。
  active.set(value.actor, value)
  return value
}
function prepare(session, batch) {
  const next = normalized(session, batch)
  const old = read(session)
  if (old) {
    if (!sameContent(old, next)) {
      throw Object.assign(new Error('上次退票结果仍待核实，不能改数量、金额或换批次；请先核实原请求'), { staleRecovery: true })
    }
    return old
  }
  const value = { version: 1, actor: next.actor, paymentId: next.paymentId,
    quantity: next.quantity, amount: next.amount, requestKey: newKey(),
    phase: 'pending', stamp: Date.now() }
  return save(value)
}
function markUnknown(value) {
  const next = Object.assign({}, value, { phase: 'unknown' })
  save(next)
  return next
}
function clear(value) {
  if (!value) return
  const actor = value.actor
  const old = read({ staffId: actor.split(':')[0], stationId: actor.split(':')[1] })
  if (old && old.requestKey !== value.requestKey) throw new Error('退票记录已变化，请重新核对')
  try {
    wx.removeStorageSync(keyOf(actor))
    if (wx.getStorageSync(keyOf(actor))) throw new Error('not removed')
    active.delete(actor)
  } catch (_) {
    // 不把未确认清除成功的内存副本当成已持久化；磁盘原记录仍在，重试仍走同一键。
    throw new Error('退票核对记录未能清除，请重试')
  }
}
module.exports = { read, prepare, markUnknown, clear, sameContent, newKey }
