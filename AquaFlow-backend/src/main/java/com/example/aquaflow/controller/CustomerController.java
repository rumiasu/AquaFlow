package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.entity.Customer;
import com.example.aquaflow.entity.CustomerStationConfig;
import com.example.aquaflow.mapper.CustomerStationConfigMapper;
import com.example.aquaflow.service.CustomerService;
import com.example.aquaflow.dto.CustomerOfflinePaymentDTO;
import com.example.aquaflow.util.AuthContext;
import com.example.aquaflow.vo.CustomerProfileVO;
import jakarta.validation.Valid;
import com.example.aquaflow.vo.CustomerStationAssetVO;
import com.example.aquaflow.vo.CustomerStationVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 客户档案（<b>站长端</b>；除 {@code GET /stats} 外全部 {@code STATION_MANAGER}）。
 *
 * <p><b>⚠️ 本类里有一个"反向"端点</b>：{@code GET /api/customers/stats} 是<b>顾客</b>查自己的消费统计，
 * 无注解、靠 {@code requireCustomerId()} 兜身份 —— 与同前缀下其它端点的归属完全相反。
 * 给这个类加类级 {@code @RequireRole} 会顺手把顾客的统计接口也拦掉，改之前务必看清。</p>
 */
@RestController
@RequestMapping("/api/customers")
@Slf4j
public class CustomerController {

    @Autowired
    private CustomerService customerService;

    @Autowired
    private com.example.aquaflow.mapper.CustomerMapper customerMapper;

    @Autowired
    private CustomerStationConfigMapper customerStationConfigMapper;

    @RequireRole({"STATION_MANAGER"})
    @GetMapping
    public Result<List<CustomerStationVO>> list(@RequestParam(required = false) Long stationId) {
        if (AuthContext.isManager()) {
            stationId = AuthContext.requireStationId();
        }
        return Result.success(customerService.listStationCustomers(stationId));
    }

    @RequireRole({"STATION_MANAGER"})
    @PostMapping
    public Result save(@RequestBody Customer customer){
        customerService.save(customer);
        return Result.success();
    }

    @RequireRole({"STATION_MANAGER"})
    @GetMapping("/{id}")
    public Result<CustomerStationVO> getById(@PathVariable Long id){
        // #37: 校验客户属于当前站长的水站
        Long stationId = AuthContext.requireStationId();
        CustomerStationVO vo = customerService.getStationCustomerDetail(id, stationId);
        if (vo == null) {
            return Result.error("客户不存在或无权查看");
        }
        return Result.success(vo);
    }

    @RequireRole({"STATION_MANAGER"})
    @PutMapping("/{id}")
    public Result update(@PathVariable Long id, @RequestBody Customer customer){
        // #37: 校验客户属于当前站长的水站
        Customer existing = customerService.getById(id);
        if (existing == null) {
            return Result.error("客户不存在");
        }
        Long stationId = AuthContext.requireStationId();
        CustomerStationConfig config = customerStationConfigMapper.getByCustomerAndStation(id, stationId);
        if (config == null) {
            return Result.error("无权修改其他水站的客户");
        }
        customer.setId(id);
        customerService.update(customer);
        return Result.success();
    }

    @GetMapping("/stats")
    public Result<Map<String, Object>> getStats() {
        Long customerId = AuthContext.requireCustomerId();
        return Result.success(customerService.getCustomerStats(customerId));
    }

    /** 获取客户在当前站长水站的线下支付授权状态 */
    @RequireRole({"STATION_MANAGER"})
    @GetMapping("/{id}/offline-payment")
    public Result<CustomerStationConfig> getOfflinePaymentConfig(@PathVariable Long id) {
        Long stationId = AuthContext.requireStationId();
        return Result.success(customerService.getOfflinePaymentConfig(id, stationId));
    }

    /** 设置客户在当前站长水站的线下支付授权（仅当水站总开关开启时生效） */
    @RequireRole({"STATION_MANAGER"})
    @PutMapping("/{id}/offline-payment")
    public Result updateOfflinePaymentConfig(@PathVariable Long id, @RequestBody @Valid CustomerOfflinePaymentDTO dto) {
        Long stationId = AuthContext.requireStationId();
        Integer enabled = dto.getOfflinePaymentEnabled();
        customerService.updateOfflinePaymentConfig(id, stationId, enabled);
        return Result.success();
    }

    /**
     * 客户画像（站长视角）：聚合该客户在本站的消费、资产、履约与行为数据。
     * GET /api/customers/{id}/profile
     */
    @RequireRole({"STATION_MANAGER"})
    @GetMapping("/{id}/profile")
    public Result<CustomerProfileVO> getProfile(@PathVariable Long id) {
        Long stationId = AuthContext.requireStationId();
        CustomerProfileVO vo = customerService.getCustomerProfile(id, stationId);
        if (vo == null) {
            return Result.error("客户不存在或无权查看");
        }
        return Result.success(vo);
    }

    /**
     * 客户在本站的资产（水桶 / 水票 / 押金）。
     * GET /api/customers/{id}/assets
     *
     * <p>水站取自登录站长（{@code AuthContext.requireStationId()}），<b>不接受前端传入 stationId</b>；
     * 服务层再校验"该客户确实属于本站"，因此该接口只能查到该客户在本站的资产，
     * 不会串到客户在其他水站的水桶/水票/押金。</p>
     */
    @RequireRole({"STATION_MANAGER"})
    @GetMapping("/{id}/assets")
    public Result<CustomerStationAssetVO> getStationAssets(@PathVariable Long id) {
        Long stationId = AuthContext.requireStationId();
        CustomerStationAssetVO vo = customerService.getStationAssets(id, stationId);
        if (vo == null) {
            return Result.error("客户不存在或不属于本水站，无权查看");
        }
        return Result.success(vo);
    }
}