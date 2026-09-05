package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.constant.OrderStatus;
import com.example.aquaflow.dto.OrderCreateDTO;
import com.example.aquaflow.dto.OrderCreateResult;
import com.example.aquaflow.entity.Orders;
import com.example.aquaflow.mapper.OrderMapper;
import com.example.aquaflow.service.OrderService;
import com.example.aquaflow.util.AuthContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderMapper orderMapper;

    @PostMapping("/create")
    public Result<OrderCreateResult> createOrder(@RequestBody OrderCreateDTO dto) {
        String userType = AuthContext.getUserType();
        if ("customer".equals(userType)) {
            Long customerId = AuthContext.requireCustomerId();
            dto.setCustomerId(customerId);
        }
        OrderCreateResult result = orderService.createOrder(dto);
        return Result.success(result);
    }

    @PostMapping
    @RequireRole({"STATION_MANAGER", "DELIVERY"})
    public Result save(@RequestBody Orders orders) {
        orderService.save(orders);
        return Result.success(orders.getId());
    }

    @GetMapping
    public Result<List<Orders>> list(@RequestParam(required = false) Long stationId,
                                     @RequestParam(required = false) Long customerId,
                                     @RequestParam(required = false) Integer status,
                                     @RequestParam(required = false) String createTimeStart,
                                     @RequestParam(required = false) String createTimeEnd) {
        String userType = AuthContext.getUserType();
        if ("customer".equals(userType)) {
            Long cid = AuthContext.requireCustomerId();
            if (customerId == null || !customerId.equals(cid)) {
                customerId = cid;
            }
        } else if (AuthContext.isManager() || AuthContext.isDelivery()) {
            // #42: 站长/配送员只能看自己水站的订单，不允许传任意stationId
            Long myStationId = AuthContext.requireStationId();
            if (stationId == null || !stationId.equals(myStationId)) {
                stationId = myStationId;
            }
        }
        return Result.success(orderService.list(stationId, customerId, status, createTimeStart, createTimeEnd));
    }

    @GetMapping("/{id}")
    public Result<Orders> getById(@PathVariable Long id) {
        Orders order = orderService.getById(id);
        if (order == null) {
            return Result.error("订单不存在");
        }
        String userType = AuthContext.getUserType();
        if ("customer".equals(userType)) {
            Long cid = AuthContext.requireCustomerId();
            if (order.getCustomerId() == null || !order.getCustomerId().equals(cid)) {
                return Result.error("无权查看他人订单");
            }
        }
        return Result.success(order);
    }

    @PutMapping("/{id}/status")
    @RequireRole({"STATION_MANAGER", "DELIVERY"})
    public Result updateStatus(@PathVariable Long id, @RequestParam Integer status) {
        // #8: 校验status必须是合法的OrderStatus值
        if (status == null) {
            return Result.error("状态不能为空");
        }
        if (status != OrderStatus.PENDING && status != OrderStatus.DELIVERING
                && status != OrderStatus.DELIVERED && status != OrderStatus.COMPLETED
                && status != OrderStatus.CANCELLED) {
            return Result.error("无效的订单状态值: " + status);
        }
        orderService.updateStatus(id, status);
        return Result.success();
    }

    /**
     * 客户获取自己最近一笔订单的水站信息（用于重新登录后自动选站）
     */
    @GetMapping("/my-station")
    public Result<?> getMyLatestStation() {
        Long customerId = AuthContext.requireCustomerId();
        Map<String, Object> station = orderMapper.getLatestStationByCustomerId(customerId);
        return Result.success(station);
    }
}
