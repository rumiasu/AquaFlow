package com.example.aquaflow.mapper;

import java.util.ArrayList;
import java.util.List;

/** Fixed SQL vocabulary, bound customer ID, full aggregation. No paging/role/station inputs. */
public final class AccountClosureCheckSql {
    private AccountClosureCheckSql() {}
    private static String row(String from,String station,String category,String owner,String condition,String qty,String amount) {
        return "select "+station+" stationId,'"+category+"' category,"+(category.equals("FACT")?"0":"count(*)")+
            " itemCount,"+(category.equals("FACT")?"0":"coalesce(sum("+qty+"),0)")+" quantity,"+
            (category.equals("FACT")?"0":"coalesce(sum("+amount+"),0)")+" amount from "+from+
            " where "+owner+"=#{customerId} and ("+condition+") group by "+station;
    }
    private static void add(List<String> rows,String table,String category,String condition,String qty,String amount) {
        rows.add(row(table,"station_id",category,"customer_id",condition,qty,amount));
    }
    public static String facts() {
        List<String> r=new ArrayList<>();
        for(String t:List.of("customer_station_config","customer_deposit_account","customer_barrel_asset","customer_barrel_lot",
                "customer_barrel_over","customer_barrel_in_transit","ticket_account","ticket_lot","payment_record","deposit_record",
                "ticket_record","barrel_record","barrel_right_purchase","barrel_right_reservation","barrel_return_detail",
                "order_barrel_purchase","order_barrel_exception","station_adjustment")) add(r,t,"FACT","1=1","0","0");
        for(String s:List.of("station_id","delivery_station_id","coalesce(settle_station_id,delivery_station_id,station_id)"))
            r.add(row("orders",s,"FACT","customer_id",s+" is not null","0","0"));
        for(String s:List.of("asset_station_id","debt_station_id")) r.add(row("customer_refusal_case",s,"FACT","customer_id","1=1","0","0"));
        r.add(row("refund_dispute","responsible_station_id","FACT","customer_id","1=1","0","0"));
        r.add(row("refund_dispute","responsible_station_id","EXCEPTION_OPEN","customer_id","status='OPEN'","0","0"));
        r.add(row("refund_dispute","responsible_station_id","MANUAL_REVIEW","customer_id","status is null or status not in ('OPEN','CLOSED')","0","0"));

        String moneyStation="coalesce(settle_station_id,delivery_station_id,station_id)";
        r.add(row("orders",moneyStation,"ORDER_OPEN","customer_id","status in (1,2,3)","0","0"));
        // Same receivable truth as Dashboard/Receivable: no reverse test on settlement_status and no cancelled debt.
        r.add(row("orders",moneyStation,"DEBT","customer_id","payment_status=1 and status<>5","0","greatest(coalesce(total_amount,0),0)"));
        r.add(row("orders",moneyStation,"MANUAL_REVIEW","customer_id",
            "status is null or status not in (1,2,3,4,5) or payment_status is null or payment_status not in (0,1,2,3,4) or "+
            "(status=4 and payment_status=0) or (status=5 and payment_status=2) or (payment_status=1 and (total_amount is null or total_amount<0))","0","0"));
        add(r,"payment_record","PAYMENT_PENDING","status in (0,1)","0","0");
        add(r,"payment_record","MANUAL_REVIEW","status is null or status not in (0,1,2,3,4) or (payment_method=1 and amount<0 and note like '%需线下退款%')","0","0");

        // Received money without a provable own business voucher is ambiguous, never a new debt.
        r.add(row("payment_record p","p.station_id","MANUAL_REVIEW","p.customer_id",
            "p.status=2 and p.amount>0 and ((p.order_id is not null and not exists(select 1 from orders o where o.id=p.order_id and o.customer_id=p.customer_id)) or "+
            "(p.order_id is null and not exists(select 1 from ticket_lot l where l.payment_record_id=p.id and l.customer_id=p.customer_id and l.station_id=p.station_id) and "+
            "not exists(select 1 from barrel_right_purchase b where b.payment_id=p.id and b.customer_id=p.customer_id and b.station_id=p.station_id) and "+
            "not exists(select 1 from order_barrel_purchase b where b.payment_id=p.id and b.customer_id=p.customer_id) and "+
            "not exists(select 1 from barrel_return_detail d where d.fee_payment_id=p.id and d.customer_id=p.customer_id and d.station_id=p.station_id)))","0","0"));

        add(r,"customer_deposit_account","DEPOSIT","balance>0","0","balance");
        add(r,"customer_deposit_account","MANUAL_REVIEW","balance<0 or balance is null","0","0");
        for(String t:List.of("customer_barrel_lot","ticket_lot")) {
            add(r,t,t.equals("ticket_lot")?"TICKETS":"BARREL_RIGHTS","remain_qty>0 and status=1","remain_qty","0");
            add(r,t,"MANUAL_REVIEW","remain_qty is null or unit_price is null or status is null or remain_qty<0 or unit_price<0 or status not in (1,2,3) or (remain_qty<>0 and status<>1)","0","0");
        }
        // Derived totals must not hide a missing lot; quantities/amounts are not reclassified as a new debt.
        for(String t:List.of("customer_barrel_asset","ticket_account")) {
            String lot=t.equals("ticket_account")?"ticket_lot":"customer_barrel_lot";
            String qty=t.equals("ticket_account")?"remain_quantity":"quantity";
            String match="l.customer_id=a.customer_id and l.station_id<=>a.station_id and l.product_id=a.product_id";
            r.add(row(t+" a","a.station_id","MANUAL_REVIEW","a.customer_id",
                "a."+qty+" is null or a.right_amount is null or a."+qty+"<>coalesce((select sum(l.remain_qty) from "+lot+" l where "+match+"),0) or "+
                "abs(a.right_amount-coalesce((select sum(l.remain_qty*l.unit_price) from "+lot+" l where "+match+"),0))>0.005","0","0"));
        }
        r.add("select d.station_id stationId,'MANUAL_REVIEW' category,1 itemCount,0 quantity,0 amount from deposit_record d "+
            "where d.customer_id=#{customerId} group by d.station_id having abs(sum(d.amount)-coalesce((select a.balance from customer_deposit_account a "+
            "where a.customer_id=#{customerId} and a.station_id<=>d.station_id),0))>0.005");
        add(r,"customer_barrel_over","BARREL_OWED","over_qty>0","over_qty","0");
        add(r,"customer_barrel_over","MANUAL_REVIEW","over_qty is null or over_qty<0","0","0");
        add(r,"customer_barrel_in_transit","BARREL_IN_TRANSIT","status='PENDING' and qty>0","qty","0");
        add(r,"customer_barrel_in_transit","MANUAL_REVIEW","status is null or qty is null or status not in ('PENDING','DELIVERED','CANCELLED') or qty<0","0","0");
        add(r,"barrel_right_reservation","BARREL_RESERVED","status='ACTIVE'","0","0");
        add(r,"barrel_right_reservation","MANUAL_REVIEW","status is null or status not in ('ACTIVE','RELEASED','DELIVERED','REFUNDED')","0","0");
        for(String t:List.of("barrel_right_purchase","order_barrel_purchase")) {
            add(r,t,"RIGHTS_PURCHASE_PENDING","status='PENDING'","quantity","0");
            String terminal=t.equals("barrel_right_purchase")?"'PAID','CANCELLED'":"'PAID','CANCELLED','REFUNDED'";
            r.add(row(t+" b","b.station_id","MANUAL_REVIEW","b.customer_id",
                "b.status is null or b.status not in ('PENDING',"+terminal+") or (b.status='PAID' and not exists(select 1 from customer_barrel_lot l "+
                "where l.id=b.lot_id and l.customer_id=b.customer_id and l.station_id=b.station_id and l.product_id=b.product_id)) or "+
                "(b.status='PAID' and not exists(select 1 from payment_record p where p.id=b.payment_id and p.customer_id=b.customer_id and p.status in (2,3))) or "+
                "(b.status='CANCELLED' and exists(select 1 from payment_record p where p.id=b.payment_id and p.status in (0,1,2)))","0","0"));
        }
        add(r,"barrel_return_detail","RETURN_PENDING","status in ('APPLIED','APPROVED','RECEIVED')","0","0");
        r.add(row("barrel_return_detail d left join barrel_record b on b.id=d.record_id","d.station_id","MANUAL_REVIEW","d.customer_id",
            "d.status is null or d.status not in ('APPLIED','APPROVED','RECEIVED','REFUNDED','REJECTED','WITHDRAWN') or b.id is null or b.customer_id<>d.customer_id or b.station_id<>d.station_id or "+
            "(d.status='REFUNDED' and (b.status<>3 or b.refund_paid_time is null))","0","0"));
        r.add(row("barrel_record b","b.station_id","RETURN_PENDING","b.customer_id",
            "b.type=2 and b.status in (1,2) and not exists(select 1 from barrel_return_detail d where d.record_id=b.id)","0","0"));
        add(r,"barrel_record","MANUAL_REVIEW","type=2 and (status is null or status not in (1,2,3,4) or (status=3 and refund_paid_time is null))","0","0");
        add(r,"order_barrel_exception","EXCEPTION_OPEN","status is null or status not in ('EXECUTED','IGNORED')","0","0");
        add(r,"station_adjustment","MANUAL_REVIEW","status is null or status not in ('EFFECTIVE','REVERSED','REJECTED')","0","0");
        // A revoked judgment does not clear a different asset station's confirmed freeze; its own release is authoritative.
        r.add(row("customer_refusal_case c left join orders o on o.id=c.order_id left join customer_refusal_resolution z on z.order_id=c.order_id",
            "c.asset_station_id","MANUAL_REVIEW","c.customer_id",
            "o.id is null or o.customer_id<>c.customer_id or (c.asset_freeze_confirmed=1 and coalesce(z.asset_freeze_released,0)=0 and o.payment_status=1 and o.status<>5) "+
            "or (z.order_id is not null and (z.judgment_revoked not in (0,1) or z.asset_freeze_released not in (0,1)))","0","0"));
        // Completed receipt rows are history. Only an absent/unconfirmed/mismatched outgoing original-channel record blocks.
        for(String table:List.of("consumption_refund","ticket_exit_refund","barrel_return_fee_refund","order_barrel_refund")) {
            r.add(row(table+" x join payment_record p on p.id=x.original_payment_id left join payment_record f on f.id=x.refund_payment_id",
                "p.station_id","MANUAL_REVIEW","p.customer_id","f.id is null or f.customer_id<>p.customer_id or f.status is null or f.status<>3 or f.amount is null or f.amount>=0","0","0"));
        }
        // Ownership can also be proved by an outgoing receipt when its original is missing.
        for(String table:List.of("consumption_refund","ticket_exit_refund","barrel_return_fee_refund","order_barrel_refund"))
            r.add(row(table+" x join payment_record f on f.id=x.refund_payment_id left join payment_record p on p.id=x.original_payment_id",
                "f.station_id","MANUAL_REVIEW","f.customer_id","p.id is null or p.customer_id<>f.customer_id","0","0"));
        r.add(row("consumption_refund x join orders o on o.id=x.order_id left join payment_record p on p.id=x.original_payment_id",
            "coalesce(o.settle_station_id,o.delivery_station_id,o.station_id)","MANUAL_REVIEW","o.customer_id",
            "p.id is null or p.customer_id<>o.customer_id","0","0"));
        r.add(row("barrel_purchase_refund x join payment_record f on f.id=x.payment_id left join barrel_right_purchase b on b.id=x.purchase_id",
            "f.station_id","MANUAL_REVIEW","f.customer_id","b.id is null or b.customer_id<>f.customer_id","0","0"));
        r.add(row("barrel_purchase_refund x join barrel_right_purchase b on b.id=x.purchase_id left join payment_record f on f.id=x.payment_id",
            "b.station_id","MANUAL_REVIEW","b.customer_id","f.id is null or f.customer_id<>b.customer_id or f.status is null or f.status<>3 or f.amount is null or f.amount>=0","0","0"));
        return "select x.stationId,s.name stationName,s.status stationStatus,x.category,sum(x.itemCount) itemCount,sum(x.quantity) quantity,sum(x.amount) amount "+
            "from ("+String.join(" union all ",r)+") x left join station s on s.id=x.stationId "+
            "group by x.stationId,s.name,s.status,x.category order by x.stationId,x.category";
    }
}
