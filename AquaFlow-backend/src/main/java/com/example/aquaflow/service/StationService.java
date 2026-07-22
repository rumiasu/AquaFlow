package com.example.aquaflow.service;

import com.example.aquaflow.entity.Station;

import java.util.List;

public interface StationService {

    List<Station> listAll();

    Station getById(Integer id);

    void save(Station station);

    void update(Station station);

    void delete(Integer id);

    /**
     * 关闭水站：
     * 1. 取消所有待配送订单
     * 2. 解绑所有客户（station_id → NULL）
     * 3. 设置水站状态为停用
     */
    void closeStation(Integer stationId);
}
