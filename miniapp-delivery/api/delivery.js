// 配送相关接口
const { get, post, put } = require('../utils/request')
const { API } = require('../config/api')

// 获取待接单列表
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

// 获取分配给我的待接单列表
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

// 从抢单池抢单
const claimPoolOrder = (id, data) => {
  return post(`${API.DELIVERY_CLAIM_POOL}/${id}`, data)
}

// 获取外派追踪列表
const getDispatchTracking = () => {
  return get(API.DELIVERY_DISPATCH_TRACKING)
}

// 取消外派
const cancelDispatch = (id) => {
  return post(`${API.DELIVERY_CANCEL_DISPATCH}/${id}`)
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
  stationReject
}
