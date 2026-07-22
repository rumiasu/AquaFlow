package com.example.aquaflow.constant;

public class OrderStatus {

    public static final int PENDING = 1;//待组批

    public static final int DELIVERING = 2;//配送中

    public static final int FINISHED = 3;//已完成

    public static final int BATCHED = 4;//已组批待出发

    public static final int CANCELLED = 5;//已取消

    /**
     * 校验状态转换是否合法
     * 合法转换：PENDING→BATCHED, PENDING→CANCELLED, BATCHED→DELIVERING, BATCHED→CANCELLED, DELIVERING→FINISHED
     */
    public static boolean isValidTransition(int from, int to) {
        switch (from) {
            case PENDING:
                return to == BATCHED || to == CANCELLED;
            case BATCHED:
                return to == DELIVERING || to == CANCELLED;
            case DELIVERING:
                return to == FINISHED;
            default:
                return false; // FINISHED 和 CANCELLED 是终态
        }
    }
}
