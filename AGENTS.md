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

### 0.1 本仓库的文档体系

- **仓库内的文档**：`docs/` 下已入库的那部分（`docs/README.md` 索引、`docs/AGENTS.md`、`docs/AI_EXECUTION_HANDOFF.md`、`docs/audit/**` 的 incident/phase 报告与 test-harness、`docs/architecture/**`、`docs/design/00〜09`、`docs/decisions/decision-record.md`、`docs/SECURITY-密钥处置指南.md`）。它们**已知大面积过期**，只作线索、不作规格。**入口是 `docs/README.md`**（2026-09-15 重写为只索引实际存在的文档）。【仓 2026-09-15】
- **矛盾時の優先順位**（以仓库内可见者为限）：**実コード > `AquaFlow-backend/sql/schema.sql` > 通过的集成测试与实际接口行为 > 本文件 > `docs/**` 其它已入库文档**。本文件只是操作契约，不构成规格的「正」。【仓】
- **数値の SSOT**：表定义看 `sql/schema.sql`、迁移执行顺序看 `sql/README.md`、枚举值看 `constant/*.java`、API 実パス看 `controller/**` 注解、测试件数看 `build/test-results/test/*.xml`。本文件与其它文档只引用、不重定义这些数值。【仓】

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
  - **客户端与员工端是「两个小程序」，appid 不同**（2026-09-15 起；本地为两个开发者工具测试号）。`wx.login` 的 code 只能用**签发它的那一端**的 appid+secret 换 openid，用错端微信只回 `40013 invalid appid` 且日志无指向性 —— 所以 `WeChatLoginService.code2Session(WeChatApp, code)` **强制显式传端**（`constant/WeChatApp.java`：`CUSTOMER` / `STAFF`）。配置键：客户端 `wechat.miniapp.appid/secret`、员工端 `wechat.miniapp.staff-appid/staff-secret`。**员工端这对缺失时本地只 `log.warn`（dev-login 兜底），`prod` profile 里是必填（缺即拒启）**。openid 按 appid 隔离：`customer.openid` 与 `staff.openid` 天然互不干扰。【仓 2026-09-15】
  - **桶账唯一写入口是 `BarrelLedgerService`**；权益真相源是 `customer_barrel_lot.remain_qty`，`customer_barrel_over` 可为负（= 水站暂存）。恒等式：占用 = 权益 + over。【仓 `BarrelLedgerService.java`；会】
  - **人工调整的方向由类型常量决定，不靠正负号**（2026-09-13 P1/P2 落地，全部已实现）：
    - `barrel_record.type` 已收敛为常量类 `BarrelRecordType`（`constant/BarrelRecordType.java:37` / `:51`）：`6=人工调整（增加）`、`9=人工调整（减少）`。守恒对账 E5 已把这两项纳入（`service/ReconciliationService.java:258-259`，注释见 `:245-249`）；不纳入则每次补录都会误报。【仓】
    - `DepositType`（`constant/DepositType.java`）新增 `9=人工补录押金（余额增加）`（`:40`）；方向判定收敛为 `isIncrease`（`:69`，= `1/5/9`）/`isDecrease`（`:79`，= `2/3/4/6/7/8`）。**调用方金额一律传正数，方向由类型决定**；扣减类流水以负数落库（`service/impl/DepositRecordServiceImpl.java:56-64`），这是对账等式1（`balance == SUM(deposit_record.amount)`，`ReconciliationService.java:113-118`）的前提。【仓】
    - 三张流水表的调整场景唯一键：`uk_deposit_adjustment`（`sql/schema.sql:255`）、`uk_record_adjustment`（`:95`）、`uk_ticket_adjustment`（`:635`），配套可空列 `adjustment_id`。**注意 `ticket_record` 原有的 `uk_ticket_consume(order_id, product_id, source)`（`:632`）在 `order_id IS NULL` 时零保护**——调整场景 `order_id` 为 NULL，而 MySQL 唯一键中 NULL 互不冲突。【仓】
    - 取消退款的唯一入口 `PaymentService.refundOrder`（`service/impl/PaymentServiceImpl.java:440`）已加 `OrderStatus.isCancellable` 门槛（`:454`，`constant/OrderStatus.java:40`）：**已完成(4)/已取消(5) 订单不得再取消**（此前可直接对已完成订单退款并置为已取消）。它同时是订单取消/拒单的单一编排入口（`service/impl/OrderWorkflowServiceImpl.java:194-204`）。【仓】
    - 站长资产调整单入口：`/api/manager/adjustments`（`controller/ManagerAdjustmentController.java`，6 端点：`GET /`(列表 `:42`)、`GET /{id}`(`:54`)、`POST /preview`(`:60`)、`POST /`(`:67`)、`POST /{id}/execute`(`:76`)、`POST /{id}/reverse`(`:83`)），类级 `@RequireRole("STATION_MANAGER")`（`:35`）。对账结果落 `reconciliation_result` 表，站长经 `/api/manager/reconciliation` 查询/手动触发（`controller/ManagerReconciliationController.java`，`GET /` `:33`、`POST /run` `:39`）。【仓】
    - 迁移已到 **v28**（`sql/migration_v27_station_adjustment.sql` + `migration_v28_payment_active_order_uk.sql`，补跑清单第 12・13 步）。**2026-09-14 已在真实库 `aquaflow` 执行**：v23（此前从未执行，见 §8 第 13 条）、v27、v28，执行前均 `mysqldump` 备份到 `backup/`。v27 为纯新增（2 表 + 3 个可空列 + 3 个唯一键），v28 为 1 个 STORED 生成列 + 1 个唯一键，业务回滚都无需删表。【仓】

