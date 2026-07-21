// 订单相关接口（后端: OrderController）
const { get, post } = require('../utils/request')
const { API, getCustomerId } = require('../config/api')

// GET /api/orders?customerId=xxx&status=xxx
const getOrders = (params = {}) => {
  return get(API.ORDERS, { customerId: getCustomerId(), ...params })
}

// GET /api/orders/{id}
const getOrderDetail = (id) => {
  return get(`${API.ORDERS}/${id}`)
}

// POST /api/orders
const createOrder = (data) => {
  return post(API.CREATE_ORDER, { customerId: getCustomerId(), source: 3, ...data })
}

module.exports = { getOrders, getOrderDetail, createOrder }
