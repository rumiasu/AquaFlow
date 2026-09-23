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
// ⚠️ stationId **必须传**：后端对顾客只认 dto（顾客 JWT 里没有水站），漏传会恒返回
//    「请先选择服务水站」；而同一页的退桶试算却正常 —— 观感上像"系统坏了"（2026-09-20 实测）。
// 用**显式位置参数**而不是对象透传：对象透传没有编译期约束，少写一个字段不报错、也不易察觉。
const requestBarrelReturn = (productId, quantity, note, stationId) => {
  return post(API.BARREL_RETURN, { productId, waterTypeId: productId, quantity, note, stationId })
}

// GET /api/barrels/return/preview 退桶试算（只读）
// 金额由后端按押金条批次 FIFO 算出，前端禁止自己用「数量 × 押金单价」估。
const previewBarrelReturn = (productId, quantity, stationId) => {
  return get(API.BARREL_RETURN_PREVIEW, { productId, quantity, ...(stationId ? { stationId } : {}) })
}

module.exports = { getBarrelSummary, getBarrelSummaryByType, getBarrelRecords, requestBarrelReturn, previewBarrelReturn }
