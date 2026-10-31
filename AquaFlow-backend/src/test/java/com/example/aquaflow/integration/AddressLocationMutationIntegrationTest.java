package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 地址融合、默认标记及历史引用删除均走真实HTTP和MySQL，不以页面mock代替写入验证。 */
@DisplayName("地址管理与水站地址地图融合")
class AddressLocationMutationIntegrationTest extends AbstractIntegrationTest {
    private long station, manager;
    private void stationSeed() {
        station = createStation("合成融合站");
        manager = createStaff("合成站长", "STATION_MANAGER", station, 1);
        jdbc.update("update station set address='原合成地址',lat=36.680000,lng=117.060000 where id=?", station);
    }
    private String token() { return staffToken(manager, "STATION_MANAGER", station); }
    private String stationBody(String extra) {
        return "{\"name\":\"合成融合站\",\"phone\":\"0531-00000000\",\"status\":1,\"address\":\"新合成地址2单元\"" + extra + "}";
    }
    private String addressBody(String detail, String extra) {
        return "{\"name\":\"合成收件人\",\"phone\":\"13800000000\",\"detail\":\""+detail+"\""+extra+"}";
    }
    private void oldLocation() {
        assertEquals("原合成地址", jdbc.queryForObject("select address from station where id=?", String.class, station));
        assertEquals(0, decimalOf("select lat from station where id=?", station).compareTo(new BigDecimal("36.68")));
        assertEquals(0, decimalOf("select lng from station where id=?", station).compareTo(new BigDecimal("117.06")));
    }

    @Test @DisplayName("资料、地址和重选坐标同次成功，响应回读当前站")
    void locationSetWritesAddressAndCoordinatesTogether() {
        stationSeed();
        Api result=put("/api/stations/"+station+"/profile",token(),stationBody(",\"coordinateMode\":\"SET\",\"lat\":36.7,\"lng\":117.1"));
        assertTrue(result.isSuccess(), result.toString());
        assertEquals("新合成地址2单元",jdbc.queryForObject("select address from station where id=?",String.class,station));
        assertEquals(0,decimalOf("select lat from station where id=?",station).compareTo(new BigDecimal("36.7")));
        assertEquals(0,decimalOf("select lng from station where id=?",station).compareTo(new BigDecimal("117.1")));
        assertEquals(station,result.data().path("id").asLong());
    }

    @Test @DisplayName("只补门牌显式KEEP保留原地图点")
    void locationKeepIsTextOnly() {
        stationSeed();
        assertTrue(put("/api/stations/"+station+"/profile",token(),"{\"address\":\"原合成地址3楼\",\"coordinateMode\":\"KEEP\"}").isSuccess());
        assertEquals("原合成地址3楼",jdbc.queryForObject("select address from station where id=?",String.class,station));
        assertEquals(0,decimalOf("select lat from station where id=?",station).compareTo(new BigDecimal("36.68")));
        assertEquals("合成融合站",jdbc.queryForObject("select name from station where id=?",String.class,station));
    }

    @Test @DisplayName("纯手填地址显式CLEAR同时清空两坐标")
    void locationClearRequiresExplicitIntent() {
        stationSeed();
        assertTrue(put("/api/stations/"+station+"/profile",token(),stationBody(",\"coordinateMode\":\"CLEAR\"")).isSuccess());
        assertEquals(1,intOf("select count(*) from station where id=? and lat is null and lng is null",station));
        assertEquals("新合成地址2单元",jdbc.queryForObject("select address from station where id=?",String.class,station));
    }

