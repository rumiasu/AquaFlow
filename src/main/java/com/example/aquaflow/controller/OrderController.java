package com.example.aquaflow.controller;

import com.example.aquaflow.common.Result;
import com.example.aquaflow.dto.OrderStatusDTO;
import com.example.aquaflow.entity.Orders;
import com.example.aquaflow.service.OrderService;
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
        orderService.save(orders);
        return Result.success(orders.getId());
    }

    @GetMapping
    public Result<List<Orders>> list(@RequestParam Integer status,
                                     @RequestParam(required = false) String tag,
                                     @RequestParam(required = false) String createTimeStart,
                                     @RequestParam(required = false) String createTimeEnd){
        return Result.success(orderService.list(status,tag,createTimeStart,createTimeEnd));
    }

    @GetMapping("/{id}")
    public Result<Orders> getById(@PathVariable Integer id){
        return Result.success(orderService.getById(id));
    }

    @PutMapping("/{id}/status")
    public Result updateStatus(@PathVariable Integer id, @RequestBody OrderStatusDTO orderStatusDTO){
        orderService.updateStatus(id,orderStatusDTO.getStatus());
        return Result.success();
    }

}
