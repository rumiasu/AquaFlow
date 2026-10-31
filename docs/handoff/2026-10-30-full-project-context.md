# AquaFlow 全量项目上下文（AI 交接版）

> 生成时间：2026-10-30  
> 用途：给下一个接手模型建立项目全貌、当前真实状态、源码入口、领域红线和验证边界。  
> 性质：**工作交接上下文，不是产品规格**。发生冲突时按 `AGENTS.md` §0 的顺序：源码 → `schema.sql` → 已验证行为 → Markdown。
> 本文件基于全量路由、控制器、表、测试文件枚举，以及核心源码/文档精读；没有在本次读取中执行业务测试、构建、迁移或真机验收。

> **当日后续状态（交付前核对）**：下文的 HEAD、文件数量、RC2 和 0/72 验收均为生成时的历史快照，不作为当前工作区或已上传版本的证明。之后蓝白页面及图片重试补丁已同步原项目；需以实际上传包和微信开发者工具重新核对。顾客首页与“我的”已接入固定场景图接口及 URL 自动刷新，以本地 `assets/ui/scene-home-water.webp` 作初始回退；对象 `public/ui/scene-home-water.webp` 尚未上传，实际桶、后端部署与原生显示仍待核实，图片问题不能算已解决。资产站名及数值须以有效登录实测确认。
> T002 中文种子据用户确认已完成校验（COMMITTED 689/1/36），不要重复导入。刷新令牌修复包已交付，实际部署状态尚未确认。真实微信支付/退款未接入，测试登录和模拟支付须按各自受限开关配置；代码、导入记录或测试通过均不证明云端已部署、真机已验收。

---

## 0. 先看这五条

1. **当前工作区不是 HEAD 的干净副本。** HEAD 为 `9b0a957882f958565480896c897c02fe7c38259d`（2026-10-25，“feat(products): separate inventory operations”），但工作区有 **472 个已跟踪文件变更（+12347 / -6772）和 139 个未跟踪文件**。不要 `reset`、不要全量回退、不要 `git add -A`。
2. **最新视觉批次是 `visual-all-RC2-final`，而且大多尚未提交。** 2026-10-29 的 RC2 文件清单含 257 个文件（233 个前端、24 个相关测试/脚本），16 个新增文件。它以未提交改动叠在当前业务基线上。
3. **当前源码同时存在两套主色/动作体系。** 旧 token 是 `--primary-color: #1264D6`；RC2 新增的 `af2` / `af-action` 体系以 `#1F5EA8` 为主色。`actions.wxss` 已用于 24/26 个顾客页和 45/46 个员工页；`af2` 表面样式实际覆盖 0 个顾客页、23 个员工页。这是**活的设计系统分叉**，不要随手统一、也不要假设 `design/37` 是唯一现状。
4. **真机/原生像素验收仍是 0/72。** RC2 交付文档明确写着“原生/真机验收仍 0/72”；RC3 文档也写明键鼠/大字/暗色/真实业务态未验收。编译、JS 回归、静态审阅都不能冒充像素通过。
5. **真实微信支付/退款、正式协议主体、生产迁移、真实恢复演练都没有上线。** 生产默认关闭模拟支付和开发登录；真实渠道仍未接入。

---

## 1. 项目是什么

AquaFlow 是桶装水配送业务系统，三个运行物：

- **后端**：`AquaFlow-backend/`，Java 17 + Spring Boot 4.0.6 + MyBatis + MySQL 8.x，单体应用，REST API。
- **顾客端**：`miniapp-user/`，原生微信小程序，26 个路由，AppID `wx12632a1cdca9fbcc`。
- **员工端**：`miniapp-delivery/`，同一小程序承载站长 + 配送员，46 个路由，AppID `wxc6211615c79da9f9`。
- 没有可维护的 Vue/Web 管理后台；`archive/` 只作历史留档，不维护。

核心业务是把四件事分开记清：

1. 客户买了什么服务；
2. 客户交了多少押金、形成多少桶权益；
3. 客户实际拿着多少桶；
4. 哪个水站还欠什么履约、退款、协作责任。

送达、收款、退桶、退款、站间结算都是**不同事实**。不能用一个“完成”状态代替。

---

## 2. 领域不变量（改代码前必须守住）

### 2.1 客户与站

- `customer` 是全局身份，**没有、也不应新增 `station_id`**。
- 客户资产（押金、水票、桶权益）一律按 `(customer_id, station_id)` 隔离。
- 经营关系 = 绑定 `customer_station_config` **∪** 本站订单，不是单一字段。
- `product.owner_station_id` 只表示商品归属：`NULL` = 平台通用库，否则 = 本站自定义商品。

### 2.2 三站语义

| 字段 | 含义 | 谁认它 |
|---|---|---|
| `orders.station_id` | 归属站：客户选择的站、定价方、客户资产责任站 | 客户资产、客户归属、原价 |
| `orders.delivery_station_id` | 履约站：库存、配送员、计件工资记这里 | 履约和现场责任 |
| `orders.settle_station_id` | 结算站：本单营收认这里 | 看板、毛利、应收、收款/退款判权、`payment_record.station_id` |

- 下单时三站都等于归属站。
- 抢单/定向外派成功：履约站、结算站转为接单站。
- 召回/退回池/指定退回同意：回归属站。
- SQL 读金额归属统一 `coalesce(o.settle_station_id, o.delivery_station_id, o.station_id)`；**写入必须显式落结算站**。
- **钱认结算站，客户资产认归属站。** 接单站不能取得归属站客户画像、成本、库存和联系方式；跨站可见面只给订单快照和钱货去向。

### 2.3 订单 / 支付 / 退款

- 订单状态正本：`constant/OrderStatus.java`  
  `1 待配送 / 2 配送中 / 3 已送达 / 4 已完成 / 5 已取消`
