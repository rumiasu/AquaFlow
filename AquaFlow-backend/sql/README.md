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
| 18 | `migration_v33_payment_idempotency.sql` | **在线购票幂等键**：`payment_record` 加 `idempotency_key varchar(64)` + 唯一键 `uk_payment_idempotency(customer_id, idempotency_key)`。原因：在线购票是**无订单支付**（`order_id` 为 NULL），`uk_payment_active_order` 建在生成列 `active_order_id` 上、**MySQL 唯一键中 NULL 互不冲突 → 该路径零保护**；连点两次「买票」落两条待收款流水，站长在「待确认收款」看到两行、两条都确认即**入账两次水票**（`confirmPayment` 的乐观锁只保证**单条**流水确认一次，管不住重复流水）。唯一键必须带 `customer_id` —— 只按 token 唯一会让客户端传别人的 token 取回别人的支付记录（跨客户泄露）。纯新增 1 列 + 1 个唯一键，存量行 `idempotency_key` 全为 NULL → **对存量数据与全部订单支付零影响**。**2026-09-17 已在测试库执行并验证幂等（二次执行全 skip）；**2026-09-18 已在真实库执行（二次执行全 skip、校验 0 条不平）** |
| 19 | `migration_v34_delivery_fee_and_floors.sql` | **配送计费与配送范围的前置字段（Phase 0，只加列、不改任何业务逻辑）**：`station` 加 `lat`/`lng`（配送范围要算「站点→客户」距离，而原先只有 `address` 有坐标）；`address` 加 `floor`/`has_elevator`（楼层费依据，见 `docs/design/17` §4.4 —— **NULL=未确认 与 0=确认无电梯必须区分**，混同会向客户乱收费）；`orders` 与 `payment_record` 各加 `delivery_fee`/`floor_fee`。⚠️ 费用**绝不并入 `water_amount`**（污染水费口径）或 **`deposit_amount`**（那是**可退押金**，退款路径按它释放押金余额，混入会导致取消订单**多退钱**）。本版 `total_amount` 的构成**一个字都没改**（仍是 水费 + 押金）；把费用并入总额是 Phase 1 的事，届时必须同改 `PaymentServiceImpl.quote` 与 `OrderServiceImpl.createOrder`。纯新增 8 列、**不动任何存量数据**（脚本自带校验 B/C/D：费用列全 0、金额汇总未变、坐标与楼层全 NULL）。**2026-09-17 已在测试库执行并验证幂等（二次执行全 skip）；**2026-09-18 已在真实库执行（二次执行全 skip、校验 0 条不平）** |
| 20 | `migration_v35_station_delivery_config.sql` | **站级配送计费配置（Phase 1 · P1-B）**：新增 `station_delivery_config`（起送量 / 配送范围 / 运费 / 楼层费，规格见 `docs/design/17`）。只存**经营参数**，算出来的钱落 `orders.delivery_fee` / `floor_fee`（下单快照）—— 站长改配置不能改到历史订单的金额。**一行一个水站；没有行 = 没配过**，代码用 `StationDeliveryConfig.defaults()` 兜底成「全 0、不拦单、只提示」，所以**存量水站的下单行为一个字都不变**。门槛处理方式三选一（WARN 仅提示 / REJECT 不接单 / FEE 加收费用），**默认 WARN** —— 默认绝不能是 REJECT（本仓欠桶硬拦 `MAX_OWED_BUCKETS` 就是按产品决定移除、改成只提醒不阻断的）。纯新增 1 张表、不动任何存量数据。**2026-09-17 已在测试库执行并验证幂等（二次执行全 skip）；**2026-09-18 已在真实库执行（二次执行全 skip、校验 0 条不平）** |
| 21 | `migration_v36_ticket_package_and_lot.sql` | **水票档位套餐 + 批次单价快照（Phase 1 · P1-D）**：新增 `ticket_package`（站级档位：10/20/100 张一组，**定价结构不是促销引擎**）与 `ticket_lot`（照抄 `customer_barrel_lot` 的批次模型）；`ticket_account` 加 `right_amount`（Σ 剩余×批次单价）、`ticket_record` 加 `unit_price`/`ticket_lot_id`、`payment_record` 加 `ticket_package_id`。**为什么必须有批次**：档位意味着票价分段，站长改一次档位价之后，「客户账户里那 100 张票值多少钱」与「退票按什么价退」就无从回答 —— 桶账早就用 `customer_barrel_lot.unit_price` 解决过同一问题（"2026 年 30 元买的，2027 年退就退 30 元"），水票照抄。**单价取实付均价**（`payment_record.amount / ticket_qty`），不取站级单张价 —— 用后者记，客户按 8 元买的票会按 9 元退，水站每张多退 1 元。**存量回填**：已有余额的账户按「站级水票价 → product.ticket_price → product.price」推断单价生成批次并标记为推断值（退票需二次确认），脚本自带 3 项校验（E8 数量/金额等式 + 存量覆盖）。⚠️ 是**纯新增 + 存量派生**：不改任何既有列含义、不动任何水票余额。**2026-09-17 已在测试库执行（二次执行全 skip、校验 0 条不平）；**2026-09-18 已在真实库执行（二次执行全 skip、校验 0 条不平）** |
| 22 | `migration_v37_staff_earning_and_payroll.sql` | **配送员计件工资（Phase 2）**：新增 `staff_piece_rate`（站级计件单价，`product_id=0` = 该站默认价）、`staff_earning`（收益明细）、`staff_payroll`（结算单 草稿→已确认→已发放）。配送员工钱此前在系统里**完全不存在**，而真实水站就是按桶计件。四条口径：**发钱的是站长不是平台**（不做平台结算单/佣金/骑手钱包，也不做打款提现 —— 发钱是线下动作，系统只落 `paid_time`+`operator_id` 留痕）；**计件单位是桶不是单**且按 `order_item` 逐商品计；**方向由 kind 决定、调用方一律传正数**（唯一例外 `ADJUST`）；**归属站 = 履约站**。⚠️ `staff_earning.auto_uk` 的 **NULL 是有意的**（NULL = 人工调整，本来就允许无限多条），自动收益幂等由该生成列唯一键兜底 —— 与 `uk_ticket_consume` 那个"NULL 导致零保护"的坑形状相同但**语义相反**，别当成同一个错误去"修"。⚠️ **工钱不进客户对账**，走独立等式 **E-PAY**，告警分级是 `OPERATION` 而不是 `SYSTEM`。纯新增 3 张表、**不动任何存量数据**（脚本自带 E-PAY/孤儿/空表三项校验）。**2026-09-17 已在测试库执行（二次执行全 skip、校验 0 条不平）；**2026-09-18 已在真实库执行（二次执行全 skip、校验 0 条不平）** |
| 23 | `migration_v38_platform_product_images.sql` | **平台通用库预设商品图（挂图，不改名）**：把 `product.owner_station_id IS NULL` 的通用库商品 `image_object_name` 从 NULL 改为**包内本地资源路径** `/assets/product/barrel-water.webp`。⚠️ **纯数据迁移、无 DDL**（该列早已存在）。⚠️ **只挂图、不改 name/brand/spec** —— 原计划"改名成通用名"被推翻：`product_id` 被 **15 张表**引用（含 `order_item` / `deposit_record` / `ticket_record` / `customer_barrel_*`），且 `inventory` 已有 2 条真实选用记录（station_id=1, product_id=1/2），改名会**篡改历史订单与资产记录的留痕**。⚠️ 值以 `/` 开头即视为**本地资源路径**，由 `util/ProductImageResolver.resolve()` 原样直通、不经 COS；将来接 COS 只需把该列换成对象键，**代码零改动**。若把本地路径丢给 `CosUtil.generatePublicUrl`，COS 未配置时异常被吞 → `imageUrl` 恒 null → 前端静默显示占位图且不报错。UPDATE 带 `image_object_name IS NULL` 条件故天然幂等。**2026-09-17 已在真实库执行**（执行前已 `mysqldump` 备份至 `backup/aquaflow_before_v38_20260917-224458.sql`；影响 2 行，二次执行影响 0 行；校验：站内自定义商品未被波及、product 总行数 2 未变、`preset_uk` 保持原值） |
| 24 | `migration_v39_inventory_cost_price.sql` | **进货成本（Phase 3）**：`inventory` 加 `cost_price`（站级当前进货成本，NULL=未填）。原因：站长**看不到自己赚多少** —— 此前全仓 `cost_price\|成本\|进价\|毛利` 零命中，而"这桶水进价多少、这单赚几块"是水站最日常的问题。⚠️ 成本落在 **`inventory`（站×商品）** 而不是 `product`（通用库）：进货价是每个水站自己的事，放通用库等于替站长定价、也等于把 A 站的成本泄露给 B 站。⚠️ **刻意不做**供应商表/采购单/应付账款/批次成本核算（`docs/design/16` §D4）—— 代价是**成本改了之后历史毛利会用新成本重算**，接口里已把这件事写进 `costBasisNote` 让前端原样展示。⚠️ 编号用 **v39**：`v38` 已被商品图片库工作流占用。纯新增 1 个可空列、**不动任何存量数据**（脚本自带三项校验）。**2026-09-17 已在测试库执行（二次执行全 skip）；**2026-09-18 已在真实库执行（二次执行全 skip、校验 0 条不平）** |

