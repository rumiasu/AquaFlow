# REST API 参考

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
> 下表由脚本从注解直读生成（268 个端点映射 / 49 个 controller）。
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

### 1.3 幂等

- 下单等创建类接口带客户端幂等键（`idempotencyKey`）。
- **无订单支付**（在线购票，`order_id` 为 NULL）**必须传幂等键** —— 这条路径没有数据库层的唯一键保护。

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
| `/api/stations/public`、`/api/station/public` | 选站列表 |
| `/api/stations/search`、`/api/station/search` | 搜站 |
| `/api/stations/{id}/public-phone`、`/api/station/{id}/public-phone` | 水站公开电话 |
| `/api/stations/{id}/status`、`/api/station/{id}/status` | 营业状态横幅（顾客端未登录时也要能看到；只返回 id / 名称 / 状态文案，**不含站长私有字段**） |

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
  校验 `@RequireRole` 与 `@RequireStation`；**新增方法自动生效**，不依赖开发者记得加校验。
- 角色只有两种：`STATION_MANAGER`（站长）、`DELIVERY`（配送员）；顾客身份走 `customer` 体系。
- **跨站隔离**：涉及本站数据的端点以登录态 `stationId` 校验归属。
- **跨租户可见面收窄**：下发给其他水站的字段只带「钱货去向」文案与快照金额，
  不带本 station 的成本、库存与联系方式；客户画像由 `util/CustomerProfileMask` 单点抹除。

---

## 5. 端点清单

以下按业务域分组。**方法 / 路径 / 角色 / 实现方法**四列中，
「实现方法」的格式是 `Controller.方法名`，可直接定位到源码。

（脚本抽取：268 个端点映射，49 个 controller 文件）

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
| `POST` | `/api/orders` | {"STATION_MANAGER","DELIVERY"} | `OrderController.save` |
| `POST` | `/api/orders/create` | {"STATION_MANAGER","DELIVERY"} | `OrderController.createOrder` |
| `GET` | `/api/orders/my-station` | {"STATION_MANAGER","DELIVERY"} | `OrderController.getMyLatestStation` |
| `GET` | `/api/orders/{id}` | {"STATION_MANAGER","DELIVERY"} | `OrderController.getById` |
| `PUT` | `/api/orders/{id}/customer-cancel` | {"STATION_MANAGER","DELIVERY"} | `OrderController.customerCancel` |

### 配送履约

| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| `GET` | `/api/delivery/barrel-records` | {"DELIVERY","STATION_MANAGER"} | `DeliveryController.getBarrelRecords` |
| `GET` | `/api/delivery/earnings` | {"DELIVERY","STATION_MANAGER"} | `DeliveryEarningController.myEarnings` |
| `GET` | `/api/delivery/history` | {"DELIVERY","STATION_MANAGER"} | `DeliveryController.getDeliveryHistory` |
| `POST` | `/api/delivery/orders/assign/{id}` | "STATION_MANAGER" | `DeliveryController.assignOrder` |
| `GET` | `/api/delivery/orders/assigned-to-me` | {"DELIVERY","STATION_MANAGER"} | `DeliveryController.getAssignedToMeOrders` |
| `POST` | `/api/delivery/orders/cancel-request/{id}/approve` | "STATION_MANAGER" | `DeliveryController.approveCancelRequest` |
| `POST` | `/api/delivery/orders/cancel-request/{id}/reject` | "STATION_MANAGER" | `DeliveryController.rejectCancelRequest` |
| `GET` | `/api/delivery/orders/completed-today` | {"DELIVERY","STATION_MANAGER"} | `DeliveryController.getCompletedToday` |
| `GET` | `/api/delivery/orders/cross-station` | {"DELIVERY","STATION_MANAGER"} | `DeliveryController.getCrossStationOrders` |
| `GET` | `/api/delivery/orders/delivered-unpaid` | {"DELIVERY","STATION_MANAGER"} | `DeliveryController.getDeliveredUnpaid` |
| `GET` | `/api/delivery/orders/delivering` | {"DELIVERY","STATION_MANAGER"} | `DeliveryController.getDeliveringOrders` |
| `GET` | `/api/delivery/orders/directed-incoming` | "STATION_MANAGER" | `DeliveryController.getDirectedIncoming` |
| `GET` | `/api/delivery/orders/directed-returns` | "STATION_MANAGER" | `DeliveryController.getDirectedReturns` |
| `GET` | `/api/delivery/orders/dispatch-tracking` | "STATION_MANAGER" | `DeliveryController.getDispatchTracking` |
| `GET` | `/api/delivery/orders/pending` | {"DELIVERY","STATION_MANAGER"} | `DeliveryController.getPendingOrders` |
| `GET` | `/api/delivery/orders/pending-approvals` | "STATION_MANAGER" | `DeliveryController.getPendingApprovals` |
| `GET` | `/api/delivery/orders/pool` | "STATION_MANAGER" | `DeliveryController.getPoolOrders` |
| `POST` | `/api/delivery/orders/reject/{id}` | {"DELIVERY","STATION_MANAGER"} | `DeliveryController.rejectOrder` |
| `POST` | `/api/delivery/orders/report/{id}` | {"DELIVERY","STATION_MANAGER"} | `DeliveryController.reportOrder` |
| `POST` | `/api/delivery/orders/return/{id}` | {"DELIVERY","STATION_MANAGER"} | `DeliveryController.returnToStation` |
| `POST` | `/api/delivery/orders/return/{id}/approve` | "STATION_MANAGER" | `DeliveryController.approveReturn` |
| `POST` | `/api/delivery/orders/return/{id}/reject` | "STATION_MANAGER" | `DeliveryController.rejectReturn` |
| `GET` | `/api/delivery/orders/station-completed` | {"DELIVERY","STATION_MANAGER"} | `DeliveryController.getStationCompletedOrders` |
| `GET` | `/api/delivery/orders/station-delivering` | {"DELIVERY","STATION_MANAGER"} | `DeliveryController.getStationDeliveringOrders` |
| `GET` | `/api/delivery/orders/station-pending` | {"DELIVERY","STATION_MANAGER"} | `DeliveryController.getStationPendingOrders` |
| `GET` | `/api/delivery/orders/station-return` | {"DELIVERY","STATION_MANAGER"} | `DeliveryController.getStationReturnOrders` |
| `GET` | `/api/delivery/orders/station-transfer` | {"DELIVERY","STATION_MANAGER"} | `DeliveryController.getStationTransferOrders` |
| `POST` | `/api/delivery/orders/transfer/{id}` | {"DELIVERY","STATION_MANAGER"} | `DeliveryController.transferOrder` |
| `POST` | `/api/delivery/orders/transfer/{id}/cancel` | {"DELIVERY","STATION_MANAGER"} | `DeliveryController.cancelTransfer` |
| `POST` | `/api/delivery/orders/transfer/{id}/claim` | {"DELIVERY","STATION_MANAGER"} | `DeliveryController.claimTransfer` |
| `POST` | `/api/delivery/orders/transfer/{id}/outsource` | "STATION_MANAGER" | `DeliveryController.outsourceOrder` |
| `POST` | `/api/delivery/orders/transfer/{id}/reject` | {"DELIVERY","STATION_MANAGER"} | `DeliveryController.rejectTransfer` |
| `GET` | `/api/delivery/orders/{id}` | {"DELIVERY","STATION_MANAGER"} | `DeliveryController.getOrderDetail` |
| `POST` | `/api/delivery/orders/{id}/accept` | {"DELIVERY","STATION_MANAGER"} | `DeliveryController.acceptOrder` |
| `POST` | `/api/delivery/orders/{id}/cancel-dispatch` | "STATION_MANAGER" | `DeliveryController.cancelDispatch` |
| `POST` | `/api/delivery/orders/{id}/cancel-request` | {"DELIVERY","STATION_MANAGER"} | `DeliveryController.requestCancel` |
| `POST` | `/api/delivery/orders/{id}/claim-pool` | "STATION_MANAGER" | `DeliveryController.claimPoolOrder` |
| `POST` | `/api/delivery/orders/{id}/complete` | {"DELIVERY","STATION_MANAGER"} | `DeliveryController.completeOrder` |
| `POST` | `/api/delivery/orders/{id}/confirm-offline-pay` | {"DELIVERY","STATION_MANAGER"} | `DeliveryController.confirmOfflinePay` |
| `GET` | `/api/delivery/orders/{id}/cross-station-risk` | {"DELIVERY","STATION_MANAGER"} | `DeliveryController.getCrossStationRisk` |
| `POST` | `/api/delivery/orders/{id}/directed-return` | "STATION_MANAGER" | `DeliveryController.directedReturn` |
| `POST` | `/api/delivery/orders/{id}/directed-return/approve` | "STATION_MANAGER" | `DeliveryController.directedReturnApprove` |
| `POST` | `/api/delivery/orders/{id}/directed-return/reject` | "STATION_MANAGER" | `DeliveryController.directedReturnReject` |
| `POST` | `/api/delivery/orders/{id}/dispatch` | {"DELIVERY","STATION_MANAGER"} | `DeliveryController.dispatchOrder` |
| `POST` | `/api/delivery/orders/{id}/resolve` | {"DELIVERY","STATION_MANAGER"} | `DeliveryController.resolveOrder` |
| `POST` | `/api/delivery/orders/{id}/station-reject` | "STATION_MANAGER" | `DeliveryController.stationReject` |
| `GET` | `/api/delivery/stats/today` | {"DELIVERY","STATION_MANAGER"} | `DeliveryController.getTodayStats` |
| `GET` | `/api/delivery/transfers` | {"DELIVERY","STATION_MANAGER"} | `DeliveryController.getTransferRecords` |
| `GET` | `/api/delivery/transfers/incoming` | {"DELIVERY","STATION_MANAGER"} | `DeliveryController.getIncomingTransfers` |
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
| `POST` | `/api/tickets/consume` | {"STATION_MANAGER"} | `TicketAccountController.consume` |
| `GET` | `/api/tickets/customer/{customerId}` | {"STATION_MANAGER"} | `TicketAccountController.listByCustomerIdForStaff` |
| `POST` | `/api/tickets/purchase` | {"STATION_MANAGER"} | `TicketAccountController.purchase` |

