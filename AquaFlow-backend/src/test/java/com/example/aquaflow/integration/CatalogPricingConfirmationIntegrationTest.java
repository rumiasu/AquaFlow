package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class CatalogPricingConfirmationIntegrationTest extends AbstractIntegrationTest {
    @Test void selectIsNotConfirmationButSuccessfulSaveAndDefaultRestoreAre() {
        long a=createStation("定价A"), b=createStation("定价B");
        String ta=staffToken(createStaff("A站长","STATION_MANAGER",a,1),"STATION_MANAGER",a);
        String tb=staffToken(createStaff("B站长","STATION_MANAGER",b,1),"STATION_MANAGER",b);
        long product=createProduct("共享参考商品",1,"12.00","0.00",0,"0.00");
        assertEquals(0,post("/api/manager/catalog/"+product+"/select",ta,"{}").code());
        assertEquals(0,post("/api/manager/catalog/"+product+"/select",tb,"{}").code());
        assertEquals(0,confirmed(a,product));
        assertNotEquals(0,put("/api/manager/catalog/"+product,ta,"{\"salePrice\":-1}").code());
        assertEquals(0,confirmed(a,product));
        assertEquals(0,put("/api/manager/catalog/"+product,ta,"{\"enabled\":1}").code());
        assertEquals(1,confirmed(a,product));
        assertEquals(0,confirmed(b,product));
        assertEquals(0,put("/api/manager/catalog/"+product,ta,"{\"salePrice\":18.25,\"depositPrice\":0}").code());
        assertEquals(0,put("/api/manager/catalog/"+product,ta,"{}").code());
        assertEquals("18.25",decimalOf("SELECT sale_price FROM inventory WHERE station_id=? AND product_id=?",a,product).toPlainString());
        assertEquals(0,put("/api/manager/catalog/"+product,ta,"{\"salePrice\":0}").code());
        assertEquals(1,confirmed(a,product));
        assertNull(jdbc.queryForObject("SELECT sale_price FROM inventory WHERE station_id=? AND product_id=?",Object.class,a,product));
        var row=get("/api/manager/catalog",ta).data().findValues("pricingConfirmed");
        assertTrue(row.stream().anyMatch(value->value.asInt()==1));
    }
    private int confirmed(long station,long product) {
        return intOf("SELECT pricing_confirmed FROM inventory WHERE station_id=? AND product_id=?",station,product);
    }
    private void migrate() throws Exception {
        try(var connection=jdbc.getDataSource().getConnection()) {
            ScriptUtils.executeSqlScript(connection,new ByteArrayResource(Files.readAllBytes(Path.of("sql/migration_v85_station_pricing_confirmation.sql"))));
        }
    }
    @Test void migrationIsRepeatableAndOnlyBackfillsProvenStationPricing() throws Exception {
        long station=createStation("旧定价站"),unknown=createProduct("旧参考",1,"12","0",0,"0");
        long known=createProduct("旧覆盖",1,"12","0",0,"0");
        createInventory(station,unknown,7);createInventoryWithStationPricing(station,known,9,"15",null,0,"0");
        var before=jdbc.queryForList("SELECT id,station_id,product_id,quantity,enabled,sale_price,deposit_price,update_time FROM inventory ORDER BY id");
        migrate();assertEquals(0,confirmed(station,unknown));assertEquals(1,confirmed(station,known));
        var first=jdbc.queryForList("SELECT * FROM inventory ORDER BY id");migrate();
        assertEquals(first,jdbc.queryForList("SELECT * FROM inventory ORDER BY id"));
        assertEquals(before,jdbc.queryForList("SELECT id,station_id,product_id,quantity,enabled,sale_price,deposit_price,update_time FROM inventory ORDER BY id"));
    }
}
