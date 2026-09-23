package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.CustomerEnterpriseApply;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 企业身份申请（v50）。写入点只有两个：客户提交、站长审核（审核只改 status/review_*）。
 */
@Mapper
public interface CustomerEnterpriseApplyMapper {

    @Insert("insert into customer_enterprise_apply(customer_id, station_id, company_name, contact_person, "
            + "contact_phone, tax_no, status, apply_time) "
            + "values(#{customerId}, #{stationId}, #{companyName}, #{contactPerson}, #{contactPhone}, #{taxNo}, "
            + "'PENDING', NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(CustomerEnterpriseApply apply);

    @Select("select * from customer_enterprise_apply where id = #{id}")
    CustomerEnterpriseApply getById(@Param("id") Long id);

    /** 该客户在该站**待审核**的那一条（用于幂等：重复提交不产生第二条待审）。 */
    @Select("select * from customer_enterprise_apply where customer_id = #{customerId} and station_id = #{stationId} "
            + "and status = 'PENDING' order by id desc limit 1")
    CustomerEnterpriseApply getPending(@Param("customerId") Long customerId, @Param("stationId") Long stationId);

    /** 该客户在该站的申请（倒序，最多几条，给"我的申请"用）。 */
    @Select("select * from customer_enterprise_apply where customer_id = #{customerId} and station_id = #{stationId} "
            + "order by id desc limit 10")
    List<CustomerEnterpriseApply> listByCustomerAndStation(@Param("customerId") Long customerId,
                                                           @Param("stationId") Long stationId);

    /** 站长待审列表（本站）。 */
    @Select("select * from customer_enterprise_apply where station_id = #{stationId} and status = 'PENDING' "
            + "order by apply_time asc")
    List<CustomerEnterpriseApply> listPendingByStation(@Param("stationId") Long stationId);

    /**
     * 审核（CAS：只有还是 PENDING 才能改）—— 返回受影响行数，调用方必须检查，
     * 否则并发两次"通过"会各写一遍企业资料。
     */
    @Update("update customer_enterprise_apply set status = #{status}, review_note = #{reviewNote}, "
            + "reviewer_id = #{reviewerId}, review_time = NOW() "
            + "where id = #{id} and status = 'PENDING'")
    int reviewIfPending(@Param("id") Long id, @Param("status") String status,
                        @Param("reviewNote") String reviewNote, @Param("reviewerId") Long reviewerId);
}
