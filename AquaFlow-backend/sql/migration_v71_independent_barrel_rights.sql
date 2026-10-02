-- 2026-10-01：独立押金与权益分配。只新增凭据，不推断/重发历史首次资格。
-- 部署顺序：保持 INDEPENDENT_BARREL_RIGHTS_ENABLED=false，应用结构后再启用新建业务。
-- 旧待配送/待退款单沿用原凭据；新订单以 ORDER 分配记录识别新口径。
CREATE TABLE IF NOT EXISTS barrel_right_purchase (
  id bigint NOT NULL AUTO_INCREMENT,
  customer_id bigint NOT NULL,
  station_id bigint NOT NULL,
  product_id bigint NOT NULL,
  quantity int NOT NULL,
  unit_price decimal(10,2) NOT NULL,
  amount decimal(10,2) NOT NULL,
  payment_id bigint NOT NULL,
  lot_id bigint DEFAULT NULL,
  idempotency_key varchar(64) NOT NULL,
  status varchar(16) NOT NULL DEFAULT 'PENDING',
  create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(id), UNIQUE KEY uk_right_purchase_intent(customer_id,idempotency_key),
  UNIQUE KEY uk_right_purchase_payment(payment_id), UNIQUE KEY uk_right_purchase_lot(lot_id),
  KEY idx_right_purchase_owner(customer_id,station_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS barrel_return_detail (
  record_id bigint NOT NULL,
  customer_id bigint NOT NULL,
  station_id bigint NOT NULL,
  idempotency_key varchar(64) NOT NULL,
  pickup_mode varchar(16) NOT NULL COMMENT 'STORE / PICKUP / COMBINED',
  companion_order_id bigint DEFAULT NULL,
  required_barrels int NOT NULL DEFAULT 0,
  received_barrels int NOT NULL DEFAULT 0,
  pickup_fee decimal(10,2) NOT NULL DEFAULT 0,
  fee_payment_id bigint DEFAULT NULL,
  status varchar(16) NOT NULL DEFAULT 'APPLIED',
  approved_time datetime DEFAULT NULL,
  customer_confirmed_time datetime DEFAULT NULL,
  received_time datetime DEFAULT NULL,
  refund_due_time datetime DEFAULT NULL,
  note varchar(200) DEFAULT NULL,
  PRIMARY KEY(record_id), UNIQUE KEY uk_return_intent(customer_id,idempotency_key),
  UNIQUE KEY uk_return_fee_payment(fee_payment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS barrel_return_lot_hold (
  record_id bigint NOT NULL, lot_id bigint NOT NULL, quantity int NOT NULL,
  unit_price decimal(10,2) NOT NULL, amount decimal(10,2) NOT NULL,
  PRIMARY KEY(record_id,lot_id), KEY idx_return_held_lot(lot_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS barrel_purchase_refund (
  payment_id bigint NOT NULL, purchase_id bigint NOT NULL,
  return_record_id bigint NOT NULL, amount decimal(10,2) NOT NULL,
  PRIMARY KEY(payment_id), UNIQUE KEY uk_purchase_return(purchase_id,return_record_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS barrel_return_fee_refund (
  original_payment_id bigint NOT NULL PRIMARY KEY, refund_payment_id bigint NOT NULL UNIQUE,
  record_id bigint NOT NULL, amount decimal(10,2) NOT NULL, operator_id bigint NOT NULL,
  note varchar(500) NULL, create_time datetime NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS barrel_right_reservation (
  id bigint NOT NULL AUTO_INCREMENT,
  customer_id bigint NOT NULL,
  station_id bigint NOT NULL,
  product_id bigint NOT NULL,
  owner_type varchar(16) NOT NULL COMMENT 'ORDER / RETURN',
  owner_id bigint NOT NULL,
  quantity int NOT NULL,
  pickup_qty int NOT NULL DEFAULT 0 COMMENT '本请求占用的未领取/暂存容量',
  status varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / RELEASED / DELIVERED / REFUNDED',
  create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(id), UNIQUE KEY uk_right_reservation_owner(owner_type,owner_id,product_id),
  KEY idx_right_reservation_available(customer_id,station_id,product_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- 拒付不扣押金、不撤权益；跨站资产冻结必须由资产站另行确认。
CREATE TABLE IF NOT EXISTS customer_refusal_case (
  order_id BIGINT NOT NULL PRIMARY KEY,
  exception_id BIGINT NOT NULL,
  customer_id BIGINT NOT NULL,
  asset_station_id BIGINT NOT NULL,
  debt_station_id BIGINT NOT NULL,
  asset_freeze_confirmed TINYINT NOT NULL DEFAULT 0,
  operator_id BIGINT NOT NULL,
  asset_confirmed_by BIGINT NULL,
  asset_confirmed_time DATETIME NULL,
  note VARCHAR(500) NULL,
  create_time DATETIME NOT NULL,
  KEY idx_refusal_customer (customer_id,asset_station_id,debt_station_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS consumption_refund (
  original_payment_id BIGINT NOT NULL, refund_payment_id BIGINT NOT NULL PRIMARY KEY,
  order_id BIGINT NOT NULL, scope VARCHAR(24) NOT NULL,
  water_amount DECIMAL(10,2) NOT NULL, delivery_fee DECIMAL(10,2) NOT NULL, floor_fee DECIMAL(10,2) NOT NULL,
  operator_id BIGINT NOT NULL, note VARCHAR(500) NULL, create_time DATETIME NOT NULL,
  KEY idx_consumption_original (original_payment_id), KEY idx_consumption_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS ticket_exit_refund (
  original_payment_id BIGINT NOT NULL PRIMARY KEY, refund_payment_id BIGINT NOT NULL UNIQUE,
  quantity INT NOT NULL, amount DECIMAL(10,2) NOT NULL, operator_id BIGINT NOT NULL, create_time DATETIME NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS inter_station_recovery (
  order_id BIGINT NOT NULL PRIMARY KEY, from_station_id BIGINT NOT NULL, to_station_id BIGINT NOT NULL,
  amount DECIMAL(10,2) NOT NULL, status VARCHAR(16) NOT NULL,
  sender_confirmed_by BIGINT NULL, sender_confirmed_time DATETIME NULL,
  receiver_confirmed_by BIGINT NULL, receiver_confirmed_time DATETIME NULL,
  note VARCHAR(500) NULL, create_time DATETIME NOT NULL,
  KEY idx_recovery_parties (from_station_id,to_station_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS dispatch_agreement (
  order_id BIGINT NOT NULL PRIMARY KEY, source_station_id BIGINT NOT NULL, target_station_id BIGINT NULL,
  service_amount DECIMAL(10,2) NOT NULL, net_barrels INT NOT NULL, barrel_mode VARCHAR(24) NOT NULL,
  actual_net_barrels INT NULL, actual_barrel_items VARCHAR(2000) NULL,
  barrel_disputed TINYINT NOT NULL DEFAULT 0, dispute_note VARCHAR(500) NULL,
  resolution_note VARCHAR(500) NULL, resolution_proposed_by BIGINT NULL, resolution_confirmed_by BIGINT NULL,
  source_barrel_confirmed_by BIGINT NULL, target_barrel_confirmed_by BIGINT NULL,
  barrel_amount DECIMAL(10,2) NOT NULL, barrel_items VARCHAR(2000) NOT NULL, status VARCHAR(24) NOT NULL,
  quoted_by BIGINT NULL, accepted_by BIGINT NULL, accepted_time DATETIME NULL,
  closed_by BIGINT NULL, closed_time DATETIME NULL, close_note VARCHAR(500) NULL,
  note VARCHAR(500) NULL, create_time DATETIME NOT NULL,
  KEY idx_dispatch_parties (source_station_id,target_station_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
