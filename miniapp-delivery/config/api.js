// API配置
// [2026-09-22 结论] 与顾客端一致：这一行的值由 `scripts/set-dev-api-host.ps1 -Apply` 维护，**不要手改** ——
//   热点开着 → 写 `192.168.137.1`（笔记本移动热点，恒定）；热点关着 → 写**当前 WLAN 地址**（现在就是它）。
//   09-21 曾写死笔记本热点地址，但 09-21、09-22 实测热点一直是**关的** → 那个地址没人连得上、真机全废；
//   **判据：默认值要指向「现在真能用」的那个地址，不是「配置上最优雅」的那个**
//   （详见 miniapp-user/config/api.js 头部注释）。
//   ⚠️ **真机要求手机与笔记本连同一个 Wi-Fi**（真机走的就是这一行）。
//   真机上的 127.0.0.1 指的是**手机自己**，必然失败且只报「网络错误」，所以回环只给开发者工具用。
//   真机预览还需在手机上打开「调试」（右上角 ... → 打开调试）跳过域名校验；
//   体验版(trial)/正式版(release) 都走 prod 的真实域名 —— 见下方 getBaseUrl 的判定。
//   ⚠️ 换地址跑 `scripts/set-dev-api-host.ps1 -Apply`（同时改两端；**只改一端那一端会白屏**，
//      现象同样是"网络错误"）。
const API_CONFIG = {
  // devtoolsBaseUrl：开发者工具跑在**同一台笔记本**上，走回环即可 —— 换网络、开关热点都不影响写代码。
  dev: { baseUrl: 'http://192.168.0.243:8080', devtoolsBaseUrl: 'http://127.0.0.1:8080' },
  prod: { baseUrl: 'https://your-domain.com' }
}

/**
 * 当前是否运行在**微信开发者工具**里（不是真机）。
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
 * <p>⚠️ **取不到设备信息时返回 false（= 当作真机）**：误判成工具会把真机指到 127.0.0.1
 * （= 手机自己），必然失败且只报「网络错误」；误判成真机只是多走一次局域网地址，本来就该走它。</p>
 */
const PC_PLATFORMS = ['devtools', 'windows', 'mac']