- 支付方式正本：`constant/PayMethod.java`  
  `1 微信 / 2 现金（货到付款） / 3 水票`
- 支付状态正本：`constant/PaymentStatus.java`  
  `0 未付 / 1 待收款 / 2 已付 / 3 已退款 / 4 已取消`
- 真值只认 `orders.payment_status`。待收款不是到账。
- 唯一把支付状态写成已付的入口是 `OrderMapper.markPaidIfCollectable`，从 0 或 1 进入，不复活 3/4。
- 现金单下单即 `待收款(1)`；`createPayment` / `completeDelivery` 不得直接改写支付状态。
- CAS 有两派参数顺序：
  - `updateStatusIf(id, 期望, 新)`：`OrderMapper`、`OrderBarrelExceptionMapper`
  - `updateStatusTo(id, 新, 期望)`：`PaymentRecordMapper`、`StaffPayrollMapper`
  - 写前一定回看 SQL；传反通常静默命中 0 行。

### 2.4 桶

- 真相源：`customer_barrel_lot.remain_qty`；汇总表 `customer_barrel_asset` 是派生值。
- 唯一写入口：`BarrelLedgerService`。
- 变量：
  - `E` = 已付有效权益容量
  - `over` = 实物相对权益的差额，可为负（暂存/未领）
  - `H = E + over` = 客户实际持桶
  - `R` = 已被进行中订单或退还申请占用的权益
  - `E - R` = 可继续分配权益，不等于可退金额或可还空桶数
- 买 q 份权益：`E + q`、`over - q`，`H` 不变。
- 下单抵扣只认已到手权益，**在途不抵扣**。
- 按商品汇总时必须取：权益批次 ∪ 遗留配送中 ∪ over 的并集。
- 退桶默认回桶数逐行计算：`min(送出桶数, 占用)`，唯一实现 `BarrelService#returnPlanOfOrder`；本批新买押金、仍在 PENDING 的桶不算。
- 回桶只对桶装水成立，唯一判断在 `util/BarrelScope`。
- 欠桶与退该商品押金互斥；欠桶只提醒，不阻断下单，不设旧 `MAX_OWED_BUCKETS` 硬拦。

### 2.5 水票

- 余额真相源是 `ticket_lot`，`ticket_account` 只做派生汇总。
- 唯一写入口：`TicketLotService`；所有加票、扣票、回补、资产调整都必须过批次账。
- FIFO 扣减；退款回补按流水当时单价。
- 在线购票必须带 `idempotencyKey`；无订单支付不能只依赖 `uk_payment_active_order`，因为 NULL 不冲突。
- 水票只能抵对应站、对应商品的消费；当前不支持票与现金/微信混付。新增押金不能用票支付。

### 2.6 库存

- `inventory.quantity` = 在库实物。
- 可用量 = `quantity - Σ活跃 reserved_qty`。
- 下单只预留，不动实物；入库/盘点后按 FIFO 补预留；完成配送才出库。
- 换站时释放旧站预留，再在新站按可用量重建。
- 取消只释放预留，不回补实物库存。
- `order_item.deducted_qty` 只是预留镜像，不是出库数。

### 2.7 配送计费

- 唯一规则实现：`util/DeliveryFeeUtil`；IO 在 `DeliveryFeeService`。
- `PaymentServiceImpl.quote` 和 `OrderServiceImpl.createOrder` 必须调用同一个 `calcForOrder`。
- 起送量、配送范围、配送费、楼层费都来自这里；费用不能并入水费或押金。
- 未经保存的计费配置不能当免费；明确保存 0 才是 0。
- 门槛默认 WARN，不是无条件 REJECT。

### 2.8 其他红线

- 登录/换会话/绑定端点按 **来源 IP** 限流，鉴权前执行。只信 `getRemoteAddr()`，不信任转发头。
- 未选身份的员工会话 `role=UNSELECTED` 只能访问引导端点。
- Controller 只做认证、参数校验、调服务；不得直接写订单/资金/桶资产表。
- 业务拒绝抛 `BusinessException`，HTTP 仍 200、`code=1`。
- 展示文案（状态、支付方式、费用说明）优先由后端下发；前端不要自建 1/2/3 映射表。
- 日志不能记录密码、JWT、微信授权码、完整手机号/地址、密钥。

---

## 3. 代码地图与规模快照

### 3.1 后端

- 主代码 Java：约 451 个文件。
- 分层：`controller 61 / service 73（含 impl 34） / mapper 81 / dto 51 / entity 57 / constant 25 / config 17 / util 19`。
- API 统一包 `{ code, message, data }`：0 成功、1 业务错误、404 路由不存在、500 系统异常；业务错误 HTTP 仍 200，未认证才是 401。
- 测试源码约 244 个 Java 类，其中 `integration` 目录 156 个，采用完整 Spring 容器 + 真实 HTTP + MySQL。
- 核心重文件：
  - `OrderWorkflowServiceImpl`（约 2000 行）：接单、派单、送达、完成、取消、外派等状态编排。
  - `PaymentServiceImpl`（约 1692 行）：报价、支付、确认、退款、押金入账。
  - `BarrelServiceImpl` / `BarrelLedgerService`：桶交接、退桶、权益批次。
  - `ReconciliationService`：每日对账等式。
  - `OrderServiceImpl`：建单、库存预留、计价调用、幂等。
  - `TicketAccountServiceImpl`：水票账户/批次编排。
  - `AuthTokenService`：登录、令牌、绑定。
  - `InventoryReservationServiceImpl`：库存预留与回填。
