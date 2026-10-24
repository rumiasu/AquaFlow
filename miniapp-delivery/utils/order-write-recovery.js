// 2026-10-24：超时可能发生在提交之后。保存原订单阻断，GET 旧状态绝不证明未执行。
// 不存凭据；身份/站别隔离，正常 token 续期不换恢复范围。已发出的请求离页仍占有原锁。
const PREFIX = 'aquaflow:order-write-recovery:v1:'
const active = new WeakMap()
let reportSequence = 0
function owner() {
  const app = getApp(), user = (app.globalData || {}).userInfo || {}
  return JSON.stringify([user.staffId, user.stationId, user.role])
}
function key(id) { return PREFIX + owner() + ':' + String(id) }
function flights(app = getApp()) {
  if (!active.has(app)) active.set(app, new Map())
  return active.get(app)
}
function read(id) {
  const storageKey = key(id)
  const local = flights().get(storageKey)
  if (local) return { ...local.value, phase: local.phase }
  let value
  try { value = wx.getStorageSync(storageKey) } catch (_) { return { id, phase: 'unknown', corrupt: true } }
  if (!value) return null
  if (value.version !== 1 || value.owner !== owner() || String(value.id) !== String(id)
    || typeof value.actionName !== 'string' || !value.before) {
    return { id, phase: 'unknown', actionName: '', corrupt: true }
  }
  return { ...value, phase: flights().has(storageKey) ? 'pending' : 'unknown' }
}
function list() {
  let ids
  try { ids = wx.getStorageSync(PREFIX + owner() + ':orders') } catch (_) { ids = [] }
  const localIds = Array.from(flights().values()).filter(token => token.value.owner === owner()).map(token => token.value.id)
  return Array.from(new Set([...(Array.isArray(ids) ? ids : []), ...localIds].map(String))).map(read).filter(Boolean)
}
function begin(id, actionName, order, report) {
  if (read(id)) throw new Error('原操作结果待核实，请勿重复提交')
  const storageKey = key(id), value = { version: 1, owner: owner(), id, actionName,
    before: { status: order.status, paymentStatus: order.paymentStatus,
      deliveryStaffId: order.deliveryStaffId, transferPending: !!order.transferPending,
      transferPendingSubKind: order.transferPendingSubKind }, stamp: Date.now(),
    nonce: Date.now().toString(36) + Math.random().toString(36).slice(2) }
  if (report) value.report = { ...report }
  try {
    const indexKey = PREFIX + owner() + ':orders', old = list().map(item => item.id)
    wx.setStorageSync(indexKey, Array.from(new Set([...old, id])))
    wx.setStorageSync(storageKey, value)
    const saved = wx.getStorageSync(storageKey)
    if (!saved || saved.nonce !== value.nonce) throw new Error('recovery record not saved')
  } catch (_) {
    throw new Error('操作记录未能保存，尚未提交，请稍后重试')
  }
  const token = { storageKey, value, app: getApp(), phase: 'pending' }
  flights(token.app).set(storageKey, token)
  return token
}
// 普通反馈的恢复只重放已保存的原键/原文；其他写操作及旧无键记录仍走保守核实。
function reportPayload(record) {
  const payload = record && record.actionName === 'onReport' && record.report
  if (!payload || typeof payload.clientRequestId !== 'string' || !/^DR-[A-Za-z0-9_-]+$/.test(payload.clientRequestId)
    || payload.clientRequestId.length > 64 || typeof payload.reason !== 'string' || !payload.reason.trim()
    || payload.reason.length > 50 || typeof payload.reasonKey !== 'string' || !payload.reasonKey) return null
  return { clientRequestId: payload.clientRequestId, reason: payload.reason, reasonKey: payload.reasonKey }
}
function beginReport(id, order, reason) {
  const payload = { reason: reason.label, reasonKey: reason.key,
    clientRequestId: 'DR-' + Date.now().toString(36) + '-' + (++reportSequence).toString(36) + '-' + (Math.random().toString(36).slice(2) || '0') }
  return begin(id, 'onReport', order || {}, payload)
}
function retryReport(id) {
  const record = read(id)
  if (!record || record.phase !== 'unknown' || !reportPayload(record)) throw new Error('原反馈记录未能识别，请联系站长核对，勿重复上报')
  // 不换键、不换内容，复用后端原反馈凭据；重启后磁盘记录也沿用同一份原意图。
  const value = { ...record }
  delete value.phase
  const token = { storageKey: key(id), value, app: getApp(), phase: 'pending' }
  flights(token.app).set(token.storageKey, token)
  return token
}
function uncertain(error) {
  return !!(error && (error.requestMayHaveOccurred === true || error.resultUnknown
    || error.malformedResponse || Number(error.statusCode) >= 500 || Number(error.backendCode) >= 500
    || error.businessRejected === false || (error.sessionChanged && error.requestMayHaveOccurred !== false)))
}
function finish(token, error) {
  if (uncertain(error)) {
    token.phase = 'unknown'
    // 在途时存储被清理/写失败，内存仍占有原订单；尽力恢复持久记录供下次进入核实。
    try {
      const indexKey = PREFIX + token.value.owner + ':orders', ids = wx.getStorageSync(indexKey)
      wx.setStorageSync(indexKey, Array.from(new Set([...(Array.isArray(ids) ? ids : []), String(token.value.id)])))
      wx.setStorageSync(token.storageKey, token.value)
    } catch (_) { /* 内存 unknown 继续阻断，不把存储故障变成可重发。 */ }
    return
  }
  try {
    clearRecord(token.storageKey, token.value)
    flights(token.app).delete(token.storageKey)
  } catch (_) {
    token.phase = 'unknown'
  }
}
function clearRecord(storageKey, value) {
  wx.removeStorageSync(storageKey)
  if (wx.getStorageSync(storageKey)) throw new Error('recovery record not removed')
  const indexKey = PREFIX + value.owner + ':orders', ids = wx.getStorageSync(indexKey)
  const next = (Array.isArray(ids) ? ids : []).filter(id => String(id) !== String(value.id))
  if (next.length) wx.setStorageSync(indexKey, next)
  else wx.removeStorageSync(indexKey)
}
function reconcile(id, order) {
  const record = read(id)
  if (!record || record.phase === 'pending' || record.corrupt || !order
    || String(order.id) !== String(id)) return ''
  const before = record.before, action = record.actionName
  // 订单取消/负责人变化也不能证明普通反馈是否建单；只能认原反馈键匹配的回执。
  if (action === 'onReport') return ''
  let text = ''
  if (action === 'onConfirmCollection') {
    if (order.paymentStatus === 2) text = '当前订单已记为已付款，请勿再次收款。'
    else if (order.paymentStatus === 3 || order.paymentStatus === 4 || order.status === 5) text = '当前订单已退款或取消，请勿再次收款；如已实际收钱，请联系站长核对。'
  } else if (order.status === 5) text = '当前订单已取消，请核对当前记录，勿重复提交原操作。'
  else if (action === 'onTransfer' && !before.transferPending && order.transferPendingSubKind === 'TRANSFER') text = '当前已有转单申请，请查看进度，勿重复提交。'
  else if (action === 'onReturnToStation' && !before.transferPending && order.transferPendingSubKind === 'RETURN_STATION') text = '当前已有退回站长申请，请查看进度，勿重复提交。'
  else if (action === 'onRequestCancel' && !before.transferPending && order.transferPendingSubKind === 'CANCEL_REQUEST') text = '当前已有取消申请，请查看进度，勿重复提交。'
  else if (action === 'onCancelTransfer' && before.transferPending && !order.transferPending) text = '当前转单申请已结束，请核对当前负责人及状态。'
  else if (['onTransfer', 'onReturnToStation', 'onAcceptTransfer'].includes(action)
    && before.deliveryStaffId != null && String(order.deliveryStaffId) !== String(before.deliveryStaffId)) text = '当前订单负责人已变化，请核对当前记录，勿重复提交。'
  if (text) {
    try { clearRecord(key(id), record); flights().delete(key(id)) } catch (_) { return '' }
  }
  return text
}
function message(record) {
  if (!record) return ''
  if (record.actionName === 'onReport' && record.phase === 'unknown' && reportPayload(record)) return '原配送反馈结果待核实。可重试原反馈，沿用原原因和记录编号，不会另建一条反馈。'
  return record.phase === 'pending' ? '原操作仍在处理中，请稍候，勿重复提交。'
    : '原操作结果尚未确认。请核实当前订单；仍是原状态也不能证明未执行。核实完成前勿重复提交，请联系站长核对记录。'
}
module.exports = { read, list, begin, finish, uncertain, reconcile, message, reportPayload, beginReport, retryReport }
