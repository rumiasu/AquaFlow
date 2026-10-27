package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.StaffEarning;
import com.example.aquaflow.entity.StaffPayroll;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;

/** 工资预览只读来源；锁内当前读与预览使用相同边界，不能按当前单价重算已发生的工资。 */
@Mapper
public interface PayrollPreviewMapper {
    @Select("<script>select * from staff_earning where station_id=#{stationId} and staff_id=#{staffId} "
            + "and create_time &gt;= #{start} and create_time &lt; #{end} order by id "
            + "<if test='locking'>for update</if></script>")
    List<StaffEarning> earnings(@Param("stationId") Long stationId, @Param("staffId") Long staffId,
            @Param("start") LocalDateTime start, @Param("end") LocalDateTime end, @Param("locking") boolean locking);

    @Select("<script>select * from staff_payroll where id=#{id} and station_id=#{stationId} "
            + "<if test='locking'>for update</if></script>")
    StaffPayroll payroll(@Param("stationId") Long stationId, @Param("id") Long id, @Param("locking") boolean locking);

    /** 旧规则缺失时可能根本没有收益行；不能把这样的已送达订单说成零工资。 */
    @Select("select count(*) from orders o join order_item i on i.order_id=o.id "
            + "left join staff_piece_rate r on r.station_id=#{stationId} and r.product_id=i.product_id "
            + "left join staff_piece_rate d on d.station_id=#{stationId} and d.product_id=0 "
            + OrderMapper.DELIVERY_EVENT_JOINS
            + "where coalesce(o.delivery_station_id,o.station_id)=#{stationId} and o.delivery_staff_id=#{staffId} "
            + "and o.status in (3,4) and ((" + OrderMapper.DELIVERY_EVENT_TIME + ") >= #{start} "
            + "and (" + OrderMapper.DELIVERY_EVENT_TIME + ") < #{end} or (" + OrderMapper.DELIVERY_EVENT_TIME
            + ") is null and o.create_time < #{end}) "
            + "and i.quantity > 0 and (coalesce(r.per_bucket_amount,d.per_bucket_amount) is null "
            + "or coalesce(r.per_bucket_amount,d.per_bucket_amount)>0) "
            + "and not exists(select 1 from staff_earning e where e.order_id=o.id and e.staff_id=#{staffId} "
            + "and e.station_id=#{stationId} and e.product_id=i.product_id and e.kind='DELIVERY_BUCKET')")
    int unrecordedDeliveries(@Param("stationId") Long stationId, @Param("staffId") Long staffId,
            @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
}
