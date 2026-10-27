# 隔离模拟支付验收记录

本文件写给接手联调工程师，用于配置、执行并判断本次模拟支付验收；不构成云环境启用许可。

## 实现与业务阶段

复用已有 `MOCK_WECHAT_PAY` 和 `POST /api/payments`，没有新增支付成功 HTTP 入口。
模拟订单、购票和独立押金先持久化 PENDING 流水，再经内部 `MockWechatPaymentAdapter`
调用原 `PaymentServiceImpl.confirmPayment`。不由前端提交成功状态，不生成微信签名。
订单金额和归属仍由服务端读取；订单→流水固定锁序、锁后重读、CAS、活跃流水唯一键和购票幂等键仍有效。

`transaction_no` 保存 `SIM-WX:` 加 UUID，付款与模拟退款均可持久辨认；备注不能替代该标识。
模拟原款、结果提示及付款状态明确标注未扣款。员工不能通过代录收款触发模拟付款；原有站别权限继续生效。
模拟退款只接受白名单客户的带标识原款，不能用当前开关把真实或未知来源微信款变成已自动退款。
历史未标识模拟数据不自动补标；保留原记录核实。

| 阶段 | 只应在该阶段执行的业务 |
|---|---|
| 建单 | 沿既有库存预留、桶容量占用流程，未付款不等于已收款 |
| 确认付款 | 一次推进流水/订单付款状态；按原业务入账押金、激活购入容量；购票款一次入账水票并保存批次均价 |
| 水票支付 | 沿原订单扣票和消费幂等处理，不因模拟微信改动 |
| 完成配送 | 既有实物出库及交付处理；付款不得提前出库 |
| 取消/退款 | 原取消编排释放预留、核销/冲正相应资金或资产；不另写一套模拟账 |

## 启用前必须核实（本次没有启用）

1. 先确认数据库是独立测试目标、无其他会话占用、有可恢复备份；名称像测试库不能替代确认。
2. 仅本机 MySQL：`jdbc:mysql://127.0.0.1:<端口>/aquaflow_test` 或
   `aquaflow_test_pay_<1到24位小写字母数字会话名>`。禁止业务库、备份/生产字样及连接重写参数。
   允许的 JDBC 参数只有 `useUnicode`、`characterEncoding`、`serverTimezone`、`allowPublicKeyRetrieval`、`useSSL` 的受限值。
3. 显式激活 `payment-sandbox`（可合并 `local`）；自动化使用 `test`。任何 `prod`、未知或混合环境标签拒启。
   这是代码的防误配置规则，不能据标签推断当前云服务实际用途；本次不改云配置。
4. `MOCK_WECHAT_PAY=true`、`MOCK_PAYMENT_ISOLATED=true`，并为
   `MOCK_PAYMENT_CUSTOMER_IDS` 填逐个确认的测试 `customer.id`，例如 `7,12`。禁止空值、通配符和范围。
   默认模拟关闭、隔离声明关闭、无人获授权。仅旧 local 开关打开会拒启，须迁移到明确隔离目标。
5. 启动还会比对实际 JDBC URL、catalog 和 `SELECT DATABASE()`，不一致即拒绝。
   生产继续保持模拟关闭；不修改现有 JWT/微信凭据、云权限或运行中的数据源。

`application-test.yml` 的 1–16 白名单只对应每例 TRUNCATE 后的合成客户，不进入发布物；
`TestDatabaseTargetGuard` 的精确 TCP/库名确认仍然必需。

## 五分钟操作验收

1. 在上述已确认隔离环境以授权顾客登录，提交一张测试微信订单。支付选项应标明模拟；
   点击一次即由后端确认，结果显示模拟且未扣款，支付记录有 `SIM-WX:` 编号。
2. 快速连续点付款，或列表与详情同时操作同一单。只有一条活跃原款；新刷新后订单已付，押金一次入账，实物库存未出库。
3. 人为断开客户端响应后恢复网络：回到同一订单重试或查询，不重建订单；返回原付款编号。
4. 分别用未授权顾客、另一顾客的订单、员工代录请求测试：拒绝且无付款/资产写入；
   把请求金额改成 0.01：不能改订单实收金额。取消订单后再重放付款不能复活。
5. 用同一购票或独立押金请求编号重试，确认原流水及一次票/容量入账；模拟退款后账单仍标明模拟。
   执行下列自动化覆盖失败、取消、延迟与重复结果；没有面向客户端的任意结果选择接口。

## 自动化执行与证据边界

