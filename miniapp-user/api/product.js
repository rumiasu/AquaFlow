// 商品（水类型）相关接口（后端: WaterTypeController）
const { get } = require('../utils/request')
const { API, getCustomerId } = require('../config/api')

// GET /api/water-types?keyword=xxx
const getWaterTypes = (params) => {
  return get(API.WATER_TYPES, params)
}

// GET /api/water-types/{id}
const getWaterTypeDetail = (id) => {
  return get(`${API.WATER_TYPES}/${id}`)
}

// GET /api/water-types/my?customerId=xxx （用户已购买过的水类型）
const getMyWaterTypes = () => {
  return get(API.MY_WATER_TYPES, { customerId: getCustomerId() })
}

module.exports = { getWaterTypes, getWaterTypeDetail, getMyWaterTypes }
