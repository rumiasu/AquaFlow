// 站长管理相关接口
const { get, post, put, del } = require('../utils/request')
const { API } = require('../config/api')

// [2026-09-19 删除] 这里原有 getDashboardToday / getDashboardOverview 两个包装函数
// （→ /api/dashboard/today、/api/dashboard/overview）。删除理由与证据见
// docs/audit/2026-09-16-死端点评估.md 的「删除登记表」#1：
//   · 全端 grep 只命中它们自身的定义与导出，**没有任何页面调用**；
//   · 看板页早已改用下面的 getDashboardReport（/api/dashboard/report）；
//   · 它们还接受客户端传的 stationId —— 后端只看登录态、传了会被忽略，
//     所以不是漏洞，但是"让人以为能查别站"的错误示范。
// ⚠️ 那个审计脚本当初**没把它们报成死端点**（config/api.js 里有路径常量，脚本算作"有调用"）——
//    核实删除时必须落到"有没有页面真调"，**定义 ≠ 调用**。

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

// 跨站履约单（本站是履约站、归属站是别站）：一次拿到总数/金额合计/按状态分类/明细。
// 明细里**没有**客户画像（后端掩码），只有订单自身信息（收件人、地址、金额）。
const getCrossStationOrders = () => {
  return get(`${API.DELIVERY_ORDERS}/cross-station`)
}

