package com.example.aquaflow.service;

import com.example.aquaflow.entity.StationAdjustment;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 站长资产调整单服务。
 *
 * <p><b>业务定位</b>：系统上线前水站已有存量客户与资产（桶、押金、水票），这些资产在库中
 * 没有任何订单/流水支撑，需要一个正式的搬运通道。本服务就是那个通道——
 * 它不产生订单，只按类型走各自账本的唯一写入口，并留全审计痕迹。
 * 详见 {@code ManagerAdjustmentController} 类注释里的完整来龙去脉。</p>
 *
 * <p>权限边界：所有方法都只允许操作 {@code AuthContext} 中当前登录站长所属水站的客户资产，
 * 不信任任何请求参数里的 stationId（跨站一律拒绝）。</p>
 *
 * <p>设计依据：docs/design/10-站长资产调整单.md</p>
 */
public interface StationAdjustmentService {

    /**
     * 只读试算：返回调整前后的权益/欠桶/占用/押金/水票与预计金额，<b>不落库</b>。
     * <p>供小程序在下单前展示"将变成什么样"，前端只渲染本接口结果，不自行计算。</p>
     */
    Map<String, Object> preview(Long customerId, String adjustType, Long productId,
                               Integer qty, BigDecimal amount, BigDecimal unitPrice);

    /**
     * 创建调整单（status=PENDING）。
     * <p>{@code clientToken} 幂等：同一 token 重复提交返回原单，不新建。</p>
     */
    StationAdjustment create(Long customerId, String adjustType, Long productId,
                             Integer qty, BigDecimal amount, BigDecimal unitPrice,
                             String reason, String evidence, String clientToken);

    /** 执行调整单：CAS PENDING→EFFECTIVE，再按类型走对应唯一写入口。重复执行会被拒绝。 */
    void execute(Long id);

    /** 撤销：生成一张反向单并执行，原单置 REVERSED（不改历史数据）。 */
    StationAdjustment reverse(Long id, String reason, String clientToken);

    /** 分页列表（本站；customerId 非空时只看该客户） */
    Map<String, Object> list(Long customerId, int page, int size);

    /** 详情（含前后快照） */
    StationAdjustment getById(Long id);
}
