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
  BARREL_RETURN_PREVIEW: '/api/barrels/return/preview',

  // 支付（后端: PaymentController）
  PAYMENTS: '/api/payments',
  PAYMENT_QUOTE: '/api/payments/quote',
  PAYMENT_RECORDS: '/api/payments/by-customer',

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

  // 注意：不要在此处再定义 SEARCH('/api/search')。
  // /api/search 是站长端"搜客户/地址/订单"的接口（@RequireRole STATION_MANAGER），
  // 不是顾客的商品搜索；顾客搜商品走 GET /api/products?keyword=xxx。
  // 曾因注释误导被误用，导致用户端搜索恒定报"当前账号未绑定水站"。

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

// 客户ID：统一从登录态获取（正确的存储键 aq_user_customerId，由 utils/token.js 维护）。
// 注意：禁止写死兜底值（|| 1）。旧实现读的是错误键名 'customerId'（正确键为 aq_user_customerId）
// 且兜底 || 1，导致所有客户都被识别成 1 号客户——付款、下单、订单归属全部错乱。
// 取不到身份就抛错，让上层跳登录，而不是用兜底常量掩盖问题。
const { getCustomerId: getCustomerIdFromToken } = require('../utils/token')

const getCustomerId = () => {
  const id = getCustomerIdFromToken()
  if (id === undefined || id === null || id === '') {
    throw new Error('未获取到客户身份，请重新登录')
  }
  return id
}

module.exports = { getBaseUrl, API, getCustomerId }