const isDevtools = () => {
  try {
    // getDeviceInfo 需要基础库 2.20.1+；取不到就退回 getSystemInfoSync。
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
 * <p>[2026-09-20] 供登录页决定要不要显示「开发者登录」按钮用：正式版必须藏起来
 * （按钮直连 `/api/auth/dev-login`，该端点在 prod profile 下**根本不存在**，
 * 但让站长/配送员在正式版看到"开发者登录"本身就是事故级观感）。</p>
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
  MANAGER_BIND_APPROVE: '/api/manager/bind/approve',           // 通过绑定申请(type=1)
  MANAGER_BIND_REJECT: '/api/manager/bind/reject',             // 拒绝绑定申请(type=1)
  // [2026-09-16 修复] 解绑申请(type=2)必须走独立端点。此前无论绑定还是解绑都调
  // /approve，而后端 approveBind 对 type!=1 直接返回「这不是绑定申请」→ 配送员
  // 提交解绑后申请永久悬挂、卡在等待页无法撤回。
  MANAGER_BIND_UNBIND_CONFIRM: '/api/manager/bind/unbind-confirm', // 同意解绑申请(type=2)
  MANAGER_BIND_UNBIND_REJECT: '/api/manager/bind/unbind-reject',   // 拒绝解绑申请(type=2)
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
  PAYMENT_REFUND_COMPONENT: (id) => `/api/payments/${id}/refund`,

  // 客户
  CUSTOMERS: '/api/customers',
  CUSTOMER_DETAIL: (id) => `/api/customers/${id}`,
  CUSTOMER_PROFILE: (id) => `/api/customers/${id}/profile`,
  // 客户在本站的资产（水桶/水票/押金）。水站由后端按登录站长判定，前端不传 stationId
  CUSTOMER_ASSETS: (id) => `/api/customers/${id}/assets`,
  CUSTOMER_OFFLINE_PAYMENT: (id) => `/api/customers/${id}/offline-payment`,

  // 企业身份申请（后端: EnterpriseController，v50）：站长在客户列表页审核。
  // 功能有总开关（app.enterprise.enabled，默认关）；关着时列表端点**返回空列表**，不报错。
  ENTERPRISE_APPLICATIONS: '/api/enterprise/manager/applications',
  ENTERPRISE_APPLICATION: (id) => `/api/enterprise/manager/applications/${id}`,
  // 站级「企业身份提示阈值」（v51）：桶数 与/或 水费金额，站长自己设；不设=用平台默认（30 桶）。
  // ⚠️ 平台级总开关没有对应端点，也没有界面（产品：平台级不做前端可视化）。
  ENTERPRISE_CONFIG: '/api/enterprise/manager/config',

  // 综合数据报表（range=today|7d|30d）。水站由后端按登录站长判定，前端不传 stationId
  DASHBOARD_REPORT: '/api/dashboard/report',

  // 订单
  ORDERS: '/api/orders',

  // 水站
  STATION_SEARCH: '/api/stations/search',           // 公开搜索，供配送员申请绑定前使用
  STATION_GET: '/api/stations/mine',
  // 水站资料（名称/电话/地址/状态）—— 2026-09-19 接线。
  // ⚠️ 后端这条是**整行覆盖**更新（name/phone/address/status 全写一遍），所以调用方
  // **必须把四项都给全**，只传要改的那一项会把其余三项写成 NULL（本仓"整行覆盖"事故已多次）。
  // 由此也要求 `creator_staff_id` 必须已回填：后端鉴权走 `getByIdAndCreator`（按创建人校验），
  // 创建人字段为 NULL 时站长会拿不到自己的站。
  STATION_UPDATE: (id) => `/api/stations/${id}`,
  // 水站坐标（地图选点，v34）。单独一个端点而不是并进站点编辑 ——
  // 后者是整行覆盖，旧客户端不传坐标会把已选的坐标冲成 NULL。
  STATION_MY_COORDINATES: '/api/stations/mine/coordinates',

  // 库存
  INVENTORY: '/api/inventory',
  INVENTORY_INBOUND: '/api/inventory/inbound',
  INVENTORY_RECORDS: '/api/inventory/records',
  INVENTORY_LOSS: (id) => `/api/inventory/${id}/loss`,

  // ===== 商品与库存（2026-09-16 重构）：通用商品库 + 本站设置 + 自定义商品 =====
  // 站长能改的只有"本站"的东西（上架/库存/本站售价/水票/优先展示）；
  // 通用库商品的名称规格图片由平台维护，站长只读 —— 所以没有"改商品"的接口。
  MANAGER_CATALOG: '/api/manager/catalog',
  MANAGER_CATALOG_ITEM: (id) => `/api/manager/catalog/${id}`,
  MANAGER_CATALOG_SELECT: (id) => `/api/manager/catalog/${id}/select`,
  MANAGER_CATALOG_STOCK: (id) => `/api/manager/catalog/${id}/stock`,
  MANAGER_CATALOG_PRESET_IMAGES: '/api/manager/catalog/preset-images',
  MANAGER_MY_PRODUCTS: '/api/manager/my-products',
  MANAGER_MY_PRODUCT: (id) => `/api/manager/my-products/${id}`,
  MANAGER_MY_PRODUCT_SUBMIT: (id) => `/api/manager/my-products/${id}/submit`,
  MANAGER_MY_SUBMISSIONS: '/api/manager/my-products/submissions',

  // 水站营业状态（软状态，2026-09-17）：**不阻断下单**，只给顾客弹提示；
  // 站长可写一句留言（如"今天休息，明早正常送"）。硬状态（停业）是另一套，见 station.status。
  // [2026-09-23] 新注册水站默认 **3 待上线**（原 3 是「配送延迟」，已改名），
  // 状态取值/文案/选项一律由后端下发，前端不要自带 1..4 映射表。
  MANAGER_STATION_STATUS: '/api/manager/station-status',

  // 站长「信息完善引导」（后端 StationSetupGuideService）：逐条给
  // {key,label,level,done,where,route,why,suggestion,action}。
  // ⚠️ `route` 是**机器可读的跳转目标**（后端按 key 下发），营业状态页的「待填项」
  // 直接 wx.navigateTo(route) —— 前端**不要**自己维护 key→路由映射表（改页面路径时必然对不上）。
  MANAGER_SETUP_GUIDE: '/api/manager/setup-guide',

  // 公告：站长发本站公告（顾客端只读已发布的；员工端 /all 只看本站）
  NOTICES: '/api/notices',
  NOTICES_ALL: '/api/notices/all',
  NOTICE: (id) => `/api/notices/${id}`,

  // 站长资产调整单（人工补录 / 代客订正，站长专属）。
  // 水站一律由后端按登录站长判定，前端不传 stationId。
  // 注意：列表与创建是同一路径、不同方法；拆成两个常量只为调用处一眼看清语义。
  MANAGER_ADJUSTMENTS: '/api/manager/adjustments',
  MANAGER_ADJUSTMENT: (id) => `/api/manager/adjustments/${id}`,
  MANAGER_ADJUSTMENT_CREATE: '/api/manager/adjustments',
  MANAGER_ADJUSTMENT_PREVIEW: '/api/manager/adjustments/preview',
  MANAGER_ADJUSTMENT_EXECUTE: (id) => `/api/manager/adjustments/${id}/execute`,
  MANAGER_ADJUSTMENT_REVERSE: (id) => `/api/manager/adjustments/${id}/reverse`,

  // 欠桶台账（v29）：本站当前仍欠桶的客户，按欠得最久排序。
  // 只读、只预警（下单是否放行与欠桶无关）；水站由后端按登录站长判定，前端不传 stationId。
  MANAGER_OWED_BARRELS: '/api/manager/owed-barrels',

  // 站长端桶异常单（只读列表 + 近 30 天统计；`/stats` 是同前缀的另一个只读端点）。
  // [2026-09-19] 唯一消费方 = 「异常订单」页的页签 1。
  // ⚠️ 损耗读数 `/api/manager/barrel-loss` 已无任何页面调用（同一批合并时删掉了那张恒为 0 的卡），
  //    后端端点仍在 —— 见 docs/audit/history/review/2026-09-16-死端点评估.md「删除登记表」#12。
  MANAGER_EXCEPTIONS: '/api/manager/exceptions',

  // 本站运营告警（v30）：只读。后端固定只返回 alert_type='OPERATION' 且本站的记录 ——
  // 系统故障告警是发给系统管理员的（带平台级细节），故意不外露给站长，前端也不要试图展示。
  // [2026-09-19] 由「异常订单」页的页签 2「处理留痕」消费（原独立页面已废弃，见登记表 #13）。
  MANAGER_ALERTS: '/api/manager/alerts',

  // 待办汇总（2026-09-19）：15 项按 P0/P1/P2 分级 + p0Total。首页红点与「其他待办」卡的唯一数据源
  // （口径、为什么与 todo-summary 并存见 ManagerPendingSummaryController 文件头）。
  MANAGER_PENDING_SUMMARY: '/api/manager/pending-summary',

  // 站间结算台账（v67 / 2026-09-27 产品拍板 4.a+4.b）：跨站单的「谁欠谁、欠多少、什么时候算办完」。
  // 站长专属（后端整类 @RequireRole("STATION_MANAGER")）。
  MANAGER_INTER_STATION_SETTLEMENTS: '/api/manager/inter-station-settlements',
  // {orderId} 在路径中间 —— 一律用下面三个构造函数，**不要**在页面里手拼字符串
  // （路径的 SSOT 就在这一张表里，散落的字面量在端点改名时改不干净）。
  MANAGER_INTER_STATION_SETTLE: (orderId) => `/api/manager/inter-station-settlements/${orderId}/settle`,
  MANAGER_INTER_STATION_REVERSE: (orderId) => `/api/manager/inter-station-settlements/${orderId}/reverse`,
  MANAGER_INTER_STATION_PRICE_LISTED: (orderId) => `/api/manager/inter-station-settlements/${orderId}/price-by-listed`,

  // 毛利 + 净利报表（v39 / 2026-09-19 加净利）：站长专属 —— 成本价是站长的商业机密，
  // 这个路径**不要**出现在顾客端或配送员会调的地方（后端整类带 @RequireRole("STATION_MANAGER")）。
  // 期间由 from/to 决定（缺省本月 1 号~今天）；「今日净利」= from=to=今天。
  MANAGER_GROSS_PROFIT: '/api/manager/gross-profit',

  // 仪表盘
  // [2026-09-19 删除] DASHBOARD_TODAY / DASHBOARD_OVERVIEW 两个常量（连同 api/station-mgmt.js
  // 里的包装函数）已删，看板一律走下面的 DASHBOARD_REPORT。
  // 证据见 docs/audit/history/review/2026-09-16-死端点评估.md「删除登记表」#1；⚠️ 它们在时会让审计脚本
  // 以为端点"有人调"（**定义 ≠ 调用**），所以那条登记特意注明是用 grep 逐条证的。

  // 反馈
  FEEDBACK: '/api/feedback',
  FEEDBACK_MY: '/api/feedback/my',
  // 站长看「落到本站的客户反馈」（只读）；与上面两个是不同人群，见 api/feedback.js
  FEEDBACK_CUSTOMERS: '/api/feedback/customers',

  // 水桶
  BARRELS_ALL_RECORDS: '/api/barrels/all-records',
  BARRELS_RECORDS_STATUS: (id) => `/api/barrels/records/${id}/status`,
  BARREL_REFUND_ELIGIBILITY: (id) => `/api/barrels/records/${id}/refund-eligibility`,
  // [v66 / 2026-09-27 产品拍板 5.a] 押金**实际交付**确认：站长（或代交的配送员）确认
  // "这笔押金已经交到顾客手上了"。⚠️ 与 status 那一步是**两件事**：status 是核销（钱从账户扣掉），
  // 这一条才是把钱交出去 —— 见 docs/design/35 §7.2「不现场给钱的不要退」。
  BARRELS_RECORDS_REFUND_PAID: (id) => `/api/barrels/records/${id}/refund-paid`,
  // 只读：已核销但**没记交付**的退押金（不合规数据，站长要能查出来）
  BARRELS_REFUND_UNDELIVERED: '/api/barrels/refund-undelivered',
  BARRELS_RETURN_APPROVE: (id) => `/api/barrels/records/${id}/approve`,
  BARRELS_RETURN_ARRANGEMENT: (id) => `/api/barrels/records/${id}/manager-arrangement`,
  MANAGER_BUSINESS_WAITING: '/api/manager/business-waiting',
  MANAGER_TICKET_EXIT_BATCHES: '/api/manager/ticket-exit-batches',
  MANAGER_REFUSAL_CASES: '/api/manager/refusal-cases',
  MANAGER_REFUSAL_FREEZE: (id) => `/api/manager/refusal-cases/${id}/confirm-freeze`,
  MANAGER_STATION_RECOVERIES: '/api/manager/inter-station-recoveries',
  MANAGER_RECOVERY_SENT: (id) => `/api/manager/inter-station-recoveries/${id}/sent`,
  MANAGER_RECOVERY_RECEIVED: (id) => `/api/manager/inter-station-recoveries/${id}/received`,
  MANAGER_STATION_BARREL_BALANCES: '/api/manager/station-barrel-balances',
  MANAGER_BARREL_DISPUTE: (id) => `/api/manager/station-barrel-balances/${id}/dispute`,
  MANAGER_BARREL_PROPOSAL: (id) => `/api/manager/station-barrel-balances/${id}/proposal`,
  MANAGER_BARREL_AGREE: (id) => `/api/manager/station-barrel-balances/${id}/agree`,
  MANAGER_BARREL_BALANCE_RECEIVED: (id) => `/api/manager/station-barrel-balances/${id}/received`,
  MANAGER_DISPATCH_AGREEMENT: (id) => `/api/manager/dispatch-agreements/${id}`,
  PAYMENT_REFUND_PREVIEW: (id) => `/api/payments/${id}/refund-preview`,
  BARRELS_RETURN_EMPTY: '/api/barrels/return-empty',

  // 上传
  ORDER_IMAGE_UPLOAD: '/api/order-images/upload',
  GENERAL_UPLOAD: '/api/common/upload',

  // 抢单池 & 外派
  DELIVERY_POOL: '/api/delivery/orders/pool',
  // 本站外派出去的单：响应里带 dispatchKind（POOL 一键外派 / DIRECTED 指定外派）与 deliveryStationName，
  // 首页「外派」页签按它分成两个子页签（形态由后端判，前端不解析 specialNote）。
  DELIVERY_DISPATCH_TRACKING: '/api/delivery/orders/dispatch-tracking',
  DELIVERY_DIRECTED_RETURNS: '/api/delivery/orders/directed-returns',
  // 别站指定外派给本站的单（首页「外派 → 指定外派」里的一个方向）
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

module.exports = { getBaseUrl, isReleaseEnv, API, BINDING_STATUS }
