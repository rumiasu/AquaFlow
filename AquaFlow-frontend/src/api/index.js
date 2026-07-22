import request from '../utils/request'
import axios from 'axios'

// ==================== 认证 ====================
export const authApi = {
  login: (data) => request.post('/auth/login', data),
  logout: () => request.post('/auth/logout'),
  getMe: () => request.get('/auth/me'),
  changePassword: (data) => request.post('/auth/change-password', data),
  refresh: (refreshToken) => axios.post('/api/auth/refresh', { refreshToken })
}

// ==================== 客户管理 ====================
export const customerApi = {
  list: () => request.get('/customers'),
  getById: (id) => request.get(`/customers/${id}`),
  save: (data) => request.post('/customers', data),
  update: (id, data) => request.put(`/customers/${id}`, data)
}

// ==================== 地址管理 ====================
export const addressApi = {
  list: (params) => request.get('/addresses', { params }),
  getById: (id) => request.get(`/addresses/${id}`),
  save: (data) => request.post('/addresses', data),
  update: (id, data) => request.put(`/addresses/${id}`, data)
}

// ==================== 水类型管理 ====================
export const waterTypeApi = {
  list: () => request.get('/water-types'),
  getById: (id) => request.get(`/water-types/${id}`),
  save: (data) => request.post('/water-types', data),
  update: (id, data) => request.put(`/water-types/${id}`, data),
  delete: (id) => request.delete(`/water-types/${id}`)
}

// ==================== 库存管理 ====================
export const inventoryApi = {
  list: () => request.get('/inventory'),
  inbound: (data) => request.post('/inventory/inbound', data)
}

// ==================== 订单管理 ====================
export const orderApi = {
  list: (params) => request.get('/orders', { params }),
  getById: (id) => request.get(`/orders/${id}`),
  save: (data) => request.post('/orders', data),
  updateStatus: (id, status) => request.put(`/orders/${id}/status`, { status }),
  cancel: (id) => request.put(`/orders/${id}/cancel`)
}

// ==================== 批次管理 ====================
export const batchApi = {
  list: (params) => request.get('/batches', { params }),
  getById: (id) => request.get(`/batches/${id}`),
  create: (orderIds) => request.post('/batches', { orderIds }),
  start: (id) => request.post(`/batches/${id}/start`),
  finish: (id, data) => request.post(`/batches/${id}/finish`, data),
  finishAll: (id) => request.post(`/batches/${id}/finish-all`),
  delete: (id) => request.delete(`/batches/${id}`),
  getMyBatches: () => request.get('/batches/my'),
  assignDelivery: (id, deliveryPersonId) => request.post(`/batches/${id}/assign`, null, { params: { deliveryPersonId } })
}

// ==================== 支付管理 ====================
export const paymentApi = {
  list: (params) => request.get('/payments', { params }),
  listByOrderId: (orderId) => request.get('/payments/by-order', { params: { orderId } }),
  listByCustomerId: (customerId) => request.get(`/payments/customer/${customerId}`),
  create: (data) => request.post('/payments', data),
  confirm: (id) => request.put(`/payments/${id}/confirm`),
  cashConfirm: (id) => request.put(`/payments/${id}/cash-confirm`),
  refund: (id) => request.put(`/payments/${id}/refund`),
  getConfig: () => request.get('/payments/config')
}

// ==================== 报表 ====================
export const reportApi = {
  addressTags: () => request.get('/addresses/report/tags')
}

// ==================== 仪表盘 ====================
export const dashboardApi = {
  today: () => request.get('/dashboard/today'),
  pendingBatches: () => request.get('/dashboard/pending-batches'),
  deliveringBatches: () => request.get('/dashboard/delivering-batches'),
  overview: () => request.get('/dashboard/overview'),
  orderSource: () => request.get('/dashboard/order-source'),
  orderStatus: () => request.get('/dashboard/order-status'),
  orderTrend: () => request.get('/dashboard/order-trend'),
  waterTypeSales: () => request.get('/dashboard/water-type-sales'),
  topCustomers: () => request.get('/dashboard/top-customers')
}

// ==================== 全局搜索 ====================
export const searchApi = {
  search: (keyword) => request.get('/search', { params: { keyword } })
}

// ==================== V2: 客户归属记录 ====================
export const customerStationRecordApi = {
  list: (customerId) => request.get(`/customer-station-records/customer/${customerId}`)
}

// ==================== V2: 押金记录 ====================
export const depositRecordApi = {
  list: (customerId) => request.get(`/deposit-records/customer/${customerId}`),
  add: (data) => request.post('/deposit-records', data)
}

