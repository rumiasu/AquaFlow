---
name: aquaflow-known-traps
description: AquaFlow 的「已知坑与历史教训」正本 —— 原 AGENTS.md §8 的 **30 条**判据全文（接口 code、并发桶账、状态倒滚、时间区间上界、按 id 操作验归属、隔离 worktree 的假绿、小程序 js 解析期错误与 BOM、弹窗确认没接上…）。**动手写/改代码前加载**：这些是"改这里会再踩一次"的护栏，不是背景知识。文中的 §8.N 就是原 AGENTS §8 的条目号（docs/design 与测试注释仍在引用它）。
whenToUse: 要动订单状态机 / 取消与退款链 / 桶账与押金 / 对账等式 / 时间区间统计 / 按 id 操作记录 / DTO 字段收敛 / 小程序 js 与 wxss / 调 event handler / 写测试与跑全量（含隔离 worktree）时，动手前先过一遍对应条目。
---

# AquaFlow 已知坑与历史教训（AGENTS.md 原 §8 · 正本）

> **为什么在这里**：`AGENTS.md` 有体积预算（≤ 65536 字节，超出会被**静默削尾**，第二轮就是这么出事的）。
> 第三轮瘦身（2026-09-22）按 §9 当年的同一先例，把 §8 这 30 条**原样**搬到这里，
> `AGENTS.md` §8 只留「指针 + 最常踩的 10 条一行版」。
>
> ⚠️ **编号仍是外部引用锚点**：`docs/design/**`、测试注释与代码注释里的 `§8.15` / `§8.16` / `§8.19` / `§8.20` 等
> **指的就是本文的条目号** —— **不要重排、不要合并条目**，改动只压正文。
> 判据（沿用原文）：**每条只留「判据」**；发现经过、误判原因与排线时间线在
> 仓库外的瘦身前存档。

> **每条只留「判据」**；发现经过、误判原因与排错时间线在 仓库外的瘦身前存档。
> ⚠️ **编号是外部引用锚点**（`docs/design/**` 与测试注释会写 §8.15 / §8.16 / §8.19 / §8.20 等）—— **不要重排、不要合并条目**，只压正文。

