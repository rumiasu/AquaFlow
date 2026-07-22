package com.example.aquaflow.mapper.factory;

import com.example.aquaflow.entity.StockTransfer;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface StockTransferMapper {

    @Insert("insert into stock_transfer(from_station_id, to_station_id, water_type_id, quantity, status, create_time, update_time) " +
            "values(#{fromStationId}, #{toStationId}, #{waterTypeId}, #{quantity}, #{status}, #{createTime}, #{updateTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(StockTransfer transfer);

    @Select("select st.*, fs.name as fromStationName, ts.name as toStationName, wt.name as waterTypeName, wt.spec as waterTypeSpec " +
            "from stock_transfer st " +
            "left join station fs on st.from_station_id = fs.id " +
            "left join station ts on st.to_station_id = ts.id " +
            "left join water_type wt on st.water_type_id = wt.id " +
            "order by st.create_time desc")
    List<StockTransfer> listAll();

    @Select("select st.*, fs.name as fromStationName, ts.name as toStationName, wt.name as waterTypeName, wt.spec as waterTypeSpec " +
            "from stock_transfer st " +
            "left join station fs on st.from_station_id = fs.id " +
            "left join station ts on st.to_station_id = ts.id " +
            "left join water_type wt on st.water_type_id = wt.id " +
            "where st.status = #{status} order by st.create_time desc")
    List<StockTransfer> listByStatus(@Param("status") Integer status);

    @Select("select st.*, fs.name as fromStationName, ts.name as toStationName, wt.name as waterTypeName, wt.spec as waterTypeSpec " +
            "from stock_transfer st " +
            "left join station fs on st.from_station_id = fs.id " +
            "left join station ts on st.to_station_id = ts.id " +
            "left join water_type wt on st.water_type_id = wt.id " +
            "where st.id = #{id}")
    StockTransfer getById(@Param("id") Integer id);

    @Update("update stock_transfer set status = #{status}, approve_note = #{approveNote}, update_time = now() where id = #{id}")
    void approve(@Param("id") Integer id, @Param("status") Integer status, @Param("approveNote") String approveNote);

    @Update("update stock_transfer set status = 3, complete_note = #{completeNote}, update_time = now() where id = #{id}")
    void complete(@Param("id") Integer id, @Param("completeNote") String completeNote);

    @Update("update stock_transfer set status = 4, update_time = now() where id = #{id}")
    void cancel(@Param("id") Integer id);
}
