// Display guards mirror command prerequisites; the server remains authoritative.
const same = (a, b) => a != null && b != null && String(a) === String(b)
function orderActions(order, user) {
  const o = order || {}; const u = user || {}
  const manager = u.role === 'STATION_MANAGER' || u.role === 'manager'
  const delivery = u.role === 'DELIVERY' || u.role === 'delivery'
  const station = o.deliveryStationId == null ? o.stationId : o.deliveryStationId
  const owns = same(station, u.stationId) && (manager || (delivery && same(o.deliveryStaffId, u.staffId)))
  const active = o.status === 1 || o.status === 2
  // docs/design/16-范围决策与实施路线图.md C-12：保持原负责人正常履约；
  // “先撤回才能送达”未获批准，不实施，转让只在当事人任务中处理。
  return {
    canComplete: owns && o.status === 2 && !o.transferTarget,
    canReport: owns && o.status === 2 && !o.transferTarget,
    canTransfer: owns && active && !o.transferPending,
    canReturn: owns && active && !o.transferPending,
    canRequestCancel: owns && o.status === 2 && !o.transferPending,
    canWithdrawTransfer: same(o.deliveryStaffId, u.staffId) && owns && active && o.transferPendingSubKind === 'TRANSFER',
    canAcceptTransfer: delivery && same(station, u.stationId) && active && !!o.transferTarget
  }
}
module.exports = { orderActions }
