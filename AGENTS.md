# AGENTS.md — AquaFlow 仓库级 AI 协作指令

> 适用范围：仓库根目录 `D:\backend\project\AquaFlow` 及其全部子目录。DSH/Claude 系 agent 读取本文件作为**操作型契约**（命令、入口、禁改、坑）。
> 证据标注：`【仓】`= 已在本仓库文件中直接核对；`【会】`= 来自历史会话日志或 `.workbuddy` 记忆；无标注的条目见文末 §9「待确认」清单。
>
> **与本仓库其它指令文件的关系（冲突时优先级从高到低）**：本节 §0 的事实基准与 §1 的领域不变量 → 本文件其余部分 → `docs/AGENTS.md`（工程/领域约定，**已知过期**，仅在进入 `docs/` 目录上下文时被加载）→ 根 `README.md`、`docs/**` 其它文档（大面积失真，仅作线索）。
> 注意 `docs/AGENTS.md` 与本文件同名但不同层级：它不是本文件的替代品，措辞冲突时**以本文件 + 实际代码为准**。

## 0. 事实基准（最高优先级）

1. **一切以代码为准**。`README.md`、`docs/**`、`.workbuddy/memory/**` 仅作参考且**已知大面积过期**；与代码冲突时以 `src/**`、`sql/schema.sql`、可运行测试为准，并顺手指出过期文档。【仓】
2. 唯一可信来源顺序：当前 Java/WXML/JS 源码 → `AquaFlow-backend/sql/schema.sql` → 通过的集成测试与实际接口行为 → 仓库 Markdown。此顺序由 `docs/AI_EXECUTION_HANDOFF.md` §0 明文规定。【仓】
3. 动手前先只读排查；破坏性操作（删文件、改 git 历史、清库、执行历史迁移 SQL）**先报告证据与影响并等确认**。用户明确要求：删除类改动必须先证明零引用且属永久废案。【会】

## 1. 项目概览与业务域

- **业务**：桶装水（18.9L 桶装水）配送管理系统，服务对象是**水站**（站长 + 配送员）。非 Demo、非课程设计，目标是可真实上线的企业级系统。【仓 `docs/AGENTS.md`】
- **在维护的端只有两个原生微信小程序**：`miniapp-user`（客户端）、`miniapp-delivery`（站长 + 配送员）。**仓库中不存在可维护的 Vue 管理后台**（`AquaFlow-frontend` 已不存在，仅 `archive/legacy-web-frontend` 留档）。【仓】
- **角色**：`STATION_MANAGER`（站长）、`DELIVERY`（配送员）、客户（微信 openid）。`staff.role` 只有这两个；`FACTORY_ADMIN` 与整个水厂端已在 DB/后端/小程序三处彻底移除。【仓 `sql/README.md`、`docs/README.md`】
- **核心领域不变量（不可凭直觉改写）**：
  - 客户是**全局身份**，`customer` 表**没有 `station_id` 列**；订单/桶/水票/押金一律按 `(customer_id, station_id)` 隔离。【仓 `docs/README.md`；会】
  - 双水站模型：`orders.station_id` = **交易/营收归属**，`orders.delivery_station_id` = **实际履约归属**，两者不可混用。跨站外派单（`station_id != delivery_station_id`）下「钱与票记归属站、库存走履约站」。【仓 `StationUtil`；会】
  - 订单状态以 `OrderStatus.java` 为准：`1 待配送 / 2 配送中 / 3 已送达 / 4 已完成 / 5 已取消`（连续编号，历史 1/3/4/5/6 已废弃）。非法流转由 `isValidTransition` 拒绝。【仓】
  - 支付方式以 `PayMethod.java` 为准：`1 微信 / 2 现金(货到付款) / 3 水票`（水票=下单即视同已付）。**微信支付渠道未接入**，`availableMethods()` 中该选项恒为 disabled。【仓】
  - 支付状态以 `PaymentStatus.java` 为准：`0 未付 / 1 待收款 / 2 已付 / 3 已退款 / 4 已取消`。唯一真值是 `orders.payment_status`。【仓；会】
  - **桶账唯一写入口是 `BarrelLedgerService`**；权益真相源是 `customer_barrel_lot.remain_qty`，`customer_barrel_over` 可为负（= 水站暂存）。恒等式：占用 = 权益 + over。【仓 `BarrelLedgerService.java`；会】

## 2. 目录结构与关键入口

