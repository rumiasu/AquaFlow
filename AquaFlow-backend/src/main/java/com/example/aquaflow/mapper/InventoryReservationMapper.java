package com.example.aquaflow.mapper;

import com.example.aquaflow.constant.ReservationStatus;
import com.example.aquaflow.entity.InventoryReservation;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 库存预留凭据（`inventory_reservation`）Mapper。
 *
 * <p>⚠️ 读"活跃凭据"一律用 {@code ...ForUpdate}（当前读 + 行锁）：补预留、换站、出库都要基于
 * **最新**的预留量判断，REPEATABLE READ 下普通 SELECT 读到的是旧快照（AGENTS §8.2 的老坑）。
 * 纯展示/校验用的汇总（{@link #sumReserved}）才走普通读。</p>
 */
@Mapper
public interface InventoryReservationMapper {

    @Insert("insert into inventory_reservation(order_id, order_item_id, product_id, station_id, " +
            "reserved_qty, shipped_qty, released_qty, status, create_time, update_time) " +
            "values(#{orderId}, #{orderItemId}, #{productId}, #{stationId}, " +
            "#{reservedQty}, #{shippedQty}, #{releasedQty}, #{status}, NOW(), NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(InventoryReservation reservation);

    @Select("select * from inventory_reservation where id = #{id}")
    InventoryReservation getById(@Param("id") Long id);

    /** 该单的全部凭据（含历史行），按 id 升序 —— 给排查与对账看轨迹。 */
    @Select("select * from inventory_reservation where order_id = #{orderId} order by id asc")
    List<InventoryReservation> listByOrderId(@Param("orderId") Long orderId);

    /** 该单的**活跃**凭据并加行锁（出库 / 换站 / 释放都从这里进）。 */
    @Select("select * from inventory_reservation where order_id = #{orderId} and status = "
            + ReservationStatus.RESERVED + " order by id asc for update")
    List<InventoryReservation> listActiveByOrderIdForUpdate(@Param("orderId") Long orderId);

    /**
     * 本站该商品"缺货待补"的行，按**下单先后**（id 升序）加锁，返回 {@code {id, needQty}}。
     *
     * <p>{@code needQty} 来自订单明细的联接、不属于凭据本身，所以用 Map 形态；
     * SQL 里**显式 as 出**这两个别名（Map 形态的既有约定：别指望下划线转驼峰）。</p>
     */
    @Select("select r.id as id, (oi.quantity - r.reserved_qty) as needQty "
            + "from inventory_reservation r join order_item oi on oi.id = r.order_item_id "
            + "where r.station_id = #{stationId} and r.product_id = #{productId} "
            + "and r.status = " + ReservationStatus.RESERVED + " and r.reserved_qty < oi.quantity "
            + "order by r.id asc for update")
    List<java.util.Map<String, Object>> listShortageNeedForUpdate(@Param("stationId") Long stationId,
                                                                  @Param("productId") Long productId);

    /** 本站该商品已预留总量（普通读，给"可用量"展示与校验用）。 */
    @Select("select coalesce(sum(reserved_qty), 0) from inventory_reservation "
            + "where station_id = #{stationId} and product_id = #{productId} and status = "
            + ReservationStatus.RESERVED)
    int sumReserved(@Param("stationId") Long stationId, @Param("productId") Long productId);

    /**
     * 该单的"缺货待补"总量 = Σ(订单明细需求量 − 已预留量)。
     * <p>完成配送前必须为 0，否则说明这批货还没落到实物上（见 {@code InventoryReservationService.shipForOrder}）。</p>
     */
    @Select("select coalesce(sum(oi.quantity - r.reserved_qty), 0) from inventory_reservation r "
            + "join order_item oi on oi.id = r.order_item_id "
            + "where r.order_id = #{orderId} and r.status = " + ReservationStatus.RESERVED)
    int shortageOfOrder(@Param("orderId") Long orderId);

    /**
     * 补预留（CAS：仅当仍是活跃凭据）。
     * <p>写成 {@code reserved_qty = reserved_qty + delta} 而不是"读出来再加"：并发入库补预留时
     * 后者会互相覆盖（同 `staff_earning` 的 `exception_count` 自增那条判据）。</p>
     *
     * @return 受影响行数；0 = 这份凭据已经出库或被释放了
     */
    @Update("update inventory_reservation set reserved_qty = reserved_qty + #{delta}, update_time = NOW() "
            + "where id = #{id} and status = " + ReservationStatus.RESERVED)
    int addReservedIfActive(@Param("id") Long id, @Param("delta") Integer delta);

    /**
     * 换站重建：把这条凭据改挂到新站并写入新站算出的预留量（CAS：仅当仍是活跃凭据）。
     *
     * @return 受影响行数；0 = 已被处理（并发换站/取消）
     */
    @Update("update inventory_reservation set station_id = #{stationId}, reserved_qty = #{reservedQty}, "
            + "update_time = NOW() where id = #{id} and status = " + ReservationStatus.RESERVED)
    int moveToStationIfActive(@Param("id") Long id, @Param("stationId") Long stationId,
                              @Param("reservedQty") Integer reservedQty);

    @Update("update inventory_reservation set status = " + ReservationStatus.RELEASED + ", "
            + "released_qty = reserved_qty, update_time = NOW() where id = #{id} and status = "
            + ReservationStatus.RESERVED)
    int releaseIfActive(@Param("id") Long id);

    /**
     * 释放整张单的活跃凭据（取消 / 拒单 / 超时扫单的单一出口）。
     *
     * @return 受影响行数（>0 = 确实释放了；0 = 本单没有活跃凭据，通常是重复取消）
     */
    @Update("update inventory_reservation set status = " + ReservationStatus.RELEASED + ", "
            + "released_qty = reserved_qty, update_time = NOW() "
            + "where order_id = #{orderId} and status = " + ReservationStatus.RESERVED)
    int releaseByOrderId(@Param("orderId") Long orderId);

    @Update("update inventory_reservation set status = " + ReservationStatus.SHIPPED + ", "
            + "shipped_qty = reserved_qty, update_time = NOW() where id = #{id} and status = "
            + ReservationStatus.RESERVED)
    int shipIfActive(@Param("id") Long id);
}
