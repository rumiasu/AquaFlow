// API配置
// [2026-09-15] dev.baseUrl 必须是「运行后端那台电脑的局域网 IP」，不能是 127.0.0.1/localhost。
//   真机（预览二维码扫出来的开发版）上的 127.0.0.1 指的是**手机自己**，请求必然失败，
//   且失败表现为「网络错误」而非业务报错，极难定位。换网络/换路由器后 IP 会变，需同步改这里。
//   真机预览还需在手机上打开「调试」（右上角 ... → 打开调试）跳过域名校验，
//   因为 request 合法域名只接受已 ICP 备案的 HTTPS 域名，本机 HTTP 地址无法配置。
//   体验版(trial)/正式版(release) 都必须走 prod 的真实域名 —— 见下方 getBaseUrl 的判定。
//   [2026-09-20 第二次过期] 192.168.0.243 ⇄ 10.213.244.181 来回变过一次（今天是 192.168.0.243）。
//   根因：本机 WLAN 网卡是 **DHCP**（`netsh interface ipv4 show config name="WLAN"` → DHCP enabled: Yes），
//   地址由路由器 192.168.0.1 按租约发放（当天 10:41 取的租约、22:55 到期）。换网络/路由器重启/
//   租约重分配都会换地址，**这不是 bug、也不是后端的问题**。
//   彻底解决（三选一，见 docs/本地运行-笔记本当服务器.md §1）：
//     ① 路由器里给本机 MAC `2C-98-11-4E-67-2B` 做 **DHCP 地址保留** → 永久固定成 192.168.0.243（推荐）；
//     ② 把 WLAN 网卡改成**静态 IP**（换到别的网络要记得改回 DHCP，否则上不了网）；
//     ③ 改用 **Windows 移动热点**：笔记本自己当 DHCP 服务端，地址恒为 `192.168.137.1`
//        （手机连笔记本热点，不依赖任何路由器，最适合实机测试）。
//   改之前先跑 `scripts/set-dev-api-host.ps1`：它按当前 WLAN 地址同时改写两端这一行，省得"只改一端"。
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

// 与后端 Controller 路径一一对应
const API = {
  // 认证（后端: LoginController）
  LOGIN: '/api/auth/login',
  WX_LOGIN: '/api/auth/wx-login',
  DEV_LOGIN: '/api/auth/dev-login',
  REFRESH: '/api/auth/refresh',
  ME: '/api/auth/me',
  UPDATE_PROFILE: '/api/auth/update-profile',

  // 商品（后端: ProductController）
  PRODUCTS: '/api/products',
  PRODUCTS_SALE: '/api/products/on-sale',
  // 注意：MY_PRODUCTS('/api/products/my') 已于 2026-09-16 随"恒空端点"清理一并删除，
  // '已有商品'标签因此下线；要恢复得先在后端实现真实的"客户已购商品"。
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
  // 水票档位价目表（后端: TicketPackageController）。站级 + 商品级，公开只读、只返回上架档位。
  TICKET_PACKAGES: '/api/ticket-packages',

  // 水桶（后端: BarrelController）
  BARREL_SUMMARY: '/api/barrels/summary',
  BARREL_SUMMARY_BY_TYPE: '/api/barrels/summary-by-type',
  BARREL_RECORDS: '/api/barrels/records',
  BARREL_RETURN: '/api/barrels/return',
  BARREL_RETURN_PREVIEW: '/api/barrels/return/preview',

  // 支付（后端: PaymentController）
  PAYMENTS: '/api/payments',
  PAYMENT_QUOTE: '/api/payments/quote',

  // 公告（后端: NoticeController）
  NOTICES: '/api/notices',

  // 企业资料（后端: CompanyInfoController）
  COMPANY_INFO: '/api/company-info',

  // 企业身份申请（后端: EnterpriseController，v50）。**刻意没有独立入口**：
  // 只有下单页拿到报价的 enterpriseHint（订单金额达阈值）后，由那个弹窗带进来。
  // 服务端有总开关（app.enterprise.enabled，默认关），关掉时这两个端点会真的返回
  // 业务错误「企业身份功能当前未开启」—— 前端不要把它当异常吞掉，也不要自己藏入口，
  // 直接展示服务端给的那句话即可。
  ENTERPRISE_APPLICATIONS: '/api/enterprise/applications',
  ENTERPRISE_APPLICATIONS_MY: '/api/enterprise/applications/my',

  // 水站（后端: StationController）
  STATIONS: '/api/stations',
  STATIONS_PUBLIC: '/api/stations/public',

  // 意见反馈（后端: FeedbackController）
  FEEDBACK: '/api/feedback',
  FEEDBACK_MY: '/api/feedback/my',

  // 订单图片（后端: OrderImageController）
  ORDER_IMAGES: '/api/order-images',

  // 注意：不要在此处再定义 SEARCH('/api/search')。
  // /api/search 是站长端"搜客户/地址/订单"的接口（@RequireRole STATION_MANAGER），
  // 不是顾客的商品搜索；顾客搜商品走 GET /api/products?keyword=xxx。
  // 曾因注释误导被误用，导致用户端搜索恒定报"当前账号未绑定水站"。

  // 注意：站长端接口（/api/manager/*、/api/stations/mine 等）一律不要定义在顾客端配置里 ——
  // 顾客 token 调它们必然 403，而这些失败往往被静默吞掉，属最难排查的一类缺陷
  // （历史上 /api/stations/mine 就被误用在模板页与下单页，导致功能静默失效）。
  // 「当前服务水站」请统一走 utils/station.js 的 resolveStationId()。

  // 客户端：我的异常记录（后端: CustomerExceptionController）
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
