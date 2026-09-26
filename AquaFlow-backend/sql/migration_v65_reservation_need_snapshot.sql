-- =============================================================================
-- v65 · 库存预留凭据的"需求量快照"列（二次收口 B2）
--
-- 背景（二次验收意见 B2）：补位原来靠 `listItemNeedAndTime`（普通 SELECT join `orders`/`order_item`）
-- 现读"需求量 + 下单时间"。注释当时的理由是"这两个字段不可变，所以快照读安全"，
-- 但**字段不可变不代表这一行在旧快照里已经存在**：REPEATABLE READ 下，
-- 当前读能看到刚提交的**新凭据**，普通读却看不到同一批提交里刚插入的**订单明细**，
-- 于是新等待单被当成 need=0 静默跳过（有 10 桶可用却不分给它）。
--
-- 修法：把"下单那一刻的需求量/需求时间"随凭据一起落库（`need_qty` / `need_time`），
-- 补位只读凭据行（当前读）。
--   · **真相源仍然是** `order_item.quantity` / `orders.create_time`（下单后不再变化）；
--     这两列是它们的**只读副本**，写入点只有建凭据/换站重建（复制旧凭据），
--     一致性由对账 **E15**（快照 ≠ 明细量）校验 —— **不是**第二份账，别单独改它。
--   · 本脚本给"已经跑过 v63/v64、表里还没有这两列"的库补列 + 回填 + 收紧为 NOT NULL。
--
-- 影响面：只加两列、只回填 NULL 行；不删列、不改既有列含义。
--
-- 执行方式：与 v63 相同 —— 用**出错即停止**的客户端（mysql CLI 默认行为，**不要 `--force`**；
--   实测 `--force` 下退出码仍为 0，会骗人）；
--   执行前 `mysqldump` 到 `backup/`，并停止业务写入。
--   ⚠️ DDL（ADD/MODIFY COLUMN）会隐式提交，**无法**被包进一个事务；所以本脚本的顺序是
--   "先加可空列（纯新增、幂等、安全）→ 再在事务里回填 → 校验无 NULL 才收紧为 NOT NULL"。
--   中途失败最坏的中间态是"列已加、值为 NULL"——此时**不要**收紧 NOT NULL，
--   直接重跑本脚本即可（回填是幂等的：只写 NULL 行）。
--
-- 回滚：`ALTER TABLE inventory_reservation DROP COLUMN need_time, DROP COLUMN need_qty;`
--   ⚠️ 但代码（InventoryReservationServiceImpl）已经依赖这两列 ⇒ **先回代码再删列**（顺序同 v41）。
-- =============================================================================

SET @db := DATABASE();

-- -----------------------------------------------------------------------------
-- 第 0 步：预检
-- -----------------------------------------------------------------------------
SET @res_exists := (SELECT COUNT(*) FROM information_schema.TABLES
                    WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'inventory_reservation');
SET @dep_tables := (SELECT COUNT(*) FROM information_schema.TABLES
                    WHERE TABLE_SCHEMA = @db AND TABLE_NAME IN ('orders','order_item'));
SELECT IF(@res_exists = 1,
          'OK: inventory_reservation 存在（本脚本给它补 need_qty / need_time）',
          'ABORT: 本库还没有 inventory_reservation —— 先跑 migration_v63（它建表时自带这两列）') AS precheck;
SELECT IF(@dep_tables = 2, 'OK: orders / order_item 均在（回填的来源表）',
          CONCAT('ABORT: orders / order_item 命中 ', @dep_tables, '/2')) AS dep_check;

SET @abort := IF(@res_exists <> 1 OR @dep_tables <> 2,
                 'SELECT * FROM __ABORT_V65_PRECHECK_FAILED__', 'SELECT 1');
PREPARE st_abort FROM @abort;
EXECUTE st_abort;
DEALLOCATE PREPARE st_abort;

-- -----------------------------------------------------------------------------
-- 第 1 步：加列（可空；已存在则跳过 —— 幂等）
-- -----------------------------------------------------------------------------
SET @has_need_qty := (SELECT COUNT(*) FROM information_schema.COLUMNS
                      WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'inventory_reservation'
                        AND COLUMN_NAME = 'need_qty');
SET @sql_add_qty := IF(@has_need_qty = 0,
    'ALTER TABLE inventory_reservation ADD COLUMN need_qty int NULL COMMENT ''需求量快照（= 下单那一刻 order_item.quantity，v65）：补位排序/分配只读它'' AFTER station_id',
    'SELECT ''skip: need_qty 已存在'' AS note');
PREPARE st1 FROM @sql_add_qty;
EXECUTE st1;
DEALLOCATE PREPARE st1;

SET @has_need_time := (SELECT COUNT(*) FROM information_schema.COLUMNS
                       WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'inventory_reservation'
                         AND COLUMN_NAME = 'need_time');
