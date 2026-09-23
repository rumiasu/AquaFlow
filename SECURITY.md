# 安全策略

## 1. 报告安全问题

如果你发现了安全漏洞，**请不要开公开的 Issue**，改用 GitHub 的
[私密漏洞报告](https://github.com/rumiasu/AquaFlow/security/advisories/new)
或直接联系仓库维护者。

报告时请尽量给出：

- 受影响的端点或组件，以及复现步骤；
- 影响范围（能否越权读取他站数据、能否篡改资金与桶账）；
- 你使用的版本或提交哈希。

本仓库是单人维护的项目，**不承诺修复时限**。已确认的问题会以私密安全公告的形式处理，修复后再公开。

## 2. 支持的版本

只有 `master` 分支的最新提交接受安全修复。本项目尚未发布正式版本，不维护历史版本分支。

## 3. 凭据管理规则

这是本项目最重要的一条安全约定：

- **所有凭据只走环境变量。** 变量清单的权威来源是 `AquaFlow-backend/.env.example`（只列名、不含值）。
- **以下内容永不入库**：`AquaFlow-backend/src/main/resources/application-local.yml`、任何 `.env*` 文件、`backup/*.sql`（含真实数据）。它们全部在 `.gitignore` 内。
- **密钥不得出现在任何文档、Issue、提交信息、日志或对话回复中。** 需要指代时一律写 `<redacted>`。
- **提交前执行一次密钥扫描**：

  ```bash
  bash scripts/scan-secrets.sh
  ```

  CI 也会执行同样的一步（**只报告位置，不回显内容**）。

- 生产环境用 `--spring.profiles.active=prod` 加**纯环境变量**启动，不落地任何配置文件。

## 4. 启动期的强制校验

`RequiredConfigChecker` 在启动时硬校验三项，缺失即抛 `IllegalStateException` 拒绝启动：

| 变量 | 约束 |
|---|---|
| `JWT_SECRET` | 长度必须 ≥ 32 |
| `WX_APP_ID` | 必填 |
| `WX_APP_SECRET` | 必填 |

其余变量不被硬校验，缺失后果由各自组件决定（多数只 `log.warn`）。生产环境另有两项为必填：

- `WX_STAFF_APP_ID` / `WX_STAFF_APP_SECRET` —— `application-prod.yml` 中无默认值，缺失即拒绝启动。
- `DEV_LOGIN_ENABLED` **必须为 `false`**。该开关提供免微信的开发者登录通道，本地默认开启（并同时按 IP 关闭登录限流），**绝不可带入生产**。

## 5. 已实施的安全控制

### 5.1 认证

- JWT 双 Token：access token 30 分钟 + refresh token 7 天，401 由客户端自动续期。
- Token 记录在 `user_token` 表，可服务端失效。
- 密码使用哈希存储，不落明文。

### 5.2 授权

- 统一由 AOP 切面保护：`@RequireRole` 标注角色、`@RequireStation` 标注水站归属，切点为 `execution(public * controller..*.*(..))`，**新增方法自动生效**，不依赖开发者记得加校验。
- **跨站隔离**：服务端从 `AuthContext` 取当前登录态里的 `stationId` 做归属校验，**不信任任何请求参数里的站点标识**。客户 ID 必须由登录态覆盖，或与订单所有者严格比对。
- **跨租户可见面收窄**：下发给其他水站的字段只带「钱货去向」文案与快照金额，不带本站的成本、库存与联系方式；客户画像字段由 `util/CustomerProfileMask` 统一抹除，与本站既无绑定又无本站订单的客户，画像端点一律不可见。

### 5.3 限流

- `RateLimitInterceptor` 对登录类端点按**来源 IP** 限流，超限返回 **HTTP 429**。
- 覆盖 `/api/auth/{login,wx-login,wx-login-staff,dev-login,refresh,change-password}`，默认 20 次/分钟（`RATE_LIMIT_*` 可调）。
- ⚠️ 该计数是**单实例内存态**：多实例部署前必须先换成集中式计数器（如 Redis），否则限流可被分摊绕过。

### 5.4 审计与告警

- `audit_log` 表记录管理侧写操作。
- `alert_log` 表按类型分级投递：**系统故障 → 系统管理员**（`alert_type = 'SYSTEM'`，不落任何水站），**运营故障 → 该站站长**（`alert_type = 'OPERATION'`）。系统告警**没有 HTTP 入口**，避免跨租户越权知情。
- 告警落库走独立事务，失败绝不连累业务。

### 5.5 日志

禁止记录密码、JWT、微信授权码、完整手机号与地址、任何密钥。

## 6. 部署侧检查清单

上线前逐条确认：

- [ ] `DEV_LOGIN_ENABLED=false`
- [ ] `spring.profiles.active=prod`，且配置文件不落地
- [ ] `JWT_SECRET` 为长度 ≥ 32 的随机串，且与开发环境不同
- [ ] `WX_STAFF_APP_ID` / `WX_STAFF_APP_SECRET` 已配置
- [ ] 微信支付模拟渠道已关闭（`MOCK_WECHAT_PAY` 为假）—— ⚠️ 真实微信支付渠道**尚未接入**，生产暂只开放现金与水票两种支付方式
- [ ] `CORS_ALLOWED_ORIGINS` 收敛到实际来源，不使用通配
- [ ] 数据库账号为最小权限账号，不使用 `root`
- [ ] `scripts/scan-secrets.sh` 无输出
- [ ] 备份可恢复（见 `docs/operations/`）

## 7. 已知边界

这些是**有意接受的现状**，不是漏洞，无需报告：

- **微信支付未接入**：没有真实的第三方支付回调面，因此不存在回调伪造风险；本地模拟渠道仅用于开发。
- **微信订阅消息未接入**：客户端正向进度通知全程为 TODO，不涉及用户授权数据处理。
- **多实例未支持**：限流计数与部分定时任务假定单实例部署。
- **`GET /api/notices`（顾客端公告列表）不做水站过滤** —— 任一顾客能看到所有水站的已发布公告。这是已知的功能缺口，会影响信息隔离预期，但公告内容由各站站长自行发布，不含客户数据。
