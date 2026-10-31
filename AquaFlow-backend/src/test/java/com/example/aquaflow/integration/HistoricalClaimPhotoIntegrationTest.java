package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import com.example.aquaflow.util.CosUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real HTTP/JWT/MySQL; external object upload is synthetic, private URL signing is the real SDK offline. */
class HistoricalClaimPhotoIntegrationTest extends AbstractIntegrationTest {
    @MockitoBean CosUtil cos;
    private final CosUtil signer = new CosUtil("ap-guangzhou", "synthetic-id", "synthetic-secret", "synthetic-123456");
    private long station, customer, manager;
    @BeforeEach void configureStorage() {
        when(cos.isCredentialsConfigured()).thenReturn(true);
        when(cos.uploadPrivate(any(),eq("private/asset-claim"),eq(".png"))).thenReturn("private/asset-claim/synthetic-proof.png");
        when(cos.generatePrivateUrl(anyString())).thenAnswer(c -> signer.generatePrivateUrl(c.getArgument(0)));
        when(cos.generatePublicUrl(anyString())).thenAnswer(c -> signer.generatePublicUrl(c.getArgument(0)));
        station=createStation("合成照片站"); customer=createCustomer("合成照片客户","synthetic-photo-customer");
        manager=createStaff("合成照片站长","STATION_MANAGER",station,1);
    }
    private Api upload(String token, long target, String key, boolean valid) throws Exception {
        String boundary="SyntheticClaimBoundary";
        String head="--"+boundary+"\r\nContent-Disposition: form-data; name=\"stationId\"\r\n\r\n"+target
            +"\r\n--"+boundary+"\r\nContent-Disposition: form-data; name=\"clientToken\"\r\n\r\n"+key
            +"\r\n--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"proof.png\"\r\nContent-Type: image/png\r\n\r\n";
        byte[] image=valid?new byte[]{(byte)137,80,78,71,13,10,26,10,1}:new byte[]{1,2,3};
        var out=new java.io.ByteArrayOutputStream(); out.write(head.getBytes(StandardCharsets.UTF_8)); out.write(image);
        out.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/asset-claims/photo"))
            .header("Content-Type","multipart/form-data; boundary="+boundary);
        if(token!=null) builder.header("Authorization","Bearer "+token);
        var response=HttpClient.newHttpClient().send(builder.POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build(),HttpResponse.BodyHandlers.ofString());
        return new Api(response.statusCode(),om.readTree(response.body()));
    }
    private long submit(String key) {
        var response=post("/api/asset-claims",customerToken(customer),"{\"stationId\":"+station+",\"clientToken\":\""+key+"\",\"confirmed\":true,\"depositBalance\":10.00,\"note\":\"合成历史押金凭证核对\",\"items\":[]}");
        assertTrue(response.isSuccess(),response.message()); return response.data().path("id").asLong();
    }
    private void privateLink(Api response) {
        assertTrue(response.isSuccess(),response.message()); assertTrue(response.data().path("exists").asBoolean());
        String url=response.data().path("url").asText(); assertTrue(url.startsWith("https://"));
        assertTrue(URI.create(url).getPath().startsWith("/private/asset-claim/"));
        String query=URLDecoder.decode(URI.create(url).getRawQuery(),StandardCharsets.UTF_8);
        assertTrue(query.contains("q-signature="));
        String[] times=java.util.Arrays.stream(query.split("&")).filter(p -> p.startsWith("q-sign-time=")).findFirst().orElseThrow().substring(12).split(";");
        long start=Long.parseLong(times[0]),end=Long.parseLong(times[1]);
        assertEquals(3600,end-start); assertTrue(end>System.currentTimeMillis()/1000 && end<=System.currentTimeMillis()/1000+3610);
    }
    @Test void originalCustomerAndItsManagerReadShortLivedPrivateLinksWithoutReupload() throws Exception {
        privateLink(upload(customerToken(customer),station,"photo-a",true));
        privateLink(upload(customerToken(customer),station,"photo-a",true));
        verify(cos,times(1)).uploadPrivate(any(),anyString(),anyString());
        assertEquals(customer,jdbc.queryForObject("select customer_id from historical_asset_claim_photo",Long.class));
        long claim=submit("photo-a");
        privateLink(get("/api/asset-claims/photo?stationId="+station+"&clientToken=photo-a",customerToken(customer)));
        privateLink(get("/api/manager/asset-claims/"+claim+"/photo",staffToken(manager,"STATION_MANAGER",station)));
        assertEquals(1,intOf("select count(*) from historical_asset_claim_photo"));
    }
    @Test void crossCustomerCrossStationDeliveryAndUnauthenticatedAccessNeverDisclosePhoto() throws Exception {
        assertTrue(upload(customerToken(customer),station,"photo-b",true).isSuccess()); long claim=submit("photo-b");
        long otherCustomer=createCustomer("合成其他客户","synthetic-photo-other"), otherStation=createStation("合成其他站");
        long otherManager=createStaff("合成其他站长","STATION_MANAGER",otherStation,1), delivery=createStaff("合成配送","DELIVERY",station,1);
        clearInvocations(cos);
        var hidden=get("/api/asset-claims/photo?stationId="+station+"&clientToken=photo-b",customerToken(otherCustomer));
        assertTrue(hidden.isSuccess()); assertFalse(hidden.data().path("exists").asBoolean()); assertTrue(hidden.data().path("url").isNull());
        assertFalse(get("/api/asset-claims/photo?stationId="+otherStation+"&clientToken=photo-b",customerToken(customer)).isSuccess());
        assertFalse(get("/api/manager/asset-claims/"+claim+"/photo",staffToken(otherManager,"STATION_MANAGER",otherStation)).isSuccess());
        assertFalse(get("/api/manager/asset-claims/"+claim+"/photo",staffToken(delivery,"DELIVERY",station)).isSuccess());
        assertFalse(get("/api/manager/asset-claims/"+claim+"/photo",customerToken(customer)).isSuccess());
        assertFalse(upload(null,station,"photo-anon",true).isSuccess());
        assertFalse(upload(customerToken(customer),otherStation,"photo-b",true).isSuccess());
        verify(cos,never()).generatePrivateUrl(anyString()); verify(cos,never()).uploadPrivate(any(),anyString(),anyString());
    }
    @Test void invalidFileAndSubmittedNoPhotoClaimCannotBeChangedAndCatalogUsesStationImage() throws Exception {
        assertFalse(upload(customerToken(customer),station,"bad-file",false).isSuccess());
        long claim=submit("without-photo");
        assertFalse(upload(customerToken(customer),station,"without-photo",true).isSuccess());
        assertFalse(get("/api/manager/asset-claims/"+claim+"/photo",staffToken(manager,"STATION_MANAGER",station)).data().path("exists").asBoolean());
        assertEquals(0,intOf("select count(*) from historical_asset_claim_photo"));
        long product=createProduct("合成本地图商品",1,"8.00","30.00",1,"8.00"); createInventoryFull(station,product,0,0,"0.00");
        jdbc.update("update product set image_object_name='public/product/global.png' where id=?",product);
        jdbc.update("update inventory set image_object_name_override='public/product/station.png' where station_id=? and product_id=?",station,product);
        var catalog=get("/api/asset-claims/products?stationId="+station,customerToken(customer)); assertTrue(catalog.isSuccess());
        assertTrue(URI.create(catalog.data().get(0).path("imageUrl").asText()).getPath().endsWith("/public/product/station.png"));
    }
    @Test void photoSchemaGuardRejectsIncompleteStructureAndAcceptsRestoredShape() {
        var guard=new com.example.aquaflow.config.HistoricalAssetClaimSchemaGuard(jdbc);
        guard.verifySchema();
        jdbc.execute("alter table historical_asset_claim_photo modify object_name varchar(512) null");
        try { assertThrows(IllegalStateException.class,guard::verifySchema); }
        finally { jdbc.execute("alter table historical_asset_claim_photo modify object_name varchar(512) not null"); }
        guard.verifySchema();
    }
}
