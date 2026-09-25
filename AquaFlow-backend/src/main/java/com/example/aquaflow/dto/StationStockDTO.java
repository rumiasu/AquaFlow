package com.example.aquaflow.dto;

import lombok.Data;

/**
 * 站长盘点本站库存（{@code POST /api/manager/catalog/{productId}/stock}）。
 *
 * <p>语义是"把库存设为 target"而不是"加/减"：站长看到实物有几个就填几个，
 * 差额由服务端算并落 {@code inventory_record}(type=ADJUST) 流水。
 * 入库（加数量）走既有的 {@code POST /api/inventory/inbound}（type=INBOUND）。</p>
 */
@Data
public class StationStockDTO {

    /** 目标库存（>=0）；必填 */
    private Integer target;

    /** 备注（如"实盘 12 桶"），进流水的 note 字段，便于对账时回溯 */
    private String note;
}
