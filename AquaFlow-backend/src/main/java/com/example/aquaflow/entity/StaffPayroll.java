package com.example.aquaflow.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 配送员工资结算单，对应 {@code staff_payroll} 表（v37）。规格见 {@code docs/design/18}。
 *
 * <p><b>「算出来」与「发出去」必须分开</b>（状态：1 草稿 → 2 已确认 → 3 已发放）：</p>
 * <ul>
 *   <li>只有一个状态时，站长改一条明细就会<b>悄悄改掉已经发过的钱</b>，下个月对不上时无从追溯；</li>
 *   <li>这与 {@code station_adjustment} 的"确认后不原地改、要改走反向单/下期调整"是同一个设计语言。</li>
 * </ul>
 *
 * <p><b>发钱是线下动作</b>（微信转账/现金），系统只做两件事：算清楚、留痕迹
 * （{@link #paidTime} + {@link #operatorId}）。不做打款/提现/钱包 —— 那要支付牌照与资金存管，
 * 而本项目连微信支付渠道都还没接。</p>
 */
@Data
public class StaffPayroll {

    private Long id;

    /** 单据号 PRyyyymmdd-000001 */
    private String payrollNo;

    private Long stationId;

    private Long staffId;

    /** 结算期间起（含） */
    private java.time.LocalDate periodStart;

    /** 结算期间止（含） */
    private java.time.LocalDate periodEnd;

    /** 本期合计；必须等于本期明细之和（对账 E-PAY） */
    private BigDecimal totalAmount;

    /** 1 草稿 / 2 已确认 / 3 已发放，见 {@link Status} */
    private Integer status;

    /** 发钱时间（线下转账/现金，系统只留痕） */
    private LocalDateTime paidTime;

    private Long operatorId;

    private String note;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    public String getStatusText() {
        return Status.textOf(status);
    }

    /** 是否已锁定（已确认或已发放的结算单，其明细不允许再改动） */
    public boolean isLocked() {
        return status != null && status >= Status.CONFIRMED;
    }

    /** 结算单状态。数字只前进，不倒滚（与订单状态同一条通用规则）。 */
    public static final class Status {
        /** 草稿：明细还可增删改 */
        public static final int DRAFT = 1;
        /** 已确认：金额锁定，等待发钱 */
        public static final int CONFIRMED = 2;
        /** 已发放：钱已经给了（线下），只留痕 */
        public static final int PAID = 3;

        public static String textOf(Integer s) {
            if (s == null) return "草稿";
            switch (s) {
                case CONFIRMED: return "已确认";
                case PAID:      return "已发放";
                default:        return "草稿";
            }
        }

        private Status() {}
    }
}
