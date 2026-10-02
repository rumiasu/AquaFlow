package com.example.aquaflow.mapper;
import org.apache.ibatis.annotations.*;
import java.math.BigDecimal;
import java.util.*;
@Mapper
public interface DispatchAgreementMapper {
    @Update("update dispatch_agreement set actual_net_barrels=#{qty},actual_barrel_items=#{items},barrel_disputed=#{disputed},dispute_note=#{note} where order_id=#{order} and status='ACCEPTED' and actual_net_barrels is null")
    int delivered(@Param("order") Long order,@Param("qty") int qty,@Param("items") String items,@Param("disputed") boolean disputed,@Param("note") String note);
    @Update("update dispatch_agreement set barrel_disputed=1,dispute_note=#{note},resolution_note=null,resolution_proposed_by=null,resolution_confirmed_by=null,source_barrel_confirmed_by=null,target_barrel_confirmed_by=null where order_id=#{order} and status='ACCEPTED' and (source_station_id=#{station} or target_station_id=#{station}) and actual_net_barrels is not null")
    int dispute(@Param("order") Long order,@Param("station") Long station,@Param("note") String note);
    @Update("update dispatch_agreement set barrel_mode=#{mode},barrel_amount=#{amount},resolution_note=#{note},resolution_proposed_by=#{operator},resolution_confirmed_by=null where order_id=#{order} and source_station_id=#{station} and status='ACCEPTED' and barrel_disputed=1")
    int propose(@Param("order") Long order,@Param("station") Long station,@Param("mode") String mode,@Param("amount") BigDecimal amount,@Param("operator") Long operator,@Param("note") String note);
    @Update("update dispatch_agreement set barrel_disputed=0,resolution_confirmed_by=#{operator} where order_id=#{order} and target_station_id=#{station} and status='ACCEPTED' and barrel_disputed=1 and resolution_proposed_by is not null")
    int agree(@Param("order") Long order,@Param("station") Long station,@Param("operator") Long operator);
    @Update("update dispatch_agreement set status='OFFERED',target_station_id=null,accepted_by=null,accepted_time=null where order_id=#{order} and status='ACCEPTED' and exists(select 1 from orders o where o.id=#{order} and o.status=1)") int recall(Long order);
    @Select("select * from dispatch_agreement where order_id=#{order}") Map<String,Object> get(Long order);
    @Select("select * from dispatch_agreement where order_id=#{order} for update") Map<String,Object> lock(Long order);
    @Select("select coalesce(sum(unit_price*decrease_qty),0) from ticket_record where order_id=#{order} and source='消耗'") BigDecimal ticketActual(Long order);
    @Insert("insert into dispatch_agreement(order_id,source_station_id,target_station_id,service_amount,net_barrels,barrel_mode,barrel_amount,barrel_items,status,quoted_by,note,create_time) values(#{order},#{source},#{target},#{service},#{qty},#{mode},#{barrel},#{items},'OFFERED',#{operator},#{note},now())")
    int insert(@Param("order") Long order,@Param("source") Long source,@Param("target") Long target,@Param("service") BigDecimal service,
               @Param("qty") int qty,@Param("mode") String mode,@Param("barrel") BigDecimal barrel,@Param("items") String items,@Param("operator") Long operator,@Param("note") String note);
    @Update("update dispatch_agreement set service_amount=#{service},barrel_mode=#{mode},barrel_amount=#{barrel},quoted_by=#{operator},note=#{note} where order_id=#{order} and status='OFFERED'")
    int quote(@Param("order") Long order,@Param("service") BigDecimal service,@Param("mode") String mode,@Param("barrel") BigDecimal barrel,@Param("operator") Long operator,@Param("note") String note);
    @Update("update dispatch_agreement set target_station_id=#{target},status='ACCEPTED',accepted_by=#{operator},accepted_time=now() where order_id=#{order} and status='OFFERED'")
    int accept(@Param("order") Long order,@Param("target") Long target,@Param("operator") Long operator);
    @Update("update dispatch_agreement set target_station_id=#{target},status='OFFERED',accepted_by=null,accepted_time=null where order_id=#{order} and status='OFFERED'")
    int route(@Param("order") Long order,@Param("target") Long target);
    @Select("select d.order_id as orderId,d.source_station_id as sourceStationId,d.target_station_id as targetStationId,d.source_barrel_confirmed_by as sourceConfirmedBy,d.target_barrel_confirmed_by as targetConfirmedBy,d.net_barrels as plannedBarrels,d.actual_net_barrels as netBarrels,d.barrel_mode as barrelMode,d.barrel_amount as barrelAmount,d.actual_barrel_items as barrelItems,d.barrel_disputed as disputed,d.dispute_note as disputeNote,d.resolution_note as resolutionNote,d.resolution_proposed_by as proposedBy,d.service_amount as serviceAmount,d.status,d.note from dispatch_agreement d join orders o on o.id=d.order_id where (d.source_station_id=#{station} or d.target_station_id=#{station}) and d.status='ACCEPTED' and (d.actual_barrel_items<>'' or d.barrel_disputed=1 or d.barrel_amount>0) and o.status in (3,4) order by d.order_id desc limit 200") List<Map<String,Object>> barrelBalances(Long station);
    @Update("update dispatch_agreement set source_barrel_confirmed_by=case when source_station_id=#{station} then #{operator} else source_barrel_confirmed_by end,target_barrel_confirmed_by=case when target_station_id=#{station} then #{operator} else target_barrel_confirmed_by end,close_note=#{note} where order_id=#{order} and ((source_station_id=#{station} and source_barrel_confirmed_by is null) or (target_station_id=#{station} and target_barrel_confirmed_by is null)) and barrel_disputed=0 and actual_net_barrels is not null and status='ACCEPTED' and exists(select 1 from orders o where o.id=#{order} and o.status in (3,4))")
    int closeBarrels(@Param("order") Long order,@Param("station") Long station,@Param("operator") Long operator,@Param("note") String note);
    @Update("update dispatch_agreement set status='BARREL_CLOSED',closed_by=#{operator},closed_time=now() where order_id=#{order} and status='ACCEPTED' and barrel_disputed=0 and source_barrel_confirmed_by is not null and target_barrel_confirmed_by is not null")
    int finishBarrels(@Param("order") Long order,@Param("operator") Long operator);
}
