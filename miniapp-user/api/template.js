// 常用订单模板接口（后端: OrderTemplateController）
const { get, post, put, del } = require('../utils/request')
const { API } = require('../config/api')

// GET /api/order-templates/quick (customerId 从 JWT 获取)
const getQuickOrder = (stationId) => {
  return get(API.ORDER_TEMPLATES_QUICK, { stationId })
}

// GET /api/order-templates (customerId 从 JWT 获取)
const getTemplates = (stationId) => {
  return get(API.ORDER_TEMPLATES, { stationId })
}

// POST /api/order-templates (customerId 从 JWT 获取)
const saveTemplate = (data, stationId) => {
  return post(API.ORDER_TEMPLATES, data, { stationId })
}

// PUT /api/order-templates/{id}/toggle (customerId 从 JWT 获取)
const toggleTemplate = (id, enabled) => {
  return put(`${API.ORDER_TEMPLATES}/${id}/toggle`, null, { enabled })
}

// PUT /api/order-templates/{id}/default (customerId 从 JWT 获取)
const setDefaultTemplate = (id, stationId) => {
  return put(`${API.ORDER_TEMPLATES}/${id}/default`, null, { stationId })
}

// POST /api/order-templates/from-order (customerId 从 JWT 获取)
const setFromOrder = (orderId, stationId) => {
  return post(API.ORDER_TEMPLATES_FROM_ORDER, null, { orderId, stationId })
}

// DELETE /api/order-templates/{id} (customerId 从 JWT 获取)
const deleteTemplate = (id) => {
  return del(`${API.ORDER_TEMPLATES}/${id}`)
}

module.exports = { getQuickOrder, getTemplates, saveTemplate, toggleTemplate, setDefaultTemplate, setFromOrder, deleteTemplate }