## 2. 目录结构与关键入口

```
AquaFlow/
├─ AquaFlow-backend/          # 唯一后端（Spring Boot + MyBatis）
│  ├─ src/main/java/com/example/aquaflow/
│  │  ├─ AquaFlowApplication.java     # 启动类（@SpringBootApplication @EnableScheduling）
│  │  ├─ controller/                  # 31 个 Controller（29 + 资产调整/对账 2；认证/订单/配送/桶/水票/押金/站长…）
│  │  ├─ service/ + service/impl/     # 业务；OrderWorkflowServiceImpl=订单状态唯一编排入口
│  │  ├─ mapper/ + resources/mapper/*.xml  # MyBatis（注解 SQL + 4 个 XML）
│  │  ├─ interceptor/AuthInterceptor.java  # JWT 解析 → AuthContext(ThreadLocal)
│  │  ├─ aspect/RequireRoleAspect.java     # @RequireRole 权限切面
│  │  └─ config/RequiredConfigChecker.java # 启动期强制校验密钥，缺失即失败退出
│  ├─ src/main/resources/application.yml   # spring.profiles.default=local
│  ├─ src/test/java/.../integration/       # 集成测试（真实上下文 + 真实 MySQL + 真 HTTP）
│  ├─ sql/                    # schema.sql(基线) / init.sql / 迁移脚本 / README.md(权威)
│  └─ gradlew.bat
├─ miniapp-user/              # 客户端小程序（22 页，无分包）
├─ miniapp-delivery/          # 站长+配送员小程序（28 页；P3 资产调整 3 页落地后 app.json 声明 31 页，无分包）
├─ .github/workflows/ci.yml   # CI 门禁（**必须在仓库根**；放 AquaFlow-backend/ 下 GitHub 不读）
├─ docs/                      # 已入库文档索引见 docs/README.md；docs/AGENTS.md=工程约定（已知过期）
│                             # 规格以 src/** 与 sql/schema.sql 为准；本目录文档只作线索
├─ scripts/                   # verify.sh / provision-test-db.sh / scan-secrets.sh（bash，本机需 Git Bash，见 §5）
├─ archive/                   # 不维护：legacy-web-frontend、miniapp-station
└─ backup/                    # 本地 DB 备份（.gitignore 忽略，勿提交）
```

- 后端 API 统一响应：`{ code, message, data }`，`code 0` 成功 / `1` 业务错误 / `404` 路由不存在 / `500` 系统异常；**业务错误 HTTP 状态仍是 200**（唯一例外：未认证返回真 401）。【仓 `common/Result.java`、`docs/audit/test-harness.md`】
- 小程序 API 基址在各自的 `config/api.js`；两端 `prod.baseUrl` 目前都是占位符 `https://your-domain.com`，导致 **release 构建指向不存在的域名**。【仓】
- **「不校验合法域名」= 两个位置，缺一不可**：① `project.config.json` 的 `urlCheck`（`miniapp-user` 已 `false`；`miniapp-delivery` 仍是 `true`，本机靠 gitignore 的 `project.private.config.json`（`urlCheck: false`）覆盖 —— **换机器 clone 后必须重新勾一次**）；② 真机上右上角 `…` →「打开调试」。少任何一个，本机 `http://<局域网IP>:8080` 的请求都会报「不在以下 request 合法域名列表中」（合法域名只收已备案 HTTPS 域名，IP 无法配置）。【仓 2026-09-15 实测】

## 3. 技术栈与本地运行 / 构建命令

| 项 | 值 |
|---|---|
| 后端 | Java 17（toolchain）、Spring Boot **4.0.6**、MyBatis-Spring-Boot 4.0.1、Jackson 3 |
| 构建 | Gradle Wrapper **9.4.1**（`gradlew.bat`）、Lombok、腾讯云 COS SDK |
| 数据库 | MySQL 8.x（本机 CLI：`D:\backend\MySQL\bin\mysql.exe`），库 `aquaflow` / 测试库 `aquaflow_test` |
| 小程序 | 微信原生（libVersion 3.17.0），无框架、无分包；**两端 appid 不同**（客户端 / 员工端各一个，正本见 `miniapp-*/project.config.json` 的 `appid`） |

```powershell
# —— 后端：编译 / 启动（端口 8080）——
cd D:\backend\project\AquaFlow\AquaFlow-backend
.\gradlew.bat clean compileJava
.\gradlew.bat bootRun
```

