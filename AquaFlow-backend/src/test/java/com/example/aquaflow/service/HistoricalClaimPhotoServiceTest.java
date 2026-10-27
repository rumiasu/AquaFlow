package com.example.aquaflow.service;

import com.example.aquaflow.entity.HistoricalAssetClaim;
import com.example.aquaflow.entity.Station;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.HistoricalAssetClaimMapper;
import com.example.aquaflow.mapper.StationMapper;
import com.example.aquaflow.util.AuthContext;
import com.example.aquaflow.util.CosUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockMultipartFile;
import java.sql.ResultSet;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HistoricalClaimPhotoServiceTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final HistoricalAssetClaimMapper claims=mock(HistoricalAssetClaimMapper.class);
    private final StationMapper stations=mock(StationMapper.class);
    private final CosUtil cos=mock(CosUtil.class);
    private final HistoricalAssetClaimService claimService=mock(HistoricalAssetClaimService.class);
    private final HistoricalClaimPhotoService photos=new HistoricalClaimPhotoService(jdbc,claims,stations,cos,claimService);
    @AfterEach void clear() { AuthContext.clear(); }
    private void customer() { AuthContext.set(new AuthContext.AuthUser(7L,"customer",null,null)); }
    private void empty() { doReturn(List.of()).when(jdbc).query(anyString(),any(RowMapper.class),any(),any()); }
    private void saved(long station) {
        doAnswer(call -> {
            RowMapper<?> mapper=call.getArgument(1); ResultSet rs=mock(ResultSet.class);
            when(rs.getLong(1)).thenReturn(station); when(rs.getString(2)).thenReturn("private/asset-claim/synthetic.png");
            return List.of(mapper.mapRow(rs,0));
        }).when(jdbc).query(anyString(),any(RowMapper.class),any(),any());
    }
    private MockMultipartFile png() { return new MockMultipartFile("file","proof.png","image/png",new byte[]{(byte)137,80,78,71,13,10,26,10,1}); }
    @Test void uploadBindsCurrentCustomerAndRetriesExistingPhotoWithoutReupload() {
        customer(); empty(); when(stations.getById(3L)).thenReturn(new Station()); when(claims.lockCustomer(7L)).thenReturn(7L);
        when(cos.isCredentialsConfigured()).thenReturn(true); when(cos.uploadPrivate(any(),anyString(),eq(".png"))).thenReturn("private/asset-claim/synthetic.png");
        when(jdbc.update(anyString(),eq(7L),eq(3L),eq("claim-a"),anyString())).thenReturn(1);
        when(cos.generatePrivateUrl(anyString())).thenReturn("https://example.test/signed");
        assertTrue(photos.upload(3L,"claim-a",png()).exists());
        verify(jdbc).update(anyString(),eq(7L),eq(3L),eq("claim-a"),anyString());
        saved(3); assertTrue(photos.upload(3L,"claim-a",png()).exists()); verify(cos,times(1)).uploadPrivate(any(),anyString(),anyString());
        assertThrows(BusinessException.class,()->photos.mine(4L,"claim-a"));
    }
    @Test void uploadRejectsInvalidContentAndExistingClaimWithoutWriting() {
        customer(); empty(); when(stations.getById(3L)).thenReturn(new Station()); when(claims.lockCustomer(7L)).thenReturn(7L);
        var fake=new MockMultipartFile("file","fake.png","image/png",new byte[]{1,2,3});
        assertThrows(BusinessException.class,()->photos.upload(3L,"claim-a",fake));
        when(claims.byToken(7L,"claim-a")).thenReturn(new HistoricalAssetClaim());
        assertThrows(BusinessException.class,()->photos.upload(3L,"claim-a",png()));
        verify(cos,never()).uploadPrivate(any(),anyString(),anyString()); verify(jdbc,never()).update(anyString(),any(),any(),any(),any());
    }
    @Test void managerPhotoAlwaysChecksClaimAuthorityBeforeLookingUpObject() {
        when(claimService.managerDetail(9L)).thenThrow(new BusinessException("无权访问"));
        assertThrows(BusinessException.class,()->photos.manager(9)); verifyNoInteractions(jdbc,cos);
        HistoricalAssetClaim claim=new HistoricalAssetClaim(); claim.setCustomerId(7L); claim.setStationId(3L); claim.setClientToken("claim-a");
        doReturn(claim).when(claimService).managerDetail(9L); saved(3); when(cos.generatePrivateUrl(anyString())).thenReturn("https://example.test/signed");
        assertEquals("https://example.test/signed",photos.manager(9).url());
        saved(4); assertThrows(BusinessException.class,()->photos.manager(9));
    }
    @Test void failedSigningIsReportedAsExistingButUnavailable() {
        customer(); saved(3); when(cos.generatePrivateUrl(anyString())).thenThrow(new IllegalStateException());
        var result=photos.mine(3L,"claim-a"); assertTrue(result.exists()); assertNull(result.url());
    }
}
