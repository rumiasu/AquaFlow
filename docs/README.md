# AquaFlow 文档索引

> 本目录为项目文档的**真相源**。2026-09-06 整理：移除 25 个旧模块稿、3 篇个人草稿、7 份历史会话报告；修订 3 份架构文档，去掉已废弃的厂长/水厂模块。历史文件可恢复，见文末「已归档」。

## ⚠️ 核心架构决策（不可删除，改动须同步）
- **客户全局身份（V13）**：`customer` 表无 `station_id` 列；客户资产按 `(customer_id, station_id)` 隔离，经 `customer_station_config` 关联。禁止给 `customer` 加 `station_id`。
- **订单状态 1–5**：`1 待配送 / 2 配送中 / 3 已送达 / 4 已完成 / 5 已取消`。状态流转见 `OrderStatus.java`，小程序端 `AquaFlow-frontend` 的旧 `2/7` 态映射已作废。
- **桶资产隔离**：按 `(customer_id, station_id)` 隔离，与水站绑定。
- **水厂 / 厂长已彻底移除**（2026-09-11 复核）：`factory` 表、`FACTORY_ADMIN` 角色、各表 `factory_id` 列**在 DB 层已全部清除**；后端与三个小程序端均无水厂业务代码（`staff.role` 实际取值只有 `STATION_MANAGER` / `DELIVERY`）。演进过程见 `AquaFlow-backend/sql/README.md`「历史迁移演进」。

## 架构
| 文件 | 内容 |
|------|------|
| `architecture/architecture.md` | 技术架构、后端分层、模块划分、前端架构、安全架构、关键设计决策 |
| `architecture/database.md` | 数据库表设计（基础数据 / 业务核心 / 资产管理 / 运营管理 / 快捷功能 / 辅助） |
| `architecture/api-design.md` | 统一响应、认证、各业务 API 设计、权限说明 |

## 业务
| 文件 | 内容 |
|------|------|
| `business/business-requirement.md` | 业务需求 |
| `business/business-process.md` | 业务流程 |
| `business/terminology.md` | 术语表 |

## 决策 / 路线图
| 文件 | 内容 |
|------|------|
| `decisions/decision-record.md` | 重大决策记录 |
| `roadmap/roadmap.md` | 路线图 |
| `roadmap/backlog.md` | 待办池 |

## 部署与验收
| 文件 | 内容 |
|------|------|
| `deployment.md` | 部署说明 |
| `production-checklist.md` | 生产就绪检查清单 |
| `测试阶段问题清单.md` | 测试阶段实测问题与修复（2026-09-05） |

## 智能体
| 文件 | 内容 |
|------|------|
| `AGENTS.md` | 给 AI 智能体看的工程约定 |

## 重新设计交接
| 文件 | 内容 |
|------|------|
| `redesign/完整交接文档.md` | **交给外部 AI 的完整交接文档**（10 章）：业务本质、术语、37 张表数据模型、三端按钮级功能清单、30 个 Controller 接口清单、不可推翻的硬约束、已知病灶、待拍板决策点、19 条历史踩坑。用于让另一个 AI 从零推翻现有设计 |

## 已归档（可恢复）
旧模块稿（`docs/modules/*` 25 个）、个人草稿（`docs/learning/*` 3 篇）、历史会话报告（7 份）共 **37 个 md**，已用 `git mv` 移至仓库根 `.docs_trash/`，可由 git 历史恢复。确认无需后执行：

```bash
git rm -r .docs_trash
git commit -m "chore: 永久删除已归档的旧文档"
```
