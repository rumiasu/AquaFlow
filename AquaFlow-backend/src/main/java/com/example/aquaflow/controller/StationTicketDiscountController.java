package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.entity.StationTicketDiscount;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.StationTicketDiscountMapper;
import com.example.aquaflow.service.impl.TicketTierService;
import com.example.aquaflow.util.AuthContext;
import com.example.aquaflow.util.TicketPreset;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 水站「统一折扣」档位（v58，2026-09-20）。规格见 {@code docs/design/26} §26.11。
 *
 * <p><b>站长端特殊化、用户端无感</b>（产品口径：「统一水票，在站长端是特殊化的，但在用户端看起来没区别…
 * 不是专门卖统一水票」）：站长在这里配"买多少张打几折"，顾客端看到的是**某款水的票**
 * （走 {@code GET /api/ticket-packages?stationId&productId}，由 {@code TicketTierService} 折算）。
 * 所以本端点**只有站长能调**，客户端调不动。</p>
 *
 * <p><b>⚠️ 这里配的是折扣，不是价格</b>：价格按各款水自己的水票价现算
 * （农夫山泉按农夫山泉的价、娃哈哈按娃哈哈的价）—— 这就是产品说的「对应水怎么统一打折」。
 * 一旦把价格也存进来，它就会与"各款水的价"分叉。</p>
 *
 * <p>站点一律取自 {@code AuthContext.requireStationId()}，忽略请求体里的 stationId ——
 * 否则站长能改别人站的折扣，进而影响别人站客户的购票价格。</p>
 */
@RestController
@RequestMapping("/api/ticket-discounts")
@Slf4j
public class StationTicketDiscountController {

    @Autowired
    private StationTicketDiscountMapper stationTicketDiscountMapper;

    @Autowired
    private TicketTierService ticketTierService;

    /** 站长查本站全部档位（含已下架） */
    @RequireRole({"STATION_MANAGER"})
    @GetMapping
    public Result<List<StationTicketDiscount>> list() {
        return Result.success(stationTicketDiscountMapper.listAll(AuthContext.requireStationId()));
    }

    /**
     * 平台预设档（**一键填入的草稿**）：10 / 20 / 100 张各打几折。
     *
     * <p>⚠️ 本端点**只读**，不会替站长建档位 —— 定价是站长的经营决定；
     * 而"本站有没有上架的档位"就是统一折扣是否生效的判据，自动落行等于替所有水站开通折扣。</p>
     */
    @RequireRole({"STATION_MANAGER"})
    @GetMapping("/presets")
    public Result<Map<String, Object>> presets() {
        return Result.success(ticketTierService.presets(AuthContext.requireStationId()));
    }

    /**
     * 新增/修改档位（按 station + qty 唯一，同张数重复保存即更新）。
     *
     * <p>传的是**折扣千分比**（950 = 9.5 折），不是价格 —— 价格按各款水现算。
     * 校验在这里做：折扣必须落在 (0, 1000] 内（0 折 = 白送、>1000 = 加价，都不是"折扣"）。</p>
     */
    @RequireRole({"STATION_MANAGER"})
    @PostMapping
    public Result<StationTicketDiscount> save(@RequestBody StationTicketDiscount body) {
        Long stationId = AuthContext.requireStationId();
        if (body.getQty() == null || body.getQty() <= 0) {
            return Result.error("档位张数必须大于 0");
        }
        if (body.getQty() > 5000) {
            // 与 addTicket 的单次上限一致：水票是预付资产，档位张数不该出现天文数字
            return Result.error("档位张数不能超过 5000");
        }
        Integer perMille = body.getDiscountPerMille();
        if (perMille == null || perMille <= 0 || perMille > TicketPreset.PER_MILLE_FULL) {
            return Result.error("折扣必须大于 0 且不超过 10 折（如 9.5 折填 950）");
        }

        StationTicketDiscount d = new StationTicketDiscount();
        d.setStationId(stationId);          // 忽略请求体里的 stationId
        d.setQty(body.getQty());
        d.setDiscountPerMille(perMille);
        d.setTitle(body.getTitle());
        d.setStatus(body.getStatus() != null ? body.getStatus() : 1);
        d.setSort(body.getSort() != null ? body.getSort() : 0);

        stationTicketDiscountMapper.upsert(d);
        log.info("[v58] 站长保存统一折扣档: stationId={}, qty={}, discount={}‰",
                stationId, d.getQty(), d.getDiscountPerMille());
        return Result.success(stationTicketDiscountMapper.getByStationAndQty(stationId, d.getQty()));
    }

    /**
     * 删除档位。
     *
     * <p>只删折扣档本身，<b>不动已售出的水票</b> —— 客户账户里的票由 {@code ticket_lot}
     * 记着单价快照（按当时折扣算的实付均价），删档位不影响它们的价值与退票口径。</p>
     */
    @RequireRole({"STATION_MANAGER"})
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        int affected = stationTicketDiscountMapper.delete(id, AuthContext.requireStationId());
        if (affected == 0) {
            throw new BusinessException("档位不存在或不属于本站");
        }
        return Result.success();
    }
}
