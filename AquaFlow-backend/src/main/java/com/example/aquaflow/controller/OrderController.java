package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.constant.OrderStatus;
import com.example.aquaflow.dto.OrderStatusDTO;
import com.example.aquaflow.entity.Orders;
import com.example.aquaflow.service.OrderService;
import com.example.aquaflow.util.AuthContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    @Autowired
    private OrderService orderService;

    @PostMapping
    public Result save(@RequestBody Orders orders){
        // 客户下单时，从 JWT 获取 customerId
        if ("customer".equals(AuthContext.getUserType()) && orders.getCustomerId() == null) {
            orders.setCustomerId(AuthContext.getUserId());
        }
        orderService.save(orders);
        return Result.success(orders.getId());
    }

    @GetMapping
    public Result<List<Orders>> list(@RequestParam(required = false) Integer stationId,
                                     @RequestParam(required = false) Integer status,
                                     @RequestParam(required = false) String tag,
                                     @RequestParam(required = false) String createTimeStart,
                                     @RequestParam(required = false) String createTimeEnd){
        // 客户只能看自己的订单，员工可以按条件筛选
        Integer customerId = null;
        if ("customer".equals(AuthContext.getUserType())) {
            customerId = AuthContext.requireCustomerId();
        }
        return Result.success(orderService.list(stationId, customerId, status, tag, createTimeStart, createTimeEnd));
    }

    @GetMapping("/{id}")
    public Result<Orders> getById(@PathVariable Integer id){
        return Result.success(orderService.getById(id));
    }

    @RequireRole({"FACTORY_ADMIN", "STATION_MANAGER"})
    @PutMapping("/{id}/status")
    public Result updateStatus(@PathVariable Integer id, @RequestBody OrderStatusDTO orderStatusDTO){
        orderService.updateStatus(id,orderStatusDTO.getStatus());
        return Result.success();
    }

    @RequireRole({"FACTORY_ADMIN", "STATION_MANAGER"})
    @PutMapping("/{id}/cancel")
    public Result cancel(@PathVariable Integer id){
        orderService.updateStatus(id, OrderStatus.CANCELLED);
        return Result.success();
    }

}
