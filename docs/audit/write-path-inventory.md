# 写路径清单 / Write-Path Inventory（Phase A 基线）

> **性质声明**：本文件是 **2026-09-12 对 `AquaFlow-backend` 源码与两个小程序的静态扫描快照**，不是业务规范。
> 若与 `schema.sql`、运行测试或实际接口行为冲突，**以代码与数据库验证为准**，并在本文“待复核”一节记录差异、提请决策。
> 扫描方式：Grep 全仓端点注解 + 关键写方法调用点 + 部分 Controller 源码通读。未逐行读每个方法体。

## 0. 范围与方法

- 后端：`AquaFlow-backend/src/main/java/com/example/aquaflow`
- 小程序：`miniapp-user/`、`miniapp-delivery/`（活跃）；`archive/` 不维护，仅作对比
- 角色判定：方法级 `@RequireRole` + 全局 AOP 切点 `execution(public * controller..*.*(..))`（见工作记忆“权限切点”）
- 写接口定义：`@PostMapping/@PutMapping/@DeleteMapping/@PatchMapping` 的方法
- 资产写点：`Orders` / `PaymentRecord` / 桶账表（`customer_barrel_lot`/`over`/`barrel_record_lot`/`customer_barrel_in_transit`）/ 库存表

## 1. 后端写接口总表（按 Controller）

> 角色列仅标注已读到的 `@RequireRole`；其余以全局切点 + 方法内 `AuthContext.requireStationId()` 为准，需逐个读 `@RequireRole`。
> “直接写 Orders”指该方法体内出现 `orderMapper.update/ updateStatus/ updatePaymentStatus` 等。

| Controller（前缀） | 写端点 | 直接写资产 | 事务 | 备注 |
|---|---|---|---|---|
| OrderController `/api/orders` | POST `/create`、PUT `/{id}/status`、PUT `/{id}/customer-cancel` | Orders（经 service） | 视 service | 顾客端入口 |
| DeliveryController `/api/delivery` | 10 个订单状态变更写端点（reject/dispatch/resolve/transfer/return/assign/outsource/claim-pool/station-reject/complete）由 `Map` 改为 `DeliveryOrderActionDTO` 嵌套 DTO + `@Valid`（D-C2）；其余写端点（accept/confirm-offline-pay/cancel-transfer/claim-transfer/reject-transfer/approve-return/reject-return/cancel-dispatch/directed-*）本就仅 `@PathVariable`，无 Map 入参 | Orders + 桶/库存 | 部分 `@Transactional` | **P0 已收敛**：请求体 Map 风险归零 |
| ManagerOrderController `/api/manager` | 见 §2（8 个订单写端点） | Orders | 均 `@Transactional` | **P0 前端零调用** |
| PaymentController `/api/payments` | quote/create/refund 已 DTO 化（@Valid + jakarta.validation）；confirm/cash-confirm 仅路径变量；updateConfig 保留 Map（动态配置 blob） | PaymentRecord（经 service） | 视 service | Phase D-A 完成：写端点强类型化 + Bean Validation |
| BarrelController `/api/barrels` | POST `/return`(BarrelReturnRequestDTO)、PUT `/records/{id}/status`(BarrelRecordStatusDTO)、POST `/return-empty`(BarrelReturnEmptyDTO) | 桶账表 | 视 service | D-B 完成：三写端点强类型化 + @Valid，应仅经 `BarrelLedgerService` |
| InventoryController `/api/inventory` | POST `/inbound` | 库存 | 视 service | 需复核跨站校验 |
| ManagerProductController `/api/manager/products` | PUT `/{id}`、DELETE `/{id}`、POST `/inbound` | 商品+库存 | 视 service | 活跃端有调用 |
| TicketAccountController `/api/tickets` | POST `/add`、`/consume`、`/purchase`（均已 DTO 化：TicketAddDTO/TicketConsumeDTO） | 水票+PaymentRecord | 视 service | `TicketAccountServiceImpl` 直写 `paymentRecordMapper` |
| CustomerController `/api/customers` | PUT `/{id}`、PUT `/{id}/offline-payment`(CustomerOfflinePaymentDTO) | customer | 视 service | D-B 完成：offline-payment 由 Map 改为 CustomerOfflinePaymentDTO（offlinePaymentEnabled @NotNull），消除缺字段 NPE |
| StaffController/StationController/AddressController/NoticeController/OrderTemplateController/CompanyInfoController/FileManageController/CommonController/FeedbackController/CustomerExceptionController/CustomerNotificationController/DashboardController/SearchController/ProductController/OrderImageController | 各自 CRUD/配置 | 非核心资金 | 视 service | 非本轮 P0，按需后续收敛 |
| DeliveryBindingController `/api/delivery/bind`、`/api/manager/bind` | 申请/取消/解绑/审批等 | 绑定关系 | 视 service | 活跃端有调用 |

