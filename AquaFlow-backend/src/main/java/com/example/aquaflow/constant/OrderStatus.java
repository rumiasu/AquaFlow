package com.example.aquaflow.constant;

public class OrderStatus {

    public static final int PENDING = 1;
    public static final int DELIVERING = 3;
    public static final int DELIVERED = 4;
    public static final int COMPLETED = 5;
    public static final int CANCELLED = 6;

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
