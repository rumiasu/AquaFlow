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

    private ExceptionCategory() {
    }
}
