package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.entity.TicketPackage;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.TicketPackageMapper;
import com.example.aquaflow.util.AuthContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 水票档位套餐：站长端维护 + 客户端查询。2026-09-17 新增（v36）。规格见 {@code docs/design/19}。
 *
 * <p><b>档位是定价结构，不是促销活动</b>：站长挂一份价目表（10 张 / 20 张 / 100 张，越买越便宜），
 * 永远可买、不叠加、不互斥。所以这里只有最朴素的 CRUD，没有活动、优先级、互斥、退款摊分。</p>
 *
 * <p><b>站点一律取自 {@code AuthContext.requireStationId()}</b>，忽略请求体里的 stationId ——
 * 否则站长能改别人站的档位，进而影响别人站客户的购票价格。</p>
 *
 * <p>客户端查询走 {@link #listForCustomer}：只返回上架档位，且必须显式传 stationId 与 productId
 * （档位是站级的，全局档位会让 A 站买的票在 B 站有价差）。</p>
 */
@RestController
@RequestMapping("/api/ticket-packages")
@Slf4j
public class TicketPackageController {

    @Autowired
    private TicketPackageMapper ticketPackageMapper;

    /**
     * 客户端查某站某商品的在售档位。
     *
     * <p>无鉴权要求与其它公开商品接口一致；只返回上架档位，不含任何站长私有字段。</p>
     */
    @GetMapping
    public Result<List<TicketPackage>> listForCustomer(@RequestParam Long stationId,
                                                       @RequestParam Long productId) {
        return Result.success(ticketPackageMapper.listOnShelf(stationId, productId));
    }

    /** 站长查本站某商品的全部档位（含已下架） */
    @RequireRole({"STATION_MANAGER"})
    @GetMapping("/manage")
    public Result<List<TicketPackage>> listForManager(@RequestParam Long productId) {
        return Result.success(ticketPackageMapper.listAll(AuthContext.requireStationId(), productId));
    }

    /**
     * 新增/修改档位（按 station + product + qty 唯一，同张数重复保存即更新）。
     *
     * <p><b>{@code unitPrice} 由服务端算</b>（{@code price / qty}，保留 2 位），不接受客户端传入 ——
     * 它是快照进水票批次的值，必须"展示与快照同源"。让前端传均价，就会出现
     * "前端显示 8.00、快照存 8.33"这类两处不一致。</p>
     */
    @RequireRole({"STATION_MANAGER"})
    @PostMapping
    public Result<TicketPackage> save(@RequestBody TicketPackage body) {
        Long stationId = AuthContext.requireStationId();
        if (body.getProductId() == null) {
            return Result.error("productId 不能为空");
        }
        if (body.getQty() == null || body.getQty() <= 0) {
            return Result.error("档位张数必须大于 0");
        }
        if (body.getQty() > 5000) {
            // 与 addTicket 的单次上限一致：水票是预付资产，档位张数不该出现天文数字
            return Result.error("档位张数不能超过 5000");
        }
        if (body.getPrice() == null || body.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
            return Result.error("档位总价必须大于 0");
        }

        TicketPackage pkg = new TicketPackage();
        pkg.setStationId(stationId);       // 忽略请求体里的 stationId
        pkg.setProductId(body.getProductId());
        pkg.setQty(body.getQty());
        pkg.setPrice(body.getPrice().setScale(2, RoundingMode.HALF_UP));
        pkg.setUnitPrice(body.getPrice().divide(BigDecimal.valueOf(body.getQty()), 2, RoundingMode.HALF_UP));
        pkg.setTitle(body.getTitle());
        pkg.setStatus(body.getStatus() != null ? body.getStatus() : 1);
        pkg.setSort(body.getSort() != null ? body.getSort() : 0);

        ticketPackageMapper.upsert(pkg);
        log.info("[v36] 站长保存水票档位: stationId={}, productId={}, qty={}, price={}, unitPrice={}",
                stationId, pkg.getProductId(), pkg.getQty(), pkg.getPrice(), pkg.getUnitPrice());
        return Result.success(ticketPackageMapper.getByStationProductQty(stationId, pkg.getProductId(), pkg.getQty()));
    }

    /**
     * 删除档位。
     *
     * <p>只删档位本身，<b>不动已售出的水票</b> —— 客户账户里的票由 {@code ticket_lot} 记着单价快照，
     * 删档位不影响它们的价值与退票口径。</p>
     */
    @RequireRole({"STATION_MANAGER"})
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        int affected = ticketPackageMapper.delete(id, AuthContext.requireStationId());
        if (affected == 0) {
            throw new BusinessException("档位不存在或不属于本站");
        }
        return Result.success();
    }
}
