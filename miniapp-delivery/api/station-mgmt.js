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

/**
 * 综合数据报表（含环比/趋势/多维分布）。
 * 注意：不传 stationId —— 后端按登录站长所属水站出数，前端传了也会被忽略，
 * 传了反而会让人误以为能查别站数据。
 */
const getDashboardReport = (range) => {
  return get(API.DASHBOARD_REPORT, { range })
}

// 订单
const getOrders = (params) => {
  return get(API.ORDERS, params)
}

// 客户
const getCustomers = (stationId) => {
  return get(API.CUSTOMERS, { stationId })
}

// 客户详情（站长视角，含本站权限与统计）
const getCustomerDetail = (id) => {
  return get(API.CUSTOMER_DETAIL(id))
}

// 客户画像（站长视角：消费/资产/行为聚合）
const getCustomerProfile = (id) => {
  return get(API.CUSTOMER_PROFILE(id))
}

// 客户在本站的资产（水桶/水票/押金）。
// 注意：后端按「登录站长所属水站」限定数据范围，前端不传也不应传 stationId，
// 否则会让人误以为可以查别站的资产。
const getCustomerAssets = (id) => {
  return get(API.CUSTOMER_ASSETS(id))
}

// 员工画像（站长视角：配送业绩/服务质量聚合）
const getStaffProfile = (id) => {
  return get(API.STAFF_PROFILE(id))
}

// 获取客户在本站的货到付款权限配置
const getOfflinePayment = (id) => {
  return get(API.CUSTOMER_OFFLINE_PAYMENT(id))
}

// 设置客户在本站的货到付款权限（enabled: true 开通 / false 关闭）
const updateOfflinePayment = (id, enabled) => {
  return put(API.CUSTOMER_OFFLINE_PAYMENT(id), { offlinePaymentEnabled: enabled ? 1 : 0 })
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

/**
 * 纯还桶：顾客交回空桶但不带走满桶 —— 只冲减 over，不扣权益、不退款。
 * 后端会校验「交回数 ≤ 占用」，clientToken 用于防重复提交（同一 token 只生效一次）。
 */
const returnEmptyBuckets = (customerId, items, clientToken, note) => {
  return post(API.BARRELS_RETURN_EMPTY, { customerId, items, clientToken, note })
}

// ===== 站长资产调整单（人工补录 / 代客订正，站长专属）=====

/**
 * 调整单列表（本站）。params: { customerId?, page?, size? }，page 从 1 起、size 上限 100。
 * 不传 customerId = 本站全部；水站由后端按登录站长判定，前端不传 stationId。
 */
const listAdjustments = (params) => {
  return get(API.MANAGER_ADJUSTMENTS, params)
}

/** 调整单详情（含 beforeSnapshot / afterSnapshot 快照串） */
const getAdjustment = (id) => {
  return get(API.MANAGER_ADJUSTMENT(id))
}

/**
 * 只读试算（不落库）：返回 before/after 的权益/欠桶/占用/押金/水票与预计金额。
 * 入参与创建一致：{ customerId, adjustType, productId?, qty?, amount?, unitPrice? }
 */
const previewAdjustment = (data) => {
  return post(API.MANAGER_ADJUSTMENT_PREVIEW, data)
}

/**
 * 创建调整单（初始 status=PENDING「待执行」，需再调 execute 才改资产）。
 * clientToken 为幂等键：同一 token 重复提交由后端返回原单，不会产生第二张单。
 */
const createAdjustment = (data) => {
  return post(API.MANAGER_ADJUSTMENT_CREATE, data)
}

/** 执行调整单（CAS PENDING→EFFECTIVE）；重复执行业务拒绝，不会重复入账 */
const executeAdjustment = (id) => {
  return post(API.MANAGER_ADJUSTMENT_EXECUTE(id))
}

/** 撤销已生效的调整单：生成反向单并执行，原单置 REVERSED。reason 必填，clientToken 幂等 */
const reverseAdjustment = (id, reason, clientToken) => {
  return post(API.MANAGER_ADJUSTMENT_REVERSE(id), { reason, clientToken })
}

// 员工
const createStaff = (data) => {
  return post(API.STAFF, data)
}

// 解除配送员与本站的所属关系
// ⚠️ 后端实际端点为 POST /api/manager/bind/release（body: {staffId}），
// 不存在 /api/staff/{id}/detach，不要按后者的名字猜路由。
const detachStaff = (id) => {
  return post(API.MANAGER_BIND_RELEASE, { staffId: id })
}

/**
 * 待确认收款列表（订单待收款 + 线上买水票的无订单待收款）。
 * 水站由后端按登录站长判定，前端不传 stationId。
 */
const getPendingPayments = () => {
  return get(API.PAYMENTS_PENDING)
}

/**
 * 确认某笔收款已到账。
 * 订单类 → 订单支付状态置已付并入账预收押金；购票类 → 水票入账。
 * 后端用乐观锁保证同一笔只能确认成功一次，重复点击会得到「该笔支付已确认」而不是重复入账。
 */
const confirmPayment = (id) => {
  return put(API.PAYMENT_CONFIRM(id))
}

module.exports = {
  getDashboardToday,
  getDashboardOverview,
  getDashboardReport,
  getOrders,
  getCustomers,
  getCustomerDetail,
  getCustomerProfile,
  getCustomerAssets,
  getStaffProfile,
  getOfflinePayment,
  updateOfflinePayment,
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
  returnEmptyBuckets,
  listAdjustments,
  getAdjustment,
  previewAdjustment,
  createAdjustment,
  executeAdjustment,
  reverseAdjustment,
  createStaff,
  detachStaff,
  getPendingPayments,
  confirmPayment
}
