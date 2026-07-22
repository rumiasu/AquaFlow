import { get } from '../utils/request'
import { API } from '../config/api'

export const getCustomerStats = () => {
  // customerId 从 JWT 获取
  return get(`${API.CUSTOMERS}/stats`)
}
