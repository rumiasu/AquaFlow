// 常用订单模板接口（后端: OrderTemplateController）
const { get, post, put, del } = require('../utils/request')
const { API, getCustomerId } = require('../config/api')

// GET /api/order-templates/quick?customerId=xxx
const getQuickOrder = () => {
  return get(API.ORDER_TEMPLATES_QUICK, { customerId: getCustomerId() })
}

// GET /api/order-templates?customerId=xxx
const getTemplates = () => {
  return get(API.ORDER_TEMPLATES, { customerId: getCustomerId() })
}

// POST /api/order-templates?customerId=xxx
const saveTemplate = (data) => {
  return post(API.ORDER_TEMPLATES, data, { customerId: getCustomerId() })
}

// PUT /api/order-templates/{id}/toggle?customerId=xxx&enabled=1
const toggleTemplate = (id, enabled) => {
  return put(`${API.ORDER_TEMPLATES}/${id}/toggle`, null, { customerId: getCustomerId(), enabled })
}

// PUT /api/order-templates/{id}/default?customerId=xxx
const setDefaultTemplate = (id) => {
  return put(`${API.ORDER_TEMPLATES}/${id}/default`, null, { customerId: getCustomerId() })
}

// POST /api/order-templates/from-order?customerId=xxx&orderId=xxx
const setFromOrder = (orderId) => {
  return post(API.ORDER_TEMPLATES_FROM_ORDER, null, { customerId: getCustomerId(), orderId })
}

// DELETE /api/order-templates/{id}?customerId=xxx
const deleteTemplate = (id) => {
  return del(`${API.ORDER_TEMPLATES}/${id}`, { customerId: getCustomerId() })
}

module.exports = { getQuickOrder, getTemplates, saveTemplate, toggleTemplate, setDefaultTemplate, setFromOrder, deleteTemplate }
