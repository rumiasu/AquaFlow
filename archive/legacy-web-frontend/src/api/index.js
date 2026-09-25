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

// ==================== 商品管理 ====================
export const productApi = {
  list: () => request.get('/products'),
  getById: (id) => request.get(`/products/${id}`),
  save: (data) => request.post('/products', data),
  update: (id, data) => request.put(`/products/${id}`, data),
  delete: (id) => request.delete(`/products/${id}`),
  uploadImage: (id, file) => {
    const formData = new FormData()
    formData.append('file', file)
    return request.post(`/products/${id}/image`, formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
  },
  deleteImage: (id) => request.delete(`/products/${id}/image`)
}

// ==================== 兼容旧接口（逐步迁移用） ====================
export const waterTypeApi = productApi

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

// ==================== 配送订单 ====================
export const deliveryApi = {
  confirmCollection: (id) => request.post(`/delivery/orders/confirm-collection/${id}`),
  unconfirmCollection: (id) => request.post(`/delivery/orders/unconfirm-collection/${id}`),
  rejectOrder: (id, reason) => request.post(`/delivery/orders/reject/${id}`, { reason }),
  getStationDeliveringOrders: () => request.get('/delivery/orders/station-delivering'),
  getStationPendingOrders: () => request.get('/delivery/orders/station-pending'),
  stationAssign: (id, data) => request.post(`/delivery/orders/assign/${id}`, data),
  stationReturn: (id, data) => request.post(`/delivery/orders/return/${id}`, data),
  transferOrder: (id, data) => request.post(`/delivery/orders/transfer/${id}`, data),
  getTransferRecords: () => request.get('/delivery/transfers'),
  getMyDeliveries: () => request.get('/delivery/orders/my')
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

// ==================== 仪表盘 ====================
export const dashboardApi = {
  today: () => request.get('/dashboard/today'),
  overview: () => request.get('/dashboard/overview')
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
  delete: (id) => request.delete(`/staff/${id}`),
  detach: (id) => request.post(`/staff/${id}/detach`),
  claim: (id, data) => request.post(`/staff/${id}/claim`, data),
  unaffiliated: (role) => request.get('/staff/unaffiliated', { params: { role } })
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

// ==================== 文件上传 ====================
export const uploadApi = {
  upload: (file) => {
    const formData = new FormData()
    formData.append('file', file)
    return request.post('/common/upload', formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
  }
}

// ==================== 订单图片 ====================
export const orderImageApi = {
  upload: (orderId, file, type = 1) => {
    const formData = new FormData()
    formData.append('file', file)
    formData.append('orderId', orderId)
    formData.append('type', type)
    return request.post('/order-images/upload', formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
  },
  listByOrder: (orderId) => request.get(`/order-images/by-order/${orderId}`)
}

// ==================== 文件管理 ====================
export const fileApi = {
  list: (category) => request.get('/files', { params: { category } }),
  upload: (file, category = 'general') => {
    const formData = new FormData()
    formData.append('file', file)
    formData.append('category', category)
    return request.post('/files/upload', formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
  },
  delete: (id) => request.delete(`/files/${id}`)
}

// ==================== 审计日志 ====================
export const auditLogApi = {
  listRecent: (limit) => request.get('/audit-logs/recent', { params: { limit } }),
  listByModule: (module, limit) => request.get('/audit-logs/module', { params: { module, limit } })
}