- **环境变量**：`.env.example` 是清单的权威来源（11 项：`JWT_SECRET`、`WX_APP_ID`、`WX_APP_SECRET`、`WX_STAFF_APP_ID`、`WX_STAFF_APP_SECRET`、`COS_*`、`DB_*`）。**启动期硬校验只有 3 项**（`config/RequiredConfigChecker.java`）：`JWT_SECRET`（长度 <32 也拒绝启动，`:54-56`）、`WX_APP_ID`（`:57-59`）、`WX_APP_SECRET`（`:60-62`），缺一跳 `IllegalStateException` 拒绝启动。【仓】
- **`COS_SECRET_ID` / `COS_SECRET_KEY` 未配置只 `log.warn`，不阻塞启动**（`RequiredConfigChecker.java:71-73`，仅对象存储上传不可用）。**`WX_STAFF_APP_ID` / `WX_STAFF_APP_SECRET`（员工端小程序）未配置同样只 `log.warn`**（`:74-77`，只影响员工端真机微信登录，本地可用 dev-login 兜底；但 `application-prod.yml` 里这对**不给默认值**，prod 缺失即拒启）。`DB_URL` / `DB_USERNAME` / `DB_PASSWORD` / `CORS_ALLOWED_ORIGINS` / `DEV_LOGIN_ENABLED`（生产必须 `false`）/ `MYBATIS_LOG_IMPL` **不被 `RequiredConfigChecker` 检查**，缺失后果由各自组件决定（如数据源连接失败）——不要再写成「缺一即启动失败」。【仓】
- 本地默认 profile 是 `local`，密钥读 `src/main/resources/application-local.yml`（**已 gitignore，含真实密钥，禁止提交、禁止回显**）；生产用 `--spring.profiles.active=prod` + 纯环境变量。【仓】
- 小程序：用微信开发者工具分别打开 `miniapp-user` / `miniapp-delivery` 目录（无 npm 构建步骤）。
- PowerShell 里**用 `;` 分隔多条命令，不要用 `&&`**。长任务（Gradle 构建、测试）放后台任务。【仓 `docs/AGENTS.md`；会】

## 4. 数据库与迁移流程

- **Flyway 未启用**（`build.gradle` 无 flyway/liquibase 依赖，`application.yml` 无相关配置）。`sql/**` **全部靠手工执行**，没有版本表、没有自动校验。（`src/main/resources/db/migration/` 目录**不存在**——曾存在于 `.workbuddy/recovery-backup/`，勿再按该路径找脚本。）【仓】
- **新建库的权威基线是 `sql/schema.sql`**（2026-09-11 从实际库导出，全部 `CREATE TABLE IF NOT EXISTS`，可重复执行；当前为 **37 张业务表、0 视图**，与真实库对象数已完全一致，见 §8 第 12 条）。`init.sql` 只建结构、不含种子数据，且 `SOURCE schema.sql` 依赖相对路径，**必须在 `sql/` 目录下执行**。【仓 2026-09-15】
- **`schema.sql` 导入必须走字节级重定向**：`cmd /c "mysql -uroot --default-character-set=utf8mb4 库名 < schema.sql"`（CI 上是 bash 的 `<`，天然正确）。**不要用 PowerShell 管道**（`Get-Content -Raw | mysql`）——PowerShell 会按控制台代码页重编码，中文注释全变乱码（本项目历史上出现过同类编码事故，专门做过 `migration_v24_fix_garbled_column_comments`）。核对是否写坏要**比字节**（`HEX(TABLE_COMMENT)`）而不是看控制台（控制台是 GBK 渲染，看着像乱码不代表数据坏）。【仓 2026-09-14 实测】

```powershell
# —— 全新库初始化（只建结构，空库）——
cd D:\backend\project\AquaFlow\AquaFlow-backend\sql
& 'D:\backend\MySQL\bin\mysql.exe' -u root -p < init.sql
```

- **已有老库升级**必须按 `sql/README.md`「基线之后必须补跑的迁移」顺序补跑（**以该清单为唯一权威**，当前共 **13 步**、末步为 `migration_v28_payment_active_order_uk.sql`），否则运行期缺表崩溃。清单前段为：`migration_order_transfer.sql` → `migration_inventory_record.sql` → `migration_aq_bucket_right_v1_ddl.sql` + `_backfill.sql` → `migration_aq009_deposit_timing.sql` → `migration_aq056_payment_fk.sql` → `migration_fix_ticket_account_uk.sql` → `migration_v22_drop_station_offline_payment.sql` → `migration_v23_fix_payment_ticket_uk.sql` → `migration_v24..v28`。执行务必带库名：`mysql -uroot <库名> < 脚本.sql`。**真实库 `aquaflow` 已于 2026-09-14 走完本清单**（第 8 步 v23 此前从未执行，是补上的）。**注意清单存在已知缺陷**：漏列 `v3`/`fix_schema_alignment`、且 `v25` 改名与 `v1_backfill` 依赖旧表名导致顺序冲突，执行前需人工核对。【仓 `sql/README.md`】
- **迁移脚本必须幂等**：MySQL 8.4 无 `DROP ... IF EXISTS` 便利，统一用 `information_schema` 预检 + `PREPARE`。**破坏性 DROP 必须先上代码、再执行 SQL**。新建脚本前先 `ls sql/` 看命名是否占用。【会】
- 严禁在生产执行：`reset_data.sql`（TRUNCATE 多表）、`reconcile_order_814.sql`（一次性修复）、`seed_dev_account.sql`、`seed_new_user_83.sql`、`sql/archive/**`（已过期且不可执行）。【仓 `sql/README.md`】

## 5. 测试与验证方式

