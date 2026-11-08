package com.example.aquaflow.service;

import com.example.aquaflow.controller.HistoricalAssetClaimController;
import com.example.aquaflow.dto.HistoricalAssetClaimDTO;
import com.example.aquaflow.entity.*;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.*;
import com.example.aquaflow.util.AuthContext;
import com.example.aquaflow.util.ProductImageResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 无数据库专项：真实校验/摘要/审批编排，账本与存储隔离；实库原子性另见集成用例。 */
class HistoricalAssetClaimAppendTest {
    private final HistoricalAssetClaimMapper claims=mock(HistoricalAssetClaimMapper.class);
    private final CustomerStationConfigMapper bindings=mock(CustomerStationConfigMapper.class);
    private final StationMapper stations=mock(StationMapper.class);
    private final ProductMapper products=mock(ProductMapper.class);
    private final InventoryMapper inventories=mock(InventoryMapper.class);
    private final BarrelLedgerService barrels=mock(BarrelLedgerService.class);
    private final StationAdjustmentService adjustments=mock(StationAdjustmentService.class);
    private final ObjectMapper json=new ObjectMapper();
    private final org.springframework.transaction.PlatformTransactionManager transactions=mock(org.springframework.transaction.PlatformTransactionManager.class);
    private ValidatorFactory factory;
    private HistoricalAssetClaimService service;
    private HistoricalAssetClaim saved;
    private static BigDecimal n(String s) { return new BigDecimal(s); }
    @BeforeEach void setup() {
        factory=Validation.buildDefaultValidatorFactory();
        service=new HistoricalAssetClaimService(claims,bindings,stations,products,inventories,barrels,
            adjustments,factory.getValidator(),mock(ProductImageResolver.class),transactions);
        when(transactions.getTransaction(any())).thenAnswer(call -> mock(org.springframework.transaction.TransactionStatus.class));
        customer();when(claims.lockCustomer(7L)).thenReturn(7L);
        when(claims.photoStation(anyLong(),anyString())).thenReturn(null);
        when(stations.getById(1L)).thenReturn(new Station());
        var product=new Product();product.setId(2L);product.setCategory(1);
        when(products.getById(2L)).thenReturn(product);
        when(inventories.getByStationAndProduct(1L,2L)).thenReturn(new Inventory());
        when(claims.insert(any())).thenAnswer(call -> {
            saved=call.getArgument(0);saved.setId(19L);saved.setStatus("PENDING");return 1;
        });
        when(claims.get(19L)).thenAnswer(call -> saved);
        when(claims.lock(19L)).thenAnswer(call -> saved);
        when(claims.decide(anyLong(),anyString(),anyLong(),nullable(String.class))).thenAnswer(call -> {
            saved.setStatus(call.getArgument(1));saved.setReviewNote(call.getArgument(3));return 1;
        });
        when(adjustments.create(anyLong(),anyString(),nullable(Long.class),nullable(Integer.class),
            nullable(BigDecimal.class),nullable(BigDecimal.class),anyString(),isNull(),anyString()))
            .thenAnswer(call -> { var a=new StationAdjustment();a.setId(100L);return a; });
    }
    @AfterEach void cleanup() { AuthContext.clear();org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);factory.close(); }
    private void customer() { AuthContext.set(new AuthContext.AuthUser(7L,"customer",null,null)); }
    private void manager() { AuthContext.set(new AuthContext.AuthUser(9L,"staff","STATION_MANAGER",1L)); }
    private HistoricalAssetClaimDTO request(BigDecimal held) {
        return new HistoricalAssetClaimDTO(1L,"old-key",true,n("75.00"),"material",
            List.of(new HistoricalAssetClaimDTO.Item(2L,n("2"),held,n("3"),n("30.00"))));
    }
    private HistoricalAssetClaimDTO empty(BigDecimal held) {
        return new HistoricalAssetClaimDTO(1L,"old-key",true,n("0"),"material",
            List.of(new HistoricalAssetClaimDTO.Item(2L,n("0"),held,n("0"),null)));
    }
    // 固定旧版本字节而非用新 canonical 造兼容证据；字段顺序/金额小数位都是旧摘要的一部分。
    private void legacy(String rights,String held,String tickets,String deposit,String price) throws Exception {
        String payload="{\"stationId\":1,\"clientToken\":\"old-key\",\"confirmed\":true,\"depositBalance\":"+deposit
            +",\"note\":\"material\",\"items\":[{\"productId\":2,\"rights\":"+rights+",\"held\":"+held
            +",\"tickets\":"+tickets+",\"unitPrice\":"+price+"}]}";
        saved=new HistoricalAssetClaim();saved.setId(19L);saved.setCustomerId(7L);saved.setStationId(1L);
        saved.setClientToken("old-key");saved.setStatus("PENDING");saved.setRequestJson(payload);
        saved.setRequestDigest(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(payload.getBytes(StandardCharsets.UTF_8))));
        when(claims.byToken(7L,"old-key")).thenReturn(saved);
    }
    @Test void missingHeldCreatesNoInventedQuantity() throws Exception {
        service.submit(request(null));
        assertFalse(json.readTree(saved.getRequestJson()).path("items").get(0).has("held"));
        assertEquals(0,verifyNoAssets());
    }
    private int verifyNoAssets() { verifyNoInteractions(barrels,adjustments,bindings);return 0; }
    @Test void httpAcceptsOmittedHeldWithRealDtoValidation() throws Exception {
        String body=json.writeValueAsString(request(null));
        var mvc=MockMvcBuilders.standaloneSetup(new HistoricalAssetClaimController(service,mock(HistoricalClaimPhotoService.class))).build();
        mvc.perform(post("/api/asset-claims").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        assertNotNull(saved);assertFalse(saved.getRequestJson().contains("held"));
    }
    @Test void oldCanonicalDigestReplaysUnchangedAndIgnoresLaterCatalogChanges() throws Exception {
        legacy("2","1","3","75.00","30.00");
        assertSame(saved,service.submit(request(n("1.0"))));
        verifyNoInteractions(products,inventories,stations);verify(claims,never()).insert(any());
    }
    @Test void oldKeyCannotChangeOrOmitLegacyHeldEvidence() throws Exception {
        legacy("2","1","3","75.00","30.00");
        assertThrows(BusinessException.class,()->service.submit(request(n("2"))));
        assertThrows(BusinessException.class,()->service.submit(request(null)));
    }
    @Test void oldHeldOnlyCanReplayReadAndRejectButCannotApprove() throws Exception {
        legacy("0","4","0","0.00","null");String payload=saved.getRequestJson(),digest=saved.getRequestDigest();
        assertSame(saved,service.submit(empty(n("4"))));assertSame(saved,service.mine(19L));
        manager();assertSame(saved,service.managerDetail(19L));
        assertThrows(BusinessException.class,()->service.review(19L,new HistoricalAssetClaimDTO.Review(true,"ok")));
        assertEquals("PENDING",saved.getStatus());verifyNoInteractions(bindings,barrels,adjustments);
        service.review(19L,new HistoricalAssetClaimDTO.Review(false,"请填写资产"));
        assertEquals("REJECTED",saved.getStatus());assertEquals(payload,saved.getRequestJson());assertEquals(digest,saved.getRequestDigest());
    }
    @Test void newHeldOnlyIsNotAnAsset() {
        assertThrows(BusinessException.class,()->service.submit(empty(n("4"))));verify(claims,never()).insert(any());
    }
    @Test void optionalHeldStillRejectsNegativeFractionAndOverflow() {
        for(String value:List.of("-1","1.5","2147483648"))
            assertThrows(BusinessException.class,()->service.submit(request(n(value))));
        verify(claims,never()).insert(any());
    }
    @Test void oldSnapshotApprovalPostsOnlyThreeAdditionsAndPreservesEvidence() throws Exception {
        legacy("2","999","3","75.00","30.00");String payload=saved.getRequestJson(),digest=saved.getRequestDigest();manager();
        service.review(19L,new HistoricalAssetClaimDTO.Review(true,"ok"));
        verify(adjustments).create(7L,"BARREL_GRANT",2L,2,null,n("30.00"),"历史资产认领 #19",null,"CLAIM-19-BARREL_GRANT-2");
        verify(adjustments).create(7L,"TICKET_GRANT",2L,3,null,null,"历史资产认领 #19",null,"CLAIM-19-TICKET_GRANT-2");
        verify(adjustments).create(7L,"DEPOSIT_GRANT",null,null,n("75.00"),null,"历史资产认领 #19",null,"CLAIM-19-DEPOSIT_GRANT-0");
        verify(adjustments,times(3)).execute(100L);
        verify(adjustments,never()).create(any(),eq("OVER_ADJUST"),any(),any(),any(),any(),any(),any(),any());
        assertEquals(payload,saved.getRequestJson());assertEquals(digest,saved.getRequestDigest());
        verify(claims,never()).overBalances(any(),any());verify(claims,never()).barrelHistory(any(),any());
        verify(claims,never()).ticketHistory(any(),any());verify(claims,never()).depositHistory(any(),any());
        verify(claims,never()).pendingPayments(any(),any());verify(claims,never()).reservations(any(),any());
    }
    @Test void repeatedApprovalUsesCurrentLockedTerminalWithoutAdditionalEffects() throws Exception {
        legacy("2","1","3","75.00","30.00");manager();
        var review=new HistoricalAssetClaimDTO.Review(true,"ok");service.review(19L,review);service.review(19L,review);
        verify(adjustments,times(3)).execute(100L);verify(claims,times(1)).decide(19L,"APPROVED",9L,"ok");
        assertThrows(BusinessException.class,()->service.review(19L,new HistoricalAssetClaimDTO.Review(false,"changed")));
    }
    @Test void casFailureCannotPostAssets() throws Exception {
        legacy("2","1","3","75.00","30.00");manager();
        when(claims.decide(anyLong(),anyString(),anyLong(),anyString())).thenReturn(0);
        assertThrows(BusinessException.class,()->service.review(19L,new HistoricalAssetClaimDTO.Review(true,"ok")));
        verifyNoInteractions(adjustments);
    }
    @Test void customerThenClaimThenRightsLockPrecedesCasAndWrites() throws Exception {
        legacy("2","1","3","75.00","30.00");manager();service.review(19L,new HistoricalAssetClaimDTO.Review(true,"ok"));
        var order=inOrder(claims,barrels,adjustments);
        order.verify(claims).get(19L);order.verify(claims).lockCustomer(7L);order.verify(claims).lock(19L);
        order.verify(barrels).lockRights(7L,1L,2L);order.verify(claims).decide(19L,"APPROVED",9L,"ok");
        order.verify(adjustments).create(7L,"BARREL_GRANT",2L,2,null,n("30.00"),"历史资产认领 #19",null,"CLAIM-19-BARREL_GRANT-2");
    }
    @Test void crossStationAndDeliveryCannotDecide() throws Exception {
        legacy("2","1","3","75.00","30.00");
        for(var user:List.of(new AuthContext.AuthUser(8L,"staff","STATION_MANAGER",2L),new AuthContext.AuthUser(8L,"staff","DELIVERY",1L))) {
            AuthContext.set(user);assertThrows(BusinessException.class,()->service.review(19L,new HistoricalAssetClaimDTO.Review(true,"ok")));
        }
        verifyNoInteractions(bindings,barrels,adjustments);verify(claims,never()).decide(any(),any(),any(),any());
    }
    @Test void failurePropagatesAndReviewTransactionRollsBack() throws Exception {
        legacy("2","1","3","75.00","30.00");manager();doThrow(new IllegalStateException("synthetic failure")).when(adjustments).execute(100L);
        assertThrows(IllegalStateException.class,()->service.review(19L,new HistoricalAssetClaimDTO.Review(true,"ok")));
        verify(transactions).rollback(any());verify(transactions,never()).commit(any());
        assertArrayEquals(new Class[]{Exception.class},HistoricalAssetClaimService.class.getMethod("submit",HistoricalAssetClaimDTO.class)
            .getAnnotation(org.springframework.transaction.annotation.Transactional.class).rollbackFor());
        assertNull(HistoricalAssetClaimService.class.getMethod("review",Long.class,HistoricalAssetClaimDTO.Review.class)
            .getAnnotation(org.springframework.transaction.annotation.Transactional.class));
    }
    @Test void reviewRetriesOnlyAfterFullRollbackWithTheOriginalDecision() throws Exception {
        legacy("2","1","3","75.00","30.00");manager();
        when(claims.get(19L)).thenThrow(new org.springframework.dao.CannotAcquireLockException("synthetic lock timeout"))
            .thenAnswer(call -> saved);
        var decision=new HistoricalAssetClaimDTO.Review(true,"ok");
        assertEquals("APPROVED",service.review(19L,decision).getStatus());
        var order=inOrder(transactions,claims);
        order.verify(transactions).getTransaction(any());order.verify(claims).get(19L);
        order.verify(transactions).rollback(any());order.verify(transactions).getTransaction(any());order.verify(claims).get(19L);
        verify(transactions).commit(any());verify(claims,times(1)).decide(19L,"APPROVED",9L,"ok");
        verify(adjustments,times(3)).execute(100L);
    }
    @Test void reviewHasBoundedRetriesAndDoesNotRetryBusinessErrors() throws Exception {
        legacy("2","1","3","75.00","30.00");manager();
        when(claims.get(19L)).thenThrow(new org.springframework.dao.CannotAcquireLockException("synthetic conflict"));
        assertThrows(BusinessException.class,()->service.review(19L,new HistoricalAssetClaimDTO.Review(true,"ok")));
        verify(transactions,times(5)).getTransaction(any());verify(transactions,times(5)).rollback(any());
        verifyNoInteractions(adjustments);
        clearInvocations(transactions);doThrow(new BusinessException("synthetic business rejection")).when(claims).get(19L);
        assertThrows(BusinessException.class,()->service.review(19L,new HistoricalAssetClaimDTO.Review(true,"ok")));
        verify(transactions,times(1)).getTransaction(any());verify(transactions,times(1)).rollback(any());
    }
    @Test void reviewWithinAnExistingTransactionNeverRetriesThatFailedTransaction() throws Exception {
        legacy("2","1","3","75.00","30.00");manager();
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        when(claims.get(19L)).thenThrow(new org.springframework.dao.CannotAcquireLockException("synthetic outer conflict"));
        assertThrows(org.springframework.dao.CannotAcquireLockException.class,
            ()->service.review(19L,new HistoricalAssetClaimDTO.Review(true,"ok")));
        verify(claims,times(1)).get(19L);verifyNoInteractions(transactions,adjustments);
    }

}
