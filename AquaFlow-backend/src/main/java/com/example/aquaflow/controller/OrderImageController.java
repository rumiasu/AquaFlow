package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.entity.OrderImage;
import com.example.aquaflow.entity.Orders;
import com.example.aquaflow.mapper.OrderMapper;
import com.example.aquaflow.entity.Customer;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.CustomerMapper;
import com.example.aquaflow.service.OrderImageService;
import com.example.aquaflow.util.AuthContext;
import com.example.aquaflow.util.CosUtil;
import com.example.aquaflow.util.StationUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import lombok.extern.slf4j.Slf4j;
import java.io.IOException;
import java.util.List;

@Slf4j
/**
 * 订单图片（配送 / 退货凭证）。上传与按订单查询对<b>三方角色</b>开放：
 * {@code STATION_MANAGER} / {@code DELIVERY} / {@code customer}。
 *
 * <p>三方都需要它：配送员上传送达/异常凭证、站长查看凭证、顾客查自己订单的图。
 * 注意顾客角色的字面量在本项目里写作小写 {@code "customer"}（与 {@code AuthContext.getUserType()} 一致），
 * 员工角色则是大写下划线风格 —— 新增端点时请照此填写，别自创写法。</p>
 */
@RestController
@RequestMapping("/api/order-images")
public class OrderImageController {

    @Autowired
    private OrderImageService orderImageService;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private CustomerMapper customerMapper;

    @Autowired
    private CosUtil cosUtil;

    /**
     * 校验订单归属：站长只能操作本站订单，配送员只能操作自己有权限的订单，客户只能操作自己的订单
     */
    private Result<Void> checkOrderOwnership(Orders order) {
        if (order == null) return Result.error("订单不存在");
        if (AuthContext.isManager()) {
            Long stationId = StationUtil.deliveryStation(order);
            if (!AuthContext.requireStationId().equals(stationId)) {
                return Result.error("无权操作他站订单");
            }
        } else if (AuthContext.isDelivery()) {
            if (order.getDeliveryStaffId() == null || !order.getDeliveryStaffId().equals(AuthContext.getUserId())) {
                return Result.error("该订单不属于当前配送员");
            }
        } else if ("customer".equals(AuthContext.getUserType())) {
            if (order.getCustomerId() == null || !order.getCustomerId().equals(AuthContext.requireCustomerId())) {
                return Result.error("无权操作他人订单");
            }
        } else {
            return Result.error("权限不足");
        }
        return null;
    }

    @RequireRole({"STATION_MANAGER", "DELIVERY", "customer"})
    @PostMapping("/upload")
    public Result<OrderImage> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("orderId") Long orderId,
            @RequestParam(value = "type", defaultValue = "1") Integer type) {
        Orders order = orderMapper.getById(orderId);
        Result<Void> check = checkOrderOwnership(order);
        if (check != null) return Result.error(check.getMessage());

        try {
            String objectName = orderImageService.uploadImage(orderId, type, file.getBytes(), file.getOriginalFilename());
            // 查询刚插入的记录并注入 URL
            List<OrderImage> images = orderImageService.getByOrderId(orderId);
            OrderImage uploaded = images.stream()
                    .filter(img -> img.getObjectName().equals(objectName))
                    .findFirst().orElse(null);
            if (uploaded != null) {
                uploaded.setUrl(cosUtil.generatePrivateUrl(objectName));
            }
            return Result.success(uploaded);
        } catch (IOException e) {
            return Result.error("图片上传失败");
        } catch (BusinessException e) {
            // 业务拒绝（如订单状态不允许传图）必须原样冒泡，别被下面的兜底吞成"上传失败"
            throw e;
        } catch (Exception e) {
            // [2026-09-16] 对象存储未配置/不可用时 CosClient 抛的是运行时异常，原先会一路冒到
            // GlobalExceptionHandler → code=500（系统故障 + 一条 SYSTEM 告警）。
            // 上传失败是可预期的运维状态，应给业务错误让用户看懂；记录一条 ERROR 便于运维定位。
            log.error("[OrderImage] 图片上传失败: orderId={}, error={}", orderId, e.getMessage(), e);
            return Result.error("图片上传失败");
        }
    }

    @RequireRole({"STATION_MANAGER", "DELIVERY", "customer"})
    @GetMapping("/by-order/{orderId}")
    public Result<List<OrderImage>> getByOrderId(@PathVariable Long orderId) {
        Orders order = orderMapper.getById(orderId);
        Result<Void> check = checkOrderOwnership(order);
        if (check != null) return Result.error(check.getMessage());

        List<OrderImage> list = orderImageService.getByOrderId(orderId);
        // 为每条记录注入临时访问 URL（私有，1h 有效）
        for (OrderImage img : list) {
            try {
                img.setUrl(cosUtil.generatePrivateUrl(img.getObjectName()));
            } catch (Exception e) {
                log.warn("生成订单图片URL失败, objectName={}, error={}", img.getObjectName(), e.getMessage());
            }
        }
        return Result.success(list);
    }
}
