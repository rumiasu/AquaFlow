# AquaFlow — 桶装水配送管理系统

[![CI](https://github.com/rumiasu/AquaFlow/actions/workflows/ci.yml/badge.svg)](https://github.com/rumiasu/AquaFlow/actions/workflows/ci.yml)

**给水站用的桶装水（18.9L）配送管理系统。** 顾客在小程序选站下单，站长接单派单、对账管钱，
配送员送达并回收空桶 —— 把「桶」当作要归还、要退押金的**资产**管起来的一整套业务闭环。

两端原生微信小程序 + 一个 Spring Boot 后端。**没有 Web 管理后台**：站长和配送员的全部管理界面
都在小程序里（`miniapp-delivery/pages/station-mgmt/**`）。

> **本文是这个项目的介绍** —— 它解决什么问题、行业难在哪、怎么实现的、现在做到什么程度、
> 哪些是**有意没做**的。要**跑起来 / 改代码**请看 [`CONTRIBUTING.md`](./CONTRIBUTING.md)。

| 模块 | 使用者 | 能做什么 |
|---|---|---|
| `miniapp-user` | 顾客 | 选站下单、买桶、用水票、退空桶 |
| `miniapp-delivery` | 站长 + 配送员 | 接单派单、送达回桶、对账、员工与工资 |
| `AquaFlow-backend` | —— | Spring Boot 4 · Java 17 · MyBatis · MySQL 8.4 |

---

## 1. 这个行业的难点：桶是资产，不是消耗品

通用电商的模型在这里会直接失效。顾客买水时买下的不只是水，还有那只桶的**权益**；喝完要还桶，
最后要退押金。所以系统不能只管订单，还必须按 `(客户 × 水站 × 商品)` 管三本账：

```
权益 Right  = Σ 桶权益批次（押金条）剩余数    ← 顾客已到手的桶
过占 over   = 占用 − 权益                     ← 可为负：多还的桶寄存在水站
恒等式      ：占用 = 权益 + 过占
```

再叠加**三站语义** —— 一笔订单同时关联归属站（定价方）、履约站（库存与配送）、结算站
（营收与应收）。跨站外派时「**钱认结算站，客户资产认归属站**」，这条口径写错就是真丢钱。

这套模型不是设计出来好看：它源于原系统一个真实存在的死锁 —— 顾客欠桶不还就再也下不了单，
押金也永远退不出来。

## 2. 三个必须懂的领域概念

**① 客户是全局身份。** `customer` 表**没有 `station_id` 列**，客户可以自由选择不同水站下单；
订单、桶、水票、押金一律按 `(customer_id, station_id)` 隔离。「经营归属」是
「绑定 ∪ 本站订单」的并集口径，不是字段。

**② 桶的四个数。** 权益（已到手）/ 配送中（已买下但未送达）/ 持有（权益 + 配送中，仅展示）/
占用（权益 + 过占，**还桶上限**）。下单抵扣与退押金**只认权益**，配送中的桶在结束前不参与抵扣。

**③ 三站语义。** `orders.station_id` 归属站 / `orders.delivery_station_id` 履约站 /
`orders.settle_station_id` 结算站。钱与应收认结算站；押金、水票、桶权益认归属站。

完整推导、判权表与状态机见 [`docs/architecture/02-领域模型.md`](./docs/architecture/02-领域模型.md)。

---

## 3. 架构概览

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

**两个关键取舍：**

- **模块化单体，不拆微服务。** 桶账、资金与对账之间有大量跨域事务，拆分会把数据库层面的一致性
  保证换成分布式事务，代价远大于收益。
- **没有 Web 管理后台。** 站长与配送员的全部管理界面都在 `miniapp-delivery/pages/station-mgmt/**`
  （历史 Vue 后台已移除，仅 `archive/legacy-web-frontend/` 留档）。

后端按业务域划分 12 个模块组（认证与账号、客户与地址、商品与库存、订单、配送履约、支付与资金、
水票、桶资产、组织与人员、运营支撑、内容与文件、检索与通用）。
详见 [`docs/architecture/01-系统架构.md`](./docs/architecture/01-系统架构.md)。

## 4. 技术栈

| 层 | 选型 |
|---|---|
| 后端 | Java 17（toolchain）· Spring Boot **4.0.6** · MyBatis-Spring-Boot 4.0.1 · Jackson 3 · Lombok |
| 构建 | Gradle Wrapper 9.4.1 |
| 数据库 | MySQL 8.4（**未启用 Flyway**，迁移手工执行） |
| 小程序 | 微信原生（无框架、无分包、无 npm 构建步骤） |
| 外部服务 | 微信登录 · 腾讯云 COS（可选）· 告警 Webhook（可选） |

## 5. 当前规模

| 指标 | 值 | 真相源 |
|---|---|---|
| 后端 Controller / 端点映射 | 53 / 285 | `AquaFlow-backend/src/main/java/com/example/aquaflow/controller/`（端点数 `node scripts/check-api-doc.js` 实跑） |
| 数据表 | 53 | `AquaFlow-backend/sql/schema.sql` |
| 迁移脚本 | v1 〜 v70 | `AquaFlow-backend/sql/README.md` |
| 集成测试 | 121 个类 / 631 个用例 / 0 失败 | `AquaFlow-backend/build/test-results/test/*.xml` |
| 小程序注册页面 | 顾客端 22 / 员工端 42 | 各自 `app.json` 的 `pages` |

> **这些数字会随开发漂移，冲突时以上述真相源为准** —— 本仓库的文档规则要求会漂移的计数只在
> 真相源处定义（见 [`docs/README.md`](./docs/README.md)）。上表为 2026-09-30 的 `main` 分支实测状态。

## 6. 质量体系

四层防线，全部自动化，不依赖人工记得跑：

1. **集成测试** —— 启动**完整 Spring 容器**、连**真实 MySQL**、发**真实 HTTP**，每个用例前 TRUNCATE
   并断言库名含 `test`。业务链路另有 `integration/scenario/` 一层。
2. **静态门禁六项**（CI 每次跑）—— `wxml` 事件绑定、页面可达性、`require` 层级、悬空注释、
   JS 解析期错误、场景测试矩阵一致性。
3. **敏感信息扫描** —— 提交前扫已跟踪文件里的硬编码密钥。
4. **对账等式** —— 押金守恒、水票批次、配送中桶、应收账款等 10 条等式每日 03:00 定时落表；
   不平会按**系统故障**投递给系统管理员（不投给站长）。

跑法见 [`CONTRIBUTING.md`](./CONTRIBUTING.md) §4–§5，设计取舍见
[`docs/development/01-测试体系.md`](./docs/development/01-测试体系.md)。

## 7. 安全

- 凭据只走环境变量，`application-local.yml` 与 `.env*` 一律不入库；CI 含敏感信息扫描步骤。
- JWT 双 Token；`@RequireRole` + `@RequireStation` 由 AOP 统一授权，新增方法自动生效。
- 登录类端点按来源 IP 限流；系统故障告警不投递给水站站长。

详见 [`SECURITY.md`](./SECURITY.md)。

---

## 8. 已知边界

以下是**有意接受的现状**，不是缺陷：

- **微信支付未接入**，生产暂只开放现金（货到付款）与水票；本地模拟渠道仅供开发。
- **客户端正向进度通知未接入**：微信订阅消息对本场景不可行（除少数行业外为一次性授权，无法静默获取），站长端只有应用内红点。
- **未支持多实例**：限流计数与部分定时任务假定单实例部署。
- **`GET /api/notices`（顾客端公告列表）不做水站过滤**。

## 9. 文档导航

| 文档 | 内容 |
|---|---|
| [`docs/architecture/01-系统架构.md`](./docs/architecture/01-系统架构.md) | 部署拓扑、分层、横切关注点、模块划分 |
| [`docs/architecture/02-领域模型.md`](./docs/architecture/02-领域模型.md) | 三站语义、桶权益模型、三本账、状态机 |
| [`docs/architecture/03-数据模型.md`](./docs/architecture/03-数据模型.md) | 表分组与职责、金额真相源 |
| [`docs/api/01-REST-API参考.md`](./docs/api/01-REST-API参考.md) | 响应体与 `code` 语义、认证、**公开路径白名单**、按业务域分组的端点表 |
| [`docs/operations/01-部署与运维.md`](./docs/operations/01-部署与运维.md) | 环境变量、迁移执行、备份恢复、对账与告警、故障处置 |
| [`docs/development/01-测试体系.md`](./docs/development/01-测试体系.md) | 测试设计、静态门禁、CI |
| [`docs/design/`](./docs/design/) | 各功能的设计记录（水票、配送计费、计件工资、营业状态…） |
| [`CONTRIBUTING.md`](./CONTRIBUTING.md) | **接手工程师入口**：环境搭建、项目结构、测试、代码约定、提交规范 |
| [`AGENTS.md`](./AGENTS.md) | **工程契约**：领域不变量、禁改项、已知坑与判据 |
| [`SECURITY.md`](./SECURITY.md) | 安全策略与部署检查清单 |
| [`CHANGELOG.md`](./CHANGELOG.md) | 能力变更记录 |

完整的文档索引与维护规则见 [`docs/README.md`](./docs/README.md)。

## License

[MIT](./LICENSE)
