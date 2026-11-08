package com.example.aquaflow.service;

import com.example.aquaflow.entity.CustomerBarrelOver;
import com.example.aquaflow.mapper.CustomerBarrelLotMapper;
import com.example.aquaflow.mapper.CustomerBarrelOverMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 执行审计专用当前读；不写桶账，不代替 BarrelLedgerService 的写入锁与业务校验。 */
@Service
public class BarrelBalanceReader {
    private final CustomerBarrelOverMapper overMapper;
    private final CustomerBarrelLotMapper lotMapper;

    public BarrelBalanceReader(CustomerBarrelOverMapper overMapper, CustomerBarrelLotMapper lotMapper) {
        this.overMapper = overMapper;
        this.lotMapper = lotMapper;
    }

    /** 执行审计的同事务当前读；不建空行，不能用于页面只读预览。 */
    @Transactional
    public CurrentBalance currentBalanceForUpdate(Long customerId, Long stationId, Long productId) {
        CustomerBarrelOver over = overMapper.getForUpdate(customerId, stationId, productId);
        int right = lotMapper.listAvailableForUpdate(customerId, stationId, productId).stream()
                .mapToInt(l -> l.getRemainQty() == null ? 0 : l.getRemainQty()).sum();
        return new CurrentBalance(right, over == null || over.getOverQty() == null ? 0 : over.getOverQty());
    }

    /** 当前读返回的权益与差额，不独立记录实际持桶数。 */
    public record CurrentBalance(int right, int over) {}
}
