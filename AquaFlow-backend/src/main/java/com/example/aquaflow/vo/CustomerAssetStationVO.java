package com.example.aquaflow.vo;

import lombok.Data;

/** 顾客选资产查看站只需标识、名称与营业状态；不返回联系人、地址或经营数据。 */
@Data
public class CustomerAssetStationVO {
    private Long id;
    private String name;
    private Integer status;

    public String getStatusText() {
        // 这里只描述硬状态；不将其当作今日营业/配送的软状态。
        if (Integer.valueOf(1).equals(status)) return "正常站点";
        if (Integer.valueOf(2).equals(status)) return "停业 · 可查看历史资产";
        return "站点状态未知";
    }
}

