package com.example.aquaflow.service.impl;

import com.example.aquaflow.entity.CustomerBarrelAsset;
import com.example.aquaflow.entity.TicketAccount;
import com.example.aquaflow.mapper.CustomerBarrelAssetMapper;
import com.example.aquaflow.mapper.CustomerDepositAccountMapper;
import com.example.aquaflow.mapper.TicketAccountMapper;
import com.example.aquaflow.service.AssetService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
public class AssetServiceImpl implements AssetService {

    @Autowired
    private TicketAccountMapper ticketAccountMapper;

    @Autowired
    private CustomerBarrelAssetMapper customerBarrelAssetMapper;

    @Autowired
    private CustomerDepositAccountMapper customerDepositAccountMapper;

    @Override
    public boolean hasStationAsset(Long customerId, Long stationId) {
        if (customerId == null || stationId == null) {
            return false;
        }

        // 1. 检查水票账户
        List<TicketAccount> tickets = ticketAccountMapper.listByCustomerAndStation(customerId, stationId);
        if (tickets != null && !tickets.isEmpty()) {
            for (TicketAccount t : tickets) {
                if (t.getRemainQuantity() != null && t.getRemainQuantity() > 0) {
                    return true;
                }
            }
        }

        // 2. 检查桶资产
        List<CustomerBarrelAsset> barrelAssets = customerBarrelAssetMapper.listByCustomerAndStation(customerId, stationId);
        if (barrelAssets != null && !barrelAssets.isEmpty()) {
            for (CustomerBarrelAsset a : barrelAssets) {
                if (a.getQuantity() != null && a.getQuantity() > 0) {
                    return true;
                }
            }
        }

        // 3. 检查押金账户
        java.math.BigDecimal depositBalance = customerDepositAccountMapper.getBalance(customerId, stationId);
        if (depositBalance != null && depositBalance.compareTo(java.math.BigDecimal.ZERO) > 0) {
            return true;
        }

        return false;
    }

    /**
     * 客户在本站**是否已经有桶**（只看桶，不看水票/押金）。
     *
     * <p>用的还是上面第 2 步那张表与同一个判据（{@code customer_barrel_asset.quantity > 0}）——
     * 口径只能有一处实现，所以这里**复用它**而不是各写一遍。为什么不能直接调
     * {@link #hasStationAsset}：见接口上的注释（票/押金会把它污染成"老客户"）。</p>
     *
     * <p>⚠️ 口径是「**已到手**的桶」（asset 表只记送达入账的），配送中(PENDING) 的桶不算 ——
     * 那正是"这单还没送到、他手里确实没有空桶"的情形，与调用方（首单判定）的语义一致。</p>
     */
    @Override
    public boolean hasBarrelAsset(Long customerId, Long stationId) {
        if (customerId == null || stationId == null) {
            return false;
        }
        List<CustomerBarrelAsset> barrelAssets =
                customerBarrelAssetMapper.listByCustomerAndStation(customerId, stationId);
        if (barrelAssets == null || barrelAssets.isEmpty()) {
            return false;
        }
        for (CustomerBarrelAsset a : barrelAssets) {
            if (a.getQuantity() != null && a.getQuantity() > 0) {
                return true;
            }
        }
        return false;
    }
}