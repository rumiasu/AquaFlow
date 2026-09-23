package com.example.aquaflow.dto;

import lombok.Data;

import java.util.List;

/**
 * 下单结果。
 * <p>库存不足时先返回 {@code needConfirm=true + shortages} 让前端弹窗，
 * 客户确认后带 {@code confirmShortage=true} 重新提交才真正创建订单。</p>
 */
@Data
public class OrderCreateResult {

    /** 创建成功后的订单ID (needConfirm=true 时为 null) */
    private Long orderId;

    /** true=库存不足, 需要前端弹窗确认后重新提交 */
    private Boolean needConfirm;

    /** 库存不足的商品列表 (含剩余库存, 供前端展示) */
    private List<ShortageItem> shortages;

    /** 已确认缺货下单后的告警信息 (信息性, 非阻断) */
    private List<String> warnings;

    /** true=首次在该水站进行资产业务(水票/桶/押金)，前端需弹窗提示 */
    private Boolean firstStationAsset;

    @Data
    public static class ShortageItem {
        private Long productId;
        private String productName;
        private String brand;
        private String spec;
        /** 客户购买数量 */
        private Integer requested;
        /** 当前剩余库存 */
        private Integer stock;
        /** 提示语 */
        private String message;
    }

    public static OrderCreateResult needConfirm(List<ShortageItem> shortages) {
        OrderCreateResult r = new OrderCreateResult();
        r.setNeedConfirm(true);
        r.setShortages(shortages);
        return r;
    }

    public static OrderCreateResult success(Long orderId, List<String> warnings, Boolean firstStationAsset) {
        OrderCreateResult r = new OrderCreateResult();
        r.setOrderId(orderId);
        r.setNeedConfirm(false);
        r.setWarnings(warnings);
        r.setFirstStationAsset(firstStationAsset);
        return r;
    }
}
