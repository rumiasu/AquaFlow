package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.entity.TicketPackage;
import com.example.aquaflow.mapper.TicketPackageMapper;
import com.example.aquaflow.util.AuthContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

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

    /** 档位判据（定制 or 站级统一折扣）的唯一实现 —— 客户端该看到哪些档位问它 */
    @Autowired
    private com.example.aquaflow.service.impl.TicketTierService ticketTierService;

    @Autowired
    private com.example.aquaflow.service.TicketPackageService ticketPackageService;

    /**
     * 客户端查某站某商品**能买哪些档位**。
     *
     * <p>无鉴权要求与其它公开商品接口一致；不含任何站长私有字段。</p>
     *
     * <p><b>[v58] 返回的是"这一款水"的档位，可能是两种来源</b>（{@code source} 字段区分，
     * 前端不必分两套渲染）：该商品开了定制票 → {@code CUSTOM}（站长挂的绝对价目表）；
     * 否则本站配了统一折扣且它是桶装水 → {@code UNIFIED}（<b>按这一款水自己的价打折</b>折算）。
     * 判据唯一实现在 {@code TicketTierService.customerTiers}。</p>
     *
     * <p>⚠️ 这里**永远不会**出现"统一水票"这种商品 —— 用户端看到的始终是某款水的票
     * （产品 2026-09-20：「在用户端看起来没区别…不是专门卖统一水票」）。</p>
     */
    @GetMapping
    public Result<List<java.util.Map<String, Object>>> listForCustomer(@RequestParam Long stationId,
                                                                      @RequestParam Long productId,
                                                                      @RequestParam(defaultValue = "false") boolean includeRetail) {
        return Result.success(ticketTierService.customerTiers(stationId, productId, includeRetail));
    }

    /** 站长查本站某商品的全部**定制**档位（含已下架）。统一折扣档走 /api/ticket-discounts。 */
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
        return Result.success(ticketPackageService.save(AuthContext.requireStationId(), body));
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
        ticketPackageService.delete(AuthContext.requireStationId(), id);
        return Result.success();
    }
}
