package com.example.aquaflow.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 站级「企业身份提示阈值」（v51）：一站一行，两条**只算水**的口径。
 *
 * <p>产品口径：「企业的只看水，押金不算，水超过 30 桶就可以吧。也可以由水站设置」+
 * 「桶数或金额，站长也可以自行设置范围，可以任选其一也可都选」。</p>
 *
 * <p>⚠️ <b>「没有行」与「有行但两项都为空」不是一回事</b>（{@link #defaults(Long)} 只负责前者）：</p>
 * <ul>
 *   <li><b>没有行</b> = 这个站还没配过 → 用平台默认（{@code app.enterprise.large-order-barrels}，默认 30 桶）；</li>
 *   <li><b>有行且两项都为 NULL</b> = 站长明确表示<b>本站不提示</b> → 一个人都不提示。</li>
 * </ul>
 * <p>两者合并会让"我想安静"变成"回到平台默认"，正是最难查的一类偏差。</p>
 *
 * <p>单条口径的含义：{@code >=} 阈值即提示 —— 文案写「达到 N 桶就提示」。
 * 想要严格"超过"（31 桶起）就把阈值填 31，不要在代码里加一层 +1。</p>
 */
@Data
public class StationEnterpriseConfig {

    private Long stationId;

    /** 本单桶装水（product.category=1）数量合计达到它即提示；NULL = 该项不启用。 */
    private Integer barrelThreshold;

    /** 本单水费（不含押金/配送费/楼层费）达到它即提示；NULL = 该项不启用。 */
    private BigDecimal waterAmountThreshold;

    /** 最后修改人（站长 staff.id），留痕用。 */
    private Long operatorId;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /**
     * 「这个站还没配过」时的占位配置：**两项都为 NULL**，由调用方再套上平台默认值。
     *
     * <p>注意它与"配了一行、两项也留空"的字段形状相同 —— 所以判定必须看
     * {@code mapper.getByStationId() == null}，**不能**拿这份占位去反推"站长关掉了"。</p>
     */
    public static StationEnterpriseConfig defaults(Long stationId) {
        StationEnterpriseConfig c = new StationEnterpriseConfig();
        c.setStationId(stationId);
        return c;
    }
}
