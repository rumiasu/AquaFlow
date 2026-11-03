package com.example.aquaflow.service;

import com.example.aquaflow.entity.PaymentRecord;
import com.example.aquaflow.constant.PaymentStatus;
import com.example.aquaflow.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** One paid selected tier purchase grants at most one product-bound delivery benefit. */
@Service
public class TicketDeliveryBenefitService {
    private final JdbcTemplate jdbc;
    public TicketDeliveryBenefitService(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    private static long n(Map<String,Object> r,String key){return ((Number)r.get(key)).longValue();}
    public boolean enabled(Long station,Long product,String kind,Long key) {
        if(station==null || key==null || !("CUSTOM".equals(kind)||"UNIFIED".equals(kind)))return false;
        Long ownerProduct="UNIFIED".equals(kind)?0L:product;
        Integer count=jdbc.queryForObject("select count(*) from ticket_delivery_offer where station_id=? and product_id=? and tier_kind=? and tier_key=? and enabled=1",Integer.class,station,ownerProduct,kind,key);
        return count!=null && count==1;
    }
    public List<Map<String,Object>> catalog(Long station) {
        List<Map<String,Object>> result=new ArrayList<>();
        for(var r:jdbc.queryForList("select tp.id,tp.product_id,tp.qty,p.name as product_name from ticket_package tp join product p on p.id=tp.product_id where tp.station_id=? and tp.status=1 order by tp.product_id,tp.qty",station)) {
            long product=n(r,"product_id"),key=n(r,"id");
            result.add(Map.of("kind","CUSTOM","tierKey",key,"productId",product,"label",r.get("product_name")+" · "+r.get("qty")+"张档","enabled",enabled(station,product,"CUSTOM",key)));
        }
        for(var r:jdbc.queryForList("select qty from station_ticket_discount where station_id=? and status=1 order by qty",station)) {
            long key=n(r,"qty");result.add(Map.of("kind","UNIFIED","tierKey",key,"productId",0L,"label","统一折扣 · "+key+"张档","enabled",enabled(station,0L,"UNIFIED",key)));
        }
        return result;
    }
    @Transactional public void configure(Long station,String kind,Long key,Long product,Boolean enabled) {
        if(station==null || key==null || key<=0 || product==null || enabled==null)throw new BusinessException("请选择有效水票档位和抵配送费设置");
        Integer count;
        if("CUSTOM".equals(kind) && product>0)
            count=jdbc.queryForObject("select count(*) from ticket_package where id=? and station_id=? and product_id=? and status=1",Integer.class,key,station,product);
        else if("UNIFIED".equals(kind) && product==0)
            count=jdbc.queryForObject("select count(*) from station_ticket_discount where station_id=? and qty=? and status=1",Integer.class,station,key);
        else throw new BusinessException("水票档位类型或商品不符");
        if(count==null || count!=1)throw new BusinessException("水票档位不存在或已下架，请刷新后重试");
        jdbc.update("insert into ticket_delivery_offer(station_id,product_id,tier_kind,tier_key,enabled) values(?,?,?,?,?) on duplicate key update enabled=values(enabled),update_time=NOW()",station,product,kind,key,enabled?1:0);
    }
    @Transactional public void snapshotPurchase(PaymentRecord payment,Long packageId,Integer unifiedQty) {
        String kind=packageId!=null?"CUSTOM":(unifiedQty!=null?"UNIFIED":null);
        Long key=packageId!=null?packageId:(unifiedQty==null?null:unifiedQty.longValue());
        if(!enabled(payment.getStationId(),payment.getTicketWaterTypeId(),kind,key))return;
        if(payment.getId()==null || payment.getOrderId()!=null || payment.getTicketQty()==null || payment.getTicketQty()<=0)
            throw new BusinessException("配送费权益必须关联本次真实购票凭据");
        jdbc.update("insert into ticket_delivery_benefit(payment_id,customer_id,station_id,product_id,tier_kind,tier_key) values(?,?,?,?,?,?)",payment.getId(),payment.getCustomerId(),payment.getStationId(),payment.getTicketWaterTypeId(),kind,key);
    }
    @Transactional public void activatePurchase(PaymentRecord payment) {
        if(!Integer.valueOf(PaymentStatus.PAID).equals(payment.getStatus()))throw new BusinessException("购票款未确认，不能发放配送费权益");
        var rows=jdbc.queryForList("select * from ticket_delivery_benefit where payment_id=? for update",payment.getId());
        if(rows.isEmpty())return;
        var row=rows.get(0);
        if(n(row,"customer_id")!=payment.getCustomerId() || n(row,"station_id")!=payment.getStationId() || n(row,"product_id")!=payment.getTicketWaterTypeId())throw new BusinessException("配送费权益与原购票款归属不符");
        if("PENDING".equals(row.get("state")))jdbc.update("update ticket_delivery_benefit set state='AVAILABLE',update_time=NOW() where payment_id=? and state='PENDING'",payment.getId());
    }
    public Long preview(Long customer,Long station,Set<Long> sourcePayments) {
        if(sourcePayments.isEmpty())return null;
        for(var row:jdbc.queryForList("select payment_id from ticket_delivery_benefit where customer_id=? and station_id=? and state='AVAILABLE' order by payment_id",customer,station)) {
            long id=n(row,"payment_id");if(sourcePayments.contains(id))return id;
        }
        return null;
    }
    @Transactional public Long reserve(Long customer,Long station,Long order) {
        // Source purchase must actually be represented in this order's held FIFO tickets.
        var rows=jdbc.queryForList("select b.* from ticket_delivery_benefit b where b.customer_id=? and b.station_id=? and b.state='AVAILABLE' and exists(select 1 from order_ticket_hold h join ticket_lot l on l.id=h.lot_id where h.order_id=? and h.state='HELD' and l.payment_record_id=b.payment_id and l.product_id=b.product_id and l.source_type=1) order by b.payment_id limit 1 for update",customer,station,order);
        if(rows.isEmpty())return null;long payment=n(rows.get(0),"payment_id");
        if(jdbc.update("update ticket_delivery_benefit set state='HELD',order_id=?,update_time=NOW() where payment_id=? and state='AVAILABLE'",order,payment)!=1)throw new BusinessException("配送费权益已被其他订单使用，请重新核对费用");
        return payment;
    }
    @Transactional public void delivered(Long order) {
        Integer held=jdbc.queryForObject("select count(*) from ticket_delivery_benefit where order_id=? and state='HELD'",Integer.class,order);
        if(held==null || held==0)return;
        int changed=jdbc.update("update ticket_delivery_benefit b set b.state='USED',b.update_time=NOW() where b.order_id=? and b.state='HELD' and exists(select 1 from orders o where o.id=b.order_id and o.status in (3,4)) and exists(select 1 from order_ticket_hold h join ticket_lot l on l.id=h.lot_id where h.order_id=b.order_id and h.state='CONSUMED' and l.payment_record_id=b.payment_id and l.source_type=1)",order);
        if(changed!=1)throw new BusinessException("配送费权益尚无实际用票和交付事实，不能核销");
    }
    @Transactional public void releaseBeforeDelivery(Long order) {
        var rows=jdbc.queryForList("select * from ticket_delivery_benefit where order_id=? and state in ('HELD','USED') for update",order);
        for(var row:rows) {
            if("USED".equals(row.get("state")))throw new BusinessException("配送已实际使用，不能通过取消恢复优惠，请核对履约记录");
            if(jdbc.update("update ticket_delivery_benefit set state='AVAILABLE',order_id=NULL,update_time=NOW() where payment_id=? and order_id=? and state='HELD'",n(row,"payment_id"),order)!=1)throw new BusinessException("配送费权益状态已变化");
        }
    }
    @Transactional public void revokeOnUnusedTicketExit(Long payment) {
        var rows=jdbc.queryForList("select * from ticket_delivery_benefit where payment_id=? for update",payment);
        if(rows.isEmpty())return;String state=String.valueOf(rows.get(0).get("state"));
        if("HELD".equals(state))throw new BusinessException("该次购票的配送费权益正被原订单占用，请先核对或取消原订单");
        // Already delivered benefits stay used: no cash equivalent and no second use.
        if("AVAILABLE".equals(state)||"PENDING".equals(state))jdbc.update("update ticket_delivery_benefit set state='REVOKED',update_time=NOW() where payment_id=? and state in ('AVAILABLE','PENDING')",payment);
    }
}
