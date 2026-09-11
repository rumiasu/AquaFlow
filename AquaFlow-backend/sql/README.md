# AquaFlow 数据库初始化

## 文件说明

| 文件 | 用途 | 说明 |
|------|------|------|
| `schema.sql` | 数据库结构基线 | 当前库完整 DDL（37 张业务表 + 1 视图），2026-09-11 从实际库重新导出 |
| `init.sql` | 一键初始化入口 | 创建数据库 + schema，**只建结构，不含种子数据** |
| `seed_dev_account.sql` | 开发账号 | 开发环境账号初始化 |
| `seed_new_user_83.sql` | 测试用户 | 测试用户数据 |
| `archive/` | **历史脚本归档区** | 8 个 V1 前的种子脚本，均已过期不可执行，详见 `archive/README.md` |

> ⚠️ 原 `seed_full_data.sql` / `seed_v2_part*` / `init_delivery.sql` 已于 2026-09-11 移入 `archive/`。
> 它们停留在 V1 大迁移之前（引用 `factory` / `water_type` / `staff.password` 等已删对象），
> 修的成本 ≈ 重写，不再维护。

## 初始化方式

所有环境统一：**只建结构，基础数据由管理员手动创建。**

```bash
cd AquaFlow-backend/sql
mysql -u root -p < init.sql
```

或只建结构（库已存在时）：

```bash
mysql -u root -p aquaflow < schema.sql
```

初始化后按依赖顺序手动建基础数据：

1. `station` 水站
2. `staff` 员工（`role` 仅 `STATION_MANAGER` / `DELIVERY`）
3. `product` 商品 → `inventory` 库存（含水票开关 `ticket_enabled`、`ticket_price`）
4. 顾客端注册下单

## 注意事项

- `schema.sql` 全部使用 `CREATE TABLE IF NOT EXISTS`，重复执行安全，不会覆盖已有表
- 视图 `v_station_exception_stats` 使用 `CREATE OR REPLACE VIEW`，可重复执行
- `init.sql` 中的 `SOURCE` 依赖相对路径，**必须在 `sql/` 目录下执行**
- 不再有任何随 init 自动灌入的种子数据 —— 期望是"建完是空库"，避免误把测试数据带进验收/生产
- 当前库已有真实业务数据，需要造数请用对账/导出功能，而不是种子脚本

---

## 历史迁移演进（脚本已删除，结论保留）

> 以下迁移脚本**均已在开发库执行完毕**，产出已固化到当前 `schema.sql`。
> 为避免与现行基线混淆、避免有人误执行历史脚本，脚本本体已于 2026-09-11 删除，
> 此处保留其结论与架构约定。

### 1. V1 大迁移 —— `migration_v1_final.sql`（2026-08-24，241 行）

- 从旧结构（含 `factory` / `batch` / `risk_alert` / `water_type` / 旧 staff 字段）迁移到 V1 最终结构
- 删除废弃表：`batch_order`、`batch`、`risk_alert`、`station_payment_config`、`stock_transfer`、`factory`、`water_type`
- `water_type`（水类型）由 `product`（商品）替代
- **结论：水厂端在 DB 层的表（`factory`）于此删除**

### 2. 身份与绑定模型 —— `migration_v1_final_binding.sql`（2026-08-24，89 行）

- `station` 删除 `manager_name`：站长关系改由 `staff.role` + `staff.station_id` 表达
- `staff` 删除明文 `password`、`factory_id`
- **架构约定（至今有效）**：
  - `staff.station_id` = 当前归属水站，`NULL` = 未绑定
  - `staff_station_application` = 入站申请历史
  - 禁止字段：`staff.apply_station_id`、`staff.binding_status`、`station.manager_name`、`factory_id`、明文 `password`、`water_type`
  - 禁止角色：`FACTORY_ADMIN` / `factory` / `manager` / `customer.role`

### 3. 测试阶段整改 —— `migration_v2_consolidated_fixes.sql`（68 行）

- **A. 水厂端彻底删除**：`orders.factory_id` 全为 0/NULL、无真实数据、后端仅死字段、前端零引用 → 删列
- C.1 创建缺失的真功能表：`company_info`（企业客户资料）、`customer_notification`（客户通知）等
- **结论：水厂端在 DB 层的最后一处列（`orders.factory_id`）于此删除。至此 DB 层再无水厂痕迹。**

### 4. 双站模型 —— `migration_v16_orders_station_cleanup.sql`（41 行）

- 删除旧的 `orders.station_id`（全为 NULL），将 `owner_station_id` 改名为 `station_id`
- **架构约定（至今有效）**：
  - `orders.station_id` = **订单归属水站**（客户自选）
  - `orders.delivery_station_id` = **实际履约水站**（可被站长切换）
