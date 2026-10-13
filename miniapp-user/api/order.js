const { get, post, put } = require('../utils/request')
const { API } = require('../config/api')
const { isDevelopEnv } = require('../config/api')
const { captureSession, isCurrentSession } = require('../utils/token')
const orderIntent = require('../utils/orderIntent')

const getOrders = (params = {}) => {
  // customerId 从 JWT 获取，不再前端传参
  const session = captureSession()
  return get(API.ORDERS, params).then(result => {
    const rows = Array.isArray(result) ? result : result && result.data
    // 只归并实际读到的本人精确 key；第一页为空或没命中不代表原单不存在。
    if (isCurrentSession(session) && Array.isArray(rows)) rows.forEach(order => orderIntent.reconcileKnownOrder(order, session))
    return result
  })
}

const getOrderDetail = (id) => {
  const session = captureSession()
  return get(`${API.ORDERS}/${id}`).then(result => {
    const order = result && result.data
    if (isCurrentSession(session) && order && String(order.id) === String(id)) orderIntent.reconcileKnownOrder(order, session)
    return result
  })
}

const createOrder = (data) => {
  // customerId/source 等由各业务页显式传入，这里不做覆盖
  return post(API.CREATE_ORDER, data)
}

const createPayment = (data, channel = {}) => {
  // Reject only a channel explicitly identified by its capability response.
  // A future real WeChat integration may also use paymentMethod=1.
  if (channel.simulatedWechatPay === true && !isDevelopEnv()) {
    return Promise.reject(new Error('模拟微信支付仅在 develop 环境可用'))
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