```
AquaFlow/
├─ AquaFlow-backend/          # 唯一后端（Spring Boot + MyBatis）
│  ├─ src/main/java/com/example/aquaflow/
│  │  ├─ AquaFlowApplication.java     # 启动类（@SpringBootApplication @EnableScheduling）
│  │  ├─ controller/                  # 29 个 Controller（认证/订单/配送/桶/水票/押金/站长…）
│  │  ├─ service/ + service/impl/     # 业务；OrderWorkflowServiceImpl=订单状态唯一编排入口
│  │  ├─ mapper/ + resources/mapper/*.xml  # MyBatis（注解 SQL + 4 个 XML）
│  │  ├─ interceptor/AuthInterceptor.java  # JWT 解析 → AuthContext(ThreadLocal)
│  │  ├─ aspect/RequireRoleAspect.java     # @RequireRole 权限切面
│  │  └─ config/RequiredConfigChecker.java # 启动期强制校验密钥，缺失即失败退出
│  ├─ src/main/resources/application.yml   # spring.profiles.default=local
│  ├─ src/main/resources/db/migration/V19..V24__*.sql  # ⚠️ 无 Flyway，不自动执行
│  ├─ src/test/java/.../integration/       # 集成测试（真实上下文 + 真实 MySQL + 真 HTTP）
│  ├─ sql/                    # schema.sql(基线) / init.sql / 迁移脚本 / README.md(权威)
│  └─ gradlew.bat
├─ miniapp-user/              # 客户端小程序（22 页，无分包）
├─ miniapp-delivery/          # 站长+配送员小程序（27 页，无分包）
├─ docs/                      # 设计/审计/路线图；docs/AGENTS.md=工程约定，docs/audit/*=阶段报告
├─ scripts/                   # verify.sh / provision-test-db.sh / scan-secrets.sh（bash）
├─ archive/                   # 不维护：legacy-web-frontend、miniapp-station
└─ backup/                    # 本地 DB 备份（.gitignore 忽略，勿提交）
```

- 后端 API 统一响应：`{ code, message, data }`，`code 0` 成功 / `1` 业务错误 / `404` 路由不存在 / `500` 系统异常；**业务错误 HTTP 状态仍是 200**（唯一例外：未认证返回真 401）。【仓 `common/Result.java`、`docs/audit/test-harness.md`】
- 小程序 API 基址在各自的 `config/api.js`；两端 `prod.baseUrl` 目前都是占位符 `https://your-domain.com`，`project.config.json` 中 `urlCheck: false` 导致开发者工具不会报错——**release 构建指向不存在的域名**。【仓】

## 3. 技术栈与本地运行 / 构建命令

| 项 | 值 |
|---|---|
| 后端 | Java 17（toolchain）、Spring Boot **4.0.6**、MyBatis-Spring-Boot 4.0.1、Jackson 3 |
| 构建 | Gradle Wrapper **9.4.1**（`gradlew.bat`）、Lombok、腾讯云 COS SDK |
| 数据库 | MySQL 8.x（本机 CLI：`D:\backend\MySQL\bin\mysql.exe`），库 `aquaflow` / 测试库 `aquaflow_test` |
| 小程序 | 微信原生（appid `wxc6211615c79da9f9`，libVersion 3.17.0），无框架、无分包 |

```powershell
# —— 后端：编译 / 启动（端口 8080）——
cd D:\backend\project\AquaFlow\AquaFlow-backend
.\gradlew.bat clean compileJava
.\gradlew.bat bootRun
```

- **环境变量**（`.env.example` 有完整清单，缺一即 `RequiredConfigChecker` 启动失败）：`JWT_SECRET`（≥32 位）、`WX_APP_ID`、`WX_APP_SECRET`、`COS_REGION/COS_SECRET_ID/COS_SECRET_KEY/COS_BUCKET_NAME`、`DB_URL`、`DB_USERNAME`、`DB_PASSWORD`、`CORS_ALLOWED_ORIGINS`、`DEV_LOGIN_ENABLED`（生产必须 `false`）、`MYBATIS_LOG_IMPL`。【仓】
- 本地默认 profile 是 `local`，密钥读 `src/main/resources/application-local.yml`（**已 gitignore，含真实密钥，禁止提交、禁止回显**）；生产用 `--spring.profiles.active=prod` + 纯环境变量。【仓】
- 小程序：用微信开发者工具分别打开 `miniapp-user` / `miniapp-delivery` 目录（无 npm 构建步骤）。
- PowerShell 里**用 `;` 分隔多条命令，不要用 `&&`**。长任务（Gradle 构建、测试）放后台任务。【仓 `docs/AGENTS.md`；会】