- 业务模块分组（控制器）：
  - 登录/身份/账户：`LoginController`、`DevLoginController`、`AuthTokenService`、`AccountClosureCheckController`、`AccountDataRequestController`、`AgreementController`
  - 客户/地址/通知：`CustomerController`、`CustomerNotificationController`、`AddressController`、`CompanyInfoController`、`EnterpriseController`
  - 商品/目录/搜索：`ProductController`、`ManagerCatalogController`、`ManagerMyProductController`、`SearchController`
  - 订单/履约：`OrderController`、`OrderTemplateController`、`OrderImageController`、`DeliveryTaskController`、`StationDeliveryConsoleController`
  - 支付/退款/应收：`PaymentController`、`ManagerReceivableController`、`ManagerInterStationSettlementController`、`ConsumptionRefundService`
  - 桶/押金/水票：`BarrelController`、`BarrelRightPurchaseController`、`DepositRecordController`、`TicketAccountController`、`TicketPackageController`、`TicketRecordController`、`StationTicketDiscountController`
  - 站长治理：`ManagerAdjustmentController`、`ManagerExceptionController`、`ManagerBarrelLossController`、`ManagerAlertController`、`ManagerReconciliationController`
  - 工资/经营：`DeliveryEarningController`、`ManagerPayrollController`、`ManagerGrossProfitController`、`DashboardController`
  - 站间/外派：`CrossStationDispatchController`、`ManagerInterStationSettlementController`
  - 历史资产：`HistoricalAssetClaimController`
  - 系统/上传/反馈：`SystemController`、`CommonController`、`FileManageController`、`FeedbackController`

### 3.2 数据库

- `schema.sql` 当前 85 张表；迁移脚本 75 个，最大编号 `v87`。
- 最新增量：
  - v84：本站商品图片覆盖
  - v85：本站定价确认
  - v86：历史资产认领照片
  - v87：可选入库成本快照
- Flyway **未启用**。迁移手工执行，老库按 `sql/README.md` 顺序与逐项预检办理；不可假定所有脚本可整份重复运行（例如 v87），禁止把 `schema.sql` 当增量升级。
- 新库基线 `schema.sql`，存量库不能直接重导基线。
- 迁移相关红线：
  - MySQL CLI 必须 `--default-character-set=utf8mb4`。
  - 新建迁移先 `ls sql/migration_v*.sql` 取磁盘最大号，不照文档最后一个 +1。
  - 破坏性 DROP 必须先上代码、再执行 SQL，执行前备份。
  - `reset_data.sql`、`clear_data.sql`、`archive/**` 严禁在生产执行。

表按域分组（85 张）：

- 身份/关系/员工：`customer`、`customer_station_config`、`staff`、`staff_bind_code`、`staff_station_application`、`customer_privilege`、`company_info`
- 地址/公告/通知：`address`、`notice`、`customer_notification`
- 商品/库存：`product`、`inventory`、`inventory_record`、`inventory_reservation`、`product_submission`
- 订单/履约：`orders`、`order_item`、`order_transfer`、`order_barrel_exception`、`order_image`、`order_template`、`order_template_item`、`order_cancel_result`
- 站点/计费：`station`、`station_delivery_config`、`station_exception_config`
- 桶/押金：`customer_barrel_lot`、`customer_barrel_asset`、`customer_barrel_over`、`customer_barrel_in_transit`、`customer_deposit_account`、`deposit_record`、`barrel_record`、`barrel_record_lot`、`barrel_right_purchase`、`barrel_right_reservation`、`barrel_return_detail`、`barrel_return_lot_hold`、`barrel_return_arrangement`、`barrel_return_arrangement_change`、`barrel_return_payout`、`barrel_purchase_refund`、`barrel_return_fee_refund`
- 水票：`ticket_account`、`ticket_record`、`ticket_lot`、`ticket_package`、`station_ticket_discount`、`ticket_exit_refund`、`ticket_purchase_fence`
- 支付/退款/拒付：`payment_record`、`payment_original_success`、`payment_original_refund_hold`、`consumption_refund`、`customer_refusal_case`、`customer_refusal_resolution`、`customer_refusal_action`、`customer_refusal_freeze_scope`
- 站间/外派：`dispatch_agreement`、`inter_station_settlement`、`inter_station_recovery`
- 工资/治理：`staff_piece_rate`、`staff_earning`、`staff_earning_item`、`staff_payroll`、`station_adjustment`、`reconciliation_result`、`alert_log`
- 历史认领/协议/反馈：`historical_asset_claim`、`historical_asset_claim_photo`、`agreement_document`、`agreement_acknowledgement`、`account_data_request`、`refund_dispute`、`refund_dispute_action`、`feedback`
- 其他：`audit_log`、`file_info`、`user_token`

### 3.3 两个小程序

- 原生 WXML/WXSS/JS，无 npm 构建、无分包。
- 98 个页面侧 WXML（含组件/模板），101 个 WXSS。
- 顾客端 26 路由，原生 tabBar：订水 / 订单 / 我的。
- 员工端 46 路由，自绘 tabBar（`custom-tab-bar`）：首页 / 配送 / 我的；站长与配送员看到不同项，P0 红点由 `pending-reminder.js` 驱动。
- 员工端新增 tab 页要同时改三处：`app.json`、`custom-tab-bar/index.js`、页面 `custom-tabbar-page` / `syncTabBar`。
- 当前 AppID 以源码为准：顾客 `wx12632a1cdca9fbcc`，员工 `wxc6211615c79da9f9`。
- `config/api.js`：develop 默认云容器；trial 也是 `cloudContainer`；release 仍是占位 `https://your-domain.com`，不能当真域名。
- 两端 `utils/request.js` 能力不完全对等；历史上配送端缺少用户端那套会话代际/refresh 单飞保护，修改网络层时两端都要查。

---

## 4. 当前视觉系统（必须知道的事实）

### 4.1 两套 token 并存

**旧系统** `miniapp-user/styles/variable.wxss` / 员工端 `app.wxss`：

