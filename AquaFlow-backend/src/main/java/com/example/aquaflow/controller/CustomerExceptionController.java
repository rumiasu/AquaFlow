package com.example.aquaflow.controller;

import com.example.aquaflow.common.Result;
import com.example.aquaflow.service.OrderBarrelExceptionService;
import com.example.aquaflow.service.OrderBarrelExceptionService.OrderBarrelExceptionDTO;
import com.example.aquaflow.util.AuthContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 客户端：我的桶异常记录。
 * <p>背景：用户端小程序 pages/exception/list 此前调用 GET /api/customer/exceptions，
 * 但后端从未提供该路由 —— 请求恒返回 HTTP 500「系统错误」，客户看不到任何异常处理进度。
 * 这里补上，并强制按登录态客户过滤，杜绝跨客户读取。</p>
 */
@RestController
@RequestMapping("/api/customer/exceptions")
public class CustomerExceptionController {

    @Autowired
    private OrderBarrelExceptionService exceptionService;

    /**
     * 我的异常记录（分页）。
     *
     * @param stationId 可选；为空表示不限水站
     * @param page      页码，从 1 开始
     * @param size      每页条数
     */
    @GetMapping
    public Result<OrderBarrelExceptionService.Page<OrderBarrelExceptionDTO>> myExceptions(
            @RequestParam(required = false) Long stationId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        // customerId 一律取自 JWT，不接受前端传入，避免越权读取他人异常记录
        Long customerId = AuthContext.requireCustomerId();
        return Result.success(exceptionService.listByCustomer(customerId, stationId, page, size));
    }

    /** 我的异常详情 */
    @GetMapping("/{id}")
    public Result<OrderBarrelExceptionDTO> myExceptionDetail(@PathVariable Long id) {
        Long customerId = AuthContext.requireCustomerId();
        OrderBarrelExceptionDTO dto = exceptionService.getById(id);
        if (dto == null || !customerId.equals(dto.getCustomerId())) {
            return Result.error("异常记录不存在或无权访问");
        }
        return Result.success(dto);
    }

    // [2026-09-18 删除] GET /list（"兼容：部分调用方按裸数组处理"）：与上面的分页端点返回同一批数据，
    // 属冗余读端点（docs/audit/2026-09-16-死端点评估.md 判"删除"，已执行）。调用方一律用
    // GET /api/customer/exceptions（分页）。回归：ManagerOrderControllerRemovedIntegrationTest 断言 404。
}
