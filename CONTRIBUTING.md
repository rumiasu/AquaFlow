# 贡献指南

本文档面向要在本仓库提交改动的人。**动手改代码前，先读根目录的 [`AGENTS.md`](./AGENTS.md)** —— 它是本仓库的工程契约（领域不变量、已知坑、禁改项），比本文更详细，且是 AI 协作与人工开发共用的同一份判据。

---

## 1. 环境要求

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | 17 | `build.gradle` 用 toolchain 固定，不依赖本机默认 JDK |
| MySQL | 8.4 | 需要两个库：`aquaflow`（开发）、`aquaflow_test`（测试） |
| Python | 3.8+ | 仅用于静态门禁脚本，不参与构建 |
| 微信开发者工具 | 最新稳定版 | 打开小程序目录即可，无 npm 构建步骤 |

不需要 Docker：集成测试直接连本机 MySQL，不使用 Testcontainers。

---

## 2. 初始化

### 2.1 建库建表

`AquaFlow-backend/sql/schema.sql` 是**唯一权威基线**（项目未启用 Flyway，全部 `CREATE TABLE IF NOT EXISTS`，可重复执行）。

```bash
cd AquaFlow-backend/sql
mysql -uroot -p aquaflow < schema.sql
mysql -uroot -p aquaflow_test < schema.sql
```

> **必须用 shell 重定向（`<`）导入，不要走 PowerShell 管道。** `Get-Content -Raw | mysql` 会按控制台代码页重编码，把中文注释写成乱码。核对是否写坏要比字节（`HEX(TABLE_COMMENT)`），不要看控制台输出。

### 2.2 配置环境变量

[`AquaFlow-backend/.env.example`](./AquaFlow-backend/.env.example) 是变量清单的权威来源。**启动期硬校验只有 3 项**：

| 变量 | 约束 |
|---|---|
| `JWT_SECRET` | 长度必须 ≥ 32，否则拒绝启动 |
| `WX_APP_ID` | 微信小程序 appid |
| `WX_APP_SECRET` | 对应 secret |

其余变量缺失时由各自组件决定后果（多数只 `log.warn`），不会导致启动失败。生产环境额外要求见 [`SECURITY.md`](./SECURITY.md)。

本地开发可把密钥写进 `AquaFlow-backend/src/main/resources/application-local.yml` —— 该文件**已 gitignore，禁止提交、禁止在任何回复或日志中回显**。

### 2.3 启动

```bash
cd AquaFlow-backend
./gradlew bootRun          # 端口 8080
```

> 若默认 Gradle 用户目录不可写，先设置 `GRADLE_USER_HOME` 指向仓库内目录。后端 `bootRun` 在跑时再执行 `gradlew` 会因文件锁失败，统一加 `--no-daemon` 解决。

### 2.4 打开小程序

用微信开发者工具分别打开 `miniapp-user/` 与 `miniapp-delivery/`。请求本机后端需要两处开关同时打开，缺一个都会报「不在以下 request 合法域名列表中」：① `project.config.json` 的 `urlCheck` 为 `false`；② 真机上右上角 `…` →「打开调试」。

---

## 3. 测试

集成测试位于 `AquaFlow-backend/src/test/java/com/example/aquaflow/integration/`，基类 `support/AbstractIntegrationTest` 会启动完整 Spring 容器、发**真实 HTTP**（JDK `HttpClient`），每个用例前 TRUNCATE 数据表并**断言当前连接的库名含 `test`**（防止误清开发库）。

```bash
cd AquaFlow-backend
./gradlew cleanTest test --no-daemon
```

- **必须带 `cleanTest`**，否则 Gradle 报 `:test UP-TO-DATE` 而实际根本没跑。
- **件数以 `build/test-results/test/*.xml` 为准**，不要数源码里的 `@Test`。统计时用真正的 XML 解析器（如 `System.Xml.XmlDocument`），不要 `Get-Content -Raw` 再转 `[xml]` —— 按 ANSI 解码会弄坏测试名并**静默少算**。
- **断言看响应体里的 `code`，不要看 HTTP 状态码**：业务错误仍返回 HTTP 200，只有未认证才是真 401。