- 主色 `--primary-color: #1264D6`
- 主色浅底 `#EAF3FF`
- 成功 `#16834A`，警示 `#A95516`，错误 `#BD3328`
- 正文 `#182230`，常规 `#4B596A`，辅助 `#637083`
- 页面底 `#F5F6F7`，内容面白色
- 触控 token：`--control-h: 88rpx`、`--control-h-main: 96rpx`

**RC2 新系统** `styles/actions.wxss` / `styles/presentation.wxss`：

- `af-action` 主色 `--af-primary: #1F5EA8`，按下 `#174A80`
- `af2` 页面主色 `--af2-primary: #1F5EA8`
- 页面浅底 `#F2F6F9`，顶部 `#E8F1F7`
- 语义表面：订单蓝、实物青、钱紫、成功绿、危险红
- 这是**opt-in 动作族**，不是全局覆盖；旧页面/旧组件仍使用旧 token。
- 端内所有 CSS 最终都会导入 `actions.wxss` / `presentation.wxss`（两端 `app.wxss` 顶部已 import）。

现状：`af-action` 已覆盖绝大多数页面，`af2` 仅部分员工页使用。`design/37` 仍以 `#1264D6` 为主，不能直接当作当前唯一视觉事实。

### 4.2 共享组件

顾客端：

- `BusinessDialog`：统一业务确认弹窗，数字加重、可输入、危险确认、队列、离页取消、键盘释放。
- `IntegerStepper`：整数草稿输入；边界校验；只发 `change`，不保存/不算钱。
- `ProductSelector`：父级持有商品事实；只选择，不报价/不保存/不改数量。
- `Icon`：线性 SVG 语义图标；`line` / `duotone` 变体。
- `OrderCard`：订单卡统一卡头、商品完整摘要、双状态、金额与动作。
- `AssetStationView`：资产站范围视图。
- `Price`、`Empty`、`Loading`、`PrivacyPopup`。

员工端：

- `BusinessDialog`、`HelpTip`、`Icon`、`IntegerStepper`、`ProductSelector`、`PrivacyPopup`。
- `HelpTip` 是把长说明从页面正文收进“?”的组件，避免每页堆说明。

两端各维护自己的 token 和组件，不跨小程序共享运行时。不要把一端的组件路径直接 require 到另一端。

### 4.3 已存在的新交互能力

- `utils/draft-guard.js`：按 app/账号/站/route/entity/世代隔离草稿；保存失败保留原草稿；账号/站点切换不可复用。
- `utils/business-dialog.js`：统一弹窗包装；找不到组件时回退原生 `wx.showModal`。
- `utils/payment-channel.js`、`utils/claim-photo.js`、`utils/multipart-body.js`、`utils/payment-sandbox-harness.js` 等新增工具。
- 这些都属于未跟踪或未提交文件，接手时先看当前源码，不要从旧文档推断不存在。

---

## 5. 全量页面清单

### 5.1 顾客端 26 页（`miniapp-user/app.json`）

| # | 路由 | 页面标题 | 主要职责 / 交接重点 |
|---:|---|---|---|
| 1 | `pages/home/index` | 送水到家 | 选站、地址、进行中订单、复购、商品、桶入口、结算栏；本次生成快照的首页视觉是 `scene-home-water.webp` 小场景 + `home-water-light.webp` 背景，不是外部 10-28 大海报 |
| 2 | `pages/home/notice` | 公告详情 | 公告正文、失败与刷新、长文阅读 |
| 3 | `pages/order/list` | 我的订单 | 订单双状态、筛选、全商品摘要、待付款入口 |
| 4 | `pages/order/detail` | 订单详情 | 配送/支付、地址、费用、凭证、进度、取消/恢复 |
| 5 | `pages/order/create` | 确认订单 | 全项目最重页面之一：地址、商品、报价、支付、水票、押金、费用抽屉、库存/范围/未知结果 |
| 6 | `pages/order/success` | 订单结果 | 真实状态、付款/等待配送、原单恢复 |
| 7 | `pages/address/list` | 地址管理 | 地址选择、默认、编辑删除、失败/空态 |
| 8 | `pages/address/edit` | 编辑地址 | 定位、楼层、电梯、费用条件、键盘避让 |
| 9 | `pages/barrel/index` | 水桶与押金 | 按商品桶账、退桶预览/申请、押金与实物区分 |
| 10 | `pages/barrel/purchase` | 交桶押金 | 独立押金、原购买恢复、支付闸门 |
| 11 | `pages/shop/index` | 商城 | 搜索、站级价、商品图、押金提示、数量与清单 |
| 12 | `pages/template/index` | 常用订单 | 常用商品/地址模板、草稿保护；不代表首页自动使用 |
| 13 | `pages/ticket/index` | 我的水票 | 余额、适用商品、档位、购票、批次/恢复、退出 |
| 14 | `pages/mine/index` | 我的 | 当前站、押金/水票/桶三资产、常用入口；不做全宫格 |
| 15 | `pages/login/index` | 登录 | 微信登录、协议、失败出口；开发登录只在既有条件下出现 |
| 16 | `pages/service/index` | 联系客服 | 水站电话、反馈、退款争议、匿名边界 |
| 17 | `pages/mine/about` | 关于 | 协议/隐私/反馈入口；不是同意状态 |
| 18 | `pages/mine/edit` | 个人资料 | 可编辑资料、保存、草稿 |
| 19 | `pages/mine/agreement/index` | 协议 | 版本化正文、草稿/占位、阅读不等于同意 |
| 20 | `pages/mine/account-data/index` | 个人资料与注销 | 访问/更正/导出/删除/注销意图；登记不等于办理 |
| 21 | `pages/payment/records` | 付款记录 | 连续流水、金额方向、支付方式、失败恢复 |
| 22 | `pages/deposit/records/index` | 押金流水 | 资产站范围、余额不等于可退、长备注 |
| 23 | `pages/notice/index` | 公告 | 全平台已发布公告（产品暂时不做站隔离） |
| 24 | `pages/mine/company` | 企业资料 | 企业身份、联系人、账期；不是独立资产账户 |
| 25 | `pages/exception/list/list` | 我的异常记录 | 差异、补偿、进度、原始记录 |
| 26 | `pages/mine/asset-claims/index` | 历史资产认领 | 客户提交 E/H/over、历史押金/票、站长审核后入期初账 |