- 集成测试位于 `AquaFlow-backend/src/test/java/com/example/aquaflow/integration/`，共 **20** 个测试类（连 `AquaFlowApplicationTests` 合计 **21 个测试类 / 96 用例**）；基类 `support/AbstractIntegrationTest` 启动完整 Spring 容器、发真实 HTTP（JDK `HttpClient`）、每用例前 TRUNCATE 并**断言当前库名含 `test`**（防止误清真实库）。测试凭据继承 `application-local.yml`。【仓 `docs/audit/test-harness.md`、`AbstractIntegrationTest.java`；件数 2026-09-15 实测】
- **bash 在本机可用，但要用 Git 自带的 Bash**【2026-09-14 订正】：`D:\backend\Git\bin\bash.exe` **可用**，`bash -n` 与 `bash scripts/scan-secrets.sh` 都实测跑通。此前记的「bash 不可用」只对 WSL 存根成立（`Get-Command bash` 解析到 `WindowsApps\bash.exe`，报 `E_ACCESSDENIED`）。**但在受限沙箱下 Cygwin 起不来**（`couldn't create signal pipe, Win32 error 5`），需放宽权限后才能执行。`python` 可用：`D:\agent\python\python.exe`（根目录三个审计脚本可直接用 python 调）。【仓 2026-09-14 实测】
- **本机无 Docker**，因此不用 Testcontainers；测试库 `aquaflow_test` 是独立可重建库。【仓 `docs/audit/test-harness.md`】

```powershell
# —— 后端集成测试（本机唯一可跑通的入口；必须显式指定 Gradle 缓存目录）——
$env:GRADLE_USER_HOME='D:\backend\project\AquaFlow\.gradlehome'
cd D:\backend\project\AquaFlow\AquaFlow-backend
.\gradlew.bat test --no-daemon --project-cache-dir .gradle_eval
```

- **运行前置（硬要求）**：本机必须显式设置 `GRADLE_USER_HOME='D:\backend\project\AquaFlow\.gradlehome'`，否则 Gradle 默认往沙箱外的用户目录写缓存、被拒后直接失败。`docs/AGENTS.md` 等旧文档给的 `--project-cache-dir .gradle_alt` 命令**不足以**解决该问题（它只改 project cache，不改 Gradle user home）。【仓】
- **Gradle 锁坑**：若后端 `bootRun`（8080）正在运行，直接 `gradlew` 会因 `fileHashes.lock` 失败。统一加 `--no-daemon`（必要时先停后端）再编译。【仓 `docs/audit/test-harness.md`；会】
- 断言看**响应体 `code`**，不看 HTTP 状态（业务错误仍 200；未认证才是 401）。新增用例：继承 `AbstractIntegrationTest`，用 `createStation/createStaff/createCustomer/createProduct/createOrderFull/createOrderCrossStation` 造数，`customerToken(id)` / `staffToken(id, role, stationId)` 取令牌，`get/post/put/delete` 发请求，`intOf/decimalOf` 直接查库。并发用例参考 `ConcurrencyIntegrationTest.fireTogether`。【仓】
- **测试结果（已实测，2026-09-15）**：`AquaFlow-backend/build/test-results/test/*.xml` = **21 个测试类 / 96 用例 / failures 0 / errors 0 / skipped 0**（全绿；比 2026-09-14 记的 18/78 多出的部分是工作区里资产调整单、站点异常配置等新增用例）。跑一次约需 3 分钟（完整 Spring 上下文 + 真实 MySQL）。统计口径：把所有 XML 相加，**不要用 `Get-Content -Raw` 再转 `[xml]`** —— 中文 Windows 上它按 ANSI 解码会把测试名里的中文弄坏、解析直接失败（并且会静默少算），改用 `$d = New-Object System.Xml.XmlDocument; $d.Load($path)`（`Load` 按 XML 声明的 UTF-8 读）。【仓 2026-09-15】
- **验证 CI 是否真的会绿，就在本机复现 CI 的两步**：① `DROP DATABASE aquaflow_test; CREATE DATABASE aquaflow_test;` 后用 **字节级重定向**导入 `sql/schema.sql`；② `.\gradlew.bat cleanTest test`（注意必须 `cleanTest`，否则 Gradle 报 `:test UP-TO-DATE` 而**根本没跑**）。**2026-09-15 按此复现：96 用例全绿**（先按新基线重建 `aquaflow_test`，再 `cleanTest test`）。【仓】

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
- **注释契约（2026-09-14 立规，强制）**：本仓库的注释**不是可选项**——它同时是下一个 AI 的**操作依据**。硬性要求：
  1. **改代码必须同步改注释**。注释与代码不符**比没有注释更危险**：没有注释时人会去读代码，有错误注释时人会直接照做。本仓库已有两次真实事故源于悬空/过期注释（见 §8 第 14 条）。**作废的 javadoc 必须删除，不能悬空留着占位。**
  2. **分工：流程 / 规则写文档，代码注释只写「改这里会踩什么坑」。** 本仓库的业务规则、领域模型、状态机、资金口径已经写在 `docs/design/00〜09` 里，**代码注释不要复述它们** —— 复述必然与文档不同步，最后两边都不可信。判断标准：
     - **该写注释**：反直觉的约束（"金额方向由类型决定，调用方一律传正数"）、历史事故（"曾因此丢钱"）、并发与加锁顺序、唯一键 / 幂等陷阱、"本类不是桶账写入口"这类边界声明、"别把它加回来"的护栏。
     - **该写文档**：业务流程、状态流转规则、字段口径、使用说明、界面交互。
     - **都不写**：`getXxx` / `setXxx`、直白的循环与判空。
     - 代码里确需提业务规则时，**用一行指向文档**（如"见 `docs/design/04-订单与状态机.md`"），不要就地展开。
     - **正面样本**：`constant/PayMethod.java`（记录"前端曾把 2/3 写反导致下单必失败"）、`constant/AdjustType.java`（"方向由类型决定"）、`BarrelLedgerService`（加锁顺序防死锁）。
       **反面样本**：把"上线前水站用纸质台账经营、存量客户资产无处安放"整段业务背景抄进 Controller —— 那属于设计文档（`docs/design/10-站长资产调整单.md` **不在仓库内分发**，见 §0.1），注释只需一句"存量迁移的唯一通道，别用假订单/改余额列代替"。
  3. **修完缺陷就地留评论**：在**出问题的源头**（而不是只写在测试里）注明「原来是什么 / 为什么错 / 后果是什么 / 正确做法」，并标日期。下次有人要重构或回退时，这段注释就是护栏。
  4. **新增端点必须写明归属与调用方**：员工端点标 `@RequireRole`；顾客自助端点必须写明身份取自 `AuthContext`（见 `aspect/RequireRoleAspect.java` 的「新增端点强制约定」）。小程序侧要注明「这个接口顾客端能不能调」。
  5. **参数必填性、枚举取值、接口路径、表结构**这四类说明最容易过期，一旦改动必须当场同步。
  6. 新增类 / 公开方法 / 非直觉分支要补 javadoc；**纯 getter/setter、显而易见的循环不补** —— 注释的价值是"降低误用概率"，不是覆盖率。【仓 2026-09-14 立规】

