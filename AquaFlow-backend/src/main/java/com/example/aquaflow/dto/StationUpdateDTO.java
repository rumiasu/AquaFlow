package com.example.aquaflow.dto;

import lombok.Data;
import java.math.BigDecimal;

/**
 * 站长融合编辑资料。coordinateMode 是地址与地图点的一次性保存意图；旧资料请求仍使用原接口。
 * SET 重选地图点，KEEP 仅补充门牌并保留原点，CLEAR 明确清除地图点；站点身份不取请求体。
 * KEEP 是调用方确认的意图，不代表服务端能从文字判断“补门牌”或验证点址一致；迁址须SET或CLEAR。
 */
@Data
public class StationUpdateDTO {
    private String name;
    private String phone;
    private String address;
    private Integer status;
    private String coordinateMode;
    private BigDecimal lat;
    private BigDecimal lng;
}