### 5.2 员工端 46 页（`miniapp-delivery/app.json`）

#### 登录与身份

| # | 路由 | 标题 | 重点 |
|---:|---|---|---|
| 1 | `pages/login/index` | 员工登录 | 微信员工登录、协议、身份仍需选择/生效 |
| 2 | `pages/mine/agreement/index` | 协议 | 员工条款与版本 |
| 3 | `pages/mine/account-data/index` | 个人资料申请 | 员工身份上下文、注销检查 |
| 4 | `pages/role-select/index` | 选择身份 | 选择 ≠ 生效；生效后不能改选 |
| 5 | `pages/bind-wait/index` | 等待审核 | 审核中、解绑中、撤回、退出；不能卡死 |
| 6 | `pages/station-mgmt/create-station/index` | 创建水站 | 名称/电话/地址/坐标；进页先同步真实状态 |
| 7 | `pages/station-mgmt/apply-bind/index` | 申请绑定水站 | 搜索、定位、站名/地址、申请状态 |

#### 配送员主链路

| # | 路由 | 标题 | 重点 |
|---:|---|---|---|
| 8 | `pages/home/index` | 配送 | 任务页签、地址/商品/桶数/电话/收款、待收款入口、接单/送达 |
| 9 | `pages/order/detail` | 订单详情 | 目的地先于电话；金额/桶数/收款；更多操作 |
| 10 | `pages/order/complete` | 确认送达 | 送货摘要、逐商品回桶、实际收款、凭证/异常、未知结果 |
| 11 | `pages/mine/index` | 我的 | 身份/站、工资、桶记录、站长经营入口、解绑 |
| 12 | `pages/barrel-records/index` | 空桶回收记录 | 送达/实回/差异 |
| 13 | `pages/my-earnings/index` | 我的工资 | 自助端点只认登录态，不能加 staffId；只读/结算单 |
| 14 | `pages/history/index` | 配送历史 | 地址、商品单位、回桶/收款事实 |
| 15 | `pages/transfer/index` | 转让记录 | 我转出/转给我、状态、后果 |
| 16 | `pages/report/index` | 意见反馈 | 分类、正文、联系方式、失败保留 |
| 17 | `pages/settings/index` | 设置 | 账户、提醒、缓存/重试、退出 |
| 18 | `pages/mine/edit` | 昵称与手机号 | 可编辑资料 |

#### 站长工作台与管理

| # | 路由 | 标题 | 重点 |
|---:|---|---|---|
| 19 | `pages/coordination/index` | 首页 | 真实待办、队列、抢单池、外派、金额/去向/风险；管理入口在“我的” |
| 20 | `pages/station-mgmt/index` | 水站管理 | 20 个真实入口的五组管理目录，徽标未知不写 0 |
| 21 | `pages/station-mgmt/business-waiting/index` | 业务待办与退出 | 缺货/超时/拒付/交接/余票退出，按事项分层 |
| 22 | `pages/station-mgmt/dashboard/index` | 站内数据 | 指标、趋势、分布、排行；Canvas 有加载/失败缺口 |
| 23 | `pages/station-mgmt/orders/index` | 订单管理 | 归属/履约/结算、筛选、跨站 |
| 24 | `pages/station-mgmt/customers/index` | 客户 | 搜索、类型、企业申请、COD 开关、电话/地址 |
| 25 | `pages/station-mgmt/customers/detail/index` | 客户画像 | 本站资产、桶/票/押金、特权、代客下单 |
| 26 | `pages/station-mgmt/customers/adjust/index` | 资产调整单 | 客户/方向/金额/状态、执行/撤销 |
| 27 | `pages/station-mgmt/customers/adjust/edit/index` | 新建资产调整 | 类型、数量/金额方向、试算、风险 |
| 28 | `pages/station-mgmt/customers/adjust/detail/index` | 调整单详情 | 前后快照、执行/撤销后果、原始记录折叠 |
| 29 | `pages/station-mgmt/products/index` | 商品与库存 | 现有商品优先、库存独立面板、站级价/押金/水票/图片/模板 |
| 30 | `pages/station-mgmt/station-status/index` | 营业状态与公告 | 软营业提示、顾客实际预览、公告草稿 |
| 31 | `pages/station-mgmt/station-info/index` | 水站资料 | 站名/电话/地址/坐标，四项整行保存 |
| 32 | `pages/station-mgmt/delivery-config/index` | 配送计费 | 起送/范围/配送费/楼层费、明确 0 与未配置、教程 |
| 33 | `pages/station-mgmt/place-order/index` | 代客下单 | 客户→地址→商品→报价，金额服务端算；站别必须登录态 |
| 34 | `pages/station-mgmt/receivables/index` | 应收账款 | 待收款+账期+逾期；核销前实际到手提示 |
| 35 | `pages/station-mgmt/inter-station/index` | 站间结算 | 谁欠谁、实际交付、冲销、售后责任 |
| 36 | `pages/station-mgmt/payroll/index` | 计件工资 | 单价、收益、结算单、线下实发登记 |
| 37 | `pages/station-mgmt/ticket-packages/index` | 水票档位 | 站级票价、预设、启停/删除、服务端均价 |
| 38 | `pages/station-mgmt/gross-profit/index` | 利润报表 | 收入/成本/利润、成本缺失不报假利润、合作款分组 |
| 39 | `pages/station-mgmt/barrel-return/index` | 退桶审批 | 申请→批准→交桶→退款；实退/待退/争议 |
| 40 | `pages/station-mgmt/owed-barrels/index` | 欠桶台账 | 净欠桶、只提醒不处罚、按商品不跨抵 |
| 41 | `pages/station-mgmt/exceptions/index` | 异常订单 | 待处理优先、统计后置、补偿留痕 |
| 42 | `pages/station-mgmt/customer-feedback/index` | 客户反馈 | 普通反馈/退款争议分开；结案不退钱 |
| 43 | `pages/station-mgmt/payments/index` | 收款记录 | 待确认/历史、实收警示、来源类型区分 |
| 44 | `pages/station-mgmt/staff/index` | 员工管理 | 员工/绑定申请、未知状态、解除绑定 |
| 45 | `pages/station-mgmt/staff/profile/index` | 员工画像 | 当前任务优先、只展示允许的订单快照 |
| 46 | `pages/station-mgmt/asset-claims/index` | 历史资产核对 | 客户清单核对、通过即入账、原申请恢复 |