| 25 | `migration_v40_customer_privilege.sql` | **客户特权（Phase 3）**：新增 `customer_privilege`（站长在客户画像里逐个授予的特权，配 `constant/PrivilegeType`）。产品决定是**不做个人/企业客户的显式区分**，差异化一律落到"站长给这个客户开特权"，所以没有 `customer.type` 这类横向分层字段 —— 要按客户分层就加一行特权，不要改客户表。⚠️ **未实现的类型（折扣率 / 免配送次数 / 允许退票）在授予时直接拒绝**、也不出现在 `grantableTypes` 里，避免出现"配了也不生效"的悬空开关：`PrivilegeType.isImplemented`（能不能授予）与 `isMoneyAffecting`（该不该用账户+流水实现）是两个必须分开的概念。已接进计费链路的是 `NO_MIN_ORDER`（免起送门槛）。纯新增 1 张表、**不动任何存量数据**。**2026-09-17 已在测试库执行并验证幂等；**2026-09-18 已在真实库执行（二次执行全 skip、校验 0 条不平）** |

| 26 | `migration_v41_drop_orders_batch_id.sql` | **删除 `orders.batch_id`（挂空列清收，`docs/design/20` §6）**：该列恒为 NULL，全项目无 `batch` 表、无读写点（复核含 `*.xml` mapper —— 本仓曾因只 grep `*.java` 把 `first_barrel_order` 误判成孤儿列），其余命中全在**不执行的历史文件**里（`sql/archive/**`、`reset_data.sql`）。⚠️ **破坏性 DROP，必须先上代码再执行**（`entity/Orders.java` 删字段、`schema.sql` 删列）—— 老代码若还有以该列名的 SELECT/INSERT，DROP 之后立刻 1054。⚠️ **脚本自带护栏**：列存在时先数非 NULL 行，**有值就中止 DROP**（挂空列的前提不成立，宁可留着），列已不存在则 skip。⚠️ **连带改动**：`migration_v24` 里那条 `MODIFY COLUMN batch_id`（修乱码注释）已删除 —— MySQL 在**解析期**校验列名，留着会让 v24 在已跑过 v41 的库上必报 1054，把一个只改注释的脚本变成不可重复执行的脚本。**2026-09-18 已在真实库执行**（执行前 `mysqldump` 备份至 `backup/aquaflow_before_v33-v40_20260918-094707.sql`；`orders` 20 行、合计 830.00 未变；二次执行 skip；v24 在 v41 之后再跑无错误） |

