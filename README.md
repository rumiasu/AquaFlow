# AquaFlow — 桶装水配送管理系统

[![CI](https://github.com/rumiasu/AquaFlow/actions/workflows/ci.yml/badge.svg)](https://github.com/rumiasu/AquaFlow/actions/workflows/ci.yml)

面向**水站**的桶装水（18.9L）配送管理系统：两端原生微信小程序（顾客端 / 站长 + 配送员端）+ 统一后端 API。

```
miniapp-user        顾客        下单、买桶、用水票、退空桶
miniapp-delivery    站长 + 配送员   接单派单、送达回桶、对账、员工与工资
AquaFlow-backend    Spring Boot 4 · Java 17 · MyBatis · MySQL 8.4
```

---

## 这个行业的难点：桶是资产，不是消耗品

通用电商的模型在这里会直接失效。顾客买水时买下的不只是水，还有那只桶的**权益**；喝完要还桶，
最后要退押金。所以系统不能只订单，还必须按 `(客户 × 水站 × 商品)` 管三本账：

```
权益 Right  = Σ 桶权益批次（押金条）剩余数    ← 顾客已到手的桶
过占 over   = 占用 − 权益                     ← 可为负：多还的桶寄存在水站
恒等式      ：占用 = 权益 + 过占
```

再叠加**三站语义** —— 一笔订单同时关联归属站（定价方）、履约站（库存与配送）、结算站
（营收与应收）。跨站外派时「**钱认结算站，客户资产认归属站**」，这条口径写错就是真丢钱。

这套模型不是设计出来好看：它源于原系统一个真实存在的死锁 —— 顾客欠桶不还就再也下不了单，
押金也永远退不出来。

## 三个必须懂的领域概念

**① 客户是全局身份。** `customer` 表**没有 `station_id` 列**，客户可以自由选择不同水站下单；
订单、桶、水票、押金一律按 `(customer_id, station_id)` 隔离。「经营归属」是
「绑定 ∪ 本站订单」的并集口径，不是字段。

**② 桶的四个数。** 权益（已到手）/ 配送中（已买下但未送达）/ 持有（权益 + 配送中，仅展示）/
占用（权益 + 过占，**还桶上限**）。下单抵扣与退押金**只认权益**，配送中的桶在结束前不参与抵扣。

**③ 三站语义。** `orders.station_id` 归属站 / `orders.delivery_station_id` 履约站 /
`orders.settle_station_id` 结算站。钱与应收认结算站；押金、水票、桶权益认归属站。

完整推导、判权表与状态机见 [`docs/architecture/02-领域模型.md`](./docs/architecture/02-领域模型.md)。

---

## 架构概览

```
        ┌──────────────────┐        ┌──────────────────┐
        │  miniapp-user    │        │ miniapp-delivery │
        │  （顾客端）       │        │（站长 + 配送员端） │
        └────────┬─────────┘        └────────┬─────────┘
                 │    HTTPS + JWT            │
                 └─────────────┬─────────────┘
                               ▼
                 ┌───────────────────────────┐
                 │   AquaFlow-backend        │
                 │   Spring Boot 4 · :8080   │
                 │   Controller → Service    │
                 │        → Mapper           │
                 └───────┬───────────┬───────┘
                         │           │
              ┌──────────▼──┐   ┌────▼─────────────┐
              │  MySQL 8.4  │   │ 腾讯云 COS（可选） │
              │  单库强一致  │   │ 订单照片 / 商品图  │
              └─────────────┘   └──────────────────┘
```

**模块化单体，不拆微服务。** 桶账、资金与对账之间有大量跨域事务，拆分会把数据库层面的一致性
保证换成分布式事务，代价远大于收益。

**没有 Web 管理后台。** 站长与配送员的全部管理界面都在 `miniapp-delivery/pages/station-mgmt/**`
（历史 Vue 后台已移除，仅 `archive/legacy-web-frontend/` 留档）。

后端按业务域划分 12 个模块组（认证与账号、客户与地址、商品与库存、订单、配送履约、支付与资金、
水票、桶资产、组织与人员、运营支撑、内容与文件、检索与通用）。
详见 [`docs/architecture/01-系统架构.md`](./docs/architecture/01-系统架构.md)。

## 技术栈

| 层 | 选型 |
|---|---|
| 后端 | Java 17（toolchain）· Spring Boot **4.0.6** · MyBatis-Spring-Boot 4.0.1 · Jackson 3 · Lombok |
| 构建 | Gradle Wrapper 9.4.1 |
| 数据库 | MySQL 8.4（**未启用 Flyway**，迁移手工执行） |
| 小程序 | 微信原生（无框架、无分包、无 npm 构建步骤） |
| 外部服务 | 微信登录 · 腾讯云 COS（可选）· 告警 Webhook（可选） |

## 当前规模

| 指标 | 值 | 真相源 |
|---|---|---|
| 后端 Controller / 端点映射 | 49 / 268 | `AquaFlow-backend/src/main/java/com/example/aquaflow/controller/` |
| 数据表 | 50 | `AquaFlow-backend/sql/schema.sql` |
| 迁移脚本 | v1 〜 v60 | `AquaFlow-backend/sql/README.md` |
| 集成测试 | 95 个类 / 466 个用例 / 0 失败 | `AquaFlow-backend/build/test-results/test/*.xml` |
| 小程序注册页面 | 顾客端 23 / 员工端 42 | 各自 `app.json` 的 `pages` |

> 上表为 2026-09-23 的 **`master` 提交状态**。**这些数字会随开发漂移，冲突时以上述真相源为准** ——
> 本仓库的文档规则要求会漂移的计数只在真相源处定义（见 [`docs/README.md`](./docs/README.md)）。
> 集成测试的件数尤其要注意：本机工作区若含未提交的新用例，实测值会大于上表。

---

## 快速开始

### 环境要求

JDK 17、MySQL 8.4、微信开发者工具。**不需要 Docker** —— 集成测试直接连本机 MySQL，不使用 Testcontainers。

### 1. 建库建表

`sql/schema.sql` 是唯一权威基线（全部 `CREATE TABLE IF NOT EXISTS`，可重复执行）。

```bash
cd AquaFlow-backend/sql
mysql -uroot -p aquaflow      < schema.sql
mysql -uroot -p aquaflow_test < schema.sql
```

> **必须用 shell 重定向（`<`）导入，不要走 PowerShell 管道** —— `Get-Content -Raw | mysql`
> 会按控制台代码页重编码，把中文注释写成乱码。

### 2. 配置环境变量

变量清单的权威来源是 [`AquaFlow-backend/.env.example`](./AquaFlow-backend/.env.example)。
**启动期硬校验只有三项**，缺一即拒绝启动：

| 变量 | 约束 |
|---|---|
| `JWT_SECRET` | 长度必须 ≥ 32 |
| `WX_APP_ID` | 微信小程序 appid |
| `WX_APP_SECRET` | 对应 secret |

### 3. 启动后端

```bash
cd AquaFlow-backend
./gradlew bootRun          # 端口 8080
```

### 4. 打开小程序

用微信开发者工具分别打开 `miniapp-user/` 与 `miniapp-delivery/`。请求本机后端需要两处开关同时打开：
① `project.config.json` 的 `urlCheck` 为 `false`；② 真机上右上角 `…` →「打开调试」。

详细步骤、代码约定与提交规范见 [`CONTRIBUTING.md`](./CONTRIBUTING.md)。

---

## 项目结构

```
AquaFlow-backend/         后端（Spring Boot）
├── src/main/java/com/example/aquaflow/
│   ├── controller/       49 个 REST 入口
│   ├── service/          业务逻辑与事务编排
│   ├── mapper/           MyBatis 数据访问
│   ├── constant/         枚举（订单状态 / 支付方式 / 支付状态 …）
│   ├── util/             纯规则（配送计费、客户画像抹除 …）
│   └── interceptor/ aspect/ config/   横切关注点
├── src/test/java/.../integration/     集成测试（真实 MySQL + 真 HTTP）
│   └── scenario/         业务链路级用例
└── sql/                  schema.sql（基线）+ 迁移脚本 + README.md（清单正本）

miniapp-user/             顾客端小程序
miniapp-delivery/         站长 + 配送员端小程序

docs/                     面向读者的文档（见 docs/README.md）
scripts/                  开发与验证脚本
archive/                  历史留档（不维护、不引用）
.github/workflows/ci.yml  CI（必须在仓库根）
```

## 测试与质量门禁

```bash
cd AquaFlow-backend
./gradlew cleanTest test --no-daemon     # 集成测试
bash ../scripts/verify.sh                # 全部静态门禁 + 敏感信息扫描
```

集成测试启动**完整 Spring 容器**、连**真实 MySQL**、发**真实 HTTP**，每个用例前 TRUNCATE 并断言
库名含 `test`。设计取舍与基类 API 见 [`docs/development/01-测试体系.md`](./docs/development/01-测试体系.md)。

CI 除测试外还跑六项静态门禁：`wxml` 事件绑定、页面可达性、`require` 层级、悬空注释、JS 解析期错误、
场景测试矩阵一致性。

## 安全

- 凭据只走环境变量，`application-local.yml` 与 `.env*` 一律不入库；CI 含敏感信息扫描步骤。
- JWT 双 Token；`@RequireRole` + `@RequireStation` 由 AOP 统一授权，新增方法自动生效。
- 登录类端点按来源 IP 限流；系统故障告警不投递给水站站长。

详见 [`SECURITY.md`](./SECURITY.md)。

---

## 文档导航

| 文档 | 内容 |
|---|---|
| [`docs/architecture/01-系统架构.md`](./docs/architecture/01-系统架构.md) | 部署拓扑、分层、横切关注点、模块划分 |
| [`docs/architecture/02-领域模型.md`](./docs/architecture/02-领域模型.md) | 三站语义、桶权益模型、三本账、状态机 |
| [`docs/architecture/03-数据模型.md`](./docs/architecture/03-数据模型.md) | 表分组与职责、金额真相源 |
| [`docs/api/01-REST-API参考.md`](./docs/api/01-REST-API参考.md) | 响应体与 `code` 语义、认证、**公开路径白名单**、按业务域分组的端点表 |
| [`docs/operations/01-部署与运维.md`](./docs/operations/01-部署与运维.md) | 环境变量、迁移执行、备份恢复、对账与告警、故障处置 |
| [`docs/development/01-测试体系.md`](./docs/development/01-测试体系.md) | 测试设计、静态门禁、CI |
| [`AGENTS.md`](./AGENTS.md) | **工程契约**：领域不变量、禁改项、已知坑与判据 |
| [`CONTRIBUTING.md`](./CONTRIBUTING.md) | 环境搭建、代码约定、提交规范 |
| [`SECURITY.md`](./SECURITY.md) | 安全策略与部署检查清单 |
| [`CHANGELOG.md`](./CHANGELOG.md) | 能力变更记录 |

完整的文档索引与维护规则见 [`docs/README.md`](./docs/README.md)。

## 已知边界

以下是**有意接受的现状**，不是缺陷：

- **微信支付未接入**，生产暂只开放现金（货到付款）与水票；本地模拟渠道仅供开发。
- **客户端正向进度通知未接入**：微信订阅消息对本场景不可行（除少数行业外为一次性授权，无法静默获取），站长端只有应用内红点。
- **本地开发两端共用同一 appid**：微信要求 `wx.login` 的 code 与 appid 配对，测试号换不出可用 openid。代价是一个 appid 只能发布一个小程序，两套代码不能各自发布。
- **未支持多实例**：限流计数与部分定时任务假定单实例部署。
- **`GET /api/notices`（顾客端公告列表）不做水站过滤**。

## License

[MIT](./LICENSE)
