package com.example.aquaflow.dto;

import lombok.Data;

/** 已获准读取的记录的展示投影；不写回业务表，也不补造历史名称。 */
@Data
public class RecordDisplayNameRow {
    private Long id;
    private String customerName;
    private String receiverName;
    private String productName;
    private String productSpec;
    private String stationName;
}
