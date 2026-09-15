package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.StationAdjustment;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface StationAdjustmentMapper {

    @Insert("insert into station_adjustment(adjust_no, station_id, customer_id, product_id, adjust_type, qty, amount, "
            + "unit_price, price_source, is_migrated, reason, evidence, before_snapshot, after_snapshot, status, "
            + "client_token, operator_id, executor_id, reverses, create_time, update_time) "
            + "values(#{adjustNo}, #{stationId}, #{customerId}, #{productId}, #{adjustType}, #{qty}, #{amount}, "
            + "#{unitPrice}, #{priceSource}, #{isMigrated}, #{reason}, #{evidence}, #{beforeSnapshot}, #{afterSnapshot}, "
            + "#{status}, #{clientToken}, #{operatorId}, #{executorId}, #{reverses}, NOW(), NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(StationAdjustment a);

    /** 单据号需要在拿到自增 id 之后生成（与押金条 lot_no 同款做法） */
    @Update("update station_adjustment set adjust_no = #{adjustNo} where id = #{id}")
    int setAdjustNo(@Param("id") Long id, @Param("adjustNo") String adjustNo);

    /**
     * 反向单回指原单（审计链：反向单 → 原单）。
     *
     * <p><b>[2026-09-14 补]</b> 原来 {@code StationAdjustmentServiceImpl#reverse} 是在
     * {@code create(...)} 之后才 {@code rev.setReverses(src.getId())} —— 而 {@code create} 内部<b>已经</b>
     * 执行过 {@code insert}，那句赋值只改了内存对象，<b>{@code reverses} 列因此恒为 NULL</b>。
     * 后果：反向单无法反查它撤销的是哪张单（正向的 {@code reversed_by} 是好的，反向链断了），
     * 审计与"这张单是被谁撤的"排查都会缺一环。
     * 由 {@code StationAdjustmentTypeCoverageIntegrationTest} 抓到（断言"应生成一张反向单并指向原单"）。</p>
     */
    @Update("update station_adjustment set reverses = #{reverses} where id = #{id}")
    int setReverses(@Param("id") Long id, @Param("reverses") Long reverses);

    @Select("select * from station_adjustment where id = #{id}")
    StationAdjustment getById(@Param("id") Long id);

    /** 幂等：同一 clientToken 只允许一张单 */
    @Select("select * from station_adjustment where client_token = #{clientToken} limit 1")
    StationAdjustment findByClientToken(@Param("clientToken") String clientToken);

    @Select("select * from station_adjustment where station_id = #{stationId} order by id desc limit #{limit} offset #{offset}")
    List<StationAdjustment> listByStation(@Param("stationId") Long stationId,
                                          @Param("offset") int offset,
                                          @Param("limit") int limit);

    @Select("select count(*) from station_adjustment where station_id = #{stationId}")
    int countByStation(@Param("stationId") Long stationId);

    @Select("select * from station_adjustment where station_id = #{stationId} and customer_id = #{customerId} "
            + "order by id desc limit #{limit} offset #{offset}")
    List<StationAdjustment> listByCustomer(@Param("stationId") Long stationId,
                                           @Param("customerId") Long customerId,
                                           @Param("offset") int offset,
                                           @Param("limit") int limit);

    @Select("select count(*) from station_adjustment where station_id = #{stationId} and customer_id = #{customerId}")
    int countByCustomer(@Param("stationId") Long stationId, @Param("customerId") Long customerId);

    /**
     * 状态 CAS：PENDING → EFFECTIVE / REJECTED；EFFECTIVE → REVERSED。
     * <p>affected=0 表示状态已被并发改动，调用方必须据此中止（而不是继续执行副作用）。</p>
     */
    @Update("update station_adjustment set status = #{status}, executor_id = #{executorId}, "
            + "before_snapshot = #{beforeSnapshot}, after_snapshot = #{afterSnapshot}, "
            + "execute_time = NOW(), update_time = NOW() "
            + "where id = #{id} and status = #{expectedStatus}")
    int markEffectiveIf(@Param("id") Long id,
                        @Param("expectedStatus") String expectedStatus,
                        @Param("status") String status,
                        @Param("executorId") Long executorId,
                        @Param("beforeSnapshot") String beforeSnapshot,
                        @Param("afterSnapshot") String afterSnapshot);

    @Update("update station_adjustment set status = #{status}, reversed_by = #{reversedBy}, update_time = NOW() "
            + "where id = #{id} and status = #{expectedStatus}")
    int markReversedIf(@Param("id") Long id,
                       @Param("expectedStatus") String expectedStatus,
                       @Param("status") String status,
                       @Param("reversedBy") Long reversedBy);

    @Update("update station_adjustment set before_snapshot = #{beforeSnapshot} where id = #{id} and before_snapshot is null")
    int fillBeforeSnapshotIfAbsent(@Param("id") Long id, @Param("beforeSnapshot") String beforeSnapshot);
}
