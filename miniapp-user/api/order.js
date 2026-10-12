const { get, post, put } = require('../utils/request')
const { API } = require('../config/api')
const { isDevelopEnv } = require('../config/api')

const getOrders = (params = {}) => {
  // customerId 从 JWT 获取，不再前端传参
  return get(API.ORDERS, params)
}

const getOrderDetail = (id) => {
  return get(`${API.ORDERS}/${id}`)
}

const createOrder = (data) => {
  // customerId/source 等由各业务页显式传入，这里不做覆盖
  return post(API.CREATE_ORDER, data)
}

const createPayment = (data) => {
  // The only client-side WeChat route in this build is the server's simulated pay channel.
  // Keep ticket payment (method 3) available, but never invoke the shortcut in trial/release.
  if (data && Number(data.paymentMethod) === 1 && !isDevelopEnv()) {
    throw new Error('模拟微信支付仅在 develop 环境可用')
  }
  const { orderId, ...rest } = data
  return post(`${API.PAYMENTS}`, { orderId, ...rest })
}

const cancelOrder = (id) => {
  return put(`${API.ORDERS}/${id}/customer-cancel`)
}

const getMyLatestStation = () => {
  return get(API.MY_STATION)
}

module.exports = { getOrders, getOrderDetail, createOrder, createPayment, cancelOrder, getMyLatestStation }