## 7. 协作注意：不要动 / 属于生成物

- **不要新增或修改与当前任务无关的用户未提交改动**。工作区**截至 2026-09-15 有 92 个已修改文件、319 个文件被移出索引（其中 318 个是 AI 工具产物、磁盘保留；另 1 个是旧 `AquaFlow-backend/.github/workflows/ci.yml`，CI 已移到仓库根）、以及一批新增文件（`.github/workflows/ci.yml`、`.gitattributes`、4 个审计脚本、`application.yml` 脱敏入库等）**全部未 commit**（P1 止血 + P2 资产调整单 + 四项收尾）：已修改的后端文件集中在 `constant/DepositType.java`、`entity/{BarrelRecord,DepositRecord,TicketRecord}.java`、`mapper/{BarrelRecord,DepositRecord,TicketRecord,Order,OrderBarrelException}Mapper.java`、`service/{BarrelLedgerService,ReconciliationService,TicketAccountService}.java`、`service/impl/{BarrelAsset,DepositRecord,OrderBarrelException,OrderWorkflow,Payment,TicketAccount}ServiceImpl.java`、`controller/DepositRecordController.java`、`sql/{schema.sql,README.md}`；未跟踪的有 `sql/migration_v27_station_adjustment.sql`、`constant/{AdjustType,BarrelRecordType}.java`、`controller/{ManagerAdjustment,ManagerReconciliation}Controller.java`、`dto/Adjustment{Create,Preview}DTO.java`、`entity/StationAdjustment.java`、`mapper/StationAdjustmentMapper.java`、`service/StationAdjustmentService.java` + `impl/StationAdjustmentServiceImpl.java`、`integration/{StationAdjustment,StationAssetBackfill}IntegrationTest.java` 等。**动手前先 `git status` 核对，review 后再决定提交，不要顺手混入无关改动、也不要替用户提交**。【仓 `git status`，2026-09-13】
- 未获明确要求**不要 `git commit` / `git push`**；改动留在工作区供 review。当前分支 `master`。**提交数不要在此处硬编码**——以 `git rev-list --count master` 为准（本文件写死过的数字都过期过，这属「数値の SSOT」的适用场景：有正本就不要重述）。【仓 2026-09-15】
- **提交粒度与信息**：一次提交只做一件事；message 写「改了什么 + 为什么」，**不写过程叙述**。**不要在提交信息里记录工具、环境或个人账号变动**——读代码的人只关心代码与业务为什么变。判据：这条 message 对三个月后排查问题的人有用吗？没用就别写。
- **提交规范 hook 已入库但默认未启用**（`.githooks/`）：`commit-msg` 强制 `<type>(<scope>): <subject>`；`pre-commit` 在暂存文件数 > 30 时拒绝提交（针对历史上那次 1099 文件的一次性提交）。启用：`git config core.hooksPath .githooks`。**注意**：受限沙箱下 Git 自带 `sh.exe` 起不来（`couldn't create signal pipe, Win32 error 5`），在该环境启用会让每次 commit 失败，故未默认开启。【仓 2026-09-15】
- **不要改 `archive/**`**（`legacy-web-frontend`、`miniapp-station` 均为历史留档，`miniapp-station` 缺 `app.js`、页面残缺，不再维护）。**不要引用 `miniapp-station`**。【仓 `docs/AGENTS.md`；会】
- 生成物 / 勿手改：`AquaFlow-backend/build/`、`out/`、`.gradle*`、`*.log`、`backup/`、`generated-images/`、`docs/design/*.html`、`tabbar-icons-preview.png`、`project.private.config.json`。另注意 `.gradlehome/`、`.gradle_alt2/`、`AquaFlow-backend/.gradle-user`、`AquaFlow-backend/.gradle_alt3` 等是历次为绕开 Gradle 锁而复制的缓存目录，属临时产物。【仓 `.gitignore`】
- **根目录有 6 个 `*.py`**：其中 **4 个已入库**（`audit_wxml_handlers.py`、`page_reach_audit.py`、`static_audit_user.py`、`audit_comments.py` —— 它们是 CI 与 `scripts/verify.sh` 的静态扫描门禁，`.gitignore` 里对 `*.py` 开了 `!` 例外；**不入库则新克隆的 CI 必然失败**，四者都有真实退出码；`audit_wxml_handlers.py` 与 `audit_comments.py` 自带两端遍历，`page_reach_audit.py` / `static_audit_user.py` 需传端名 `python xxx.py miniapp-user|miniapp-delivery`）；另 2 个仍被忽略（`e2e_user_test.py`、`gen_tabbar_icons.py`），属调试残留，不可作为项目入口或规范依据。`test_*.bat` 为 0 个；另散落 `clear_data.sql`、`reset_data.sql`（**严禁在生产执行**，见 §4）。【仓 2026-09-14 实测】
- **`.gitattributes` 已加入**（`* text=auto` + `*.sh/*.py/*.yml/*.sql eol=lf`）：blob 一律存 LF，防止 Windows 检出 CRLF 后 `bash scripts/*.sh` 在 CI（Linux）上因 `\r` 失败。加入前实测索引内已是 100% LF，故无重新规范化噪音。【仓】
- `sql/` 里**大量脚本是 SUPERSEDED/DUPLICATE**（见 `sql/README.md` 表格），已被 `schema.sql` 吸收，**不要在新环境执行**。（`resources/db/migration/` 目录不存在，见 §4。）【仓】

