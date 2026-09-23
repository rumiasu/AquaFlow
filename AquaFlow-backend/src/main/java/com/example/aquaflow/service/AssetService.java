package com.example.aquaflow.service;

public interface AssetService {

    /**
     * 判断客户在指定水站是否已有资产（水票、桶、押金任一存在即为 true）
     * 用于首次资产业务判断
     */
    boolean hasStationAsset(Long customerId, Long stationId);
}