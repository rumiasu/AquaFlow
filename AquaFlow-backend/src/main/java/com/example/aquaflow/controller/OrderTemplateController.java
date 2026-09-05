package com.example.aquaflow.controller;

import com.example.aquaflow.common.Result;
import com.example.aquaflow.entity.OrderTemplate;
import com.example.aquaflow.service.OrderTemplateService;
import com.example.aquaflow.util.AuthContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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