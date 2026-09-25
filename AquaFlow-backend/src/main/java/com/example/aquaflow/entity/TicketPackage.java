package com.example.aquaflow.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 水票档位套餐，对应 {@code ticket_package} 表（v36）。规格见 {@code docs/design/19}。
 *
 * <p><b>它是定价结构，不是促销活动</b>：站长挂出来一份价目表（10 张 / 20 张 / 100 张，越买越便宜），
 * 永远可买、不叠加、不互斥、不需要活动引擎。这一点是本轮取舍的核心 ——
 * 通用促销（满减/优惠券/叠加/优先级/退款摊分）是个无底洞，而档位只是"分段单价"。</p>
 *
 * <p>档位是<b>站级</b>的（唯一键 `(station_id, product_id, qty)`）：全局档位会让 A 站买的票
 * 在 B 站出现价差。</p>
 */
@Data
public class TicketPackage {

    private Long id;

    /** 水站ID（档位属于具体水站） */
    private Long stationId;

    /** 商品ID */
    private Long productId;

    /** 本档张数（如 10 / 20 / 100） */
    private Integer qty;

    /** 本档总价 */
    private BigDecimal price;

    /**
     * 均价 = {@code price / qty}。
     *
     * <p>冗余落库而不是每次相除：它是要**快照进 `ticket_lot.unit_price`** 的值，
     * 落库一次、展示与快照都用它，避免"展示用四舍五入、快照用原始值"这类两处不一致。</p>
     */
    private BigDecimal unitPrice;

    /** 展示名（可空，前端按张数生成即可） */
    private String title;

    /** 1 上架 0 下架 */
    private Integer status;

    /** 排序，小的在前 */
    private Integer sort;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 是否上架（前端展示用，避免前端自己写 1/0 判断） */
    public boolean isOnShelf() {
        return status != null && status == 1;
    }
}
