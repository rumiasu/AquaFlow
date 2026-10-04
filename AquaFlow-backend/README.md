# AquaFlow 后端

为客户小程序和站长/配送员小程序提供统一 API。使用 Java 17、Spring Boot、MyBatis 和 MySQL；具体依赖版本由 `build.gradle`、依赖锁及校验元数据定义。

## 业务职责

后端负责订单及履约状态、实际收退款、桶权益与实物、水票批次、库存预留、站间协议及应收、人员收益和治理记录。押金不能作为水费收入，付款不能替代交付，账面冲销不能替代实际回款。

核心规则入口为 `BarrelLedgerService`、`TicketLotService`、订单编排服务和库存预留服务。API 注解是路径事实来源，[API 参考](../docs/api/01-REST-API参考.md)登记对接契约。不要根据旧类数、目录图或报告判断当前结构。

## 运行

1. 准备 JDK 17 和 MySQL 8.4。
2. 新建开发、测试数据库；新库导入 `sql/schema.sql`，旧库按 `sql/README.md` 升级。
3. 按 `.env.example` 设置环境变量；本机可使用被忽略的 `src/main/resources/application-local.yml`。环境文件不会由 Gradle 自动加载。
4. Windows 运行 `./gradlew.bat bootRun`，其他系统运行 `./gradlew bootRun`。

独立权益开启时须有完整凭据结构，购买幂等和随单新增押金还需当前版本要求的增量；具体安装要求以 `sql/README.md`、启动结构保护器及相应服务为准，不只核对最初 v71 表组。该开关用于初次部署切换，产生新业务之后不能关闭开关继续按旧模型营业。

## 验证与发布

`./gradlew.bat test --rerun-tasks --no-daemon` 执行后端验证；只连接目标保护器允许的 `aquaflow_test` 或会话后缀，须同时明确确认可清空的库名和完整 IPv4/端口/库目标；`*_test`、业务/备份标记及仅凭名称相似均不放行。每用例在同一实际连接核对目标后清表，配置要求见根 CONTRIBUTING §4。结果读取 `build/test-results/test/TEST-*.xml`；构建输出被改目录时读取对应目录，不能把 UP-TO-DATE 当作重跑。

`./gradlew.bat bootJar --no-daemon` 生成发布包，之后按根贡献指南执行发布检查。真实凭据不能入版本库或 JAR。真实微信支付/退款目前不可用，生产必须关闭模拟支付和开发登录。

完整流程见[贡献指南](../CONTRIBUTING.md)、[领域模型](../docs/architecture/02-领域模型.md)、[迁移清单](sql/README.md)和[部署说明](../docs/operations/01-部署与运维.md)。
