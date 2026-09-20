// 配送相关接口
const { get, post, put } = require('../utils/request')
const { API } = require('../config/api')

// 获取待配送列表（本站 status=1 的单：已派给我但未接单 + 全站还没派出去的）
const getPendingOrders = (params = {}) => {
  return get(API.DELIVERY_PENDING, params)
}

// 获取配送中订单
const getDeliveringOrders = (params = {}) => {
  return get(API.DELIVERY_DELIVERING, params)
}

// 获取订单详情
const getOrderDetail = (id) => {
  return get(`${API.DELIVERY_ORDERS}/${id}`)
}

// 接单
const acceptOrder = (id) => {
  return post(`${API.DELIVERY_ORDERS}/${id}/accept`)
}

// 完成配送
const completeOrder = (id, data) => {
  return post(`${API.DELIVERY_ORDERS}/${id}/complete`, data)
}

// 获取已配送待付款订单（未收款）
const getDeliveredUnpaid = () => {
  return get(API.DELIVERY_DELIVERED_UNPAID)
}

// 收款确认
const confirmCollection = (id) => {
  return post(`${API.DELIVERY_ORDERS}/${id}/confirm-offline-pay`)
}

// 拒单入池
const rejectOrder = (id, reason) => {
  return post(`${API.DELIVERY_REJECT}/${id}`, { reason })
}

// 转让订单
const transferOrder = (id, data) => {
  return post(`${API.DELIVERY_TRANSFER}/${id}`, data)
}

// 退回站长
const returnToStation = (id, data) => {
  return post(`${API.DELIVERY_RETURN}/${id}`, data)
}

// 异常反馈
const reportOrder = (id, data) => {
  return post(`${API.DELIVERY_REPORT}/${id}`, data)
}

// 今日统计
const getTodayStats = () => {
  return get(API.DELIVERY_STATS_TODAY)
}

// 当前配送员今日已完成
const getCompletedToday = () => {
  return get(API.DELIVERY_COMPLETED_TODAY)
}

// 配送历史
const getDeliveryHistory = () => {
  return get(API.DELIVERY_HISTORY)
}

// 转让记录
const getTransferRecords = () => {
  return get(API.DELIVERY_TRANSFERS)
}

// 获取水站所有配送中订单（站长协调用）
const getStationDeliveringOrders = () => {
  return get(API.DELIVERY_STATION_DELIVERING)
}

// 分配订单给指定配送员（站长用）
const assignOrder = (id, data) => {
  return post(`${API.DELIVERY_ASSIGN}/${id}`, data)
}

// 获取水站配送员列表
const getStaffList = (stationId) => {
  return get(API.STAFF, { stationId })
}

// 获取「分配给我、我还没接单」的列表（与上面那支合并成「待配送」页签）
const getAssignedToMe = () => {
  return get(API.DELIVERY_ASSIGNED_TO_ME)
}

// 响应转单（同意/拒绝）
const respondTransfer = (id, data) => {
  const action = data.accept ? 'claim' : 'reject'
  // #50: 传递data参数到后端
  return post(`${API.DELIVERY_TRANSFER}/${id}/${action}`, data)
}

// 获取待我确认的转单列表
const getTransferList = () => {
  return get(API.DELIVERY_TRANSFERS + '/incoming')
}

// 空桶回收记录
const getBarrelRecords = () => {
  return get(API.DELIVERY_BARREL_RECORDS)
}

// 外派订单：指派给其他水站配送
const dispatchOrder = (id, data) => {
  return post(`${API.DELIVERY_ORDERS}/${id}/dispatch`, data)
}

// 解决订单：拒单并取消，触发退款
const resolveOrder = (id, data) => {
  return post(`${API.DELIVERY_ORDERS}/${id}/resolve`, data)
}

// ==================== 抢单池 & 外派追踪 ====================

// 获取抢单池列表
const getPoolOrders = () => {
  return get(API.DELIVERY_POOL)
}

// 从抢单池抢单（后端路由 /api/delivery/orders/{id}/claim-pool，{id} 在路径中间）
const claimPoolOrder = (id, data) => {
  return post(`${API.DELIVERY_ORDERS}/${id}/claim-pool`, data)
}

