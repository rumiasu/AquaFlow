package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.TicketPackage;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 水票档位套餐（v36）。
 *
 * <p>档位按 {@code (station_id, product_id, qty)} 唯一 —— 同一商品同一张数只能有一个档位，
 * 否则"10 张到底多少钱"会有两个答案。</p>
 */
@Mapper
public interface TicketPackageMapper {

    /** 客户端可见的档位：仅上架，按 sort 再按张数升序 */
    @Select("select * from ticket_package where station_id = #{stationId} and product_id = #{productId} "
            + "and status = 1 order by sort asc, qty asc")
    List<TicketPackage> listOnShelf(@Param("stationId") Long stationId, @Param("productId") Long productId);

    /** 站长端：含已下架 */
    @Select("select * from ticket_package where station_id = #{stationId} and product_id = #{productId} "
            + "order by sort asc, qty asc")
    List<TicketPackage> listAll(@Param("stationId") Long stationId, @Param("productId") Long productId);

    @Select("select * from ticket_package where id = #{id}")
    TicketPackage getById(@Param("id") Long id);

    @Select("select * from ticket_package where station_id = #{stationId} and product_id = #{productId} and qty = #{qty}")
    TicketPackage getByStationProductQty(@Param("stationId") Long stationId,
                                         @Param("productId") Long productId,
                                         @Param("qty") Integer qty);

    /**
     * 保存档位（有则更新、无则插新）。
     *
     * <p>upsert 而不是"先查再决定"：并发两次保存会双双查不到、双双 insert 撞唯一键。</p>
     *
     * <p>⚠️ {@code unit_price} 由调用方算好（{@code price / qty}）——
     * 它是快照进 {@code ticket_lot.unit_price} 的值，必须落库一次、展示与快照共用，
     * 不能"展示时四舍五入、快照时用原始值"。</p>
     */
    @Insert("insert into ticket_package(station_id, product_id, qty, price, unit_price, title, status, sort, create_time, update_time) "
            + "values(#{stationId}, #{productId}, #{qty}, #{price}, #{unitPrice}, #{title}, #{status}, #{sort}, NOW(), NOW()) "
            + "on duplicate key update price=values(price), unit_price=values(unit_price), title=values(title), "
            + "status=values(status), sort=values(sort), update_time=NOW()")
    int upsert(TicketPackage pkg);

    /** 删除档位；带 station_id 条件，返回受影响行数（调用方必须检查：0 = 不是本站的档位） */
    @Delete("delete from ticket_package where id = #{id} and station_id = #{stationId}")
    int delete(@Param("id") Long id, @Param("stationId") Long stationId);
}
