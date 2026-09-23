package com.example.aquaflow.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 水站实体类，对应数据库 station 表。
 * <p><b>V1 Binding 模型:</b> 站长关系<b>不在 station 表里存 manager 字段</b>，真正的站长关系由:
 * <pre>
 *   staff.role       = STATION_MANAGER
 *   staff.station_id = station.id
 * </pre>
 * 表达。
 */
@Data
public class Station {

    /** 水站ID，主键自增 */
    private Long id;

    /** 水站名称 */
    private String name;

    /** 水站电话 */
    private String phone;

    /** 水站地址 */
    private String address;

    /**
     * 纬度（2026-09-17 新增，见 {@code sql/migration_v34_delivery_fee_and_floors.sql}）。
     *
     * <p>配送范围要算「站点到客户」的距离，而原表只有 {@code address} 有坐标、站点自己没有。
     * <b>NULL = 未设置</b>：此时范围校验必须<b>跳过（放行）</b>而不是拒单 —— 站长一建站就
     * 把所有客户挡在门外是最坏的失败方式。见 {@code docs/design/17} §4.2。</p>
     */
    private BigDecimal lat;

    /** 经度，语义同 {@link #lat} */
    private BigDecimal lng;

    /** 状态: 1 营业 2 停业（**硬状态**：停业会真的拒绝下单，且不在公开选站列表里） */
    private Integer status;

    /**
     * 营业状态（**软状态**，2026-09-17 新增）：见 {@link com.example.aquaflow.constant.StationOperatingStatus}。
     * <p>1 正常运营 / 2 休息中 / 3 配送延迟 / 4 暂停配送可预约 —— <b>一律不阻断下单</b>，
     * 只作为提示展示给顾客（商城/下单页横幅 + 下单响应 warnings）。默认 1。</p>
     */
    private Integer operatingStatus;

    /** 站长留言：配合营业状态的一句话说明（如"今天休息，明早 8 点正常送"），≤100 字 */
    private String statusNote;

    /** 营业状态最近一次修改时间（顾客提示里显示"刚刚更新"） */
    private LocalDateTime statusUpdateTime;

    /** 营业状态文案（后端唯一下发来源，前端禁止自带 1..4 映射表） */
    public String getOperatingStatusText() {
        return com.example.aquaflow.constant.StationOperatingStatus.textOf(operatingStatus);
    }

    /*
     * [2026-09-12 已移除] 原「水站线下支付总开关」字段曾位于此处，现已删除。
     * 货到付款的唯一控制点收敛为客户级授权 customer_station_config.offline_payment_enabled
     * （站长在「用户画像 → 权限设置 → 货到付款」逐个开通），见 PaymentServiceImpl#canUseOfflinePayment。
     * 该字段与配套接口/列均已停止读写，DROP 脚本见 sql/migration_v22_drop_station_offline_payment.sql。
     * 保留这段说明（用普通块注释而非 javadoc：它不对应任何成员），是为了防止有人照旧文档把它加回来。
     */

    /** 创建者站长ID */
    private Long creatorStaffId;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