// 获取外派追踪列表
const getDispatchTracking = () => {
  return get(API.DELIVERY_DISPATCH_TRACKING)
}

// 取消外派
// ⚠️ 以下路由的 {id} 在路径中间（/orders/{id}/xxx），必须用 DELIVERY_ORDERS 拼接，
// 不能把 id 追加在末尾，否则请求会打到不存在的路径（返回"系统错误，请联系管理员"）。
const cancelDispatch = (id) => {
  return post(`${API.DELIVERY_ORDERS}/${id}/cancel-dispatch`)
}

// 指定水站外派：目标水站将订单调解退回原归属站
const directedReturn = (id) => {
  return post(`${API.DELIVERY_ORDERS}/${id}/directed-return`)
}

// 原归属站：同意退回（变回普通待分配）
const approveDirectedReturn = (id) => {
  return post(`${API.DELIVERY_ORDERS}/${id}/directed-return/approve`)
}

// 原归属站：拒绝退回（回到配送中，由原配送员继续）
const rejectDirectedReturn = (id) => {
  return post(`${API.DELIVERY_ORDERS}/${id}/directed-return/reject`)
}

// 原归属站：被退回待确认的订单列表
const getDirectedReturns = () => {
  return get(API.DELIVERY_DIRECTED_RETURNS)
}

// 目标水站：被其他水站指定为履约站的订单列表（他站外派给我）
const getDirectedIncoming = () => {
  return get(API.DELIVERY_DIRECTED_INCOMING)
}

// 配送员转单（退回站长）：站长同意 -> 变普通待分配
const approveStaffReturn = (id) => {
  return post(`${API.DELIVERY_ORDERS}/return/${id}/approve`)
}

// 配送员转单（退回站长）：站长拒绝 -> 回到配送中，由原配送员继续配送
const rejectStaffReturn = (id) => {
  return post(`${API.DELIVERY_ORDERS}/return/${id}/reject`)
}

// ==================== 取消申请（已接单订单的取消须站长审批） ====================

// 站长「审批」页：customer（客户发起）/ station（站内发起）两组待决策申请
const getPendingApprovals = () => {
  return get(API.DELIVERY_PENDING_APPROVALS)
}

// 配送员发起取消申请：订单已被接单，取消需站长同意（不立即取消，仅提交申请）
const requestCancel = (id, data) => {
  return post(`${API.DELIVERY_ORDERS}/${id}/cancel-request`, data || {})
}

// 站长同意取消申请：走完整退款链（退水票/支付/押金、回补库存），订单置已取消
const approveCancelRequest = (id) => {
  return post(`${API.DELIVERY_ORDERS}/cancel-request/${id}/approve`)
}

// 站长驳回取消申请：订单保持原状态，由原配送员继续履约
const rejectCancelRequest = (id) => {
  return post(`${API.DELIVERY_ORDERS}/cancel-request/${id}/reject`)
}

// 站长拒单（简化版）
const stationReject = (id, data) => {
  return post(`${API.DELIVERY_ORDERS}/${id}/station-reject`, data)
}

module.exports = {
  getPendingOrders,
  getDeliveringOrders,
  getCompletedToday,
  getOrderDetail,
  acceptOrder,
  completeOrder,
  getDeliveredUnpaid,
  confirmCollection,
  rejectOrder,
  transferOrder,
  returnToStation,
  reportOrder,
  getTodayStats,
  getDeliveryHistory,
  getTransferRecords,
  getStationDeliveringOrders,
  assignOrder,
  getStaffList,
  getAssignedToMe,
  getBarrelRecords,
  respondTransfer,
  getTransferList,
  dispatchOrder,
  resolveOrder,
  getPoolOrders,
  claimPoolOrder,
  getDispatchTracking,
  cancelDispatch,
  stationReject,
  directedReturn,
  approveDirectedReturn,
  rejectDirectedReturn,
  getDirectedReturns,
  getDirectedIncoming,
  approveStaffReturn,
  rejectStaffReturn,
  getPendingApprovals,
  requestCancel,
  approveCancelRequest,
  rejectCancelRequest
}