## 8. 已知坑与历史教训

1. **接口报错但 HTTP 200**：只判断 HTTP 状态码会把业务失败当成功。一律判断 body `code`。【仓】
2. **MySQL REPEATABLE READ 下的并发桶账**：只加行锁不够——第二个事务拿到锁后，普通 `SELECT` 读到的**仍是旧快照**（实测两个请求都读到 `overBefore=0` 双双通过）。**并发写路径必须「加锁 + 当前读 `FOR UPDATE`」两件套**，且 `INSERT ... ON DUPLICATE KEY UPDATE` 必须 upsert（`SELECT FOR UPDATE` 对不存在的行不加锁）。【会 DEF-4】
3. **取消订单的钱货分家**：跨站外派单取消时，钱/票记**归属站**、库存回补记**履约站**；历史上 `refundOrder` 混用过，导致真丢钱。`deposit_record.related_order_id` 必须落库，否则按订单反查押金释放记录查不到。是否需要释放押金要锚定「有没有入账凭据（PREPARE 流水）」，不能只看 `orders.deposit_amount`（那是应收，未付款单也有值）。【会/仓 commit 与新增测试】
4. **水票是唯一「下单即视同已付」的支付方式**，它绕过 `confirmPayment`，因此押金入账必须在 `TicketAccountServiceImpl` 那条路径自己补，否则客户用票付了押金、账户是 0，退桶退不出钱。【会 AQ-009】
5. **`AbstractIntegrationTest.resetDatabase()` 会 TRUNCATE 全表**，靠「断言库名含 test」做最后护栏；改测试数据源前务必先看这条护栏。【仓】
6. **Spring Boot 4.0.6 已移除 `TestRestTemplate`**，测试用 JDK `HttpClient` + `@Value("${local.server.port}")`。【会】
7. **Jackson 2/3 并存**：Web 层是 Jackson 3（对 Jackson3 设置 `WRITE_DATES_AS_TIMESTAMPS` 会启动失败）；手工 `new ObjectMapper()` 拿到的是 Jackson 2。时间统一 ISO-8601 字符串，前端一律 `new Date(str)`，**禁止 `.replace(/-/g,'/')`**。【会；仓 `application.yml` AQ-044/045】
8. **`station.offline_payment_enabled` 已于 2026-09-12 从真实库删除**（列 + 接口 + 实体字段 + DROP 脚本均已清），货到付款唯一控制点改为 `customer_station_config.offline_payment_enabled`（客户级，站长逐个开通）。**该漂移已消除**：`sql/schema.sql` 已无此列（文件头 `:21-22` 明写「真实库已 DROP（v22 已执行），本文件此前仍保留 → 已删」），主代码 0 引用（`entity/Station.java:36` 只剩一句说明注释）；仅存于 `migration_v18_offline_payment.sql`（历史脚本）、`migration_v22_drop_station_offline_payment.sql`（DROP 脚本）与 `backup/*.sql`（旧备份）中，属预期。【仓】
9. **`DEV_LOGIN_ENABLED` 开发登录后门默认关闭**，生产必须保持 `false`；`miniapp-delivery` 的登录页目前**无条件渲染「开发者登录」按钮**，不检查 `__wxConfig.envVersion`。【仓 `application.yml`；会】
10. **两端 `config/api.js` 的 `prod.baseUrl` 是占位域名**，正式发版前必须替换。【仓】
11. 文档漂移实例（不要照着做）：根 `README.md` 仍写 `AquaFlow-frontend`、`seed_full_data.sql`、订单状态 `1/3/4/5/6`、支付方式 `1微信/2水票/3线下`、`station_payment_config` 表、`ManagerOrderController`（该文件已不存在）；`docs/AGENTS.md` 的「订单流转」小节把接单后的状态写成 `status=3`，与 `OrderStatus.DELIVERING=2` 矛盾，**以 `OrderStatus.java` 为准**。原本还在的 `docs/deployment.md` / `docs/production-checklist.md` 已于 2026-09-15 删除（含已移除的水厂端步骤，属错误信息）；部署与迁移的权威仍是 `AquaFlow-backend/sql/README.md`。【仓 2026-09-15】
12. **真实库与基线已完全对齐（2026-09-15 实测，取代 2026-09-14 的"53 对象 = 基线 38 表 + 1 视图 + 14 张备份表"）**：`aquaflow` 现有 **37 个对象 = `schema.sql` 的 37 张表**，**0 视图、0 备份表、0 迁移残留**，"基线有真实库没有"与"真实库有基线没有"两个方向均为 0。2026-09-15 的清理（动手前均已 `mysqldump` 到 `backup/`）：① 14 张 `bak_*` 迁移备份表（`bak_bkt_*` 5 张、`bak_station_before_drop_offline`、`bak_v25_customer_owed_barrel` 与 `..._retired`、`customer_barrel_asset_bak_20260910`、`customer_barrel_in_transit_bak_20260910`、`orders_bak_20260910`、`payment_record_bak_20260910`、`customer_deposit_account_bak_aq009`、`deposit_record_bak_aq009`）；② 表 `migration_diff_bucket_right` 与视图 `v_station_exception_stats`（两者均 0 处代码引用，属迁移期/人工查看产物）从真实库与基线一并移除；③ 陈旧库 `aquaflow_rebuild_test`（停在 2026-08-21 的 `batch`/`batch_order`/`claim_pool` 旧结构）。**仍然不要照真实库反向改 `schema.sql`**——正确做法是先判定哪边对，再把两边同时改齐。【仓 2026-09-15 实测】
13. **`schema.sql` 建出的测试库全绿 ≠ 真实库可用**（2026-09-14 血泪，**当晚已核实修复**）：`aquaflow_test` 由 `schema.sql` 建库，结构永远等于基线；而真实库是历史累积的，**索引可能停留在旧形态**。曾实测：真实库带唯一键 `uk_payment_order_status`、且 `uk_ticket_consume` 未纳入 `source` —— 于是 `refundOrder`（原流水置 REFUNDED + 再插一条 REFUNDED 冲正流水）与「水票退款回补流水」在真实库上**必然报 1062 失败**，而 78 个用例全绿、毫无察觉（当时 v23 从未在真实库执行）。
    **【2026-09-14 晚复核实测：该漂移已消除】** 直连真实库查 `information_schema.STATISTICS`：`payment_record` 现为 `idx_payment_order_status (order_id, status)` —— **非唯一**，`uk_payment_order_status` 已不存在（另有 `uk_payment_active_order (active_order_id)`）；`ticket_record` 的 `uk_ticket_consume` 已含 `source`。即 **v23 已在真实库执行完毕，退款 1062 风险不再存在**。
    **教训（长期有效）**：涉及唯一键 / 索引的改动，必须到真实库核对 `information_schema.STATISTICS`，并优先用「事务内造数据 → 观察是否成功 → ROLLBACK」的行为法验证。【仓 2026-09-14 复核】
