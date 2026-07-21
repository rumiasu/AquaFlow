// 水桶相关接口（后端: BarrelController）
const { get, post } = require('../utils/request')
const { API, getCustomerId } = require('../config/api')

// GET /api/barrels/summary?customerId=xxx
const getBarrelSummary = () => {
  return get(API.BARREL_SUMMARY, { customerId: getCustomerId() })
}

// GET /api/barrels/summary-by-type?customerId=xxx
const getBarrelSummaryByType = () => {
  return get(API.BARREL_SUMMARY_BY_TYPE, { customerId: getCustomerId() })
}

// GET /api/barrels/records?customerId=xxx
const getBarrelRecords = () => {
  return get(API.BARREL_RECORDS, { customerId: getCustomerId() })
}

// POST /api/barrels/return
const requestBarrelReturn = (data) => {
  return post(API.BARREL_RETURN, { customerId: getCustomerId(), ...data })
}

module.exports = { getBarrelSummary, getBarrelSummaryByType, getBarrelRecords, requestBarrelReturn }
