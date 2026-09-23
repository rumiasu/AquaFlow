const { get } = require('../utils/request')
const { API } = require('../config/api')

const getCustomerStats = () => {
  // customerId 从 JWT 获取
  return get(`${API.CUSTOMERS}/stats`)
}

module.exports = { getCustomerStats }
