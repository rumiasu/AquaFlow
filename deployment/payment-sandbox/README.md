# 旧独立 sandbox 构建规格（当前测试更新不使用）

当前用户已选择现有服务普通更新加 `PAYMENT_TEST_MODE=true`，无需本目录的 profile、UUID、marker 或顾客白名单。操作见 [普通更新流程](../limited-trial/README.md)。本目录保留为旧隔离方式兼容资料。

写给负责测试交付的工程师。本目录提供独立镜像入口和待配置模板，不创建云服务、数据库、账号、权限或凭据，不构成部署授权。原 `deployment/limited-trial` 和 prod 包继续关闭模拟。

## 已实现的边界

- 只激活 `payment-sandbox`，不得混入 `test`、`local`、`prod` 或未知 profile。开发登录与默认密码补齐器均不创建；DB、JWT、两端微信登录和 CORS 必需配置缺失即拒绝。TLS 实现和真实微信登录保留。
- 独立桶权益固定开启，入口拒绝三个相关环境变量别名的 false/空值，数据源边界再次拒绝最终配置 false；与现有体验包一致，不把已存在资产转回旧模型。
- 默认 `MOCK_WECHAT_PAY=false`、无授权顾客。开启须同时有显式隔离声明、逐个顾客 ID、经核准部署绑定及数据库标记。小程序版本不是后端授权。
- JDBC 必须是单一明确主机、显式端口和库名，与许可字段逐字匹配。库名不承担安全证明，不强制叫 aquaflow_test；例如用户已确认用途的 aquaflow_trial 仍须另核实例、地址、备份和标记，模板没有许可它。
- 云 sandbox 必须加密连接，JDBC `sslMode` 只接受 REQUIRED、VERIFY_CA、VERIFY_IDENTITY；后两种校验证书的能力依赖核准的 JVM 信任配置。不得为连通关闭 TLS。其余驱动参数只允许代码中的有限清单，连接初始化 SQL、catalog 重写、额外驱动属性、自动 SQL/迁移入口被拒绝。
- 数据源每次借出连接前核对 JDBC URL、catalog、`SELECT DATABASE()`、`@@server_uuid` 和唯一隔离标记。错误连接不会交给业务调用方；标记缺失、错配、多行、不可查询均拒绝。应用不创建或修补标记。
- `/api/payments/capabilities` 只读且只接受顾客本人。订单、购票、独立押金在付款前共用该能力；模拟需本人授权，trial 还须和当前目标的明确 sandbox 绑定一致。release 拒绝模拟，未来真实渠道仍按独立能力处理。
- 付款沿原服务确认、订单→流水锁序、CAS、幂等键及 `SIM-WX:` 标识。没有新增“提交成功状态”的入口；模拟不发生微信扣款/真实退款。

## 待核准的非秘密配置

| 字段 | 必须核对的事实 |
|---|---|
| PAYMENT_SANDBOX_ENV_ID / SERVICE_ID | 获准的独立测试环境及服务，名称含 prod 不代表用途 |
| PAYMENT_SANDBOX_RUNTIME_ENV_ID / RUNTIME_SERVICE_ID | 平台运行身份到上述配置的可靠映射，必须与获准绑定相同 |
| PAYMENT_SANDBOX_BINDING_ID | 本次隔离部署的唯一非秘密编号，与数据库标记、客户端许可一致 |
| PAYMENT_SANDBOX_DB_HOST / DB_PORT / DB_NAME | 经确认的独占测试库精确目标，与 DB_URL 相同；无通配、无默认值 |
| PAYMENT_SANDBOX_DB_SERVER_UUID | 在已获准实例只读取得的 `@@server_uuid`，不是凭名称猜测的资源 ID |
| MOCK_PAYMENT_CUSTOMER_IDS | 该专用库正常微信登录后生成、逐个确认的 customer.id；不能复制 CI 的 1–16 白名单 |
| 两端 AppID、资源 AppID、CORS | 对应实际两端及测试连接；秘密值另走平台安全配置通道 |

代码核对部署声明与数据库证据，不调用云管理 API 为这些声明背书。运行身份映射来源必须在实际部署审批时核实；不能从 HTTP 请求、前端 envVersion 或随手复制标签取得。实例切换/恢复后 UUID 或标记变化将拒绝连接，需重新核准，不能自动接受。

`sandbox.env.example` 的目标、身份和凭据全为空，不能直接启动。实际账号仅限该测试库，标记建议由核准准备账号建立、服务账号只读；权限和凭据变更另行审批，不由本包执行。COS 与告警外投在 sandbox 覆盖为空，不继承默认资源；若需要这些能力，另核准独立测试资源。

数据库结构仍认 `AquaFlow-backend/sql/schema.sql` 与 SQL 清单；`isolation-marker.sql.example` 只是部署准备模板，不加入业务迁移、不自动运行。核对目标、独占性、可恢复备份及写入许可后才可填写标记，禁止直接执行占位版本。

客户端 `miniapp-user/config/api.js` 的 `API_CONFIG.paymentSandbox` 默认全空。获准后填写 environmentId、serviceId、bindingId；trial 云路由须指向同一服务。HTTPS 路由还须明确 apiBaseUrl；不得自动回退正式或开发地址。正式 release 不会因填了这份许可而启用模拟。

## 独立构建上下文

1. 从精确开发提交用 Java 17 / Gradle Wrapper 构建 `bootJar`，运行原发布物配置检查及完整 CI。不要使用旧 JAR 冒充当前提交。
2. 新建仓库外构建目录，只复制本目录 Dockerfile、entrypoint.sh、.dockerignore，以及准确产物重命名的 app.jar。不要放已填写 env、证书私钥、local 配置或数据库备份。
3. 显式选择经审查、按 digest 固定的 Linux Java 17 基础镜像，包含 `/bin/sh`、`install` 与 `java`。构建参数为 `JRE_IMAGE`，无默认镜像。构建使用该独立目录，产物名称明确含 payment-sandbox，不覆盖原 prod 包。
4. 运行用户 10001:10001，目录 0755、文件 0644，可写目录 /tmp、端口 8080。原入口 `exec java`，不接受额外启动参数或任意 JVM/配置注入。
5. `/bin/sh /opt/aquaflow/entrypoint.sh --check-only` 只验证离线配置形状，不联网、不验凭据、不验证数据库标记，不等于服务可用。随后需要获准目标上的完整启动与真实体验版验收。

## 交付验收

合成测试覆盖缺配置、混合 profile、连接重写、错目标/实例/标记、借连接后篡改标记、无密码补齐/开发登录、能力只读与本人授权。客户端覆盖三入口 trial 正向、release 拒绝、缺能力、错绑定、换会话/连接及 20 次连点只写一次。既有真实 MySQL 的 8 并发、原单重试、取消三顺序及账务守恒仍须通过。

实际部署和体验版上传均待单独授权。本目录不证明已连接任何云库、已核实平台身份映射、已上线或用户已经获得新包。D 同步及本地 Java 17 包由恢复后的本地任务接续。
