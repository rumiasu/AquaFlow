package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.Inventory;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface InventoryMapper {

    @Insert("insert into inventory(station_id, product_id, quantity, enabled, ticket_enabled, ticket_price, priority_display, create_time, update_time) " +
            "values(#{stationId}, #{productId}, #{quantity}, #{enabled}, #{ticketEnabled}, #{ticketPrice}, #{priorityDisplay}, #{createTime}, #{updateTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(Inventory inventory);

    @Select("select * from inventory where id = #{id}")
    Inventory getById(@Param("id") Long id);

    @Update("update inventory set quantity=#{quantity}, enabled=#{enabled}, ticket_enabled=#{ticketEnabled}, " +
            "ticket_price=#{ticketPrice}, priority_display=#{priorityDisplay}, update_time=NOW() where id=#{id}")
    void update(Inventory inventory);

    @Delete("delete from inventory where id = #{id}")
    void delete(@Param("id") Long id);

    @Select("select i.*, p.name as product_name, p.spec as spec, p.image_object_name as image_object_name, " +
            "p.status as status, p.category as category " +
            "from inventory i left join product p on i.product_id = p.id " +
            "where i.station_id = #{stationId} order by i.id asc")
    List<Inventory> listByStationId(@Param("stationId") Long stationId);

    @Select("select * from inventory where station_id = #{stationId} and product_id = #{productId}")
    Inventory getByStationAndProduct(@Param("stationId") Long stationId, @Param("productId") Long productId);

    @Select("select * from inventory")
    List<Inventory> list();

    @Update("update inventory set quantity = quantity - #{quantity}, update_time = NOW() " +
            "where station_id = #{stationId} and product_id = #{productId} and quantity >= #{quantity}")
    int decreaseStock(@Param("stationId") Long stationId, @Param("productId") Long productId, @Param("quantity") Integer quantity);

    @Update("update inventory set quantity = quantity + #{quantity}, update_time = NOW() " +
            "where station_id = #{stationId} and product_id = #{productId}")
    void increaseStock(@Param("stationId") Long stationId, @Param("productId") Long productId, @Param("quantity") Integer quantity);

    @Insert("insert into inventory(station_id, product_id, quantity, enabled, ticket_enabled, ticket_price, priority_display, create_time, update_time) " +
            "values(#{stationId}, #{productId}, #{quantity}, 1, 0, 0, 0, NOW(), NOW()) " +
            "on duplicate key update quantity = quantity + #{quantity}, update_time = NOW()")
    void upsertQuantity(@Param("stationId") Long stationId, @Param("productId") Long productId, @Param("quantity") Integer quantity);

    /**
     * 插入或更新库存设置 (上架开关/水票开关/水票价格/优先展示).
     * 不存在则插入 (quantity 默认 0), 已存在则更新设置字段.
     */
    @Insert("insert into inventory(station_id, product_id, quantity, enabled, ticket_enabled, ticket_price, priority_display, create_time, update_time) " +
            "values(#{stationId}, #{productId}, #{quantity}, #{enabled}, #{ticketEnabled}, #{ticketPrice}, #{priorityDisplay}, NOW(), NOW()) " +
            "on duplicate key update enabled = #{enabled}, ticket_enabled = #{ticketEnabled}, " +
            "ticket_price = #{ticketPrice}, priority_display = #{priorityDisplay}, " +
            "quantity = #{quantity}, update_time = NOW()")
    void upsertSettings(@Param("stationId") Long stationId, @Param("productId") Long productId,
                       @Param("quantity") Integer quantity, @Param("enabled") Integer enabled,
                       @Param("ticketEnabled") Integer ticketEnabled,
                       @Param("ticketPrice") java.math.BigDecimal ticketPrice,
                       @Param("priorityDisplay") Integer priorityDisplay);

    @Update("update inventory set priority_display=#{priorityDisplay}, update_time=NOW() where id=#{id}")
    void updatePriorityDisplay(@Param("id") Long id, @Param("priorityDisplay") Integer priorityDisplay);

    @Select("select count(*) from inventory where station_id = #{stationId} and priority_display = 1")
    int countPriorityDisplay(@Param("stationId") Long stationId);
}