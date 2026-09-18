package com.example.aquaflow.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 客户×水站权限配置
 * V1 仅用于线下支付授权
 */
@Data
public class CustomerStationConfig {

    /** 主键 */
    private Long id;

    /** 客户ID */
    private Long customerId;

    /** 水站ID */
    private Long stationId;

    /** 是否允许线下支付 */
    private Integer offlinePaymentEnabled;

    /**
     * 货到付款**单笔上限**；{@code null} = 不限（v48）。
     *
     * <p>产品口径：「特殊允许的客户可以大额」—— 就是把这一列设成 NULL 或一个更大的数。
     * 判定只在 {@code PaymentServiceImpl.offlinePaymentBlockReason} 一处。</p>
     */
    private java.math.BigDecimal offlinePaymentSingleLimit;

    /**
     * 是否允许该客户**首单**货到付款（0 = 不允许，默认，v48）。
     *
     * <p>产品口径：「对首单设限」—— 新客户第一单先走水票/在线付，与"首单收满押金"同一个逻辑。
     * ⚠️ 存量客户默认 0：升级后站长要在界面上给"已合作但系统里还没订单的老客户"逐个放开
     * （产品要的效果，不是数据问题，见 v48 迁移头）。</p>
     */
    private Integer offlinePaymentAllowFirstOrder;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}