package com.example.aquaflow.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class Feedback {
    private Long id;
    private Long staffId;
    private Long customerId;
    private String category;
    private String content;
    private String contact;
    private String customerName;
    private LocalDateTime createTime;
}
