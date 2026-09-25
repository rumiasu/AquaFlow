# sql/archive/ —— 历史脚本归档区（不可执行）

> 2026-09-11 归档。目录内脚本**均已过期，直接执行一定报错**，保留仅为追溯历史。

## 为什么归档

这批脚本停留在 **V1 大迁移之前**，引用了大量已从库中删除的对象：

| 已删对象 | 被哪些脚本引用 |
| --- | --- |
| `factory` 表 / `FACTORY_ADMIN` 角色 | `seed_v2_part1_base.sql`、`seed_full_data.sql` |
| `water_type` 表（已被 `product` 取代） | `seed_v2_part1_base.sql`、`seed_full_data.sql` |
| `staff.password`（已改为微信/JWT 登录） | `seed_v2_part1_base.sql`、`seed_full_data.sql` |
| `orders.water_type_id`（已改为 `product_id`） | `seed_v2_part4_orders.sql`、`seed_full_data.sql` |

只删 `factory` 它们照样跑不起来 —— 修等于重写，且当前库已有真实数据，不需要靠种子脚本造数。

## 归档清单

| 文件 | 原用途 |
| --- | --- |
| `seed_v2_part1_base.sql` | 基础数据（水站/商品/水厂） |
| `seed_v2_part2_customers.sql` | 顾客数据 |
| `seed_v2_part3_addr_inv.sql` | 地址与库存 |
| `seed_v2_part4_orders.sql` | 订单数据 |
| `seed_v2_part5_aux.sql` | 辅助数据 |
| `seed_full_data.sql` | 全量种子（唯一被 `init.sql` 引用的一个） |
| `seed_test_user_86.sql` | 测试用户 |
| `init_delivery.sql` | 配送端初始化 |

`init.sql` 中的 `SOURCE seed_full_data.sql;` 已同步移除 —— 现在只建结构。

## 2026-09-18 追加归档：被 `schema.sql` 吸收的历史迁移（19 个）

与上面那批"跑起来必报错"的种子脚本不同，这 19 个**当年是能跑的**，只是内容已被 `schema.sql` 覆盖，
在新环境重复执行没有意义。它们原本躺在 `sql/` 顶层，靠 `../README.md` 的一段文字提醒"不要执行"——
现在移进归档区，让"顶层只剩要跑的脚本"成为目录结构事实。

| 文件 | 原判定 |
| --- | --- |
| `migration_full.sql`、`migration_v2.sql`、`migration_v3.sql` | SUPERSEDED |
| `migration_v5_audit_log.sql`、`migration_v6_file_manage.sql`、`migration_v7_water_type_image.sql`、`migration_v8_stock_index.sql`、`migration_v9_barrel_discrepancy.sql`、`migration_v10_feedback.sql`、`migration_v11_user_function_fixes.sql` | SUPERSEDED |
| `migration_p0_v12_payment_server_rules.sql`、`migration_p1_v13_barrel_asset_owed.sql`、`migration_p5_6_station_asset_isolation.sql`、`migration_p6_station_select.sql` | SUPERSEDED |
| `migration_p0_v12b_barrel_type.sql`、`migration_p0_v12c_orders_snapshot_coords.sql` | DUPLICATE（与 v11 重复） |
| `barrel_record.sql`、`order_template.sql` | DUPLICATE（与 `migration_full.sql` 重复） |
| `fix_add_openid.sql` | DUPLICATE（与 `add_openid.sql` 重复） |

⚠️ 逐条判定理由的正本仍在 `../README.md` 的同名表格里（本文件不重述，避免两处口径漂移）；
⚠️ 其中 `migration_p0_v12b_barrel_type.sql` 在一批**本地保留、未入库**的设计文档里被提及过（历史叙述，不按路径读文件），
移动不影响它。仓库内没有其它引用，也不需要有。

## 需要种子数据怎么办

手动按依赖顺序建：

1. `station` 水站
2. `staff` 员工（`role` 只有 `STATION_MANAGER` / `DELIVERY` 两种）
3. `product` 商品 → `inventory` 库存（含水票开关 `ticket_enabled` / `ticket_price`）
4. 顾客端注册下单

开发账号见主目录 `seed_dev_account.sql`。
