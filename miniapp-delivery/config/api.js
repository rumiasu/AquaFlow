// API配置
const API_CONFIG = {
  dev: { baseUrl: 'http://localhost:8080' },
  prod: { baseUrl: 'https://your-domain.com' }
}

const getBaseUrl = () => {
  try {
    const env = __wxConfig.envVersion === 'release' ? 'prod' : 'dev'
    return API_CONFIG[env].baseUrl
  } catch (e) {
    throw new Error('无法获取小程序环境版本，请勿在非微信开发者工具中运行')
  }
}

// AquaFlow V1 配送端 API 配置
// 路径与后端完全对齐：认证(/api/auth/*)、配送(/api/delivery/*)、
//   水站管理(/api/manager/*、/api/stations/*)、绑定(/api/delivery/bind/*)
const API = {
  // ==================== 认证 ====================
  LOGIN: '/api/auth/login',
  WX_LOGIN: '/api/auth/wx-login',
  WX_LOGIN_STAFF: '/api/auth/wx-login-staff',
  DEV_LOGIN: '/api/auth/dev-login',
  REFRESH: '/api/auth/refresh',
  LOGOUT: '/api/auth/logout',
  ME: '/api/auth/me',
  UPDATE_PROFILE: '/api/auth/update-profile',
  // V1: 首次登录选择角色（STATION_MANAGER / DELIVERY），创建对应 staff 记录
  SELECT_ROLE: '/api/auth/select-role',
  // V1: 站长创建水站并绑定自己为该站站长
  AUTH_CREATE_STATION: '/api/auth/create-station',

  // ==================== 配送员-水站绑定（双向） ====================
  BIND_APPLY: '/api/delivery/bind/apply',          // 配送员申请绑定某水站
  BIND_CANCEL: '/api/delivery/bind/cancel',        // 配送员取消(撤回)绑定申请
  BIND_UNBIND_REQUEST: '/api/delivery/bind/unbind-request', // 配送员主动申请解绑(等站长确认)
  BIND_STATUS: '/api/delivery/bind/status',

  // ==================== 站长-绑定审批/员工管理 ====================
  MANAGER_BIND_APPLICATIONS: '/api/manager/bind/applications', // 我的待审批绑定申请
  MANAGER_BIND_APPROVE: '/api/manager/bind/approve',           // 通过绑定申请
  MANAGER_BIND_REJECT: '/api/manager/bind/reject',             // 拒绝绑定申请
  MANAGER_UNBIND_CONFIRM: '/api/manager/bind/unbind-confirm',  // 确认配送员的解绑申请
  MANAGER_BIND_RELEASE: '/api/manager/bind/release',           // 站长单方面解除配送员绑定
  MANAGER_STAFF: '/api/manager/staff',                         // 站长查看本站员工(含申请中)

  // ==================== 配送订单 ====================
  DELIVERY_ORDERS: '/api/delivery/orders',
  DELIVERY_PENDING: '/api/delivery/orders/pending',
  DELIVERY_DELIVERING: '/api/delivery/orders/delivering',
  DELIVERY_ACCEPT: '/api/delivery/orders/accept',
  DELIVERY_COMPLETE: '/api/delivery/orders/complete',
  DELIVERY_DELIVERED_UNPAID: '/api/delivery/orders/delivered-unpaid',
  DELIVERY_CONFIRM_COLLECTION: '/api/delivery/orders/confirm-collection',
  DELIVERY_UNCONFIRM_COLLECTION: '/api/delivery/orders/unconfirm-collection',
  DELIVERY_REJECT: '/api/delivery/orders/reject',
  DELIVERY_TRANSFER: '/api/delivery/orders/transfer',
  DELIVERY_RETURN: '/api/delivery/orders/return',
  DELIVERY_REPORT: '/api/delivery/orders/report',
  DELIVERY_COMPLETED_TODAY: '/api/delivery/orders/completed-today',
  DELIVERY_STATION_DELIVERING: '/api/delivery/orders/station-delivering',
  DELIVERY_ASSIGN: '/api/delivery/orders/assign',
  DELIVERY_ASSIGNED_TO_ME: '/api/delivery/orders/assigned-to-me',
  DELIVERY_HISTORY: '/api/delivery/history',
  DELIVERY_TRANSFERS: '/api/delivery/transfers',
  DELIVERY_BARREL_RECORDS: '/api/delivery/barrel-records',

  // 配送统计
  DELIVERY_STATS_TODAY: '/api/delivery/stats/today',

  // 员工
  STAFF: '/api/staff',
  STAFF_PROFILE: (id) => `/api/staff/${id}/profile`,

  // 支付（站长端）
  // 待确认收款：订单待收款 + 线上买水票的无订单待收款（微信支付未接入，只能人工核对到账后确认）
  PAYMENTS_PENDING: '/api/payments/pending',
  PAYMENT_CONFIRM: (id) => `/api/payments/${id}/confirm`,

  // 客户
  CUSTOMERS: '/api/customers',
  CUSTOMER_DETAIL: (id) => `/api/customers/${id}`,
  CUSTOMER_PROFILE: (id) => `/api/customers/${id}/profile`,
  // 客户在本站的资产（水桶/水票/押金）。水站由后端按登录站长判定，前端不传 stationId
  CUSTOMER_ASSETS: (id) => `/api/customers/${id}/assets`,
  CUSTOMER_OFFLINE_PAYMENT: (id) => `/api/customers/${id}/offline-payment`,

  // 综合数据报表（range=today|7d|30d）。水站由后端按登录站长判定，前端不传 stationId
  DASHBOARD_REPORT: '/api/dashboard/report',

  // 订单
  ORDERS: '/api/orders',
  ORDER_DETAIL: (id) => `/api/orders/${id}`,

  // 水站
  STATIONS: '/api/stations',
  STATION_SEARCH: '/api/stations/search',           // 公开搜索，供配送员申请绑定前使用
  STATION_GET: '/api/stations/mine',
  STATION_CREATE: '/api/stations',
  STATION_UPDATE: '/api/stations',

  // 库存
  INVENTORY: '/api/inventory',

  // 商品（V1 不再使用 water-types）
  PRODUCTS: '/api/products',
  WATER_TYPES: '/api/products',
  WATER_TYPE_UPDATE: (id) => `/api/products/${id}`,

  // 管理端: 商品+库存统一管理 (站长专用)
  MANAGER_PRODUCTS: '/api/manager/products',
  MANAGER_PRODUCT: (id) => `/api/manager/products/${id}`,
  MANAGER_PRODUCT_SHELF: (id) => `/api/manager/products/${id}/shelf`,
  MANAGER_PRODUCTS_INBOUND: '/api/manager/products/inbound',

  // 仪表盘
  DASHBOARD_TODAY: '/api/dashboard/today',
  DASHBOARD_OVERVIEW: '/api/dashboard/overview',

  // 反馈
  FEEDBACK: '/api/feedback',
  FEEDBACK_MY: '/api/feedback/my',

  // 水桶
  BARRELS_ALL_RECORDS: '/api/barrels/all-records',
  BARRELS_RECORDS_STATUS: (id) => `/api/barrels/records/${id}/status`,
  BARRELS_RETURN_EMPTY: '/api/barrels/return-empty',

  // 上传
  ORDER_IMAGE_UPLOAD: '/api/order-images/upload',
  GENERAL_UPLOAD: '/api/common/upload',

  // 线下收款确认
  CONFIRM_OFFLINE_PAY: '/api/delivery/orders/confirm-offline-pay',

  // 转单池（协调）
  TRANSFER_POOL: '/api/delivery/transfer-pool',
  TRANSFER_CLAIM: '/api/delivery/transfer-claim',
  TRANSFER_PLACE: '/api/delivery/transfer-place',
  TRANSFER_CANCEL: '/api/delivery/transfer-cancel',

  // 退回申请（站长确认/拒绝）
  RETURN_CONFIRM: '/api/delivery/return/confirm',
  RETURN_REJECT: '/api/delivery/return/reject',
  RETURN_REQUEST: '/api/delivery/return-request',

  // 抢单池 & 外派追踪
  DELIVERY_POOL: '/api/delivery/orders/pool',
  DELIVERY_CLAIM_POOL: '/api/delivery/orders/claim-pool',
  DELIVERY_DISPATCH_TRACKING: '/api/delivery/orders/dispatch-tracking',
  DELIVERY_DIRECTED_RETURNS: '/api/delivery/orders/directed-returns',
  DELIVERY_DIRECTED_INCOMING: '/api/delivery/orders/directed-incoming',
  // ⚠️ 以下 POST 路由的 {id} 在路径**中间**（如 /orders/{id}/cancel-dispatch），
  // 千万不要拿一个"完整路径常量"再在末尾追加 id（会打到不存在的地址，报"系统错误，请联系管理员"）。
  // 已在 api/delivery.js 统一改用 DELIVERY_ORDERS + `/${id}/...` 拼接，这些常量已废弃不再使用。
}

// 员工绑定状态枚举(与后端 constant.BindingStatus 一致)
const BINDING_STATUS = {
  UNBOUND: 'UNBOUND',           // 未绑定(或已解绑,可申请)
  PENDING: 'PENDING',           // 绑定申请中,等待站长审批
  BOUND: 'BOUND',               // 已绑定
  REJECTED: 'REJECTED',         // 绑定申请被站长拒绝
  PENDING_UNBIND: 'PENDING_UNBIND' // 配送员主动申请解绑,等待站长确认
}

module.exports = { getBaseUrl, API, BINDING_STATUS }