---

## 6. 关键业务链路与源码落点

### 6.1 顾客下单

`miniapp-user/pages/order/create` → `POST /api/orders/create` → `OrderServiceImpl.createOrder`：

1. 幂等键按 `(customer_id, idempotency_key)` 校验；同键不同内容拒绝。
2. 校验商品、站点、库存、限购、水票适用性。
3. 需求→报价：`PaymentServiceImpl.quote`；下单时再调用同一个 `DeliveryFeeService.calcForOrder`。
4. 容量不足时返 `needConfirm`，客户明确确认后才建单。
5. 新桶权益模型开启时：随单押金走 `OrderBarrelPurchaseService`，未付款不形成 E。
6. 新建预留凭据；订单初始 `UNPAID/PENDING` 依支付方式与模型而变。
7. 订单金额/押金/费用分列，不能混写。

**水票需重点复核的风险：** 当前 `OrderServiceImpl.createOrder` 对水票只校验“该站/商品是否开通票”，未看到 `balanceOf` 预检；余额检查仍在 `PaymentServiceImpl`。因此 0 张票可能先建出订单，之后支付才被拒。历史记录称这类单会因支付状态/支付方式不满足可见性而无法进入履约、也不会自动清理。接手改支付/水票前必须先复现并确认现状。

### 6.2 配送完成

`miniapp-delivery/pages/order/complete` → `/api/delivery/orders/{id}/complete`：

- 回桶建议来自服务端 `barrelItem/suggestedReturnQty`，前端不能自己推。
- 现金未收只能记待收款；新增押金单必须水款+押金实际收齐后激活并交付。
- 库存 `shipForOrder` 校验履约站、预留和实物。
- 完成状态 CAS 成功后才生成本单计件收益。
- 任何照片上传未知、金额未知、库存失败都要保留原单恢复，不可重复提交。

### 6.3 外派/抢单

- 未分配单只进站长视野；配送员只能接派给自己的单。
- 抢单池/定向外派只给订单快照和钱货去向，不给客户画像/成本/库存。
- 含押金或桶权益风险的单不能进抢单池；定向外派双方确认。
- 被接单后归接单站管理；归属站只能在未接单时召回。
- 客户资产仍留归属站；结算站转接单站。

### 6.4 桶权益与退桶

- 独立购买：`BarrelRightPurchaseController` / `IndependentBarrelService`。
- 随单购买：`OrderBarrelPurchaseService`，退款按 paymentId + lotId 双凭据。
- 退桶申请：`ApprovedBarrelReturnService` / `BarrelServiceImpl`。
- 新路径状态：`APPLIED → APPROVED →（必要授权）→ RECEIVED → REFUNDED`。
- 未实际交桶不能视为退款；未领容量无需交实物桶。

### 6.5 站长经营

- 工作台：`coordination/index` + `ManagerPendingSummaryController` + `ManagerTodoController`。
- 管理目录：`station-mgmt/index` 20 个真实入口。
- 商品：`station-mgmt/products` + `ManagerCatalogController` / `InventoryController`。
- 库存：`inventory` 实物、`inventory_reservation` 预留、`inventory_record` 流水、v87 成本快照。
- 应收：`ManagerReceivableController` + `ReceivableService`。
- 工资：`ManagerPayrollController` + `StaffEarningService`。
- 毛利：`ManagerGrossProfitController` + 成本覆盖校验。
- 退桶审批：`ApprovedBarrelReturnService`。
- 历史资产：`HistoricalAssetClaimController`。

### 6.6 历史资产认领

- 客户提交的是原始清单快照，包含 E/H/over、历史押金、票、可选旧权益单价。
- 站长通过时，在同一事务建立本站关系并记期初账；不是新收款、不是新营收。
- 已有非零资产、活跃付款/占用时拒绝叠加。
- 原申请键/摘要永久防重；未知结果先查原申请，不能换键。
- 这是 v80 起的独立旁表，不能退回 7249632 旧包。

---

## 7. 当前批次与文档状态

### 7.1 Git

- 分支：`main`
- HEAD：`9b0a957882f958565480896c897c02fe7c38259d`，2026-10-25。
- 近期提交：
  - `9b0a9578` 商品库存操作拆分
  - `9fd1cfd3` 商品与配送配置说明收口
  - `6c6e38aa` 站长修复和网格验证记录
  - `edd1a90c` 目录范围与真实动作状态
- 工作区：
  - 611 个 `git status` 条目
  - 472 个已跟踪变更，+12347/-6772
  - 139 个未跟踪文件
  - 被修改：员工端 190、顾客端 108、测试 83、后端 78
- **不能把 HEAD 当作当前 UI 现状。** 最新视觉 RC2 是工作区改动。

