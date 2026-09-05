package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.service.OrderBarrelExceptionService;
import com.example.aquaflow.service.StationExceptionConfigService;
import com.example.aquaflow.util.AuthContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

/**
 * 站长端：桶异常管理 API
 */
@RestController
@RequestMapping("/api/manager/exceptions")
@RequireRole("STATION_MANAGER")
public class ManagerExceptionController {

    @Autowired
    private OrderBarrelExceptionService exceptionService;

    @Autowired
    private StationExceptionConfigService configService;

    /** 异常列表（分页筛选） */
    @GetMapping
    public Result<?> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Long staffId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        Long stationId = AuthContext.requireStationId();
        OrderBarrelExceptionService.ExceptionQuery query = new OrderBarrelExceptionService.ExceptionQuery();
        query.setStatus(status);
        query.setCategory(category);
        query.setStaffId(staffId);
        query.setPage(page);
        query.setSize(size);
        return Result.success(exceptionService.listExceptions(stationId, query));
    }

    /** 异常详情 */
    @GetMapping("/{id}")
    public Result<?> getDetail(@PathVariable Long id) {
        Long stationId = AuthContext.requireStationId();
        OrderBarrelExceptionService.OrderBarrelExceptionDTO dto = exceptionService.getById(id);
        if (dto == null || !dto.getStationId().equals(stationId)) {
            return Result.error("异常不存在或无权访问");
        }
        return Result.success(dto);
    }

    /** 站长处理异常 */
    @PostMapping("/{id}/handle")
    public Result<Void> handle(
            @PathVariable Long id,
            @RequestBody OrderBarrelExceptionService.HandleInput input
    ) {
        Long stationId = AuthContext.requireStationId();
        OrderBarrelExceptionService.OrderBarrelExceptionDTO dto = exceptionService.getById(id);
        if (dto == null || !dto.getStationId().equals(stationId)) {
            return Result.error("异常不存在或无权访问");
        }
        exceptionService.handleException(id, input);
        return Result.success();
    }

    /** 执行补偿 */
    @PostMapping("/{id}/execute")
    public Result<Void> execute(@PathVariable Long id) {
        Long stationId = AuthContext.requireStationId();
        OrderBarrelExceptionService.OrderBarrelExceptionDTO dto = exceptionService.getById(id);
        if (dto == null || !dto.getStationId().equals(stationId)) {
            return Result.error("异常不存在或无权访问");
        }
        exceptionService.executeCompensation(id);
        return Result.success();
    }

    /** 异常统计看板 */
    @GetMapping("/stats")
    public Result<?> stats(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate
    ) {
        Long stationId = AuthContext.requireStationId();
        LocalDate start = startDate != null ? LocalDate.parse(startDate) : LocalDate.now().minusDays(30);
        LocalDate end = endDate != null ? LocalDate.parse(endDate) : LocalDate.now();
        return Result.success(exceptionService.getStats(stationId, start, end));
    }

    /** 获取站点异常处理配置 */
    @GetMapping("/config")
    public Result<?> getConfig() {
        Long stationId = AuthContext.requireStationId();
        return Result.success(configService.getConfig(stationId));
    }

    /** 更新站点异常处理配置 */
    @PutMapping("/config")
    public Result<Void> updateConfig(@RequestBody Map<String, Object> config) {
        Long stationId = AuthContext.requireStationId();
        configService.updateConfig(stationId, config);
        return Result.success();
    }
}