### 新增用例

继承 `AbstractIntegrationTest`，用基类夹具造数（`createStation` / `createStaff` / `createCustomer` / `createProduct` / `createOrderFull` / `createOrderCrossStation`），用 `customerToken(id)` / `staffToken(id, role, stationId)` 取令牌，用 `get/post/put/delete` 发请求，用 `intOf/decimalOf` 直接查库断言。并发场景参考 `ConcurrencyIntegrationTest.fireTogether`。

---

## 4. 静态门禁

仓库根目录的 Python 脚本是 CI 的一部分，**新克隆的仓库若缺少它们，CI 必然失败**：

| 脚本 | 检查内容 |
|---|---|
| `audit_wxml_handlers.py` | `wxml` 绑定的事件处理函数是否真实存在（缺失 = 点击静默无反应），自动扫两端 |
| `page_reach_audit.py <端名>` | `app.json` 注册的页面是否缺 `.js`、是否跳转到未注册页；两端各跑一次 |
| `static_audit_user.py <端名>` | `require` 相对层级错误；`wxml` 里调用全局对象或 Page 方法 |
| `audit_comments.py` | 悬空 javadoc —— 无归属的注释会误导读代码的人 |
| `audit_js_syntax.py` | JS 解析期错误（重复 `const` 声明、少括号）会让整个模块加载失败 |
| `audit_scenario_matrix.py` | 场景测试矩阵声称的测试类/方法必须真实存在 |

一条命令跑完全部门禁（含敏感信息扫描）：

```bash
bash scripts/verify.sh
```

---

## 5. 提交规范

### 5.1 信息格式

```
<type>(<scope>): <subject>
```

`type` 取值：`feat` / `fix` / `refactor` / `docs` / `test` / `chore` / `perf`。

标题写**改了什么**，正文写**为什么**。判据：这条信息对三个月后来排查问题的人有用吗？**不写过程叙述，不记录工具、环境或个人账号变动。**

### 5.2 粒度

**一次提交只做一件事。** 不要顺手混入无关改动 —— 用 `git status` 核对后再提交，工作区常有并行改动。

仓库自带提交规范 hook（`.githooks/`，默认未启用）：`commit-msg` 强制上述格式，`pre-commit` 在暂存文件数 > 30 时拒绝提交。启用方式：

```bash
git config core.hooksPath .githooks
```

---

## 6. 代码约定

### 6.1 分层

```
Controller  →  认证 + DTO 校验 + 调服务 + 返回 Result<T>
Service     →  业务逻辑与事务编排
Mapper      →  数据访问（注解 SQL + XML）
```

**Controller 只做这四件事**：禁止直接写 `orders` / `payment_record` / 库存 / 桶资产表；订单状态与副作用只能经由 `OrderWorkflowServiceImpl` 这类编排服务完成。

### 6.2 必须遵守的硬约束

- **所有状态改写必须 CAS 并检查受影响行数。** 无 expected-state 的 `updateStatus` 属待清除的旧路径。
  ⚠️ 本仓库 CAS 方法有**两派参数顺序**，靠名字区分：`updateStatusIf(id, 期望, 新)`（`OrderMapper` / `OrderBarrelExceptionMapper`）与 `updateStatusTo(id, 新, 期望)`（`PaymentRecordMapper` / `StaffPayrollMapper`）。**传反了恒命中 0 行，不报错、静默什么都没改。**