// 客户
// keyword 可选（不传 = 全量）：姓名 / 电话 / 地址片段，支持缩写「阳光81301」与中英数字混用「八栋/8栋」。
// 搜索在服务端做（归一化 + 相关性排序），页面不要再本地 filter name/phone —— 那会把地址命中整条丢掉。
const getCustomers = (stationId, keyword) => {
  const query = { stationId }
  if (keyword) query.keyword = keyword
  return get(API.CUSTOMERS, query)
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

// 开通/关闭货到付款（v48 的「首单是否放行 / 单笔上限」两项已按产品裁定撤回，见 migration_v49）。
//
// ⚠️ 2026-09-19 事故：本函数在这里被**声明了两次**（v49 那次改写时把旧的 `(id, enabled)` 版本
// 留在下面没删）→ `SyntaxError: Identifier 'updateOfflinePayment' has already been declared`
// → **整个模块加载失败**，而模块里还有订单/商品/库存/工资/对账等全部站长接口 ——
// 表现出来是站长端一堆页面同时白屏，而四个静态门禁与后端用例**一个都报不出来**
// （它们不解析 delivery 端的 js）。现已加门禁 `audit_js_syntax.py`（node --check 全量小程序 js）。
// 只保留下面这一个签名：payload 由调用方显式给全，别再写"布尔开关"的重载形态。
const updateOfflinePayment = (id, payload) => {
  return put(API.CUSTOMER_OFFLINE_PAYMENT(id), payload)
}

// 开通弹窗的依据（v48）：当前配置 + 该客户在本站的欠款/逾期 + 历史订单数 + 此刻能不能用（不能用给原因）
const getOfflinePaymentSummary = (id) => {
  return get(`${API.CUSTOMER_OFFLINE_PAYMENT(id)}/summary`)
}

// ===== 商品与库存（2026-09-16 重构后的唯一入口）=====
//
// 语义要点（见 docs/design/12-商品与库存重构.md）：
//   · 选品目录 = 通用库 + 本站自定义商品；`selected` 表示本站是否已配置过；
//   · 站长只能改"本站设置"（上架/售价/押金/水票/优先展示/库存）；
//   · 库存**不能**在设置里直改：加数量走入库（写 INBOUND 流水），盘数量走 stock（写 ADJUST 流水）；
//   · 水站一律由后端按登录站长判定，前端不传 stationId。

/** 选品目录（含本站状态：selected/enabled/quantity/salePrice/effectivePrice/...） */
const getCatalog = () => {
  return get(API.MANAGER_CATALOG)
}

/**
 * 平台预设商品图清单（建自定义商品时选图用）。
 * 返回 [{ key, path }]：key 是稳定标识，path 是小程序包内资源路径，可直接当 image src。
 * 由后端下发而非前端硬编码 —— 换存储（如改 COS）时前端零改动。
 */
const getPresetImages = () => {
  return get(API.MANAGER_CATALOG_PRESET_IMAGES)
}

/** 选用某商品到本站（默认未上架，站长再填库存并上架） */
const selectCatalogProduct = (id, data) => {
  return post(API.MANAGER_CATALOG_SELECT(id), data || {})
}

/** 更新本站设置（未传的字段保持原值；价格传 0 = 清除覆盖、回落平台参考价） */
const updateCatalogSetting = (id, data) => {
  return put(API.MANAGER_CATALOG_ITEM(id), data)
}

/** 移除本站配置（不再卖）：库存必须先盘点为 0 */
const removeCatalogProduct = (id) => {
  return del(API.MANAGER_CATALOG_ITEM(id))
}

/** 盘点：把本站库存设为 target（差额写 ADJUST 流水），note 会进流水的 note 字段 */
const setCatalogStock = (id, target, note) => {
  return post(API.MANAGER_CATALOG_STOCK(id), { target, note: note || '' })
}

/** 本站自定义商品（不入通用库，仅本站可见；名称规格图片可改） */
const getMyProducts = () => {
  return get(API.MANAGER_MY_PRODUCTS)
}

const createMyProduct = (data) => {
  return post(API.MANAGER_MY_PRODUCTS, data)
}

const updateMyProduct = (id, data) => {
  return put(API.MANAGER_MY_PRODUCT(id), data)
}

const deleteMyProduct = (id) => {
  return del(API.MANAGER_MY_PRODUCT(id))
}

/** 上报给开发者，请其考虑补进通用库（同一商品已有待处理上报时会被拒） */
const submitMyProduct = (id, note) => {
  return post(API.MANAGER_MY_PRODUCT_SUBMIT(id), { note: note || '' })
}

/** 本站的上报记录 */
const getMySubmissions = () => {
  return get(API.MANAGER_MY_SUBMISSIONS)
}

// ===== 水站营业状态（软状态，2026-09-17）=====
//
// **不阻断下单**：顾客照常下单，只是会在商城/下单页看到横幅、下单响应里带 warnings。
// 真正"不接单"用的是 station.status = 2 停业（硬开关），两者不要混。

/** 读本站营业状态（含 statusText / note / customerHint） */
const getStationStatus = () => {
  return get(API.MANAGER_STATION_STATUS)
}

/** 设置营业状态与留言：{ operatingStatus: 1..4, note: '≤100字' } */
const updateStationStatus = (operatingStatus, note) => {
  return put(API.MANAGER_STATION_STATUS, { operatingStatus, note: note || '' })
}

// ===== 水站坐标（地图选点，2026-09-17 / v34）=====
//
// 坐标是**配送范围判定**的前提：没有它，「这单超没超范围」根本无从判断，
// 系统只能跳过校验（等于功能不存在）。建站页从 2026-09-16 起就在收集坐标，
// 但后端 DTO 当时没有这两个字段，被 Jackson 静默丢掉了 —— 本端点给已建的站补上。

/** 读本站信息（含 lat / lng；lat 为 null 表示还没选点） */
const getMyStation = () => {
  return get(API.STATION_GET)
}

/**
 * 保存本站资料（名称 / 电话 / 地址 / 状态）。
 *
 * ⚠️ 后端那条 `PUT /api/stations/{id}` 是**整行覆盖**（`set name=?, phone=?, address=?, status=?`），
 * 所以 payload **必须四项给全** —— 只传 name 会把 phone/address/status 一并写成 NULL。
 * 页面侧的做法：先把 `getMyStation()` 的结果读回来，改了哪项就覆盖哪项，其余原值带回去。
 *
 * ⚠️ `status`（1 营业 / 2 停业）是**硬状态**，停业会让顾客下不了单、且不在公开选站列表里。
 * 本页**不提供**改它的入口（现有交互里没有这个需求），只把它原样带回，别顺手做成开关。
 *
 * stationId 由后端从登录态以外的方式校验（`getByIdAndCreator` 按**创建人**判定），
 * 所以创建人字段没回填的站会报「无权操作」，不是前端传错。
 */
const updateStation = (id, payload) => {
  return put(API.STATION_UPDATE(id), payload)
}

/**
 * 保存本站坐标。传 null 表示清除（清除后范围校验跳过，不会拒单）。
 * stationId 由后端从登录态取，前端不传。
 */
const updateStationCoordinates = (lat, lng) => {
  return put(API.STATION_MY_COORDINATES, { lat: lat === undefined ? null : lat, lng: lng === undefined ? null : lng })
}

// ===== 公告（站长发本站公告；顾客端只读已发布）=====

/** 本站全部公告（含草稿） */
const getNotices = () => {
  return get(API.NOTICES_ALL)
}

/** 新建公告：{ title, content, type: 2 水站通知, status: 1 发布 / 0 下架 } */
const createNotice = (data) => {
  return post(API.NOTICES, data)
}

const updateNotice = (id, data) => {
  return put(API.NOTICE(id), data)
}

const deleteNotice = (id) => {
  return del(API.NOTICE(id))
}

/** 批量入库（加库存，写 INBOUND 流水）。items: [{productId, quantity}] */
const inboundProducts = (stationId, items) => {
  return post(API.INVENTORY_INBOUND + '?stationId=' + stationId, { items })
}

/** 本站库存流水（倒序）。传 productId 只看某种商品；limit 用于"加载更多" */
const getInventoryRecords = (limit, productId) => {
  const params = {}
  if (limit) params.limit = limit
  if (productId) params.productId = productId
  return get(API.INVENTORY_RECORDS, params)
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

/**
 * 欠桶台账（v29）：本站当前仍欠桶的客户，按欠得最久排前面。
 *
 * 只读、只预警 —— 下单是否放行与欠桶**无关**（原「欠桶 ≥5 拒绝下单」硬拦已移除）。
 * minDays 传 0 / 不传 = 不过滤；「欠了几天」来自后端 owed_since，
 * 天数未知（历史存量行）的也一并返回，故筛天数是前端兜底而非过滤掉。
 */
const getOwedBarrels = (minDays) => {
  return get(API.MANAGER_OWED_BARRELS, minDays ? { minDays } : {})
}

/**
 * 本站运营告警（v30）：只读，最近 N 条（默认 50、后端上限 200）。
 *
 * 后端固定只返回 `alert_type='OPERATION'` 且属于本站的记录 ——
 * **系统故障告警不会出现在这里**（它带平台级细节、收件人是系统管理员），
 * 所以前端不要试图"顺带展示全部"。
 *
 * 投递状态含义（notifyStatus）：
 *   LOGGED = 已落库（外部渠道没配或站长侧未接入，属正常，不代表告警不存在）
 *   PUSHED = 已推送外部渠道；FAILED = 推送失败
 */
const getAlerts = (limit) => {
  return get(API.MANAGER_ALERTS, limit ? { limit } : {})
}

/**
 * 本站待审的企业身份申请（v50）：客户在下单页看到「大额订单可申请企业身份」后提交的那些。
 *
 * ⚠️ 功能总开关关着时后端**返回空列表而不是报错** —— 这是有意的：客户列表页那一行提示
 * 应该安静地消失，而不是给站长弹一个"功能未开启"的红字。所以调用方拿到空列表就什么都不显示，
 * 别自己判断"接口是不是坏了"。
 */
const getEnterpriseApplies = () => {
  return get(API.ENTERPRISE_APPLICATIONS)
}

/** 审核一条企业身份申请：approve=true 通过（该客户转企业身份 + 写企业资料），false 驳回。 */
const reviewEnterpriseApply = (id, approve, note) => {
  return put(API.ENTERPRISE_APPLICATION(id), { approve: !!approve, note: note || undefined })
}

/**
 * 本站的「企业身份提示阈值」（v51）：桶数 与/或 水费金额。
 *
 * 语义（照后端 StationEnterpriseConfig）：两项都填 = 任一满足即提示；只填一项 = 只按那一项；
 * **两项都留空 = 本站不提示**；**从没配过 = 用平台默认**（`defaultBarrels`，默认 30 桶，
 * 此时 `usingDefault=true`）。开关关着时后端回 `enabled:false`（不报错），前端据此把整块隐藏。
 */
const getEnterpriseConfig = () => {
  return get(API.ENTERPRISE_CONFIG)
}

/** 保存本站阈值：两项都可传 null（= 该项不启用）。 */
const updateEnterpriseConfig = (payload) => {
  return put(API.ENTERPRISE_CONFIG, payload)
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
  getDashboardReport,
  getOrders,
  getCrossStationOrders,
  getCustomers,
  getCustomerDetail,
  getCustomerProfile,
  getCustomerAssets,
  getStaffProfile,
  getOfflinePayment,
  getOfflinePaymentSummary,
  updateOfflinePayment,
  getCatalog,
  getPresetImages,
  selectCatalogProduct,
  updateCatalogSetting,
  removeCatalogProduct,
  setCatalogStock,
  getMyProducts,
  createMyProduct,
  updateMyProduct,
  deleteMyProduct,
  submitMyProduct,
  getMySubmissions,
  getStationStatus,
  updateStationStatus,
  getMyStation,
  updateStation,
  updateStationCoordinates,
  getNotices,
  createNotice,
  updateNotice,
  deleteNotice,
  inboundProducts,
  getInventoryRecords,
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
  confirmPayment,
  getOwedBarrels,
  getAlerts,
  getEnterpriseApplies,
  reviewEnterpriseApply,
  getEnterpriseConfig,
  updateEnterpriseConfig
}
