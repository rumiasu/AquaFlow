import request from '../utils/request'

// ==================== 认证 ====================
export const authApi = {
  login: (data) => request.post('/auth/login', data)
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
  updateStatus: (id, status) => request.put(`/orders/${id}/status`, { status })
}

// ==================== 批次管理 ====================
export const batchApi = {
  list: (params) => request.get('/batches', { params }),
  getById: (id) => request.get(`/batches/${id}`),
  create: (orderIds) => request.post('/batches', { orderIds }),
  start: (id) => request.post(`/batches/${id}/start`),
  finish: (id, data) => request.post(`/batches/${id}/finish`, data),
  finishAll: (id) => request.post(`/batches/${id}/finish-all`),
  delete: (id) => request.delete(`/batches/${id}`)
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
  list: (customerId) => request.get('/customer-station-records', { params: { customerId } })
}

// ==================== V2: 押金记录 ====================
export const depositRecordApi = {
  list: (customerId) => request.get('/deposit-records', { params: { customerId } }),
  add: (data) => request.post('/deposit-records', data)
}

// ==================== V2: 桶退回管理 ====================
export const barrelApi = {
  allRecords: () => request.get('/barrels/all-records'),
  handleReturn: (id, status, handleNote) => request.put(`/barrels/records/${id}/status`, { status, handleNote })
}

// ==================== V2: 水票 ====================
export const ticketApi = {
  list: (customerId) => request.get('/tickets', { params: { customerId } }),
  add: (data) => request.post('/tickets/add', data),
  consume: (data) => request.post('/tickets/consume', data)
}

// ==================== V2: 水票流水 ====================
export const ticketRecordApi = {
  list: (customerId) => request.get('/ticket-records', { params: { customerId } })
}

// ==================== V2: 企业客户 ====================
export const companyInfoApi = {
  getByCustomerId: (customerId) => request.get('/company-info', { params: { customerId } }),
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
  delete: (id) => request.delete(`/stations/${id}`)
}

// ==================== V2: 水厂 ====================
export const factoryApi = {
  list: () => request.get('/factories'),
  getById: (id) => request.get(`/factories/${id}`),
  save: (data) => request.post('/factories', data),
  update: (id, data) => request.put(`/factories/${id}`, data),
  delete: (id) => request.delete(`/factories/${id}`)
}
