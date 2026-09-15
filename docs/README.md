# AquaFlow 文档索引

> 本文件只索引**仓库内**的文档。
>
> **事实基准**：`src/**` 代码 > `AquaFlow-backend/sql/schema.sql` > 通过的集成测试 >
> 根 `AGENTS.md` > 本目录文档。本目录文档**已知大面积过期**，只作线索、不作规格。

## ⚠️ 核心架构决策（改动须同步）

- **客户是全局身份**：`customer` 表**没有 `station_id` 列**；订单 / 桶 / 水票 / 押金一律按
  `(customer_id, station_id)` 隔离。**禁止给 `customer` 加 `station_id`**。
- **订单状态 1–5**：`1 待配送 / 2 配送中 / 3 已送达 / 4 已完成 / 5 已取消`。以
  `constant/OrderStatus.java` 为准（历史上出现过 `1/3/4/5/6` 与 `1/4/2/3` 等错误口径）。
- **支付方式**：`1 微信 / 2 现金(货到付款) / 3 水票`，以 `constant/PayMethod.java` 为准；
  **微信支付渠道未接入**。
- **桶账唯一写入口是 `BarrelLedgerService`**；权益真相源是 `customer_barrel_lot.remain_qty`，
  `customer_barrel_over` 可为负。恒等式：占用 = 权益 + over。
- **水厂 / 厂长已彻底移除**（2026-09-11 复核）：`factory` 表、`FACTORY_ADMIN` 角色、各表
  `factory_id` 列在 DB 层已全部清除。演进见 `AquaFlow-backend/sql/README.md`。

## 设计讲解（10 篇）

每篇回答「为什么这么设计、为什么不那么设计」，含被否决的方案与真实踩坑。

| 文件 | 内容 |
|---|---|
| `design/00-文档导航.md` | 索引与讲述路线 |
| `design/01-项目总览与技术选型.md` | 业务背景、技术栈与选型理由 |
| `design/02-领域建模.md` | 四大域、聚合边界、多水站隔离维度 |
| `design/03-桶权益模型.md` | 核心：桶是资产不是消耗品 |
| `design/04-订单与状态机.md` | 状态机与非法流转的拒绝点 |
| `design/05-支付与资金.md` | 支付方式、押金入账、对账等式 |
| `design/06-权限与安全.md` | 角色、`@RequireRole`、跨站隔离 |
| `design/07-数据一致性与对账.md` | 并发、唯一键、守恒对账 |
| `design/08-踩坑与复盘.md` | 真实事故与结论 |
| `design/09-面试速答.md` | Q&A 要点 |

## 架构

| 文件 | 内容 | 状态 |
|---|---|---|
| `architecture/architecture.md` | 技术架构、后端分层、模块划分 | 旧版，仅作线索 |
| `architecture/api-design.md` | 统一响应、认证、API 设计 | 旧版，仅作线索 |

## 工程约定 · 交接 · 决策

| 文件 | 内容 |
|---|---|
| `AGENTS.md` | 工程 / 领域约定（**已知大面积过期**；冲突时以根 `AGENTS.md` + 代码为准） |
| `AI_EXECUTION_HANDOFF.md` | 改造执行任务书（**已知大面积过期**，仅作线索） |
| `audit/test-harness.md` | 集成测试怎么跑、如何判定成功（看 body `code`，不看 HTTP 状态） |
| `audit/write-path-inventory.md` | 各表写入口清单（实现新写路径前先查） |
| `SECURITY-密钥处置指南.md` | 密钥泄露事件的处置与是正记录（旧值已失效） |
| `decisions/decision-record.md` | 重大决策记录（**部分已作废**，文件顶部有标注） |

## 运行 / 本地环境

| 文件 | 内容 |
|---|---|
| `本地运行-笔记本当服务器.md` | 不买服务器/域名的运行方案：启动顺序、局域网真机预览、防火墙放行、开发登录、公网访问（CGNAT）判定、个人主体小程序红线、故障排查 |

## 工程过程记录（历史，保留）

| 目录 | 内容 |
|---|---|
| `audit/incident-*.md` | 源码误删与恢复的事件记录 |
| `audit/phase-*.md` | Phase B〜E 阶段性作业报告 |

## 2026-09-15 清出的文档

以下 7 份**因描述已不存在的 schema 或已被取代而删除**，需要时可从 git 历史恢复：

```
business/business-requirement.md     business/business-process.md   business/terminology.md
architecture/database.md             roadmap/backlog.md
deployment.md                        production-checklist.md
```

删除依据（每条都已核实）：全部**零入站引用**，且含 1〜16 处指向**已删除表 / 状态**的描述 ——
例如 `terminology.md` 写「押金维护在 `water_type.deposit`」（`water_type` 已于 v24 DROP）、
`architecture/database.md` 把 `batch`(49 行) 与 `water_type`(10 行) 列为现存表、
`business-requirement.md` 写订单状态 `1→4→2→3`（真实为 `1→2→3→4`）。
**错误信息比没有信息更危险**：留着它们，下一个 AI 会照着不存在的表写 SQL。

`docs/roadmap/roadmap.md`、`docs/测试阶段问题清单.md` 与仓库根 `.docs_trash/` 在本文件此前的
版本里就被索引，但**早已不存在**；本次一并从索引中移除。
