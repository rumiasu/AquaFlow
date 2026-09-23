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
}