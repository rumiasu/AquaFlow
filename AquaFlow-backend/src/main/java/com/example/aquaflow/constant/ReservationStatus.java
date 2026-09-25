package com.example.aquaflow.constant;

/**
 * 库存预留凭据的状态（`inventory_reservation.status`）。
 *
 * <p>三态是**单向**的（AGENTS §8.18「状态只前进」）：`1 预留中 → 2 已出库`（完成配送）
 * 或 `1 预留中 → 3 已释放`（取消 / 拒单 / 换站时旧凭据作废）。已出库与已释放都是终态，
 * 不许回退成预留中 —— 那等于让同一份货被两条路径各出一次。</p>
 */
public class ReservationStatus {

    /** 1 预留中：占了本站可用量、实物还没动（换站时这份凭据会被释放并在新站重建一条） */
    public static final int RESERVED = 1;

    /** 2 已出库：完成配送时实物已减（`inventory.quantity` 已扣，出库量记在 `shipped_qty`） */
    public static final int SHIPPED = 2;

    /** 3 已释放：这份承诺作废（取消订单 / 换站搬到别站），释放量记在 `released_qty` */
    public static final int RELEASED = 3;

    private ReservationStatus() {}
}
