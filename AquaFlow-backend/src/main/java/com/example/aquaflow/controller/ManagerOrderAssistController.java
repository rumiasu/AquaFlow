package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.dto.PaymentQuoteDTO;
import com.example.aquaflow.dto.PaymentQuoteItemDTO;
import com.example.aquaflow.entity.Address;
import com.example.aquaflow.mapper.AddressMapper;
import com.example.aquaflow.mapper.CustomerMapper;
import com.example.aquaflow.service.CustomerService;
import com.example.aquaflow.service.PaymentService;
import com.example.aquaflow.util.AuthContext;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 代客下单：站长端的三个"辅助读"接口。2026-09-17 新增（规格见 {@code docs/design/20} §5）。
 *
 * <p><b>为什么只做"辅助读"，不做建单端点</b>：真正的建单入口是 {@code POST /api/orders/create}
 * —— 它本来就支持员工代客下单（{@code OrderController} 只在 {@code userType == customer} 时
 * 强覆盖 customerId），并且已经带了「客户确属本水站」「地址必须属于该客户」两道护栏。
 * **再写一个建单端点就会重演"计价双轨"的同形风险**（两条路径算金额），所以本类只补
 * 页面必须、而顾客侧接口给不了的三样东西：</p>
 * <ol>
 *   <li><b>客户选择器</b>：{@code GET /api/customers} 是 orders 驱动（客户画像口径），
 *       <b>没下过单的新客户查不出来</b>；</li>
 *   <li><b>客户的地址</b>：{@code GET /api/addresses} 是<b>顾客自助</b>端点
 *       （身份取自 {@code AuthContext.requireCustomerId()}），站长调用只会看到自己的地址；</li>
 *   <li><b>报价</b>：{@code POST /api/payments/quote} 同样要求顾客身份，站长调不到 ——
 *       而电话叫水时"这单多少钱"恰恰是站长必须当场回答的问题。</li>
 * </ol>
 *
 * <p>站点一律取自 {@code AuthContext}，<b>不接受请求参数里的 stationId</b>
 * （AGENTS §6：跨站校验以服务端刷新的 stationId 为准）；每个接口先校验目标客户属本站。</p>
 *
 * <p>⚠️ 报价走的是 {@code PaymentService.quote} —— <b>与顾客端下单、代客下单完全是同一个实现</b>
 * （含配送计费的 {@code calcForOrder}）。本类不允许内联任何金额算法。</p>
 */
@RestController
@RequestMapping("/api/manager/order-assist")
@RequireRole({"STATION_MANAGER"})
@Slf4j
public class ManagerOrderAssistController {

    @Autowired private CustomerMapper customerMapper;
    @Autowired private AddressMapper addressMapper;
    @Autowired private PaymentService paymentService;
    @Autowired private CustomerService customerService;

    /**
     * 客户选择器：按姓名 / 电话 / <b>地址</b>片段搜本站客户（含"已绑定但还没下过单"的新客户）。
     *
     * <p>搜索实现与 {@code GET /api/customers}（客户列表）<b>完全同一个</b>：
     * {@code CustomerService.searchStationCustomers} + {@code util/CustomerSearchMatcher}
     * （归一化 + 相关性打分）。这里<b>不允许</b>再写一份 LIKE 或打分 —— 两条路径各算一套
     * 正是本仓计价双轨事故的同形风险。</p>
     *
     * <p>产品口径（2026-09-18）：站长搜客户更看重<b>地址</b>（"地址其实更能指代人"），
     * 习惯缩写（「阳光81301」=「阳光小区8栋1单元301」）且中文/阿拉伯数字混用，
     * 所以返回项带 {@code addressText} 供界面显示"送到哪"。</p>
     *
     * @param keyword 姓名 / 电话 / 地址片段；为空则返回最近建档的若干条
     */
    @GetMapping("/customers")
    public Result<List<Map<String, Object>>> customers(@RequestParam(required = false) String keyword) {
        Long stationId = AuthContext.requireStationId();
        return Result.success(customerService.searchStationCustomers(stationId, keyword));
    }

