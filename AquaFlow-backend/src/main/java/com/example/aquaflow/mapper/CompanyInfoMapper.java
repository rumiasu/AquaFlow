package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.CompanyInfo;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CompanyInfoMapper {

    @Insert("insert into company_info(customer_id, company_name, contact_person, contact_phone, payment_method, due_days, create_time, update_time) " +
            "values(#{customerId}, #{companyName}, #{contactPerson}, #{contactPhone}, #{paymentMethod}, #{dueDays}, #{createTime}, #{updateTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(CompanyInfo companyInfo);

    @Select("select * from company_info where customer_id = #{customerId}")
    CompanyInfo getByCustomerId(Integer customerId);

    @Update("update company_info set company_name=#{companyName}, contact_person=#{contactPerson}, " +
            "contact_phone=#{contactPhone}, payment_method=#{paymentMethod}, due_days=#{dueDays}, update_time=NOW() " +
            "where customer_id=#{customerId}")
    void update(CompanyInfo companyInfo);
}
