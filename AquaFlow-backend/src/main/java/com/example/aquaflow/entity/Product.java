package com.example.aquaflow.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品实体类，对应数据库 product 表。
 * <p>存储商品基本信息，支持桶装水、瓶装水、饮水器等多品类。</p>
 */
@Data
public class Product {

    /** 商品ID，主键自增 */
    private Long id;

    /**
     * 归属水站：{@code NULL} = <b>通用商品库</b>（由开发者/运维维护，站长只读，只能"选用"到自己站）；
     * 非空 = 该站的<b>自定义商品</b>（不入通用库，仅本站可见，站长可完整编辑）。
     * <p>为什么要这张表同时装两种商品：{@code product.id} 是 15 张业务表（订单明细/桶账/水票/
     * 库存流水/调整单…）与 7 个唯一键的锚点，物理拆表会让这些引用的 id 语义分叉。
     * 隔离靠查询口径（{@code owner_station_id IS NULL OR = 本站}），不靠分表。</p>
     */
    private Long ownerStationId;

    /** 商品名称 */
    private String name;

    /** 商品分类: 1 桶装水 2 瓶装水 3 饮水器 */
    private Integer category;

    /**
     * 分类中文文案（全系统唯一来源）。
     * <p>前端禁止自带 1/2/3 映射表（本仓历史事故：两端各写一套映射，导致新客下单 100% 失败），
     * 所以展示文案一律由后端下发。</p>
     */
    public String getCategoryText() {
        if (category == null) return "";
        switch (category) {
            case 1: return "桶装水";
            case 2: return "瓶装水";
            case 3: return "饮水器";
            default: return "";
        }
    }

    /** 品牌 */
    private String brand;

    /** 规格 */
    private String spec;

    /** 图片 COS 对象键（如 public/product/abc.jpg） */
    private String imageObjectName;

    /** 商品描述 */
    private String description;

    /** 基础售价 */
    private BigDecimal price;

    /** 押金(只有桶装水使用) */
    private BigDecimal deposit;

    /** 水票价格 */
    private BigDecimal ticketPrice;

    /** 是否支持水票支付 */
    private Integer ticketEnabled;

    /** 单次购买上限 */
    private Integer maxPerOrder;

    /** 状态: 0 下架 1 正常 2 停售 */
    private Integer status;

    /** 状态中文文案（全系统唯一来源）：0 下架 1 在售 2 停售 */
    public String getStatusText() {
        if (status == null) return "在售";
        switch (status) {
            case 0: return "下架";
            case 1: return "在售";
            case 2: return "停售";
            default: return "在售";
        }
    }

    /** 排序 */
    private Integer sort;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;

    /** 图片临时访问 URL（由 Controller 注入，不入库） */
    private transient String imageUrl;
}
