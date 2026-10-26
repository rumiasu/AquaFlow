package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.Inventory;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface InventoryMapper {

    /** One-ticket tier compatibility only; never touches physical stock or other pricing. */
    @Update("update inventory set ticket_price=#{price}, update_time=NOW() "
            + "where station_id=#{stationId} and product_id=#{productId}")
    int updateTicketPrice(@Param("stationId") Long stationId, @Param("productId") Long productId,
                          @Param("price") java.math.BigDecimal price);

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

    /**
     * 移除本站对该商品的配置行（站长"不卖了"）。
     * <p>⚠️ 只删 {@code inventory} 行：{@code inventory_record} 流水必须保留（那是审计证据），
     * 商品行也不能删（历史订单/桶账/水票都引用 {@code product.id}）。
     * 调用方必须先确认库存已盘到 0，否则库存数会凭空消失、流水再也对不上。</p>
     *
     * @return 受影响行数
     */
    @Delete("delete from inventory where station_id = #{stationId} and product_id = #{productId}")
    int deleteByStationAndProduct(@Param("stationId") Long stationId, @Param("productId") Long productId);

    /**
     * 同 {@link #getByStationAndProduct}，但加行锁（当前读）。
     * <p>库存盘点/入库必须先锁行再算差额，否则两个并发盘点会各自读到旧值、
     * 算出两个 delta，最后库存数是"最后一次覆盖"而不是两次之和。</p>
     */
    @Select("select * from inventory where station_id = #{stationId} and product_id = #{productId} for update")
    Inventory getByStationAndProductForUpdate(@Param("stationId") Long stationId, @Param("productId") Long productId);

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
     * 插入或更新本站商品设置。新行初始化 quantity，已有行保留实时库存和图片覆盖。
     * 价格与开关仍需调用方带回未编辑字段；库存调整只走有流水的 setStock。
     * duplicate 分支不得覆盖 quantity，避免旧快照撤销并发入库。
     */
    @Insert("insert into inventory(station_id, product_id, quantity, enabled, sale_price, deposit_price, " +
            "ticket_enabled, ticket_price, priority_display, create_time, update_time) " +
            "values(#{stationId}, #{productId}, #{quantity}, #{enabled}, #{salePrice}, #{depositPrice}, " +
            "#{ticketEnabled}, #{ticketPrice}, #{priorityDisplay}, NOW(), NOW()) " +
            "on duplicate key update enabled = #{enabled}, " +
            "sale_price = #{salePrice}, deposit_price = #{depositPrice}, " +
            "ticket_enabled = #{ticketEnabled}, ticket_price = #{ticketPrice}, " +
            "priority_display = #{priorityDisplay}, update_time = NOW()")
    void upsertSettings(Inventory inventory);

    /** 只更新本站该商品的图片引用；其他设置写入不会覆盖此列。 */
    @Update("update inventory set image_object_name_override=#{imageObjectName}, update_time=NOW() " +
            "where station_id=#{stationId} and product_id=#{productId}")
    int updateStationImage(@Param("stationId") Long stationId, @Param("productId") Long productId,
                           @Param("imageObjectName") String imageObjectName);


    @Update("update inventory set priority_display=#{priorityDisplay}, update_time=NOW() where id=#{id}")
    void updatePriorityDisplay(@Param("id") Long id, @Param("priorityDisplay") Integer priorityDisplay);

    @Select("select count(*) from inventory where station_id = #{stationId} and priority_display = 1")
    int countPriorityDisplay(@Param("stationId") Long stationId);
}
