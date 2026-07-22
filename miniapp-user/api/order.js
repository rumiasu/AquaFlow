import { get, post, put } from '../utils/request'
import { API } from '../config/api'

export const getOrders = (params = {}) => {
  // customerId 从 JWT 获取，不再前端传参
  return get(API.ORDERS, params)
}

export const getOrderDetail = (id) => {
  return get(`${API.ORDERS}/${id}`)
}

export const createOrder = (data) => {
  // customerId 从 JWT 获取，但后端 save 仍需要它来关联 stationId
  return post(API.ORDERS, { ...data, source: 3 })
}

export const cancelOrder = (id) => {
  return put(`${API.ORDERS}/${id}/cancel`)
}
