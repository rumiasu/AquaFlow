package com.example.aquaflow.vo;

import lombok.Data;
import java.math.BigDecimal;

/** 客户找站只读投影，不包含站长身份、联系电话或完整计费配置。 */
@Data
public class StationDiscoveryVO {
    private Long id;
    private String name;
    private String address;
    private Integer status;
    private Integer operatingStatus;
    private BigDecimal lat;
    private BigDecimal lng;
    private Integer deliveryRadiusM;
    private BigDecimal distanceKm;
}