## 4. 数据库与迁移流程

- **Flyway 未启用**（`build.gradle` 无 flyway/liquibase 依赖，`application.yml` 无相关配置）。`sql/**` 与 `resources/db/migration/V*__*.sql` **全部靠手工执行**，没有版本表、没有自动校验。【仓】
- **新建库的权威基线是 `sql/schema.sql`**（37 张业务表 + 1 视图，2026-09-11 从实际库导出，全部 `CREATE TABLE IF NOT EXISTS`，可重复执行）。`init.sql` 只建结构、不含种子数据，且 `SOURCE schema.sql` 依赖相对路径，**必须在 `sql/` 目录下执行**。【仓】

```powershell
# —— 全新库初始化（只建结构，空库）——
cd D:\backend\project\AquaFlow\AquaFlow-backend\sql
& 'D:\backend\MySQL\bin\mysql.exe' -u root -p < init.sql
```

- **已有老库升级**必须按 `sql/README.md`「基线之后必须补跑的迁移」顺序补跑，否则运行期缺表崩溃：`migration_order_transfer.sql` → `migration_inventory_record.sql` → `migration_aq_bucket_right_v1_ddl.sql` + `_backfill.sql` → `migration_aq009_deposit_timing.sql` → `migration_aq056_payment_fk.sql` → `migration_fix_ticket_account_uk.sql` → `migration_v22_drop_station_offline_payment.sql` → `migration_v23_fix_payment_ticket_uk.sql`。执行务必带库名：`mysql -uroot <库名> < 脚本.sql`。【仓】
- **迁移脚本必须幂等**：MySQL 8.4 无 `DROP ... IF EXISTS` 便利，统一用 `information_schema` 预检 + `PREPARE`。**破坏性 DROP 必须先上代码、再执行 SQL**。新建脚本前先 `ls sql/` 看命名是否占用。【会】
- 严禁在生产执行：`reset_data.sql`（TRUNCATE 多表）、`reconcile_order_814.sql`（一次性修复）、`seed_dev_account.sql`、`seed_new_user_83.sql`、`sql/archive/**`（已过期且不可执行）。【仓 `sql/README.md`】

## 5. 测试与验证方式

- 集成测试位于 `AquaFlow-backend/src/test/java/com/example/aquaflow/integration/`，共 14 个测试类；基类 `support/AbstractIntegrationTest` 启动完整 Spring 容器、发真实 HTTP（JDK `HttpClient`）、每用例前 TRUNCATE 并**断言当前库名含 `test`**（防止误清真实库）。测试凭据继承 `application-local.yml`。【仓 `docs/audit/test-harness.md`、`AbstractIntegrationTest.java`】
- 本机**无 Docker**，因此不用 Testcontainers；测试库 `aquaflow_test` 是独立可重建库。【仓 `docs/audit/test-harness.md`】

```powershell
# —— 一键验证（需 bash + MYSQL_PWD + 本机 MySQL 在跑；脚本内部用 ./gradlew）——
$env:MYSQL_PWD='<本地root密码>'
cd D:\backend\project\AquaFlow
bash scripts/verify.sh     # 重建测试库 → 后端集成测试 → 小程序静态扫描 → 敏感信息扫描

# —— 只跑后端集成测试（Windows 官方入口）——
cd D:\backend\project\AquaFlow\AquaFlow-backend
.\gradlew.bat test --no-daemon --project-cache-dir .gradle_alt
```

- **Gradle 锁坑**：若后端 `bootRun`（8080）正在运行，直接 `gradlew` 会因 `fileHashes.lock` 失败。统一加 `--no-daemon --project-cache-dir .gradle_alt`，或先停后端再编译。【仓 `docs/audit/test-harness.md`；会】
- 断言看**响应体 `code`**，不看 HTTP 状态（业务错误仍 200；未认证才是 401）。新增用例：继承 `AbstractIntegrationTest`，用 `createStation/createStaff/createCustomer/createProduct/createOrderFull/createOrderCrossStation` 造数，`customerToken(id)` / `staffToken(id, role, stationId)` 取令牌，`get/post/put/delete` 发请求，`intOf/decimalOf` 直接查库。并发用例参考 `ConcurrencyIntegrationTest.fireTogether`。【仓】
- 缓存测试结果（`build/test-results/test/*.xml`，2026-09-12 23:00）：**63 用例，failures 0 / errors 0 / skipped 0**。【仓】