    @Test @DisplayName("非法、缺失、冲突坐标与空地址均拒绝，文字和坐标整体不写")
    void invalidLocationNeverPartiallySavesText() {
        stationSeed();
        for (String suffix:List.of(
                ",\"coordinateMode\":\"SET\",\"lat\":36.7",
                ",\"coordinateMode\":\"SET\"",
                ",\"coordinateMode\":\"SET\",\"lat\":90.01,\"lng\":117",
                ",\"coordinateMode\":\"SET\",\"lat\":36,\"lng\":180.01",
                ",\"coordinateMode\":\"KEEP\",\"lat\":36,\"lng\":117",
                ",\"coordinateMode\":\"CLEAR\",\"lat\":36,\"lng\":117",
                ",\"coordinateMode\":\"UNKNOWN\"")) {
            Api bad=put("/api/stations/"+station+"/profile",token(),stationBody(suffix));
            assertEquals(1,bad.code(),bad.toString());oldLocation();
        }
        assertEquals(1,put("/api/stations/"+station+"/profile",token(),"{\"address\":\" \",\"coordinateMode\":\"CLEAR\"}").code());
        assertEquals(1,put("/api/stations/"+station+"/profile",token(),"{\"address\":\""+"址".repeat(201)+"\",\"coordinateMode\":\"KEEP\"}").code());
        oldLocation();
    }

    @Test @DisplayName("旧资料更新不带mode保留坐标，旧坐标接口仍支持选点和清空")
    void legacyCallsRemainCompatible() {
        stationSeed();
        assertTrue(put("/api/stations/"+station,token(),stationBody(",\"lat\":0,\"lng\":0")).isSuccess());
        assertEquals(0,decimalOf("select lat from station where id=?",station).compareTo(new BigDecimal("36.68")));
        assertTrue(put("/api/stations/mine/coordinates",token(),"{\"lat\":36.8,\"lng\":117.2}").isSuccess());
        assertTrue(put("/api/stations/mine/coordinates",token(),"{\"lat\":null,\"lng\":null}").isSuccess());
        assertEquals(1,intOf("select count(*) from station where id=? and lat is null and lng is null",station));
    }

    @Test @DisplayName("他站站长、配送员和顾客不得改本站位置")
    void locationPermissionBoundaries() {
        stationSeed();long other=createStation("合成他站");long m=createStaff("合成他站长","STATION_MANAGER",other,1);
        long driver=createStaff("合成配送员","DELIVERY",station,1);long customer=createCustomer("合成客户","addr-scope");
        for(String t:List.of(staffToken(m,"STATION_MANAGER",other),staffToken(driver,"DELIVERY",station),customerToken(customer)))
            assertFalse(put("/api/stations/"+station+"/profile",t,stationBody(",\"coordinateMode\":\"CLEAR\"")).isSuccess());
        assertEquals("原合成地址", jdbc.queryForObject("select address from station where id=?", String.class, station));
        oldLocation();
    }

    @Test @DisplayName("编辑并设默认不会被整行NULL覆盖，也不清掉其他客户默认")
    void editingDefaultUsesActualOwnerAndRetainsFlag() {
        long c=createCustomer("合成甲","addr-a"),other=createCustomer("合成乙","addr-b");
        long old=createAddress(c,"原默认"),target=insert("insert into address(customer_id,detail,is_default) values(?,?,0)",c,"待编辑");
        long foreign=createAddress(other,"其他客户默认");
        Api result=put("/api/addresses/"+target,customerToken(c),addressBody("改好门牌",",\"isDefault\":1,\"customerId\":"+other));
        assertTrue(result.isSuccess(),result.toString());
        assertEquals(1,intOf("select is_default from address where id=?",target));
        assertEquals(0,intOf("select is_default from address where id=?",old));
        assertEquals(1,intOf("select is_default from address where id=?",foreign));
        assertEquals(c,longOf("select customer_id from address where id=?",target));
    }

    @Test @DisplayName("编辑省略默认标记保留原默认，楼层和电梯旧兼容不丢")
    void omittedDefaultAndLegacyFloorStayIntact() {
        long c=createCustomer("合成客户","addr-preserve"),id=createAddress(c,"原地址");
        jdbc.update("update address set floor=3,has_elevator=1 where id=?",id);
        assertTrue(put("/api/addresses/"+id,customerToken(c),addressBody("仅补门牌","")).isSuccess());
        assertEquals(1,intOf("select is_default from address where id=?",id));
        assertEquals(3,intOf("select floor from address where id=?",id));
        assertEquals(1,intOf("select has_elevator from address where id=?",id));
    }

