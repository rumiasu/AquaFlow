package com.example.aquaflow.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class AccountDataRequest {
    private Long id;
    private String actorType;
    private Long actorId;
    private String requestType;
    private String note;
    private String idempotencyKey;
    private String requestDigest;
    private String status;
    private LocalDateTime createTime;
}
