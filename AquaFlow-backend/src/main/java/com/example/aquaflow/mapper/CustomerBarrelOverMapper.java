package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.CustomerBarrelOver;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 客户过占桶（按商品，可负）数据访问。
 *
 * <p><b>注意：本表所有写入都不做「结果必须 &gt;= 0」的校验。</b>
 * over &lt; 0（顾客多还桶 / 水站暂存）是业务方确认的合法状态，
 * 加任何形式的非负校验都会把合理场景挡在门外。</p>
 */
@Mapper
public interface CustomerBarrelOverMapper {

    @Select("select * from customer_barrel_over " +
            "where customer_id = #{customerId} and station_id = #{stationId} and product_id = #{productId}")
    CustomerBarrelOver get(@Param("customerId") Long customerId,
                           @Param("stationId") Long stationId,
                           @Param("productId") Long productId);

    @Select("select * from customer_barrel_over " +
            "where customer_id = #{customerId} and station_id = #{stationId}")
    List<CustomerBarrelOver> listByCustomerAndStation(@Param("customerId") Long customerId,
                                                      @Param("stationId") Long stationId);

    @Select("select * from customer_barrel_over where station_id = #{stationId}")
    List<CustomerBarrelOver> listByStation(@Param("stationId") Long stationId);

    /**
     * 增减 over（可为负）。
     * 行不存在时按 delta 插入——注意 delta 本身可以是负数，直接插入负值即可。
     */
    @Insert("insert into customer_barrel_over(customer_id, station_id, product_id, over_qty, create_time, update_time) " +
            "values(#{customerId}, #{stationId}, #{productId}, #{delta}, NOW(), NOW()) " +
            "on duplicate key update over_qty = over_qty + #{delta}, update_time = NOW()")
    int adjustOver(@Param("customerId") Long customerId,
                   @Param("stationId") Long stationId,
                   @Param("productId") Long productId,
                   @Param("delta") int delta);

    /**
     * 加锁读取（并发写入路径用）。
     * 注意：行不存在时 FOR UPDATE 不产生锁，调用方需自行处理（通常靠 lot 行锁或唯一键兜底）。
     */
    @Select("select * from customer_barrel_over " +
            "where customer_id = #{customerId} and station_id = #{stationId} and product_id = #{productId} " +
            "for update")
    CustomerBarrelOver getForUpdate(@Param("customerId") Long customerId,
                                    @Param("stationId") Long stationId,
                                    @Param("productId") Long productId);

    /**
     * [DEF-4] 确保 over 行存在并对其加<b>排他行锁</b>，用于把同一
     * (customer, station, product) 的并发桶账写入串行化。
     *
     * <p><b>为什么必须是 upsert 而不是 SELECT ... FOR UPDATE：</b>
     * {@code SELECT ... FOR UPDATE} 在行不存在时不产生任何锁（无间隙锁兜底），
     * 于是「首次还桶」这种行尚未建立的场景仍会两端并发通过校验。
     * 这里用 {@code INSERT ... ON DUPLICATE KEY UPDATE}：行不存在则插入 0 并持有排他锁，
     * 行存在则同样持排他锁，两种情况都能把并发事务挡在门外。</p>
     *
     * <p>调用方必须处于事务中（{@code @Transactional}），锁才会持续到事务提交。</p>
     */
    @Insert("insert into customer_barrel_over(customer_id, station_id, product_id, over_qty, create_time, update_time) " +
            "values(#{customerId}, #{stationId}, #{productId}, 0, NOW(), NOW()) " +
            "on duplicate key update over_qty = over_qty")
    int lockOrCreate(@Param("customerId") Long customerId,
                     @Param("stationId") Long stationId,
                     @Param("productId") Long productId);

    /**
     * 同步 {@code owed_since}（欠桶起始时间）。**必须在每次 {@link #adjustOver} 之后调用。**
     *
     * <p>三条规则全部由 SQL 的 CASE 表达，避免调用方各写一套判断：</p>
     * <ul>
     *   <li>{@code over_qty <= 0} → 置 NULL（含 over&lt;0 的水站暂存，不算欠桶）；</li>
     *   <li>{@code over_qty > 0} 且 {@code owed_since IS NULL} → 写入 NOW()（本次欠桶开始，也顺带修复历史空值）；</li>
     *   <li>{@code over_qty > 0} 且已有值 → 保持不变（同一笔欠桶再增加，天数不清零）。</li>
     * </ul>
     *
     * <p>注意本语句**不要**显式写 {@code update_time = ...}：该列是
     * {@code ON UPDATE CURRENT_TIMESTAMP}，只有行真的变化时 MySQL 才刷新它。
     * 第三分支"保持不变"时行无变化 → update_time 不动，符合"行更新时间"的语义。</p>
     *
     * <p>仅供展示（站长端欠桶台账 / 下单提醒），<b>不参与任何校验</b>。</p>
     */
    @Update("update customer_barrel_over set owed_since = case " +
            "when over_qty <= 0 then null " +
            "when owed_since is null then now() " +
            "else owed_since end " +
            "where customer_id = #{customerId} and station_id = #{stationId} and product_id = #{productId}")
    int syncOwedSince(@Param("customerId") Long customerId,
                      @Param("stationId") Long stationId,
                      @Param("productId") Long productId);

    /**
     * 站长端「欠桶台账」：本站当前仍欠桶（{@code over_qty > 0}）的客户，按欠得最久的排前面。
     *
     * <p>只读查询。明细（哪一单欠的、差几个、处理到哪一步）走现成的
     * {@code order_barrel_exception}（取 {@code discrepancy > 0} 的记录），
     * 不在这里重复建事件表。</p>
     */
    @Select("select o.customer_id, o.product_id, o.over_qty, o.owed_since, " +
            "       c.name as customer_name, c.phone as phone, " +
            "       p.name as product_name, p.spec as product_spec " +
            "  from customer_barrel_over o " +
            "  join customer c on c.id = o.customer_id " +
            "  left join product p on p.id = o.product_id " +
            " where o.station_id = #{stationId} and o.over_qty > 0 " +
            " order by o.owed_since is null, o.owed_since asc, o.customer_id asc")
    List<com.example.aquaflow.vo.OwedBarrelVO> listOwedByStation(@Param("stationId") Long stationId);
}
