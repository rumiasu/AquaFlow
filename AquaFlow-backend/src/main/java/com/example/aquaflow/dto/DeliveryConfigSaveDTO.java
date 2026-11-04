package com.example.aquaflow.dto;

import com.example.aquaflow.entity.StationDeliveryConfig;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 站长编辑配送配置：保留原平铺表单，并携带读取时的快照。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class DeliveryConfigSaveDTO extends StationDeliveryConfig {
    private String expectedSnapshot;
}
