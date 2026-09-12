// 水站相关接口（后端: StationController）
const { get, post } = require('../utils/request')
const { API } = require('../config/api')

// GET /api/stations/public 公开可选水站列表（无需登录）
const getPublicStations = () => {
  return get(API.STATIONS_PUBLIC)
}

// GET /api/stations 全部水站（站长管理端用）
const getStations = () => {
  return get(API.STATIONS)
}

// GET /api/stations/{id} 获取水站详情
const getStationById = (id) => {
  return get(`${API.STATIONS}/${id}`)
}

// GET /api/stations/{id}/public-phone 公开获取站点电话（无需登录，顾客资产说明用）
const getStationPublicPhone = (id) => {
  return get(`${API.STATIONS}/${id}/public-phone`)
}

module.exports = { getPublicStations, getStations, getStationById, getStationPublicPhone }