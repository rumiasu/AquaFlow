package com.example.aquaflow.constant;

/**
 * 订单状态（canonical 定义，全端统一）。
 * <p>1=待配送 2=配送中 3=已送达 4=已完成 5=已取消（连续编号）。
 * 历史版本曾用 1/3/4/5/6（含"组批"概念、状态 2 空缺），现重排为连续编号，
 * 以免前端/小程序映射混乱。流转：1→2/5，2→3/4/5，3→4/5。</p>
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
    /** 5 已取消：任意前置态可取消 */
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

    /** 是否处于可取消状态（已送达待收款仍允许站长/客户取消，已完成与已取消不可） */
    public static boolean isCancellable(Integer status) {
        return status != null && (status == PENDING || status == DELIVERING || status == DELIVERED);
    }

    public static boolean isValidTransition(int from, int to) {
        switch (from) {
            case PENDING:
                return to == DELIVERING || to == CANCELLED;
            case DELIVERING:
                return to == DELIVERED || to == COMPLETED || to == CANCELLED;
            case DELIVERED:
                return to == COMPLETED || to == CANCELLED;
            default:
                return false;
        }
    }

    private OrderStatus() {}
}
