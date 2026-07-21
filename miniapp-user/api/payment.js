const { post, get, put } = require('../utils/request')
const { API, getCustomerId } = require('../config/api')

/** 创建支付记录 */
const createPayment = (data) => {
  return post(API.PAYMENTS, data)
}

/** 确认支付 */
const confirmPayment = (id) => {
  return put(`${API.PAYMENTS}/${id}/confirm`)
}

/** 查询订单支付记录 */
const getPaymentsByOrder = (orderId) => {
  return get(`${API.PAYMENTS}/by-order`, { orderId })
}

/** 查询客户支付记录 */
const getPaymentsByCustomer = () => {
  return get(`${API.PAYMENTS}/by-customer`, { customerId: getCustomerId() })
}

/** 获取站点支付配置 */
const getPayConfig = () => {
  return get(`${API.PAYMENTS}/config`, { stationId: 0 })
}

module.exports = { createPayment, confirmPayment, getPaymentsByOrder, getPaymentsByCustomer, getPayConfig }