## 2. 订单状态 / 支付状态直接写点（P0 核心）

### 2.1 CAS 原语已存在（关键发现）

`OrderMapper` 已提供并发安全原语：
- `updateStatusIf(id, expectedStatus, newStatus)`：`update orders set status=#{newStatus} where id=#{id} and status=#{expectedStatus}`（line 37-38）→ 返回 affected rows = CAS ✓
- `updatePaymentStatusIf(id, newPaymentStatus, expectedStatus)`：`where id=#{id} and payment_status=#{expectedStatus}`（line 41）→ CAS ✓
- `updateStatusIfPENDING(id, status, staffId)`：`where id=#{id} and status=1`（line 66-67）→ CAS ✓
- `OrderServiceImpl.transitionStatus(id, targetStatus)`（line 621）：先 `isValidTransition` 校验，再 `updateStatusIf(id, currentStatus, targetStatus)` 并 `int affected =` 检查（line 632）→ **已是 CAS 状态机入口** ✓

**问题不是缺机制，而是调用方不遵守**：以下写点绕开上述原语。

### 2.2 DeliveryController —— 18 处非 CAS 直写（P0）

以下均为 `orderMapper.update(order)`（全行选择性 UPDATE，**read-modify-write、无 WHERE status、无 affected-row 检查**），或 1 处 `updateStatus(id, status)`（非 CAS）：

| 行号 | 所在方法（端点） | 写内容 |
|---|---|---|
| 373 | `confirm-offline-pay` | status/paymentStatus |
| 665 | `reject/{id}` | status |
| 696 | `dispatch/{id}` | status/staff |
| 779 | `resolve/{id}` | 状态 |
| 827 | `orders/transfer/{id}` | staff/状态 |
| 879 | `orders/return/{id}` | 状态 |
| 907 | `orders/assign/{id}` | staff |
| 919 | `orders/assign/{id}` | staff |
| 934 | `orders/transfer/{id}/outsource` | station/staff |
| 986 | `orders/transfer/{id}/cancel` | 状态 |
| 1001 | `orders/transfer/{id}/claim` | `updateStatus(id, PENDING)`（非 CAS） |
| 1272 | `orders/return/{id}/approve` | staff/状态 |
| 1300 | `orders/return/{id}/reject` | 状态 |
| 1327 | `orders/{id}/directed-return` | station/staff |
| 1353 | `orders/{id}/directed-return/approve` | 状态 |
| 1403 | `orders/{id}/directed-return/reject` | 状态 |
| 1410 | `orders/{id}/directed-return/reject` | 状态 |

> 另：`accept`(312)、`complete`(451) 经 `OrderServiceImpl` 工作流（需逐读确认内部是否仍直写）。
> **风险**：同订单可经 `DeliveryController` 多路径跳过状态机/退款/押金入账/并发控制；两个并发接单/确认收款可能双写成功（丢失更新）。

### 2.3 ManagerOrderController —— 8 个写端点，前端零调用（P0）

全部 `@Transactional` + `deliveryStation(order)` 站校验，但**均用 `orderMapper.update(order)` 直写，绕开 `transitionStatus`**：

1. `POST /api/manager/orders/{id}/assign`
2. `POST /api/manager/orders/{id}/dispatch`
3. `POST /api/manager/orders/transfer-apply`
4. `POST /api/manager/orders/transfer-approve`
5. `POST /api/manager/orders/transfer-reject`
6. `POST /api/manager/orders/return-request`
7. `POST /api/manager/orders/return-approve`（额外 `orderMapper.clearDeliveryStaff(orderId)`）
8. `POST /api/manager/offline-exception`（`CONFIRM_COLLECTED`/`MARK_CANCELLED`/`CORRECT_PAYMENT` 三动作，其中 `CORRECT_PAYMENT` 允许前端传 `paymentStatus`/`paymentMethod` 直写 → **越权/记错账风险**）

> 类 Javadoc 自标 `[AQ-053] 本类 8 个接口当前前端零调用`。已用 Grep 在活跃小程序交叉验证（见 §5），**确无调用方**。

### 2.4 PaymentServiceImpl —— 3 处非 CAS `orderMapper.updateStatus`