1. **接口报错但 HTTP 200** —— 只判 HTTP 状态会把业务失败当成功。**一律判 body `code`。**
2. **REPEATABLE READ 下的并发桶账** —— 只加行锁不够：第二个事务拿到锁后普通 `SELECT` 仍读**旧快照**。**并发写路径必须「加锁 + 当前读 `FOR UPDATE`」两件套**；`INSERT ... ON DUPLICATE KEY UPDATE` 必须 upsert。
3. **取消订单的"钱货分家"** —— 跨站外派单取消：钱/票记**归属站**、库存回补记**履约站**（曾混用，真丢钱）；营收归属见 §1.1。`deposit_record.related_order_id` 必须落库。**释放押金锚定「有没有 PREPARE 流水」，不能只看 `orders.deposit_amount`**（那是应收）。
4. **水票是唯一「下单即视同已付」的方式** —— 它绕过 `confirmPayment`，押金入账必须在 `TicketAccountServiceImpl` 那条路径自己补；否则客户用票付了押金、账户是 0，退桶退不出钱。
5. **`AbstractIntegrationTest.resetDatabase()` 会 TRUNCATE 全表** —— 靠「断言库名含 `test`」做最后护栏；**改测试数据源前先看这条护栏**。
6. **Spring Boot 4.0.6 已移除 `TestRestTemplate`** —— 测试用 JDK `HttpClient` + `@Value("${local.server.port}")`。
7. **Jackson 2/3 并存** —— Web 层是 Jackson 3（对它设 `WRITE_DATES_AS_TIMESTAMPS` 会启动失败）；手工 `new ObjectMapper()` 拿到的是 Jackson 2。时间统一 ISO-8601，前端一律 `new Date(str)`，**禁止 `.replace(/-/g,'/')`**。
8. **`station.offline_payment_enabled` 已从真实库删除** —— 货到付款唯一控制点是 `customer_station_config.offline_payment_enabled`（客户级、站长逐个开通）；历史脚本与 `backup/*.sql` 里的残留属预期。
9. **`DEV_LOGIN_ENABLED` 默认关闭、生产必须 `false`** —— 但 `miniapp-delivery` 登录页**无条件渲染「开发者登录」按钮**，不检查 `__wxConfig.envVersion`。
10. **两端 `config/api.js` 的 `prod.baseUrl` 是占位域名** —— 发版前必须替换。
11. **文档漂移（不要照着做）** —— 曾点名根 `README.md` 与 `docs/AGENTS.md` 的过期内容，均已订正。**判据一：点名某文档漂移前先复核**；**判据二：本机有一批有意不入库的文档**（规则在 `.git/info/exclude`），**不许写进任何已入库文件**。冲突以常量正本（如 `OrderStatus.java`）为准。
12. **「测试库全绿 ≠ 真实库可用」** —— 真实库索引可能停在旧形态（实测 `uk_payment_order_status`/`uk_ticket_consume` 未纳入 `source` → 退款/回补**必然 1062**，而用例全绿毫无察觉）。**判据（长期有效）：涉及唯一键/索引的改动必须到真实库核对 `information_schema.STATISTICS`，优先用「事务内造数 → 观察是否成功 → ROLLBACK」的行为法。（该漂移已消除）**
13. **不要照真实库反向改 `schema.sql`** —— 先判定哪边对，再把两边同时改齐。
14. **注释会诱导误用（三次事故的共同诱因）** —— `StationController./mine` 上方的悬空 javadoc 把**员工**接口说成顾客接口，顾客端据此三次误调。⚠️ **这类拒绝不是真 403**：`RequireRoleAspect` 抛 `BusinessException` → **HTTP 200 + `code=1`**。**判据：悬空/过期注释必须删；注释里不要写行号**（写注释本身就让行号失效），要指位置就写函数名/关键字。定位手法：搜「`*/` 后紧接 `/**`」（`audit_comments.py`，已进 CI）。
15. **「请求体从裸 `Map` 收敛成强类型 DTO」会静默丢字段** —— `DeliveryOrderActionDTO.Complete` 漏抄 `collected` 与 `note`，而 service 在读、配送端在发；**Jackson 对未知字段静默忽略** → 配送员点「已收款」被当「未收款」：订单停在已送达(3)、`payment_status` 被改写成未付(0)、**钱不入账也无 PAID 流水**、备注写不进 `orders.special_note`。**判据：改 DTO 必须逐字段核对老 Map 键名与前端实际发送体（grep 调用点），并补一条走 HTTP 的用例。** 用例 `DeliveryCompleteIntegrationTest`。
16. **只遍历 `customer_barrel_asset` 是本仓惯犯（第 4 次）** —— 见 §1.1 并集不变式。**`getBarrelSummary` 与 `getBarrelSummaryByType` 的占用口径必须永远相等。** 用例 `occupiedCountsOwedBarrelsEvenWithoutRights`。
17. **「要求了补偿却没执行」也必须失败** —— 退水票分支曾在 `adjustProductId` 为空时只 `log.warn`、照样推到 `EXECUTED`（界面"已补偿"、票一张没多，同 §8.15"静默成功"）。现抛 `BusinessException` 整体回滚、退回 `STAFF_RECORDED` 可重试。**判据：任何"用户以为做成了、账上没动"的分支都算缺陷，宁可失败出声。** 用例 `BarrelExceptionFlowIntegrationTest.refundTicketsRequiresProductThenCreditsAccount`。
18. **订单状态不许倒滚** —— `unconfirmOrderCollection` 曾把 已完成(4) 改回 已送达(3) 且不动 `payment_status`，已删除。**护栏**：`PaymentFlowIntegrationTest.noUnconfirmCollectionEndpoint` 断言 4 条路径全 404。**通用规则：状态只前进；要表达异常态就新设状态，不要复用/回退。**
19. **时间区间上界写成 `<= 当天` 会漏掉一整天** —— `LocalDate` 今天在 SQL 里等价于 `<= 今天 00:00:00`，**今天新增的一条都统计不到**。**判据：按「某天（含）」筛 datetime 列一律 `>= 起始 AND < 结束+1天`。** 用例 `ExceptionStatsIntegrationTest.statsIncludesToday`。
20. **「按 id 操作记录」必须逐条验证归属，且必须检查受影响行数** —— 三处实例：`OrderTemplateServiceImpl.save` 传别人的模板 id 会**覆盖别人的模板并连带清空其明细**（[AQ-036]）；`AddressServiceImpl.delete` 的 SQL 带了 `and customer_id=?` 但 service **不看返回值**、控制器无条件 success → 客户端收到"成功"、刷新又冒出来；`setDefault` 不校验归属 → **清掉自己的默认、把别人的改成默认**。**判据：接口收客户端可编造的 id 就必须回答"这条属于调用者吗"；拿不到行数就别返回 success。** 用例 `AddressAndOrderTemplateIntegrationTest`。
21. **「外部依赖没配 / 客户端输入问题」不得升级成系统异常** —— ① COS 未配时上传/删除抛运行时异常 → `code=500` + 一条 SYSTEM 告警，DB 行仍在；**判据：可预期的运维状态给 `code=1` + 可读文案并记 ERROR，删除路径可 WARN 后继续清 DB 记录**，用例 `FileUploadIntegrationTest`（看 body `code`）。② 客户端漏发请求体抛 `HttpMessageNotReadableException` → 落 `Exception` 分支变 `code=500` + SYSTEM 告警（`GlobalExceptionHandler:151-155` 的 400 类白名单里没有它）；且 `MethodArgumentNotValidException` 的字段级 message 被丢成「参数格式不正确：参数」，客户端拿不到"数量至少 1""请指定配送员"。
22. **「零覆盖端点」的真实形态是「界面空白」** —— mapper JOIN 写错**不抛异常、只返回空列表**，界面"今天没有单"与"确实没有单"无法区分。已补契约级用例（这批接口本身无功能缺陷，但抓到 §8.21 与 `updateStatusIf` 参数写反那批）：`DashboardNoticeSearchFeedbackIntegrationTest`、`InventoryStaffProductIntegrationTest`、`TicketDepositNotificationIntegrationTest`、`DeliveryConsoleAndSelfServiceIntegrationTest`、`DirectedReturnAndReturnToStationIntegrationTest`、`FileUploadIntegrationTest`。
23. **死端点评估（当时 39 条：删 8 / 接线 19 / 保留 12）—— 两个必须先处理的发现均已处置**：
    - **`GET /api/files` 跨站可见 —— 已修（v45）**：原 `listAll`/`listByCategory` **无水站过滤**、`file_info` **无 `station_id` 列** → 任何站长能列出**全部水站**文件名与临时 URL。现补 `station_id`（**NULL = 平台级文件、全站可见**，不是脏数据）+ `idx_file_station`，存量按 `uploader_id → staff.station_id` 回填（**查不到保持 NULL**），查询改 `listVisible` / `listVisibleByCategory`（本站 **或** 平台级），用例 `FileUploadIntegrationTest.fileListAndDeleteAreStationScoped`。**判据：接线前必须先做站隔离（加列 + 回填 + 过滤），否则整族删掉；有语义的 NULL 不许当脏数据清。**
    - **`GET /api/delivery/orders/station-exception` 名不副实 —— 已删（2026-09-18）**，用例 `ManagerOrderControllerRemovedIntegrationTest`：它过滤 `status=5（已取消）`，一个"异常"标签返回**取消单**，与 `GET /api/orders?status=5` 重复。**判据：端点名与返回集不符必须删或改名**（同批删 `GET /api/dashboard/order-status`、`/order-trend`、`GET /api/customer/exceptions/list`，`OrderMapper` 的 `listStationExceptionOrders`/`countByStatusByStationId`/`trendLast7DaysByStationId` 一并删并留墓碑注释）。
    - **其余删除候选等产品点头**；每条都挂着契约断言或文档表格行 —— 删代码必须连带改测试与文档。
