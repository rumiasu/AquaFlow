package com.example.aquaflow.util;

import com.example.aquaflow.constant.DeliveryLimitMode;
import com.example.aquaflow.constant.FloorFeeMode;
import com.example.aquaflow.entity.StationDeliveryConfig;
import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 配送计费：起送量 / 配送范围 / 运费 / 楼层费。<b>全系统唯一实现</b>，2026-09-17 新增（v35）。
 *
 * <p>规格见 {@code docs/design/17}。之所以必须只有一个实现：本仓出过"计价双轨"事故 ——
 * 结算页报价与下单落单各算各的，客户看到的价格与最终订单金额不一致（`PriceUtil` 文件头有记录）。
 * 所以 {@code PaymentServiceImpl.quote} 与 {@code OrderServiceImpl.createOrder}
 * <b>必须调本类的同一个 {@link #compute}</b>，禁止任何一侧内联算费用。</p>
 *
 * <p><b>本类只算钱和出提示，不做任何 IO</b>：配置由调用方查好传进来，距离由
 * {@link GeoUtil} 算好传进来。这样它可以用纯单元测试覆盖，不必起 Spring 容器。</p>
 *
 * <p>三条硬约束：</p>
 * <ol>
 *   <li><b>拿不准就不收</b>：距离算不出来（站点没坐标 / 地址没定位）、楼层或电梯未填，
 *       一律<b>不收该项费用</b>并给提示 —— 宁可少收，不可乱收。</li>
 *   <li><b>费用只报不落库</b>：本类返回的是"该收多少"，落库由调用方写进
 *       {@code orders.delivery_fee} / {@code orders.floor_fee}，
 *       <b>绝不并入 {@code water_amount} 或 {@code deposit_amount}</b>
 *       （后者是可退押金，混入会导致取消订单多退钱）。</li>
 *   <li>默认配置（{@link StationDeliveryConfig#defaults}）下所有费用为 0、不拦单 ——
 *       存量水站没有配置行，行为必须一个字不变。</li>
 * </ol>
 */
public final class DeliveryFeeUtil {

    private DeliveryFeeUtil() {}

    /**
     * 兼容旧调用：不带客户特权（等价于该客户没有任何免除类特权）。
     */
    public static FeeResult compute(StationDeliveryConfig config, int totalBuckets, BigDecimal waterAmount,
                                    Double distanceMeters, Integer floor, Integer hasElevator) {
        return compute(config, totalBuckets, waterAmount, distanceMeters, floor, hasElevator, false);
    }

    /**
     * 计算配送相关费用与提示。
     *
     * @param config          站级配置；传 {@code null} 视为"没配"（全 0、不拦单）
     * @param totalBuckets    本单桶装水桶数（用于起送量、免运费门槛、按桶楼层费）
     * @param waterAmount     水费金额（不含押金与运费；免运费/起送量的金额门槛都按这个口径比）
     * @param distanceMeters  站点到收货地址的直线距离（米）；{@code null} = 算不出来
     * @param floor           收货地址楼层；{@code null} = 未填
     * @param hasElevator     有无电梯：1 有 / 0 无 / {@code null} 未确认。<b>NULL 与 0 不可混同</b>
     * @param noMinOrder      该客户是否被站长授予「免起送门槛」特权（v40）。为 true 时
     *                        <b>整段跳过起送量判定</b>：不拦、不加收、也不提示 ——
     *                        对这个客户门槛本就不适用，提示反而像是在为难他
     */
    public static FeeResult compute(StationDeliveryConfig config, int totalBuckets, BigDecimal waterAmount,
                                    Double distanceMeters, Integer floor, Integer hasElevator,
                                    boolean noMinOrder) {
        StationDeliveryConfig cfg = config != null ? config : StationDeliveryConfig.defaults(null);
        BigDecimal water = waterAmount != null ? waterAmount : BigDecimal.ZERO;
        int buckets = Math.max(0, totalBuckets);

        FeeResult r = new FeeResult();
        BigDecimal deliveryFee = BigDecimal.ZERO;

        // ---- 1. 起送量（桶数与金额取或，满足其一即达门槛）----
        // 站长给该客户开了「免起送门槛」特权时整段跳过（v40）：不拦、不加收、不提示。
        // 这是「起送量默认 WARN 而不是 REJECT」的逐客户版 —— 老客户就买 1 桶，站长愿意送。
        if (!noMinOrder && belowMinOrder(cfg, buckets, water)) {
            String mode = DeliveryLimitMode.normalize(cfg.getMinOrderMode());
            String desc = minOrderDesc(cfg);
            if (DeliveryLimitMode.REJECT.equals(mode)) {
                r.block("未达起送量（" + desc + "），本水站暂不接单");
            } else if (DeliveryLimitMode.FEE.equals(mode)) {
                BigDecimal fee = nz(cfg.getMinOrderFee());
                deliveryFee = deliveryFee.add(fee);
                r.warn("未达起送量（" + desc + "），加收配送费 " + yuan(fee));
            } else {
                r.warn("未达起送量（" + desc + "），将按单配送");
            }
        }

        // ---- 2. 配送范围 ----
        Integer radius = cfg.getDeliveryRadiusM();
        if (radius != null && radius > 0) {
            if (distanceMeters == null) {
                // 拿不准就不判：把"没数据"当"超范围"会把客户全挡在门外
                r.warn("无法判断配送距离（水站未设置坐标或收货地址没有定位），本次不做范围校验");
            } else if (distanceMeters > radius) {
                String mode = DeliveryLimitMode.normalize(cfg.getOverRadiusMode());
                String km = km(distanceMeters);
                if (DeliveryLimitMode.REJECT.equals(mode)) {
                    r.block("超出配送范围（约 " + km + " 公里，本站上限 " + (radius / 1000) + " 公里）");
                } else if (DeliveryLimitMode.FEE.equals(mode)) {
                    BigDecimal fee = nz(cfg.getRemoteFee());
                    deliveryFee = deliveryFee.add(fee);
                    r.warn("超出配送范围（约 " + km + " 公里），加收远程费 " + yuan(fee));
                } else {
                    r.warn("超出配送范围（约 " + km + " 公里），将协调配送");
                }
            }
        }

        // ---- 3. 基础配送费（达到免运费门槛则免）----
        if (!isFreeDelivery(cfg, buckets, water)) {
            deliveryFee = deliveryFee.add(nz(cfg.getBaseDeliveryFee()));
        }

        // ---- 4. 楼层费（向客户收的那一笔；给配送员的楼层补贴是另一笔成本，在工资侧）----
        BigDecimal floorFee = computeFloorFee(cfg, buckets, floor, hasElevator, r);

        r.setDeliveryFee(deliveryFee);
        r.setFloorFee(floorFee);
        return r;
    }

    /**
     * 起送量判据：**桶数与金额取或**（满足其一即达门槛）。
     *
     * <p><b>[2026-09-19 产品裁定]「没用桶数时，核验金额即可」</b>：本单一件桶装水都没有
     * （{@code buckets <= 0}，即纯瓶装水/一次性桶/饮水机单）时，桶数条件**不适用** ——
     * 它是为循环桶设的，拿它去判这种单必然是"0 桶 &lt; N 桶 → 未达起送量"，
     * 于是整类非桶装订单会被无理由拦下（或按 FEE 模式白加钱）。此时**只看金额条件</b>：</p>
     * <ul>
     *   <li>配了金额门槛 → 按它判（这是唯一有意义的口径）；</li>
     *   <li>没配金额门槛 → 两条条件一条不适用、一条没配 → <b>该规则整体不生效（不拦）</b>。</li>
     * </ul>
     * <p>⚠️ 这是"条件不适用"，**不是**"默认放行"：有桶的单照旧按取或判（1 桶 &lt; 2 桶且水费也没到 → 未达）。
     * 站长侧有配套引导：上架非桶装商品时若金额门槛为空会被拒绝保存，并给出建议值
     * （见 {@code CatalogServiceImpl} 与 {@code ManagerDeliveryConfigController}）。</p>
     */
    private static boolean belowMinOrder(StationDeliveryConfig cfg, int buckets, BigDecimal water) {
        Integer mb = cfg.getMinOrderBuckets();
        BigDecimal ma = cfg.getMinOrderAmount();
        if (mb == null && ma == null) return false;          // 不限起送量
        if (buckets <= 0) {
            // 纯非桶装单：桶数条件不适用，只核金额；金额也没配 = 该规则不生效
            return ma != null && water.compareTo(ma) < 0;
        }
        if (mb != null && buckets >= mb) return false;       // 桶数达标
        if (ma != null && water.compareTo(ma) >= 0) return false;  // 金额达标
        return true;
    }

    private static String minOrderDesc(StationDeliveryConfig cfg) {
        List<String> parts = new ArrayList<>();
        if (cfg.getMinOrderBuckets() != null) parts.add(cfg.getMinOrderBuckets() + " 桶");
        if (cfg.getMinOrderAmount() != null) parts.add(yuan(cfg.getMinOrderAmount()));
        return String.join(" 或 ", parts);
    }

    /**
     * 免运费判据：桶数与金额取或（达到其一即免基础配送费）。
     *
     * <p>与 {@link #belowMinOrder} 同一条产品口径：本单没有桶装水时，桶数条件不适用，
     * 只看金额条件；金额也没配 → 这条规则不生效 = **照常收基础配送费**
     * （免运费是"给优惠"，条件不适用时不给优惠是保守且可解释的一侧，不要反成"默认免"）。</p>
     */
    private static boolean isFreeDelivery(StationDeliveryConfig cfg, int buckets, BigDecimal water) {
        Integer fb = cfg.getFreeDeliveryBuckets();
        BigDecimal fa = cfg.getFreeDeliveryAmount();
        if (fb == null && fa == null) return false;          // 没有免运费门槛 → 一直收基础配送费
        if (buckets <= 0) {
            return fa != null && water.compareTo(fa) >= 0;    // 纯非桶装单：只认金额
        }
        if (fb != null && buckets >= fb) return true;
        if (fa != null && water.compareTo(fa) >= 0) return true;
        return false;
    }

    private static BigDecimal computeFloorFee(StationDeliveryConfig cfg, int buckets, Integer floor,
                                              Integer hasElevator, FeeResult r) {
        BigDecimal per = nz(cfg.getFloorFeePerLevel());
        if (per.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO;  // 本站没配楼层费
        if (hasElevator != null && Integer.valueOf(1).equals(hasElevator)) return BigDecimal.ZERO;  // 有电梯不收

        if (floor == null || hasElevator == null) {
            // 三态里"未确认"是合法状态：不收、只提示。把 NULL 当无电梯会向客户乱收钱。
            r.warn("收货地址未填楼层或未确认有无电梯，本次不收楼层费");
            return BigDecimal.ZERO;
        }

        int freeLevel = cfg.getFloorFreeLevel() != null ? cfg.getFloorFreeLevel() : 1;
        if (floor <= freeLevel) return BigDecimal.ZERO;

        int levels = floor - freeLevel;
        BigDecimal fee;
        if (FloorFeeMode.PER_BUCKET.equals(FloorFeeMode.normalize(cfg.getFloorFeeMode()))) {
            // 按桶：桶数为 0 时（例如只买瓶装水）也算 1 份 —— 上楼这件事本身要出力
            fee = per.multiply(BigDecimal.valueOf((long) levels * Math.max(1, buckets)));
        } else {
            fee = per.multiply(BigDecimal.valueOf(levels));
        }
        r.warn("无电梯且位于 " + floor + " 层，加收楼层费 " + yuan(fee));
        return fee;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    /** 金额文案：去掉多余的 0（22.50 显示成 ¥22.5，22.00 显示成 ¥22） */
    private static String yuan(BigDecimal v) {
        return "¥" + nz(v).stripTrailingZeros().toPlainString();
    }

    private static String km(Double meters) {
        return String.format("%.1f", meters / 1000.0);
    }

    /**
     * 计费结果。
     *
     * <p>{@link #blocked} 为真时，{@code PaymentServiceImpl.quote} 把它作为
     * {@code blocked/blockReason} 下发给前端、{@code OrderServiceImpl.createOrder} 直接抛
     * {@code BusinessException} —— <b>两侧判据同源</b>，不会出现"报价说能下、下单被拒"。</p>
     */
    @Data
    public static class FeeResult {

        /** 配送费（基础运费 + 远程费 + 未达起送量的加收） */
        private BigDecimal deliveryFee = BigDecimal.ZERO;

        /** 楼层费（向客户收的那一笔） */
        private BigDecimal floorFee = BigDecimal.ZERO;

        /** 给客户看的提示（起送量、超范围、楼层未填…）；由后端下发，前端不得自造文案 */
        private List<String> warnings = new ArrayList<>();

        /** 是否硬拦（{@code REJECT} 模式命中门槛） */
        private boolean blocked;

        /** 硬拦原因（{@link #blocked} 为真时非空） */
        private String blockReason;

        /** 两项费用合计，便于调用方算总额 */
        public BigDecimal getFeeTotal() {
            return deliveryFee.add(floorFee);
        }

        void warn(String message) {
            this.warnings.add(message);
        }

        void block(String reason) {
            this.blocked = true;
            this.blockReason = reason;
        }
    }
}
