package com.example.aquaflow.mapper;

import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.Map;

/** 合作订单期间的原约定与登记实款；无列表分页截断，不把报价当实收。 */
@Mapper
public interface GrossProfitCooperationMapper {
    String PERIOD = " o.create_time >= #{start} and o.create_time < #{endExclusive} ";

    @Select("select coalesce(sum(case when da.source_station_id=#{stationId} then da.service_amount else 0 end),0) as agreedPayAmount, "
            + "coalesce(sum(case when da.target_station_id=#{stationId} then da.service_amount else 0 end),0) as agreedReceiveAmount, "
            + "coalesce(sum(case when da.source_station_id=#{stationId} then "
            + "(case when o.payment_method=3 then coalesce((select sum(tr.decrease_qty*tr.unit_price) from ticket_record tr where tr.order_id=o.id and tr.decrease_qty>0),0) else o.water_amount end) "
            + "+o.delivery_fee+o.floor_fee-da.service_amount else 0 end),0) as quoteDifferenceAmount "
            + "from dispatch_agreement da join orders o on o.id=da.order_id "
            + "where da.status in ('ACCEPTED','BARREL_CLOSED') and (da.source_station_id=#{stationId} or da.target_station_id=#{stationId}) and " + PERIOD)
    Map<String,Object> agreements(@Param("stationId") Long stationId, @Param("start") LocalDateTime start, @Param("endExclusive") LocalDateTime endExclusive);

    // Original positive receipts retain status=3 after refund. Negative refund rows are separate facts.
    @Select("select coalesce(sum(case when pr.amount>0 and pr.status in (2,3) then pr.amount-coalesce(pr.barrel_deposit,0) else 0 end),0) as customerReceivedAmount, "
            + "coalesce(sum(case when pr.amount<0 and pr.status=3 then -(pr.amount-coalesce(pr.barrel_deposit,0)) else 0 end),0) as customerRefundedAmount "
            + "from payment_record pr join orders o on o.id=pr.order_id where pr.station_id=#{stationId} and pr.payment_method in (1,2) "
            + "and (exists(select 1 from dispatch_agreement da where da.order_id=o.id and da.status in ('ACCEPTED','BARREL_CLOSED')) "
            + "or exists(select 1 from inter_station_settlement st where st.order_id=o.id) or coalesce(o.delivery_station_id,o.station_id)<>o.station_id) and " + PERIOD)
    Map<String,Object> customerCash(@Param("stationId") Long stationId, @Param("start") LocalDateTime start, @Param("endExclusive") LocalDateTime endExclusive);

    // Reversal voids the claim, not an earlier actual payment registration.
    @Select("select coalesce(sum(case when st.from_station_id=#{stationId} then st.amount else 0 end),0) as settledPayAmount, "
            + "coalesce(sum(case when st.to_station_id=#{stationId} then st.amount else 0 end),0) as settledReceiveAmount "
            + "from inter_station_settlement st join orders o on o.id=st.order_id where st.settled_time is not null "
            + "and (st.from_station_id=#{stationId} or st.to_station_id=#{stationId}) and " + PERIOD)
    Map<String,Object> settlements(@Param("stationId") Long stationId, @Param("start") LocalDateTime start, @Param("endExclusive") LocalDateTime endExclusive);

    @Select("select coalesce(sum(case when r.from_station_id=#{stationId} and r.status='PENDING' and r.sender_confirmed_time is null then r.amount else 0 end),0) as recoveryPendingPayAmount, "
            + "coalesce(sum(case when r.to_station_id=#{stationId} and r.status='PENDING' then r.amount else 0 end),0) as recoveryPendingReceiveAmount, "
            + "coalesce(sum(case when r.from_station_id=#{stationId} and r.sender_confirmed_time is not null then r.amount else 0 end),0) as recoverySentPayAmount, "
            + "coalesce(sum(case when r.to_station_id=#{stationId} and r.status='RECEIVED' and r.receiver_confirmed_time is not null then r.amount else 0 end),0) as recoveryReceivedAmount, "
            + "coalesce(sum(case when r.to_station_id=#{stationId} and r.status='PENDING' and r.sender_confirmed_time is not null then r.amount else 0 end),0) as recoveryAwaitingConfirmationAmount "
            + "from inter_station_recovery r join orders o on o.id=r.order_id where (r.from_station_id=#{stationId} or r.to_station_id=#{stationId}) and " + PERIOD)
    Map<String,Object> recoveries(@Param("stationId") Long stationId, @Param("start") LocalDateTime start, @Param("endExclusive") LocalDateTime endExclusive);
}
