package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.HistoricalAssetClaim;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface HistoricalAssetClaimMapper {
    @Select("select p.id,p.name,p.spec,p.category,p.image_object_name,null as image_url from product p join inventory i on i.product_id=p.id "
        +"where i.station_id=#{station} and (p.owner_station_id is null or p.owner_station_id=#{station}) order by p.id")
    List<com.example.aquaflow.dto.HistoricalAssetClaimDTO.ProductOption> catalog(Long station);
    @Select("select station_id from historical_asset_claim_photo where customer_id=#{customer} and client_token=#{token}")
    Long photoStation(@Param("customer") Long customer,@Param("token") String token);
    // 同一客户的申请/核对按固定顺序串行；不读取姓名、电话、openid 等档案。
    @Select("select id from customer where id=#{customer} for update")
    Long lockCustomer(Long customer);
    @Select("select * from historical_asset_claim where id=#{id}")
    HistoricalAssetClaim get(Long id);
    @Select("select * from historical_asset_claim where id=#{id} for update")
    HistoricalAssetClaim lock(Long id);
    @Select("select * from historical_asset_claim where customer_id=#{customer} and client_token=#{token} for update")
    HistoricalAssetClaim byToken(@Param("customer") Long customer,@Param("token") String token);
    @Select("select count(*) from historical_asset_claim where station_id=#{station} and active_customer_id=#{customer}")
    int active(@Param("customer") Long customer,@Param("station") Long station);
    @Insert("insert into historical_asset_claim(customer_id,station_id,client_token,request_digest,request_json,status) "
        +"values(#{customerId},#{stationId},#{clientToken},#{requestDigest},#{requestJson},'PENDING')")
    @Options(useGeneratedKeys=true,keyProperty="id")
    int insert(HistoricalAssetClaim claim);
    @Update("update historical_asset_claim set status=#{status},reviewer_id=#{reviewer},review_note=#{note},review_time=now() where id=#{id} and status='PENDING'")
    int decide(@Param("id") Long id,@Param("status") String status,@Param("reviewer") Long reviewer,@Param("note") String note);
    @Select("select * from historical_asset_claim where customer_id=#{customer} and station_id=#{station} order by id desc limit 100")
    List<HistoricalAssetClaim> mine(@Param("customer") Long customer,@Param("station") Long station);
    @Select("select * from historical_asset_claim where station_id=#{station} and status='PENDING' order by id limit 100")
    List<HistoricalAssetClaim> pending(Long station);

    // 当前读并锁已有记录及空区间。期初迁入不猜测/叠加已经进入其他业务的历史账。
    @Select("select id from customer_barrel_lot where customer_id=#{customer} and station_id=#{station} for update")
    List<Long> barrelHistory(@Param("customer") Long customer,@Param("station") Long station);
    @Select("select id from ticket_lot where customer_id=#{customer} and station_id=#{station} for update")
    List<Long> ticketHistory(@Param("customer") Long customer,@Param("station") Long station);
    @Select("select id from deposit_record where customer_id=#{customer} and station_id=#{station} for update")
    List<Long> depositHistory(@Param("customer") Long customer,@Param("station") Long station);
    @Select("select id from payment_record where customer_id=#{customer} and station_id=#{station} and status in (0,1) for update")
    List<Long> pendingPayments(@Param("customer") Long customer,@Param("station") Long station);
    @Select("select id from barrel_right_reservation where customer_id=#{customer} and station_id=#{station} and status='ACTIVE' for update")
    List<Long> reservations(@Param("customer") Long customer,@Param("station") Long station);
    @Select("select quantity from customer_barrel_asset where customer_id=#{customer} and station_id=#{station} for update")
    List<Integer> barrelBalances(@Param("customer") Long customer,@Param("station") Long station);
    @Select("select over_qty from customer_barrel_over where customer_id=#{customer} and station_id=#{station} for update")
    List<Integer> overBalances(@Param("customer") Long customer,@Param("station") Long station);
    @Select("select remain_quantity from ticket_account where customer_id=#{customer} and station_id=#{station} for update")
    List<Integer> ticketBalances(@Param("customer") Long customer,@Param("station") Long station);
    @Select("select balance from customer_deposit_account where customer_id=#{customer} and station_id=#{station} for update")
    List<java.math.BigDecimal> depositBalances(@Param("customer") Long customer,@Param("station") Long station);
}
