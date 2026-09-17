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
     * 插入或更新本站的商品配置（上架开关 / 本站售价 / 本站押金 / 水票开关与价格 / 优先展示 / 库存）。
     * 不存在则插入，已存在则整行覆盖。
     *
     * <p>⚠️ <b>调用方必须先取当前行，把没打算改的字段一并带上</b>（尤其是本站售价/本站押金/数量）：
     * 本方法是"整行覆盖"语义，漏传就会被写成 NULL/0。历史上正是因为这里缺省落 0，
     * 站长只想改水票价格时把本站库存静默清零 → 对账不平（见 ManagerProductController 的 [P1-2] 注释）。</p>
     *
     * <p>⚠️ 写 {@code quantity} 会<b>绕过 inventory_record 流水</b>。库存增减一律走
     * {@code InventoryService#setStock}（写 ADJUST 流水）；本方法只允许在"新建行"或
     * "带回原值"两种场景下带上 quantity。</p>
     */
    @Insert("insert into inventory(station_id, product_id, quantity, enabled, sale_price, deposit_price, " +
            "ticket_enabled, ticket_price, priority_display, create_time, update_time) " +
            "values(#{stationId}, #{productId}, #{quantity}, #{enabled}, #{salePrice}, #{depositPrice}, " +
            "#{ticketEnabled}, #{ticketPrice}, #{priorityDisplay}, NOW(), NOW()) " +
            "on duplicate key update quantity = #{quantity}, enabled = #{enabled}, " +
            "sale_price = #{salePrice}, deposit_price = #{depositPrice}, " +
            "ticket_enabled = #{ticketEnabled}, ticket_price = #{ticketPrice}, " +
            "priority_display = #{priorityDisplay}, update_time = NOW()")
    void upsertSettings(Inventory inventory);

    @Update("update inventory set priority_display=#{priorityDisplay}, update_time=NOW() where id=#{id}")
    void updatePriorityDisplay(@Param("id") Long id, @Param("priorityDisplay") Integer priorityDisplay);

    @Select("select count(*) from inventory where station_id = #{stationId} and priority_display = 1")
    int countPriorityDisplay(@Param("stationId") Long stationId);
}