    /**
     * 某客户在本站的收货地址（代客下单第二步用）。
     *
     * <p>先校验客户属本站，再按 customer_id 取地址 —— 归属判定与建单时的
     * 「地址必须属于当前客户」护栏同源，这里提前挡住可以少一次失败提交。</p>
     */
    @GetMapping("/customers/{customerId}/addresses")
    public Result<List<Address>> addresses(@PathVariable Long customerId) {
        String bad = ownershipError(customerId);
        if (bad != null) return Result.error(bad);
        return Result.success(addressMapper.list(customerId, null));
    }

    /**
     * 代客下单前的服务端试算（站长当场报给客户）。
     *
     * <p>请求体直接复用顾客端的 {@link PaymentQuoteDTO} —— <b>同一份契约、同一个实现</b>。
     * {@code customerId} 走查询参数而<b>不</b>加进 {@code PaymentQuoteDTO}：那个 DTO 是顾客端
     * {@code POST /api/payments/quote} 共用的，一旦它带上 customerId，将来有人顺手在顾客端
     * 改成"用它指定的 customerId"就是一次越权（顾客能替别人报价/占用别人的水票押金口径）。
     * 顾客端恒用 {@code AuthContext}，站长端才需要显式指定客户。</p>
     *
     * <p>另外 {@code stationId} 会被本站覆盖（不信任请求参数）。</p>
     *
     * <p>⚠️ 试算结果只用于展示：真正的金额在下单时由服务端重算，客户端传的金额一律不可信。</p>
     */
    @PostMapping("/quote")
    public Result<Map<String, Object>> quote(@RequestParam Long customerId,
                                             @RequestBody @Valid PaymentQuoteDTO dto) {
        Long stationId = AuthContext.requireStationId();
        String bad = ownershipError(customerId);
        if (bad != null) return Result.error(bad);

        // 地址必须属于该客户（与 OrderServiceImpl 建单时的同一条护栏），
        // 否则楼层费/远程费会按别人的地址算出来
        if (dto.getAddressId() != null) {
            Address addr = addressMapper.getById(dto.getAddressId());
            if (addr == null) {
                return Result.error("地址不存在");
            }
            if (!customerId.equals(addr.getCustomerId())) {
                return Result.error("该地址不属于当前客户");
            }
        }

        List<Map<String, Object>> itemMaps = new ArrayList<>();
        if (dto.getItems() != null) {
            for (PaymentQuoteItemDTO i : dto.getItems()) {
                Map<String, Object> m = new HashMap<>();
                m.put("productId", i.getProductId());
                m.put("quantity", i.getQuantity());
                itemMaps.add(m);
            }
        }
        // 与顾客端 quote / 下单走同一个实现，不在此内联任何金额算法
        return Result.success(paymentService.quote(customerId, stationId, dto.getPaymentMethod(),
                itemMaps, dto.getAddressId()));
    }

    /**
     * 客户必须归属本站。
     *
     * <p>判据走 {@code countCustomerOfStation}（绑定 <b>或</b> 本站订单，取并集）——
     * <b>不要</b>换成只查绑定行的 {@code customerStationConfigMapper.getByCustomerAndStation}，
     * 也不要换成客户画像口径的 {@code getStationCustomer}（它要求有订单，会把新客户挡掉）。
     * 三种口径的差异在 2026-09-17 已经踩过两次，详见
     * {@code CustomerMapper.countCustomerOfStation} 的注释。</p>
     */
    private String ownershipError(Long customerId) {
        if (customerId == null) {
            return "请先选择客户";
        }
        if (customerMapper.getById(customerId) == null) {
            return "客户不存在";
        }
        if (customerMapper.countCustomerOfStation(customerId, AuthContext.requireStationId()) == 0) {
            return "该客户不属于本水站";
        }
        return null;
    }
}