SET @sql_add_time := IF(@has_need_time = 0,
    'ALTER TABLE inventory_reservation ADD COLUMN need_time datetime NULL COMMENT ''业务需求时间快照（= 下单那一刻 orders.create_time，v65）：FIFO 依据'' AFTER need_qty',
    'SELECT ''skip: need_time 已存在'' AS note');
PREPARE st2 FROM @sql_add_time;
EXECUTE st2;
DEALLOCATE PREPARE st2;

-- -----------------------------------------------------------------------------
-- 第 2 步：回填（只写 NULL 行；真相源 = order_item.quantity / orders.create_time）
--   覆盖**全部**行（含已出库/已释放的历史行）：让列能收紧成 NOT NULL，也让排查时语义一致。
-- -----------------------------------------------------------------------------
START TRANSACTION;
UPDATE inventory_reservation r
  JOIN order_item oi ON oi.id = r.order_item_id
  JOIN orders o ON o.id = r.order_id
   SET r.need_qty = COALESCE(r.need_qty, oi.quantity),
       r.need_time = COALESCE(r.need_time, o.create_time),
       r.update_time = NOW()
 WHERE r.need_qty IS NULL OR r.need_time IS NULL;

-- 回填后仍为 NULL 的行（例如 order_item 已被删除）：**不收紧 NOT NULL**，先报出来让人处理
SELECT COUNT(*) AS rows_still_null FROM inventory_reservation
 WHERE need_qty IS NULL OR need_time IS NULL;
SET @nulls := (SELECT COUNT(*) FROM inventory_reservation
                WHERE need_qty IS NULL OR need_time IS NULL);

SET @commit_stmt := IF(@nulls = 0, 'COMMIT',
                       'SELECT * FROM __V65_BACKFILL_LEFT_NULLS_TX_NOT_COMMITTED__');
PREPARE st_commit FROM @commit_stmt;
EXECUTE st_commit;
DEALLOCATE PREPARE st_commit;

-- -----------------------------------------------------------------------------
-- 第 3 步：校验无 NULL 才收紧为 NOT NULL（收紧后业务代码可以放心不判空）
-- -----------------------------------------------------------------------------
SET @nulls_now := (SELECT COUNT(*) FROM inventory_reservation
                    WHERE need_qty IS NULL OR need_time IS NULL);
SET @qty_nullable := (SELECT IS_NULLABLE FROM information_schema.COLUMNS
                       WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'inventory_reservation'
                         AND COLUMN_NAME = 'need_qty');
SET @sql_tighten := IF(@nulls_now = 0 AND @qty_nullable = 'YES',
    'ALTER TABLE inventory_reservation MODIFY COLUMN need_qty int NOT NULL COMMENT ''★ 需求量快照（= 下单那一刻 order_item.quantity，v65）：补位排序/分配只读它，不再 join order_item —— 见 docs/design/28 §11.9（旧快照下 join 读不到刚提交的新明细）''',
    'SELECT ''skip: 不满足收紧条件（还有 NULL 或已经是 NOT NULL）'' AS note');
PREPARE st3 FROM @sql_tighten;
EXECUTE st3;
DEALLOCATE PREPARE st3;

SET @time_nullable := (SELECT IS_NULLABLE FROM information_schema.COLUMNS
                        WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'inventory_reservation'
                          AND COLUMN_NAME = 'need_time');
SET @sql_tighten2 := IF(@nulls_now = 0 AND @time_nullable = 'YES',
    'ALTER TABLE inventory_reservation MODIFY COLUMN need_time datetime NOT NULL COMMENT ''★ 业务需求时间快照（= 下单那一刻 orders.create_time，v65）：FIFO 排序依据；换站重建时从旧凭据复制（凭据 id 会变、需求时间不变）''',
    'SELECT ''skip: 不满足收紧条件（还有 NULL 或已经是 NOT NULL）'' AS note');
PREPARE st4 FROM @sql_tighten2;
EXECUTE st4;
DEALLOCATE PREPARE st4;

-- -----------------------------------------------------------------------------
-- 第 4 步：自查
-- -----------------------------------------------------------------------------
SELECT (SELECT COUNT(*) FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'inventory_reservation'
           AND COLUMN_NAME IN ('need_qty','need_time'))                    AS snapshot_columns,
       (SELECT COUNT(*) FROM inventory_reservation)                        AS total_rows,
       (SELECT COUNT(*) FROM inventory_reservation
         WHERE need_qty IS NULL OR need_time IS NULL)                      AS null_rows,
       (SELECT COUNT(*) FROM inventory_reservation r JOIN order_item oi ON oi.id = r.order_item_id
         WHERE r.status = 1 AND r.need_qty <> oi.quantity)                 AS active_snapshot_mismatch;
-- ↑ 期望：snapshot_columns=2、null_rows=0、active_snapshot_mismatch=0
