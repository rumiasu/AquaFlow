package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.constant.DepositType;
import com.example.aquaflow.dto.DepositDTO;
import com.example.aquaflow.entity.DepositRecord;
import com.example.aquaflow.mapper.CustomerMapper;
import com.example.aquaflow.mapper.CustomerStationConfigMapper;
import com.example.aquaflow.service.DepositRecordService;
import com.example.aquaflow.util.AuthContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * 押金流水。
 *
 * <p>按归属分两类，别一刀切：{@code GET /api/deposit-records} 是<b>顾客</b>查自己的
 * （无注解 + {@code requireCustomerId()}）；其余是<b>站长</b>查/记
 * （{@code STATION_MANAGER} + {@code @RequireStation}，忽略客户端传入的 stationId，杜绝跨站查询）。</p>
 *
 * <p>押金<b>余额</b>的唯一真相是 {@code customer_deposit_account}，本类只负责流水；
 * 对账等式「余额 == SUM(deposit_record.amount)」见 {@code ReconciliationService} 等式 1。</p>
 */
@RestController
@RequestMapping("/api/deposit-records")
@Slf4j
public class DepositRecordController {

    /** [AQ-004] 押金流水类型白名单：只允许业务上存在的类型，杜绝传非法 type 让余额不动 */
    private static final Set<Integer> ALLOWED_DEPOSIT_TYPES = Arrays.asList(
            DepositType.PURCHASE, DepositType.RETURN, DepositType.COMPENSATION_LOST,
            DepositType.ADJUSTMENT, DepositType.PREPAID, DepositType.RETURN_BARREL,
            DepositType.EXCEPTION_COMPENSATION, DepositType.CANCEL_PREPAID,
            // [2026-09-13] 新增 9=人工补录押金（余额增加）。此前站长要「给客户补一笔押金」
            // 没有任何正确的类型可用，只能借用 1/5，或误用方向相反的 7。
            DepositType.MANUAL_GRANT
    ).stream().collect(java.util.stream.Collectors.toSet());

    @Autowired
    private DepositRecordService depositRecordService;

    @Autowired
    private CustomerMapper customerMapper;

    @Autowired
    private CustomerStationConfigMapper customerStationConfigMapper;

    @GetMapping
    public Result<List<DepositRecord>> listByCustomerId(@RequestParam(required = false) Long stationId) {
        Long customerId = AuthContext.requireCustomerId();
        if (stationId == null) {
            return Result.error("请选择水站");
        }
        return Result.success(depositRecordService.listByCustomerAndStation(customerId, stationId));
    }

    @RequireRole({"STATION_MANAGER"})
    @com.example.aquaflow.annotation.RequireStation
    @GetMapping("/customer/{customerId}")
    public Result<List<DepositRecord>> listByCustomerIdForStaff(@PathVariable Long customerId, @RequestParam Long stationId) {
        // 强制使用登录站长所属水站，忽略客户端传入的 stationId，杜绝跨站查询
        Long myStationId = AuthContext.requireStationId();
        return Result.success(depositRecordService.listByCustomerAndStation(customerId, myStationId));
    }

    @RequireRole({"STATION_MANAGER"})
    @com.example.aquaflow.annotation.RequireStation
    @PostMapping
    public Result add(@RequestBody DepositDTO dto) {
        if (dto.getCustomerId() == null) {
            return Result.error("客户ID不能为空");
        }
        Long customerId = Long.valueOf(dto.getCustomerId());
        com.example.aquaflow.entity.Customer c = customerMapper.getById(customerId);
        if (c == null) return Result.error("客户不存在");

        // [AQ-004] 押金流水金额与类型必须服务端校验，不能采信客户端任意值。
        // decreaseBalance 用 `balance - #{amount}`，amount 传负数会反向放大余额。
        if (dto.getAmount() == null || dto.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            return Result.error("押金金额必须大于 0");
        }
        if (dto.getType() == null || !ALLOWED_DEPOSIT_TYPES.contains(dto.getType())) {
            return Result.error("非法的押金流水类型");
        }

        // [AQ-012 同质越权口] 客户必须归属当前登录站长的水站，禁止替他站客户记押金。
        Long myStationId = AuthContext.requireStationId();
        if (customerStationConfigMapper.getByCustomerAndStation(customerId, myStationId) == null) {
            return Result.error("该客户不属于本水站，无法记账");
        }

        DepositRecord record = new DepositRecord();
        record.setCustomerId(customerId);
        record.setType(dto.getType());
        record.setAmount(dto.getAmount());
        record.setNote(dto.getNote());
        // 押金记录归属强制绑定当前登录站长的水站，不采信客户端 stationId
        record.setStationId(myStationId);
        depositRecordService.add(record, myStationId);
        return Result.success();
    }
}