24. **写入口冗余（未动代码）** —— 押金两条写路径：`POST /api/deposit-records`（全部白名单类型）与站长调整单 `DEPOSIT_GRANT/DEDUCT`（最终都调 `depositRecordService.add`）；水票 `addTicket` **没有** `adjustment_id` 幂等键（`adjustTicket` 有 `uk_ticket_adjustment` 兜底）。`docs/design/10` 还把前者称作"押金调整的正确入口"。**动之前先想清哪条是正门。**
25. **`PUT /api/payments/{id}/cash-confirm` 与 `PUT /{id}/confirm` 实现逐字相同**（都只调 `confirmPayment`），但已进验收文档（IT-PAY-002 / IT-CNF-002）→ **保留、登记，不要合并。**
26. **「隔离工作区跑全量测试」证明不了"应用能起来"** —— worktree **只含 HEAD、不含未提交文件**（实测多个构造器没标 `@Autowired` → 上下文起不来，而隔离 worktree 里用例全绿）。**收尾三步都要做**：① 真实工作区 `.gradlew.bat clean compileJava compileTestJava`；② 真起一次上下文（`test --tests '*AquaFlowApplicationTests*'`，11 秒才是真起过）；③ 再在隔离 worktree 跑全量。**Spring 只在"有且仅有一个构造器"时自动选它**，多于一个必须给生产那个标 `@Autowired`。⚠️ 隔离 worktree 没有 `application-local.yml`，那几个必需变量**都得从环境变量传**，缺了 `RequiredConfigChecker` 拒启。
27. **小程序 js 的「解析期」错误此前无门禁** —— `miniapp-delivery/api/station-mgmt.js` 里 `const updateOfflinePayment` 重复声明 → `SyntaxError` → **整个模块不执行**，而站长端 **15 个页面**都 require 它 → 整端白屏；后端 382 例与 4 个静态门禁**一个都没报出来**（都不解析 js）。**判据一：语义审计抓不到少括号/多逗号/重复声明 —— 改完小程序 js 必须过解析器**（`audit_js_syntax.py` 用 `node --check` 两端全部 js，进 `scripts/verify.sh` 与 CI，缺 node 时**跳过并明说**而不是判绿）。**判据二：api 模块别写"重载形态"**（`(id, enabled)` 与 `(id, payload)` 靠调用方自觉 = 迟早撞车），一个函数一种签名、由调用方把 body 给全。**判据三：用例依赖什么就自己声明什么** —— 有 1 例靠 `application-local.yml` 开 `dev-login`，而 CI 把它设成 `false` → **只在 CI 上红**；用 `@TestPropertySource` 修（见 §5）。
28. **小程序文件带 UTF-8 BOM 会让 IDE 编译失败，本地门禁全查不出** —— `Set-Content -Encoding UTF8` 写入的是 **BOM + CRLF**（仓库其余文件 LF 无 BOM）→ 开发者工具报 `编译 .wxss 文件错误` 且**不指名文件**，而四个门禁与 `page_reach_audit` **全绿**。**判据一：改小程序文件别用 `Set-Content -Encoding UTF8`**（用 `edit`/`write`，或 `New-Object System.Text.UTF8Encoding($false)` + `File.WriteAllText`）。**判据二：遇"编译错但门禁全绿"先查 BOM**（前三字节 `EF BB BF`），**再读 IDE 日志**（`%LOCALAPPDATA%\微信开发者工具\User Data\<hash>\WeappLog\logs\*.log`）。
29. **「解除员工」不校验角色 → 站长能把自己解除，水站变孤儿（已修）** —— `POST /api/manager/bind/release` 原来只校验「员工存在 + 属于本站」，**无 role 校验、无"别解除自己"**；而列表源 `StaffMapper.listByStationId` **不带 role 条件**，站长自己也在列表里且前端给了「解除」按钮：`staff.station_id` 置 NULL 后该站长所有 `requireStationId()` 端点全废、被路由去"创建水站"，而 `station`/客户/订单/库存全留着 —— **客户照常下单却没人能接单**，重建水站是**新 id**、旧数据搬不回。**判据一：凡"把某行归属置空"的端点先回答"置空后谁会变孤儿"；判据二：列表 SQL 不过滤 role 时，前端不得按"列表里有什么"决定给不给按钮**（列表看不到 ≠ id 编不出来）。护栏：role 必须是 `DELIVERY` + 不能解除自己；回归 `DeliveryBindingIntegrationTest`（**已反向验证**：摘掉护栏即红）。⚠️ **"转让水站"全仓无端点**，别把 release 当地址用。
30. **「弹窗确认后没接上」= 死循环（2026-09-20 实测）** —— `miniapp-user/pages/order/create.js` 的 `onOfflineConfirmOk()` 只 `setData({showOfflineConfirm:false})`、不继续提交，而 `onSubmit` 闸门是 `selectedMethod === 2 && !showOfflineConfirm` ⇒ 点「确认」只关弹窗、什么都没发生，再点又弹同一个窗，**现金单永远提交不出去**（后端日志什么都看不到 —— 前端没发请求）。**判据：弹窗的「确认」必须让流程继续走；只改 UI 状态的确认按钮 = 静默失败。** 门禁只能查"handler 存不存在"，查不出"handler 里少了一句"。

