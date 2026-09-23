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

    /** 只用于「手工发起异常单」时的订单归属校验（别在这里写业务）。 */
    @Autowired
    private com.example.aquaflow.mapper.OrderMapper orderMapper;

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

    /**
     * 【v60 接线】站长**手工发起**一条异常单。
     *
     * <p>在此之前异常单只能由「完成配送时少收空桶」自动生成（{@code recordReturn}），
     * 站长<b>没有任何手段</b>主动记一笔异常 —— 而"客户拒付"这类场景，
     * 桶可能一个不少地还回来了（差异为 0），自动生成那条路根本不会触发。
     * 服务方法 {@code recordException} 其实早就写好了，只是<b>全仓零调用</b>（后端就位、接口没接）。</p>
     *
     * <p>⚠️ 订单必须属于本站 —— 判据取服务端 {@code AuthContext} 的站别，
     * 不信任请求体里的 stationId。</p>
     *
     * @param orderId 目标订单；{@code body} 见 {@code ExceptionInput}（category 必填）
     */
    @PostMapping
    public Result<?> create(@RequestParam Long orderId,
                            @RequestBody OrderBarrelExceptionService.ExceptionInput body) {
        Long stationId = AuthContext.requireStationId();
        if (body == null || body.getCategory() == null || body.getCategory().isBlank()) {
            return Result.error("请选择异常类型");
        }
        // 归属校验：拿订单实体比对站别，避免用别站的 orderId 建单（跨租户写入面）
        com.example.aquaflow.entity.Orders order = orderMapper.getById(orderId);
        if (order == null || !stationId.equals(order.getStationId())) {
            return Result.error("订单不存在或不属于本水站");
        }
        return Result.success(exceptionService.recordException(orderId, body));
    }

    /**
     * 【v60 新增】拒付结案（核销认损）。
     *
     * <p>客户收了货但拒不付款时用它一次性收口：核销那笔收不回来的应收（不再计入站长「待收款」）、
     * 撤销该单送出但客户未归还的桶权益、把等量桶记成客户欠桶。
     * 这是「已送达不可取消」之后拒付订单<b>唯一</b>的出路。</p>
     *
     * <p>幂等由异常单状态 CAS 保证：已结案/已忽略的再点会报错，不会重复撤权益。</p>
     *
     * @param body {@code {managerNote}}，可空
     */
    @PostMapping("/{id}/write-off")
    public Result<Void> writeOff(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        Long stationId = AuthContext.requireStationId();
        OrderBarrelExceptionService.OrderBarrelExceptionDTO dto = exceptionService.getById(id);
        if (dto == null || !dto.getStationId().equals(stationId)) {
            return Result.error("异常不存在或无权访问");
        }
        Object note = body == null ? null : body.get("managerNote");
        exceptionService.writeOffForRefusal(id, note == null ? null : String.valueOf(note));
        return Result.success();
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