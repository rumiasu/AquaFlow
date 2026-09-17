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

    /**
     * 只改账期（站长端设/清客户账期，2026-09-17）。
     *
     * <p>⚠️ <b>不要改用 {@link #updateByCustomerId} 代替本方法</b>：它是<b>整行覆盖</b>，
     * 而站长设账期时手上只有 {@code dueDays} 一个字段 —— 走那条路会把客户自己填的
     * 企业名称 / 联系人 / 电话 / 结算方式一起抹成 NULL（同形事故见 AGENTS §8.15 的
     * 「强类型 DTO 静默丢字段」与 Phase 0 的「地址楼层做保留合并」）。</p>
     *
     * @return 受影响行数；0 = 该客户还没有 company_info 行，调用方应先 {@link #insertCreditTerms}
     */
    @Update("update company_info set due_days = #{dueDays}, update_time = NOW() " +
            "where customer_id = #{customerId}")
    int updateDueDays(@Param("customerId") Long customerId, @Param("dueDays") Integer dueDays);

    /**
     * 为客户建一条只带账期的 company_info 行（其余资料留空，等客户自己补）。
     *
     * <p>用 upsert 而不是"先查再插"：并发两次设置账期会双双查不到、双双 insert 撞
     * {@code uk_company_customer}。</p>
     */
    @Insert("insert into company_info(customer_id, due_days, create_time, update_time) " +
            "values(#{customerId}, #{dueDays}, NOW(), NOW()) " +
            "on duplicate key update due_days = values(due_days), update_time = NOW()")
    void insertCreditTerms(@Param("customerId") Long customerId, @Param("dueDays") Integer dueDays);
}
