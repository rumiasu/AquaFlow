# AquaFlow 数据库初始化

## 文件说明

| 文件 | 用途 | 说明 |
|------|------|------|
| `schema.sql` | 数据库结构基线 | 32张表的完整DDL |
| `init.sql` | 一键初始化入口 | 创建数据库 + schema + seed |
| `seed_full_data.sql` | 开发/验收环境种子数据 | 包含完整的业务示例数据 |

## 初始化方式

### 开发环境

```bash
mysql -u root -p < sql/init.sql
```

### 验收环境

```bash
mysql -u root -p < sql/init.sql
```

### 生产环境

**禁止直接使用 `seed_full_data.sql`。**

生产环境必须使用独立管理员初始化流程：
1. 执行 `schema.sql` 创建表结构
2. 由管理员手动创建水厂、水站、员工、水类型等基础数据
3. 生产员工密码必须使用 BCrypt 哈希

## 注意事项

- `seed_full_data.sql` 仅用于开发、测试、验收环境
- 该文件包含明文密码 (`123456`)，仅作为测试用途
- 生产环境**禁止**直接使用该种子数据
- 生产员工密码必须使用 BCrypt 哈希存储

## 已废弃的 Migration

以下历史 migration 已被 `schema.sql` 吸收，不再需要在新环境执行：

| 文件 | 状态 | 说明 |
|------|------|------|
| migration_full.sql | SUPERSEDED | 被 schema.sql 吸收 |
| migration_v2.sql | SUPERSEDED | 被 schema.sql 吸收 |
| migration_v3.sql | SUPERSEDED | 被 schema.sql 吸收 |
| migration_v4_jwt_auth.sql | SUPERSEDED | 被 schema.sql 吸收 |
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

## 严禁在生产环境执行的 SQL

| 文件 | 原因 |
|------|------|
| migration_factory_ops.sql | 包含 `DELETE FROM inventory`，会清空库存数据 |
| reset_data.sql | 包含 `TRUNCATE` 多张表，会清空所有数据 |
| reconcile_order_814.sql | 一次性数据修复脚本 |
| init_delivery.sql | 测试数据脚本 |
| seed_dev_account.sql | 开发测试账号 |
| seed_test_user_86.sql | 测试用户数据 |
| seed_new_user_83.sql | 测试用户数据 |