- line 242 `updateStatus(orderId, COMPLETED)`
- line 254 `updateStatus(orderId, DELIVERED)`
- line 531 `updateStatus(orderId, CANCELLED)`

均为 `OrderMapper.updateStatus(id, status)`（line 25，**无 expected status** → 非 CAS）。✓ 即 Phase C 目标“确认无合法调用后删除或限制为私有”的候选。

### 2.5 非 CAS Mapper 方法清单（Phase C 清理对象）

- `OrderMapper.updateStatus(id, status)`（line 25，`where id=#{id}` 无 expected）→ 非 CAS
- `OrderMapper.updatePaymentStatus(id, paymentStatus)`（line 62，同上）→ 非 CAS
- `OrderMapper.update(order)`（选择性全行更新）→ read-modify-write，Controller 禁用

> 对应的 CAS 版本 `updateStatusIf` / `updatePaymentStatusIf` / `updateStatusIfPENDING` 已存在，收敛时直接替换。

## 3. PaymentRecord 写点

| 写入方 | 方法 | CAS? | 备注 |
|---|---|---|---|
| PaymentServiceImpl | `insert(record)`(166,295,364,472)、`updateStatusIf(paymentId,PAID,PENDING)`(193) | updateStatusIf 是 CAS ✓ | **主写入方，纪律较好** |
| PaymentServiceImpl | `updateStatus(r.getId(), PAID)`(235,456)、`updateStatus(paymentId, REFUNDED)`(543) | 非 CAS（直 set） | 退款路径，需收敛到 CAS |
| TicketAccountServiceImpl | `paymentRecordMapper.insert(record)`(204) | — | 水票消费/购买记流水 |
| PaymentController | 持有 `paymentRecordMapper` 字段（line 34） | 待复核 | Grep 未命中 `paymentRecordMapper.` 调用（可能因小写变量名或经 service）；**需逐读确认是否直写** |

## 4. 桶账写点 / BarrelLedgerService 唯一入口核查

- 调用 `barrelLedgerService` 的文件：`BarrelController`、`BarrelServiceImpl`、`DeliveryController`（grep 命中 3 个）。
- `BarrelController.return-empty`(237) 应为员工端唯一还空桶入口；`return`(127) 为退桶。
- **待复核**：`DeliveryController` 中桶相关写（如完成配送写 `customer_barrel_in_transit`/权益）是否全部经 `BarrelLedgerService`，有无旁路径直写 `customer_barrel_lot`/`over`/`barrel_record_lot`。

## 5. 小程序调用映射 & 无调用接口标记

### 5.1 `/api/manager/**` 在活跃端的真实调用

- `miniapp-delivery`：仅 `/api/manager/bind/*`（apply/cancel/unbind-request/status）、`/api/manager/bind/approve|reject|unbind-confirm|unbind-reject|release`、`/api/manager/staff`、`/api/manager/products*`（`products/{id}`、`/shelf`、`/inbound`）→ 对应 `DeliveryBindingController` / `ManagerProductController`。
- `miniapp-user`：仅 config 定义 `MANAGER_EXCEPTIONS`→`/api/manager/exceptions`（对应 `ManagerExceptionController`），**未见实际 fetch 调用**；无任何 `/api/manager/orders/*` 或 `/api/manager/offline-exception` 引用。
- **结论**：`ManagerOrderController` 全部 8 个订单写端点 **无活跃前端调用方** → 标记“废弃候选（删除或本轮关闭 + 回归测试）”。

### 5.2 `/api/delivery/**` 在活跃端的真实调用

- `miniapp-delivery` 大量调用：`orders/pending, delivering, completed-today, accept, complete, delivered-unpaid, confirm-offline-pay, reject, transfer, return, assign, assigned-to-me, history, transfers, barrel-records, stats/today, pool, claim-pool, dispatch-tracking, directed-returns, directed-incoming, station-reject` 等 → 对应 `DeliveryController` 读+写端点，**均为活跃写入口，必须保留并收敛**。
- `archive/miniapp-station` 的 `/api/delivery/*` 引用为历史归档，不计。

### 5.3 其他

- `miniapp-user` 订单相关走 `/api/orders/*`、`/api/payments/*`、`/api/barrels/*`、`/api/tickets/*`。

## 6. 待进一步复核项（Phase A 收尾 + Phase B/C 前置）