### 7.2 最新交付记录

- `docs/audit/2026-10-29-AquaFlow-全页视觉RC2-交付.md`
  - 版本标记 `visual-all-RC2-final / V0-4.1`
  - 覆盖 72 页
  - 原始文件清单 257 项（前端 233、测试/脚本 24），16 个新增
  - 原生/真机 0/72
  - 没有后端、数据库、SQL、云部署、上传
- `docs/audit/2026-10-28-AquaFlow-UI-RC3交付与验收.md`
  - 官方编译：顾客 36 WXML/40 WXSS、员工 56/56 通过
  - RC3 定向 13 套通过，完整回归 181/185，4 项环境阻塞
  - 明确键盘/大字/暗色/真实业务态未验收
- `docs/README.md` 已补入本次全量上下文与 RC2 交付入口；更早的 RC3 回执仍可直接按文件名打开。

### 7.3 外部海报备份

用户 2026-10-28 要求做大海报，但随后要求“只额外生成，不覆盖当前项目”。成品在：

`D:/backend/project/AquaFlow-design-backup/2026-10-28-home-poster/`

- `home-water-impact-v2.jpg`：1536×472，约 177 KB
- `source.png`
- `preview.html`

**大海报未接入本次快照的首页。** 当时首页使用 `assets/ui/scene-home-water.webp`（小场景）和 `assets/banner/home-water-light.webp`（顶部背景）；后续场景图接口状态见文首。不要误以为大海报已经上线。

---

## 8. 验证状态与命令

### 8.1 当前可信的验证边界

- 静态/源码：72 路由齐全；`audit_wxml_handlers.py` 最近读到缺失绑定 0；`audit_wxss_selectors.py` 无冲突。
- 官方离线编译：RC3 文档记录两端 WXML/WXSS 编译通过。
- JS：`tests/js/run-all.js` 当前注册约 189 个套件；`find` 有 197 个 `*.test.js` 文件（含辅助/未注册文件）。
- 后端：244 个测试类，156 个集成测试类；历史精确提交 CI 有 205 类 / 1666 项通过等快照，但**不能套用到当前脏工作区**。
- 原生/真机：0/72 像素验收。
- 真实微信支付/退款：未接入。
- 生产迁移/恢复/部署：未完成。
- **本次全量读取没有执行任何测试、构建、数据库或真机操作。**

### 8.2 修改后的最小验证命令

在仓库根：

```powershell
node tests/js/run-all.js
python audit_js_syntax.py
python audit_wxml_handlers.py
python audit_wxss_selectors.py miniapp-user miniapp-delivery
node scripts/check-api-doc.js
node scripts/check-sql-catalog.js
node scripts/check-pending-decisions.js
node scripts/check-miniapp-text.js
```

后端：

```powershell
$env:GRADLE_USER_HOME='D:\backend\project\AquaFlow\.gradlehome'
cd AquaFlow-backend
.\gradlew.bat test --rerun-tasks --no-daemon --project-cache-dir .gradle_eval
```

- 测试库必须是可重建的 `aquaflow_test` 或允许的会话后缀。
- 清库前必须同时设置 `AQUAFLOW_ALLOW_DB_RESET` 和 `AQUAFLOW_ALLOW_TEST_DB_TARGET`。
- 禁止业务库、备份库、生产库。
- 多会话并行必须独立构建目录/测试库，不能共享 `build/`。
- 不能把 `UP-TO-DATE` 当本次通过；要看 XML 的 tests/failures/errors/skipped。
- 受沙箱限制时，`tests/js/run-all.js` 若拿不到子进程输出，要逐套件直跑，并检查 ASCII 收尾 `AQUAFLOW_SUITE_OK`。

---

## 9. 已知风险与未决事项

### 9.1 工程风险

1. **工作区 611 条变更混在一起**：不是单一功能分支，不能一键提交或回退。下一次修改前先确定文件边界。
2. **两套主色并存**：`#1264D6` 旧 token 与 `#1F5EA8` 新动作/表面体系。UI 改动要先选体系，不能同一卡片混用两套按钮语义。
3. **最新 RC2/R3 未入 docs 索引**，下一个模型若只看 `docs/README` 会以为 10-22 是当前视觉。
4. **72 页真机 0 验收**，尤其长文本、320/390 宽度、大字体、暗色、键盘、底部安全区、弹窗遮罩。
5. **水票 0 余额建单风险**：`createOrder` 未看到余额预检；需在改支付前复核。
6. **员工绑定码**：已有一次性 6 位码 + IP 限流，但未见失败计数；不要把它当成已完成抗穷举。
7. **公开水站端点**：当前源码已有 `PublicRateLimitInterceptor` 独立 IP 配额，旧记忆中“公开站列表无 IP 限流”已过期；仍只有单实例内存计数。
8. **限流/多实例**：登录与公开端点都是进程内计数，多实例前必须换集中式。
9. **商品图片来源**：品牌官网图未获授权，只能内测；平台自制图不能冒充品牌商品。正式上线前必须换有授权素材。
10. **通知**：客户侧进度通知基本没有；`NotificationServiceImpl` 多为日志占位，不能宣称真实推送。

### 9.2 产品待拍板（只在 `docs/design/16 §12` 维护）

- C-01 真实收款主体、商户关系、渠道费率。
- C-03 拒付错判后的复核期限、免债权限/部分免债边界。
- C-04 跨站部分售后的最终责任分担与协调者。
- C-08 超过原路退款窗口后的长期押金实际退出细节。
- C-09 正式协议主体、联系人、正文版本与历史用户告知。
- C-10 个人资料保留期、删除/匿名化字段、导出范围和核验方式。
- C-11 备份预算、保留期、异地加密目的地和管理权限。
- 复购默认交易站：沿用上次履约站还是回归属站。

