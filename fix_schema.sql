-- Fix missing tables and indexes

-- ticket_account: fix unique index to include station_id
ALTER TABLE ticket_account DROP INDEX IF EXISTS uk_customer_product_station;
ALTER TABLE ticket_account ADD UNIQUE KEY uk_customer_product_station (customer_id, product_id, station_id);

-- deposit_record: add index on station_id
CREATE INDEX IF NOT EXISTS idx_deposit_record_station ON deposit_record (station_id);

-- ticket_record: add index on station_id
CREATE INDEX IF NOT EXISTS idx_ticket_record_station ON ticket_record (station_id);

-- order_template_item: add index on station_id
CREATE INDEX IF NOT EXISTS idx_template_item_station ON order_template_item (station_id);