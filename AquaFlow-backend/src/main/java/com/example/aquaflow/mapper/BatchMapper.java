package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.Batch;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface BatchMapper {

    @Insert("insert into batch (status, total_qty, station_id, delivery_person_id, create_time, update_time) values " +
            "(#{status},#{totalQTY},#{stationId},#{deliveryPersonId},#{createTime},#{updateTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(Batch batch);


    List<Batch> list(Integer status, String createTimeStart, String createTimeEnd);

    @Select("select * from batch where id=#{id}")
    Batch getById(Integer id);

    @Update("update batch set status=#{status},update_time=now() where id = #{id}")
    void updateStatus(@Param("id") Integer id, @Param("status") Integer status);

    @Delete("delete from batch where id=#{id}")
    void delete(Integer id);

    @Update("update batch set total_qty = #{totalQTY}, update_time = now() where id = #{id}")
    void updateTotalQTY(@Param("id") Integer id, @Param("totalQTY") Integer totalQTY);

    @Select("select count(*) from batch")
    int countAll();

    @Select("select count(*) from batch where status = #{status}")
    int countByStatus(@Param("status") Integer status);

    @Select("select ifnull(sum(total_qty), 0) from batch where status in (1, 2)")
    int sumDeliveringQty();

    /**
     * 查询指定配送员的批次列表
     */
    @Select("select * from batch where delivery_person_id = #{deliveryPersonId} order by create_time desc")
    List<Batch> listByDeliveryPersonId(@Param("deliveryPersonId") Integer deliveryPersonId);

    /**
     * 分配配送员到批次
     */
    @Update("update batch set delivery_person_id = #{deliveryPersonId}, update_time = now() where id = #{batchId}")
    void assignDeliveryPerson(@Param("batchId") Integer batchId, @Param("deliveryPersonId") Integer deliveryPersonId);
}
