package com.example.aquaflow.mapper;
import org.apache.ibatis.annotations.*;
import java.util.*;
@Mapper
public interface BusinessWaitingMapper {
    /** 精确定位原申请仍按本站过滤，不能用历史列表上限判断申请不存在。 */
    @Select("select * from barrel_record where id=#{record} and station_id=#{station} and type=2")
    com.example.aquaflow.entity.BarrelRecord returnRecord(@Param("record") Long record, @Param("station") Long station);
    // 2026-10-02：列表曾混入已结案历史、且上限被当总数。列表/COUNT 共用谓词，
    // 当前站必须有下一步办理责任；不能按“两站之一”把对方责任也报成本站待办。
    int WAITING_LIMIT = 200;
    String STOCK_FROM = " from orders o join inventory_reservation r on r.order_id=o.id and r.status=1"
            + " where coalesce(o.delivery_station_id,o.station_id)=#{station} and o.status in (1,2)"
            + " and (o.payment_status=2 or o.payment_method=2) and r.need_qty>r.reserved_qty";
    String RETURNS_FROM = " from barrel_return_detail d join barrel_record r on r.id=d.record_id"
            + " where r.station_id=#{station} and d.station_id=r.station_id and r.type=2"
            + " and ((d.status='APPLIED' and r.create_time<date_sub(now(),interval 2 hour)) or d.status='RECEIVED')";
    String RECOVERY_FROM = " from inter_station_recovery r where r.status='PENDING' and"
            + " ((r.from_station_id=#{station} and r.sender_confirmed_time is null)"
            + " or (r.to_station_id=#{station} and r.sender_confirmed_time is not null))";
    String BARREL_FROM = " from dispatch_agreement d join orders o on o.id=d.order_id"
            + " where d.status='ACCEPTED' and o.status in (3,4) and d.actual_net_barrels is not null"
            + " and (d.actual_barrel_items<>'' or d.barrel_disputed=1 or d.barrel_amount>0) and"
            + " ((d.barrel_disputed=0 and ((d.source_station_id=#{station} and d.source_barrel_confirmed_by is null)"
            + " or (d.target_station_id=#{station} and d.target_barrel_confirmed_by is null)))"
            + " or (d.barrel_disputed=1 and ((d.source_station_id=#{station} and d.resolution_proposed_by is null)"
            + " or (d.target_station_id=#{station} and d.resolution_proposed_by is not null))))";
    @Select("select distinct o.id from orders o join barrel_right_reservation r on r.owner_type='ORDER' and r.owner_id=o.id where o.status=1 and o.payment_status=0 and o.payment_method<>2 and o.create_time<date_sub(now(),interval #{minutes} minute) and not exists(select 1 from payment_record p where p.order_id=o.id and p.status in (1,2)) order by o.id limit 100")
    List<Long> unpaidExpired(int minutes);
    @Select("select count(*) from payment_record where order_id=#{order} and status in (1,2)") int hasActivePayment(Long order);
    @Select("select o.id as orderId,o.order_no as orderNo,o.create_time as createTime,"
            + "o.create_time as waitingSinceTime,coalesce(o.delivery_station_id,o.station_id) as responsibleStationId,"
            + "sum(greatest(0,r.need_qty-r.reserved_qty)) as shortageQty" + STOCK_FROM
            + " group by o.id,o.order_no,o.create_time,o.delivery_station_id,o.station_id order by o.create_time,o.id limit " + WAITING_LIMIT)
    List<Map<String,Object>> waitingStock(Long station);
    @Select("select count(distinct o.id)" + STOCK_FROM)
    int countWaitingStock(Long station);
    @Select("select r.id as recordId,r.customer_id as customerId,d.status,d.refund_due_time as refundDueTime,"
            + "d.approved_time as approvedTime,r.deposit_refund as refundAmount,r.station_id as responsibleStationId,"
            + "coalesce(d.received_time,r.create_time) as waitingSinceTime,"
            + "case when d.status='RECEIVED' and d.refund_due_time<now() then 1 else 0 end as overdue" + RETURNS_FROM
            + " order by waitingSinceTime,r.id limit " + WAITING_LIMIT)
    List<Map<String,Object>> delayedReturns(Long station);
    @Select("select count(*) as returnsTotal,coalesce(sum(d.status='RECEIVED'),0) as returnRefund" + RETURNS_FROM)
    Map<String,Object> countWaitingReturns(Long station);
    @Select("select r.order_id as orderId,r.from_station_id as fromStationId,r.to_station_id as toStationId,"
            + "r.amount,r.status,r.sender_confirmed_time as senderConfirmedTime,#{station} as responsibleStationId,"
            + "coalesce(r.sender_confirmed_time,r.create_time) as waitingSinceTime,"
            + "case when r.sender_confirmed_time is null then 'sent' else 'received' end as nextAction" + RECOVERY_FROM
            + " order by waitingSinceTime,r.order_id limit " + WAITING_LIMIT)
    List<Map<String,Object>> waitingRecoveries(Long station);
    @Select("select coalesce(sum(r.sender_confirmed_time is null),0) as recoverySend,"
            + "coalesce(sum(r.sender_confirmed_time is not null),0) as recoveryReceive" + RECOVERY_FROM)
    Map<String,Object> countWaitingRecoveries(Long station);
    @Select("select d.order_id as orderId,d.source_station_id as sourceStationId,d.target_station_id as targetStationId,"
            + "d.actual_net_barrels as netBarrels,d.barrel_mode as barrelMode,d.barrel_amount as barrelAmount,"
            + "d.actual_barrel_items as barrelItems,d.barrel_disputed as disputed,d.dispute_note as disputeNote,"
            + "d.resolution_note as resolutionNote,d.accepted_time as waitingSinceTime,#{station} as responsibleStationId,"
            + "case when d.barrel_disputed=0 then 'barrels' when d.source_station_id=#{station} then 'proposal' else 'agree' end as nextAction"
            + BARREL_FROM + " order by waitingSinceTime,d.order_id limit " + WAITING_LIMIT)
    List<Map<String,Object>> waitingBarrels(Long station);
    @Select("select coalesce(sum(d.barrel_disputed=0),0) as barrelHandover,"
            + "coalesce(sum(d.barrel_disputed=1),0) as barrelDispute" + BARREL_FROM)
    Map<String,Object> countWaitingBarrels(Long station);
}
