package com.example.aquaflow.service;
import com.example.aquaflow.entity.Orders;
import com.example.aquaflow.mapper.*;
import com.example.aquaflow.exception.BusinessException;
import org.springframework.beans.factory.annotation.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.annotation.Lazy;
import java.util.*;
@Service
public class BusinessWaitingService {
    @Autowired private BarrelBusinessPolicy policy;
    @Autowired private BusinessWaitingMapper mapper;
    @Autowired private ConsumptionRefundMapper locks;
    @Autowired private PaymentService payments;
    @Autowired @Lazy private BusinessWaitingService proxy;
    @Value("${aquaflow.barrel.unpaid-timeout-minutes:15}") private int minutes;
    @Value("${aquaflow.barrel.maintenance-enabled:true}") private boolean maintenance;
    /** 不猜测渠道处理中款项；仅无活跃支付请求的未付新单自动释放占用。 */
    @Scheduled(fixedDelayString="${aquaflow.barrel.maintenance-interval-ms:60000}")
    public void expireUnpaid() {
        if (!policy.isEnabled() || !maintenance || minutes<=0) return;
        for (Long order:mapper.unpaidExpired(minutes)) {
            try { proxy.expire(order); } catch (BusinessException ignored) { /* 并发已付款/已取消时留给下一轮重新判断 */ }
        }
    }
    @Transactional public void expire(Long id) {
        if (!policy.isEnabled()) return;
        Orders order=locks.lockOrder(id);
        if (order==null || order.getStatus()!=1 || order.getPaymentStatus()!=0 || order.getPaymentMethod()==2
                || order.getCreateTime().isAfter(java.time.LocalDateTime.now().minusMinutes(minutes)) || mapper.hasActivePayment(id)>0) return;
        payments.refundOrder(id,"未付订单超过预留时间，自动取消并释放库存、权益占用；独立已付押金不退");
    }
    public Map<String,Object> waiting(Long station) {
        if(!policy.hasSchema())return Map.of("stock",List.of(),"returns",List.of());
        return Map.of("stock",mapper.waitingStock(station),"returns",mapper.delayedReturns(station));
    }
}
