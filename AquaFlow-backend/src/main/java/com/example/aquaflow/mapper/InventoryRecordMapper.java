package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.InventoryRecord;
import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

/**
 * 库存流水 Mapper [AQ-029]。
 */
@Mapper
public interface InventoryRecordMapper {

    @Insert("insert into inventory_record(station_id, product_id, delta, type, ref_id, operator_id, note, create_time) " +
            "values(#{stationId}, #{productId}, #{delta}, #{type}, #{refId}, #{operatorId}, #{note}, NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(InventoryRecord record);

    /** 按站+商品查询流水（倒序） */
    @Select("select r.*, p.name as product_name, p.spec as spec " +
            "from inventory_record r left join product p on r.product_id = p.id " +
            "where r.station_id = #{stationId} and r.product_id = #{productId} " +
            "order by r.create_time desc, r.id desc limit #{limit}")
    List<InventoryRecord> listByStationAndProduct(@Param("stationId") Long stationId,
                                                  @Param("productId") Long productId,
                                                  @Param("limit") int limit);

    /** 按站查询全部流水（倒序） */
    @Select("select r.*, p.name as product_name, p.spec as spec " +
            "from inventory_record r left join product p on r.product_id = p.id " +
            "where r.station_id = #{stationId} " +
            "order by r.create_time desc, r.id desc limit #{limit}")
    List<InventoryRecord> listByStation(@Param("stationId") Long stationId, @Param("limit") int limit);

    /** 流水累计值（用于对账：应与 inventory.quantity 相等） */
    @Select("select coalesce(sum(delta), 0) from inventory_record where station_id = #{stationId} and product_id = #{productId}")
    int sumDelta(@Param("stationId") Long stationId, @Param("productId") Long productId);

    /**
     * 对账查询：逐条比对「库存表数量」与「流水累计」，返回不一致项。
     * 供日结自动对账使用。
     */
    @Select("select i.station_id, i.product_id, i.quantity as stock_qty, " +
            "coalesce((select sum(r.delta) from inventory_record r " +
            "          where r.station_id = i.station_id and r.product_id = i.product_id), 0) as flow_sum " +
            "from inventory i " +
            "having stock_qty <> flow_sum")
    List<Map<String, Object>> findMismatch();
}
