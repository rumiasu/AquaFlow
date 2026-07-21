package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.Address;
import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

@Mapper
public interface AddressMapper {
    @Insert("insert into address(customer_id, name, phone, is_default, label, detail, tag, lat, lng, create_time, update_time) " +
            "values(#{customerId}, #{name}, #{phone}, #{isDefault}, #{label}, #{detail}, #{tag}, #{lat}, #{lng}, #{createTime}, #{updateTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(Address address);

    List<Address> list(@Param("customerId") Integer customerId, @Param("tag") String tag, @Param("keyword") String keyword);

    @Select("select * from address where id = #{id}")
    Address getById(Integer id);

    void update(Address address);

    @Delete("delete from address where id = #{id} and customer_id = #{customerId}")
    void delete(@Param("id") Integer id, @Param("customerId") Integer customerId);

    @Update("update address set is_default = 0 where customer_id = #{customerId}")
    void clearDefault(@Param("customerId") Integer customerId);

    @Update("update address set is_default = 1 where id = #{id}")
    void setDefault(@Param("id") Integer id);

    List<Map<String, Object>> countByTag();

    @Select("select count(*) from address")
    int countAll();

    @Select("select * from address where detail like concat('%', #{keyword}, '%') or tag like concat('%', #{keyword}, '%')")
    List<Address> search(@Param("keyword") String keyword);
}