14. **注释会诱导误用（2026-09-14，三次事故的共同诱因）**：`StationController` 的 `/mine` 上方长期残留一段**悬空 javadoc**「当前登录**客户**选择的服务水站」，而该方法实际是**员工**接口（`@RequireRole({"STATION_MANAGER","DELIVERY"})`）。顾客端据此三次误调同一类接口（模板页 → `stationId` 恒 null、模板存不进；下单页"再来一单" → 跨站校验沦为死分支；更早的取水站电话），每一次都是 **403 被静默吞掉**，现象是"功能莫名失效"而不是报错，极难定位。同类还有 `miniapp-user/config/api.js` 里 `SEARCH` 的误导注释（已改成"不要在此处定义"的反向警示）。**教训：注释是被信任的契约——悬空/过期注释必须删，改代码必须同步改注释**（见 §6「注释契约」）。定位手法：搜「`*/` 后紧接 `/**`」即可揪出悬空 javadoc（已做成门禁脚本 **根目录 `audit_comments.py`**，2026-09-14 起接入 CI 与 `scripts/verify.sh`，退出码 0/1）。【仓 2026-09-14】

## 9. 待确认 / 未验证清单

以下条目本次未取得确证，**执行前必须自行核实**：

1. ~~`scripts/verify.sh` 引用的三个审计脚本被 `.gitignore` 忽略~~ **已解决（2026-09-14）**：已入库 **4 个**（`.gitignore` 里 `!` 开例外）且都有真实退出码 —— `audit_wxml_handlers.py`、`page_reach_audit.py`、`static_audit_user.py`、`audit_comments.py`（新增：悬空 javadoc 检测）。`api_audit.py` 确不存在，不必再找。
2. ~~`docs/README.md` 提到已归档文档位于仓库根 `.docs_trash/`，但该目录**当前不存在**~~ **已解决（2026-09-15）**：`docs/README.md` 已重写为只索引**实际存在**的文档，并移除了 `.docs_trash/`、`roadmap/roadmap.md`、`测试阶段问题清单.md` 等一批**早已悬空**的条目。`.docs_trash/` 确认不存在，不再恢复。
3. ~~`README.md` 仍把它列为在维护的第三端~~ **已解决（2026-09-14）**：根 `README.md` 已改为「两端原生小程序」，并删除 Vue3 / `station_payment_config` 等过期内容。
4. ~~真实库表数与基线的一致性未复核~~ **已完全对齐（2026-09-15）**：真实库 37 个对象 = 基线 37 张表，0 视图、0 备份表，两个方向的差额均为 0，详见 §8 第 12 条。此前 `docs/AGENTS.md` 的「32 张 / 38 张 + 1 视图」、`sql/README.md` 与 `init.sql` 的「36 张 + 1 视图」等旧口径已于同日一并订正；计数以 `schema.sql` 实测为准（`Select-String -Pattern '^CREATE TABLE'` = 37）。
5. 「水厂端已彻底移除」为 2026-09-11 的复核结论（`sql/README.md` + `docs/README.md`），本次未重新全库检索 `factory` 残留。
6. 已弃用表的实际停写状态：`customer_owed_barrel`（`docs/AGENTS.md` 称已停止写入）与 `customer_barrel_in_transit` 的写入点，本次未逐一复核调用链。
7. `ManagerOrderController` 已被删除（文件不存在，且有 `ManagerOrderControllerRemovedIntegrationTest`），但 `.workbuddy/memory/MEMORY.md` 仍把它列为「仍未做」的高危项。该记忆条目已过期，**不代表当前存在该风险**，但也不排除有其他等效写入口，待确认。
8. ~~当前实测 77 例~~ **已更新（2026-09-15）**：实测 **96 用例 / 21 类 / 0 失败**（在按 `schema.sql` 全新重建的库上跑出）；`docs/audit/test-harness.md` 记的 28 例、`docs/audit/2026-09-14-双轴评价.md` 记的 90 例均为更早口径，仅作历史。
9. ~~真实库是否含真实业务数据、是否可直接用于验证迁移，本次未探测~~ **已探测（2026-09-14）**：`aquaflow` 有数据（实测 `payment_record` 17 行 / `barrel_record` 4 / `deposit_record` 7 / `ticket_record` 4），**可直接用于验证迁移**；本次即在其上跑完 v23/v27/v28，全程先 `mysqldump` 备份、后用「事务内造数据→验证→ROLLBACK」确认行为，数据零改动。**任何情况下都不要在回复、日志或文档中回显密钥。**
10. ~~`application.yml` 曾被历史提交且含真实密钥~~ **已结案**：真实密钥已轮换（仅公开的 AppID 与历史相同，不构成凭据泄露）；`application.yml` 已改为 `${ENV:}` 占位（13 处）并**解除 gitignore 入库**，真凭据只留在本机 `application-local.yml`（gitignore，禁止回显）。旧值已从版本历史中清除，不再需要 `git filter-repo`。【仓 2026-09-14】

