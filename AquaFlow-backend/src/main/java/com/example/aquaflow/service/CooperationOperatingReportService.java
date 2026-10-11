package com.example.aquaflow.service;

import com.example.aquaflow.mapper.GrossProfitCooperationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/** 销售利润旁的合作款摘要；按原订单期间回看截至查询时的登记事实。 */
@Service
@RequiredArgsConstructor
public class CooperationOperatingReportService {
    private final GrossProfitCooperationMapper mapper;
    private final InterStationSettlementService settlements;
    private final BarrelBusinessPolicy policy;

    @Transactional(readOnly=true)
    public Map<String,Object> report(Long stationId, LocalDateTime start, LocalDateTime endExclusive) {
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("available",policy.hasSchema());
        if (!policy.hasSchema()) {
            out.put("scopeNote","合作款汇总尚未开放，请按原站间台账逐笔核对。");
            return out;
        }
        out.putAll(mapper.agreements(stationId,start,endExclusive));
        out.putAll(mapper.customerCash(stationId,start,endExclusive));
        out.putAll(mapper.settlements(stationId,start,endExclusive));
        out.putAll(mapper.recoveries(stationId,start,endExclusive));
        out.putAll(settlements.pendingOfPeriod(stationId,start,endExclusive));
        BigDecimal net=amount(out,"customerReceivedAmount").subtract(amount(out,"customerRefundedAmount"))
                .add(amount(out,"settledReceiveAmount")).subtract(amount(out,"settledPayAmount"))
                .add(amount(out,"recoveryReceivedAmount")).subtract(amount(out,"recoverySentPayAmount"));
        out.put("recordedCashNetAmount",net);
        String[][] fields={
            {"agreedPayAmount","原接受应付报酬"},{"agreedReceiveAmount","原接受应收报酬"},{"quoteDifferenceAmount","本站外派原报价差额"},
            {"customerReceivedAmount","已登记客户消费收款"},{"customerRefundedAmount","已登记客户消费退款"},
            {"pendingPayAmount","合作款待付"},{"pendingReceiveAmount","合作款待收"},
            {"settledPayAmount","已登记合作交款"},{"settledReceiveAmount","已登记合作收款"},
            {"recoveryPendingPayAmount","退款后返还待交"},{"recoveryPendingReceiveAmount","退款后返还待收"},
            {"recoverySentPayAmount","返还已登记交出"},{"recoveryReceivedAmount","返还已确认收到"},
            {"recoveryAwaitingConfirmationAmount","对方已交出、本站待确认"},{"recordedCashNetAmount","登记净现金（不等于利润）"}
        };
        List<Map<String,Object>> rows=new ArrayList<>();
        for(String[] field:fields) rows.add(Map.of("key",field[0],"label",field[1],"amount",out.get(field[0])));
        out.put("rows",rows);
        out.put("scopeNote","按下单期间查看这些合作订单，截至查询时的约定和登记实款；不按交款日期统计。原报价差额 = 本站外派消费报价 − 已接受报酬，退款不改原报价。登记净现金不等于利润；返还由付款方登记交出、收款方确认收到，待确认不记收款。押金、桶款、买票款和责任分摊不在此表。");
        return out;
    }
    private static BigDecimal amount(Map<String,Object> row,String key) { return (BigDecimal)row.get(key); }
}
