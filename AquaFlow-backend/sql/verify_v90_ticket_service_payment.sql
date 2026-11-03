-- Read only; run after migration_v90_ticket_service_payment.sql, before deploying the new backend.
SELECT DATABASE() AS target_schema;
SELECT COUNT(*) AS installed_tables FROM information_schema.tables
 WHERE table_schema=DATABASE() AND table_name IN
 ('order_ticket_payment','order_ticket_hold','ticket_delivery_offer','ticket_delivery_benefit');
-- Expect 4 installed tables; default configurations/benefits may correctly have no rows.
SELECT COUNT(*) AS enabled_offers FROM ticket_delivery_offer WHERE enabled=1;
SELECT state,COUNT(*) AS records FROM ticket_delivery_benefit GROUP BY state;
SELECT COUNT(*) AS invalid_split FROM order_ticket_payment
 WHERE delivery_credit<0 OR delivery_credit>delivery_fee OR cash_due<0
 OR cash_due<>delivery_fee-delivery_credit+floor_fee+deposit_amount
 OR cash_due>0 AND (cash_method IS NULL OR cash_method NOT IN (1,2));
SELECT COUNT(*) AS invalid_hold FROM order_ticket_hold h LEFT JOIN ticket_lot l ON l.id=h.lot_id
 WHERE h.quantity<=0 OR l.id IS NULL OR h.product_id<>l.product_id;
-- Expect 0 invalid split/hold rows. Never treat a query failure as a zero result.
