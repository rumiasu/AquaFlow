package com.example.aquaflow.vo;

import java.util.List;

/** 按站 ID 稳定翻页；nextStationId 只在 hasMore 时有值。 */
public record CustomerAssetStationsVO(List<CustomerAssetStationVO> stations,
        boolean hasMore, Long nextStationId) {}

