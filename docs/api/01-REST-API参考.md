# REST API 参考

> **本文写给：接入方与工程师** —— 端点、响应契约与公开路径白名单。
> 跑起来见 [CONTRIBUTING.md](../../CONTRIBUTING.md)。

> **文档头**
>
> | 项 | 值 |
> |---|---|
> | 文档名 | REST API 参考 |
> | 状态 | 已实施 |
> | 适用对象 | 后端 / 两个小程序端 |
> | 相关代码 | `controller/**`（端点真相源）、`config/WebMvcConfig.java`（白名单与限流）、`common/Result.java`（响应体） |
> | 关联迁移 | 无 |
> | 上位文档 | `docs/architecture/01-系统架构.md` |
> | 下位文档 | 无 |

> ⚠️ **端点清单的真相源是 `controller/**` 上的注解，不是本文。**
> 下表按注解登记；当前数量和双向一致性由 `scripts/check-api-doc.js` 读取源码核对。
> 代码改动后本文会过期 —— 冲突时以注解为准。

---

## 1. 通用约定

### 1.1 响应体

所有接口返回统一信封：

```json
{ "code": 0, "message": "ok", "data": { } }
```

| `code` | 含义 | HTTP 状态 |
|---|---|---|
| `0` | 成功 | 200 |
| `1` | 业务错误（前置条件不满足、权限不足、状态非法…） | **仍是 200** |
| `404` | 路由不存在 | 200 |
| `500` | 系统异常 | 200 |

> ⚠️ **调用方必须判响应体的 `code`，不要判 HTTP 状态码。**
> 唯一的例外是**未认证**：那是真 401，会被拦截器直接拦下，不会走到 Controller。

### 1.2 身份与站点

- 身份一律从服务端解析的登录态（`AuthContext`）取，**不信任请求参数里的用户 ID 或站点 ID**。
- 客户 ID 必须由登录态覆盖，或与订单所有者严格比对。
- 站点归属校验以登录态里的 `stationId` 为准。
- 公告更新/删除只允许记录的 `stationId` 明确等于登录站；`stationId=NULL` 的系统公告及他站公告均返回 `code=1`，拒绝时不调用写 Mapper。创建公告仍强制绑定登录站，请求体不能把系统公告或他站公告改成本站归属。

### 1.3 幂等

- 下单等创建类接口带客户端幂等键（`idempotencyKey`）。
- **无订单支付**（在线购票及独立资产款，`order_id` 为 NULL）必须传客户端幂等键；一单一活跃流水键不保护 NULL 订单，须由带客户作用域的支付/购买幂等键保护。

### 1.4 指定退回审批的申请绑定

原归属站站长调用 `POST /api/delivery/orders/{id}/directed-return/approve` 或 `POST /api/delivery/orders/{id}/directed-return/reject` 时，必须提交 `{"requestId":123}`。`requestId` 是正整数，取自所见订单行的 `transferPendingRequestId`（对应 `order_transfer.id`），两张审批列表 `GET /api/delivery/orders/station-pending` 与 `GET /api/delivery/orders/directed-returns` 均下发该值；没有待审批指定退回申请时该值为空。

客户端在打开确认弹窗时固定此编号，确认时原样提交。服务在同一订单锁内核对归属站、原配送安排、当前待审批申请及编号，然后执行原有同意/拒绝流程。同意仍将订单退回归属站待分配，并搬回结算站、待收款与库存预留；拒绝仍保留原配送状态和指派。同轮相反决策只能成功一次。

缺请求体、缺编号、空编号或非正数均返回 HTTP 200、`code=1`，提示「请刷新后重新打开指定退回申请再处理」。过期、已处理或其他订单的编号返回 `code=1` 与刷新提示，不改变订单、申请、待收款或库存预留；不得静默改为审批最新一轮。客户端展示错误并刷新列表，让站长重新打开申请后确认，不自动替换编号重试。退回站长的 STAFF 审批沿用其独立契约。

### 1.5 配置与支付未知状态、文件和公告保存结果（2026-10-10 修补契约）

客户端读取 `GET /api/enterprise/manager/config` 失败，或成功信封中缺少明确布尔值 `enabled`，只表示企业设置未核实，提供局部重试，不推断功能关闭、不阻塞客户查询。只有成功的 `enabled=false` 才隐藏企业入口。阈值、平台默认值和审批规则不变；恢复后的未编辑表单回填当前配置，已输入草稿仅保留在同一登录周期。

客户原单支付回读只接受对象详情中的已知 `paymentStatus`：`0/1` 未付、`2` 已付、`3/4` 已关闭。缺详情、非对象详情、缺字段、`null`、空字符串或非法枚举均为未知，继续保留原订单与待确认文案，不据此认定未付；支付、重试和原单恢复流程不变。

