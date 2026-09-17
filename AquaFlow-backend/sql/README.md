# AquaFlow 数据库初始化

## 文件说明

| 文件 | 用途 | 说明 |
|------|------|------|
| `schema.sql` | 数据库结构基线 | 当前库完整 DDL（39 张业务表、无视图），2026-09-11 从实际库重新导出，2026-09-12 校正漂移，2026-09-15 清理废弃对象，2026-09-16 加入商品与库存重构的 3 列 + 2 个唯一键 + `product_submission`（v31） |
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
- 基线**只含表、不含视图**。原视图 `v_station_exception_stats`（水站桶异常近 30 天统计）已随 2026-09-15 的清理移出基线：它只有 0 处代码引用，属人工查看用的临时产物，需要时直接查 `order_barrel_exception` 即可
- `init.sql` 中的 `SOURCE` 依赖相对路径，**必须在 `sql/` 目录下执行**
- 不再有任何随 init 自动灌入的种子数据 —— 期望是"建完是空库"，避免误把测试数据带进验收/生产
- 当前库已有真实业务数据，需要造数请用对账/导出功能，而不是种子脚本

---

## 基线之后必须补跑的迁移（已有库升级）

`schema.sql` 是**新建库**的权威基线。对于**已经存在的老库**，以下迁移脚本必须按顺序补跑，
否则代码依赖的表/列/索引不存在，后端会在运行时崩溃：

| 顺序 | 文件 | 用途 |
|------|------|------|
| 1 | `migration_order_transfer.sql` | 转单表 `order_transfer` |
| 2 | `migration_inventory_record.sql` | 库存流水表 `inventory_record` |
| 3 | `migration_aq_bucket_right_v1_ddl.sql` + `migration_aq_bucket_right_v1_backfill.sql` | 桶权益模型（lot / over / record_lot）。**注意**：`_backfill.sql` 会顺手建一张人工核对用的差异登记表 `migration_diff_bucket_right`，它**不属于基线**、无任何代码引用，跑完核对一遍即可 DROP（真实库已于 2026-09-15 删除） |
| 4 | `migration_aq009_deposit_timing.sql` | 押金改为支付成功时入账 |
| 5 | `migration_aq056_payment_fk.sql` | `payment_record` 外键 |
| 6 | `migration_fix_ticket_account_uk.sql` | 水票账户唯一键修正 |
| 7 | `migration_v22_drop_station_offline_payment.sql` | 删除 `station.offline_payment_enabled` |
| 8 | `migration_v23_fix_payment_ticket_uk.sql` | **[DEF-3]** 修正 `uk_ticket_consume`（纳入 `source`）与 `uk_payment_order_status`（降级为普通索引）。**2026-09-14 已在真实库执行**（此前从未执行：真实库上「取消已付款订单」与「水票退款」都会撞唯一键而失败，已用事务回滚法复现并复测通过） |
| 9 | `migration_v24_fix_garbled_column_comments.sql` | 归一化导出期编码事故造成的乱码列/表注释（16 列 + 6 表），并补齐停在旧口径的 `orders` 注释。**已在真实库执行** |
| 10 | `migration_v25_retire_customer_owed_barrel.sql` | 归档旧欠桶台账 `customer_owed_barrel`：备份为 `bak_v25_customer_owed_barrel` 后改名为 `bak_v25_customer_owed_barrel_retired`（**不 DROP**，改名后同名引用会立刻报错，作为误引用哨兵）。**已在真实库执行** |
| 11 | `migration_v26_deposit_record_order_index.sql` | 为 `deposit_record` 增加 `idx_deposit_record_order(related_order_id, type)` —— 支付/退款路径按订单查押金流水，原来无索引（全表扫描）。**已在真实库执行** |
| 12 | `migration_v27_station_adjustment.sql` | 站长资产调整单：新增 `station_adjustment`（人工补录/订正的单据头）与 `reconciliation_result`（对账结果落表）；给 `barrel_record` / `deposit_record` / `ticket_record` 各加 `adjustment_id` 及调整场景唯一键（`uk_record_adjustment` / `uk_deposit_adjustment` / `uk_ticket_adjustment`）。纯新增、不改既有列、不动数据。**2026-09-14 已在真实库执行**（执行前已 `mysqldump` 备份至 `backup/`；存量行数执行前后一致：`barrel_record` 4 / `deposit_record` 7 / `ticket_record` 4） |
| 13 | `migration_v28_payment_active_order_uk.sql` | **[AQ-053] 资金防重**：为 `payment_record` 增加 STORED 生成列 `active_order_id`（仅当 `status in (1,2)` 时取 `order_id`，否则 NULL）与唯一键 `uk_payment_active_order`，使「同一订单最多一条活跃流水」由数据库强制。修复并发重复提交导致重复扣票 + 重复入账押金（`payment_record` 原唯一键因与退款冲正流水冲突已降级为普通索引）。**执行前务必先跑脚本第 1 步预检**：若历史数据已有同一订单多条活跃流水，加唯一键会失败，须人工核对后处理（脚本不自动删资金数据）。**2026-09-14 已在真实库执行**（预检 0 行重复；执行后 17 行未变，并已用回滚事务验证：同订单再插活跃流水被 `Duplicate entry` 拒绝） |
| 14 | `migration_v29_customer_barrel_over_owed_since.sql` | **欠桶台账**：给 `customer_barrel_over` 增加可空列 `owed_since`（本次欠桶起始时间），用于站长端"欠桶台账"算天数与下单标红。语义：over 由 ≤0 变 >0 写入、回到 ≤0 清空、已是正数再增加**不重置**（复用 `update_time` 会因每次部分回收被刷新而永远显示"今天"）。纯新增、可空、不改数据；**只用于展示，不参与任何校验**（欠桶的物理约束仍是 `占用 = 权益 + over ≥ 0`）。脚本内含存量近似回填（`over_qty > 0` 的行用 `update_time` 兜底）。**2026-09-15 已在真实库执行**（执行前已 `mysqldump` 备份至 `backup/`） |

