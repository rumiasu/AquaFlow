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

## 需要种子数据怎么办

手动按依赖顺序建：

1. `station` 水站
2. `staff` 员工（`role` 只有 `STATION_MANAGER` / `DELIVERY` 两种）
3. `product` 商品 → `inventory` 库存（含水票开关 `ticket_enabled` / `ticket_price`）
4. 顾客端注册下单

开发账号见主目录 `seed_dev_account.sql`。
