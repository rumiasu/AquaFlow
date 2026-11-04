package com.example.aquaflow.service;

import com.example.aquaflow.constant.DeliveryLimitMode;
import com.example.aquaflow.constant.FloorFeeMode;
import com.example.aquaflow.dto.DeliveryConfigSaveDTO;
import com.example.aquaflow.entity.StationDeliveryConfig;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.StationDeliveryConfigMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 配送配置编辑的快照比较与写入必须处于同一个数据库事务。 */
@Service
public class StationDeliveryConfigService {
    private final StationDeliveryConfigMapper mapper;

    public StationDeliveryConfigService(StationDeliveryConfigMapper mapper) {
        this.mapper = mapper;
    }

    /** 配置与快照从同一次读取生成，未配置也具有独立快照。 */
    public Snapshot get(Long stationId) {
        return view(stationId, mapper.getByStationId(stationId));
    }

    /**
     * [2026-11-04] 旧 upsert 会让停在默认表单的会话覆盖其他会话的新配置。
     * 先锁始终存在的水站行，再当前读比较快照；只锁配置行挡不住最初无行的并发创建。
     */
    @Transactional
    public Snapshot save(Long stationId, DeliveryConfigSaveDTO body) {
        if (body.getExpectedSnapshot() == null || body.getExpectedSnapshot().isBlank()) {
            // 旧员工端不会发送快照；重复加载页面仍无法恢复，须明确引导更新小程序。
            throw new BusinessException("请更新员工端小程序至最新版本后再保存配送计费规则");
        }
        if (mapper.lockStation(stationId) == null) {
            throw new BusinessException("水站不存在");
        }
        StationDeliveryConfig current = mapper.getByStationIdForUpdate(stationId);
        if (!snapshot(stationId, current).equals(body.getExpectedSnapshot())) {
            throw new BusinessException("配送配置已被其他会话修改，请重新加载后再保存");
        }
        StationDeliveryConfig cfg = normalize(stationId, body);
        mapper.upsert(cfg);
        // MySQL 会规范金额精度；返回落库后的快照，而不是请求体的表示形式。
        // upsert 的 0 行是合法的同值保存；但保存后仍无行不能返回成功。
        StationDeliveryConfig saved = mapper.getByStationIdForUpdate(stationId);
        if (saved == null) throw new BusinessException("配送配置保存失败，请重新加载后重试");
        return view(stationId, saved);
    }

    private Snapshot view(Long stationId, StationDeliveryConfig config) {
        return new Snapshot(config != null,
                config == null ? StationDeliveryConfig.defaults(stationId) : config,
                snapshot(stationId, config));
    }

    /** 业务字段的内容快照；不依赖只有秒精度的 update_time，金额 0 与 0.00 等价。 */
    static String snapshot(Long stationId, StationDeliveryConfig config) {
        StringBuilder value = new StringBuilder("delivery-config-v1|");
        append(value, stationId);
        append(value, config != null);
        if (config != null) {
            append(value, config.getMinOrderBuckets());
            append(value, config.getMinOrderAmount());
            append(value, config.getMinOrderMode());
            append(value, config.getMinOrderFee());
            append(value, config.getDeliveryRadiusM());
            append(value, config.getOverRadiusMode());
            append(value, config.getRemoteFee());
            append(value, config.getBaseDeliveryFee());
            append(value, config.getFreeDeliveryBuckets());
            append(value, config.getFreeDeliveryAmount());
            append(value, config.getFloorFreeLevel());
            append(value, config.getFloorFeePerLevel());
            append(value, config.getFloorFeeMode());
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static void append(StringBuilder target, Object value) {
        if (value == null) {
            target.append("null;");
        } else {
            String text = value instanceof BigDecimal amount
                    ? amount.stripTrailingZeros().toPlainString() : value.toString();
            target.append(text.length()).append(':').append(text).append(';');
        }
    }

    private StationDeliveryConfig normalize(Long stationId, StationDeliveryConfig body) {
        StationDeliveryConfig cfg = new StationDeliveryConfig();
        cfg.setStationId(stationId);
        cfg.setMinOrderBuckets(nonNegative(body.getMinOrderBuckets()));
        cfg.setMinOrderAmount(nonNegative(body.getMinOrderAmount()));
        cfg.setMinOrderMode(DeliveryLimitMode.normalize(body.getMinOrderMode()));
        cfg.setMinOrderFee(nonNegative(body.getMinOrderFee()));
        cfg.setDeliveryRadiusM(nonNegative(body.getDeliveryRadiusM()));
        cfg.setOverRadiusMode(DeliveryLimitMode.normalize(body.getOverRadiusMode()));
        cfg.setRemoteFee(nonNegative(body.getRemoteFee()));
        cfg.setBaseDeliveryFee(nonNegative(body.getBaseDeliveryFee()));
        cfg.setFreeDeliveryBuckets(nonNegative(body.getFreeDeliveryBuckets()));
        cfg.setFreeDeliveryAmount(nonNegative(body.getFreeDeliveryAmount()));
        Integer freeLevel = nonNegative(body.getFloorFreeLevel());
        cfg.setFloorFreeLevel(freeLevel != null ? freeLevel : 1);
        cfg.setFloorFeePerLevel(nonNegative(body.getFloorFeePerLevel()));
        cfg.setFloorFeeMode(FloorFeeMode.normalize(body.getFloorFeeMode()));
        return cfg;
    }

    private static Integer nonNegative(Integer value) {
        return value == null ? null : Math.max(0, value);
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        return value == null ? null : value.max(BigDecimal.ZERO);
    }

    /** HTTP 读取和成功保存都返回相同的快照契约。 */
    public record Snapshot(boolean configured, StationDeliveryConfig config, String snapshot) { }
}