- **业务前置不满足一律抛 `BusinessException`**（→ `code=1`），不要用 `RuntimeException`（会被兜成 500）。**不要在被 `@Transactional` 注解的方法内 catch 业务异常**（会抛 `UnexpectedRollbackException`，把正常业务拒绝伪装成 500）。
- **权限用注解**：`@RequireRole({"STATION_MANAGER"})` + `@RequireStation`，由 AOP 切面统一保护，新增方法自动生效。**跨站校验一律以 `AuthContext` 中服务端刷新的 `stationId` 为准，不信任请求参数。**
- **展示文案由后端下发**（`statusText` / `payMethodText` / `payStateText`），前端禁止自带 `1/2/3` 映射表 —— 两端各写一套曾导致新客下单 100% 失败。
- **请求体的枚举入参必须白名单校验**。兜底文案不许把未知值说成某个已知值。
- **金额、客户与订单归属、水票数量一律服务端推导或强校验。**

### 6.3 注释约定

本仓库的注释是工程资产，不是可选项。

- **改代码必须同步改注释。** 注释与代码不符**比没有注释更危险**：没有注释人会去读代码，注释错了人会直接照做。**作废的 javadoc 必须删除，不能悬空留着占位。**
- **分工**：流程 / 规则 / 领域模型 / 资金口径写在 `docs/`；**代码注释只写「改这里会踩什么坑」** —— 反直觉约束、历史事故、"别加回来"的护栏、并发与加锁顺序、唯一键与幂等陷阱、边界声明。
- **修完缺陷就地留评论**：在出问题的源头注明「原来是什么 / 为什么错 / 后果是什么 / 正确做法」并标日期。
- 纯 getter/setter、显而易见的循环**不补**注释。注释的价值是降低误用概率，不是覆盖率。

### 6.4 日志

**禁止记录**密码、JWT、微信授权码、完整手机号与地址、任何密钥。回复与文档中同样不回显密钥，一律写 `<redacted>`。

---

## 7. 数据库迁移

**项目未启用 Flyway**，`sql/**` 全部靠手工执行，没有版本表、没有自动校验。

- **新建迁移前先 `ls sql/` 看编号，不要照任何清单的最后一个数字 + 1。**
- **迁移脚本必须幂等。** MySQL 8.4 没有 `DROP ... IF EXISTS`，统一用 `information_schema` 预检 + `PREPARE`。
- **破坏性 DROP 必须先上代码、再执行 SQL**，执行前 `mysqldump`。
- ⚠️ **MySQL 在解析期校验列名**：对某列的 `MODIFY` / `WHERE col` 即使不执行到也会报 1054 —— 删列时必须把**历史迁移里对该列的引用一并清掉**。
- 执行务必带库名：`mysql -uroot <库名> < 脚本.sql`。
- 迁移清单与执行证据的正本是 [`AquaFlow-backend/sql/README.md`](./AquaFlow-backend/sql/README.md)，**不要在其他文档里复述清单**。

**严禁在生产执行**：`reset_data.sql`、`clear_data.sql`、`reconcile_order_814.sql`、`seed_dev_account.sql`、`seed_new_user_83.sql`、`sql/archive/**`。

---

## 8. 小程序约定

- **`wxml` 内禁止调用 Page 方法、`Math.`、`Date.`**；绑定的事件处理函数必须真实存在，否则点击**静默无反应**。
- 注意 `require` 的相对层级。改完 JS 必须过 `audit_js_syntax.py`。
- **小程序文件不要写出 BOM**。
- 后端 `/api/delivery/orders/{id}/xxx` 这类路径用模板串拼接。

---

## 9. 不要做的事

- **不要改 `archive/**`** —— `legacy-web-frontend` 与 `miniapp-station` 均为历史留档，不维护、不引用。
- **不要提交生成物**：`build/`、`out/`、`.gradle*`、`*.log`、`backup/`、`generated-images/`、`*.html` 预览文件、`project.private.config.json`。
- **不要提交密钥**：`application-local.yml`、`.env*` 一律在 gitignore 内；提交前跑一次 `scripts/scan-secrets.sh`。
- **不要给 `customer` 表加 `station_id`** —— 客户是全局身份，资产按 `(customer_id, station_id)` 隔离，这是本项目的核心架构决策之一（理由见 `docs/architecture/`）。

---

## 10. 许可证

提交即表示同意以本仓库的 [MIT License](./LICENSE) 授权你的贡献。
