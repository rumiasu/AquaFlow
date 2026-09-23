package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.Address;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface AddressMapper {

    @Insert("insert into address(customer_id, name, phone, province, city, district, label, detail, lat, lng, floor, has_elevator, is_default, create_time, update_time) " +
            "values(#{customerId}, #{name}, #{phone}, #{province}, #{city}, #{district}, #{label}, #{detail}, #{lat}, #{lng}, #{floor}, #{hasElevator}, #{isDefault}, #{createTime}, #{updateTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(Address address);

    @Select("select * from address where id = #{id}")
    Address getById(@Param("id") Long id);

    /**
     * 整行更新地址。
     *
     * <p>⚠️ {@code floor} / {@code has_elevator} 也在覆盖范围内：<b>旧版客户端不会传这两个字段</b>，
     * 直接写会把客户已填的楼层抹成 NULL（本仓「整行覆盖」事故的同一形状）。
     * 因此 {@code AddressServiceImpl.update} 会先读一次现有记录、对这两个字段做保留合并 ——
     * 改这条 SQL 时别把那层保护绕过去。</p>
     */
    @Update("update address set name=#{name}, phone=#{phone}, province=#{province}, city=#{city}, district=#{district}, label=#{label}, detail=#{detail}, " +
            "lat=#{lat}, lng=#{lng}, floor=#{floor}, has_elevator=#{hasElevator}, is_default=#{isDefault}, update_time=NOW() where id=#{id}")
    void update(Address address);

    @Delete("delete from address where id = #{id}")
    void deleteById(@Param("id") Long id);

    @Select("select * from address where customer_id = #{customerId} order by is_default desc")
    List<Address> listByCustomerId(@Param("customerId") Long customerId);

    @Select("select * from address where customer_id = #{customerId} " +
            "and (province like concat('%', #{keyword}, '%') or city like concat('%', #{keyword}, '%') " +
            "or district like concat('%', #{keyword}, '%') or detail like concat('%', #{keyword}, '%'))")
    List<Address> searchByCustomerId(@Param("customerId") Long customerId, @Param("keyword") String keyword);

    @Update("update address set is_default = 0 where customer_id = #{customerId}")
    void clearDefault(@Param("customerId") Long customerId);

    @Update("update address set is_default = 1 where id = #{id}")
    void setDefault(@Param("id") Long id);

    @Select("select * from address where (#{customerId} is null or customer_id = #{customerId}) " +
            "and (#{keyword} is null or detail like concat('%', #{keyword}, '%')) " +
            "order by is_default desc, create_time desc")
    List<Address> list(@Param("customerId") Long customerId, @Param("keyword") String keyword);

    @Select("select a.* from address a " +
            "inner join customer c on a.customer_id = c.id " +
            "inner join customer_station_config csc on csc.customer_id = c.id and csc.station_id = #{stationId} " +
            "where (#{keyword} is null or a.detail like concat('%', #{keyword}, '%')) " +
            "order by a.is_default desc, a.create_time desc")
    List<Address> listByStation(@Param("stationId") Long stationId, @Param("keyword") String keyword);

    /** 归属限定的删除；返回受影响行数，调用方必须检查（0 = 不是他的地址） */
    @Delete("delete from address where id = #{id} and customer_id = #{customerId}")
    int delete(@Param("id") Long id, @Param("customerId") Long customerId);

    @Select("select label as tag, count(*) as cnt from address group by label")
    List<java.util.Map<String, Object>> countByTag();

    @Select("select a.label as tag, count(*) as cnt from address a " +
            "inner join customer c on a.customer_id = c.id " +
            "inner join customer_station_config csc on csc.customer_id = c.id and csc.station_id = #{stationId} " +
            "group by a.label")
    List<java.util.Map<String, Object>> countByTagByStation(@Param("stationId") Long stationId);
}
