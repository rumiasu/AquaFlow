package com.example.aquaflow.mapper;

import com.example.aquaflow.vo.CustomerAssetStationVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

/** 只读本人资产关联站；订单取归属站，历史零余额与停用站也保留。 */
@Mapper
public interface CustomerAssetStationMapper {
    String RELATED = """
            select station_id from (
                select station_id from customer_station_config where customer_id = #{customerId}
                union
                select station_id from orders where customer_id = #{customerId}
                union
                select station_id from customer_deposit_account where customer_id = #{customerId}
                union
                select station_id from customer_barrel_asset where customer_id = #{customerId}
                union
                select station_id from customer_barrel_over where customer_id = #{customerId}
                union
                select station_id from customer_barrel_lot where customer_id = #{customerId}
                union
                select station_id from customer_barrel_in_transit where customer_id = #{customerId}
                union
                select station_id from ticket_account where customer_id = #{customerId}
                union
                select station_id from ticket_lot where customer_id = #{customerId}
                union
                select station_id from deposit_record where customer_id = #{customerId}
                union
                select station_id from barrel_record where customer_id = #{customerId}
                union
                select station_id from ticket_record where customer_id = #{customerId}
                union
                select station_id from barrel_right_purchase where customer_id = #{customerId}
            ) owned_station where station_id is not null
            """;

    @Select("select count(*) from customer where id = #{customerId}")
    int countCustomer(@Param("customerId") Long customerId);

    @Select("select s.id, s.name, s.status from station s "
            + "where s.id > #{afterStationId} and s.id in (" + RELATED + ") "
            + "order by s.id asc limit #{limit}")
    List<CustomerAssetStationVO> listOwned(@Param("customerId") Long customerId,
            @Param("afterStationId") Long afterStationId, @Param("limit") int limit);

    @Select("select s.id, s.name, s.status from station s "
            + "where s.id = #{stationId} and s.id in (" + RELATED + ")")
    CustomerAssetStationVO getOwned(@Param("customerId") Long customerId,
            @Param("stationId") Long stationId);
}