1. `OrderServiceImpl.transitionStatus` 已确认 CAS；但 `accept`/`complete` 内部是否仍直写 `orderMapper.update` 需逐读（§2.2 注）。
2. `PaymentController` 是否直写 `paymentRecordMapper` 需逐读（§3）。
3. `DeliveryController` 桶账写是否全经 `BarrelLedgerService`（§4）。
4. `InventoryController.inbound` 跨站校验与事务边界。
5. `OrderMapper.update(order)` 选择性更新在 `NULL` 到 `NOT NULL DEFAULT 0` 列的丢失更新/空值问题（工作记忆已知 MySQL DEFAULT 仅省略列时生效）。
6. `ManagerOrderController.offline-exception` 的 `CORRECT_PAYMENT` 允许前端传 `paymentStatus` 直写，属越权/记错账高危，Phase C 必须删除或强校验。

## 7. Phase A 验收对照

- [x] 所有订单/支付/桶/库存写入口均有明确归属（§1-§4 已列）。
- [x] 无“可能存在”的写接口：已用 Grep 全仓定位 `orderMapper.update/ updateStatus/ updatePaymentStatus` 全部调用点（§2）。
- [x] 小程序对 `/api/manager/**`、`/api/delivery/**` 调用已映射，无调用接口已标记（§5）。
- [x] 测试护栏基架（§Phase A.4）：**已建立**（2026-09-12，转由 Phase B 完成）。本机无 Docker→弃用 Testcontainers，改用独立可重建测试库 `aquaflow_test` + 真实 Spring 上下文 + 真实 HTTP。详见 `docs/audit/test-harness.md`，一键脚本 `scripts/verify.sh`。
- [x] `docs/audit/write-path-inventory.md` 已产出，并显式声明“代码扫描快照而非业务规范”。

---
## 8. Phase D 收敛记录（写路径强类型化 + Bean Validation）

- **D-A（资金）已完成**：
  - `PaymentController`：`POST /quote`、`POST /`、`PUT /{id}/refund` 由 `Map<String,Object>` 改为强类型 DTO（`PaymentQuoteDTO`/`PaymentQuoteItemDTO`/`PaymentCreateDTO`/`PaymentRefundDTO`）+ `jakarta.validation` 注解 + `@Valid`；非法入参在边界被 `MethodArgumentNotValidException` → `Result.error` 拒回，不落库。
  - `PUT /{id}/confirm`、`PUT /{id}/cash-confirm` 仅用 `@PathVariable`，本就强类型，无需改。
  - `PUT /config` 为动态配置 blob，保留 `Map`（符合方案"字段杂乱保留 Map"例外）。
  - `TicketAccountController` 的 add/consume/purchase 早已 DTO 化（TicketAddDTO/TicketConsumeDTO），本轮仅确认，未改。
  - 新增契约测试 `PaymentDtoValidationIntegrationTest`（4 例：缺 orderId / 缺 paymentMethod / 空 items / 缺 stationId 均被拒且不落库）。
  - `amount` 保持可空（服务端以订单金额重算，缺失按 0），与改造前行为一致。
- **D-B（桶/押金）已完成**：
  - `BarrelController` 三个写端点由 `Map<String,Object>` 改为强类型 DTO + `@Valid`：
    - `POST /return` → `BarrelReturnRequestDTO`（stationId 可空、productId @NotNull、quantity @Min(1)、note）。
    - `PUT /records/{id}/status` → `BarrelRecordStatusDTO`（status @NotNull、handleNote）。
    - `POST /return-empty` → `BarrelReturnEmptyDTO`（customerId @NotNull、clientToken @NotNull、items @NotEmpty @Valid/嵌套 `BarrelReturnEmptyItemDTO`{productId @NotNull, qty @NotNull}）。
  - `CustomerController.PUT /{id}/offline-payment` 由 `Map<String,Object>` 改为 `CustomerOfflinePaymentDTO`（offlinePaymentEnabled @NotNull）——原 Map 实现在缺字段时 `Integer.valueOf(null.toString())` NPE，现以 `@NotNull` 把"授权标志必须存在"变为编译期可验证契约；原"缺省回 0"行为不再有（miniapp 始终发送该字段，无回归）。
  - 全部复用以 `jakarta.validation`（Spring Boot 4 命名空间），复用既有 `GlobalExceptionHandler` 对 `MethodArgumentNotValidException` 的 `Result.error` 转换，**未新增异常基础设施**。
  - 新增契约测试 `BarrelDtoValidationIntegrationTest`（6 例：return 缺 productId / quantity=0、return-empty 缺 customerId / 缺 clientToken / 空 items、offline-payment 缺 flag 均被拒且不落库/不改授权）。
  - 既有 `BarrelLedgerIntegrationTest`（7 例）/ `ConcurrencyIntegrationTest`（3 例）仍全绿，确认 JSON 契约与改造前一致、桶账行为不变。
