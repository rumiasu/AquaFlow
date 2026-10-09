// API配置
// [2026-09-22 结论] 下面这一行的值**不要手改**，由 `scripts/set-dev-api-host.ps1 -Apply` 维护：
//   热点开着 → 写 `192.168.137.1`（笔记本移动热点，恒定）；
//   热点关着 → 写**当前 WLAN 地址**（现在就是这一行里的那个值）。
//   为什么不在代码里写死成"最优雅"的那个值：09-21 曾写死笔记本热点的 `192.168.137.1`，
//   但 09-21、09-22 两次实测热点都是**关的** → 那个地址**没人连得上**、真机全废，
//   而当下真实可用的 WLAN 地址反倒没被用上。
//   **判据：默认值必须指向「现在真能用」的那个地址，不是「配置上最优雅」的那个。**
//   ⚠️ **真机要求手机与笔记本连同一个 Wi-Fi**（真机走的就是这一行）。
//   真机（预览扫码）上的 127.0.0.1 指的是**手机自己**，必然失败且只报「网络错误」，极难定位 ——
//   所以回环只给开发者工具用（见 devtoolsBaseUrl）。
//   真机预览还需在手机上打开「调试」（右上角 ... → 打开调试）跳过域名校验，
//   因为 request 合法域名只接受已 ICP 备案的 HTTPS 域名，本机 HTTP 地址无法配置。
//   当前仅正式版(release) 走 prod；体验版(trial)/开发版(develop) 走 dev，不能当正式域名验收。
//   ⚠️ 换地址跑 `scripts/set-dev-api-host.ps1 -Apply`（它**同时改两端**；只改一端会让那一端白屏，
//      现象同样是"网络错误"，很难联想到是 IP 不对）。
const API_CONFIG = {
  // devtoolsBaseUrl：开发者工具跑在**同一台笔记本**上，走回环即可 —— 换网络、开关热点都不影响写代码。
  dev: { baseUrl: 'http://192.168.0.243:8080', devtoolsBaseUrl: 'http://127.0.0.1:8080' },
  prod: { baseUrl: 'https://your-domain.com' }
}

/**
 * 当前是否运行在**微信开发者工具**里（不是真机）。
 *
 * <p>[2026-09-21] 只用于把 dev 拆成「工具走回环、真机走上面那个 dev 地址」两个地址。</p>
 *
 * <p>[2026-09-22 修正判据] 原来只认 `platform === 'devtools'`，但开发者工具在 Windows / macOS 上
 * 跑的其实是个 **PC 客户端**，`platform` 可能报 `devtools`、也可能报成 `windows` / `mac`
 * （都是 `getDeviceInfo` 的合法值）→ 只认 devtools 会**漏判**，工具里白白走局域网地址。
 * 现在三个都算「工具」。</p>
 *
 * <p>⚠️ 真机（手机预览 / **真机调试**）报的是 `ios` / `android`，不会被误判
 * （本仓 `miniapp-user/pages/login/index.js` 2026-09-20 已核实过这一点）。</p>
 *
 * <p>代价：真在微信 PC 客户端里跑小程序时也会命中（那时 127.0.0.1 指那台 PC）—— 本项目不发 PC 端，
 * 且这条只在非 release 生效，可接受。</p>
 *
 * <p>⚠️ **取不到设备信息时返回 false（= 当作真机）**，这是刻意的"失败开口"：
 * 误判成真机只是多走一次局域网地址（真机上本来就该走它）；
 * 误判成工具则会把真机指到 127.0.0.1（= 手机自己）—— 必然失败，且只报"网络错误"。</p>
 */
const PC_PLATFORMS = ['devtools', 'windows', 'mac']

const isDevtools = () => {
  try {
    // getDeviceInfo 需要基础库 2.20.1+；取不到就退回 getSystemInfoSync（同 pages/home 的防御写法）。
    const info = wx.getDeviceInfo ? wx.getDeviceInfo() : wx.getSystemInfoSync()
    return PC_PLATFORMS.indexOf(info.platform) !== -1
  } catch (e) {
    return false
  }
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
  const envConfig = API_CONFIG[env]
  // 只有 dev 才分「工具 / 真机」；prod 两端都走真实域名。
  // ⚠️ 顺序是「**先确认是工具**才用回环」，不是「猜不到就用回环」—— 失败必须朝向真机地址，
  //    反了会把真机指到 127.0.0.1（手机自己），必然失败且只报「网络错误」。
  const baseUrl = env === 'dev' && envConfig.devtoolsBaseUrl && isDevtools()
    ? envConfig.devtoolsBaseUrl
    : envConfig.baseUrl
  // 占位域名直接拦下来报清楚，否则真机上只看到超时/网络错误，排查成本极高。
  if (baseUrl.indexOf('your-domain.com') !== -1) {
    throw new Error('正式环境域名还是占位符 https://your-domain.com，请先在 config/api.js 里替换为已备案的真实域名')
  }
  return baseUrl
}

/**
 * 当前是否处于**正式版**（`envVersion === 'release'`）。
 *
 * <p>[2026-09-20] 供登录页决定要不要显示「测试账号直接登录」按钮用：正式版必须藏起来
 * （那个按钮直连 `/api/auth/dev-login`，虽然该端点在 prod profile 下**根本不存在**，
 * 但让顾客看到"测试账号直接登录"本身就是事故级观感）。</p>
 *
 * <p>⚠️ **取不到环境时返回 false（= 当作非正式版）**，这是刻意的"失败开口"：
 * 真机联调时 `dev-login` 是微信登录不通时的唯一退路（见 AGENTS §9.4），
 * 宁可在正式版上多显示一个点了会报错的按钮，也不能因为读不到 `__wxConfig`
 * 就把真机联调的退路悄悄藏掉 —— 后者会让人在客户现场无路可走。</p>
 */
const isReleaseEnv = () => {
  try {
    return __wxConfig.envVersion === 'release'
  } catch (e) {
    return false
  }
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
  BARREL_RIGHTS: '/api/barrel-rights',
  BARREL_RIGHT_QUOTE: '/api/barrel-rights/quote',
  BARREL_RIGHT_PURCHASE: '/api/barrel-rights/purchase',
  BARREL_RIGHT_WITHDRAW: (id) => `/api/barrel-rights/${id}/withdraw`,
  BARREL_RETURN_CONFIRM: (id) => `/api/barrels/records/${id}/customer-confirm`,
  BARREL_RETURN_ARRANGEMENT: (id) => `/api/barrels/records/${id}/arrangement`,
  BARREL_RETURN_WITHDRAW: (id) => `/api/barrels/records/${id}/withdraw`,
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

module.exports = { getBaseUrl, isReleaseEnv, API, getCustomerId }
