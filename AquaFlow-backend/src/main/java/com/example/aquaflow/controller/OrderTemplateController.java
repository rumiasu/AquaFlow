package com.example.aquaflow.controller;

import com.example.aquaflow.common.Result;
import com.example.aquaflow.entity.OrderTemplate;
import com.example.aquaflow.service.OrderTemplateService;
import com.example.aquaflow.util.AuthContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 常用订单模板接口（顾客自助；customerId 一律取自 JWT，不信请求参数）。
 *
 * <p><b>⚠️ {@code stationId} 是必填参数，不是可有可无的装饰。</b>
 * 模板按 {@code (customerId, stationId)} 隔离（见 {@code OrderTemplateServiceImpl} 的 H02 注释）：
 * 少了它，列表查不出来、保存会落到空站上。</p>
 *
 * <p>顾客端取 stationId 的<b>唯一正确方式</b>是 {@code miniapp-user/utils/station.js}
 * 的 {@code resolveStationId()}（stationStorage 优先 → {@code /api/orders/my-station} 回退）。
 * 历史上本页曾误用<b>员工</b>接口 {@code /api/stations/mine} 取站：顾客 token 恒 403，
 * 异常又被空 catch 吞掉，导致 stationId 永远为 null——现象是"模板列表恒空、保存无声失败"，
 * 不报任何错，排查了很久。改接口前请先读那段历史以免重蹈覆辙。</p>
 */
@RestController
@RequestMapping("/api/order-templates")
@Slf4j
public class OrderTemplateController {

    @Autowired
    private OrderTemplateService templateService;

    @GetMapping("/quick")
    public Result<OrderTemplate> getQuickOrder(@RequestParam Long stationId) {
        Long customerId = AuthContext.requireCustomerId();
        return Result.success(templateService.getQuickOrder(customerId, stationId));
    }

    @GetMapping
    public Result<List<OrderTemplate>> listByCustomerId(@RequestParam Long stationId) {
        Long customerId = AuthContext.requireCustomerId();
        return Result.success(templateService.listByCustomerAndStation(customerId, stationId));
    }

    @PostMapping
    public Result<OrderTemplate> save(@RequestBody OrderTemplate template, @RequestParam Long stationId) {
        Long customerId = AuthContext.requireCustomerId();
        return Result.success(templateService.save(customerId, template, stationId));
    }

    @PutMapping("/{id}/toggle")
    public Result toggleEnabled(@PathVariable Long id,
                                @RequestParam Integer enabled) {
        Long customerId = AuthContext.requireCustomerId();
        templateService.toggleEnabled(customerId, id, enabled);
        return Result.success();
    }

    @PutMapping("/{id}/default")
    public Result setDefault(@PathVariable Long id, @RequestParam Long stationId) {
        Long customerId = AuthContext.requireCustomerId();
        templateService.setDefault(customerId, id, stationId);
        return Result.success();
    }

    @PostMapping("/from-order")
    public Result<OrderTemplate> setFromOrder(@RequestParam Long orderId, @RequestParam Long stationId) {
        Long customerId = AuthContext.requireCustomerId();
        log.info("从订单创建模板: customerId={}, orderId={}", customerId, orderId);
        try {
            return Result.success(templateService.setFromOrder(customerId, orderId, stationId));
        } catch (Exception e) {
            log.error("创建模板失败: {}", e.getMessage());
            return Result.error(e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public Result delete(@PathVariable Long id) {
        Long customerId = AuthContext.requireCustomerId();
        templateService.delete(customerId, id);
        return Result.success();
    }
}