## 6. 代码约定与风格

- 后端分层：`Controller` 只做认证 + DTO 校验 + 调服务 + 返回 `Result<T>`；**禁止 Controller 直接写 `orders` / `payment_record` / 库存 / 桶资产表**。订单状态与副作用只能经 `OrderWorkflowServiceImpl` 这类编排服务完成。【仓 `docs/AI_EXECUTION_HANDOFF.md` Phase C；会】
- **所有状态改写必须 CAS 并检查受影响行数**（`updateStatusIf` / `updatePaymentStatusIf`）；无 expected-state 的 `updateStatus` / `updatePaymentStatus` 属于待清除的旧路径。【会；仓 commit `0c6ede4`】
- 业务前置不满足一律抛 `BusinessException`（→ `code=1`），**不要用 `RuntimeException`**（会被兜成 500）。**不要在被 `@Transactional` 注解的方法内 catch 业务异常**——会抛 `UnexpectedRollbackException`，把正常业务拒绝伪装成 500，让它冒泡到 `GlobalExceptionHandler`。【会】
- 权限：`@RequireRole({"STATION_MANAGER"})` + `@RequireStation`，由 AOP 切面 `execution(public * controller..*.*(..))` 统一保护，新增方法自动生效。**跨站校验一律以 `AuthContext` 中服务端刷新的 `stationId` 为准，不信任请求参数**；客户 ID 必须由登录态覆盖或与订单所有者严格比对。【仓 `aspect/**`；会】
- MyBatis：注解 SQL 用下划线列名（配 `map-underscore-to-camel-case: true`）；**注解 SQL 无编译期校验，新写必须手工在真实 MySQL 上跑过**。4 个 XML（Address/Customer/Inventory/Order）在 `resources/mapper/`。【仓】
- 金额、客户、订单归属、水票数量一律服务端推导或强校验；客户端传的金额不可信。展示文案（`statusText` / `payMethodText` / `payStateText` 等）由后端下发，**前端禁止自带 1/2/3 映射表**（历史上两端各写一套，导致新客下单 100% 失败）。【仓 `PayMethod.java` 注释、`OrderStatus.textOf`；会】
- 写库顺序：先 `getByClientToken` 判断幂等再动手；Controller 调 service 后再写库必须 `@Transactional`。【会】
- 日志禁止记录密码、JWT、微信授权码、完整手机号/地址、任何密钥。**回复中也不回显密钥**（用 `<redacted>`）。【仓 `docs/AI_EXECUTION_HANDOFF.md` §4；会】
- 术语统一：**配送中**（= 已付款买下桶权益但未送到，旧称「在途」已禁用）、**进行中**（= 待配送 1 + 配送中 2）。表名 `customer_barrel_in_transit` / 类名 `CustomerBarrelInTransit` 仅为兼容历史命名保留，注释与文案一律写「配送中」。`customer_owed_barrel` 已停止写入，欠桶改读 `customer_barrel_over`。【仓 `docs/AGENTS.md`】
- 小程序：`wxml` 内禁止调用 Page 方法 / `Math.` / `Date.`；`wxml` 绑定的事件处理函数必须真实存在，否则点击**静默无反应**；注意 `require` 相对层级；后端 `/api/delivery/orders/{id}/xxx` 用模板串拼接。【会】

## 7. 协作注意：不要动 / 属于生成物

- **不要新增或修改与当前任务无关的用户未提交改动**。接手时工作区已有未提交修改：`PaymentServiceImpl.java`、`AbstractIntegrationTest.java`、`OrderCancelRollbackIntegrationTest.java`、`PaymentFlowIntegrationTest.java`，以及未跟踪的 `CrossStationRefundIntegrationTest.java`、`.gradlehome/`、`.gradle_alt2/`、`docs/redesign/`。【仓 `git status`】
- 未获明确要求**不要 `git commit` / `git push`**；改动留在工作区供 review。当前分支 `master`，30 个提交。【仓；会】
- **不要改 `archive/**`**（`legacy-web-frontend`、`miniapp-station` 均为历史留档，`miniapp-station` 缺 `app.js`、页面残缺，不再维护）。**不要引用 `miniapp-station`**。【仓 `docs/AGENTS.md`；会】
- 生成物 / 勿手改：`AquaFlow-backend/build/`、`out/`、`.gradle*`、`*.log`、`backup/`、`generated-images/`、`docs/design/*.html`、`tabbar-icons-preview.png`、`project.private.config.json`。另注意 `.gradlehome/`、`.gradle_alt2/`、`AquaFlow-backend/.gradle-user`、`AquaFlow-backend/.gradle_alt3` 等是历次为绕开 Gradle 锁而复制的缓存目录，属临时产物。【仓 `.gitignore`】
- **根目录大量一次性脚本**（约 50 个 `*.py` + 20 个 `test_*.bat` + 散落的 `*.sql`）**全部被 `.gitignore` 忽略**（`*.py`、`test_*.bat`、`fixture_*.sql`），属历史调试残留，不可作为项目入口或规范依据。仅 `scripts/` 下三个 `.sh` 是正式脚本。【仓】
- `sql/` 与 `resources/db/migration/` 里**大量脚本是 SUPERSEDED/DUPLICATE**（见 `sql/README.md` 表格），已被 `schema.sql` 吸收，**不要在新环境执行**。【仓】

