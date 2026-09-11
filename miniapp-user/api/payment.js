const { post, get, put } = require('../utils/request')
const { API } = require('../config/api')

/** 创建支付记录 */
const createPayment = (data) => {
  return post(API.PAYMENTS, data)
}

/** 服务端支付试算（金额/新增桶押金以服务端为准） */
const getQuote = (data) => {
  return post(API.PAYMENT_QUOTE, data)
}

/** 确认支付 */
const confirmPayment = (id) => {
  return put(`${API.PAYMENTS}/${id}/confirm`)
}

/** 查询订单支付记录 */
const getPaymentsByOrder = (orderId) => {
  return get(`${API.PAYMENTS}/by-order`, { orderId })
}

/** 查询客户支付记录 (customerId 从 JWT 获取) */
const getPaymentsByCustomer = () => {
  return get(`${API.PAYMENTS}/by-customer`)
}

// 说明：原先这里导出一个 getPayConfig()，调用 GET /api/payments/config 并写死 stationId: 0。
// 该接口是站长端的（@RequireRole STATION_MANAGER），且 stationId 入参会被后端用登录态覆盖，
// 客户端调用必然失败；此函数也从未被任何页面使用，已删除。
// 客户端的支付方式与可用性请使用 getQuote() 返回的 methods / allowOfflinePayment 字段。

module.exports = { createPayment, getQuote, confirmPayment, getPaymentsByOrder, getPaymentsByCustomer }
