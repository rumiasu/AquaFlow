# 后端集成测试基架 / Integration Test Harness

> 对应《AquaFlow 改造执行任务书》Phase A.4 与 Phase B。
> 本文件描述「怎么跑、为什么这么设计、怎么加用例」。

## 1. 方案选型（为什么要自建，而不用 Testcontainers）

| 选项 | 结论 | 原因 |
|---|---|---|
| Testcontainers MySQL | ✗ 不可用 | 本机**无 Docker**（`docker: command not found`），无法起容器 |
| H2 内存库 | ✗ 拒绝 | 本项目依赖 MySQL 专有语法/行为（`ON DUPLICATE KEY`、`information_schema`、utf8mb4、JSON 列等），H2 会掩盖真实 SQL 问题，违反任务书「不允许用 Mock 掩盖事务/SQL 问题」 |
| **独立可重建测试库**（选用） | ✓ | 任务书认可的降级方案：真实 MySQL + 独立库 + 可一键重建 |

**本方案**：真实 Spring 上下文 + 真实 MySQL（`aquaflow_test`）+ 真实 HTTP。

## 2. 组成

| 文件 | 作用 |
|---|---|
| `AquaFlow-backend/src/test/resources/application-test.yml` | test profile：把数据源指向 `aquaflow_test`；**不含任何密钥**，凭据继承自 `local` profile（`application-local.yml`，已 gitignore） |
| `AquaFlow-backend/src/test/java/com/example/aquaflow/support/AbstractIntegrationTest.java` | 基类：启动完整 Web 容器、造真实 JWT、用 JDK `HttpClient` 发真请求、每用例前清库、造数辅助 |
| `AquaFlow-backend/src/test/java/com/example/aquaflow/integration/*.java` | 具体回归用例 |
| `scripts/provision-test-db.sh` | 重建 `aquaflow_test`（DROP→CREATE→载入 `sql/schema.sql`） |
| `scripts/verify.sh` | 一键：重建库→跑测试→静态扫描→敏感信息扫描 |

## 3. 安全护栏（重要）

1. **只连测试库**：`application-test.yml` 把 URL 指向 `aquaflow_test`。
2. **防误清真实库**：`AbstractIntegrationTest.resetDatabase()` 每用例前先执行 `SELECT DATABASE()`，
   **断言库名包含 `test`**，否则直接失败。即使有人把 URL 改回 `aquaflow`，测试也会炸在护栏上而不会 TRUNCATE 真实数据。
3. **不提交密钥**：test profile 只覆盖 URL；`MYSQL_PWD` 通过环境变量注入，从不写入任何文件。
4. **真实鉴权**：测试不发「假身份头」，而是复用生产 `JwtUtil` 造真 JWT，请求真正穿过 `AuthInterceptor` + `RequireRoleAspect`。

## 4. 运行方式

```bash
# 前置：本机 MySQL 在跑；设置本地 root 密码（勿提交）
export MYSQL_PWD='***'

# 方式 A：一键（推荐）
bash scripts/verify.sh

# 方式 B：只跑后端集成测试
bash scripts/provision-test-db.sh
cd AquaFlow-backend
./gradlew test --no-daemon --project-cache-dir .gradle_alt
```

> ⚠️ **gradle 锁坑**：若本地 `bootRun`（后端 8080）正在运行，直接 `./gradlew` 会因 `fileHashes.lock`
> 失败。统一使用 `--project-cache-dir .gradle_alt` 绕开；或先停后端再编译。

## 5. 响应断言约定

- 业务错误 **HTTP 状态仍为 200**，真实错误在 body 的 `code`：`0` 成功 / `1` 业务错误 / `404` 路由不存在 / `500` 系统异常。
  → 断言一律看 `res.code()`，**不要**看 HTTP 状态（唯一例外：未认证是真实 `401`）。
- `Api` record 已封装：`code()` / `message()` / `data()` / `isSuccess()`。

## 6. 新增用例

1. 在 `com.example.aquaflow.integration` 下新建 `XxxIntegrationTest extends AbstractIntegrationTest`。
2. 造数辅助（每个用例开始库被清空，造数完全自包含、可复现）：
   - 基础：`createStation / createStaff / createCustomer / createProduct / createAddress / createOrder`
   - 资金：`createInventoryFull`（可控水票开关/站级水票价）、`createTicketAccount`、
     `createCustomerStationConfig`、`createPaymentRecord`、`createDepositBalance`
   - 订单/桶：`createOrderFull`（可控支付方式/金额/首单标记）、`createOrderItem`、
     `createBarrelLot`、`createBarrelAsset`、`createBarrelInTransit`、`createBarrelOver`
3. 用 `customerToken(id)` / `staffToken(id, role, stationId)` 取令牌，`get/post/put/delete(path, token, json)` 发请求。
4. 断言 `res.code()` 与数据库状态；直接查库用 `intOf / decimalOf / longOf`。
5. 并发用例参考 `ConcurrencyIntegrationTest.fireTogether`（`CountDownLatch` 栅栏 + `ExecutorService`，把请求逼到同一瞬间）。

## 7. 测试矩阵与结果

| 类 | 覆盖 |
|---|---|
| `AuthzIsolationIntegrationTest` | 6 | 无令牌 401 / 客户越权 / 员工跨站读写 |
| `OrderStateMachineIntegrationTest` | 3 | 状态机逐边 + 非法跳转 + 终态 |
| `OrderCreationIntegrationTest` | 2 | 服务端金额重算 / 幂等键 |
| `PaymentFlowIntegrationTest` | 3 | 现金「创建不置已付 + 确认一次」/ 水票原子扣减与幂等 |
| `OrderCancelRollbackIntegrationTest` | 3 | 取消回滚 / 已送达不可取消 / 水票退回 |
| `BarrelLedgerIntegrationTest` | 7 | 首购 / 换桶 / 纯还桶 / over<0 / 退桶 FIFO / 跨商品跨站隔离 |
| `ConcurrencyIntegrationTest` | 3 | 并发接单 / 收款 / 还桶，只能一个成功 |

**当前结果（2026-09-12 恢复后复测）**：**28 用例（27 回归 + 1 ContextLoad）全绿，失败 0、错误 0**。

> 历史说明：Phase B 首次跑出的是「27 用例，23 绿 / 4 红」，4 条红灯是 4 个**真实缺陷**
> （详见 `phase-b-report.md` §4）。DEF-1~4 修复后已全绿；
> `src/test/**` 曾随删除事故一并丢失，后又按 `javap` 常量池 + JUnit XML 逐条还原，
> 还原过程与验收见 `incident-src-restore.md`。
>
> 稳定性：两次连续运行（第二次带 `--rerun-tasks` 全量重编重跑）均为 28/28。

## 8. 已知边界

- `sql/schema.sql` 仍含 `station.offline_payment_enabled` 列（v22 已从真实库删除、代码已无引用）。
  新库会多出这一列，**对本轮测试无害**；但这是 schema 与代码的漂移，已记入 `write-path-inventory.md` 待办。
- 集成测试只在 `*test` 库运行；生产/验收环境请不要执行 `scripts/provision-test-db.sh`。
- 部分造数走直连 SQL（绕过 HTTP），用于构造前置状态（如已扣库存、已有批次）；业务动作本身仍一律走真实 HTTP。
