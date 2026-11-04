package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.constant.DeliveryLimitMode;
import com.example.aquaflow.constant.FloorFeeMode;
import com.example.aquaflow.entity.StationDeliveryConfig;
import com.example.aquaflow.dto.DeliveryConfigSaveDTO;
import com.example.aquaflow.service.StationDeliveryConfigService;
import com.example.aquaflow.service.impl.DeliveryConfigGuideService;
import com.example.aquaflow.util.AuthContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 站级配送计费配置：起送量 / 配送范围 / 运费 / 楼层费，2026-09-17 新增（v35）。
 *
 * <p>规格见 {@code docs/design/17}。计费规则本身在 {@code util/DeliveryFeeUtil}，
 * 本类只负责读写配置。</p>
 *
 * <p><b>站点一律取自 {@code AuthContext.requireStationId()}</b>，请求体里的 stationId 被忽略 ——
 * 否则站长可以改别人站的配置，进而影响别人站客户的订单金额。</p>
 *
 * <p><b>没配过</b>时 GET 返回一份全 0 的默认配置（{@code configured=false}），
 * 而不是 404 或空体：前端拿到就能直接渲染表单，不需要为"还没配"单独写一套默认值 ——
 * 前端自带默认值正是历史上两端口径不一致的源头。</p>
 */
@RestController
@RequestMapping("/api/manager/delivery-config")
@RequireRole({"STATION_MANAGER"})
public class ManagerDeliveryConfigController {

    @Autowired
    private StationDeliveryConfigService stationDeliveryConfigService;

    @Autowired
    private DeliveryConfigGuideService deliveryConfigGuideService;

    /**
     * 读本站配送计费配置。
     *
     * <p>没配过时返回默认配置（全 0、WARN、不收费不拦单），并带 {@code configured=false}。</p>
     *
     * <p>[2026-09-19] 一并下发 {@code guidance}：本站是否已上架非桶装商品、哪些**金额类门槛**还缺、
     * 以及按本站最便宜的一桶水算出来的**建议值**。产品口径「没用桶数时，核验金额即可」——
     * 非桶装商品不占桶，只配桶数门槛等于那条规则对它不生效，所以要把站长引到金额字段上。</p>
     */
    @GetMapping
    public Result<Map<String, Object>> get() {
        Long stationId = AuthContext.requireStationId();
        StationDeliveryConfigService.Snapshot snapshot = stationDeliveryConfigService.get(stationId);
        StationDeliveryConfig cfg = snapshot.config();
        Map<String, Object> data = new HashMap<>();
        data.put("configured", snapshot.configured());
        data.put("config", cfg);
        data.put("snapshot", snapshot.snapshot());
        // 枚举文案由后端下发，前端禁止自建映射表
        data.put("minOrderModeText", DeliveryLimitMode.textOf(cfg.getMinOrderMode()));
        data.put("overRadiusModeText", DeliveryLimitMode.textOf(cfg.getOverRadiusMode()));
        data.put("floorFeeModeText", FloorFeeMode.textOf(cfg.getFloorFeeMode()));
        // 引导信息（缺哪些金额字段 + 建议值）。前端只展示、不自算倍数。
        data.put("guidance", deliveryConfigGuideService.guidance(stationId));
        return Result.success(data);
    }

    /**
     * 保存本站配送计费配置，expectedSnapshot 必须来自最近加载的表单；过期快照拒绝覆盖。
     *
     * <p>两类校验刻意做得宽松：</p>
     * <ul>
     *   <li><b>模式值归一化</b>（{@link DeliveryLimitMode#normalize}）：非法值一律回落到 WARN。
     *       绝不能因为配置写错就让客户下不了单。</li>
     *   <li><b>负值一律归零</b>：负数费用会让 {@code totalAmount} 变小，属于"能少收钱"的输入，
     *       必须挡住。</li>
     * </ul>
     */
    @PutMapping
    public Result<Map<String, Object>> save(@RequestBody DeliveryConfigSaveDTO body) {
        StationDeliveryConfigService.Snapshot saved =
                stationDeliveryConfigService.save(AuthContext.requireStationId(), body);
        Map<String, Object> data = new HashMap<>();
        data.put("configured", saved.configured());
        data.put("config", saved.config());
        data.put("snapshot", saved.snapshot());
        return Result.success(data);
    }
}