| 15 | `migration_v30_alert_log.sql` | **分级告警**：新增 `alert_log` —— 按「谁该处理」投递（`SYSTEM` 系统故障→系统管理员；`OPERATION` 运营故障→该站站长，见 `constant/AlertType`）。原因：此前所有"通知"都只是 `log.info`，桶异常产生后**站长根本不知道**（`recordReturn` 里那行推送甚至是注释掉的）。落库负责"可追责"；外部渠道（系统告警 webhook / 站长微信订阅消息）未接入时记 `notify_status=LOGGED`。纯新增 1 张表、不改既有列与数据。**2026-09-16 已在真实库执行**（执行前已 `mysqldump` 备份至 `backup/`） |

| 16 | `migration_v31_catalog_and_station_pricing.sql` | **商品与库存重构**（规格见 `docs/design/12-商品与库存重构.md`）：`product` 加 `owner_station_id`（NULL=通用商品库、非空=该站自定义）+ 两个 STORED 生成列及唯一键（`preset_uk`=名称\|品牌\|规格，`station_uk`=站号:名称\|品牌\|规格）+ 归属索引；`inventory` 加 `sale_price`/`deposit_price`（站级售价/押金覆盖，NULL 或 ≤0 = 回落 `product` 参考值，计价唯一入口 `util/PriceUtil`）；新增 `product_submission`（站长自定义商品上报通用库）。纯新增列/表，**不动任何存量数据**（存量商品 `owner_station_id` 保持 NULL = 通用库）。**2026-09-16 已在真实库执行**（执行前已 `mysqldump` 备份至 `backup/aquaflow_before_v31_20260916-225048.sql`；脚本自带 6 项校验全部通过，行数未变：product 2 / inventory 2 / orders 20，库对象 38→39） |