| 27 | `migration_v42_piece_rate_two_items.sql` | **工资只有两项（v42）**：`staff_piece_rate` 删掉 `return_bucket_amount` / `per_order_amount` / `penalty_per_bucket` —— 站长实际只用「每桶计件价 + 楼层补贴」，回桶与单量能并进桶价，而"少收空桶自动扣钱"会引发劳资纠纷（本仓对欠桶一贯**只提醒不扣钱**）。⚠️ 删的是**配置项不是记录**：少收空桶仍有订单 `barrel_discrepancy` + 异常单留痕，只是不再折算成扣款；`EarningKind` 的 RETURN_BUCKET/ORDER_BONUS/PENALTY 三个常量**保留但停用**（历史流水还要显示）。⚠️ 破坏性 DROP，先上代码再执行；脚本第 1 步会把这三列**非零**的存量配置打印出来留档（真实库当时该表为空），执行前已 `mysqldump` 到 `backup/aquaflow_before_v42_20260918-124503.sql`。**2026-09-18 已在真实库执行**（二次执行全 skip；`staff_earning` / `staff_payroll` 均 0 行，金额流水未受影响） |
| 28 | `migration_v43_floor_report.sql` | **配送员上报楼层 + 楼层凭证（v43）**：`orders` 加 `reported_floor int NULL`（选填上报），`order_image.type` 注释补取值 **3 楼层凭证**（只改注释）。为什么：楼层补贴是给配送员的钱，只有他知道自己爬了几层 —— 只认客户在地址里填的楼层，等于拿别人的话给自己发工资。填了以他报的为准、没填沿用地址；与地址不一致时在收益明细 `note` 里标记（防虚报的痕迹）；照片不强制，配送员与站长都可传。⚠️ **不影响向客户收的楼层费**（下单时按地址快照）。纯加 1 个可空列、存量订单全 NULL = 口径与升级前完全一致。**2026-09-18 已在真实库执行**（执行前 `mysqldump` 到 `backup/aquaflow_before_v43_20260918-151923.sql`；orders 20 行、合计 830.00 未变；二次执行 skip） |
| 29 | `migration_v44_earning_items.sql` | **站长自定义工资条目（v44）**：新增 `staff_earning_item`（站级加项/扣项字典，`uk(station_id,name)` —— 同站两个"高温补贴"会让月底汇总直接对不上账），`staff_earning` 加 `item_id`（NULL=非按条目录入）与 `item_name`（**写入时的名称快照**：条目改名不改写已经发生过的工资历史，与 `order_item.product_name` 同口径）。为什么：v42 之后工资只有两项自动收益，其余加减钱只能走自由文本的人工调整 —— 每次重新打字、月底汇总不出来、打错字也没人拦，而这笔钱**会真的从人家工资里扣掉**。⚠️ **方向由条目决定：传了 `itemId` 就只收正数**（负数直接拒，不静默取绝对值）；⚠️ **被流水用过的条目只能停用、不能删**（删掉它，那些流水在界面上就成了"无条目的人工调整"）；⚠️ 条目**不参与任何自动计算、不进对账等式**，金额照旧走 E-PAY，`itemSummary` 只汇总带条目的流水（**不等于**未结合计）。同批修掉一个越权：`POST /api/manager/payroll/adjust` 原本完全不校验 `staffId` 与本站的关系，而「我的工资」自助查询按设计只按 `staff_id` 过滤 → 任何站长传一个别站配送员 id 就能改那个人的未结工资；现按「本站员工 **或** 在本站有过收益」的**并集**放行（跨站外派照旧能记账）。纯新增 1 张表 + 2 个可空列，存量流水全 NULL = 口径与升级前完全一致（脚本自带 4 项校验）。**2026-09-18 已在测试库执行（二次执行 skip）** |
| 30 | `migration_v45_file_info_station.sql` | **file_info 站隔离（v45）**：补 `station_id`（**NULL = 平台级文件、全站可见** —— 这个 NULL 有语义，不是脏数据）+ `idx_file_station`；存量按 `uploader_id → staff.station_id` 回填，**查不到就保持 NULL**（宁可少隔离几条历史记录也不猜 —— 猜错就是把 A 站文件暴露给 B 站）。为什么：`GET /api/files` 此前**没有任何水站过滤**，而本表连 `station_id` 都没有 → 任何站长 token 都能列出**全部水站**的文件名与 COS 预签名 URL（跨租户泄露，2026-09-16 死端点评估 §6.4/§8.23 登记为"接线前必须先修"）。配套：`FileInfoMapper` 的 `listAll()/listByCategory()`（无站过滤）换成 `listVisible(stationId)/listVisibleByCategory(stationId, category)`（本站 **或** 平台级），上传按登录态写归属站，删除加"他站文件无权操作"。纯新增 1 个可空列 + 1 个索引 + 1 次有条件 UPDATE。**2026-09-18 已在测试库与真实库执行**（真实库执行前 `mysqldump` 到 `backup/aquaflow_before_v45_20260918-165302.sql`；回填 1 行、orders 20 行/830.00 未变；二次执行 skip） |
| 31 | `migration_v46_feedback_anonymous.sql` | **客户反馈匿名提交（v46）**：`feedback` 加 `anonymous tinyint NOT NULL DEFAULT 0`（0 实名 / 1 匿名）。为什么：反馈此前只有实名一种形态，而站长端「客户反馈」页会显示客户姓名 —— 客户要报"某配送员态度差""水站乱收费"这类**针对水站本身**的问题时，实名等于**当着被投诉方的面投诉他**，这类问题就永远不会被报上来。⚠️ **匿名 = 「站长不知道是谁」，不是「前端不显示」**：脱敏钉在**查询 SQL** 上（`FeedbackMapper.listCustomerFeedbackByStation` 用 `CASE WHEN f.anonymous = 1 THEN NULL` 把 `customer_id` 与 JOIN 出的姓名一起置空），**不在 Java 里 setCustomerId(null)** —— Java 置空只保护当前这一版这一个调用点，下一个人把 `c.name` 加回 select 就静默泄露（编译/运行都不报错）。⚠️ `content` / `category` / `contact` / `create_time` **照常下发**：匿名保护的是身份不是内容，`contact` 由客户自己选填（留了就是他主动同意被联系）。⚠️ 顾客自己的 `GET /api/feedback/my` **不脱敏**（那是他自己的记录，脱了「我的反馈」就变空白）。⚠️ 顾客端**自动错误上报保持实名**（报障的价值是站长能复现/追问），只有「客服/反馈」页的手动提交才有匿名开关。⚠️ 站长端列表**刻意不 select `anonymous`**（响应里该字段恒为 null），页面只按"有没有姓名/客户号"显示「匿名顾客」，免得前端据标志位自造身份文案。纯新增 1 个 NOT NULL DEFAULT 0 列、**不动任何存量行**（存量全落 0 = 实名，可见性与升级前完全一致）。**2026-09-18 已在测试库执行**（两遍：第一遍 ADD，第二遍 `skip: feedback.anonymous 已存在`；`HEX(COLUMN_COMMENT)` 与 `schema.sql` 逐字节一致） |
| 32 | `migration_v47_order_settle_station.sql` | **订单「结算站」显式化（v47）**：`orders` 加 `settle_station_id bigint NULL`（结算站 = **本单营收归谁**：水费 + 配送费 + 楼层费）+ `idx_orders_settle_station`；存量按 `coalesce(delivery_station_id, station_id)` 回填（= 升级前代码一直在推导的那个值，所以回填后看板/客户画像/毛利/应收的数字与升级前**逐字一致**）。为什么：2026-09-18 产品裁定「配送费要改、**水费也一起给实际配送站**、毛利报表之类的也要**本站化**」—— 而"营收归谁"此前**没有任何一列表达**，每个查询各自推导：看板/客户画像用 `coalesce(delivery_station_id, station_id)`（履约站口径），毛利表与应收账款却用 `station_id`（归属站口径）→ 同一笔钱在两张报表里归两个站，跨站外派单必然对不上账，而两种写法**都能编译、都不报错**。取值：下单 = `station_id`；抢单/定向外派 = 履约站；取消外派/召回/退回池/指定退回-同意 = 回 `station_id`。⚠️ **押金、水票、桶权益仍按归属站**（`customer_deposit_account`/`ticket_*`/`customer_barrel_*` 一行不动 —— 那是"客户买在哪个站的资产"，与"这单营收归谁"是两件事）。⚠️ 读取一律 `coalesce(settle_station_id, delivery_station_id, station_id)`，但**这是防御不是常态**：正常路径必须写本列，全靠回退等于让它退化成装饰。纯新增 1 个可空列 + 1 个索引 + 1 次列注释订正（`orders.station_id` 原注释写着"交易/营收归属"，v47 后营收不再归它 —— 只改注释、逐字复刻 `bigint DEFAULT NULL`，注释已一致则 skip）+ 1 次有条件 UPDATE（只写 `is null` 的行，二次执行 0 行）。**2026-09-18 已在测试库与真实库执行**（真实库执行前 `mysqldump` 到 `backup/aquaflow_before_v47_20260918-204427.sql`；回填 20 行；二次执行三个 skip + 回填 0 行；核对 0 不一致 / 0 空值，orders 20 行/830.00 与 47 表未变） |
| 33 | `migration_v48_customer_offline_payment_limits.sql` | **货到付款的客户级约束（v48）**：`customer_station_config` 加 `offline_payment_single_limit decimal(10,2) NULL`（**NULL = 不限**，用于"特殊允许的客户可以大额"）+ `offline_payment_allow_first_order tinyint NOT NULL DEFAULT 0`（**默认 0 = 首单不给货到付款**）。为什么：2026-09-18 产品裁定「货到付款…如果做也要对**首单和大额**订单设限（特殊允许的客户可以大额）」，且这些约束「最好是给站长定，在设置是否允许货到付款时**就给弹出来**」—— 此前只有"开关"一层，等于要么全放、要么全禁。**"欠款即停"不加列**：判据是"该客户在本站还有逾期未结的现金单"（`payment_status = 1 且 status <> 5 且 due_date < 今天`），用现有列现算。纯加 2 列、无 UPDATE/DELETE，存量客户 `single_limit = NULL`（= 不限，行为不变）、`allow_first_order = 0`（**这是新引入的约束**：升级后站长需在界面上给"已合作但系统里还没订单的老客户"逐个放开，属产品要的效果、不是数据问题）。唯一判据 `PaymentServiceImpl.offlinePaymentBlockReason`，下单与报价都调它。**2026-09-18 已在测试库与真实库执行**（真实库执行前 `mysqldump` 到 `backup/aquaflow_before_v48_20260918-234717.sql`；首跑加 2 列、二次执行两个 skip；核对存量未变：`customer_station_config` 1 行、`orders` 20 行/830.00，与执行前一致） |
| 34 | `migration_v49_drop_cod_limits.sql` | **撤回 v48 的「首单是否放行 / 单笔上限」两列（v49）**：产品 2026-09-18 第四批裁定「既然目前还由站长审核，这两个先不做了」—— 货到付款本来就只能由站长**逐个客户**开通（默认关闭、无批量开关），站长审核已是第一道闸，再加"首单/额度"两层是重复设防。**保留「欠款即停」**（判据是"该客户在本站有逾期未结的现金单"，用现有列现算，不依赖被删的两列）。⚠️ 脚本自带护栏：**只要有一行配过这两项（`single_limit` 非空 或 `allow_first_order ≠ 0`）就中止**，不执行 DROP。回滚 = 重新执行 v48（纯加列、幂等）。 |
| 35 | `migration_v50_enterprise_apply.sql` | **企业身份申请（v50）**：新增一张表 `customer_enterprise_apply`（客户申请 → 站长审核）。产品口径「企业和普通用户分离…不建议做成入口，在订水时检测到大额订单，弹出确认是否是企业，可申请企业身份」—— 所以**不做独立入口**：报价时金额超过阈值且客户还是个人身份，响应里带一句 `enterpriseHint`，客户据此提交申请；站长在客户列表里审核，通过后 `customer.customer_type` 置 2 并把企业资料写进既有的 `company_info`（唯一键 `uk_company_customer`）。**整个功能由 `app.enterprise.enabled` 控制（默认关闭，环境变量 `ENTERPRISE_IDENTITY_ENABLED`）**：关掉时报价**不再下发该字段**、`/api/enterprise/**` 一律返回业务错误「企业身份功能当前未开启」（不是把入口藏起来但接口还能调），站长待审列表返回空列表而不报错；关掉**不影响已经是企业身份的历史客户**。纯加一张表，无 UPDATE/DELETE。用例 `EnterpriseIdentityIntegrationTest`（开关开）+ `EnterpriseIdentityDisabledIntegrationTest`（开关关）。⚠️ 阈值口径已由 v51 重做（只算水），见下一行。 |
| 36 | `migration_v51_station_enterprise_config.sql` | **站级「企业身份提示阈值」（v51）**：新增一张表 `station_enterprise_config`（一站一行）。把 v50 的「订单总额 ≥ 500 元」改成**只算水**的两条口径 —— 产品 2026-09-19 第五批裁定「企业的只看水，押金不算，水超过 30 桶就可以吧。也可以由水站设置」+「桶数或金额，站长也可以自行设置范围，可以任选其一也可都选」：① 桶数 = 本单桶装水（`product.category=1`）数量合计；② 金额 = 本单**水费**（不含押金/配送费/楼层费）。**两项都配 = 任一满足即提示**，只配一项 = 只按那一项。⚠️ **「没有行」与「有行但两项都空」语义不同**：前者 = 还没配过 → 用平台默认（`app.enterprise.large-order-barrels`，默认 **30 桶**）；后者 = 站长明确表示**本站不提示**。纯加一张表，无 UPDATE/DELETE，**不改任何既有表列、不动存量数据**。⚠️ 平台级总开关**没有前端入口**（产品：「平台级的暂时不做前端可视化了」），界面上能改的只有这张表的站级阈值。用例 `EnterpriseIdentityIntegrationTest`（10 例，含押金不算/按站配置）、`EnterpriseIdentityDefaultThresholdIntegrationTest`（平台默认 30 桶边界）、`EnterpriseIdentityDisabledIntegrationTest`。 |
| 37 | `migration_v52_jinan_barrel_catalog.sql` | **济南主流桶装水入库（v52）**：把平台通用库从 **2 行扩到 26 行** —— 站长「选品」界面此前只有 2 条不带水种的泛化种子（`农夫山泉 19L 桶装水` / `娃哈哈 18.9L 桶装水`），而济南货架真实在卖的是普利思 / 泉娃 / 百脉泉 / 趵突泉 / 百圣泉 / 冰露 / 涵露 / 七星台 / 泉城茗水 / 爱茶说 / 泰山甘泉 / 好山好水 + 全国品牌（农夫山泉 / 娃哈哈 / 怡宝 / 景田百岁山 / 乐百氏 / 崂山）。**商品名一律带水种**（`{品牌} {水种} {规格} 桶装水`）—— 同品牌同规格下纯净水与天然矿泉水是**不同的品、价格差近一倍**（普利思 12 vs 16）；而**图仍用 v38 那张通用图**（水种差异体现在名字与价格，不体现在视觉）。⚠️ **改动存量两处**：① 既有 2 行**改名补水种**（安全前提已逐项核实：`order_item` 有 `product_name_snapshot`/`brand_snapshot`/`spec_snapshot` 三个快照列且 20 条历史订单全部已快照，`OrderMapper`/`DashboardMapper`/`GrossProfitMapper` 展示全走快照，引用按 `product_id` 不按名；执行后实测快照**仍是旧名**。唯一可见副作用是 `CustomerMapper.listFavoriteProducts` 用 `ifnull(p.name, …)` **活名优先** → 客户画像「常用商品 TOP3」标签跟着变新名）；② 该 2 行**押金 30 → 50**（依据两个独立来源：水立多济南站、爱企查"大品牌约50"），⚠️ **这是唯一影响真实金额的改动** —— station 1 的 `inventory.deposit_price` 为 NULL，按 `PriceUtil.calcDeposit` 回落到 `product.deposit`，客户首单缺桶押金由 30/桶 变 50/桶。卖法**只落到既有列**（`price` 参考零售价 / `deposit` 押金 / `ticket_enabled`+`ticket_price` 水票开关与**面值**）；`ticket_price` 设为等于零售价 —— 票的面值就是"一张抵一桶的钱"，而「买十赠一」是**赠**不是**降价**，归宿是站级 `ticket_package`（v36）。**刻意不做**：一次性桶（本仓无任何列能表达「一次性」，判据只有 `category=1`，硬建行会让它计入 `totalNeededBuckets` 并向客户要回桶 → **凭空生成桶异常单**；需先加"复用方式"维度并同改 `OrderServiceImpl`/`BarrelLedgerService`/回桶核对）、站级水票档位（v36）、起送量/运费（v35）—— 后两者是站长自己的经营参数。**一行没动 `inventory`**。⚠️ **编号说明**：起草时曾命名 v50，但 v50/v51 已被并行工作流占用，故顺延 v52（内容与已执行数据无差异）—— 教训：写迁移前先 `ls migration_v*.sql` 看最大编号，别凭本清单尾部猜。**2026-09-19 已在真实库执行**（执行前 `mysqldump` 到 `backup/aquaflow_before_v52_20260919-190753.sql`；新增 24 行，二次执行新增 0 行；核对 26 行全部 `category=1`+本地图+`preset_uk` 非空+0 重复品；`orders` 20 行/830.00 未变、`order_item` 快照未回改、`inventory` 4 行未变） |
| 38 | `migration_v53_jinan_brand_expansion.sql` | **济南水站货架扩充 + 一次性桶归类（v53）**：通用库 **26 → 52 行**、品牌 **34 个**。按「每个品牌能查到的都写进去」补齐 8 个济南本地新品牌（云恬 / 畅饮吧 / 一山一水 / 山下泉 / 天地矿泉 / 圣境甘泉 / 惬尔 / 艺韵）+ 8 个全国品牌（雀巢 / 泉阳泉 / 恒大 / 雪峪系 / 象牙山冰点 / 润田翠 / 阿尔卑斯 / 乐百氏）。**收录规则（本轮新立）**：只收济南水站/配送商**明确在售**的品牌 —— 排除①无济南在售证据（康师傅/屈臣氏/昆仑山）②B2B 定制代工（鹤知源/甘雨露/普利森/达利园）③配送平台（水鲤鲤/好柿到家/水立多，是渠道不是水品牌）④其他城市本地品牌（北纬39度/汇云山泉/悦玛泉）⑤证据不足（艾珂）。★ **核心决定：一次性桶归入 `category=2`（瓶装水）** —— 划分口径「规格 < 16L 记为一次性桶、≥ 16L 记为循环桶」，有直接证据的以证据为准。理由：`category=1` 是**唯一**的桶判据，一次性桶（免押、不回收）走它会被记账成"客户持有可回收桶"，而押金填 0 也拦不住。⚠️ **但实测推翻了"零桶逻辑"的推断**：下单侧确实干净（`delivery_bucket_qty=NULL` / 押金 0 / `first_barrel_order=0`，已由回归用例锁住），**配送侧不干净** —— `BarrelLedgerService.applyDelivery` 的「本单送出」按 `order_item` 数量统计、**完全不看 category**，`delta = delivered(2) − returned(0) − rightPurchase(0) = +2` → `over += 2` → `owed != 0` → **仍生成桶异常单**。⇒「归类为瓶装水」只挡住下单侧。**这是既有潜伏缺陷，与本迁移无关**（真实库 `inventory` 只有 category=1 商品 4 行、`customer_barrel_over` 全为 category=1 且 `over_qty=0`，从未触发；但任何瓶装水/饮水器售出并完成配送即会污染桶账）。发现与修复方向记录在 `DisposableBarrelLedgerBoundaryIntegrationTest` 的 `@Disabled` 用例（A: `applyDelivery` 内按 category 过滤；B: `completeDelivery` 以 `delivery_bucket_qty == null` 为闸，但混合单仍有洞）。⚠️ **改动存量 1 行**：`好山好水 天然水 15L 桶装水` **订正为** `好山好水 天然水 15L 一次性桶`（`category` 1→2、押金 50→0、`sort`→600）—— v52 按循环桶入库是错的，水立多章丘站在售页明确标注该品「免押金」。**2026-09-19 已在真实库执行**（执行前 `mysqldump` 到 `backup/aquaflow_before_v53_20260919-192349.sql`；订正 1 行 + 新增 26 行，二次执行全为 0 行；核对 52 行 = 桶装水 42 + 瓶装水 10，押金分布 41×50 + 1×30 + 10×0，`order_item` 20 行未变）。⚠️ **编号说明**：已先 `ls migration_v*.sql` 确认磁盘最大号为 v52 再取 v53（v38/v52 两次撞号的教训已落为纪律） |
| 39 | `migration_v54_ticket_record_account.sql` | **统一水票（v54）**：`ticket_record` 新增**可空列** `account_product_id` —— 「这一笔水票变动落在**哪个账户**」。产品裁定（2026-09-19）：「定制优先，**统一水票**是可以设置项，比如买 10 张都打 9.5 折、30 张统一 9 折这种，定制和统一都有的情况下，定制优先，**统一的仅在没有定制水票的桶时生效**」。统一票 = **站级通用票账户**，用 `product_id = 0` 表达（水票四张表的 `product_id` 都**没有外键**，实测 `KEY_COLUMN_USAGE` 为空 → 零结构障碍）；本站是否开通 = **有没有上架的 `product_id=0` 档位**（不新增开关列，避免多一处会与档位状态打架的真值）。扣票选账户的**唯一判据**是 `util/TicketScope`（定制余额 >0 → 只用定制，**不够也不拿统一票补差额**；否则若配了统一票且商品是桶装水 → 扣统一票）。★ **为什么要加这一列**：`uk_ticket_consume(order_id, product_id, source)` 用 `product_id` 保证"同一单同一商品只扣一次"，统一票的流水若也写 `product_id=0`，同单两个走统一票的商品会**撞唯一键 → 第二条被当成并发重复静默跳过 → 少扣一张票**；而且退款**必须回到当初扣的那个账户**（退款时刻余额已变，重新判定会算出另一个账户）。取值语义：`NULL` = 与 `product_id` 同账户（**存量行全部如此**，读侧把 NULL 解释为"= product_id"，与升级前逐字一致）、`0` = 站级通用票、其它 = 定制票账户（新流水一律显式写）。**纯新增可空列：不改任何既有列/含义、不动任何存量数据、不带索引**。用例 `UnifiedTicketIntegrationTest`。**2026-09-19 已在真实库 + `aquaflow_test` 执行**（执行前 `mysqldump` 到 `backup/aquaflow_before_v54_20260919_222556.sql`；列 `column_ready=1`、`total_records=0`、`records_with_account=0`，二次执行输出 `skip: ticket_record.account_product_id 已存在`） |
| 40 | `migration_v55_platform_product_brand_images.sql` | **平台商品图去统一化（v55）**：把「有官网实拍图且能精确对应」的 **5 行**从统一通用图换成品牌图 —— 普利思（纯净水/天然泉水/天然矿泉水，源 `www.pulisi.com`）+ 崂山（矿泉水/山泉水，源 `www.laoshan.com.cn`）。**背景**：用户 2026-09-19 指令「你之前把图都统一修了吗，改回来吧，每个都先用官网图，后期我再改」；核对属实 —— 通用库 52 行的图**完全一致**（v38 给最初的 2 行挂上 + v52/v53 的 INSERT 逐行写死同一张 `barrel-water.webp`），站长选品时无法凭图区分品牌。**匹配键用 `brand + name LIKE` 而非自增 id**（换库/重灌种子后依然成立）；每条 UPDATE 带「目标值≠现值」条件 → 幂等。**刻意只改 5 行**：素材库（`assets/product-preset-sources/`）只采了 5 个品牌且能用的仅两条线 —— 娃哈哈是「多规格组合图+纯黑底」不可用、农夫山泉只有 4L 一次性桶图（库里那行是 19L 循环桶）、景田百岁山只有瓶装与 4.5L 图；**其余 29 个品牌尚无素材**，其中济南本地小品牌多数**根本没有官网**（实测：云恬/一山一水/天地矿泉 只有招商软文与黄页；百脉泉 `sdbaimaiquan.com`、泉娃 `quanwa.com` 有官网，产品图待采）⇒「每行都挂自己品牌的官网图」需另行采集。⚠️ **前端配套**：两端包内各存 5 个文件（微信包内资源不能跨小程序共享），生成脚本 `assets/product-preset-sources/tools/build-brand-images.py`（可复跑）。⚠️ **版权**：图采自品牌官网、**未经授权**，仅适用于开发/内测，上线前必须替换。⚠️ **源图命名有历史错误**：`崂山18.9L-绿标.jpg` 实际是 3.78L 山泉小桶、`蓝桶.jpg` 实际是崂山山泉大桶、`蓝桶2.jpg` 实际是崂山矿泉水大桶 —— 脚本以**实际画面内容**为准。**2026-09-19 已在真实库执行**（执行前 `mysqldump` 到 `backup/aquaflow_before_v55_20260919-225104.sql`；5 行各影响 1 行，**二次执行全为 0 行**；校验 52 行守恒 = 47 行通用图 + 5 行品牌图，站内自定义商品未被波及）。⚠️ **编号说明**：已先 `ls migration_v*.sql` 确认磁盘最大号为 v54（并发工作流的水票账户）再取 v55 |
| 41 | `migration_v56_platform_catalog_sort_and_delist.sql` | **平台货架整理：按品牌重排 + 无品牌图的下架（v56）**：① 按**品牌拼音升序**重排 `sort`（同品牌内：桶装水在前 → 参考价升序 → id），用 `FIELD(brand, …)` **显式声明**品牌顺序 —— 不依赖数据库排序规则（中文拼音序在 `utf8mb4_general_ci` 下不可靠）。修掉的历史问题：v52/v53 的 sort 分段（10–280 / 290–450 / 600–680）导致**同一品牌被排到货架两端**（乐百氏 260 与 450、农夫山泉 210 与 600、怡宝 240 与 620、泉阳泉 390/630/640、艺韵 360 与 670），且 `sort=600` **有重复值**、排序不稳定。② 把**没有品牌官网图**的 **47 行** `status` 置 0（下架），只留 v55 换过图的 **5 行在架**（普利思 3 + 崂山 2）。⚠️ **下架的真实语义（读代码得到，非推测）**：`status = 0` 正是本仓既有下架口径（`ProductMapper.softDeleteOwned` 注释"软删除(停用/下架)"），**不做物理删除**（`product.id` 是 15 张业务表的锚点）；**客户端** `listSellableByStation` / `listSellableByStationWithInventory` 的 WHERE 都带 `p.status = 1` → 立即停售；**站长端** `CatalogServiceImpl.listCatalog` 的底层 SQL **不过滤 `product.status`**，故**同批改了 Service**（`listCatalog` 加过滤）：通用库商品须 `status = 1`，或**本站已选用**（`inventoryId != null`，否则站长看不到自己已上架的商品、无法再管库存与站级价）；本站自定义商品不受平台下架影响。⚠️ **副作用（须知情）**：station 1 已选用的 `product_id=1`（农夫山泉 19L）、`product_id=2`（娃哈哈 18.9L）**都没品牌图、本次一并下架** → 会**从客户端商城消失**；站长端仍可见（属「本站已选用」豁免），库存与历史订单不受影响；若要恢复售卖，把那两行 `status` 置回 1 即可（回滚 SQL 在脚本头部）。**2026-09-20 已在真实库执行**（执行前 `mysqldump` 到 `backup/aquaflow_before_v56_20260920-091750.sql`；校验：在架恰 **5 行且全部持有品牌图**、`sort` 无重复、行数守恒 **52**、二次执行下架影响 **0 行**） |
| 42 | `migration_v57_clear_selfmade_images.sql` | **清空「平台自制图」的引用（v57）**：商品图口径收紧为**只用品牌官网实拍图**。背景：用户 2026-09-20 指令「只要官网图片，你自己加的先不要」—— 两份自制图（`/assets/product/barrel-water.webp` 通用桶装水、`/assets/product/barrel-purified.webp` 饮用纯净水桶）已连同**两端小程序包内的物理文件**一起删除（各 2 份共 4 个文件），`constant/ProductImageKeys` 的预设清单同步清空（站长选图面板暂时为空，前端空列表显示「暂无预设图」）。⚠️ **为什么还要改数据**：库里仍有 **47 行**的 `image_object_name` 指着这两个路径 —— 文件没了就是**死链**，将来这些商品重新上架时界面会去加载不存在的包内资源（表现为**静默无图**、不报错、很难查），故置为 NULL 让"没有图"在数据层如实反映。**影响范围只有这 47 行且全部已下架**（`status = 0`），在架 5 行的品牌官网图不受影响。脚本带安全闸：若待清理行里**还有在架的**直接终止（防止把在架商品悄悄变成无图）。**不做物理删行** —— `product.id` 是 15 张业务表的锚点，仓规永不物理删商品。**2026-09-20 已在真实库执行**（执行前 `mysqldump` 到 `backup/aquaflow_before_v57_20260920-093138.sql`；影响 47 行，**二次执行影响 0 行**；校验：全仓无残留自制图引用、在架 5 行均持有官网图且无空图、行数守恒 52 = 5 在架 + 47 下架） |
> 以上脚本均为**幂等**（`information_schema` 预检 + `PREPARE`），可重复执行。
> 执行方式务必带库名：`mysql -uroot <库名> < 脚本.sql`。
>
> **真实库（`aquaflow`）已走完本清单（2026-09-14 实测；2026-09-15 补 v29、2026-09-16 补 v30/v31、2026-09-17 补 v32、**2026-09-18 补 v33~v37 / v39 / v40，并执行 v41、v42、v43、v44、v45、v47**（各批都先 `mysqldump` 到 `backup/`））**：第 1〜7 步、9〜13 步确认生效，
> 第 8 步（v23）此前**从未执行**，已于 2026-09-14 补跑。核对方法（只读）：
> 逐个对象查 `information_schema`（表 / 列 / `STATISTICS`），不要凭"应该跑过了"推断——
> v23 的遗漏正是这样被发现的：真实库仍存在唯一键 `uk_payment_order_status`，
> 且 `uk_ticket_consume` 未纳入 `source`，导致取消已付款订单与退水票在真实库上必然失败
> （`aquaflow_test` 由 `schema.sql` 建库，因此测试全绿、问题只在真实库暴露）。
>
> ⚠️ 已知例外（尚未整改，执行前请先人工确认）：`migration_aq056_payment_fk.sql` 中的 `ADD CONSTRAINT` 非幂等，
> 重跑会报 1061；且本清单第 3 步依赖 `customer_owed_barrel` 旧表名，而第 10 步已将其改名——顺序存在冲突。
> 详见 当时的审计报告（已移出仓库） 的 P0-6。

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

