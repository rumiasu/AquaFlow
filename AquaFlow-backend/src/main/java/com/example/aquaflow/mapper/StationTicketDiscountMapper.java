package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.StationTicketDiscount;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 水站「统一折扣」档位（v58）。规格见 {@code docs/design/26} §26.11、{@code entity/StationTicketDiscount}。
 *
 * <p>表里**只有折扣、没有价格** —— 价格按各款水自己的水票价现算，不落库。</p>
 */
@Mapper
public interface StationTicketDiscountMapper {

    /** 客户端可见的档位：仅上架，按 sort 再按张数升序 */
    @Select("select * from station_ticket_discount where station_id = #{stationId} and status = 1 "
            + "order by sort asc, qty asc")
    List<StationTicketDiscount> listOnShelf(@Param("stationId") Long stationId);

    /** 站长端：含已下架 */
    @Select("select * from station_ticket_discount where station_id = #{stationId} order by sort asc, qty asc")
    List<StationTicketDiscount> listAll(@Param("stationId") Long stationId);

    @Select("select * from station_ticket_discount where id = #{id}")
    StationTicketDiscount getById(@Param("id") Long id);

    /** 购买时按张数定位档位（唯一键 (station_id, qty)）—— 客户端只传张数，价格由服务端算 */
    @Select("select * from station_ticket_discount where station_id = #{stationId} and qty = #{qty} and status = 1")
    StationTicketDiscount getOnShelfByQty(@Param("stationId") Long stationId, @Param("qty") Integer qty);

    /** 站长保存后回显用：不看上下架状态（保存成下架时也要能取回刚存的那一行） */
    @Select("select * from station_ticket_discount where station_id = #{stationId} and qty = #{qty}")
    StationTicketDiscount getByStationAndQty(@Param("stationId") Long stationId, @Param("qty") Integer qty);

    /**
     * 保存档位（有则更新、无则插新）。
     *
     * <p>upsert 而不是"先查再决定"：并发两次保存会双双查不到、双双 insert 撞唯一键。</p>
     */
    @Insert("insert into station_ticket_discount(station_id, qty, discount_per_mille, title, status, sort, create_time, update_time) "
            + "values(#{stationId}, #{qty}, #{discountPerMille}, #{title}, #{status}, #{sort}, NOW(), NOW()) "
            + "on duplicate key update discount_per_mille=values(discount_per_mille), title=values(title), "
            + "status=values(status), sort=values(sort), update_time=NOW()")
    int upsert(StationTicketDiscount discount);

    /** 删除档位；带 station_id 条件，返回受影响行数（调用方必须检查：0 = 不是本站的档位） */
    @Delete("delete from station_ticket_discount where id = #{id} and station_id = #{stationId}")
    int delete(@Param("id") Long id, @Param("stationId") Long stationId);

    /** 本站已上架的档位数 —— "统一折扣是否生效"的唯一判据（没有就返回 0） */
    @Select("select count(*) from station_ticket_discount where station_id = #{stationId} and status = 1")
    int countOnShelf(@Param("stationId") Long stationId);
}