| 17 | `migration_v32_station_operating_status.sql` | **水站营业状态（软状态）+ 站长留言**（规格见 `docs/design/13-营业状态与公告.md`）：`station` 加 `operating_status`（1 正常运营 / 2 休息中 / 3 配送延迟 / 4 暂停配送可预约，见 `constant/StationOperatingStatus`）、`status_note`（站长留言 ≤100 字）、`status_update_time`。**软状态一律不阻断下单**，只作提示（商城/下单页横幅 + 下单响应 `warnings`）；硬状态仍是 `station.status`（2 停业 = 下单直接被拒）。纯新增 3 个可空/带默认列，存量水站自动为 `1 正常运营`。**2026-09-17 已在真实库执行**（执行前已 `mysqldump` 备份至 `backup/aquaflow_before_v32_20260917-085327.sql`；校验：三列就绪、2 个水站均为 1、行数未变 station 2 / staff 3 / orders 20；并用「事务内改状态 → 校验 → ROLLBACK」验证读写路径且零残留） |
| 18 | `migration_v33_payment_idempotency.sql` | **在线购票幂等键**：`payment_record` 加 `idempotency_key varchar(64)` + 唯一键 `uk_payment_idempotency(customer_id, idempotency_key)`。原因：在线购票是**无订单支付**（`order_id` 为 NULL），`uk_payment_active_order` 建在生成列 `active_order_id` 上、**MySQL 唯一键中 NULL 互不冲突 → 该路径零保护**；连点两次「买票」落两条待收款流水，站长在「待确认收款」看到两行、两条都确认即**入账两次水票**（`confirmPayment` 的乐观锁只保证**单条**流水确认一次，管不住重复流水）。唯一键必须带 `customer_id` —— 只按 token 唯一会让客户端传别人的 token 取回别人的支付记录（跨客户泄露）。纯新增 1 列 + 1 个唯一键，存量行 `idempotency_key` 全为 NULL → **对存量数据与全部订单支付零影响**。**2026-09-17 已在测试库执行并验证幂等（二次执行全 skip）；⚠️ 真实库尚未执行** |
| 19 | `migration_v34_delivery_fee_and_floors.sql` | **配送计费与配送范围的前置字段（Phase 0，只加列、不改任何业务逻辑）**：`station` 加 `lat`/`lng`（配送范围要算「站点→客户」距离，而原先只有 `address` 有坐标）；`address` 加 `floor`/`has_elevator`（楼层费依据，见 `docs/design/17` §4.4 —— **NULL=未确认 与 0=确认无电梯必须区分**，混同会向客户乱收费）；`orders` 与 `payment_record` 各加 `delivery_fee`/`floor_fee`。⚠️ 费用**绝不并入 `water_amount`**（污染水费口径）或 **`deposit_amount`**（那是**可退押金**，退款路径按它释放押金余额，混入会导致取消订单**多退钱**）。本版 `total_amount` 的构成**一个字都没改**（仍是 水费 + 押金）；把费用并入总额是 Phase 1 的事，届时必须同改 `PaymentServiceImpl.quote` 与 `OrderServiceImpl.createOrder`。纯新增 8 列、**不动任何存量数据**（脚本自带校验 B/C/D：费用列全 0、金额汇总未变、坐标与楼层全 NULL）。**2026-09-17 已在测试库执行并验证幂等（二次执行全 skip）；⚠️ 真实库尚未执行** |
| 20 | `migration_v35_station_delivery_config.sql` | **站级配送计费配置（Phase 1 · P1-B）**：新增 `station_delivery_config`（起送量 / 配送范围 / 运费 / 楼层费，规格见 `docs/design/17`）。只存**经营参数**，算出来的钱落 `orders.delivery_fee` / `floor_fee`（下单快照）—— 站长改配置不能改到历史订单的金额。**一行一个水站；没有行 = 没配过**，代码用 `StationDeliveryConfig.defaults()` 兜底成「全 0、不拦单、只提示」，所以**存量水站的下单行为一个字都不变**。门槛处理方式三选一（WARN 仅提示 / REJECT 不接单 / FEE 加收费用），**默认 WARN** —— 默认绝不能是 REJECT（本仓欠桶硬拦 `MAX_OWED_BUCKETS` 就是按产品决定移除、改成只提醒不阻断的）。纯新增 1 张表、不动任何存量数据。**2026-09-17 已在测试库执行并验证幂等（二次执行全 skip）；⚠️ 真实库尚未执行** |
| 21 | `migration_v36_ticket_package_and_lot.sql` | **水票档位套餐 + 批次单价快照（Phase 1 · P1-D）**：新增 `ticket_package`（站级档位：10/20/100 张一组，**定价结构不是促销引擎**）与 `ticket_lot`（照抄 `customer_barrel_lot` 的批次模型）；`ticket_account` 加 `right_amount`（Σ 剩余×批次单价）、`ticket_record` 加 `unit_price`/`ticket_lot_id`、`payment_record` 加 `ticket_package_id`。**为什么必须有批次**：档位意味着票价分段，站长改一次档位价之后，「客户账户里那 100 张票值多少钱」与「退票按什么价退」就无从回答 —— 桶账早就用 `customer_barrel_lot.unit_price` 解决过同一问题（"2026 年 30 元买的，2027 年退就退 30 元"），水票照抄。**单价取实付均价**（`payment_record.amount / ticket_qty`），不取站级单张价 —— 用后者记，客户按 8 元买的票会按 9 元退，水站每张多退 1 元。**存量回填**：已有余额的账户按「站级水票价 → product.ticket_price → product.price」推断单价生成批次并标记为推断值（退票需二次确认），脚本自带 3 项校验（E8 数量/金额等式 + 存量覆盖）。⚠️ 是**纯新增 + 存量派生**：不改任何既有列含义、不动任何水票余额。**2026-09-17 已在测试库执行（二次执行全 skip、校验 0 条不平）；⚠️ 真实库尚未执行** |
| 22 | `migration_v37_staff_earning_and_payroll.sql` | **配送员计件工资（Phase 2）**：新增 `staff_piece_rate`（站级计件单价，`product_id=0` = 该站默认价）、`staff_earning`（收益明细）、`staff_payroll`（结算单 草稿→已确认→已发放）。配送员工钱此前在系统里**完全不存在**，而真实水站就是按桶计件。四条口径：**发钱的是站长不是平台**（不做平台结算单/佣金/骑手钱包，也不做打款提现 —— 发钱是线下动作，系统只落 `paid_time`+`operator_id` 留痕）；**计件单位是桶不是单**且按 `order_item` 逐商品计；**方向由 kind 决定、调用方一律传正数**（唯一例外 `ADJUST`）；**归属站 = 履约站**。⚠️ `staff_earning.auto_uk` 的 **NULL 是有意的**（NULL = 人工调整，本来就允许无限多条），自动收益幂等由该生成列唯一键兜底 —— 与 `uk_ticket_consume` 那个"NULL 导致零保护"的坑形状相同但**语义相反**，别当成同一个错误去"修"。⚠️ **工钱不进客户对账**，走独立等式 **E-PAY**，告警分级是 `OPERATION` 而不是 `SYSTEM`。纯新增 3 张表、**不动任何存量数据**（脚本自带 E-PAY/孤儿/空表三项校验）。**2026-09-17 已在测试库执行（二次执行全 skip、校验 0 条不平）；⚠️ 真实库尚未执行** |
| 23 | `migration_v39_inventory_cost_price.sql` | **进货成本（Phase 3）**：`inventory` 加 `cost_price`（站级当前进货成本，NULL=未填）。原因：站长**看不到自己赚多少** —— 此前全仓 `cost_price\|成本\|进价\|毛利` 零命中，而"这桶水进价多少、这单赚几块"是水站最日常的问题。⚠️ 成本落在 **`inventory`（站×商品）** 而不是 `product`（通用库）：进货价是每个水站自己的事，放通用库等于替站长定价、也等于把 A 站的成本泄露给 B 站。⚠️ **刻意不做**供应商表/采购单/应付账款/批次成本核算（`docs/design/16` §D4）—— 代价是**成本改了之后历史毛利会用新成本重算**，接口里已把这件事写进 `costBasisNote` 让前端原样展示。⚠️ 编号用 **v39**：`v38` 已被商品图片库工作流占用（工作区内、尚未入库）。纯新增 1 个可空列、**不动任何存量数据**（脚本自带三项校验）。**2026-09-17 已在测试库执行（二次执行全 skip）；⚠️ 真实库尚未执行** |

