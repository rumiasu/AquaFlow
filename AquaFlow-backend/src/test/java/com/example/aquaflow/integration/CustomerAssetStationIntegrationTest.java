package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** 只读本人关联站目录；不以公开营业站目录代替，不把履约/结算站当资产归属站。 */
class CustomerAssetStationIntegrationTest extends AbstractIntegrationTest {
    static final String PATH = "/api/customer-assets/stations";

    Set<Long> ids(Api response) {
        assertEquals(0, response.code(), response.toString());
        var result = new HashSet<Long>();
        for (var row : response.data().path("stations")) {
            assertEquals(4, row.size(), "只下发 id/name/status/statusText");
            assertFalse(row.has("phone")); assertFalse(row.has("address")); assertFalse(row.has("creatorId"));
            assertTrue(result.add(row.path("id").asLong()), "同站多种关系不得重复");
        }
        return result;
    }

    @Test void allOwnershipSourcesIncludeHistoryZeroBalancesAndStoppedStations() {
        long customer = createCustomer("查看客户", "asset-view-owner");
        long stranger = createCustomer("其他客户", "asset-view-stranger");
        long product = createProduct("桶装水", 1,"20.00","60.00",1,"18.00");
        long address = createAddress(customer,"测试地址");
        var expected = new HashSet<Long>();
        for (int source=0; source<13; source++) {
            long station = createStation("资产关系" + source); expected.add(station);
            switch (source) {
                case 0 -> createCustomerStationConfig(customer,station,0);
                case 1 -> createOrder(customer,address,station,product,5,4);
                case 2 -> createDepositBalance(customer,station,"0.00");
                case 3 -> createBarrelAsset(customer,station,product,0,"0.00");
                case 4 -> createBarrelOver(customer,station,product,1);
                case 5 -> createBarrelLot("view-lot",customer,station,product,"60.00",1,0);
                case 6 -> createBarrelInTransit(customer,station,product,1,"60.00",null,"CANCELLED");
                case 7 -> createTicketAccount(customer,station,product,0);
                case 8 -> insert("INSERT INTO ticket_lot(lot_no,customer_id,station_id,product_id,unit_price,qty,remain_qty,source_type,price_source,status) VALUES (?,?,?,?,0,1,0,3,3,2)","view-ticket-lot",customer,station,product);
                case 9 -> insert("INSERT INTO deposit_record(customer_id,station_id,type,amount) VALUES (?,?,2,-60)",customer,station);
                case 10 -> insert("INSERT INTO barrel_record(customer_id,station_id,product_id,type,quantity,status) VALUES (?,?,?,2,1,3)",customer,station,product);
                case 11 -> insert("INSERT INTO ticket_record(customer_id,station_id,product_id,decrease_qty,source) VALUES (?,?,?,1,'消费')",customer,station,product);
                case 12 -> {
                    long payment = createPaymentRecord(null,customer,station,"60.00",2,1);
                    insert("INSERT INTO barrel_right_purchase(customer_id,station_id,product_id,quantity,unit_price,amount,payment_id,idempotency_key) VALUES (?,?,?,1,60,60,?,'view-purchase')",customer,station,product,payment);
                }
            }
            if (source==9) jdbc.update("UPDATE station SET status=2 WHERE id=?",station);
        }
        long foreign = createStation("他人水站"); createDepositBalance(stranger,foreign,"300.00");
        long publicOnly = createStation("无关系营业站");
        var response = get(PATH + "?customerId=" + stranger, customerToken(customer));
        assertEquals(expected,ids(response)); assertFalse(ids(response).contains(publicOnly));
        assertEquals(1, get(PATH + "/" + foreign,customerToken(customer)).code());
        // 上述目录请求没有创建账户、扣票、改绑定或变更营业状态。
        assertEquals(2,intOf("SELECT count(*) FROM customer_deposit_account"));
        assertEquals(1,intOf("SELECT count(*) FROM customer_station_config WHERE customer_id=?",customer));
        assertEquals(1,intOf("SELECT count(*) FROM station WHERE status=2"));
        assertEquals(0,intOf("SELECT count(*) FROM ticket_account WHERE customer_id=? AND remain_quantity<>0",customer));
    }

    @Test void stationDirectoryUsesOwnershipAndDeduplicatesAcrossAssetTypes() {
        long customer=createCustomer("归属客户","asset-view-origin"), product=createProduct("水",1,"20","60",0,"0");
        long owner=createStation("归属站"), delivery=createStation("履约站");
        long order=createOrder(customer,createAddress(customer,"地址"),owner,product,4,2);
        jdbc.update("UPDATE orders SET delivery_station_id=?,settle_station_id=? WHERE id=?",delivery,delivery,order);
        createDepositBalance(customer,owner,"0"); createCustomerStationConfig(customer,owner,0);
        assertEquals(Set.of(owner),ids(get(PATH,customerToken(customer))));
        assertEquals(1,get(PATH + "/" + delivery,customerToken(customer)).code());
        assertEquals(0,get(PATH + "/" + owner,customerToken(customer)).code());
    }

    @Test void cursorTraversesMoreThanOnePageWithoutMissingRelations() {
        long customer=createCustomer("多站客户","asset-view-pages"); var expected=new HashSet<Long>();
        for(int i=0;i<45;i++) { long station=createStation("多站"+i); expected.add(station); createCustomerStationConfig(customer,station,0); }
        var seen=new HashSet<Long>(); long cursor=0;
        for(int page=0;page<3;page++) {
            var response=get(PATH+"?limit=20&afterStationId="+cursor,customerToken(customer));
            var pageIds=ids(response); assertTrue(pageIds.stream().noneMatch(seen::contains)); seen.addAll(pageIds);
            assertEquals(page<2,response.data().path("hasMore").asBoolean());
            if(page<2) cursor=response.data().path("nextStationId").asLong();
            else assertTrue(response.data().path("nextStationId").isNull());
        }
        assertEquals(expected,seen);
        assertEquals(1,get(PATH+"?limit=51",customerToken(customer)).code());
        assertEquals(1,get(PATH+"?afterStationId=-1",customerToken(customer)).code());
    }

    @Test void unauthenticatedStaffUnselectedAndMissingCustomerCannotReadDirectory() {
        long station=createStation("权限站"), staff=createStaff("站长","STATION_MANAGER",station,1);
        assertEquals(401,get(PATH,null).status());
        assertEquals(1,get(PATH,staffToken(staff,"STATION_MANAGER",station)).code());
        assertEquals(1,get(PATH,unselectedStaffToken(-7L,"asset-unselected")).code());
        assertEquals(1,get(PATH,customerToken(99999999L)).code());
    }
}
