package com.example.aquaflow.service;

import com.example.aquaflow.entity.HistoricalAssetClaim;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.HistoricalAssetClaimMapper;
import com.example.aquaflow.mapper.StationMapper;
import com.example.aquaflow.util.AuthContext;
import com.example.aquaflow.util.CosUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;

/** 单张可选凭证；不将临时签名 URL 写入申请快照。 */
@Service
public class HistoricalClaimPhotoService {
    private final JdbcTemplate jdbc;
    private final HistoricalAssetClaimMapper claims;
    private final StationMapper stations;
    private final CosUtil cos;
    private final HistoricalAssetClaimService claimService;
    public record Photo(boolean exists, String url) {}
    private record Row(long stationId, String objectName) {}
    public HistoricalClaimPhotoService(JdbcTemplate jdbc, HistoricalAssetClaimMapper claims, StationMapper stations,
            CosUtil cos, HistoricalAssetClaimService claimService) {
        this.jdbc=jdbc; this.claims=claims; this.stations=stations; this.cos=cos; this.claimService=claimService;
    }
    private void token(String token) {
        if (token==null || !token.matches("[A-Za-z0-9._:-]{1,64}")) throw new BusinessException("申请凭据无效");
    }
    private Row find(long customer, String token) {
        List<Row> rows=jdbc.query("select station_id,object_name from historical_asset_claim_photo where customer_id=? and client_token=?",
            (rs,n)->new Row(rs.getLong(1),rs.getString(2)),customer,token);
        return rows.isEmpty()?null:rows.get(0);
    }
    private Photo view(Row row) {
        if (row==null) return new Photo(false,null);
        try { return new Photo(true,cos.generatePrivateUrl(row.objectName())); }
        catch (Exception e) { return new Photo(true,null); }
    }
    public Photo mine(Long station, String token) {
        long customer=AuthContext.requireCustomerId(); token(token);
        if (station==null || station<=0) throw new BusinessException("请选择水站");
        Row row=find(customer,token);
        if (row!=null && row.stationId()!=station) throw new BusinessException("照片不属于该水站");
        return view(row);
    }
    public Photo manager(long claimId) {
        HistoricalAssetClaim claim=claimService.managerDetail(claimId);
        Row row=find(claim.getCustomerId(),claim.getClientToken());
        if (row!=null && row.stationId()!=claim.getStationId()) throw new BusinessException("照片与申请不一致");
        return view(row);
    }
    @Transactional(rollbackFor=Exception.class)
    public Photo upload(Long station, String token, MultipartFile file) {
        long customer=AuthContext.requireCustomerId(); token(token);
        if (station==null || station<=0 || stations.getById(station)==null) throw new BusinessException("请选择水站");
        if (claims.lockCustomer(customer)==null) throw new BusinessException("客户不存在");
        Row previous=find(customer,token);
        if (previous!=null) {
            if (previous.stationId()!=station) throw new BusinessException("照片不属于该水站");
            return view(previous);
        }
        if (claims.byToken(customer,token)!=null) throw new BusinessException("申请已提交，不能添加照片");
        if (file==null || file.isEmpty() || file.getSize()>5*1024*1024) throw new BusinessException("请选择不超过5MB的照片");
        byte[] bytes;
        try { bytes=file.getBytes(); } catch (Exception e) { throw new BusinessException("照片读取失败，请重试"); }
        String extension=extension(bytes);
        if (!cos.isCredentialsConfigured()) throw new BusinessException("照片上传暂不可用，请稍后重试");
        String objectName;
        try { objectName=cos.uploadPrivate(bytes,"private/asset-claim",extension); }
        catch (Exception e) { throw new BusinessException("照片上传结果未确认，请重试核对"); }
        if (jdbc.update("insert into historical_asset_claim_photo(customer_id,station_id,client_token,object_name) values(?,?,?,?)",
            customer,station,token,objectName)!=1) throw new BusinessException("照片未保存，请重试核对");
        return view(new Row(station,objectName));
    }
    private String extension(byte[] b) {
        if (b.length>=3 && (b[0]&255)==255 && (b[1]&255)==216 && (b[2]&255)==255) return ".jpg";
        if (b.length>=8 && (b[0]&255)==137 && b[1]==80 && b[2]==78 && b[3]==71 && b[4]==13 && b[5]==10 && b[6]==26 && b[7]==10) return ".png";
        if (b.length>=12 && b[0]==82 && b[1]==73 && b[2]==70 && b[3]==70 && b[8]==87 && b[9]==69 && b[10]==66 && b[11]==80) return ".webp";
        throw new BusinessException("请选择 JPG、PNG 或 WebP 照片");
    }
}