- **D-C（订单/OrderWorkflow）已完成**（分两小提交，全量测试绿）：
  - **D-C1（OrderController）**：`createOrder` 由 `@RequestBody OrderCreateDTO` 改为 `@RequestBody @Valid OrderCreateDTO`；`OrderCreateDTO.items` 加 `@NotNull`/`@NotEmpty`/`@Valid`，嵌套 `OrderItemDTO` 的 `productId` `@NotNull`、`quantity` `@NotNull @Min(1)`——边界拒回空单/缺商品/数量<1。下单入口（顾客端）强类型化完成。
  - **D-C2（DeliveryController —— P0 重灾区）**：原 10 个订单状态变更端点（reject/dispatch/resolve/transfer/return/assign/outsource/claim-pool/station-reject/complete）由 `Map<String,Object>` 改为 `DeliveryOrderActionDTO` 的嵌套 DTO（Reject/Dispatch/Resolve/Transfer/ReturnToStation/Assign/Outsource/ClaimPool/StationReject/Complete）+ `@Valid`；`complete` 因 `OrderWorkflowServiceImpl.completeDelivery(id, Map)` 仍吃 Map，采用桥接 `toCompleteParams(Complete)`，入参边界校验后转 Map 下发，行为不变。其余 DeliveryController 写端点本就仅 `@PathVariable`、无 Map 入参。
  - **关键结论**：DeliveryController 全部"请求体 Map 风险"归零；订单写路径 P0 强类型化闭环。
  - 新增契约测试 `OrderDtoValidationIntegrationTest`（3 例）、`DeliveryOrderDtoValidationIntegrationTest`（7 例：缺 reason/缺 targetStationId/缺 deliveryStaffId/缺 orderItemId/缺 productId/空 items/缺 tryDispatch 均被拒且不落库，complete 合法空体正常放行）。全量 52 测试绿。
- **Phase D 收尾状态**：D-A(资金)/D-B(桶/押金)/D-C(订单) 全部完成，方案 A 范围内 P0 写路径强类型化 + Bean Validation 闭环；未做 Controller 列表外的非 P0 端（DeliveryBindingController 等），按方案 A 不在本轮。

---

## 9. Phase F 收敛记录（非 P0 请求体 Map 清零）

- **范围**：D 阶段（方案 A）剩余的全部 19 个 `@RequestBody Map` 请求体端点，分认证/绑定/其他三族一次收敛，契约保形（小程序发送的 JSON 键名/类型逐一对齐，`_pendingOpenid` 以 `@JsonProperty` 映射）。
  - **认证族**：`LoginController` 9 端点（wx-login/wx-login-staff/select-role/create-station/bind-staff/update-profile/login/refresh/change-password）+ `DevLoginController.dev-login` → `AuthRequestDTO` 嵌套 DTO + `@Valid`。原手工判空（code/角色合法性/姓名手机号/新旧密码/新密码≥6位）全部前移为注解校验，消息文案保持原样。
  - **绑定族**：`DeliveryBindingController` 6 端点（apply/approve/reject/unbind-confirm/unbind-reject/release）→ `BindingActionDTO`（ApplyBind/Handle/Release）；applicationId+staffId 二选一的兼容语义保留（不加 @NotNull）。
  - **其他**：`FeedbackController.submit` → `FeedbackCreateDTO`；`ManagerProductController.save/update` → `ManagerProductDTO.Save/Update`（imageUrl 旧格式回退 `extractObjectName` 保留；库存配置块由 `containsKey` 改为「任一字段非空」判定，语义等价）。
- **保留 Map（既定例外）**：`PaymentController.updateConfig`、`ManagerExceptionController.updateConfig` —— 动态配置 blob，键集合开放。
- 新增契约测试 `AuthBindingDtoValidationIntegrationTest`（9 例：wx-login 缺 code / login 缺 username / 改密过短 / apply 缺 stationId / release 缺 staffId / 反馈空内容（且不落库）/ 反馈合法体放行 / 商品缺 name / 缺 price，均 code=1 拒回不落库）。全量 **61 测试绿**。
- **至此全后端请求体 `Map<String,Object>` 强转风险清零**（仅剩 2 个动态配置 blob 端点按例外保留）。

*本文件由 Phase A 静态扫描生成，后续 Phase B/C/D 改动写接口时应同步更新本表。*