**已定但不要重新问**：欠桶可下单且只提醒、回桶差异原因选填、免费原安排免二次确认、客户资产留 A、外派价格接单前固定、水票与现金/微信不混付、正常整单履约、C-13 客户提交确认+站长核对通过即入账。

### 9.3 业务未实现/未验证

- 真实微信支付、退款、回调、验签、商户证书。
- 生产数据库迁移和真实业务库。
- 正式域名/HTTPS/微信上传/体验版。
- 真实水站、真实配送员、实体手机、弱网、并发现场验收。
- 完整经营报表、真实站间转账、完整关站清结。
- 隐私协议正式正文与法定审核。
- 账户注销/导出实际执行。

---

## 10. 下一个模型怎么用这份上下文

### 如果继续做 UI/UX

1. 先读 `AGENTS.md` §1、§6、§7、§9。
2. 读本文件 §4（两套视觉系统）和 §5（全部页面）。
3. 先运行 `git status --short`，只改明确分配给自己的文件。
4. 不要以 `docs/design/37` 为唯一色板；以当前页面 WXML 实际使用的 `af-*` / `af2-*` 或旧 token 为准。
5. 任何页面修改同时检查：加载、部分失败、真实空、无权限、处理中、长文本、缺图、键盘、底部安全区、弹窗。
6. 修改后至少跑：WXML 绑定、WXSS 选择器、JS 语法、相关流程套件；不能宣称真机通过。

### 如果继续做后端/业务

1. 先读 `docs/architecture/02-领域模型.md`、`docs/design/15-业务流程地图.md`、`docs/design/16 §12`。
2. 改订单/资金/桶/票/库存前，先定位唯一写入口：
   - 桶：`BarrelLedgerService`
   - 票：`TicketLotService`
   - 收益：`StaffEarningService`
   - 订单状态：`OrderWorkflowServiceImpl`
   - 支付：`PaymentServiceImpl`
3. 先看 `schema.sql` 和 `sql/README.md`，不要凭旧文档补迁移。
4. 新增迁移必须先看磁盘最大编号；MySQL 8.4 幂等迁移需要 information_schema 预检 + PREPARE。
5. 修改后跑相关集成测试；无独立测试库就明确写“未运行”。

### 如果只做评审

先回答四个问题：

1. 当前工作区的未提交改动是否属于同一交付？
2. 这次要审的是源码、RC2 视觉、后端业务还是现场验收？
3. 结论是否有源码/测试证据，是否把静态投影误当原生像素？
4. 是否触碰 C-01/03/04/08/09/10/11 等未决产品决策？

---

## 11. 最短可信交接摘要

> AquaFlow 是 Java 17 + Spring Boot + MyBatis + MySQL 的单体桶装水配送系统，两个原生微信小程序共 72 页。当前 HEAD 是 2026-10-25 的业务基线，但工作区叠加了 2026-10-29 的全页视觉 RC2（472 个已跟踪变更、139 个未跟踪文件）。核心红线是：客户全局、资产按站隔离；归属/履约/结算三站分权，钱认结算站、客户资产认归属站；桶只走 `BarrelLedgerService`、票只走 `TicketLotService`、库存只预留不直接扣、完成配送才出库；订单/支付状态只前进不倒滚，所有状态改写用 CAS。当前 UI 同时存在 `#1264D6` 旧 token 和 `#1F5EA8` 新 `af-action/af2` 体系；72 页原生/真机验收仍为 0，真实微信支付/退款未接入，当前脏工作区不能整体回退或直接提交。下一步最稳妥的工作顺序是：先明确范围 → 固定视觉体系 → 真机验收代表页 → 再扩展同类页面或业务收口。


## 2026-10-31 开发候选归档补记

本节写给接手工程师，覆盖本次审查后代码与艾泉品牌资源的 Git 归档边界；前文带日期的历史统计保留，不作当前验收状态。

- 归档目标为既有开发分支 `codex/stage-handoff-20261004`，不合并 `main`，不等同正式上线或生产部署。私有配置、业务库导出、构建缓存、临时预览和六张尚未公开审查的本机截图不纳入。
- 后端最近一次完整基线 A：240 类、1924 项，失败/错误/跳过均 0；四处后续修订后的源码 B：相关 80 项和最终 Gradle 编译产物 13 项通过，两组有重叠，未将其拼成最终全量。完整候选的精确提交 CI 结果须单独核对。
- 本轮确认并修复现金应收核销后新增桶容量未激活、未付款水票单超时释放缺口、未匹配成本的售票收入误算利润、两个站长状态列表的跨站客户档案脱敏遗漏、领取容量与回桶异常差异口径，以及跨站应收账期重算。相关测试保留在源码；不替历史订单补账或伪造退款。
- 双端艾泉标记与最后离线品牌包一致。顾客包 SHA-256 为 `e6f85e403d390acde82be13c807c37257a5a26db09de2baf9fc66e342060d69c`，员工包为 `4d2969be439dd49303a99f93acfd0f973a17d33fdffacc8b39d46e465b5c2011`；两包共 566 个输入文件逐字节核对。此前前端全量为 198 套件、194 通过、4 项环境阻塞，随后修订仅相关回归，不称最终全量或官方微信编译通过。
- `release/prod` 中正式域名仍有占位，协议文本仍有草稿。真实微信支付、退款、回调、官方上传和最终真机链路未验证；测试部署开关不能当作真实到账。当前云实例、目标库迁移、COS 对象存在及图片渲染也未由本轮归档证实。
- 数据库升级必须先只读核对结构与备份，参见 `deployment/limited-trial/schema-preflight-v80-v87-readonly.sql` 和 SQL 正本；v87 含非幂等 ALTER，不盲目重跑。既有云调用 TLS 配置须保留，归档不修改实际环境变量或凭据。
