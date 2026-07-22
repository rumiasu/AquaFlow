// 商品（水类型）相关接口（后端: WaterTypeController）
const { get } = require('../utils/request')
const { API } = require('../config/api')

// GET /api/water-types?keyword=xxx
const getWaterTypes = (params) => {
  return get(API.WATER_TYPES, params)
}

// GET /api/water-types/{id}
const getWaterTypeDetail = (id) => {
  return get(`${API.WATER_TYPES}/${id}`)
}

// GET /api/water-types/my （用户已购买过的水类型，customerId 从 JWT 获取）
const getMyWaterTypes = () => {
  return get(API.MY_WATER_TYPES)
}

module.exports = { getWaterTypes, getWaterTypeDetail, getMyWaterTypes }