以下历史 migration 已被 `schema.sql` 吸收，不再需要在新环境执行。
⚠️ **2026-09-18 起，下表 19 个脚本已从 `sql/` 顶层移入 `sql/archive/`** —— 它们与在用的迁移混在同一目录时，
"别执行它们"只能靠这段文字提醒；移走后变成目录结构事实（顶层只剩要跑的）。表格保留为**索引**（含每条的判定理由），
文件本体在 `sql/archive/`。

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

| 43 | `migration_v58_station_ticket_discount.sql` | **水站「统一折扣」档位（v58）**：新增一张表 `station_ticket_discount`（`station_id` + `qty` 唯一），**只存折扣、不存价格**。产品 2026-09-20 澄清纠正 v54 的形态：「统一水票，**在站长端是特殊化的**，但在**用户端看起来没区别**，执行上**也不是统一定价**，而是**对应水怎么统一打折、统一打几折**的区别，**不是专门卖统一水票**」→ 「统一」统一的是**折扣率**（站级一处配：买 10 张 9.5 折、30 张 9 折），价格按**各款水自己的水票价**折算（农夫山泉按农夫山泉的价、娃哈哈按娃哈哈的价）。★ **为什么价格不落库**：一落库就会与"各款水的价"分叉 —— v54 把统一票做成"站级一个价（8.55/张、全站通用）"正是这么错的。判据链（唯一实现 `TicketTierService`）：该商品 `inventory.ticket_enabled=1` 且水票价 > 0 → 走**定制**（散买按站级水票价、档位按 `ticket_package` 的绝对价目表）；否则若它是**桶装水**且本站有**上架**的折扣档 → 走**统一折扣**；其余不能用票。**「有没有上架的档位」就是统一折扣的开关**（不另设开关列）。⚠️ **真实库实测 `ticket_record` / `ticket_account` / `ticket_package` 均为 0 行**（功能上线但没人配过、没人买过）→ 本次形态收口**零数据迁移成本**。纯加一张表 + 一个唯一键，不改任何既有表、不动存量数据。端点 `/api/ticket-discounts`（`GET` 列表 / `GET /presets` 平台预设 / `POST` upsert / `DELETE`，全 `@RequireRole("STATION_MANAGER")`）。用例 `StationTicketDiscountIntegrationTest`（6 例，含★「同一 9.5 折、20 元的水折出 19.00、12 元的水折出 11.40」）。⚠️ **执行记录订正（2026-09-20）**：本行原写「已在真实库执行」，但当日复验 `information_schema` 时 `aquaflow` 与 `aquaflow_test` **都只有 49 张表（恰好缺本表）**，且 `alert_log` id=4/5 留有 10:32 的实证 500（`Table 'aquaflow.station_ticket_discount' doesn't exist` ← `StationTicketDiscountMapper.countOnShelf`）—— 即当时**并未真正执行**（判据：以库为准，不以本表为准）。**已于 2026-09-20 11:51 补执行**：执行前备份 `backup/aquaflow_before_v58_20260920-115146.sql`；`table_ready=1`、`uk_station_ticket_discount` 2 列、`rows_now=0`；**二次执行同样干净**（幂等）；`HEX(TABLE_COMMENT)` 校验中文未写坏。同批**重建了 `aquaflow_test`**（现两库各 50 张表，列级与索引差异均为 0） |
| 44 | `migration_v59_drop_ticket_account_product.sql` | **撤回 v54 的 `ticket_record.account_product_id`（v59，破坏性 DROP）**：v54 加那一列，是因为当时把统一水票理解成**站级通用票账户**（`product_id = 0`），于是"订单行商品"与"扣票账户"可能不同，需要额外一列自证（否则 `uk_ticket_consume(order_id, product_id, source)` 的逐项幂等会把同单第二个走统一票的商品当成重复而静默跳过）。产品 2026-09-20 拍板「按统一折扣买的票**进该商品的账户、只能抵那款水**」之后，两者**恒等**，这一列不再承载任何信息 —— 留着就是一张永远等于 `product_id` 的死列。★ **破坏性操作按规程走**：**先上代码**（`TicketAccountServiceImpl` / `TicketRecordMapper` 已不写不读）、**再执行 SQL**，脚本自带护栏「只要有一行 `account_product_id` 非 NULL 就中止，不执行 DROP」。真实库 `ticket_record` **0 行** → 不可能有值可丢。**待执行**（执行前 `mysqldump` 到 `backup/`） |
| 45 | `migration_v60_station_credit_terms.sql` | **账期从「客户级」改为「客户 × 水站级」+ 企业默认账期可一键套用（v60）**：`customer_station_config` 加两列 —— `due_days`（账期天数，NULL = 即时结清不挂账）与 `settlement_cycle`（`IMMEDIATE` 现结 / `MONTHLY` 月结）。★ **为什么必须站级**：原账期在 `company_info.due_days`（`uk_company_customer` 唯一、客户级），而设置端点只校验"该客户归属本站" ⇒ **A 站设的账期会在 B 站生效、B 站还能改掉它**，与 `customer_privilege` 表早已立下的规矩（「特权按 (customer, station) 隔离：A 站给的不在 B 站生效（否则等于跨站送钱）」）冲突，也与 `offline_payment_enabled`（本来就在这张表、本来就是站级）粒度不一致。★ **口径变化**：`due_date` 从"下单日 + N 天"改成 **"当月最后一天 + N 天"**（企业主流：本月消费、下月结账）；两个字段**都满足**（周期 = MONTHLY 且天数 > 0）才挂账，否则 `due_date` 为空 = 即时结清（**与升级前"散户不挂账"的行为一致**）。★ **存量 `company_info.due_days` 不再被任何代码读取** —— 效果等价于"账期一律清空、由站长重设"（2026-09-21 用户裁定），但**不删数据**、可回溯；**不自动搬迁**是有意的：老值没记"是哪个站设的"，复制给所有归属站等于给 B 站凭空授出赊账权（脚本末尾附**逐站可选**的继承 SQL）。★ **读点已全部改齐**（AGENTS §"废弃一列前先全仓 grep 读取点"）：`ReceivableService.resolveDueDate`（下单快照）、`ReceivableMapper.listByCustomer`（台账 `dueDays` 列 —— 曾漏改，被 `ReceivableIntegrationTest` 当场抓红）。★ **平台默认 = 月结 30 天**，站长审核通过企业申请时**一键套用**（`EnterpriseIdentityService.review`），他不需要理解"结算周期"是什么。★ **新增** `POST /api/manager/customers/{id}/credit-terms/recalculate`：把**未结的挂账单**按当前账期重算（因为 `orders.due_date` 是下单时快照、之后只读，站长改了账期会发现"老单没变"）；锚点用**该单自己下单那个月**（不是今天，否则等于延长账期）；每张被改的单在 `orders.special_note` 追加 `[账期重算] 旧→新（操作人 N）` 留痕。★ 纯加两列，**不含任何 UPDATE/DELETE/INSERT**，幂等；回滚 = DROP 两列 + `resolveDueDate` 改回读 `company_info`。**待执行**（执行前 `mysqldump` 到 `backup/`）。用例 `StationCreditTermsIntegrationTest`（5 例：站级隔离 / 一键套用且只影响本站 / 只对新单生效+重算留痕 / 现结不许重算 / 未支持周期被拒） |