无数据库单测（后端目录，JDK 17、Gradle 9.4.1；为本会话配置独立 `GRADLE_USER_HOME`、project cache 和 build 目录）：

```powershell
.\gradlew.bat cleanTest test --no-daemon --tests '*MockPayment*Test' --tests '*WechatPayNotificationAdapterTest' --tests '*WechatPaySuccessMatcherTest' --tests '*RequiredConfigCheckerGuardTest'
```

前端（仓库根）：

```powershell
node tests/js/payment-single-flight.test.js
node tests/js/payment-quote-capability.test.js
node tests/js/order-create-flow.test.js
node tests/js/order-result-navigation.test.js
node tests/js/payment-history-cache-boundary.test.js
node tests/js/payment-records-recovery.test.js
```

每套必须同时检查退出码与 `AQUAFLOW_SUITE_OK` 哨兵，不能把 Promise 未结束的退出零当通过。
内部适配器用例覆盖 SUCCESS、CANCEL、FAIL、DELAY、延迟后重试和成功后重复结果；
真实服务方法单测覆盖锁后终态、归属/金额不一致和 CAS 失败的拒绝与副作用次数。
验签/金额匹配组件的本地合成材料测试独立执行；未将模拟结果包装成微信已验签通知。

真实 HTTP/MySQL 并发专项已加入 `IsolatedMockPaymentConcurrencyIntegrationTest`：8 并发付款、
响应丢失原单重试、伪造金额、非本人/非授权/员工、取消与付款竞态。另复用
`MockWechatPayIntegrationTest`、`TicketPurchaseIdempotencyIntegrationTest`、
`UnpaidWechatOrderTimeoutIntegrationTest`、`CombinedOrderBarrelPurchaseIntegrationTest`。
**本次未运行任何数据库集成测试**，因为尚未确认独占且有可恢复备份的目标；不得宣称 MySQL 锁、事务回滚、真实库存全链路已通过。
确认后遵循 CONTRIBUTING 与 `TestDatabaseTargetGuard` 设置精确目标，使用同一隔离构建目录运行这些类。

本地最终定向 Gradle 执行通过 264 项，失败/错误/跳过均为 0；包括上述支付/验签/启动保护、
PaymentRecord 用途、直接受影响的购票/独立押金/退还资格/现金退款恢复及分层架构单测。
所有 Java 测试源码（含新增但未执行的数据库并发类）均已编译。
前端 6 套逐套核对退出 0 和唯一哨兵，通过 150 项：单飞 6、报价能力 29、建单付款流程 46、
结果导航恢复 32、账单缓存边界 12、支付记录恢复 25。两处修改 JS 的 `node --check` 和 `git diff --check` 通过。
结果 XML 位于本次独立构建 `AquaFlow-backend/build_payment/test-results/test/`，
不与原 D 工作树或其他会话共享构建目录。本次未启动线上服务、未付款/退款、未执行迁移或变更数据库。

模拟成功不能证明微信商户、证书、公钥获取、真实签名、统一下单、真实通知、真实扣款或真实退款链路成功。

## 2026-10-27 与界面改动集成复核

在 `ce817a4d9872894e7b8162fed21b5ccb40c6695a` 上三方集成支付与界面交付，保留水票商品选择器、
整数步进器、档位子页返回核对、配送完成晚回执隔离及购票读取恢复。回归覆盖子页改为 95 元后，
父页保存商品说明不会重写旧 88 元票价；名称、排序和仅提交实际修改档位的行为均保留。
同时补齐环境隔离检查脚本的登录会话夹具，并修正下单明细在 320px 窄屏下数量、金额和单价字号。

本次实际通过：无数据库 Java 单测 14 类、314 项（失败、错误、跳过均为 0）；完整 JS 入口
148 套、3865 个计数项（逐套核对退出码及结束哨兵）；官方 `wcc`/`wcsc` 编译 6 份 WXML、
6 份 WXSS。小程序文本/BOM、191 份 JS 解析、两端模块闭包及差异空白检查通过。
独立构建输出、Gradle 缓存及测试工具适配均在仓库外，不覆盖原工作树构建目录。

首轮 Java 通配筛选误包含数据库并发类，该类在 `TestDatabaseTargetGuard` 的连接前目标检查被拒；
随后改用完整类名重跑上述无数据库用例。真实 HTTP/MySQL 并发专项仍未执行：本次没有核实到
独占测试库及可恢复备份证据。模拟支付保持默认关闭，未启用云配置、部署后端或上传小程序；
真实小程序体验由用户验收。
