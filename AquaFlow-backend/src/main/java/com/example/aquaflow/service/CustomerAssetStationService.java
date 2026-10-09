package com.example.aquaflow.service;

import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.CustomerAssetStationMapper;
import com.example.aquaflow.vo.CustomerAssetStationVO;
import com.example.aquaflow.vo.CustomerAssetStationsVO;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class CustomerAssetStationService {
    private final CustomerAssetStationMapper mapper;

    public CustomerAssetStationService(CustomerAssetStationMapper mapper) { this.mapper = mapper; }

    private void requireCustomer(Long customerId) {
        if (customerId == null || mapper.countCustomer(customerId) == 0)
            throw new BusinessException("客户不存在");
    }

    public CustomerAssetStationsVO list(Long customerId, Long afterStationId, int limit) {
        requireCustomer(customerId);
        if (afterStationId == null || afterStationId < 0 || limit < 1 || limit > 50)
            throw new BusinessException("站点页游标或数量不正确");
        var rows = mapper.listOwned(customerId, afterStationId, limit + 1);
        boolean hasMore = rows.size() > limit;
        var page = List.copyOf(rows.subList(0, Math.min(rows.size(), limit)));
        return new CustomerAssetStationsVO(page, hasMore,
                hasMore ? page.get(page.size() - 1).getId() : null);
    }

    public CustomerAssetStationVO get(Long customerId, Long stationId) {
        requireCustomer(customerId);
        if (stationId == null || stationId <= 0) throw new BusinessException("请选择有效水站");
        var station = mapper.getOwned(customerId, stationId);
        if (station == null) throw new BusinessException("该站暂无你的资产或历史关系");
        return station;
    }

}

