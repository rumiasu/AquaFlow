package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import com.example.aquaflow.dto.AddressProfileDTO;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.service.AddressService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** C-01: real JSON binding distinguishes explicit CLEAR from legacy null/omission. */
@DisplayName("地址显式清空与旧客户端保留契约")
class AddressExplicitClearIntegrationTest extends AbstractIntegrationTest {
    @Autowired AddressService addresses;
    @Autowired PlatformTransactionManager transactions;
    long customer,address;
    @BeforeEach void seedAddress() {
        customer=createCustomer("合成地址客户","explicit-clear");
        address=createAddress(customer,"合成门牌");
        jdbc.update("update address set name='合成收件人',phone='13800000000',floor=6,has_elevator=0,lat=30,lng=120 where id=?",address);
    }
    private Api edit(String body) { return put("/api/addresses/"+address+"/profile",customerToken(customer),body); }
    private void oldValues() {
        assertEquals(6,intOf("select floor from address where id=?",address));
        assertEquals(0,intOf("select has_elevator from address where id=?",address));
        assertEquals(0,decimalOf("select lat from address where id=?",address).compareTo(new BigDecimal("30")));
    }
    @Test @DisplayName("明确清楼层保留电梯与坐标，回执包含真实NULL")
    void clearFloorAlone() {
        Api result=edit("{\"floor\":null,\"clearFloor\":true}");assertTrue(result.isSuccess(),result.toString());
        assertTrue(result.data().path("floor").isNull());assertEquals(address,result.data().path("id").asLong());
        assertEquals(1,intOf("select count(*) from address where id=? and floor is null and has_elevator=0 and lat=30 and lng=120",address));
    }
    @Test @DisplayName("明确清电梯不清楼层")
    void clearElevatorAlone() {
        assertTrue(edit("{\"clearElevator\":true}").isSuccess());
        assertEquals(1,intOf("select count(*) from address where id=? and floor=6 and has_elevator is null",address));
    }
    @Test @DisplayName("明确清两坐标并改主地点，回执与库一致")
    void clearCoordinatesTogether() {
        Api r=edit("{\"detail\":\"手填新地点\",\"lat\":null,\"lng\":null,\"clearCoordinates\":true}");
        assertTrue(r.isSuccess(),r.toString());assertTrue(r.data().path("lat").isNull());assertTrue(r.data().path("lng").isNull());
        assertEquals(1,intOf("select count(*) from address where id=? and lat is null and lng is null and detail='手填新地点'",address));
    }
    @Test @DisplayName("新路由省略或普通NULL保留旧值，不静默变清空")
    void omittedAndNullFieldsKeepValues() {
        assertTrue(edit("{\"name\":\"新收件人\"}").isSuccess());oldValues();
        assertTrue(edit("{\"floor\":null,\"hasElevator\":null,\"lat\":null,\"lng\":null}").isSuccess());oldValues();
        assertEquals("新收件人",jdbc.queryForObject("select name from address where id=?",String.class,address));
    }
    @Test @DisplayName("旧路由NULL仍表示保留楼层电梯")
    void legacyNullKeepsOldSemantics() {
        Api r=put("/api/addresses/"+address,customerToken(customer),"{\"name\":\"旧客户端\",\"detail\":\"合成门牌\",\"floor\":null,\"hasElevator\":null}");
        assertTrue(r.isSuccess(),r.toString());
        assertEquals(6,intOf("select floor from address where id=?",address));assertEquals(0,intOf("select has_elevator from address where id=?",address));
    }
    @Test @DisplayName("明确零楼层与无电梯不被当成未填写")
    void zeroAndConfirmedElevatorRemainDistinct() {
        Api r=edit("{\"floor\":0,\"hasElevator\":1}");assertTrue(r.isSuccess(),r.toString());
        assertEquals(0,r.data().path("floor").asInt());assertEquals(1,r.data().path("hasElevator").asInt());
        assertTrue(edit("{\"hasElevator\":0}").isSuccess());assertEquals(0,intOf("select has_elevator from address where id=?",address));
    }
    @Test @DisplayName("清空与设值冲突、非法坐标及枚举均拒绝，文字也不落库")
    void contradictoryOrInvalidPatchNeverPartiallyWrites() {
        for(String body:List.of("\"clearFloor\":true,\"floor\":0","\"clearElevator\":true,\"hasElevator\":0",
                "\"clearCoordinates\":true,\"lat\":30,\"lng\":120","\"lat\":30","\"lat\":91,\"lng\":120",
                "\"lat\":30,\"lng\":181","\"hasElevator\":2","\"floor\":-1","\"isDefault\":2")) {
            Api r=edit("{\"name\":\"不得保存\","+body+"}");assertEquals(1,r.code(),r.toString());oldValues();
            assertEquals("合成收件人",jdbc.queryForObject("select name from address where id=?",String.class,address));
        }
    }
    @Test @DisplayName("客户归属不可伪造，拒绝前后默认标记不变")
    void ownerCannotBeChanged() {
        long other=createCustomer("合成另一客户","explicit-other");
        assertEquals(1,edit("{\"customerId\":"+other+",\"clearFloor\":true}").code());oldValues();
        assertEquals(1,intOf("select is_default from address where id=?",address));
    }
    @Test @DisplayName("他人、站长与配送员不能使用顾客本人版本化路由")
    void onlyOwningCustomerMayPatch() {
        long other=createCustomer("合成另一客户","explicit-other");long station=createStation("合成地址站");
        long manager=createStaff("合成站长","STATION_MANAGER",station,1),driver=createStaff("合成配送员","DELIVERY",station,1);
        for(String token:List.of(customerToken(other),staffToken(manager,"STATION_MANAGER",station),staffToken(driver,"DELIVERY",station)))
            assertFalse(put("/api/addresses/"+address+"/profile",token,"{\"clearFloor\":true}").isSuccess());
        oldValues();
        assertEquals(1,intOf("select count(*) from address where id=? and customer_id=? and is_default=1",address,customer));
        assertEquals("合成收件人",jdbc.queryForObject("select name from address where id=?",String.class,address));
    }
    @Test @DisplayName("设默认与清空同事务生效，省略默认值保留唯一默认")
    void defaultRemainsOwnedAndUnique() {
        long second=insert("insert into address(customer_id,detail,is_default) values(?,?,0)",customer,"合成次地址");
        assertTrue(put("/api/addresses/"+second+"/profile",customerToken(customer),"{\"isDefault\":1,\"clearFloor\":true}").isSuccess());
        assertEquals(0,intOf("select is_default from address where id=?",address));
        assertTrue(put("/api/addresses/"+second+"/profile",customerToken(customer),"{\"name\":\"再编辑\"}").isSuccess());
        assertEquals(1,intOf("select count(*) from address where customer_id=? and is_default=1",customer));
        assertEquals(1,intOf("select is_default from address where id=?",second));
    }
    @Test @DisplayName("两个编辑者未提供的字段不会覆盖另一方已写入值")
    void partialEditKeepsOtherEditorsFields() {
        assertTrue(edit("{\"phone\":\"13900000000\"}").isSuccess());
        assertTrue(edit("{\"clearFloor\":true}").isSuccess());
        assertEquals("13900000000",jdbc.queryForObject("select phone from address where id=?",String.class,address));
        assertEquals(1,intOf("select count(*) from address where id=? and floor is null",address));
    }
    @Test @DisplayName("明确设点允许合法零坐标并保留其他字段")
    void setCoordinatesIncludingZero() {
        Api r=edit("{\"lat\":0,\"lng\":0}");assertTrue(r.isSuccess(),r.toString());
        assertEquals(0,decimalOf("select lat from address where id=?",address).compareTo(BigDecimal.ZERO));
        assertEquals("合成门牌",r.data().path("detail").asText());
    }
    @Test @DisplayName("真实清空和默认变更之后强制失败，整个事务回滚")
    void clearAndDefaultRollbackAfterRealWrite() {
        long second=insert("insert into address(customer_id,detail,is_default,floor) values(?,?,0,8)",customer,"合成次地址");
        AddressProfileDTO request=new AddressProfileDTO();request.setClearFloor(true);request.setIsDefault(1);
        assertThrows(BusinessException.class,()->new TransactionTemplate(transactions).execute(status->{
            addresses.updateProfile(customer,second,request);
            assertEquals(1,intOf("select count(*) from address where id=? and floor is null and is_default=1",second));
            assertEquals(0,intOf("select is_default from address where id=?",address));
            throw new BusinessException("合成提交后回滚故障");
        }));
        assertEquals(8,intOf("select floor from address where id=?",second));
        assertEquals(0,intOf("select is_default from address where id=?",second));
        assertEquals(1,intOf("select is_default from address where id=?",address));
    }
    @Test @DisplayName("清楼层后报价不再沿用旧楼层费，订单快照保留原值")
    void clearedFloorChangesFutureQuoteButNotHistoricalOrder() {
        long station=createStation("合成报价站"),manager=createStaff("合成报价站长","STATION_MANAGER",station,1);
        long product=createProduct("合成报价水",1,"20.00","30.00",0,"0.00");createInventoryFull(station,product,100,0,"0.00");
        assertTrue(put("/api/manager/delivery-config",staffToken(manager,"STATION_MANAGER",station),"{\"floorFeePerLevel\":2,\"floorFreeLevel\":1}").isSuccess());
        String body="{\"stationId\":"+station+",\"addressId\":"+address+",\"paymentMethod\":1,\"items\":[{\"productId\":"+product+",\"quantity\":1}]}";
        Api before=post("/api/payments/quote",customerToken(customer),body);assertTrue(before.isSuccess(),before.toString());
        assertEquals(0,new BigDecimal("10").compareTo(new BigDecimal(before.data().path("floorFee").asText())));
        String create=body.substring(0,body.length()-1)+",\"idempotencyKey\":\"clear-future-quote\"}";
        Api order=post("/api/orders/create",customerToken(customer),create);assertTrue(order.isSuccess(),order.toString());long id=order.data().path("orderId").asLong();
        assertTrue(edit("{\"clearFloor\":true}").isSuccess());
        Api after=post("/api/payments/quote",customerToken(customer),body);assertTrue(after.isSuccess(),after.toString());
        assertEquals(0,BigDecimal.ZERO.compareTo(new BigDecimal(after.data().path("floorFee").asText())));
        assertEquals(0,new BigDecimal("10").compareTo(decimalOf("select floor_fee from orders where id=?",id)));
    }
}
