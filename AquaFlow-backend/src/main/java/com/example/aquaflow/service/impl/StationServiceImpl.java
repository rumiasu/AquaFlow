package com.example.aquaflow.service.impl;

import com.example.aquaflow.entity.Orders;
import com.example.aquaflow.entity.Station;
import com.example.aquaflow.mapper.CustomerMapper;
import com.example.aquaflow.mapper.OrderMapper;
import com.example.aquaflow.mapper.StationMapper;
import com.example.aquaflow.service.StationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class StationServiceImpl implements StationService {

    @Autowired
    private StationMapper stationMapper;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private CustomerMapper customerMapper;

    @Override
    public List<Station> listAll() {
        return stationMapper.listAll();
    }

    @Override
    public Station getById(Integer id) {
        Station station = stationMapper.getById(id);
        if (station == null) {
            throw new RuntimeException("水站不存在");
        }
        return station;
    }

    @Override
    public void save(Station station) {
        station.setCreateTime(LocalDateTime.now());
        station.setUpdateTime(LocalDateTime.now());
        stationMapper.insert(station);
    }

    @Override
    public void update(Station station) {
        station.setUpdateTime(LocalDateTime.now());
        stationMapper.update(station);
    }

    @Override
    public void delete(Integer id) {
        stationMapper.delete(id);
    }

    @Override
    @Transactional
    public void closeStation(Integer stationId) {
        // 1. 取消所有待配送订单
        List<Orders> pendingOrders = orderMapper.listPendingByStationId(stationId);
        for (Orders order : pendingOrders) {
            orderMapper.updateStatus(order.getId(), 5); // 5 = 已取消
        }

        // 2. 解绑所有客户（station_id → NULL）
        customerMapper.unbindByStationId(stationId);

        // 3. 设置水站状态为停用
        Station station = getById(stationId);
        station.setStatus(0);
        station.setUpdateTime(LocalDateTime.now());
        stationMapper.update(station);
    }
}
