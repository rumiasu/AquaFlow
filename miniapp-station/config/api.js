// 站长端 API配置
const API_CONFIG = {
  dev: { baseUrl: 'http://127.0.0.1:8080' },
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

// 与后端 Controller 路径一一对应
const API = {
  // 认证
  LOGIN: '/api/auth/login',
  WX_LOGIN: '/api/auth/wx-login',
  DEV_LOGIN: '/api/auth/dev-login',
  REFRESH: '/api/auth/refresh',
  LOGOUT: '/api/auth/logout',
  ME: '/api/auth/me',

  // 配送员订单
  DELIVERY_ORDERS_PENDING: '/api/delivery/orders/pending',
  DELIVERY_ORDERS_DELIVERING: '/api/delivery/orders/delivering',
  DELIVERY_ORDERS_COMPLETED: '/api/delivery/orders/completed-today',
  DELIVERY_ORDER_DETAIL: '/api/delivery/orders',
  DELIVERY_ORDER_ACCEPT: '/api/delivery/orders',
  DELIVERY_ORDER_COMPLETE: '/api/delivery/orders',
  DELIVERY_ORDER_EXCEPTION: '/api/delivery/orders',

  // 站长端：桶异常管理
  MANAGER_EXCEPTIONS: '/api/manager/exceptions',
  MANAGER_EXCEPTIONS_STATS: '/api/manager/exceptions/stats',
  MANAGER_EXCEPTIONS_CONFIG: '/api/manager/exceptions/config',

  // 站长端：商品管理
  MANAGER_PRODUCTS_WITH_STOCK: '/api/products/with-stock',
  MANAGER_PRODUCTS: '/api/manager/products',

  // 水站信息
  STATIONS_MY_CURRENT: '/api/stations/mine',
  STATION_BY_ID: '/api/stations'
}

module.exports = { getBaseUrl, API }