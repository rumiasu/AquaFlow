package com.example.aquaflow.integration;

import com.example.aquaflow.dto.StationUpdateDTO;
import com.example.aquaflow.entity.Address;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.service.AddressService;
import com.example.aquaflow.service.StationService;
import com.example.aquaflow.support.AbstractIntegrationTest;
import com.example.aquaflow.util.AuthContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

/** 强制在真实写入后失败，核验外层事务回滚，不把“校验前没写”当回滚证明。 */
class AddressLocationTransactionIntegrationTest extends AbstractIntegrationTest {
    @Autowired StationService stations;
    @Autowired AddressService addresses;
    @Autowired PlatformTransactionManager transactions;

    @Test @DisplayName("新地址及地图点写入后整体回滚")
    void stationLocationRollsBackAfterActualWrite() {
        long station=createStation("合成事务站"),manager=createStaff("合成站长","STATION_MANAGER",station,1);
        jdbc.update("update station set address='原地址',lat=36.68,lng=117.06 where id=?",station);
        StationUpdateDTO body=new StationUpdateDTO();body.setAddress("新地址");body.setCoordinateMode("SET");
        body.setLat(new BigDecimal("36.7"));body.setLng(new BigDecimal("117.1"));
        AuthContext.set(new AuthContext.AuthUser(manager,"staff","STATION_MANAGER",station));
        try {
            assertThrows(BusinessException.class,()->new TransactionTemplate(transactions).execute(status->{
                stations.updateProfile(station,body);
                assertEquals("新地址",jdbc.queryForObject("select address from station where id=?",String.class,station));
                throw new BusinessException("合成回滚故障");
            }));
        } finally {AuthContext.clear();}
        assertEquals("原地址",jdbc.queryForObject("select address from station where id=?",String.class,station));
        assertEquals(0,decimalOf("select lat from station where id=?",station).compareTo(new BigDecimal("36.68")));
        assertEquals(0,decimalOf("select lng from station where id=?",station).compareTo(new BigDecimal("117.06")));
    }

    @Test @DisplayName("新默认地址实际写入后失败，恢复原默认且不留下新地址")
    void newDefaultRollsBackWithItsInsert() {
        long customer=createCustomer("合成客户","addr-rollback"),old=createAddress(customer,"原默认");
        Address next=new Address();next.setCustomerId(customer);next.setDetail("新默认");next.setIsDefault(1);
        assertThrows(BusinessException.class,()->new TransactionTemplate(transactions).execute(status->{
            addresses.save(next);
            assertEquals(0,intOf("select is_default from address where id=?",old));
            assertEquals(1,intOf("select is_default from address where id=?",next.getId()));
            throw new BusinessException("合成回滚故障");
        }));
        assertEquals(1,intOf("select is_default from address where id=?",old));
        assertEquals(1,intOf("select count(*) from address where customer_id=?",customer));
    }
}
