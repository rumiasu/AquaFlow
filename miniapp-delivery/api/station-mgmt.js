// 站长管理相关接口
const { get, post, put, del } = require('../utils/request')
const { API } = require('../config/api')

// 仪表盘
const getDashboardToday = (stationId) => {
  return get(API.DASHBOARD_TODAY, { stationId })
}

const getDashboardOverview = (stationId) => {
  return get(API.DASHBOARD_OVERVIEW, { stationId })
}

// 订单
const getOrders = (params) => {
  return get(API.ORDERS, params)
}

// 客户
const getCustomers = (stationId) => {
  return get(API.CUSTOMERS, { stationId })
}

// 库存
const getInventory = (stationId) => {
  return get(API.INVENTORY, { stationId })
}

// 水类型 (兼容旧调用)
const getWaterTypesWithStock = (stationId) => {
  return get(API.WATER_TYPES + '/with-stock', { stationId })
}

const updateWaterType = (id, data) => {
  return put(API.WATER_TYPE_UPDATE(id), data)
}

// ===== 管理端: 商品+库存统一管理 (V1) =====

/** 商品列表 (含当前水站库存/上架/水票配置) */
const getManagerProducts = (status) => {
  const params = {}
  if (status !== undefined && status !== null) params.status = status
  return get(API.MANAGER_PRODUCTS, params)
}

/** 单个商品 (含库存配置) */
const getManagerProduct = (id) => {
  return get(API.MANAGER_PRODUCT(id))
}

/** 新增商品 + 同时配置库存/上架/水票 */
const createManagerProduct = (data) => {
  return post(API.MANAGER_PRODUCTS, data)
}

/** 修改商品基本信息 + 库存配置 */
const updateManagerProduct = (id, data) => {
  return put(API.MANAGER_PRODUCT(id), data)
}

/** 软删除 (停用/下架) */
const deleteManagerProduct = (id) => {
  return del(API.MANAGER_PRODUCT(id))
}

/** 快捷上下架 */
const toggleShelf = (id, enabled) => {
  return put(API.MANAGER_PRODUCT_SHELF(id) + '?enabled=' + enabled)
}

/** 批量入库 */
const inboundProducts = (items) => {
  return post(API.MANAGER_PRODUCTS_INBOUND, { items })
}

// 退桶
const getAllBarrelRecords = () => {
  return get(API.BARRELS_ALL_RECORDS)
}

const updateBarrelRecordStatus = (id, status) => {
  return put(API.BARRELS_RECORDS_STATUS(id), { status })
}

// 员工
const createStaff = (data) => {
  return post(API.STAFF, data)
}

const detachStaff = (id) => {
  return post(API.STAFF_DETACH(id))
}

module.exports = {
  getDashboardToday,
  getDashboardOverview,
  getOrders,
  getCustomers,
  getInventory,
  getWaterTypesWithStock,
  updateWaterType,
  getManagerProducts,
  getManagerProduct,
  createManagerProduct,
  updateManagerProduct,
  deleteManagerProduct,
  toggleShelf,
  inboundProducts,
  getAllBarrelRecords,
  updateBarrelRecordStatus,
  createStaff,
  detachStaff
}
