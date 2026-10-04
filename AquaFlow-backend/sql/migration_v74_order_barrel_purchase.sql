-- 随单押金与独立购买并存。仅增表/列，不改写任何历史订单或资产。
-- 执行前备份并核实目标；先安装结构，再部署一致后端与小程序。
CREATE TABLE IF NOT EXISTS order_barrel_purchase (
  id bigint NOT NULL AUTO_INCREMENT,
  order_id bigint NOT NULL, customer_id bigint NOT NULL, station_id bigint NOT NULL,
  product_id bigint NOT NULL, quantity int NOT NULL,
  unit_price decimal(10,2) NOT NULL, amount decimal(10,2) NOT NULL,
  payment_id bigint DEFAULT NULL, lot_id bigint DEFAULT NULL,
  refunded_qty int NOT NULL DEFAULT 0, refunded_amount decimal(10,2) NOT NULL DEFAULT 0,
  status varchar(16) NOT NULL DEFAULT 'PENDING',
  PRIMARY KEY(id), UNIQUE KEY uk_order_barrel_product(order_id,product_id),
  UNIQUE KEY uk_order_barrel_lot(lot_id), KEY idx_order_barrel_payment(payment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS order_barrel_refund (
  id bigint NOT NULL AUTO_INCREMENT, purchase_id bigint NOT NULL,
  original_payment_id bigint NOT NULL, refund_payment_id bigint NOT NULL,
  return_record_id bigint DEFAULT NULL, quantity int NOT NULL, amount decimal(10,2) NOT NULL,
  reason varchar(16) NOT NULL COMMENT 'CANCEL / RETURN',
  PRIMARY KEY(id), UNIQUE KEY uk_order_barrel_refund(refund_payment_id,purchase_id),
  UNIQUE KEY uk_order_barrel_return(purchase_id,return_record_id),
  KEY idx_order_barrel_original(original_payment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='barrel_right_reservation' AND column_name='pending_qty')=0,
 'ALTER TABLE barrel_right_reservation ADD COLUMN pending_qty int NOT NULL DEFAULT 0 COMMENT ''本单尚未实收押金的补购容量，不计入生效占用''','SELECT 1');
PREPARE aq_v74 FROM @ddl; EXECUTE aq_v74; DEALLOCATE PREPARE aq_v74;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='barrel_right_reservation' AND column_name='pending_pickup_qty')=0,
 'ALTER TABLE barrel_right_reservation ADD COLUMN pending_pickup_qty int NOT NULL DEFAULT 0 COMMENT ''补购尚未实收部分的预计领取量''','SELECT 1');
PREPARE aq_v74 FROM @ddl; EXECUTE aq_v74; DEALLOCATE PREPARE aq_v74;
