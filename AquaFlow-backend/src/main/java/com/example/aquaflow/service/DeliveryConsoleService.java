package com.example.aquaflow.service;

import com.example.aquaflow.entity.Orders;

import java.util.List;
import java.util.Map;

/**
 * 员工端配送控制台的**只读投影**（F-18：由 {@code DeliveryController} 下沉）。
 *
 * <p><b>为什么要有它</b>：拆分前的 {@code DeliveryController} 一个类 1236 行 / 48 个端点，
 * 同时承担「配送员自助 / 站长控制台 / 跨站外派」三种读者的界面，还直接注入了 6 个 Mapper。
 * 拆成多个薄壳控制器之后，控制器只剩「认证 + DTO 校验 + 调服务 + 包 Result」，
 * 一切列表取数、字段拼装与跨租户可见面的裁剪都收在本服务里 —— 分层门禁
 * （{@code LayeringArchitectureTest}）的基线因此只减不增。</p>
 *
 * <p>⚠️ <b>本服务只读</b>：任何状态变更都必须走 {@link OrderWorkflowService}
 * （订单状态 / 支付状态 / 配送员 / 履约站的唯一写入口），不要在这里加写方法。</p>
 *
 * <p>⚠️ <b>时间源照搬原状</b>：本类的方法体是从 {@code DeliveryController} 原样搬出来的，
 * 其中「今日完成 / 今日统计 / 外派久未接单」三处仍直连 JVM 时钟 ——
 * F-16 的 {@code util/BusinessTime} 改造范围不含它们（那是口径改动，不属于"纯搬"）。
 * 要改请单独成批，并连带改这三处的用例。</p>
 */
public interface DeliveryConsoleService {

    /* ==================== 配送员自助面 ==================== */

    /** 派给我、还没接的单（待配送）。 */
    List<Orders> listAssignedToMe(Long staffId);

    /** 我配送中的单。 */
    List<Orders> listDelivering(Long staffId);

    /** 我今天完成的单。 */
    List<Orders> listCompletedToday(Long staffId);

    /** 我的回桶记录。 */
    List<Orders> listBarrelRecords(Long staffId);

    /** 我的历史完成单。 */
    List<Orders> listHistory(Long staffId);

    /** 转给我的（待我确认的）单。 */
    List<Orders> listIncomingTransfers(Long staffId);

    /** 配送员「今日」看板四个数（完成数 / 配送中数 / 回桶数 / 待收款数）。 */
    Map<String, Object> todayStats(Long staffId, Long stationId);

    /**
     * 订单详情：明细 + 回桶计划 + 备货情况 + 楼层 + 历史计数 + 转单状态 + 画像掩码。
     *
     * <p>⚠️ <b>不含鉴权</b>：调用方必须自己按登录态校验履约站归属与"是不是我名下的单"
     * （判据见各控制器的端点实现）—— 这里只负责组装，不回答"你能不能看"。</p>
     *
     * @return 订单不存在时返回 {@code null}（调用方据此回「订单不存在」）
     */
    Orders orderDetail(Long orderId);

    /** 原始订单（不组装、不加掩码）—— 需要先做归属判定再决定要不要组装的端点用得到。 */
    Orders findOrder(Long orderId);

    /* ==================== 站长控制台面 ==================== */

    /** 本站未分配配送员的待配送单。 */
    List<Orders> listPendingByStation(Long stationId);

    /** 站长待分配列表（含他站定向外派给本站的单，已过画像掩码 + 客户信用标记）。 */
    List<Orders> listStationPendingUnassigned(Long stationId);

    /** 站维度按状态取单（配送中 / 已完成 / 已送达未收款共用）。 */
    List<Orders> listStationByStatus(Long stationId, int status);

    /** 本站相关的转单列表（站长「审批」页与「转单」页共用）。 */
    List<Orders> listStationTransferred(Long stationId);

    /** 本站的退回站长申请列表。 */
    List<Orders> listStationReturn(Long stationId);

    /** 跨站履约单汇总（本站是履约站）：总数 / 金额合计 / 按状态分类计数 / 逐单明细（已掩码）。 */
    Map<String, Object> crossStationSummary(Long stationId);

    /** 站长「审批」页两个子页签：{@code customer} = 客户发起；{@code station} = 站内发起。 */
    Map<String, Object> pendingApprovals(Long stationId);

    /* ==================== 跨站外派面 ==================== */

    /**
     * 抢单池列表（跨租户可见面）。
     *
     * <p>⚠️ 只下发「钱货去向」文案与订单快照金额，<b>绝不重算金额</b>（那是计价双轨）、
     * <b>绝不下发客户画像</b>（池里的画像是归属站的）。见 {@code feeInfoOf}。</p>
     */
    List<Map<String, Object>> listPoolOrders(Long stationId);

    /** 外派追踪列表：本站外派出去的单 + 外派种类 + 目标站名 + 「久未接单」提示。 */
    List<Orders> listDispatchTracking(Long stationId);

    /** 原归属站：被指定水站退回、等待同意的订单列表。 */
    List<Orders> listDirectedReturns(Long stationId);

    /** 目标水站视角：被他站指定为履约站的订单列表（含金额去向信息 + 无条件画像掩码）。 */
    List<Orders> listDirectedIncoming(Long stationId);
}