11. **开发者工具「测试号」是否支持 `wx.login` / `jscode2session`，尚未实测**（2026-09-15）：官方对测试号只承诺「开发测试 + 真机预览」（<https://developers.weixin.qq.com/miniprogram/dev/devtools/sandbox.html>），**没有明文承诺登录能力**。当前本地两端各用一个测试号（正本见 `miniapp-*/project.config.json` 的 `appid`），所以真机「微信一键登录」能否跑通要实测：把两端 appid/secret 填进配置后，真机点微信登录，看后端日志里 `微信code2Session响应[CUSTOMER]` / `[STAFF]` 的返回。**测试号确定不能上传代码 / 发布 / 设为体验版**；若不支持登录，`dev-login` 是唯一可用登录路径（它不经过微信，不受影响）。

## 10. 本文件的来源与维护

- 来源：一次只读的仓库全量扫描（`src/**`、`sql/**`、`docs/**`、`scripts/**`、`git status/log`、测试结果缓存）＋ 解码一份历史会话日志（898 条记录、216 次工具调用，原文件 `~/.dsh/sessions/--D-backend-project-AquaFlow--/session-66eb5a35-…`，已迁移进 DSH Desktop）。
- 原则：**能验证才写，不能验证就放进 §9**。任何一条若与本仓库当前代码冲突，以代码为准，并回来改本文件。
- 维护建议：每次做完整合/重构后，顺手核对 §1 的常量清单（`OrderStatus` / `PayMethod` / `PaymentStatus` / `DepositType` / `BarrelRecordType`）、§2 的目录结构与 §3 的技术栈版本；§9 的条目被证实后应上移进正文并删除。**每次新增迁移或新增枚举类型后，必须同步核对本文件 §1 的常量清单**（枚举值正本在 `constant/*.java`，本文件只引用、不重定义）。
- 已知待决策项（不属本文件内容，但会影响后续协作）：`docs/AGENTS.md` 是否重命名为 `docs/DOMAIN.md` 以免与根 `AGENTS.md` 撞名；**工作区那批未提交改动是否先 review 后按语义拆成数个提交**（见 §7）。本地专用文件的排除规则写在 `.git/info/exclude`（只在本机生效、不入库、不随仓库分发）。
