package com.example.aquaflow.mapper;

import com.example.aquaflow.constant.ReservationStatus;
import com.example.aquaflow.entity.InventoryReservation;
import org.apache.ibatis.annotations.*;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 库存预留凭据（`inventory_reservation`）Mapper。
 *
 * <p><b>当前读纪律（2026-09-25 返工 R1）</b>：所有**分配决策**（预留量、补位量、换站重建量、出库量）
 * 读活跃凭据必须走 {@code ...ForUpdate}——REPEATABLE READ 下普通 SELECT 读的是事务开始时的快照，
 * 只锁 {@code inventory} 行并不能让后面的 SUM 变成当前读（AGENTS §8.2 的「加锁 + 当前读」两件套）。</p>
 *
 * <p>⚠️ <b>锁定读里不要 join {@code orders} / {@code order_item}</b>：MySQL 的 {@code FOR UPDATE}
 * 会锁住语句引用到的**所有**表的行，那会把订单行也卷进来，与订单工作流的锁形成新环。
 * 需要"业务需求时间/需求量"这类**不可变**数据时，先用只锁凭据表的当前读取行，再用普通读补齐
 * （`order_item.quantity` 与 `orders.create_time` 建单后不再变化，快照读它们没有风险）。</p>
 */
@Mapper
public interface InventoryReservationMapper {

