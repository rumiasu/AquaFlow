-- ============================================================
-- AquaFlow 测试阶段整改（彻底最优版） consolidated DDL
-- 对应方案：docs/测试阶段问题清单.md + plans/blazing-pulse-tesla.md
-- 执行前提：开发阶段，数据库可执（已获授权）。
-- 内容：A 水厂端删除 / B 订单状态迁移 / C 数据库结构更优解
-- ============================================================

-- ---------- A. 水厂端彻底删除 ----------
-- orders.factory_id 全为 0/NULL、无真实数据、后端仅死字段、前端零引用 -> 删列
ALTER TABLE orders DROP COLUMN factory_id;

-- ---------- C.1 创建缺失的真功能表 ----------
CREATE TABLE IF NOT EXISTS company_info (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    company_name VARCHAR(200),
    contact_person VARCHAR(100),
    contact_phone VARCHAR(100),
    payment_method VARCHAR(50),
    due_days INT,
    create_time DATETIME,
    update_time DATETIME,
    UNIQUE KEY uk_company_customer (customer_id)
) COMMENT='企业客户资料（对公结账、账期等）';

CREATE TABLE IF NOT EXISTS customer_notification (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    type VARCHAR(50),
    title VARCHAR(200),
    content TEXT,
    related_order_id BIGINT,
    is_read INT DEFAULT 0,
    create_time DATETIME
) COMMENT='客户通知（拒单、临时外派等订单状态变更提醒）';

-- ---------- C.2 字段对齐 ----------
-- 代码表名 customer_barrel_owed 与实体字段 owed_qty 指向真实表 customer_owed_barrel；
-- 将库列 quantity 重命名为 owed_qty，与实体 CustomerBarrelOwed.owedQty 一致。
ALTER TABLE customer_owed_barrel CHANGE quantity owed_qty INT DEFAULT 0 COMMENT '欠桶数(正数=客户欠桶,负数=客户多还)';

-- ---------- C.3 补缺失列（真功能读写，库缺列） ----------
ALTER TABLE orders ADD COLUMN first_barrel_order TINYINT(1) DEFAULT 0 COMMENT '是否首次桶装水订单(押金桶无需回桶)';

ALTER TABLE barrel_record ADD COLUMN station_id BIGINT AFTER customer_id COMMENT '所属水站';
ALTER TABLE barrel_record ADD COLUMN type INT AFTER station_id COMMENT '1新增押金桶 2退桶 3丢失 4损坏 5赔偿 6人工调整';
ALTER TABLE barrel_record ADD COLUMN related_order_id BIGINT AFTER type COMMENT '关联订单';
ALTER TABLE barrel_record ADD COLUMN operator_id BIGINT AFTER related_order_id COMMENT '操作员';

ALTER TABLE notice ADD COLUMN station_id BIGINT AFTER id COMMENT '所属水站(NULL=系统公告)';
ALTER TABLE notice ADD COLUMN publisher_id BIGINT AFTER station_id COMMENT '发布者';
ALTER TABLE notice DROP COLUMN publisher_role;  -- 旧模型残留，与实体 publisherId 不一致，删除

ALTER TABLE ticket_record ADD COLUMN station_id BIGINT AFTER product_id COMMENT '所属水站';

ALTER TABLE product ADD COLUMN ticket_enabled INT DEFAULT 0 COMMENT '是否支持水票支付';
ALTER TABLE product ADD COLUMN ticket_price DECIMAL(10,2) DEFAULT 0.00 COMMENT '水票价格';

-- ---------- B. 订单状态数据迁移 ----------
-- 旧方案 1/3/4/5/6 -> 新连续方案 1/2/3/4/5
--   1待配送->1  3配送中->2  4已送达->3  5已完成->4  6已取消->5
UPDATE orders SET status = CASE status
    WHEN 1 THEN 1
    WHEN 3 THEN 2
    WHEN 4 THEN 3
    WHEN 5 THEN 4
    WHEN 6 THEN 5
    ELSE status END;
