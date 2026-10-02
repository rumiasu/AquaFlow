package com.example.aquaflow.util;

import com.example.aquaflow.entity.PaymentRecord;
import com.example.aquaflow.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** 购票意图的永久快照；价格变化不改变客户端原请求。 */
public final class TicketPurchaseIntent {
    private TicketPurchaseIntent() {}

    public static String digest(Long customer, Long station, Long product, Integer qty,
                                Integer method, Long pack, Integer unified) {
        String canonical = "ticket-v1|" + customer + "|" + station + "|" + product + "|" + qty
                + "|" + method + "|" + pack + "|" + unified;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** 2026-10-02 F-75：原实现同键就回原款，改数量也显示成功；必须先核内容再重放。 */
    public static void requireSame(PaymentRecord record, Long customer, Long station, Long product,
                                   Integer qty, Integer method, Long pack, Integer unified) {
        boolean sameFields = record.getOrderId() == null && record.getTicketQty() != null
                && Objects.equals(customer, record.getCustomerId())
                && Objects.equals(station, record.getStationId())
                && Objects.equals(product, record.getTicketWaterTypeId())
                && Objects.equals(qty, record.getTicketQty())
                && Objects.equals(method, record.getPaymentMethod())
                && Objects.equals(pack, record.getTicketPackageId());
        String saved = record.getPurchaseRequestDigest();
        // 旧记录不能拿现价重构摘要。只兼容可明确识别的原定价方式，未知备注须查原款核实。
        boolean sameIntent = saved != null
                ? saved.equals(digest(customer, station, product, qty, method, pack, unified))
                : (unified == null ? "线上购买水票" : "线上购买水票（站级统一折扣 " + unified + " 张档）")
                    .equals(record.getNote());
        if (!sameFields || !sameIntent) {
            throw new BusinessException("该购买编号已用于其他内容，请先查询原购买结果，不要修改原请求");
        }
    }
}