- 重建索引 `idx_orders_station`、`idx_orders_delivery_station`

### 5. 水厂运营平台（历史名词）—— `migration_factory_ops.sql`

- 背景：项目早期曾规划"水厂运营平台"。本脚本为其建表（`stock_transfer` 调拨表、`risk_alert` 风险预警表），
  并给 `orders` / `inventory` 加 `station_id`、给 `customer` 加 `role`
- **该平台从未实现**：后端无任何 factory 业务代码，无 `FACTORY_ADMIN` 角色（实际角色只有 `STATION_MANAGER` / `DELIVERY`）
- 所建两表已在「V1 大迁移」中被 DROP
- 脚本含 `DELETE FROM inventory`，**严禁在任何环境执行**
- 有效产出（`orders.station_id`、`inventory.station_id`）已固化到 `schema.sql`；脚本本体删除

### 6. JWT 认证 —— `migration_v4_jwt_auth.sql`

- 引入 `user_token` 表，支撑 JWT 双 Token 无感续期
- 脚本内曾插入 `FACTORY_ADMIN` 测试账号 —— **该角色从未实现**，属无效数据
- 产出已被 `schema.sql` 吸收；脚本本体删除

### 水厂端清理结论（2026-09-11 复核）

| 检查项 | 结果 |
|--------|------|
| 后端 Java 中的水厂 / factory 业务代码 | 0 处 |
| 小程序端水厂相关代码 | 0 处 |
| DB `factory` 表 | 0 个 |
| DB 全库 `factory_id` 列 | 0 个 |
| `FACTORY_ADMIN` 角色 | 不存在（`staff.role` 实际只有 STATION_MANAGER / DELIVERY） |

> `staff.role` 列注释中的 `FACTORY_ADMIN` 已于同日修正为 `角色：STATION_MANAGER/DELIVERY`。

---

## 已废弃的 Migration

以下历史 migration 已被 `schema.sql` 吸收，不再需要在新环境执行：

| 文件 | 状态 | 说明 |
|------|------|------|
| migration_full.sql | SUPERSEDED | 被 schema.sql 吸收 |
| migration_v2.sql | SUPERSEDED | 被 schema.sql 吸收 |
| migration_v3.sql | SUPERSEDED | 被 schema.sql 吸收 |
| migration_v5_audit_log.sql | SUPERSEDED | 被 schema.sql 吸收 |
| migration_v6_file_manage.sql | SUPERSEDED | 被 schema.sql 吸收 |
| migration_v7_water_type_image.sql | SUPERSEDED | 被 schema.sql 吸收 |
| migration_v8_stock_index.sql | SUPERSEDED | 被 schema.sql 吸收 |
| migration_v9_barrel_discrepancy.sql | SUPERSEDED | 被 schema.sql 吸收 |
| migration_v10_feedback.sql | SUPERSEDED | 被 schema.sql 吸收 |
| migration_v11_user_function_fixes.sql | SUPERSEDED | 被 schema.sql 吸收 |
| migration_p0_v12_payment_server_rules.sql | SUPERSEDED | 被 schema.sql 吸收 |
| migration_p0_v12b_barrel_type.sql | DUPLICATE | 与 v11 重复 |
| migration_p0_v12c_orders_snapshot_coords.sql | DUPLICATE | 与 v11 重复 |
| migration_p1_v13_barrel_asset_owed.sql | SUPERSEDED | 被 schema.sql 吸收 |
| migration_p5_6_station_asset_isolation.sql | SUPERSEDED | 被 schema.sql 吸收 (P5.6 资产站点隔离) |
| migration_p6_station_select.sql | SUPERSEDED | 被 schema.sql 吸收 (P6 客户选站) |
| barrel_record.sql | DUPLICATE | 与 migration_full.sql 重复 |
| order_template.sql | DUPLICATE | 与 migration_full.sql 重复 |
| fix_add_openid.sql | DUPLICATE | 与 add_openid.sql 重复 |

> 另：`schema_v1_final.sql`（24 张表）为 V1 大迁移的中间产物，同样已被 `schema.sql` 取代，
> 仅作历史参考，**不要用于初始化新环境**。

## 严禁在生产环境执行的 SQL

| 文件 | 原因 |
|------|------|
| reset_data.sql | 包含 `TRUNCATE` 多张表，会清空所有数据 |
| reconcile_order_814.sql | 一次性数据修复脚本 |
| archive/init_delivery.sql | 测试数据脚本（已归档，且不可执行） |
| archive/seed_test_user_86.sql | 测试用户数据（已归档，且不可执行） |
| archive/seed_full_data.sql | 全量测试数据，含明文密码（已归档，且不可执行） |
| seed_dev_account.sql | 开发测试账号 |
| seed_new_user_83.sql | 测试用户数据 |
