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
  save: (data) => request.post('/water-types', data)
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
