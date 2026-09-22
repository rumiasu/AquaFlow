package com.example.aquaflow.service;

import com.example.aquaflow.dto.OrderCreateDTO;
import com.example.aquaflow.dto.OrderCreateResult;
import com.example.aquaflow.entity.Orders;

import java.util.List;
import java.util.Map;

/**
 * 订单基础服务：下单、列表 / 详情、客户取消。
 *
 * <p><b>边界（重要）</b>：订单的「状态 / 支付状态 / 配送员 / 履约站」这四类字段的写入口是
 * {@code OrderWorkflowService}，<b>不是本接口</b>。那四类字段一旦被别处直接改写，
 * 就会绕开状态机、CAS 与退款/押金副作用 —— 这正是历史上"同一张订单经不同 HTTP 路径被写坏"的根因。</p>
 *
 * <p>{@code cancelByCustomer} 按状态分流（2026-09-14 起；已送达的排除见 2026-09-21）：
 * <b>待配送(1)</b> 当场取消并走完整退款链；<b>配送中(2)</b> 转成<b>取消申请</b>交站长审批，
 * 不再允许客户自助取消；<b>已送达(3) 及以上一律直接拒</b>（货已交付，异常走「配送异常」）。</p>
 */
public interface OrderService {

    OrderCreateResult createOrder(OrderCreateDTO dto);

    void save(Orders orders);

    List<Orders> list(Long stationId, Long customerId, Integer status, String createTimeStart, String createTimeEnd,
                      Integer limit, Integer offset);

    Orders getById(Long id);

    /** 支付状态 CAS 转换：仅当当前支付态等于 expected 时才更新为 target（返回 affected=0 视为并发冲突抛异常）。 */
    void transitionPaymentStatus(Long id, Integer expected, Integer target);

    /**
     * 客户主动取消订单。
     *
     * <p>分流（见 {@code OrderWorkflowService.requestCancelByCustomer}）：</p>
     * <ul>
     *   <li><b>待配送(1)</b>：尚未接单，直接取消并完整回滚下单副作用
     *       （回补库存、退还预收桶押金、清理配送中桶记录）。</li>
     *   <li><b>配送中(2)</b>：已被配送员接单，客户不能再自助取消，
     *       只提交<b>取消申请</b>（{@code order_transfer} kind=CUSTOMER），由站长审批；
     *       站长同意后才走同一条退款链。<b>也就是说：配送中的单实际"取消"这个动作只能由配送端做。</b></li>
     *   <li><b>已送达(3) 及以上</b>：连申请都提交不了（{@code OrderStatus.isCancellable} 直接拒，
     *       文案指向「配送异常」）—— 货已交付，不该靠"取消"抹掉。</li>
     * </ul>
     *
     * @param orderId    订单ID
     * @param customerId 当前登录客户ID（用于校验归属，防止取消他人订单）
     */
    void cancelByCustomer(Long orderId, Long customerId);
}
