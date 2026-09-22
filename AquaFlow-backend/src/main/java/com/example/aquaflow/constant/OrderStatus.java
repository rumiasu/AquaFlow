package com.example.aquaflow.constant;

/**
 * 订单状态（canonical 定义，全端统一）。
 * <p>1=待配送 2=配送中 3=已送达 4=已完成 5=已取消（连续编号）。
 * 历史版本曾用 1/3/4/5/6（含"组批"概念、状态 2 空缺），现重排为连续编号，
 * 以免前端/小程序映射混乱。</p>
 *
 * <p><b>流转：[2026-09-21 修订]</b> {@code 1→2/5，2→3/4/5，3→4}。
 * 已送达(3) <b>不再流向已取消(5)</b> —— 见 {@link #isCancellable}。</p>
 */
public class OrderStatus {

    /** 1 待配送：已下单，待分配 */
    public static final int PENDING = 1;
    /** 2 配送中：已接单出库 */
    public static final int DELIVERING = 2;
    /** 3 已送达：货已交，待收款确认 */
    public static final int DELIVERED = 3;
    /** 4 已完成：订单闭环 */
    public static final int COMPLETED = 4;
    /** 5 已取消：仅 待配送(1) / 配送中(2) 可进入（已送达不可，2026-09-21） */
    public static final int CANCELLED = 5;

    /**
     * 订单状态中文文案（全系统唯一文案来源）。
     * <p>历史上用户端/配送端各自维护一套 status -> 文案映射，新增状态或调整叫法时两端容易漂移
     * （例如旧前端长期停留在错误的 2/7 映射）。前端一律渲染后端下发的 statusText，禁止自行映射。</p>
     */
    public static String textOf(Integer status) {
        if (status == null) return "未知";
        switch (status) {
            case PENDING:    return "待配送";
            case DELIVERING: return "配送中";
            case DELIVERED:  return "已送达";
            case COMPLETED:  return "已完成";
            case CANCELLED:  return "已取消";
            default:         return "未知";
        }
    }

    /**
     * 是否处于「这笔交易还可能不成立」的状态 —— 它同时是**三件事的闸门**：取消订单、拒单 / 解决、退回重派（入抢单池）。
     *
     * <p><b>[2026-09-21 产品裁定] 已送达(3) 不再是可取消状态。</b>理由：已送达不只是系统里的一个标记，
     * 现实里<b>货已经交付完成</b> —— 交付完成的事不该靠"取消"抹掉，那只会把账搞乱：</p>
     * <ul>
     *   <li>权益批次在<b>送达那一刻</b>就建好了（{@code BarrelLedgerService.applyDelivery}），
     *       而取消链 {@code PaymentServiceImpl.refundOrder} 的桶账处理整块罩在
     *       {@code orderStatus < 已送达(3)} 之下，<b>够不着它</b>；</li>
     *   <li>于是"取消一张已送达的单"会留下<b>没被撤销的桶权益</b>：客户手上是可退押金的权益，
     *       而我们从未收到过那笔押金，订单却已取消、这笔钱再也不会收。</li>
     * </ul>
     * <p>客户拒付之类的异常改走「配送异常」流程（`order_barrel_exception`），不要在这里揉成一团。</p>
     *
     * <p>⚠️ <b>本判据不是"能不能退钱"</b>：已完成(4) 的订单要么走单笔退款
     * （{@code PaymentService.refundPayment}），要么走人工调整 —— 都不经过本方法。</p>
     */
    public static boolean isCancellable(Integer status) {
        return status != null && (status == PENDING || status == DELIVERING);
    }

    /**
     * 「不可取消」时给用户看的统一文案 —— <b>全系统唯一来源</b>，别在调用点各写一套
     * （调用点有 6 处，文案分叉会让同一种拒绝在不同入口说不同的话）。
     *
     * <p>已送达/已完成要<b>说清下一步该去哪</b>：只回"不可取消"会让人反复重试或找客服。</p>
     *
     * @param action 调用方在做的事（"取消" / "拒单" / "解决"），只影响措辞
     */
    public static String notCancellableReason(Integer status, String action) {
        String a = (action == null || action.isBlank()) ? "取消" : action;
        if (status != null && status == DELIVERED) {
            return "订单已送达，不能再" + a + "（货已交付）。如客户拒付或货物有问题，请走「配送异常」处理";
        }
        if (status != null && status == COMPLETED) {
            return "订单已完成，不能再" + a + "；如需退钱请走退款流程";
        }
        if (status != null && status == CANCELLED) {
            return "订单已取消，无需重复操作";
        }
        return "当前订单状态不可" + a + "，如需帮助请联系水站";
    }

    /** {@link #notCancellableReason(Integer, String)} 的默认措辞版本（action = "取消"）。 */
    public static String notCancellableReason(Integer status) {
        return notCancellableReason(status, "取消");
    }

    public static boolean isValidTransition(int from, int to) {
        switch (from) {
            case PENDING:
                return to == DELIVERING || to == CANCELLED;
            case DELIVERING:
                return to == DELIVERED || to == COMPLETED || to == CANCELLED;
            case DELIVERED:
                // [2026-09-21] 去掉 to == CANCELLED：与 isCancellable 保持一致。
                // 两处若不一致，状态机说"合法"而闸门说"不许"，下一个人会照着状态机去写。
                return to == COMPLETED;
            default:
                return false;
        }
    }

    private OrderStatus() {}
}