## 8. 已知坑与历史教训

1. **接口报错但 HTTP 200**：只判断 HTTP 状态码会把业务失败当成功。一律判断 body `code`。【仓】
2. **MySQL REPEATABLE READ 下的并发桶账**：只加行锁不够——第二个事务拿到锁后，普通 `SELECT` 读到的**仍是旧快照**（实测两个请求都读到 `overBefore=0` 双双通过）。**并发写路径必须「加锁 + 当前读 `FOR UPDATE`」两件套**，且 `INSERT ... ON DUPLICATE KEY UPDATE` 必须 upsert（`SELECT FOR UPDATE` 对不存在的行不加锁）。【会 DEF-4】
3. **取消订单的钱货分家**：跨站外派单取消时，钱/票记**归属站**、库存回补记**履约站**；历史上 `refundOrder` 混用过，导致真丢钱。`deposit_record.related_order_id` 必须落库，否则按订单反查押金释放记录查不到。是否需要释放押金要锚定「有没有入账凭据（PREPARE 流水）」，不能只看 `orders.deposit_amount`（那是应收，未付款单也有值）。【会/仓 commit 与新增测试】
4. **水票是唯一「下单即视同已付」的支付方式**，它绕过 `confirmPayment`，因此押金入账必须在 `TicketAccountServiceImpl` 那条路径自己补，否则客户用票付了押金、账户是 0，退桶退不出钱。【会 AQ-009】
5. **`AbstractIntegrationTest.resetDatabase()` 会 TRUNCATE 全表**，靠「断言库名含 test」做最后护栏；改测试数据源前务必先看这条护栏。【仓】
6. **Spring Boot 4.0.6 已移除 `TestRestTemplate`**，测试用 JDK `HttpClient` + `@Value("${local.server.port}")`。【会】
7. **Jackson 2/3 并存**：Web 层是 Jackson 3（对 Jackson3 设置 `WRITE_DATES_AS_TIMESTAMPS` 会启动失败）；手工 `new ObjectMapper()` 拿到的是 Jackson 2。时间统一 ISO-8601 字符串，前端一律 `new Date(str)`，**禁止 `.replace(/-/g,'/')`**。【会；仓 `application.yml` AQ-044/045】
8. **`station.offline_payment_enabled` 已于 2026-09-12 从真实库删除**（列 + 接口 + 实体字段 + DROP 脚本均已清），货到付款唯一控制点改为 `customer_station_config.offline_payment_enabled`（客户级，站长逐个开通）。**但 `sql/schema.sql` 仍残留该列**（已知漂移，新库会多一列，无害）。【会；仓 `docs/audit/test-harness.md` §8】
9. **`DEV_LOGIN_ENABLED` 开发登录后门默认关闭**，生产必须保持 `false`；`miniapp-delivery` 的登录页目前**无条件渲染「开发者登录」按钮**，不检查 `__wxConfig.envVersion`。【仓 `application.yml`；会】
10. **两端 `config/api.js` 的 `prod.baseUrl` 是占位域名**，正式发版前必须替换。【仓】
11. 文档漂移实例（不要照着做）：根 `README.md` 仍写 `AquaFlow-frontend`、`seed_full_data.sql`、订单状态 `1/3/4/5/6`、支付方式 `1微信/2水票/3线下`、`station_payment_config` 表、`ManagerOrderController`（该文件已不存在）；`docs/AGENTS.md` 的「订单流转」小节把接单后的状态写成 `status=3`，与 `OrderStatus.DELIVERING=2` 矛盾，**以 `OrderStatus.java` 为准**。根目录 `deployment.md` / `production-checklist.md` 亦已过期，权威是 `sql/README.md`。【仓；会】

