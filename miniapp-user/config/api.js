// API配置
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
  // 认证（后端: LoginController）
  LOGIN: '/api/auth/login',
  WX_LOGIN: '/api/auth/wx-login',
  DEV_LOGIN: '/api/auth/dev-login',
  REFRESH: '/api/auth/refresh',
  LOGOUT: '/api/auth/logout',
  ME: '/api/auth/me',
  UPDATE_PROFILE: '/api/auth/update-profile',
  CHANGE_PASSWORD: '/api/auth/change-password',

  // 商品（后端: ProductController）
  PRODUCTS: '/api/products',
  PRODUCTS_SALE: '/api/products/on-sale',
  MY_PRODUCTS: '/api/products/my',
  PRODUCTS_SALE_BY_STATION: '/api/products/sale-by-station',

// 订单（后端: OrderController）
  CUSTOMERS: '/api/customers',
  ORDERS: '/api/orders',
  CREATE_ORDER: '/api/orders/create',
  MY_STATION: '/api/orders/my-station',

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
  PAYMENT_QUOTE: '/api/payments/quote',
  PAYMENT_RECORDS: '/api/payments/by-customer',

  // 押金记录（后端: DepositRecordController）
  DEPOSIT_RECORDS: '/api/deposit-records',

  // 公告（后端: NoticeController）
  NOTICES: '/api/notices',

  // 企业资料（后端: CompanyInfoController）
  COMPANY_INFO: '/api/company-info',

  // 水站（后端: StationController）
  STATIONS: '/api/stations',
  STATIONS_CURRENT: '/api/stations/current',
  STATIONS_MY_CURRENT: '/api/stations/mine',
  STATIONS_PUBLIC: '/api/stations/public',
  STATIONS_SELECT: '/api/stations/current/select',

  // 意见反馈（后端: FeedbackController）
  FEEDBACK: '/api/feedback',
  FEEDBACK_MY: '/api/feedback/my',

  // 订单图片（后端: OrderImageController）
  ORDER_IMAGES: '/api/order-images',

  // 搜索（后端: SearchController）
  SEARCH: '/api/search',

  // 站长端：桶异常管理（后端: ManagerExceptionController）
  MANAGER_EXCEPTIONS: '/api/manager/exceptions',
  MANAGER_EXCEPTIONS_STATS: '/api/manager/exceptions/stats',
  MANAGER_EXCEPTIONS_CONFIG: '/api/manager/exceptions/config',

  // 客户端：我的异常记录（后端: OrderController/CustomerExceptionController）
  CUSTOMER_EXCEPTIONS: '/api/customer/exceptions',

  // 客户通知（后端: CustomerNotificationController）
  CUSTOMER_NOTIFICATIONS: '/api/customer/notifications',
  CUSTOMER_NOTIFICATIONS_UNREAD: '/api/customer/notifications/unread',
  CUSTOMER_NOTIFICATIONS_UNREAD_COUNT: '/api/customer/notifications/unread-count',
  CUSTOMER_NOTIFICATIONS_READ_ALL: '/api/customer/notifications/read-all'
}

// 客户ID（兼容旧代码，优先从 JWT 获取）
const getCustomerId = () => {
  return wx.getStorageSync('customerId') || 1
}

module.exports = { getBaseUrl, API, getCustomerId }