    @Test @DisplayName("新默认地址原子替换旧默认，非默认新地址不抢默认")
    void newDefaultsReplaceOnlyWhenRequested() {
        long c=createCustomer("合成客户","addr-create");createAddress(c,"原默认");String t=customerToken(c);
        Api a=post("/api/addresses",t,addressBody("新默认",",\"isDefault\":1"));assertTrue(a.isSuccess(),a.toString());
        assertTrue(post("/api/addresses",t,addressBody("普通地址","")).isSuccess());
        assertEquals(1,intOf("select count(*) from address where customer_id=? and is_default=1",c));
        assertEquals(a.data().asLong(),longOf("select id from address where customer_id=? and is_default=1",c));
    }

    @Test @DisplayName("两次并发新默认均可完成，最终默认仍唯一")
    void concurrentNewDefaultsStayUnique() throws Exception {
        long c=createCustomer("合成客户","addr-concurrent");createAddress(c,"原默认");String t=customerToken(c);
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch go=new CountDownLatch(1);
        try {
            Future<Api> a=pool.submit(()->{go.await();return post("/api/addresses",t,addressBody("并发甲",",\"isDefault\":1"));});
            Future<Api> b=pool.submit(()->{go.await();return post("/api/addresses",t,addressBody("并发乙",",\"isDefault\":1"));});
            go.countDown();assertTrue(a.get(30,TimeUnit.SECONDS).isSuccess());assertTrue(b.get(30,TimeUnit.SECONDS).isSuccess());
            assertEquals(3,intOf("select count(*) from address where customer_id=?",c));
            assertEquals(1,intOf("select count(*) from address where customer_id=? and is_default=1",c));
        } finally {pool.shutdownNow();}
    }

    @Test @DisplayName("地址半坐标或越界被拒，原默认不丢")
    void invalidAddressCoordinatesDoNotClearDefault() {
        long c=createCustomer("合成客户","addr-invalid");long id=createAddress(c,"原默认");String t=customerToken(c);
        for(String x:List.of(",\"lat\":36.6",",\"lat\":91,\"lng\":117",",\"lat\":36,\"lng\":181",",\"isDefault\":2"))
            assertEquals(1,post("/api/addresses",t,addressBody("无效地址",x)).code());
        assertEquals(1,intOf("select is_default from address where id=?",id));
        assertEquals(1,intOf("select count(*) from address where customer_id=?",c));
    }

    @Test @DisplayName("已被订单引用的地址如实拒绝删除，订单所有列与地址保留")
    void referencedAddressDeleteIsReadableAndNonDestructive() {
        long s=createStation("合成站"),c=createCustomer("合成客户","addr-ref"),id=createAddress(c,"合成历史地址");
        long p=createProduct("合成瓶水",2,"10.00","0.00",0,"0.00");
        long order=createOrderFull(c,id,s,p,4,2,2,"10.00","0.00","10.00",false,0);
        var snapshot=jdbc.queryForMap("select * from orders where id=?",order);
        Api result=delete("/api/addresses/"+id,customerToken(c));
        assertEquals(1,result.code(),result.toString());assertTrue(result.message().contains("已被订单使用"),result.message());
        assertEquals(1,intOf("select count(*) from address where id=?",id));
        assertEquals(snapshot,jdbc.queryForMap("select * from orders where id=?",order));
    }

    @Test @DisplayName("未引用本人地址可真删，他人地址拒绝且不动默认")
    void unreferencedDeleteAndOwnershipStillWork() {
        long a=createCustomer("合成甲","addr-del-a"),b=createCustomer("合成乙","addr-del-b");
        long mine=createAddress(a,"本人地址"),other=createAddress(b,"他人地址");
        assertFalse(delete("/api/addresses/"+other,customerToken(a)).isSuccess());
        assertEquals(1,intOf("select is_default from address where id=?",other));
        assertTrue(delete("/api/addresses/"+mine,customerToken(a)).isSuccess());
        assertEquals(0,intOf("select count(*) from address where id=?",mine));
    }
}
