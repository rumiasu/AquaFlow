package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.CompanyInfo;
import org.apache.ibatis.annotations.*;

@Mapper
public interface CompanyInfoMapper {

    @Select("select * from company_info where customer_id = #{customerId}")
    CompanyInfo getByCustomerId(@Param("customerId") Long customerId);

    @Insert("insert into company_info(customer_id, company_name, contact_person, contact_phone, payment_method, due_days, create_time, update_time) " +
            "values(#{customerId}, #{companyName}, #{contactPerson}, #{contactPhone}, #{paymentMethod}, #{dueDays}, NOW(), NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(CompanyInfo companyInfo);

    @Update("update company_info set company_name=#{companyName}, contact_person=#{contactPerson}, " +
            "contact_phone=#{contactPhone}, payment_method=#{paymentMethod}, due_days=#{dueDays}, update_time=NOW() " +
            "where customer_id=#{customerId}")
    void updateByCustomerId(CompanyInfo companyInfo);
}
