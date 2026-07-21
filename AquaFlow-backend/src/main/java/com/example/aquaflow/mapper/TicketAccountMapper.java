package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.TicketAccount;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface TicketAccountMapper {

    @Insert("insert into ticket_account(customer_id, water_type_id, remain_quantity) " +
            "values(#{customerId}, #{waterTypeId}, #{remainQuantity})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(TicketAccount ticketAccount);

    @Select("select * from ticket_account where customer_id = #{customerId} and water_type_id = #{waterTypeId}")
    TicketAccount getByCustomerAndWaterType(@Param("customerId") Integer customerId, @Param("waterTypeId") Integer waterTypeId);

    @Update("update ticket_account set remain_quantity = remain_quantity - #{qty} where id = #{id} and remain_quantity >= #{qty}")
    void decrementQuantity(@Param("id") Integer id, @Param("qty") Integer qty);

    @Update("update ticket_account set remain_quantity = remain_quantity + #{qty} where id = #{id}")
    void incrementQuantity(@Param("id") Integer id, @Param("qty") Integer qty);

    @Update("update ticket_account set remain_quantity = #{remainQuantity} where id = #{id}")
    void updateQuantity(@Param("id") Integer id, @Param("remainQuantity") Integer remainQuantity);

    @Select("select * from ticket_account where customer_id = #{customerId}")
    List<TicketAccount> listByCustomerId(Integer customerId);

    @Select("select ta.*, w.name as waterTypeName, w.spec as waterTypeSpec, w.price as waterTypePrice " +
            "from ticket_account ta left join water_type w on ta.water_type_id = w.id " +
            "where ta.customer_id = #{customerId}")
    java.util.List<java.util.Map<String, Object>> listByCustomerIdWithDetail(@Param("customerId") Integer customerId);
}
