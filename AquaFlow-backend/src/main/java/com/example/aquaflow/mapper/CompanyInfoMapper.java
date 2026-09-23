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
     * 【墓碑 · 2026-09-21 起无人调用】原「只改账期」，v60 起账期已改到**站级**。
     *
     * <p>账期现在是 {@code customer_station_config.due_days / settlement_cycle}
     * （写入口 {@code CustomerStationConfigMapper.updateCreditTerms}）——
     * 原因是客户级的 {@code due_days} 会让 **A 站设的账期在 B 站生效**，属租户边界漏洞。
     * 本方法连同 {@code company_info.due_days} 一列都已失效，**不要再用它设账期**；
     * 保留仅为兼容历史代码与留痕，下一次清理时按 §0.3 的删除协议评估移除。</p>
     *
     * <p>⚠️ 顺便留下它当初存在的理由（对 {@link #updateByCustomerId} 的告诫仍然有效）：
     * 那个方法是<b>整行覆盖</b>，而站长设账期时手上只有 {@code dueDays} 一个字段 ——
     * 走那条路会把客户自己填的企业名称 / 联系人 / 电话一起抹成 NULL。</p>
     */
    @Update("update company_info set due_days = #{dueDays}, update_time = NOW() " +
            "where customer_id = #{customerId}")
    int updateDueDays(@Param("customerId") Long customerId, @Param("dueDays") Integer dueDays);

    /**
     * 【墓碑 · 2026-09-21 起无人调用】原「为客户建一条只带账期的 company_info 行」，
     * 已被 {@code CustomerStationConfigMapper.ensureExists + updateCreditTerms} 取代（v60，站级账期）。
     */
    @Insert("insert into company_info(customer_id, due_days, create_time, update_time) " +
            "values(#{customerId}, #{dueDays}, NOW(), NOW()) " +
            "on duplicate key update due_days = values(due_days), update_time = NOW()")
    void insertCreditTerms(@Param("customerId") Long customerId, @Param("dueDays") Integer dueDays);
}
