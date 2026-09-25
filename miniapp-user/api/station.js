// 水站相关接口（后端: StationController）
const { get } = require('../utils/request')
const { API } = require('../config/api')

// GET /api/stations/public 公开可选水站列表（无需登录）
const getPublicStations = () => {
  return get(API.STATIONS_PUBLIC)
}

// GET /api/stations/{id}/public-phone 公开获取站点电话（无需登录，顾客资产说明用）
const getStationPublicPhone = (id) => {
  return get(`${API.STATIONS}/${id}/public-phone`)
}

// GET /api/stations/{id}/status 公开获取水站营业状态（软状态）+ 站长留言（无需登录）
// 注意：它是**提示**用的，不阻断下单 —— 真正"停业不可下单"由后端硬状态决定。
const getStationStatus = (id) => {
  return get(`${API.STATIONS}/${id}/status`)
}

// [清理 2026-09-12] 删除 getStations() 与 getStationById(id)：两者全程序零调用，
// 且打的 GET /api/stations 与 GET /api/stations/{id} 都是 @RequireRole("STATION_MANAGER")，
// 顾客 token 调用只会在运行期拿到「权限不足」。
// 之前 fetchStationPhone 误用 getStationById，导致站点电话恒定取不到、被 try 兜底成「请咨询客服」，
// 排查成本很高；把这类"顾客端却打站长路由"的死导出删掉，避免下一颗同样的雷。

module.exports = { getPublicStations, getStationPublicPhone, getStationStatus }