文件管理上传在读取文件字节、调用对象存储之前核实登录水站，登记复用核实后的站别；保留原角色、类型/大小/分类校验及上传错误信封。公告 PUT 的 UPDATE 同时限定路径编号和登录站；零行时仅在回读确认仍属本站且请求四字段均相同的情况下视为同值保存成功，否则返回 `code=1` 与刷新提示。公告 DELETE 及对象存储删除降级不变。本段契约已通过对应前端回归及后端真实 HTTP/MySQL 定向验证（含两种更新行数模式），不代表真实COS成功上传、真机或发布验收，见 [本批验证记录](../audit/2026-10-08-四路收尾统一验收.md#unknown-states-closeout-20261010)。

---

## 2. 认证

| 项 | 说明 |
|---|---|
| 方式 | JWT 双 Token：access token 30 分钟 + refresh token 7 天 |
| 传递 | 请求头 `Authorization: Bearer <token>` |
| 续期 | access token 过期返回 401，客户端用 refresh token 换新 |
| 失效 | token 记录在 `user_token` 表，可服务端失效（登出、改密） |

**三种登录入口**：

| 入口 | 端点 | 适用 |
|---|---|---|
| 顾客微信登录 | `POST /api/auth/wx-login` | 顾客端 |
| 员工微信登录 | `POST /api/auth/wx-login-staff` | 站长 / 配送员端 |
| 姓名 + 密码 | `POST /api/auth/login` | 员工 |
| 开发登录 | `POST /api/auth/dev-login` | **仅非生产环境**，且需 `DEV_LOGIN_ENABLED=true` |

> ⚠️ `wx.login` 的 code 只能用**签发它的那个 appid + secret** 换取 openid，
> 配置错配只回 `40013 invalid appid`，日志没有指向性。故代码强制显式指定端（`CUSTOMER` / `STAFF`）。

**登录限流**：`/api/auth/{login,wx-login,wx-login-staff,dev-login,refresh,change-password}`
按**来源 IP** 限流（默认 20 次/分钟），超限返回 **HTTP 429 + `{code:1,message}`**。
⚠️ 计数是**单实例内存态**，多实例部署前必须换成集中式计数器。

---

## 3. 公开路径白名单

`config/WebMvcConfig.java` 中的白名单**是匿名访问的唯一依据**。列在其中的路径不会被 `AuthInterceptor` 拦截。

| 免登录路径 | 用途 |
|---|---|
| `/api/auth/login`、`/api/auth/wx-login`、`/api/auth/wx-login-staff`、`/api/auth/dev-login`、`/api/auth/refresh`、`/api/auth/bind-staff` | 登录与续期 |
| `/api/agreements/current`、`/api/agreements/documents/{versionId}` | 打包协议目录/指定正文只读；确认记录与资料请求仍须认证 |
| `/api/stations/public`、`/api/station/public` | 选站列表 |
| `/api/stations/search`、`/api/station/search` | 搜站 |
| `/api/stations/{id}/public-phone`、`/api/station/{id}/public-phone` | 水站公开电话 |
| `/api/stations/{id}/status`、`/api/station/{id}/status` | 营业状态横幅（顾客端未登录时也要能看到；只返回 id / 名称 / 状态文案，**不含站长私有字段**） |
| `/api/system/health` | 存活探针（F-46）：不查库、固定结构；网关/容器 liveness 与 `scripts/smoke-check.js` 的存活判据用 |

其余 `/api/**` 全部需要登录。

> ⚠️ **`/api/stations` 与 `/api/station` 是同一组端点的双前缀**（兼容历史单数写法，见
> `StationController` 的类级 `@RequestMapping({...})`）。下表用 `／` 分隔这两个等价路径。
> 新增端点时**不要**只注册一个前缀。

> ⚠️ 下表的「角色」列来自 `@RequireRole` 注解。**注解不是匿名访问的判据** ——
> 类级注解照样可能出现在上面的白名单里（例如 `/api/stations/public`）。
> 判断一个端点是否真的免登录，**只认白名单**。

---

## 4. 授权与站点归属

- 授权由 AOP 切面统一保护，切点 `execution(public * controller..*.*(..))`，
  校验 `@RequireRole` 与 `@RequireStation`；**切面对每个方法都会执行，但无注解 = 放行**
  （`RequireRoleAspect` 的 `requireRole == null` 分支）—— 新增方法**不写注解就是裸的**，
  这不是"自动获得保护"，恰恰是要开发者主动二选一（历史越权事故见 `SECURITY.md` §5.2）。
- 角色只有两种：`STATION_MANAGER`（站长）、`DELIVERY`（配送员）；顾客身份走 `customer` 体系。
- **跨站隔离**：涉及本站数据的端点以登录态 `stationId` 校验归属。
- **跨租户可见面收窄**：下发给其他水站的字段只带「钱货去向」文案与快照金额，
  不带本 station 的成本、库存与联系方式；客户画像由 `util/CustomerProfileMask` 单点抹除。

---

## 5. 端点清单

以下按业务域分组。**方法 / 路径 / 角色 / 实现方法**四列中，
「实现方法」的格式是 `Controller.方法名`，可直接定位到源码。

（端点映射与 controller 数量以 `node scripts/check-api-doc.js` 实跑为准；不手写会漂移的计数。）

### 认证与账号

| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| `POST` | `/api/auth/bind-staff` | — | `LoginController.bindStaff` |
| `POST` | `/api/auth/change-password` | — | `LoginController.changePassword` |
| `POST` | `/api/auth/create-station` | — | `LoginController.createStationAndBind` |
| `POST` | `/api/auth/dev-login` | — | `DevLoginController.devLogin` |
| `POST` | `/api/auth/login` | — | `LoginController.login` |
| `POST` | `/api/auth/logout` | — | `LoginController.logout` |
| `GET` | `/api/auth/me` | — | `LoginController.me` |
| `POST` | `/api/auth/refresh` | — | `LoginController.refresh` |
| `POST` | `/api/auth/select-role` | — | `LoginController.selectRole` |
| `POST` | `/api/auth/update-profile` | — | `LoginController.updateProfile` |
| `POST` | `/api/auth/wx-login` | — | `LoginController.wxLogin` |
| `POST` | `/api/auth/wx-login-staff` | — | `LoginController.wxLoginStaff` |
| `GET` | `/api/delivery/bind/applications` | {"DELIVERY","STATION_MANAGER"} | `DeliveryBindingController.getMyApplications` |
| `POST` | `/api/delivery/bind/apply` | {"DELIVERY","STATION_MANAGER"} | `DeliveryBindingController.applyBind` |
| `POST` | `/api/delivery/bind/cancel` | {"DELIVERY","STATION_MANAGER"} | `DeliveryBindingController.cancelApply` |
| `GET` | `/api/delivery/bind/status` | {"DELIVERY","STATION_MANAGER"} | `DeliveryBindingController.getBindStatus` |
| `POST` | `/api/delivery/bind/unbind-request` | {"DELIVERY","STATION_MANAGER"} | `DeliveryBindingController.unbindRequest` |
| `GET` | `/api/manager/bind/applications` | "STATION_MANAGER" | `DeliveryBindingController.getBindApplications` |
| `POST` | `/api/manager/bind/approve` | "STATION_MANAGER" | `DeliveryBindingController.approveBind` |
| `POST` | `/api/manager/bind/reject` | "STATION_MANAGER" | `DeliveryBindingController.rejectBind` |
| `POST` | `/api/manager/bind/release` | "STATION_MANAGER" | `DeliveryBindingController.release` |
| `POST` | `/api/manager/bind/unbind-confirm` | "STATION_MANAGER" | `DeliveryBindingController.unbindConfirm` |
| `POST` | `/api/manager/bind/unbind-reject` | "STATION_MANAGER" | `DeliveryBindingController.unbindReject` |
| `GET` | `/api/manager/staff` | "STATION_MANAGER" | `DeliveryBindingController.getManagerStaff` |
| `POST` | `/api/manager/staff/{staffId}/bind-code` | "STATION_MANAGER" | `DeliveryBindingController.generateBindCode`（F-03③：为本站员工签发绑微信的**一次性**码，10 分钟有效、用一次即废；站别只认登录态） |

### 客户与地址

| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| `GET` | `/api/addresses` | — | `AddressController.list` |
| `POST` | `/api/addresses` | — | `AddressController.save` |
| `DELETE` | `/api/addresses/{id}` | — | `AddressController.delete` |
| `GET` | `/api/addresses/{id}` | — | `AddressController.getById` |
| `PUT` | `/api/addresses/{id}` | — | `AddressController.update` |
| `PUT` | `/api/addresses/{id}/default` | — | `AddressController.setDefault` |
| `GET` | `/api/customer/exceptions` | — | `CustomerExceptionController.myExceptions` |
| `GET` | `/api/customer/exceptions/{id}` | — | `CustomerExceptionController.myExceptionDetail` |
| `GET` | `/api/customer/notifications` | — | `CustomerNotificationController.list` |
| `POST` | `/api/customer/notifications/read-all` | — | `CustomerNotificationController.markAllRead` |
| `GET` | `/api/customer/notifications/unread` | — | `CustomerNotificationController.unread` |
| `GET` | `/api/customer/notifications/unread-count` | — | `CustomerNotificationController.unreadCount` |
| `POST` | `/api/customer/notifications/{id}/read` | — | `CustomerNotificationController.markRead` |
| `GET` | `/api/customers` | {"STATION_MANAGER"} | `CustomerController.list` |
| `POST` | `/api/customers` | {"STATION_MANAGER"} | `CustomerController.save` |
| `GET` | `/api/customers/stats` | {"STATION_MANAGER"} | `CustomerController.getStats` |
| `GET` | `/api/customers/{id}` | {"STATION_MANAGER"} | `CustomerController.getById` |
| `PUT` | `/api/customers/{id}` | {"STATION_MANAGER"} | `CustomerController.update` |
| `GET` | `/api/customers/{id}/assets` | {"STATION_MANAGER"} | `CustomerController.getStationAssets` |
| `GET` | `/api/customers/{id}/offline-payment` | {"STATION_MANAGER"} | `CustomerController.getOfflinePaymentConfig` |
| `PUT` | `/api/customers/{id}/offline-payment` | {"STATION_MANAGER"} | `CustomerController.updateOfflinePaymentConfig` |
| `GET` | `/api/customers/{id}/offline-payment/summary` | {"STATION_MANAGER"} | `CustomerController.offlinePaymentSummary` |
| `GET` | `/api/customers/{id}/profile` | {"STATION_MANAGER"} | `CustomerController.getProfile` |

客户地址搜索解释（2026-10-09）：`GET /api/customers?keyword=` 和 `GET /api/manager/order-assist/customers?keyword=` 在原有姓名、电话、全部档案地址与本站订单快照的排序结果中，新增只读可空字段 `matchedAddressText`（实际匹配的单条地址）和 `matchedAddressSource`（`PROFILE` 档案地址 / `ORDER_HISTORY` 本站历史订单地址）。姓名/电话优先命中、无关键字或没有单条可解释命中时为空；不下发全部历史快照。原 `addressText` 仍为默认优先的第一条档案地址，历史命中且没有当前档案地址时该字段为空。命中解释不代表当前配送地址，代客下单仍须从原地址接口明确选地址；候选范围、排序、角色与 `adjustmentEligible` 均沿用原契约。

### 商品与库存

| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| `GET` | `/api/inventory` | {"STATION_MANAGER"} | `InventoryController.list` |
| `POST` | `/api/inventory/inbound` | {"STATION_MANAGER"} | `InventoryController.inbound` |
| `GET` | `/api/inventory/records` | {"STATION_MANAGER"} | `InventoryController.records` |
| `GET` | `/api/manager/catalog` | "STATION_MANAGER" | `ManagerCatalogController.list` |
| `GET` | `/api/manager/catalog/preset-images` | "STATION_MANAGER" | `ManagerCatalogController.presetImages` |
| `DELETE` | `/api/manager/catalog/{productId}` | "STATION_MANAGER" | `ManagerCatalogController.remove` |
| `PUT` | `/api/manager/catalog/{productId}` | "STATION_MANAGER" | `ManagerCatalogController.updateSetting` |
| `POST` | `/api/manager/catalog/{productId}/select` | "STATION_MANAGER" | `ManagerCatalogController.select` |
| `POST` | `/api/manager/catalog/{productId}/stock` | "STATION_MANAGER" | `ManagerCatalogController.setStock` |
| `GET` | `/api/manager/my-products` | "STATION_MANAGER" | `ManagerMyProductController.list` |
| `POST` | `/api/manager/my-products` | "STATION_MANAGER" | `ManagerMyProductController.create` |
| `GET` | `/api/manager/my-products/submissions` | "STATION_MANAGER" | `ManagerMyProductController.submissions` |
| `DELETE` | `/api/manager/my-products/{id}` | "STATION_MANAGER" | `ManagerMyProductController.delete` |
| `PUT` | `/api/manager/my-products/{id}` | "STATION_MANAGER" | `ManagerMyProductController.update` |
| `POST` | `/api/manager/my-products/{id}/submit` | "STATION_MANAGER" | `ManagerMyProductController.submit` |
| `GET` | `/api/products` | {"STATION_MANAGER"} | `ProductController.list` |
| `GET` | `/api/products/on-sale` | {"STATION_MANAGER"} | `ProductController.listOnSale` |
| `GET` | `/api/products/sale-by-station` | {"STATION_MANAGER"} | `ProductController.listOnSaleByStation` |
| `GET` | `/api/products/with-stock` | {"STATION_MANAGER"} | `ProductController.listWithStock` |
| `GET` | `/api/products/{id}` | {"STATION_MANAGER"} | `ProductController.getById` |

### 订单

| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| `GET` | `/api/manager/order-assist/customers` | {"STATION_MANAGER"} | `ManagerOrderAssistController.customers` |
| `GET` | `/api/manager/order-assist/customers/{customerId}/addresses` | {"STATION_MANAGER"} | `ManagerOrderAssistController.addresses` |
| `POST` | `/api/manager/order-assist/quote` | {"STATION_MANAGER"} | `ManagerOrderAssistController.quote` |
| `GET` | `/api/order-images/by-order/{orderId}` | {"STATION_MANAGER","DELIVERY","customer"} | `OrderImageController.getByOrderId` |
| `POST` | `/api/order-images/upload` | {"STATION_MANAGER","DELIVERY","customer"} | `OrderImageController.upload` |
| `GET` | `/api/order-templates` | — | `OrderTemplateController.listByCustomerId` |
| `POST` | `/api/order-templates` | — | `OrderTemplateController.save` |
| `POST` | `/api/order-templates/from-order` | — | `OrderTemplateController.setFromOrder` |
| `GET` | `/api/order-templates/quick` | — | `OrderTemplateController.getQuickOrder` |
| `DELETE` | `/api/order-templates/{id}` | — | `OrderTemplateController.delete` |
| `PUT` | `/api/order-templates/{id}/default` | — | `OrderTemplateController.setDefault` |
| `PUT` | `/api/order-templates/{id}/toggle` | — | `OrderTemplateController.toggleEnabled` |
| `GET` | `/api/orders` | {"STATION_MANAGER","DELIVERY"} | `OrderController.list` |
| `POST` | `/api/orders/create` | {"STATION_MANAGER","DELIVERY"} | `OrderController.createOrder` |
| `GET` | `/api/orders/my-station` | {"STATION_MANAGER","DELIVERY"} | `OrderController.getMyLatestStation` |
| `GET` | `/api/orders/{id}` | {"STATION_MANAGER","DELIVERY"} | `OrderController.getById` |
| `PUT` | `/api/orders/{id}/customer-cancel` | {"STATION_MANAGER","DELIVERY"} | `OrderController.customerCancel` |

### 配送履约

| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| `GET` | `/api/delivery/barrel-records` | {"DELIVERY","STATION_MANAGER"} | `DeliveryTaskController.getBarrelRecords` |
| `GET` | `/api/delivery/earnings` | {"DELIVERY","STATION_MANAGER"} | `DeliveryEarningController.myEarnings` |
| `GET` | `/api/delivery/history` | {"DELIVERY","STATION_MANAGER"} | `DeliveryTaskController.getDeliveryHistory` |
| `POST` | `/api/delivery/orders/assign/{id}` | "STATION_MANAGER" | `StationDeliveryConsoleController.assignOrder` |
| `GET` | `/api/delivery/orders/assigned-to-me` | {"DELIVERY","STATION_MANAGER"} | `DeliveryTaskController.getAssignedToMeOrders` |
| `POST` | `/api/delivery/orders/cancel-request/{id}/approve` | "STATION_MANAGER" | `StationDeliveryConsoleController.approveCancelRequest` |
| `POST` | `/api/delivery/orders/cancel-request/{id}/reject` | "STATION_MANAGER" | `StationDeliveryConsoleController.rejectCancelRequest` |
| `GET` | `/api/delivery/orders/completed-today` | {"DELIVERY","STATION_MANAGER"} | `DeliveryTaskController.getCompletedToday` |
| `GET` | `/api/delivery/orders/cross-station` | {"DELIVERY","STATION_MANAGER"} | `StationDeliveryConsoleController.getCrossStationOrders` |
| `GET` | `/api/delivery/orders/delivered-unpaid` | {"DELIVERY","STATION_MANAGER"} | `StationDeliveryConsoleController.getDeliveredUnpaid` |
| `GET` | `/api/delivery/orders/delivering` | {"DELIVERY","STATION_MANAGER"} | `DeliveryTaskController.getDeliveringOrders` |
| `GET` | `/api/delivery/orders/directed-incoming` | "STATION_MANAGER" | `CrossStationDispatchController.getDirectedIncoming` |
| `GET` | `/api/delivery/orders/directed-returns` | "STATION_MANAGER" | `CrossStationDispatchController.getDirectedReturns` |
| `GET` | `/api/delivery/orders/dispatch-tracking` | "STATION_MANAGER" | `CrossStationDispatchController.getDispatchTracking` |
| `GET` | `/api/delivery/orders/pending` | {"DELIVERY","STATION_MANAGER"} | `StationDeliveryConsoleController.getPendingOrders` |
| `GET` | `/api/delivery/orders/pending-approvals` | "STATION_MANAGER" | `StationDeliveryConsoleController.getPendingApprovals` |
| `GET` | `/api/delivery/orders/pool` | "STATION_MANAGER" | `CrossStationDispatchController.getPoolOrders` |
| `POST` | `/api/delivery/orders/reject/{id}` | {"DELIVERY","STATION_MANAGER"} | `DeliveryTaskController.rejectOrder` |
| `POST` | `/api/delivery/orders/report/{id}` | {"DELIVERY","STATION_MANAGER"} | `DeliveryTaskController.reportOrder` |
| `POST` | `/api/delivery/orders/return/{id}` | {"DELIVERY","STATION_MANAGER"} | `DeliveryTaskController.returnToStation` |
| `POST` | `/api/delivery/orders/return/{id}/approve` | "STATION_MANAGER" | `StationDeliveryConsoleController.approveReturn` |
| `POST` | `/api/delivery/orders/return/{id}/reject` | "STATION_MANAGER" | `StationDeliveryConsoleController.rejectReturn` |
| `GET` | `/api/delivery/orders/station-completed` | {"DELIVERY","STATION_MANAGER"} | `StationDeliveryConsoleController.getStationCompletedOrders` |
| `GET` | `/api/delivery/orders/station-delivering` | {"DELIVERY","STATION_MANAGER"} | `StationDeliveryConsoleController.getStationDeliveringOrders` |
| `GET` | `/api/delivery/orders/station-pending` | {"DELIVERY","STATION_MANAGER"} | `StationDeliveryConsoleController.getStationPendingOrders` |
| `GET` | `/api/delivery/orders/station-return` | {"DELIVERY","STATION_MANAGER"} | `StationDeliveryConsoleController.getStationReturnOrders` |
| `GET` | `/api/delivery/orders/station-transfer` | {"DELIVERY","STATION_MANAGER"} | `StationDeliveryConsoleController.getStationTransferOrders` |
| `POST` | `/api/delivery/orders/transfer/{id}` | {"DELIVERY","STATION_MANAGER"} | `DeliveryTaskController.transferOrder` |
| `POST` | `/api/delivery/orders/transfer/{id}/cancel` | {"DELIVERY","STATION_MANAGER"} | `DeliveryTaskController.cancelTransfer` |
| `POST` | `/api/delivery/orders/transfer/{id}/claim` | {"DELIVERY","STATION_MANAGER"} | `DeliveryTaskController.claimTransfer` |
| `POST` | `/api/delivery/orders/transfer/{id}/outsource` | "STATION_MANAGER" | `CrossStationDispatchController.outsourceOrder` |
| `POST` | `/api/delivery/orders/transfer/{id}/reject` | {"DELIVERY","STATION_MANAGER"} | `DeliveryTaskController.rejectTransfer` |
| `GET` | `/api/delivery/orders/{id}` | {"DELIVERY","STATION_MANAGER"} | `DeliveryTaskController.getOrderDetail` |
| `POST` | `/api/delivery/orders/{id}/accept` | {"DELIVERY","STATION_MANAGER"} | `DeliveryTaskController.acceptOrder` |
| `POST` | `/api/delivery/orders/{id}/cancel-dispatch` | "STATION_MANAGER" | `CrossStationDispatchController.cancelDispatch` |
| `POST` | `/api/delivery/orders/{id}/cancel-request` | {"DELIVERY","STATION_MANAGER"} | `DeliveryTaskController.requestCancel` |
| `POST` | `/api/delivery/orders/{id}/claim-pool` | "STATION_MANAGER" | `CrossStationDispatchController.claimPoolOrder` |
| `POST` | `/api/delivery/orders/{id}/complete` | {"DELIVERY","STATION_MANAGER"} | `DeliveryTaskController.completeOrder` |
| `POST` | `/api/delivery/orders/{id}/confirm-offline-pay` | {"DELIVERY","STATION_MANAGER"} | `DeliveryTaskController.confirmOfflinePay` |
| `GET` | `/api/delivery/orders/{id}/cross-station-risk` | {"DELIVERY","STATION_MANAGER"} | `CrossStationDispatchController.getCrossStationRisk` |
| `POST` | `/api/delivery/orders/{id}/directed-return` | "STATION_MANAGER" | `CrossStationDispatchController.directedReturn` |
| `POST` | `/api/delivery/orders/{id}/directed-return/approve` | "STATION_MANAGER" | `CrossStationDispatchController.directedReturnApprove` |
| `POST` | `/api/delivery/orders/{id}/directed-return/reject` | "STATION_MANAGER" | `CrossStationDispatchController.directedReturnReject` |
| `POST` | `/api/delivery/orders/{id}/dispatch` | {"DELIVERY","STATION_MANAGER"} | `CrossStationDispatchController.dispatchOrder` |
| `POST` | `/api/delivery/orders/{id}/resolve` | {"DELIVERY","STATION_MANAGER"} | `DeliveryTaskController.resolveOrder` |
| `POST` | `/api/delivery/orders/{id}/station-reject` | "STATION_MANAGER" | `StationDeliveryConsoleController.stationReject` |
| `GET` | `/api/delivery/stats/today` | {"DELIVERY","STATION_MANAGER"} | `DeliveryTaskController.getTodayStats` |
| `GET` | `/api/delivery/transfers` | {"DELIVERY","STATION_MANAGER"} | `StationDeliveryConsoleController.getTransferRecords` |
| `GET` | `/api/delivery/transfers/incoming` | {"DELIVERY","STATION_MANAGER"} | `DeliveryTaskController.getIncomingTransfers` |
| `GET` | `/api/manager/delivery-config` | {"STATION_MANAGER"} | `ManagerDeliveryConfigController.get` |
| `PUT` | `/api/manager/delivery-config` | {"STATION_MANAGER"} | `ManagerDeliveryConfigController.save` |

### 支付与资金

| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| `GET` | `/api/company-info` | — | `CompanyInfoController.getCompanyInfo` |
| `POST` | `/api/company-info` | — | `CompanyInfoController.saveCompanyInfo` |
| `PUT` | `/api/company-info` | — | `CompanyInfoController.updateCompanyInfo` |
| `GET` | `/api/deposit-records` | {"STATION_MANAGER"} | `DepositRecordController.listByCustomerId` |
| `POST` | `/api/deposit-records` | {"STATION_MANAGER"} | `DepositRecordController.add` |
| `GET` | `/api/deposit-records/customer/{customerId}` | {"STATION_MANAGER"} | `DepositRecordController.listByCustomerIdForStaff` |
| `POST` | `/api/enterprise/applications` | {"STATION_MANAGER"} | `EnterpriseController.submit` |
| `GET` | `/api/enterprise/applications/my` | {"STATION_MANAGER"} | `EnterpriseController.myApplications` |
| `GET` | `/api/enterprise/manager/applications` | {"STATION_MANAGER"} | `EnterpriseController.pendingApplications` |
| `PUT` | `/api/enterprise/manager/applications/{id}` | {"STATION_MANAGER"} | `EnterpriseController.review` |
| `GET` | `/api/enterprise/manager/config` | {"STATION_MANAGER"} | `EnterpriseController.stationConfig` |
| `PUT` | `/api/enterprise/manager/config` | {"STATION_MANAGER"} | `EnterpriseController.updateStationConfig` |
| `GET` | `/api/manager/customers/{customerId}/credit-terms` | {"STATION_MANAGER"} | `ManagerReceivableController.creditTerms` |
| `PUT` | `/api/manager/customers/{customerId}/credit-terms` | {"STATION_MANAGER"} | `ManagerReceivableController.setCreditTerms` |
| `POST` | `/api/manager/customers/{customerId}/credit-terms/recalculate` | {"STATION_MANAGER"} | `ManagerReceivableController.recalculate` |
| `GET` | `/api/manager/customers/{customerId}/risk` | {"STATION_MANAGER"} | `ManagerReceivableController.risk` |
| `GET` | `/api/manager/receivables` | {"STATION_MANAGER"} | `ManagerReceivableController.overview` |
| `GET` | `/api/manager/receivables/orders` | {"STATION_MANAGER"} | `ManagerReceivableController.orders` |
| `POST` | `/api/manager/receivables/settle` | {"STATION_MANAGER"} | `ManagerReceivableController.settle` |
| `GET` | `/api/manager/inter-station-settlements` | {"STATION_MANAGER"} | `ManagerInterStationSettlementController.ledger` —— 站间应收应付台账（**谁欠谁、欠多少、按什么价、结没结清**；"欠多少"实时算，表只记人工动作） |
| `POST` | `/api/manager/inter-station-settlements/{orderId}/settle` | {"STATION_MANAGER"} | `ManagerInterStationSettlementController.settle` —— 登记**结清**（幂等；快照当时的金额与计价口径） |
| `POST` | `/api/manager/inter-station-settlements/{orderId}/price-by-listed` | {"STATION_MANAGER"} | `ManagerInterStationSettlementController.priceByListed` —— 改按**挂牌价**结（差价由卖票站承担，写改价快照） |
| `POST` | `/api/manager/inter-station-settlements/{orderId}/reverse` | {"STATION_MANAGER"} | `ManagerInterStationSettlementController.reverse` —— **冲销**已结清的那笔（订单取消后留在台账里等人点，冲销不删原记录） |
| `GET` | `/api/payments` | {"STATION_MANAGER"} | `PaymentController.listAll` |
| `POST` | `/api/payments` | {"STATION_MANAGER"} | `PaymentController.create` |
| `GET` | `/api/payments/all` | {"STATION_MANAGER"} | `PaymentController.listAllBackup` |
| `GET` | `/api/payments/by-customer` | {"STATION_MANAGER"} | `PaymentController.listByCustomerId` |
| `GET` | `/api/payments/by-order` | {"STATION_MANAGER"} | `PaymentController.listByOrderId` |
| `GET` | `/api/payments/customer/{customerId}` | {"STATION_MANAGER"} | `PaymentController.listByCustomerIdForStaff` |
| `GET` | `/api/payments/pending` | {"STATION_MANAGER"} | `PaymentController.listPending` |
| `POST` | `/api/payments/quote` | {"STATION_MANAGER"} | `PaymentController.quote` |
| `PUT` | `/api/payments/{id}/cash-confirm` | {"STATION_MANAGER"} | `PaymentController.confirmCashById` |
| `PUT` | `/api/payments/{id}/confirm` | {"STATION_MANAGER"} | `PaymentController.confirm` |
| `PUT` | `/api/payments/{id}/refund` | {"STATION_MANAGER"} | `PaymentController.refund` |

### 水票

| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| `GET` | `/api/ticket-discounts` | {"STATION_MANAGER"} | `StationTicketDiscountController.list` |
| `POST` | `/api/ticket-discounts` | {"STATION_MANAGER"} | `StationTicketDiscountController.save` |
| `GET` | `/api/ticket-discounts/presets` | {"STATION_MANAGER"} | `StationTicketDiscountController.presets` |
| `DELETE` | `/api/ticket-discounts/{id}` | {"STATION_MANAGER"} | `StationTicketDiscountController.delete` |
| `GET` | `/api/ticket-packages` | {"STATION_MANAGER"} | `TicketPackageController.listForCustomer` |
| `POST` | `/api/ticket-packages` | {"STATION_MANAGER"} | `TicketPackageController.save` |
| `GET` | `/api/ticket-packages/manage` | {"STATION_MANAGER"} | `TicketPackageController.listForManager` |
| `DELETE` | `/api/ticket-packages/{id}` | {"STATION_MANAGER"} | `TicketPackageController.delete` |
| `GET` | `/api/ticket-records` | {"STATION_MANAGER"} | `TicketRecordController.listByCustomerId` |
| `GET` | `/api/ticket-records/customer/{customerId}` | {"STATION_MANAGER"} | `TicketRecordController.listByCustomerIdForStaff` |
| `GET` | `/api/tickets` | {"STATION_MANAGER"} | `TicketAccountController.listByCustomerId` |
| `POST` | `/api/tickets/add` | {"STATION_MANAGER"} | `TicketAccountController.add` |
| `POST` | `/api/tickets/consume` | {"STATION_MANAGER"} | `TicketAccountController.consume`（站长手工扣票）。**`idempotencyKey` 必传**（v70，缺失/空白 → `code=1`「缺少幂等键 idempotencyKey」）：`orderId` 可空，而不带订单的扣票在数据库层没有兜底（`uk_ticket_consume` 对 `order_id IS NULL` 零保护）⇒ 重复提交会重复扣。服务端按 `(customer_id, idempotencyKey)` 幂等，重试复用同一个键即原样返回、不再扣一次 |
| `GET` | `/api/tickets/customer/{customerId}` | {"STATION_MANAGER"} | `TicketAccountController.listByCustomerIdForStaff` |
| `POST` | `/api/tickets/purchase` | 客户会话 | `TicketAccountController.purchase`；同客户同编号必须保持站、商品、数量、方式、档位不变。摘要不含现价；命中原款先验内容，再跳过商品/价格现状校验。 |
| `GET` | `/api/tickets/purchase-result` | 客户会话 | `TicketAccountController.purchaseResult`；`idempotencyKey` 必传，只查当前客户原购票款。`data=null` 表示当次未查到，不授权换编号重付；有结果含 `paymentId/amount/status/statusText/stationId/productId/quantity/paymentMethod/packageId/unifiedQty`，不发起/确认收款。 |
| `POST` | `/api/tickets/purchase-intent/close` | 客户会话 | `TicketAccountController.closePurchaseIntent`；请求 `{idempotencyKey}`，只结束尚未登记任何款项的原编号，客户身份取登录态；不取消或退款已有款。 |

购票恢复：普通错误、超时、一次空查询与本地缓存均不证明原请求已结束。结束接口在事务内与建款共用同客户/编号锁，当前读无任何原款才返回 `{idempotencyKey,closed:true,payment:null}` 并永久封锁此编号；已有购票原款返回 `{idempotencyKey,closed:false,payment:<原款结果>}`，已有非购票款则业务拒绝。已有款的金额、状态和资产不因该端点改变。客户端须核原编号、内容与会话周期，存储保存/删除失败继续保留原凭据；`unifiedQty` 区分统一档与同数量散买。部署协议及结构前提见运维说明，不能把未查到或失败文案当作可换键依据。

### 桶资产

| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| `GET` | `/api/barrels/all-records` | {"STATION_MANAGER"} | `BarrelController.getAllRecords` |
| `GET` | `/api/barrels/records` | {"STATION_MANAGER"} | `BarrelController.listRecords` |
| `GET` | `/api/barrels/records/{id}/refund-eligibility` | "STATION_MANAGER" | `BarrelController.refundEligibility` —— 归属站交款前只读核对原款、可退金额和渠道；不执行退款，办理时仍重新校验 |
| `PUT` | `/api/barrels/records/{id}/status` | "STATION_MANAGER" | `BarrelController.handleReturn`（`status=3` 时**必须**带 `refundChannel`：`CASH` 当面交付 / `ONLINE` 原路退回；微信退款通道未接入 ⇒ `ONLINE` 明确拒绝，不假装已退） |
| `PUT` | `/api/barrels/records/{id}/refund-paid` | "STATION_MANAGER" | `BarrelController.markRefundPaid` —— 「押金已交顾客」**幂等**确认（重复调用不改原交付时间，只补事实，不动金额与状态） |
| `GET` | `/api/barrels/refund-undelivered` | "STATION_MANAGER" | `BarrelController.refundUndelivered` —— 「已核销未交付」**违规数据**只读清单（口径：`type=2 且 status=3 且 refund_paid_time IS NULL`） |
| `POST` | `/api/barrels/return` | {"STATION_MANAGER"} | `BarrelController.requestReturn` |
| `POST` | `/api/barrels/return-empty` | {"STATION_MANAGER"} | `BarrelController.returnEmpty` |
| `GET` | `/api/barrels/return/preview` | {"STATION_MANAGER"} | `BarrelController.previewReturn` |
| `GET` | `/api/barrels/summary` | {"STATION_MANAGER"} | `BarrelController.getBarrelSummary` |
| `GET` | `/api/barrels/summary-by-type` | {"STATION_MANAGER"} | `BarrelController.getBarrelSummaryByType` |
| `GET` | `/api/manager/adjustments` | "STATION_MANAGER" | `ManagerAdjustmentController.list` |
| `POST` | `/api/manager/adjustments` | "STATION_MANAGER" | `ManagerAdjustmentController.create` |
| `POST` | `/api/manager/adjustments/preview` | "STATION_MANAGER" | `ManagerAdjustmentController.preview` |
| `GET` | `/api/manager/adjustments/{id}` | "STATION_MANAGER" | `ManagerAdjustmentController.detail` |
| `POST` | `/api/manager/adjustments/{id}/execute` | "STATION_MANAGER" | `ManagerAdjustmentController.execute` |
| `POST` | `/api/manager/adjustments/{id}/reverse` | "STATION_MANAGER" | `ManagerAdjustmentController.reverse` |
| `GET` | `/api/manager/barrel-loss` | {"STATION_MANAGER"} | `ManagerBarrelLossController.stats` |
| `GET` | `/api/manager/exceptions` | "STATION_MANAGER" | `ManagerExceptionController.list` |
| `POST` | `/api/manager/exceptions` | "STATION_MANAGER" | `ManagerExceptionController.create` |
| `GET` | `/api/manager/exceptions/config` | "STATION_MANAGER" | `ManagerExceptionController.getConfig` |
| `PUT` | `/api/manager/exceptions/config` | "STATION_MANAGER" | `ManagerExceptionController.updateConfig` |
| `GET` | `/api/manager/exceptions/stats` | "STATION_MANAGER" | `ManagerExceptionController.stats` |
| `GET` | `/api/manager/exceptions/{id}` | "STATION_MANAGER" | `ManagerExceptionController.getDetail` |
| `POST` | `/api/manager/exceptions/{id}/execute` | "STATION_MANAGER" | `ManagerExceptionController.execute` |
| `POST` | `/api/manager/exceptions/{id}/handle` | "STATION_MANAGER" | `ManagerExceptionController.handle` |
| `POST` | `/api/manager/exceptions/{id}/write-off` | "STATION_MANAGER" | `ManagerExceptionController.writeOff` |
| `GET` | `/api/manager/owed-barrels` | "STATION_MANAGER" | `ManagerOwedBarrelController.list` |

### 组织与人员

| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| `GET` | `/api/manager/earning-items` | {"STATION_MANAGER"} | `ManagerPayrollController.listEarningItems` |
| `POST` | `/api/manager/earning-items` | {"STATION_MANAGER"} | `ManagerPayrollController.createEarningItem` |
| `GET` | `/api/manager/earning-items/directions` | {"STATION_MANAGER"} | `ManagerPayrollController.earningItemDirections` |
| `DELETE` | `/api/manager/earning-items/{id}` | {"STATION_MANAGER"} | `ManagerPayrollController.deleteEarningItem` |
| `PUT` | `/api/manager/earning-items/{id}` | {"STATION_MANAGER"} | `ManagerPayrollController.updateEarningItem` |
| `POST` | `/api/manager/earning-items/{id}/status` | {"STATION_MANAGER"} | `ManagerPayrollController.setEarningItemStatus` |
| `GET` | `/api/manager/earnings` | {"STATION_MANAGER"} | `ManagerPayrollController.listEarnings` |
| `GET` | `/api/manager/payroll` | {"STATION_MANAGER"} | `ManagerPayrollController.listPayrolls` |
| `POST` | `/api/manager/payroll` | {"STATION_MANAGER"} | `ManagerPayrollController.generatePayroll` |
| `POST` | `/api/manager/payroll/adjust` | {"STATION_MANAGER"} | `ManagerPayrollController.adjustEarning` |
| `POST` | `/api/manager/payroll/{id}/confirm` | {"STATION_MANAGER"} | `ManagerPayrollController.confirmPayroll` |
| `POST` | `/api/manager/payroll/{id}/pay` | {"STATION_MANAGER"} | `ManagerPayrollController.payPayroll` |
| `GET` | `/api/manager/piece-rate` | {"STATION_MANAGER"} | `ManagerPayrollController.getPieceRates` |
| `PUT` | `/api/manager/piece-rate` | {"STATION_MANAGER"} | `ManagerPayrollController.savePieceRate` |
| `GET` | `/api/staff` | "STATION_MANAGER" | `StaffController.listAll` |
| `POST` | `/api/staff` | "STATION_MANAGER" | `StaffController.save` |
| `DELETE` | `/api/staff/{id}` | "STATION_MANAGER" | `StaffController.delete` |
| `GET` | `/api/staff/{id}` | "STATION_MANAGER" | `StaffController.getById` |
| `PUT` | `/api/staff/{id}` | "STATION_MANAGER" | `StaffController.update` |
| `GET` | `/api/staff/{id}/profile` | "STATION_MANAGER" | `StaffController.getProfile` |
| `GET` | `/api/stations ／ /api/station` | {"STATION_MANAGER"} | `StationController.listAll` |
| `POST` | `/api/stations ／ /api/station` | {"STATION_MANAGER"} | `StationController.save` |
| `GET` | `/api/stations/mine ／ /api/station/mine` | {"STATION_MANAGER"} | `StationController.getMyStation` |
| `PUT` | `/api/stations/mine/coordinates ／ /api/station/mine/coordinates` | {"STATION_MANAGER"} | `StationController.updateCoordinates` |
| `GET` | `/api/stations/mine/list ／ /api/station/mine/list` | {"STATION_MANAGER"} | `StationController.listMyStations` |
| `GET` | `/api/stations/public ／ /api/station/public` | {"STATION_MANAGER"} | `StationController.listPublic` |
| `GET` | `/api/stations/search ／ /api/station/search` | {"STATION_MANAGER"} | `StationController.search` |
| `DELETE` | `/api/stations/{id} ／ /api/station/{id}` | {"STATION_MANAGER"} | `StationController.delete` |
| `GET` | `/api/stations/{id} ／ /api/station/{id}` | {"STATION_MANAGER"} | `StationController.getById` |
| `PUT` | `/api/stations/{id} ／ /api/station/{id}` | {"STATION_MANAGER"} | `StationController.update` |
| `GET` | `/api/stations/{id}/public-phone ／ /api/station/{id}/public-phone` | {"STATION_MANAGER"} | `StationController.getPublicPhone` |
| `GET` | `/api/stations/{id}/status ／ /api/station/{id}/status` | {"STATION_MANAGER"} | `StationController.getPublicStatus` |

### 运营支撑

| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| `GET` | `/api/dashboard/report` | {"STATION_MANAGER"} | `DashboardController.report` |
| `GET` | `/api/manager/alerts` | "STATION_MANAGER" | `ManagerAlertController.list` |
| `GET` | `/api/manager/customers/{customerId}/privileges` | {"STATION_MANAGER"} | `ManagerCustomerPrivilegeController.list` |
| `POST` | `/api/manager/customers/{customerId}/privileges` | {"STATION_MANAGER"} | `ManagerCustomerPrivilegeController.grant` |
| `DELETE` | `/api/manager/customers/{customerId}/privileges/{type}` | {"STATION_MANAGER"} | `ManagerCustomerPrivilegeController.revoke` |
| `GET` | `/api/manager/gross-profit` | {"STATION_MANAGER"} | `ManagerGrossProfitController.report` |
| `PUT` | `/api/manager/gross-profit/cost` | {"STATION_MANAGER"} | `ManagerGrossProfitController.setCost` |
| `GET` | `/api/manager/gross-profit/missing-cost` | {"STATION_MANAGER"} | `ManagerGrossProfitController.missingCost` |
| `GET` | `/api/manager/pending-summary` | {"STATION_MANAGER"} | `ManagerPendingSummaryController.summary` |
| `GET` | `/api/manager/pending-summary/return-record/{recordId}` | {"STATION_MANAGER"} | `ManagerPendingSummaryController.returnRecord`；只读本站原退桶申请 |
| `GET` | `/api/manager/reconciliation` | "STATION_MANAGER" | `ManagerReconciliationController.check` |
| `GET` | `/api/manager/setup-guide` | {"STATION_MANAGER"} | `ManagerSetupGuideController.guide` |
| `GET` | `/api/manager/station-status` | "STATION_MANAGER" | `ManagerStationStatusController.get` |
| `PUT` | `/api/manager/station-status` | "STATION_MANAGER" | `ManagerStationStatusController.update` |
| `GET` | `/api/manager/todo-summary` | {"STATION_MANAGER"} | `ManagerTodoController.summary` |

### 协议与本人资料请求（准备能力，正式及受理默认关闭）

| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| `GET` | `/api/agreements/current` | 公开正文 | `AgreementController.current`；`audience=CUSTOMER/STAFF`，返回启用状态、提示与当前两类正文 |
| `GET` | `/api/agreements/documents/{versionId}` | 公开正文 | `AgreementController.document`；指定版本只读，旧版本不等于可接受当前条款 |
| `POST` | `/api/agreements/acknowledgements` | 本人真实客户/员工（服务层校验） | `AgreementController.acknowledge`；`type=user/privacy`、`versionId`；仅启用且生效的当前对应正文可记录，当前草稿拒绝 |
| `GET` | `/api/agreements/acknowledgements/my` | 本人真实客户/员工（服务层校验） | `AgreementController.mine`；仅本人事件，拒绝任何查询参数 |
| `GET` | `/api/account/data-requests/options` | 本人真实客户/员工（服务层校验） | `AccountDataRequestController.options`；受理开关/渠道、后端请求类型及客户检查能力，拒绝身份/站别等查询参数 |
| `POST` | `/api/account/data-requests` | 本人真实客户/员工（服务层校验） | `AccountDataRequestController.submit`；`requestType`、可选`note`≤500、必填ASCII请求键`idempotencyKey`≤64；只登记SUBMITTED，新登记默认关闭 |
| `GET` | `/api/account/data-requests/my` | 本人真实客户/员工（服务层校验） | `AccountDataRequestController.mine`；仅允许可选正数`beforeId`，`items`每页最多50、`nextBeforeId`；拒绝其他参数 |
| `GET` | `/api/account/data-requests/{id}` | 本人真实客户/员工（服务层校验） | `AccountDataRequestController.detail`；本人记录与客户CLOSURE的当前只读检查；拒绝查询参数，不执行资料处理 |

协议 `app.agreements.formal-enabled` 默认false；正文SHA256、当前版本、APPROVED审核元数据、真实主体/联系方式及生效时间共同决定是否可确认，开关不能将占位草稿变为正式稿。条款接受与隐私告知确认分事件，后者不表示所有处理获同意。两种微信登录可选 `agreement={termsVersionId,privacyVersionId}`，服务端先核版本再交换code，并随真实身份写入正文快照/事件；响应附 `agreementCatalog/agreementRecorded`。字段缺失或草稿不伪造接受；开发登录、刷新及阅读不记事件，UNSELECTED白名单不扩展。

资料请求 `app.account-data-requests.enabled` 默认false；启用前受理人/渠道须真实完整。请求类型ACCESS/CORRECTION/EXPORT/DELETION/CLOSURE只表达意图，未结事项或检查失败不拒绝受理；没有实际导出、删除、匿名化、注销、处理完成或撤销凭据命令。暂停受理仍可查询本人历史及重放已登记原请求。两端正文/资料页已完成2026-10-08统一验证；2026-10-09当前草稿增量另行定向验证，不以旧CI覆盖本轮增量。客户用户协议r3仅明确新增费用/站方新安排须必要授权、免费原安排免再次确认，r2指定版本仍可读，状态DRAFT、reviewApproved=false、正式及受理开关默认关闭；详见 [协议与资料请求规格](../design/协议版本与账户资料请求.md)。

### 内容与文件

| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| `POST` | `/api/feedback` | 登录客户/员工 | `FeedbackController.submit`；普通反馈保留原匿名规则 |
| `GET` | `/api/feedback/customers` | {"STATION_MANAGER"} | `FeedbackController.customerFeedback` |
| `GET` | `/api/feedback/my` | 本人客户/员工 | `FeedbackController.my`；客户含本人关联记录及站长追加说明，员工通用反馈不借此穿透退款责任站权限 |
| `POST` | `/api/feedback/refund-notes` | 本人客户/责任站站长（服务层校验） | `FeedbackController.appendRefundNote`；`refundType`、`refundId`、`idempotencyKey`，说明/联系方式可选；仅追加，不执行资金或状态命令 |
| `GET` | `/api/feedback/refund-notes` | 本人客户/责任站站长（服务层校验） | `FeedbackController.refundNotes`；按原款/申请读说明，停业历史仍可访问 |
| `GET` | `/api/feedback/refund-options` | 本人客户（会话） | `FeedbackController.refundOptions`；`page`默认1，每页200个本人退押金申请及已收原款候选，`hasMore`如实标识；选项不代表退款成功 |
| `GET` | `/api/feedback/refund-disputes` | 本人客户/精确责任站站长（服务层校验） | `FeedbackController.refundDisputes`；`page`默认1、每页200，争议状态与历史，不收退款 |
| `POST` | `/api/feedback/refund-disputes/open` | 客户本人（服务层校验） | `FeedbackController.openDispute`；本人提出/重提，员工不得代提 |
| `POST` | `/api/feedback/refund-disputes/close` | "STATION_MANAGER"；精确责任站 | `FeedbackController.closeDispute`；必填处理结果、OPEN与期望版本；无需客户确认，客户仍可重提 |
| `GET` | `/api/files` | {"STATION_MANAGER"} | `FileManageController.list` |
| `POST` | `/api/files/upload` | {"STATION_MANAGER"} | `FileManageController.upload` |
| `DELETE` | `/api/files/{id}` | {"STATION_MANAGER"} | `FileManageController.delete` |
| `GET` | `/api/notices` | {"STATION_MANAGER"} | `NoticeController.listPublished` |
| `POST` | `/api/notices` | {"STATION_MANAGER"} | `NoticeController.save` |
| `GET` | `/api/notices/all` | {"STATION_MANAGER"} | `NoticeController.listAll` |
| `DELETE` | `/api/notices/{id}` | {"STATION_MANAGER"} | `NoticeController.delete` |
| `GET` | `/api/notices/{id}` | {"STATION_MANAGER"} | `NoticeController.getById` |
| `PUT` | `/api/notices/{id}` | {"STATION_MANAGER"} | `NoticeController.update` |

客户端自动报障复用 `POST /api/feedback`，保持实名且须本人明确确认。仅 HTTP 200 且业务 `code=0/200` 显示已上报；业务拒绝、401、其他 HTTP 状态、损坏响应及网络失败显示相应失败原因，并释放本登录周期该错误的去重记录，允许再次确认上报。反馈请求直接调用 transport，不递归询问、不自动重发；正在询问/提交或已完成/取消的相同错误仍去重，换会话独立处理，旧确认与回执不影响新会话。

### 检索与通用

| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| `POST` | `/api/common/upload` | — | `CommonController.upload` |
| `GET` | `/api/search` | {"STATION_MANAGER"} | `SearchController.search` |
| `GET` | `/api/system/health` | — | `SystemController.health`（公开，见 §3 白名单；静态存活探针，不查库） |

---

## 6. 已删除的端点

以下端点**曾经存在但已经删除**。旧文档里仍可能出现，不要再调用；
零引用结论登记在 [`../audit/删除登记表.md`](../audit/删除登记表.md)（删除登记表正本）。

| 端点 | 删除原因 |
|---|---|
| `PUT /api/orders/{id}/status` | 状态改写只允许经 `OrderWorkflowServiceImpl` 编排，不开放通用改状态入口 |
| `POST /api/orders`（裸实体整行更新） | 由客户端直传 `Orders` 实体，可改金额 / 履约站（连带结算站）/ 配送员 / 支付方式，且无状态 CAS。订单修改一律走具名业务命令或带 expected-state 的专用列更新（2026-09-25，架构评审问题 2；零调用方） |
| `POST /api/manager/reconciliation/run` | 会写**全平台**对账结果 = 跨租户泄露；现只保留只读的 `GET`，运维记录由定时任务落表 |
| `GET /api/payments/config` | 站点级线下支付总开关已移除，货到付款收敛为客户级授权 |
| `GET /api/barrels/assets` | 被桶权益批次模型取代 |
| `POST /api/barrels/handle-exception` | 被桶异常单闭环取代 |
| `GET /api/dashboard/station-exception*` | 同上 |
| `GET /api/dashboard/today` | 零消费端点，且其 `pendingOrders` 只按状态数、与待分配列表的付款闸门口径分叉（2026-09-27 产品批准，守护用例断言 `code=404`） |
| `GET /api/dashboard/overview` | 同一批删除：只服务于已删的 Vue 管理后台看板 |

---

## 7. 待确认事项

| 待确认内容 | 需要的验证手段 | 影响 |
|---|---|---|
| `/api/auth/bind-staff` 出现在 `AuthInterceptor` 的 `excludePathPatterns` 里（免登录） | 读该方法实现，确认它内部是否有独立的身份校验（如依赖一次性凭据而非登录态） | 若是纯匿名可写端点，等于任何人可发起员工与站点的绑定；需确认或补校验 |
| 端点清单会随代码漂移 | 需要时按 `controller/**` 注解重新生成，或把生成脚本纳入 CI | 本文与代码不一致时**一律以代码为准** |
| 微信支付端点尚未接入 | 取决于商户资质 | 接入后需新增支付回调端点，并在本文补一节回调约定 |

---

## 相关文档

- [`../architecture/01-系统架构.md`](../architecture/01-系统架构.md) —— 分层、横切关注点与模块划分
- [`../architecture/02-领域模型.md`](../architecture/02-领域模型.md) —— 三站语义、状态机与判权表
- [`../development/01-测试体系.md`](../development/01-测试体系.md) —— 断言约定（为什么判 `code` 而不是 HTTP 状态）
- [`../audit/删除登记表.md`](../audit/删除登记表.md) —— 删除登记表正本

## 2026-10-01 v71 业务调整入口

| 方法 | 路径 | 身份与用途 |
|---|---|---|
| `GET` | `/api/barrel-rights/quote` | 客户本人，独立押金报价 |
| `POST` | `/api/barrel-rights/purchase` | 客户本人，创建独立购买意图 |
| `GET` | `/api/barrel-rights` | 客户本人，购买历史与未知结果查询 |
| `PUT` | `/api/barrel-rights/{id}/withdraw` | 客户本人，撤回未确认未交款意图 |
| `PUT` | `/api/barrels/records/{id}/withdraw` | 客户本人，撤回未交接申请 |
| `PUT` | `/api/barrels/records/{id}/approve` | 归属站站长，批准取桶/退款安排 |
| `PUT` | `/api/barrels/records/{id}/customer-confirm` | 客户本人，确认批准安排 |
| `PUT` | `/api/barrels/records/{id}/arrangement` | 客户本人；`BarrelController.changeReturnArrangement`，只改本人未交接申请的安排，不改数量/账务；实现已冻结，统一验收待完成 |
| `PUT` | `/api/barrels/records/{id}/manager-arrangement` | "STATION_MANAGER"；`BarrelController.proposeReturnArrangement`，归属站提新安排，不代客户签确认；实现已冻结，统一验收待完成 |
| `GET` | `/api/payments/{id}/refund-preview` | 授权站长，退款组成与余额预览 |
| `GET` | `/api/manager/business-waiting` | 站长，本站当前缺货、退桶审批提醒、已收桶待退款及站间返还/净桶责任 |
| `GET` | `/api/manager/ticket-exit-batches` | 原款站站长，可按真实批次退剩余票 |
| `GET` | `/api/manager/refusal-cases` | 当事站站长，拒付事实台账 |
| `PUT` | `/api/manager/refusal-cases/{orderId}/confirm-freeze` | 资产站站长，核实冻结本站退款资格；当前待收款/未取消/未撤销/未解除CAS |
| `GET` | `/api/manager/refusal-cases/{orderId}/history` | "STATION_MANAGER"；原债权站或资产站，追加动作历史 |
| `POST` | `/api/manager/refusal-cases/{orderId}/revoke` | "STATION_MANAGER"；原债权站，撤销本案误判；同站原子解除本案冻结 |
| `POST` | `/api/manager/refusal-cases/{orderId}/release-freeze` | "STATION_MANAGER"；资产站，独立解除本案已确认冻结 |
| `GET` | `/api/manager/dispatch-agreements/{orderId}` | 当事站/池接收站站长，服务和桶报价 |
| `PUT` | `/api/manager/dispatch-agreements/{orderId}` | 归属站站长，接受前修改完整报价 |
| `GET` | `/api/manager/station-barrel-balances` | 当事站站长，实际净送桶和争议待办 |
| `POST` | `/api/manager/station-barrel-balances/{orderId}/dispute` | 当事站站长，记录桶争议 |
| `PUT` | `/api/manager/station-barrel-balances/{orderId}/proposal` | 归属站站长，提出处理方案 |
| `POST` | `/api/manager/station-barrel-balances/{orderId}/agree` | 履约站站长，确认处理方案 |
| `POST` | `/api/manager/station-barrel-balances/{orderId}/received` | 双方站长，各确认本方实际交接 |
| `GET` | `/api/manager/inter-station-recoveries` | 当事站站长，冲销后的返还债务 |
| `POST` | `/api/manager/inter-station-recoveries/{orderId}/sent` | 返还付款站站长，实际交付返还款 |
| `POST` | `/api/manager/inter-station-recoveries/{orderId}/received` | 收款站站长，实际收到返还款 |
| `POST` | `/api/inventory/{productId}/loss` | 本站站长，实物损失与受影响预留 |

独立桶权益：`GET /api/barrel-rights/quote`、`POST /api/barrel-rights/purchase`、`GET /api/barrel-rights`、`PUT /api/barrel-rights/{id}/withdraw`。仅实际款确认后生效；请求站/商品/数量及幂等键不可自算金额。

新退还：`PUT /api/barrels/records/{id}/approve` 批准安排；`PUT /api/barrels/records/{id}/customer-confirm` 客户确认；旧 status 收桶/退款命令对新凭据强制阶段闸门。收桶费单独支付和退款。

消费：`GET /api/payments/{id}/refund-preview`、`PUT /api/payments/{id}/refund`，scope 为 WATER/SERVICE/ALL_CONSUMPTION；购票剩余退款还须 expectedTicketQty、expectedTicketAmount，与已确认余额变化不符时整笔回滚。

站长：`GET /api/manager/business-waiting`、`GET /api/manager/ticket-exit-batches`、`GET /api/manager/refusal-cases`、`PUT /api/manager/refusal-cases/{orderId}/confirm-freeze`；`GET/PUT /api/manager/dispatch-agreements/{orderId}` 每单报酬和桶安排；`GET /api/manager/station-barrel-balances` 与 `{orderId}/dispute`、`proposal`、`agree`、`received` 处理实际桶争议及双方交接；`GET /api/manager/inter-station-recoveries` 与 `{orderId}/sent`、`received` 处理已付款返还。新库存损失 `POST /api/inventory/{productId}/loss` 必传预期实物、目标实物及原因。

注解、DTO 与当前源码为端点正本；资金手工登记要求实际已交付，系统不自动转账。

### 站长日常责任读数（2026-10-02）

`GET /api/manager/pending-summary` 与业务等待页使用同源的责任计数。新增 `waitingStock` 默认 P0；`returnRefund`、`recoverySend`、`recoveryReceive`、`barrelHandover`、`barrelDispute` 默认 P1，保留现有级别覆盖。每项 `available` 表示数量已核对；结构未就绪时相关 `count` 为 null、`available=false`，整体 `complete=false`。`p0Total` 仍是非零 P0 项的数量，不能当各项总单数。读取失败或未知不能解释为已全部处理完。

`GET /api/manager/business-waiting` 返回 `stock`、`returns`、`recoveries`、`barrels` 当前责任列表，以及 `counts`、`limit`、`schemaAvailable`。`counts` 含 `waitingStock`、`returnsTotal`、`returnRefund`、`recoveriesTotal`、`recoverySend`、`recoveryReceive`、`barrelsTotal`、`barrelHandover`、`barrelDispute`；总数不受列表展示上限影响。行保留原 `orderId` 或 `recordId`，附 `responsibleStationId`、`waitingSinceTime`、`waitingSinceLabel`、`waitingReason`、`nextAction`、`nextActionText`。各站只见自己当前可办理的动作，历史终态不计入责任；缺货按履约站，客户资产退款仍按归属站。

`GET /api/manager/pending-summary/return-record/{recordId}` 仅站长可调，站别取登录态；查询限制本站且 `type=2`，返回原 `BarrelRecord`、`returnDetail` 与沿用桶账的 `owedBuckets`。已收口原申请仍可查看，不受历史列表上限影响。此端点只读，审批、收桶、退款继续调用原业务命令；其他站的编号不能穿透读取。

### 站长管理只读展示补充（2026-10-06）

- 本站客户搜索 `/api/manager/order-assist/customers` 的每项新增 `adjustmentEligible`；只在客户与登录站有绑定时为 true。搜索归属仍为绑定∪本站订单，资产调整的创建/执行继续再次校验绑定。
- `/api/customers/{id}/assets` 同样下发本站 `adjustmentEligible`，用于预选客户再次核对；未取得明确 true 时新建页不允许试算或提交。
- `/api/manager/adjustments` 的本站单据补 `customerName`、`customerPhone`，不改变单据站别权限。
- `/api/manager/pending-summary` 新增 `businessWaitingTotal`，为六类业务责任数量之和（事项数，不是去重订单数）；任何分类不可用时为 null。管理菜单应收角标使用 `overdueReceivable`（逾期客户数），退桶角标使用 `barrelReturn`，未知显示“未核对”。
- 异常列表 `/api/manager/exceptions` 的待处理筛选使用 `status=STAFF_RECORDED`，与首页待办同源，并继续用 `page/size` 翻页；近30天统计独立于历史列表筛选。

站长历史页的现行查询范围：

- 订单页使用 `GET /api/orders` 的 `page/pageSize` 服务端分页，可筛客户、状态与下单起止日期，结束日包含当天。页面每页取回 20 条；跨站履约汇总另列，不随本页客户、日期、状态筛选变化。
- 水票购买收款历史先读取支付记录，再按 `orderId` 为空且 `ticketQty > 0`、生成日期进行本地筛选。全站读取最近 200 笔支付，不能称为全部购票历史；选择客户后读取该客户本站全部支付记录，每次“显示更多”仅展开已取回的 50 条，不是服务端分页。此页不是持券余额或补票记录。
- 工资结算单历史 `GET /api/manager/payroll` 默认读取本站最近 100 张，单页 `limit` 限于 1～500；可带正数 `beforeId`，按本站 `id DESC` 严格读取 `id < beforeId`，末页之后返回空数组；非正游标明确返回业务错误“读取位置无效”。仍返回原数组，站别只取登录态，他站ID仅作数值边界，不授权他站读取。员工姓名/编号和结算期间相交日期均在已加载资料内筛选；继续加载后才扩大读取范围，不改变生成结算单的期间。读取失败保留原资料与游标并标明未重新核对，不能由本地筛选消除读取失败。
- 资产调整列表只支持客户过滤和 `page/size`，默认 20、最多 100；调整类型、状态和日期不是现行接口的筛选参数。资产摘要是只读查询，刷新不得改变客户选择、表单内容或幂等意图；试算显示的内容必须与实际提交内容属于同一客户、站别和表单版本。

### 本人注销前只读检查（2026-10-05）

| 方法 | 路径 | 身份与用途 |
|---|---|---|
| `GET` | `/api/customer/account/closure-check` | 客户本人，全事实站只读清结检查，不执行注销 |

`GET /api/customer/account/closure-check` 仅已认证客户本人可访问，不接受任何身份/站别查询参数；员工与他人不可代查。无写命令、无新表或迁移。

返回 `complete`、`clear`、`message`、`checkedAt`、固定用途提示、全部事实站的最小站名/状态及 `blockingItems`。项目只带站别、类别、数量/金额、人工清结入口，不下发客户姓名/电话/openid或原流水详情。`clear=true` 仅表示本次完整读取未发现未结事项，不代表可注销或已经注销。任何结构、查询、事务完成或结果形状失败均 `complete=false, clear=false`，提示“检查未完成”，不展示部分结论。

事实站含停业站、仅有独立资产且未绑定/无水单的站；全量聚合不套用历史展示条数上限。未结订单、待收款、付款凭据、独立/随单押金购买、真实押金余额、桶/水票批次、回桶占用/欠桶、退还与异常分别检查，不跨站或商品相抵。未知站别状态、历史或凭据不完整标为人工核实，`complete=false, clear=false` 并提示“检查未完成”，不新造欠款。入口转现有客服，并明确原事实站，避免把当前选择站的资产页面误当隐藏站清结结果。实际注销/匿名化/资料留存和导出规则仍按design/16 §12 C-10待拍板，当前没有该命令。

### 资产、员工与工资写命令边界（2026-10-08）

资产调整 `clientToken` 必填且最多64字符；同键完整请求不一致拒绝，金额/单价必须可按分无损保存。试算、创建、执行均核本站绑定；冲正保留原单关联与原子执行。员工 CRUD 仅本站站长，创建的站别/配送员角色由服务器限定，更新只接受白名单且防越站/改站长/改本人。工资生成先认本站历史收益，无收益时才核本站现员工角色；转站或已删除员工的本站历史清结仍保留。以上不是新增跨站调整入口，当前源码与事务校验仍为正本。

### 异常结案写命令（2026-10-08 已编写，后端待验）

拒付撤销/解除请求为非负 `expectedVersion`、必填 `idempotencyKey`≤64及必填 `reason`≤1000；退款争议写请求再带 `refundType/refundId`，不接受身份、站别或金额。本人/原责任站来自会话和原凭据，同键只重放原回执，同键改内容/版本拒绝；新动作采用锁与版本/状态CAS，审计失败整笔回滚。未知结果须用原键、原理由和原版本查回重试。

债权站撤销不免债、不恢复赊账；跨站资产站独立复核，处理一案不解除其他有效案。争议CLOSED只是责任站已登记处理结果，客户可重新OPEN；不改实收/退款事实、资金资产、原退款资格或再次退款。新增v77结构和启动护栏未执行后端/DB验证；客户减负的免费原安排、安排更新、取消可见/CAS和首次须知免勾选仍由另一任务实施中，此处不声称已通过。

安排变更当前DTO：`pickupMode=STORE/PICKUP/COMBINED`、可选`companionOrderId`、`expectedVersion`≥1、必填`idempotencyKey`≤64及`reason`≤200；不接受资产数量、退款金额或服务费改写。v78与流程已冻结且DDL已合入schema，当前登记不代表后端编译或真实事务已通过。

### 2026-10-08 冻结接口与认证事务补充

退还批准 `/api/barrels/records/{id}/approve`、本人授权 `/api/barrels/records/{id}/customer-confirm` 增加expectedVersion：v1保留旧客户端兼容，安排变更后的v2+必须带当前版本；旧确认不得授权新安排。免费原安排批准后可交接，新增收费或站方实质新安排仍需授权。两种安排PUT只变安排版本，原申请资产、金额/原款和初始幂等内容保留，已付旧服务费先实际原款退款；变更不执行收费/退款。

`GET /api/orders/{id}` 现附 `customerCancelRequest`（准确请求ID、状态文案、结果说明、提交/处理时间）；待处理时canCancel=false。提出请求不暂停配送；批准取消成功后送达必须在副作用前拒绝，完成配送可保留自动关闭原因，不新造售后。

员工微信换码异常分类在WeChatLoginService专用入口保留：BusinessException原样抛出，其他RuntimeException保留原业务前缀；认证事务不捕获失败，协议事件和会话写入共同回滚。草稿不记录接受、UNSELECTED不记正式事件，公开白名单/权限不扩展。新增回滚专项已编写，当前编译和真实HTTP证据尚待统一运行。

### 2026-10-08 本地统一验收结果

上述“后端待验/尚待运行”为冻结记录。当前接口已完成真实HTTP/MySQL多角色及并发专项、稳定最终源码全量回归；运行输入与测试策略无漂移，矩阵按本次完整XML更新。客户确认联表写入两张表的合法两行计数已修，仍保留状态/版本CAS、收费与站方新安排授权、金额/数量和原款/占用边界；没有修改接口路径或扩大权限。详情及精确恢复见 [v76–v78统一验收回执](../audit/2026-10-08-v76-v78-统一验收.md)。

真实配置、微信、真机、生产迁移/部署未在本轮验证或启用，正式协议及资料受理保持准备状态。通用订单读取风险仍待另行复核，本轮全量通过不表示该未测面已关闭。


## 2026-10-08 四路收尾只读入口与录入契约

| 方法 | 路径 | 调用方与归属 | 参数与行为 |
|---|---|---|---|
| `GET` | `/api/customer-assets/stations` | 顾客本人，身份由JWT取，不接受customerId | `afterStationId=0`、`limit=20`；本人关联站的去重目录，含历史、零余额和停业站；只读，不产生绑定。 |
| `GET` | `/api/customer-assets/stations/{stationId}` | 顾客本人且与站有关联 | 不相关站拒绝；查看站不改变全局下单站，购买仍显式校验站别。 |
| `GET` | `/api/manager/business-waiting/returns` | 站长当前站 | `scope=ACTIVE`或`ALL`，可选`beforeId`；固定50条，申请ID倒序，返回`stationId/scope/limit/items/nextBeforeId`。 |
| `GET` | `/api/manager/refusal-cases/page` | 站长当前资产站或债权站 | `scope=ACTIVE`或`ALL`；`beforeId`与`orderId`互斥，定位返回LOOKUP；固定50条，不新增写权限。 |

`POST /api/manager/payroll/adjust`须带`idempotencyKey`，站级同键同内容重放原工资条目；改员工、条目、金额或说明拒绝，金额最多两位小数并受现有列精度约束。发布前须装v79，历史清结权限沿用。通用订单列表/详情按当前角色、本人任务和履约站过滤，跨站保留收件快照而抹客户档案；指定退回必须有真实申请，等待期间不越过履约闸门，批准/拒绝核原状态与精确申请。正式协议及资料处理仍关闭。统一验证进行中，不以作者静态检查冒称HTTP/MySQL通过。
