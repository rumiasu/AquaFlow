package com.example.aquaflow.constant;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 桶异常单的「异常类别」—— <b>全系统唯一正本</b>（2026-09-23 建）。
 *
 * <h3>为什么要有这个类</h3>
 * <p>在此之前这套取值是<b>散在 3 处 switch 里的字符串字面量</b>：
 * {@code entity/OrderBarrelException.getCategoryText()}、
 * {@code service/impl/NotificationServiceImpl}、以及 {@code sql/schema.sql} 的列注释。
 * 加一个类别要改三处、漏一处不会报错（只会显示成英文代号）—— 本仓的老形状。</p>
 *
 * <p>⚠️ 更要紧的是<b>入口没有白名单</b>：{@code OrderBarrelExceptionServiceImpl.recordException}
 * 直接 {@code setCategory(input.getCategory())}，而 {@code ManagerExceptionController.create}
 * 又把请求体原样透传。2026-09-22 的业务实测就成功写进了一个<b>不在集合里</b>的值
 * （{@code REFUSAL}）—— 与 AGENTS §6「请求体的枚举入参必须白名单校验」是同一个坑
 * （同类事故：{@code OrderCreateDTO.paymentMethod} 传 99 也建单成功）。</p>
 *
 * <p>取值与 {@code sql/schema.sql} 中 {@code order_barrel_exception.category} 的列注释逐字一致，
 * 改这里就要改那里（两者是同一份事实）。</p>
 */
public final class ExceptionCategory {

    /** 少回桶：送出去 N 个空桶，收回来不到 N 个（{@code recordReturn} 自动生成）。 */
    public static final String RETURN_SHORT = "RETURN_SHORT";
    /** 多回桶：收回来比送出去的多。 */
    public static final String RETURN_OVER = "RETURN_OVER";
    /** 拒收：客户/站点当场拒收。 */
    public static final String RETURN_REFUSE = "RETURN_REFUSE";
    /** 损坏：桶损坏，无法继续周转。 */
    public static final String RETURN_DAMAGE = "RETURN_DAMAGE";
    /** 站内缺水：站点自身库存/水源问题。 */
    public static final String STATION_SHORTAGE = "STATION_SHORTAGE";
    /** 客户拒收（拒付）：收了货但拒不付款 —— 拒付结案（核销认损）针对的就是它。 */
    public static final String CUSTOMER_REFUSE = "CUSTOMER_REFUSE";
    /** 其他：兜底，必须由站长填写备注说明。 */
    public static final String OTHER = "OTHER";

    /** 全部合法取值（顺序即前端下拉的展示顺序）。 */
    public static final Set<String> ALL = java.util.Collections.unmodifiableSet(
            new LinkedHashSet<>(java.util.Arrays.asList(
                    RETURN_SHORT, RETURN_OVER, RETURN_REFUSE, RETURN_DAMAGE,
                    STATION_SHORTAGE, CUSTOMER_REFUSE, OTHER)));

    /** 中文文案（全系统唯一来源，前端禁止自带映射表）。未知值<b>原样返回</b>，不伪装成某个已知类别。 */
    public static String textOf(String category) {
        if (category == null || category.isBlank()) return "其他";
        switch (category) {
            case RETURN_SHORT:     return "少回桶";
            case RETURN_OVER:      return "多回桶";
            case RETURN_REFUSE:    return "拒收";
            case RETURN_DAMAGE:    return "损坏";
            case STATION_SHORTAGE: return "站内缺水";
            case CUSTOMER_REFUSE:  return "客户拒收";
            case OTHER:            return "其他";
            // ⚠️ 未知值**不要**兜底成"其他"：那会把脏数据洗成看起来正常的值，
            // 让"写进来一个不存在的类别"这件事永远查不出来（同 PayMethod.textOf 的 default 教训）。
            default:               return category;
        }
    }

    /** 是否是合法类别 —— 入口白名单校验用。 */
    public static boolean isValid(String category) {
        return category != null && ALL.contains(category);
    }

    /**
     * 配送员「上报配送问题」的**原因 → 异常类别**映射（2026-09-27 立）—— 全系统唯一正本。
     *
     * <h3>为什么需要它</h3>
     * <p>在此之前配送员上报现场问题（{@code POST /api/delivery/orders/report/{id}}）**不生成异常单**，
     * 只往 {@code orders.special_note} 写一行备注 —— 于是站长端「异常订单」里永远只有
     * 「回桶数量对不上」这一种（{@code recordReturn} 自动建的单，类别写死少回桶/多回桶），
     * 而「拒收 / 损坏 / 客户拒付」这些真正需要站长处置的现场问题**只能靠站长事后手工补录**
     * （{@code POST /api/manager/exceptions}）。配送员能识别问题，系统却不收"问题是什么"。</p>
     *
     * <h3>设计取舍</h3>
     * <ul>
     *   <li><b>原因 key 由服务端定，不由客户端传类别</b>：给配送员一个"从 7 个类别里选"的下拉，
     *       他既分不清 {@code RETURN_REFUSE}（当场拒收）与 {@code CUSTOMER_REFUSE}（收了但不给钱），
     *       也不该关心；这里把"现场他看到的那句话"直接落成类别，映射只有这一份。</li>
     *   <li><b>不涉桶账</b>：这类异常单的 {@code discrepancy} 恒为 0，所以
     *       {@code OwedBarrel} 那族「取 {@code discrepancy > 0}」的下钻不会把它算进欠桶
     *       —— 这是它能安全复用同一张表的前提。</li>
     *   <li>话术与前端 {@code utils/delivery-problem.js} 的选择列表**一一对应**，
     *       加一项要同时改两处（那是给配送员看的，这里是给库看的）。</li>
     * </ul>
     */
    public static final class ReportReason {
        public static final String CUSTOMER_UNREACHABLE = "customer_unreachable";
        public static final String ADDRESS_NOT_FOUND = "address_not_found";
        public static final String CUSTOMER_REFUSE = "customer_refuse";
        public static final String BARREL_DAMAGED = "barrel_damaged";
        public static final String OTHER = "other";

        /** 现场原因 key → 异常类别。未知 key 一律 {@code null}（调用方据此拒绝，不猜）。 */
        public static String categoryOf(String key) {
            if (key == null) return null;
            switch (key) {
                case CUSTOMER_UNREACHABLE: return ExceptionCategory.OTHER;
                case ADDRESS_NOT_FOUND:    return ExceptionCategory.OTHER;
                case CUSTOMER_REFUSE:      return ExceptionCategory.RETURN_REFUSE;
                case BARREL_DAMAGED:       return ExceptionCategory.RETURN_DAMAGE;
                case OTHER:                return ExceptionCategory.OTHER;
                default:                   return null;
            }
        }

        /** 现场原因 key → 中文（写进异常单备注。与前端选择列表逐字一致）。 */
        public static String textOf(String key) {
            if (key == null) return "其他";
            switch (key) {
                case CUSTOMER_UNREACHABLE: return "客户不接电话";
                case ADDRESS_NOT_FOUND:    return "地址找不到";
                case CUSTOMER_REFUSE:      return "客户拒收";
                case BARREL_DAMAGED:       return "水桶破损";
                case OTHER:                return "其他";
                default:                   return "其他";
            }
        }

        public static boolean isValid(String key) {
            return categoryOf(key) != null;
        }

        private ReportReason() {
        }
    }

    private ExceptionCategory() {
    }
}
