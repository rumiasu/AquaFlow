package com.example.aquaflow.service.factory;

import com.example.aquaflow.entity.StockTransfer;

import java.util.List;
import java.util.Map;

public interface StockTransferService {

    /** 查询可调拨库存 */
    List<Map<String, Object>> availableStock();

    /** 发起调拨 */
    void create(StockTransfer transfer);

    /** 调拨列表 */
    List<StockTransfer> list(Integer status);

    /** 审批调拨 */
    void approve(Integer id, String note);

    /** 完成调拨 */
    void complete(Integer id, String note);

    /** 取消调拨 */
    void cancel(Integer id);
}
