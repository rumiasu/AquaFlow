package com.example.aquaflow.service;

import com.example.aquaflow.mapper.ConsumptionRefundMapper;
import com.example.aquaflow.mapper.OrderBarrelPurchaseMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;

/** 原款行锁仍由外层持有；独立只读事务读取最新已提交凭据，不锁退款索引的空区间。 */
@Service
public class RefundReceiptReader {
    @Autowired private ConsumptionRefundMapper consumption;
    @Autowired private OrderBarrelPurchaseMapper purchases;
    public record Rows(List<Map<String,Object>> consumption,List<Map<String,Object>> deposits) {}

    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,readOnly=true)
    public Rows latestCommitted(Long original,boolean combinedSchema) {
        return new Rows(consumption.refundRows(original),combinedSchema?purchases.refundRows(original):List.of());
    }
}
