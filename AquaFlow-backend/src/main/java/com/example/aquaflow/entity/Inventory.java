package com.example.aquaflow.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 水站商品库存实体类，对应数据库 inventory 表。
 * <p>某水站有没有这个商品、库存多少、是否在商城销售。</p>
 */
@Data
public class Inventory {

    /** 库存记录ID，主键自增 */
    private Long id;

    /** 水站ID */
    private Long stationId;

    /** 商品ID */
    private Long productId;

    /** 商品名称(关联查询字段) */
    private String productName;

    /** 规格(关联查询字段) */
    private String spec;

    /** 库存数量 */
    private Integer quantity;

    /** 是否在商城销售: 0 不在商城销售 1 在商城销售 */
    private Integer enabled;

    /**
     * 本站售价（覆盖 product.price 这个"通用库参考价"）。
     * <p><b>NULL 或 &lt;=0 都表示"不覆盖"</b>，回落 product.price —— 与 ticket_price 的
     * "&gt;0 才生效"保持同一口径，避免"填了 0 结果变成免费送水"。读取必须走
     * {@code util/PriceUtil#calcUnitPrice}，不要各处自己 if。</p>
     */
    private BigDecimal salePrice;

    /**
     * 本站押金（覆盖 product.deposit 这个"通用库参考押金"）。
     * <p><b>NULL 或 &lt;=0 表示"不覆盖"</b>：0 押金会让 {@code customer_barrel_lot.unit_price = 0}，
     * 而 {@code BarrelLedgerService#resolveUnitPrice} 按本仓 [DEF-2] 的口径把 0 视为"无快照"，
     * 于是会回退到商品押金 —— 即"免押金"在当前桶账模型下表达不出来。要做免押金得先改那条口径。</p>
     */
    private BigDecimal depositPrice;

    /** 是否支持水票: 0 不支持 1 支持 */
    private Integer ticketEnabled;

    /** 水票价格 */
    private BigDecimal ticketPrice;

    /** 优先展示: 0 否 1 是 */
    private Integer priorityDisplay;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;

    /** 所属水站名称(关联查询字段) */
    private String stationName;

    /** 商品状态（联查 product.status）：0 下架 1 在售 2 停售 */
    private Integer status;

    /** 商品类别（联查 product.category） */
    private String category;

    /** 商品状态中文文案（全系统唯一来源） */
    public String getStatusText() {
        if (status == null) return "在售";
        switch (status) {
            case 0: return "下架";
            case 1: return "在售";
            case 2: return "停售";
            default: return "在售";
        }
    }

    /** 低库存预警线（业务参数，由后端定义，前端不再硬编码阈值） */
    public static final int LOW_STOCK_THRESHOLD = 20;

    /** 是否低库存（低于预警线） */
    public Boolean getLowStock() {
        return quantity != null && quantity < LOW_STOCK_THRESHOLD;
    }
}