> 以上脚本均为**幂等**（`information_schema` 预检 + `PREPARE`），可重复执行。
> 执行方式务必带库名：`mysql -uroot <库名> < 脚本.sql`。
>
> **真实库（`aquaflow`）已走完本清单（2026-09-14 实测；2026-09-15 补 v29、2026-09-16 补 v30/v31、2026-09-17 补 v32；⚠️ v33 见第 18 条、v34 见第 19 条，**均尚未在真实库执行**）**：第 1〜7 步、9〜13 步确认生效，
> 第 8 步（v23）此前**从未执行**，已于 2026-09-14 补跑。核对方法（只读）：
> 逐个对象查 `information_schema`（表 / 列 / `STATISTICS`），不要凭"应该跑过了"推断——
> v23 的遗漏正是这样被发现的：真实库仍存在唯一键 `uk_payment_order_status`，
> 且 `uk_ticket_consume` 未纳入 `source`，导致取消已付款订单与退水票在真实库上必然失败
> （`aquaflow_test` 由 `schema.sql` 建库，因此测试全绿、问题只在真实库暴露）。
>
> ⚠️ 已知例外（尚未整改，执行前请先人工确认）：`migration_aq056_payment_fk.sql` 中的 `ADD CONSTRAINT` 非幂等，
> 重跑会报 1061；且本清单第 3 步依赖 `customer_owed_barrel` 旧表名，而第 10 步已将其改名——顺序存在冲突。
> 详见 `docs/audit/2026-09-13-全方位评价.md` 的 P0-6。

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
