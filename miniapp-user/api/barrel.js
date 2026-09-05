// 水桶相关接口（后端: BarrelController）
const { get, post } = require('../utils/request')
const { API } = require('../config/api')

// GET /api/barrels/summary (customerId 从 JWT 获取)
const getBarrelSummary = (stationId) => {
  return get(API.BARREL_SUMMARY, stationId ? { stationId } : {})
}

// GET /api/barrels/summary-by-type (customerId 从 JWT 获取)
const getBarrelSummaryByType = (stationId) => {
  return get(API.BARREL_SUMMARY_BY_TYPE, stationId ? { stationId } : {})
}

// GET /api/barrels/records (customerId 从 JWT 获取)
const getBarrelRecords = (stationId) => {
  return get(API.BARREL_RECORDS, stationId ? { stationId } : {})
}

// POST /api/barrels/return (customerId 从 JWT 获取)
const requestBarrelReturn = (data) => {
  return post(API.BARREL_RETURN, data)
}

module.exports = { getBarrelSummary, getBarrelSummaryByType, getBarrelRecords, requestBarrelReturn }