### 桶资产

| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| `GET` | `/api/barrels/all-records` | {"STATION_MANAGER"} | `BarrelController.getAllRecords` |
| `GET` | `/api/barrels/records` | {"STATION_MANAGER"} | `BarrelController.listRecords` |
| `PUT` | `/api/barrels/records/{id}/status` | "STATION_MANAGER" | `BarrelController.handleReturn` |
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
| `GET` | `/api/dashboard/overview` | {"STATION_MANAGER"} | `DashboardController.overview` |
| `GET` | `/api/dashboard/report` | {"STATION_MANAGER"} | `DashboardController.report` |
| `GET` | `/api/dashboard/today` | {"STATION_MANAGER"} | `DashboardController.today` |
| `GET` | `/api/manager/alerts` | "STATION_MANAGER" | `ManagerAlertController.list` |
| `GET` | `/api/manager/customers/{customerId}/privileges` | {"STATION_MANAGER"} | `ManagerCustomerPrivilegeController.list` |
| `POST` | `/api/manager/customers/{customerId}/privileges` | {"STATION_MANAGER"} | `ManagerCustomerPrivilegeController.grant` |
| `DELETE` | `/api/manager/customers/{customerId}/privileges/{type}` | {"STATION_MANAGER"} | `ManagerCustomerPrivilegeController.revoke` |
| `GET` | `/api/manager/gross-profit` | {"STATION_MANAGER"} | `ManagerGrossProfitController.report` |
| `PUT` | `/api/manager/gross-profit/cost` | {"STATION_MANAGER"} | `ManagerGrossProfitController.setCost` |
| `GET` | `/api/manager/gross-profit/missing-cost` | {"STATION_MANAGER"} | `ManagerGrossProfitController.missingCost` |
| `GET` | `/api/manager/pending-summary` | {"STATION_MANAGER"} | `ManagerPendingSummaryController.summary` |
| `GET` | `/api/manager/reconciliation` | "STATION_MANAGER" | `ManagerReconciliationController.check` |
| `GET` | `/api/manager/setup-guide` | {"STATION_MANAGER"} | `ManagerSetupGuideController.guide` |
| `GET` | `/api/manager/station-status` | "STATION_MANAGER" | `ManagerStationStatusController.get` |
| `PUT` | `/api/manager/station-status` | "STATION_MANAGER" | `ManagerStationStatusController.update` |
| `GET` | `/api/manager/todo-summary` | {"STATION_MANAGER"} | `ManagerTodoController.summary` |

### 内容与文件

| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| `POST` | `/api/feedback` | {"STATION_MANAGER"} | `FeedbackController.submit` |
| `GET` | `/api/feedback/customers` | {"STATION_MANAGER"} | `FeedbackController.customerFeedback` |
| `GET` | `/api/feedback/my` | {"STATION_MANAGER"} | `FeedbackController.my` |
| `GET` | `/api/files` | {"STATION_MANAGER"} | `FileManageController.list` |
| `POST` | `/api/files/upload` | {"STATION_MANAGER"} | `FileManageController.upload` |
| `DELETE` | `/api/files/{id}` | {"STATION_MANAGER"} | `FileManageController.delete` |
| `GET` | `/api/notices` | {"STATION_MANAGER"} | `NoticeController.listPublished` |
| `POST` | `/api/notices` | {"STATION_MANAGER"} | `NoticeController.save` |
| `GET` | `/api/notices/all` | {"STATION_MANAGER"} | `NoticeController.listAll` |
| `DELETE` | `/api/notices/{id}` | {"STATION_MANAGER"} | `NoticeController.delete` |
| `GET` | `/api/notices/{id}` | {"STATION_MANAGER"} | `NoticeController.getById` |
| `PUT` | `/api/notices/{id}` | {"STATION_MANAGER"} | `NoticeController.update` |

### 检索与通用

| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| `POST` | `/api/common/upload` | — | `CommonController.upload` |
| `GET` | `/api/search` | {"STATION_MANAGER"} | `SearchController.search` |

---

## 6. 已删除的端点

以下端点**曾经存在但已经删除**。旧文档里仍可能出现，不要再调用；
零引用结论登记在 [`../audit/删除登记表.md`](../audit/删除登记表.md)（删除登记表正本）。

| 端点 | 删除原因 |
|---|---|
| `PUT /api/orders/{id}/status` | 状态改写只允许经 `OrderWorkflowServiceImpl` 编排，不开放通用改状态入口 |
| `POST /api/manager/reconciliation/run` | 会写**全平台**对账结果 = 跨租户泄露；现只保留只读的 `GET`，运维记录由定时任务落表 |
| `GET /api/payments/config` | 站点级线下支付总开关已移除，货到付款收敛为客户级授权 |
| `GET /api/barrels/assets` | 被桶权益批次模型取代 |
| `POST /api/barrels/handle-exception` | 被桶异常单闭环取代 |
| `GET /api/dashboard/station-exception*` | 同上 |

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
