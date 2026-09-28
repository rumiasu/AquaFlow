package com.example.aquaflow.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 站间结算台账实体，对应 {@code inter_station_settlement} 表（v67）。
 *
 * <p><b>它解决什么</b>：跨站外派单（归属 A / 履约 B）的三样东西落在两个站，而系统里
 * <b>没有任何一处把它们对上</b>（实测，正本 {@code docs/design/31} §6.1）——
 * 钱在 A（{@code payment_record.station_id}）、营收算 B
 * （{@code coalesce(settle_station_id, delivery_station_id, station_id)}）、票批次也在 A。
 * 本表把「谁该付给谁多少、什么时候算办完」记下来。</p>
 *
 * <p><b>它不是第二套账</b>：金额一律由 {@code InterStationSettlementService} 算好后写快照，
 * 本表<b>不自算任何金额、不参与客户侧对账</b>（客户侧等式 E1/E5/E8/E10 都不读它）。
 * "欠多少"平时是**实时算**的（见 Service 口径说明），本表只在两种情况下落行：
 * 站长改价（要留快照，否则下次实时算就改回去了）、站长登记结清（要留"办完了"这个事实）。</p>
 *
 * <p>⚠️ {@code feeAmount}（票覆盖的配送费/楼层费）<b>不计入 amount</b>：
 * 「这两笔要不要一起结给履约站」是 {@code docs/design/31} §8.4 第 3 问，产品尚未回答。
 * 单独记一列是为了拍板后<b>一个 UPDATE 就能启用</b>，不必回头补历史数据。
 * <b>不要</b>在没有拍板前把它加进 amount。</p>
 */
@Data
public class InterStationSettlement {

    private Long id;

    /** 订单ID（一单一笔，唯一键 {@code uk_inter_settle_order}） */
    private Long orderId;

    /** 付款方 = 归属站（票钱/微信款收在它手上） */
    private Long fromStationId;

    /** 收款方 = 结算站 = {@code coalesce(settle_station_id, delivery_station_id, station_id)}（谁送谁收） */
    private Long toStationId;

    /** 本单应付金额（一律正数；方向由 from→to 表达） */
    private BigDecimal amount;

    /** 计价依据，取值见 {@link com.example.aquaflow.constant.SettleBasis} */
    private Integer basis;

    /** 本单用票张数（仅 basis = 2/3） */
    private Integer ticketQty;

    /** 采用的单价快照（仅 basis = 2/3） */
    private BigDecimal unitPrice;

    /** 票覆盖的配送费 + 楼层费（仅 basis = 2/3）；⚠️ 不计入 amount，见类注释 */
    private BigDecimal feeAmount;

    /** 状态，取值见 {@link com.example.aquaflow.constant.SettleStatus} */
    private Integer status;

    /** 计价快照时间 */
    private LocalDateTime snapshotTime;

    /** 登记结清的时间 = 「什么时候算办完」 */
    private LocalDateTime settledTime;

    /** 登记结清的人（staff.id） */
    private Long settledBy;

    /** 结清凭据说明（转账流水号 / 经手人） */
    private String settleNote;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    // ===== 展示字段（非数据库列，由读接口填充）=====

    /** 付款方站名（列表里要显示"谁欠我/我欠谁"）。 */
    private String fromStationName;

    /** 收款方站名。 */
    private String toStationName;
}