    @Insert("insert into inventory_reservation(order_id, order_item_id, product_id, station_id, " +
            "need_qty, need_time, reserved_qty, shipped_qty, released_qty, status, create_time, update_time) " +
            "values(#{orderId}, #{orderItemId}, #{productId}, #{stationId}, " +
            "#{needQty}, #{needTime}, #{reservedQty}, #{shippedQty}, #{releasedQty}, #{status}, NOW(), NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(InventoryReservation reservation);

    @Select("select * from inventory_reservation where id = #{id}")
    InventoryReservation getById(@Param("id") Long id);

    /** 该单的全部凭据（含历史行），按 id 升序 —— 给排查与对账看轨迹。 */
    @Select("select * from inventory_reservation where order_id = #{orderId} order by id asc")
    List<InventoryReservation> listByOrderId(@Param("orderId") Long orderId);

    /**
     * 该单的**活跃**凭据，**不加锁**（纯发现用）。
     *
     * <p>用途：B1 锁序协议的第一步 —— 先把"这次要动哪些 (站,商品)"**发现**出来（这一步读的是快照，
     * **不能**当作决策依据），按 (站,商品) 升序锁完 `inventory` 行之后，再用
     * {@link #listActiveByOrderIdForUpdate} 做**当前读 + 重新验证**：集合与站别与发现时不一致
     * ⇒ 资源集合在发现到加锁之间变了，必须抛可读冲突（让人从事务外重试），不能照着旧集合继续做。</p>
     */
    @Select("select * from inventory_reservation where order_id = #{orderId} and status = "
            + ReservationStatus.RESERVED + " order by id asc")
    List<InventoryReservation> listActiveByOrderId(@Param("orderId") Long orderId);

    /** 该单的**活跃**凭据并加行锁（出库 / 换站 / 释放都从这里进）。 */
    @Select("select * from inventory_reservation where order_id = #{orderId} and status = "
            + ReservationStatus.RESERVED + " order by id asc for update")
    List<InventoryReservation> listActiveByOrderIdForUpdate(@Param("orderId") Long orderId);

    /**
     * 本站该商品的**活跃**凭据并加行锁（补位决策用）。
     * <p>只锁凭据行（不带 join，见类注释）；排序在服务里按"业务需求时间"做。</p>
     */
    @Select("select * from inventory_reservation where station_id = #{stationId} and product_id = #{productId} "
            + "and status = " + ReservationStatus.RESERVED + " order by id asc for update")
    List<InventoryReservation> listActiveForUpdate(@Param("stationId") Long stationId,
                                                   @Param("productId") Long productId);

    /** 本站该商品已预留总量（普通读，只给展示/查询用，**不得用于分配决策**）。 */
    @Select("select coalesce(sum(reserved_qty), 0) from inventory_reservation "
            + "where station_id = #{stationId} and product_id = #{productId} and status = "
            + ReservationStatus.RESERVED)
    int sumReserved(@Param("stationId") Long stationId, @Param("productId") Long productId);

    /*
     * 已删除：`listItemNeedAndTime(ids)`（2026-09-25 二次收口 B2）。
     * 它用普通 SELECT join `orders`/`order_item` 现读"需求量 + 下单时间"，注释当时写的理由是
     * "这两个字段不可变，所以快照读安全" —— **字段不可变不代表这行在旧快照里已经存在**：
     * REPEATABLE READ 下，当前读能看到刚提交的**新凭据**，普通读却看不到同一批提交里刚插入的
     * **订单明细**，于是新等待单被当成 need=0 静默跳过（有货不分给它）。
     * 现在需求量/时间随凭据行一起存（`need_qty` / `need_time`，v65），补位只读凭据表。
     * 不要为了"少存一列"把它加回来。
     */

    /**
     * 该单的"缺货待补"总量 = Σ(需求量快照 − 已预留量)，只看活跃凭据。
     *
     * <p>⚠️ 用凭据上的 {@code need_qty} 快照（v65），**不再 join** {@code order_item}：
     * 二次验收 B2 的反例是"当前读看得到新凭据、普通读看不到同一批提交里刚插的明细"——
     * 只要这里还 join，就仍可能读到旧快照（读不到就被当成 need=0）。
     * 快照与真相源的一致性由对账 {@code E15} 保证。</p>
     *
     * <p>⚠️ 它**不覆盖"整条明细没有凭据"**的情况（那种缺口的判据是
     * {@link #countInflightItemsWithoutCredential}），完成配送的完整性检查两条都要看。</p>
     */
    @Select("select coalesce(sum(r.need_qty - r.reserved_qty), 0) from inventory_reservation r "
            + "where r.order_id = #{orderId} and r.status = " + ReservationStatus.RESERVED)
    int shortageOfOrder(@Param("orderId") Long orderId);

    /** 该单的活跃凭据数（完整性检查用：必须等于该单明细数）。 */
    @Select("select count(*) from inventory_reservation where order_id = #{orderId} and status = "
            + ReservationStatus.RESERVED)
    int countActiveByOrderId(@Param("orderId") Long orderId);

    /** 该单的明细数（完整性检查用）。 */
    @Select("select count(*) from order_item where order_id = #{orderId}")
    int countItemsByOrderId(@Param("orderId") Long orderId);

    /**
     * 该单**逐条明细**的备货情况（配送端「已备齐 / 还缺哪些」用，契约工作包 C4）。
     *
     * <p>口径与 {@link #shortageOfOrder} 一致（只读凭据上的 {@code need_qty} 快照，
     * 不 join 现读需求量），只是把汇总拆成逐商品：{@code shortage = need_qty − reserved_qty}。
     * 缺货待补的明细 {@code shortage > 0}；"整条明细没有凭据"那半边另由
     * {@link #countInflightItemsWithoutCredential} 覆盖 —— 两条都满足才算"备齐"。</p>
     *
     * <p>商品名取**下单快照** {@code order_item.product_name_snapshot}：与配送员手上那张单一致，
     * 也不额外读取商品库/他站目录。返回 Map（列别名即键），避免为展示再造一个 DTO。</p>
     */
    @Select("select r.order_item_id as orderItemId, r.product_id as productId, "
            + "oi.product_name_snapshot as productName, r.need_qty as needQty, r.reserved_qty as reservedQty, "
            + "(r.need_qty - r.reserved_qty) as shortage "
            + "from inventory_reservation r join order_item oi on oi.id = r.order_item_id "
            + "where r.order_id = #{orderId} and r.status = " + ReservationStatus.RESERVED
            + " order by r.id asc")
    java.util.List<java.util.Map<String, Object>> listPrepByOrder(@Param("orderId") Long orderId);

    /**
     * 补预留（CAS：仅当仍是活跃凭据）。
     * <p>写成 {@code reserved_qty = reserved_qty + delta} 而不是"读出来再加"：并发补位时后者会互相覆盖。</p>
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
    /*
     * 已删除（2026-09-25 二次收口 B1）：`moveToStationIfActive(id, stationId, reservedQty)`。
     * 它是"就地改这条凭据的站别"的写法，与运行时的换站实现（**旧站置已释放 + 新站插一条新的**）
     * 不是一回事：就地改会抹掉"这份货原先挂在哪一站"的审计轨迹，也让历史行的
     * released_qty 永远是 0。两条路都留着必然分叉 —— 换站只走 release+insert。
     */

    @Update("update inventory_reservation set status = " + ReservationStatus.RELEASED + ", "
            + "released_qty = reserved_qty, update_time = NOW() where id = #{id} and status = "
            + ReservationStatus.RESERVED)
    int releaseIfActive(@Param("id") Long id);

    /*
     * 已删除（2026-09-25 二次收口）：`releaseByOrderId(orderId)`。
     * 它是"一条 UPDATE 释放整单"的写法，但释放之后**必须**知道每条凭据原来挂在哪些 (站,商品)
     * 才能补位，而本方法不返回它们 —— 调用方只能再查一次，等于把"释放"和"补位"拆成两次读、
     * 中间还开了窗口。现在按行 `releaseIfActive` 释放并顺手收集 (站,商品)。
     */

    @Update("update inventory_reservation set status = " + ReservationStatus.SHIPPED + ", "
            + "shipped_qty = reserved_qty, update_time = NOW() where id = #{id} and status = "
            + ReservationStatus.RESERVED)
    int shipIfActive(@Param("id") Long id);

    // ==================== 对账用（只读，允许 join） ====================

    /** E11：存在 (站,商品) 使活跃预留 > 在库实物。 */
    @Select("select count(*) from (select r.station_id, r.product_id from inventory_reservation r "
            + "join inventory i on i.station_id = r.station_id and i.product_id = r.product_id "
            + "where r.status = " + ReservationStatus.RESERVED + " "
            + "group by r.station_id, r.product_id having sum(r.reserved_qty) > max(i.quantity)) x")
    int countReservedExceedsStock();

    /** E12：在途单（1/2）的明细没有活跃凭据。 */
    @Select("select count(*) from order_item oi join orders o on o.id = oi.order_id "
            + "where o.status in (1, 2) and not exists (select 1 from inventory_reservation r "
            + "  where r.order_item_id = oi.id and r.status = " + ReservationStatus.RESERVED + ")")
    int countInflightItemsWithoutCredential();

    /** E13：活跃凭据的站别 ≠ 该单当前履约站（`coalesce(delivery_station_id, station_id)`）。 */
    @Select("select count(*) from inventory_reservation r join orders o on o.id = r.order_id "
            + "where r.status = " + ReservationStatus.RESERVED
            + " and r.station_id <> coalesce(o.delivery_station_id, o.station_id)")
    int countCredentialWrongStation();

    /** E14：活跃凭据预留量越界（负数或超过需求量快照）。 */
    @Select("select count(*) from inventory_reservation r "
            + "where r.status = " + ReservationStatus.RESERVED
            + " and (r.reserved_qty < 0 or r.reserved_qty > r.need_qty)")
    int countReservedOutOfRange();

    /**
     * E15：活跃凭据的需求量快照与真相源（`order_item.quantity`）不一致，或预留量超过真相源需求量。
     *
     * <p>为什么必须有（二次验收 B2）：`need_qty` 是凭据上的**副本**，补位只读它 ——
     * 副本一旦与真相源分叉，分配就会按错的量走。于是必须有一条对账把它钉住：
     * 覆盖"快照 ≠ 明细量"，以及"明细量被改小之后预留量反而超过它"。</p>
     */
    @Select("select count(*) from inventory_reservation r join order_item oi on oi.id = r.order_item_id "
            + "where r.status = " + ReservationStatus.RESERVED
            + " and (r.need_qty <> oi.quantity or r.reserved_qty > oi.quantity)")
    int countNeedSnapshotMismatch();

    /**
     * E16：活跃凭据所在的 (站,商品)**没有库存行** —— 对账内连接库存表会漏掉它。
     *
     * <p>为什么单独一条：E11 是 `join inventory`（按 (站,商品) 分组比较），
     * 一份挂在"根本没有库存行"的站上的凭据**不会被 E11 看见**（join 直接把它过滤掉），
     * 于是它可以永远保留一个没有实物支撑的承诺。这类凭据正是"下单时可分配量算错"的产物
     * （二次验收 B3 点名的形状：原扣减站库存行缺失时凭空建正数预留）。</p>
     *
     * <p>⚠️ <b>`reserved_qty > 0` 这个条件是必须的，别去掉</b>：把一张单外派到"根本没配这个商品"的站
     * 是**合法经营动作**（那边的可用量就是 0 ⇒ 凭据照建、`reserved_qty = 0` = 缺货待补）。
     * 只按"有没有库存行"判会把这种**合法状态**算成差异（本仓在这件事上踩过三次，
     * 判据见 {@code docs/architecture/03-数据模型.md} §9）。</p>
     */
    @Select("select count(*) from inventory_reservation r "
            + "where r.status = " + ReservationStatus.RESERVED + " and r.reserved_qty > 0 "
            + " and not exists (select 1 from inventory i "
            + "  where i.station_id = r.station_id and i.product_id = r.product_id)")
    int countCredentialWithoutInventoryRow();

    /** 采样：预留超实物的 (站,商品)。 */
    @Select("select concat(r.station_id, '/', r.product_id) from inventory_reservation r "
            + "join inventory i on i.station_id = r.station_id and i.product_id = r.product_id "
            + "where r.status = " + ReservationStatus.RESERVED + " "
            + "group by r.station_id, r.product_id having sum(r.reserved_qty) > max(i.quantity) limit 5")
    List<String> sampleReservedExceedsStock();

    /** 采样：在途却缺凭据的订单明细。 */
    @Select("select concat('item:', oi.id) from order_item oi join orders o on o.id = oi.order_id "
            + "where o.status in (1, 2) and not exists (select 1 from inventory_reservation r "
            + "  where r.order_item_id = oi.id and r.status = " + ReservationStatus.RESERVED + ") limit 5")
    List<String> sampleInflightItemsWithoutCredential();

    /** 采样：凭据挂错站的订单。 */
    @Select("select concat('order:', r.order_id) from inventory_reservation r join orders o on o.id = r.order_id "
            + "where r.status = " + ReservationStatus.RESERVED
            + " and r.station_id <> coalesce(o.delivery_station_id, o.station_id) limit 5")
    List<String> sampleCredentialWrongStation();

    /** 采样：预留量越界的凭据。 */
    @Select("select concat('res:', r.id) from inventory_reservation r "
            + "where r.status = " + ReservationStatus.RESERVED
            + " and (r.reserved_qty < 0 or r.reserved_qty > r.need_qty) limit 5")
    List<String> sampleReservedOutOfRange();

    /** 采样：需求量快照与真相源不一致的凭据。 */
    @Select("select concat('res:', r.id, ' need=', r.need_qty, ' item=', oi.quantity) "
            + "from inventory_reservation r join order_item oi on oi.id = r.order_item_id "
            + "where r.status = " + ReservationStatus.RESERVED
            + " and (r.need_qty <> oi.quantity or r.reserved_qty > oi.quantity) limit 5")
    List<String> sampleNeedSnapshotMismatch();

    /** 采样：挂在没有库存行的 (站,商品) 上、且**承诺了数量**的活跃凭据。 */
    @Select("select concat('res:', r.id, ' station=', r.station_id, ' product=', r.product_id) "
            + "from inventory_reservation r "
            + "where r.status = " + ReservationStatus.RESERVED + " and r.reserved_qty > 0 "
            + " and not exists (select 1 from inventory i "
            + "  where i.station_id = r.station_id and i.product_id = r.product_id) limit 5")
    List<String> sampleCredentialWithoutInventoryRow();
}
