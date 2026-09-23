package com.example.aquaflow.service;

import com.example.aquaflow.mapper.OrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 超时未支付的**微信**单自动取消（2026-09-18 产品裁定）。
 *
 * <p>产品原话：「微信单不支付就在配送员那里当不存在，然后和主流平台那样，做时间限制，
 * 长时间不支付会被取消，只是微信单，货到付款是特殊。」</p>
 *
 * <p>它是"先付款后派单"的配套：未付微信单本来就不进站长/配送员视野，
 * 但会一直挂在"待支付"占着库存 —— 本类按主流平台的做法给个时限，到点走
 * {@link PaymentService#refundOrder}（<b>取消订单的唯一编排入口</b>：退水票 → 退流水 → 退押金 →
 * 清配送中桶 → <b>回补库存</b> → 置已取消），所以不会留下"库存扣了、单没了"的窟窿。</p>
 *
 * <p>⚠️ 三条边界，改判据前先读：</p>
 * <ol>
 *   <li><b>只碰微信单</b>（判据在 {@code OrderMapper.listTimedOutWechatOrders}，理由写在那里）；</li>
 *   <li><b>逐单独立事务</b>：一张单取消失败（并发被别人改了状态等）不能连累其余单，
 *       方法内 catch 并记 WARN —— 定时任务是无人值守路径，抛出去只会让整批停摆；</li>
 *   <li><b>阈值可配</b>（{@code app.order.wechat-pay-timeout-minutes}，≤0 表示关闭），
 *       行业常见 15–30 分钟，默认 30。</li>
 * </ol>
 */
@Service
@Slf4j
public class UnpaidWechatOrderSweeper {

    /** 单次最多处理多少单：防止积压时一次拉太多把库压住（剩余的下个周期继续）。 */
    private static final int BATCH_LIMIT = 200;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private PaymentService paymentService;

    /** 超时阈值（分钟）；≤0 = 关闭自动取消（例如商户想全部人工处理）。 */
    @Value("${app.order.wechat-pay-timeout-minutes:30}")
    private int timeoutMinutes;

    /** 每 5 分钟扫一次：比阈值小一个量级，保证"到点后最多 5 分钟内被取消"。 */
    @Scheduled(cron = "0 */5 * * * ?")
    public void sweep() {
        sweepOnce();
    }

    /**
     * 跑一轮（定时任务与用例共用同一入口，避免"测的不是跑的那份"）。
     *
     * @return 实际取消的单数
     */
    public int sweepOnce() {
        if (timeoutMinutes <= 0) {
            return 0;
        }
        List<Long> ids = orderMapper.listTimedOutWechatOrders(timeoutMinutes, BATCH_LIMIT);
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        int cancelled = 0;
        for (Long id : ids) {
            try {
                paymentService.refundOrder(id, "微信支付超时未到账，系统自动取消（" + timeoutMinutes + " 分钟）");
                cancelled++;
            } catch (RuntimeException e) {
                // 单张单失败不能连累其余（并发改状态、已被人取消、渠道未接入的历史单…）
                log.warn("[超时取消] 订单 {} 自动取消失败，跳过：{}", id, e.getMessage());
            }
        }
        if (cancelled > 0) {
            log.info("[超时取消] 微信未付单超时（>{} 分钟）自动取消 {} 张", timeoutMinutes, cancelled);
        }
        return cancelled;
    }
}