## 9. 待确认 / 未验证清单

以下条目本次未取得确证，**执行前必须自行核实**：

1. `bash` 在本机的可用性：`Get-Command bash` 只解析到 `WindowsApps\bash.exe`（WSL 存根），`scripts/*.sh` 是否真能跑通未实测；`verify.sh` 内部还会调 `python`/`python3` 跑三个根目录扫描脚本，同样未实测。
2. `scripts/verify.sh` 引用的 `audit_wxml_handlers.py`、`static_audit_user.py`、`page_reach_audit.py` 三个文件确实存在于仓库根目录且被 `.gitignore` 忽略；但会话记忆提到的 `api_audit.py` **在仓库根目录不存在**（`Test-Path` 为 False），该脚本是否曾存在、是否等价于其他脚本，待确认。
3. `docs/README.md` 提到已归档文档位于仓库根 `.docs_trash/`，但该目录**当前不存在**（可能已永久删除）。相关恢复说明是否仍有效，待确认。
4. `docs/README.md` 称 `AquaFlow-frontend`、`docs/AGENTS.md` 称「管理后台 (Vue3)」——**该目录当前不存在**，但 `README.md` 仍把它列为在维护的第三端。是否需要明确「三端 → 两端」的收敛结论，待业务方拍板。
5. `docs/AGENTS.md` 称「32 张表」，`sql/README.md` 称「37 张业务表 + 1 视图」，本次实测 `schema.sql` 中 `CREATE TABLE` = **37**。以 37 为准，但**真实库（aquaflow）当前表数未在本次会话复核**。
6. 「水厂端已彻底移除」为 2026-09-11 的复核结论（`sql/README.md` + `docs/README.md`），本次未重新全库检索 `factory` 残留。
7. 缓存测试结果 63 用例全绿来自 `build/test-results/test/*.xml`（2026-09-12 23:00 时间戳），**本次未重新执行测试**；且工作区尚有未提交改动，当前代码是否仍全绿待确认。
8. 已弃用表的实际停写状态：`customer_owed_barrel`（`docs/AGENTS.md` 称已停止写入）与 `customer_barrel_in_transit` 的写入点，本次未逐一复核调用链。
9. `ManagerOrderController` 已被删除（文件不存在，且有 `ManagerOrderControllerRemovedIntegrationTest`），但 `.workbuddy/memory/MEMORY.md` 仍把它列为「仍未做」的高危项。该记忆条目已过期，**不代表当前存在该风险**，但也不排除有其他等效写入口，待确认。
10. `ConcurrencyIntegrationTest`、`BarrelLedgerIntegrationTest` 等具体用例数与覆盖矩阵，本次只读到文档口径（`docs/audit/test-harness.md` 记 28 例，缓存 XML 合计 63 例），两者口径不一致的原因（Phase D/F 新增 DTO 校验测试）属推断，待确认。
11. 后端端口 8080 当前是否有实例在跑、`aquaflow` 真实库是否含真实业务数据，本次未探测。
12. `docs/SECURITY-密钥处置指南.md` 提到 `application.yml` 曾被历史提交且含真实密钥，需要吊销轮换 + 清理 git 历史。**该项是否已完成，本次未验证**；任何情况下都不要在回复、日志或文档中回显密钥。

## 10. 本文件的来源与维护

- 来源：一次只读的仓库全量扫描（`src/**`、`sql/**`、`docs/**`、`scripts/**`、`git status/log`、测试结果缓存）＋ 解码一份历史会话日志（898 条记录、216 次工具调用，原文件 `~/.dsh/sessions/--D-backend-project-AquaFlow--/session-66eb5a35-…`，已迁移进 DSH Desktop）。
- 原则：**能验证才写，不能验证就放进 §9**。任何一条若与本仓库当前代码冲突，以代码为准，并回来改本文件。
- 维护建议：每次做完整合/重构后，顺手核对 §1 的常量清单（`OrderStatus` / `PayMethod` / `PaymentStatus`）、§2 的目录结构与 §3 的技术栈版本；§9 的条目被证实后应上移进正文并删除。
- 已知待决策项（不属本文件内容，但会影响后续协作）：`docs/AGENTS.md` 是否重命名为 `docs/DOMAIN.md` 以免与根 `AGENTS.md` 撞名；`application.yml` 被 `.gitignore` 导致新克隆起不来的问题如何收敛；工作区那批未提交的跨站退款修复是否先提交。
