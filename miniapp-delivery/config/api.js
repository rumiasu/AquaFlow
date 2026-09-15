// API配置
// [2026-09-15] dev.baseUrl 必须是「运行后端那台电脑的局域网 IP」，不能是 127.0.0.1/localhost。
//   真机（预览二维码扫出来的开发版）上的 127.0.0.1 指的是**手机自己**，请求必然失败，
//   且失败表现为「网络错误」而非业务报错，极难定位。换网络/换路由器后 IP 会变，需同步改这里。
//   真机预览还需在手机上打开「调试」（右上角 ... → 打开调试）跳过域名校验，
//   因为 request 合法域名只接受已 ICP 备案的 HTTPS 域名，本机 HTTP 地址无法配置。
//   体验版(trial)/正式版(release) 都必须走 prod 的真实域名 —— 见下方 getBaseUrl 的判定。
const API_CONFIG = {
  dev: { baseUrl: 'http://192.168.0.243:8080' },
  prod: { baseUrl: 'https://your-domain.com' }
}

const getBaseUrl = () => {
  // 这里只包住环境读取：不要把下面的域名校验一起包进来，
  // 否则它抛出的明确提示会被外层 catch 覆盖成「无法获取小程序环境版本」，反而更难排查。
  let envVersion
  try {
    envVersion = __wxConfig.envVersion
  } catch (e) {
    throw new Error('无法获取小程序环境版本，请勿在非微信开发者工具中运行')
  }
  // 注意：体验版(envVersion='trial') 与开发版('develop') 都落到 dev。
  // 曾因此让「上传后的体验版」静默指向本机地址，扫码后一片网络错误。
  const env = envVersion === 'release' ? 'prod' : 'dev'
  const baseUrl = API_CONFIG[env].baseUrl
  // 占位域名直接拦下来报清楚，否则真机上只看到超时/网络错误，排查成本极高。
  if (baseUrl.indexOf('your-domain.com') !== -1) {
    throw new Error('正式环境域名还是占位符 https://your-domain.com，请先在 config/api.js 里替换为已备案的真实域名')
  }
  return baseUrl
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
  MANAGER_BIND_RELEASE: '/api/manager/bind/release',           // 站长单方面解除配送员绑定
  MANAGER_STAFF: '/api/manager/staff',                         // 站长查看本站员工(含申请中)

  // ==================== 配送订单 ====================
  DELIVERY_ORDERS: '/api/delivery/orders',
  DELIVERY_PENDING: '/api/delivery/orders/pending',
  DELIVERY_DELIVERING: '/api/delivery/orders/delivering',
  DELIVERY_DELIVERED_UNPAID: '/api/delivery/orders/delivered-unpaid',
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

  // 水站
  STATION_SEARCH: '/api/stations/search',           // 公开搜索，供配送员申请绑定前使用
  STATION_GET: '/api/stations/mine',

  // 库存
  INVENTORY: '/api/inventory',

  // 商品（V1 不再使用 water-types）
  WATER_TYPES: '/api/products',
  WATER_TYPE_UPDATE: (id) => `/api/products/${id}`,

  // 管理端: 商品+库存统一管理 (站长专用)
  MANAGER_PRODUCTS: '/api/manager/products',
  MANAGER_PRODUCT: (id) => `/api/manager/products/${id}`,
  MANAGER_PRODUCT_SHELF: (id) => `/api/manager/products/${id}/shelf`,
  MANAGER_PRODUCTS_INBOUND: '/api/manager/products/inbound',

  // 站长资产调整单（人工补录 / 代客订正，站长专属）。
  // 水站一律由后端按登录站长判定，前端不传 stationId。
  // 注意：列表与创建是同一路径、不同方法；拆成两个常量只为调用处一眼看清语义。
  MANAGER_ADJUSTMENTS: '/api/manager/adjustments',
  MANAGER_ADJUSTMENT: (id) => `/api/manager/adjustments/${id}`,
  MANAGER_ADJUSTMENT_CREATE: '/api/manager/adjustments',
  MANAGER_ADJUSTMENT_PREVIEW: '/api/manager/adjustments/preview',
  MANAGER_ADJUSTMENT_EXECUTE: (id) => `/api/manager/adjustments/${id}/execute`,
  MANAGER_ADJUSTMENT_REVERSE: (id) => `/api/manager/adjustments/${id}/reverse`,

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

  // 抢单池 & 外派追踪
  DELIVERY_POOL: '/api/delivery/orders/pool',
  DELIVERY_DISPATCH_TRACKING: '/api/delivery/orders/dispatch-tracking',
  DELIVERY_DIRECTED_RETURNS: '/api/delivery/orders/directed-returns',
  DELIVERY_DIRECTED_INCOMING: '/api/delivery/orders/directed-incoming',
  // 站长「审批」页：客户 / 站内 两组待决策申请（已接单订单的取消须站长同意）
  DELIVERY_PENDING_APPROVALS: '/api/delivery/orders/pending-approvals',
  // ⚠️ 关于「带 {id} 的路径」：本端 POST 路由的 {id} 多在路径**中间**
  // （如 /api/delivery/orders/{id}/cancel-dispatch），一律用 DELIVERY_ORDERS + `/${id}/...` 拼接；
  // 不要再定义「完整路径常量」再往末尾追加 id —— 那样会打到不存在的地址，
  // 前端只会看到「系统错误，请联系管理员」，极难定位。
  // [2026-09-14] 已删除 19 个此类废弃常量（TRANSFER_POOL / RETURN_REQUEST / DELIVERY_ACCEPT 等）；
  // 其中多数**指向后端根本不存在的接口**，留着属于"错误信息"而不只是冗余。
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
