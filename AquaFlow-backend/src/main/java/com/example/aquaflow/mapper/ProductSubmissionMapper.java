package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.ProductSubmission;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 自定义商品上报通用库的登记表访问（{@code product_submission}）。
 *
 * <p>可见性：站长只能读写**本站**的登记行；没有平台管理端，处置由运维直接改库。</p>
 */
@Mapper
public interface ProductSubmissionMapper {

    @Insert("insert into product_submission(station_id, product_id, submitter_staff_id, note, status, create_time, update_time) " +
            "values(#{stationId}, #{productId}, #{submitterStaffId}, #{note}, #{status}, NOW(), NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(ProductSubmission submission);

    @Select("select * from product_submission where id = #{id}")
    ProductSubmission getById(@Param("id") Long id);

    @Select("select * from product_submission where station_id = #{stationId} order by id desc limit #{limit}")
    List<ProductSubmission> listByStation(@Param("stationId") Long stationId, @Param("limit") int limit);

    /**
     * 该商品在本站是否已有"待处理"的上报。
     * <p>用来挡住重复上报（同一商品攒十条待处理，运维没法看）。被驳回后允许再次上报，
     * 所以这里只数 {@code status = 0}。</p>
     */
    @Select("select count(*) from product_submission where station_id = #{stationId} and product_id = #{productId} and status = 0")
    int countPending(@Param("stationId") Long stationId, @Param("productId") Long productId);
}
