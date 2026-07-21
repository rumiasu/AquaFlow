package com.example.aquaflow.controller;

import com.example.aquaflow.common.Result;
import com.example.aquaflow.entity.OrderTemplate;
import com.example.aquaflow.service.OrderTemplateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/order-templates")
@Slf4j
public class OrderTemplateController {

    @Autowired
    private OrderTemplateService templateService;

    @GetMapping("/quick")
    public Result<OrderTemplate> getQuickOrder(@RequestParam Integer customerId) {
        return Result.success(templateService.getQuickOrder(customerId));
    }

    @GetMapping
    public Result<List<OrderTemplate>> listByCustomerId(@RequestParam Integer customerId) {
        return Result.success(templateService.listByCustomerId(customerId));
    }

    @PostMapping
    public Result<OrderTemplate> save(@RequestBody OrderTemplate template,
                                      @RequestParam Integer customerId) {
        return Result.success(templateService.save(customerId, template));
    }

    @PutMapping("/{id}/toggle")
    public Result toggleEnabled(@PathVariable Integer id,
                                @RequestParam Integer customerId,
                                @RequestParam Integer enabled) {
        templateService.toggleEnabled(customerId, id, enabled);
        return Result.success();
    }

    @PutMapping("/{id}/default")
    public Result setDefault(@PathVariable Integer id, @RequestParam Integer customerId) {
        templateService.setDefault(customerId, id);
        return Result.success();
    }

    @PostMapping("/from-order")
    public Result<OrderTemplate> setFromOrder(@RequestParam Integer customerId,
                                              @RequestParam Integer orderId) {
        log.info("从订单创建模板: customerId={}, orderId={}", customerId, orderId);
        try {
            return Result.success(templateService.setFromOrder(customerId, orderId));
        } catch (Exception e) {
            log.error("创建模板失败: {}", e.getMessage());
            return Result.error(e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public Result delete(@PathVariable Integer id, @RequestParam Integer customerId) {
        templateService.delete(customerId, id);
        return Result.success();
    }
}