// ==================== V2: 桶退回管理 ====================
export const barrelApi = {
  allRecords: () => request.get('/barrels/all-records'),
  handleReturn: (id, status, handleNote) => request.put(`/barrels/records/${id}/status`, { status, handleNote })
}

// ==================== V2: 水票 ====================
export const ticketApi = {
  list: (customerId) => request.get(`/tickets/customer/${customerId}`),
  add: (data) => request.post('/tickets/add', data),
  consume: (data) => request.post('/tickets/consume', data)
}

// ==================== V2: 水票流水 ====================
export const ticketRecordApi = {
  list: (customerId) => request.get(`/ticket-records/customer/${customerId}`)
}

// ==================== V2: 企业客户 ====================
export const companyInfoApi = {
  getByCustomerId: (customerId) => request.get(`/company-info/customer/${customerId}`),
  save: (data) => request.post('/company-info', data)
}

// ==================== V2: 员工管理 ====================
export const staffApi = {
  list: (params) => request.get('/staff', { params }),
  getById: (id) => request.get(`/staff/${id}`),
  save: (data) => request.post('/staff', data),
  update: (id, data) => request.put(`/staff/${id}`, data),
  delete: (id) => request.delete(`/staff/${id}`)
}

// ==================== V2: 水站 ====================
export const stationApi = {
  list: () => request.get('/stations'),
  getById: (id) => request.get(`/stations/${id}`),
  save: (data) => request.post('/stations', data),
  update: (id, data) => request.put(`/stations/${id}`, data),
  delete: (id) => request.delete(`/stations/${id}`),
  close: (id) => request.put(`/stations/${id}/close`)
}

// ==================== V2: 水厂 ====================
export const factoryApi = {
  list: () => request.get('/factories'),
  getById: (id) => request.get(`/factories/${id}`),
  save: (data) => request.post('/factories', data),
  update: (id, data) => request.put(`/factories/${id}`, data),
  delete: (id) => request.delete(`/factories/${id}`)
}

// ==================== 水厂运营平台 ====================
export const factoryOpsApi = {
  overview: () => request.get('/factory-ops/overview'),
  stationRanking: (params) => request.get('/factory-ops/stations/ranking', { params }),
  stationTrend: (params) => request.get('/factory-ops/stations/trend', { params }),
  stationDetail: (stationId) => request.get(`/factory-ops/stations/${stationId}/detail`),
  salesDecline: (params) => request.get('/factory-ops/analysis/sales-decline', { params }),
  areaHeatmap: () => request.get('/factory-ops/analysis/area-heatmap'),
  customerChurn: (params) => request.get('/factory-ops/analysis/customer-churn', { params }),
  inventoryPressure: () => request.get('/factory-ops/analysis/inventory-pressure'),
  suggestions: () => request.get('/factory-ops/analysis/suggestions'),
  profile: (stationId) => request.get(`/factory-ops/profile/${stationId}`),
  profileSalesTrend: (stationId, params) => request.get(`/factory-ops/profile/${stationId}/sales-trend`, { params }),
  profileCustomerStats: (stationId) => request.get(`/factory-ops/profile/${stationId}/customer-stats`),
  profileInventoryTurnover: (stationId) => request.get(`/factory-ops/profile/${stationId}/inventory-turnover`),
  profilePaymentSpeed: (stationId) => request.get(`/factory-ops/profile/${stationId}/payment-speed`),
  inventoryOverview: () => request.get('/factory-ops/inventory-overview'),
  transferAvailable: () => request.get('/factory-ops/transfers/available'),
  transferCreate: (data) => request.post('/factory-ops/transfers', data),
  transferList: (params) => request.get('/factory-ops/transfers', { params }),
  transferApprove: (id, data) => request.put(`/factory-ops/transfers/${id}/approve`, data),
  transferComplete: (id, data) => request.put(`/factory-ops/transfers/${id}/complete`, data),
  alertList: (params) => request.get('/factory-ops/alerts', { params }),
  alertStats: () => request.get('/factory-ops/alerts/stats'),
  alertRead: (id) => request.put(`/factory-ops/alerts/${id}/read`),
  alertHandle: (id, data) => request.put(`/factory-ops/alerts/${id}/handle`, data),
  alertCheck: () => request.post('/factory-ops/alerts/check'),
  recentAlerts: () => request.get('/factory-ops/alerts/recent')
}

// ==================== 审计日志 ====================
export const auditLogApi = {
  listRecent: (limit) => request.get('/audit-logs/recent', { params: { limit } }),
  listByModule: (module, limit) => request.get('/audit-logs/module', { params: { module, limit } })
}
