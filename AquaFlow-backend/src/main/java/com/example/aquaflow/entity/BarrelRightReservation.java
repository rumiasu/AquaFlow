package com.example.aquaflow.entity;

import lombok.Data;
import java.time.LocalDateTime;

/** 权益分配凭据；取消只释放，不删除历史分配或退款责任。 */
@Data
public class BarrelRightReservation {
    private Long id;
    private Long customerId;
    private Long stationId;
    private Long productId;
    private String ownerType;
    private Long ownerId;
    private Integer quantity;
    private Integer pickupQty;
    private Integer pendingQty = 0;
    private Integer pendingPickupQty = 0;
    private String status;
    private LocalDateTime createTime;
}
