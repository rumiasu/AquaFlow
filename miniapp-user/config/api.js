// API配置
const API_CONFIG = {
  dev: { baseUrl: 'http://192.168.0.243:8080' },
  prod: { baseUrl: 'https://your-domain.com' }
}

const getBaseUrl = () => {
  try {
    const env = __wxConfig.envVersion === 'release' ? 'prod' : 'dev'
    return API_CONFIG[env].baseUrl
  } catch (e) {
    return API_CONFIG.dev.baseUrl
  }
}

// 与后端 Controller 路径一一对应
const API = {
  // 认证（后端: LoginController）
  LOGIN: '/api/auth/login',
  WX_LOGIN: '/api/auth/wx-login',
  DEV_LOGIN: '/api/auth/dev-login',
  REFRESH: '/api/auth/refresh',
  LOGOUT: '/api/auth/logout',
  ME: '/api/auth/me',
  UPDATE_PROFILE: '/api/auth/update-profile',
  CHANGE_PASSWORD: '/api/auth/change-password',

  // 商品（后端: WaterTypeController）
  WATER_TYPES: '/api/water-types',
  MY_WATER_TYPES: '/api/water-types/my',

  // 订单（后端: OrderController）
  CUSTOMERS: '/api/customers',
    ORDERS: '/api/orders',
  CREATE_ORDER: '/api/orders',

  // 常用订单模板（后端: OrderTemplateController）
  ORDER_TEMPLATES: '/api/order-templates',
  ORDER_TEMPLATES_QUICK: '/api/order-templates/quick',
  ORDER_TEMPLATES_FROM_ORDER: '/api/order-templates/from-order',

  // 地址（后端: AddressController）
  ADDRESSES: '/api/addresses',
  CREATE_ADDRESS: '/api/addresses',

  // 水票（后端: TicketAccountController）
  TICKETS: '/api/tickets',
  TICKET_RECORDS: '/api/ticket-records',

  // 水桶（后端: BarrelController）
  BARREL_SUMMARY: '/api/barrels/summary',
  BARREL_SUMMARY_BY_TYPE: '/api/barrels/summary-by-type',
  BARREL_RECORDS: '/api/barrels/records',
  BARREL_RETURN: '/api/barrels/return',

  // 支付（后端: PaymentController）
  PAYMENTS: '/api/payments',

  // 搜索（后端: SearchController）
  SEARCH: '/api/search'
}

// 客户ID（兼容旧代码，优先从 JWT 获取）
const getCustomerId = () => {
  return wx.getStorageSync('customerId') || 1
}

module.exports = { getBaseUrl, API, getCustomerId }
