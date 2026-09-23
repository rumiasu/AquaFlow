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

    /**
     * 站长端客户列表。
     *
     * @param keyword 姓名 / 电话 / <b>地址</b>关键字（支持缩写「阳光81301」与中英数字混用「八栋/8栋」，
     *                口径见 {@code util/CustomerSearchMatcher}）；为空 = 全量。
     *                带关键字时按相关性降序、最多 50 条，命中项会带 {@code addressText}
     *                供界面显示"送到哪"（站长靠地址认人）。
     */
    @RequireRole({"STATION_MANAGER"})
    @GetMapping
    public Result<List<CustomerStationVO>> list(@RequestParam(required = false) Long stationId,
                                                @RequestParam(required = false) String keyword) {
        if (AuthContext.isManager()) {
            stationId = AuthContext.requireStationId();
        }
        return Result.success(customerService.listStationCustomers(stationId, keyword));
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

    /**
     * 获取客户在当前站长水站的线下支付授权状态。
     *
     * <p>⚠️ [2026-09-18 修] <b>归属校验是必须的</b>。本方法此前只有 {@code @RequireRole}、
     * 没有任何"这客户是不是本站的"校验，而它（经 {@code CustomerServiceImpl.getOfflinePaymentConfig}）
     * 会 {@code ensureExists} —— <b>给任意 customerId 凭空写一行 {@code customer_station_config} 绑定</b>。
     * 这构成一条完整的跨站越权链（判据错在"用一次查询顺手把归属关系创建出来"）：</p>
     * <ol>
     *   <li>A 站的客户（只在 A 站下过单、与 B 站毫无关系）被 B 站站长拿 id 调一次本端点
     *       → B 站凭空多出一行绑定；</li>
     *   <li>该绑定正是 {@code PUT /api/customers/{id}}（{@link #update}）的判据
     *       —— B 站接着就能改 A 站客户的档案（姓名/电话/备注/标签）；</li>
     *   <li>它还成了"按 id 探测客户是否存在"的探针（任何 id 都返回成功 + 一行配置）。</li>
     * </ol>
     *
     * <p>判据用 {@code CustomerMapper.countCustomerOfStation}（<b>绑定 ∪ 本站订单</b>），
     * <b>不要</b>换成画像口径 {@code getStationCustomer} —— 后者要求"有本站订单"，
     * 会把"已绑定但还没下过单"的客户挡掉（AGENTS §1.1 已把这个坑记了四次）。</p>
     *
     * <p>客服两端同一条错误文案：不区分"不存在"与"不属于本站"，避免把 id 空间变成可枚举的探针。</p>
     */
    @RequireRole({"STATION_MANAGER"})
    @GetMapping("/{id}/offline-payment")
    public Result<CustomerStationConfig> getOfflinePaymentConfig(@PathVariable Long id) {
        Long stationId = AuthContext.requireStationId();
        // 先判归属再 ensureExists：顺序反了就等于"先建绑定、再问归属"，校验恒真
        if (customerMapper.countCustomerOfStation(id, stationId) == 0) {
            return Result.error("客户不存在或不属于本水站，无权查看");
        }
        return Result.success(customerService.getOfflinePaymentConfig(id, stationId));
    }

    /**
     * 设置客户在当前站长水站的线下支付授权（仅当水站总开关开启时生效）。
     *
     * <p>[2026-09-18] 与 {@link #getOfflinePaymentConfig} 同一道闸门：这里同样会
     * {@code ensureExists} 建绑定行，缺了校验就能拿任意 id 给本站"认领"一个别站客户。</p>
     *
     */
    @RequireRole({"STATION_MANAGER"})
    @PutMapping("/{id}/offline-payment")
    public Result updateOfflinePaymentConfig(@PathVariable Long id, @RequestBody @Valid CustomerOfflinePaymentDTO dto) {
        Long stationId = AuthContext.requireStationId();
        if (customerMapper.countCustomerOfStation(id, stationId) == 0) {
            return Result.error("客户不存在或不属于本水站，无权操作");
        }
        Integer enabled = dto.getOfflinePaymentEnabled();
        customerService.updateOfflinePaymentConfig(id, stationId, enabled);
        return Result.success();
    }

    /**
     * 开通货到付款时弹窗要用的依据（v48）：当前配置 + 该客户在本站的欠款/逾期 + 历史订单数 +
     * 此刻能不能用（不能用时给出**后端唯一判据**产出的原因）。
     *
     * <p>产品口径：「在设置是否允许货到付款时就给弹出来」—— 站长要在按下开关的那一刻看到
     * "这个客户欠着多少、为什么现在还用不了"，而不是开通完再被下单拒绝。</p>
     */
    @RequireRole({"STATION_MANAGER"})
    @GetMapping("/{id}/offline-payment/summary")
    public Result<java.util.Map<String, Object>> offlinePaymentSummary(@PathVariable Long id) {
        Long stationId = AuthContext.requireStationId();
        if (customerMapper.countCustomerOfStation(id, stationId) == 0) {
            return Result.error("客户不存在或不属于本水站，无权查看");
        }
        return Result.success(customerService.offlinePaymentSummary(id, stationId));
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