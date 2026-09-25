package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.constant.PrivilegeType;
import com.example.aquaflow.mapper.CustomerMapper;
import com.example.aquaflow.mapper.CustomerPrivilegeMapper;
import com.example.aquaflow.util.AuthContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户特权：站长端。2026-09-17 新增（v40）。规格见 {@code docs/design/20} §4。
 *
 * <p>产品决定（{@code docs/design/16} §D6）：不做个人/企业客户的显式区分，
 * 差异化一律落到「站长在客户画像里给特权」。已有先例是货到付款的客户级授权
 * （{@code customer_station_config.offline_payment_enabled}）—— 本控制器把那套模式一般化。</p>
 *
 * <p><b>站点取自 {@code AuthContext}</b>；客户必须归属本站 —— 否则站长能给别的站的客户开特权。
 * 归属判定走 {@code CustomerMapper.countCustomerOfStation}（绑定<b>或</b>订单，两者取并集）。</p>
 *
 * <p>⚠️ <b>归属判据不能用 {@code CustomerMapper.getStationCustomer}</b>：那是「客户画像」口径
 * （SQL 里带 {@code exists (select 1 from orders ...)}），会把没下过单的新客户一律判成"不属于本站"，
 * 而免起送门槛的典型场景恰恰就是新客户第一单。2026-09-17 实测该写法导致三个特权用例全红，
 * 详见 {@code countCustomerOfStation} 的注释。</p>
 *
 * <p>⚠️ <b>只允许授予已实现的类型</b>。未实现的（折扣率/免配送次数/允许退票）在
 * {@link #grant} 里直接拒绝并给出原因 —— 收下它们就等于给站长一个"看着开了、其实没用"的开关。
 * 这是本仓"悬空字段/空壳功能"教训的直接应用（{@link PrivilegeType#isImplemented}）。</p>
 */
@RestController
@RequestMapping("/api/manager/customers/{customerId}/privileges")
@RequireRole({"STATION_MANAGER"})
@Slf4j
public class ManagerCustomerPrivilegeController {

    @Autowired private CustomerPrivilegeMapper customerPrivilegeMapper;
    @Autowired private CustomerMapper customerMapper;

    /** 该客户在本站的全部特权 + 当前可授予的类型清单（前端据此渲染，不写死枚举）。 */
    @GetMapping
    public Result<Map<String, Object>> list(@PathVariable Long customerId) {
        Long stationId = AuthContext.requireStationId();
        String bad = ownershipError(customerId, stationId);
        if (bad != null) return Result.error(bad);

        List<Map<String, Object>> rows = customerPrivilegeMapper.listByCustomer(customerId, stationId);
        // 文案由后端下发，前端禁止自带映射表
        for (Map<String, Object> r : rows) {
            r.put("typeText", PrivilegeType.textOf(String.valueOf(r.get("type"))));
        }
        Map<String, Object> data = new HashMap<>();
        data.put("privileges", rows);
        // 可授予清单**只含已实现的类型** —— 未实现的连选项都不给，避免"选了却没反应"
        data.put("grantableTypes", List.of(Map.of(
                "type", PrivilegeType.NO_MIN_ORDER,
                "text", PrivilegeType.textOf(PrivilegeType.NO_MIN_ORDER),
                "desc", "该客户在本站下单不受起送量限制")));
        return Result.success(data);
    }

    /**
     * 授予特权（幂等 upsert：重复授予只更新备注）。
     *
     * @param body {@code {type, note}}；{@code type} 必须是已实现的类型
     */
    @PostMapping
    public Result<Void> grant(@PathVariable Long customerId, @RequestBody Map<String, Object> body) {
        Long stationId = AuthContext.requireStationId();
        String bad = ownershipError(customerId, stationId);
        if (bad != null) return Result.error(bad);

        String type = body.get("type") == null ? null : String.valueOf(body.get("type"));
        // 未知类型名（拼错）必须明确拒绝，不能静默存下一行永远不会被读到的特权
        if (!PrivilegeType.isKnown(type)) {
            return Result.error("未知的特权类型：" + type);
        }
        // ⚠️ 未实现的类型直接拒绝并说明原因 —— 收下就是"配了也不生效"
        if (!PrivilegeType.isImplemented(type)) {
            return Result.error(PrivilegeType.unimplementedReason(type));
        }
        String note = body.get("note") == null ? null : String.valueOf(body.get("note"));

        int affected = customerPrivilegeMapper.upsert(customerId, stationId, type, null, note,
                AuthContext.getUserId());
        log.info("[v40] 站长授予客户特权: stationId={}, customerId={}, type={}, affected={}",
                stationId, customerId, type, affected);
        return Result.success();
    }

    /**
     * 撤销特权。
     *
     * <p>本来就没有这项特权时返回业务错误，而不是无条件成功 ——
     * "删了却还在"（或反之）正是本仓 {@code AddressServiceImpl.delete} 踩过的坑（AGENTS §8.20）。</p>
     */
    @DeleteMapping("/{type}")
    public Result<Void> revoke(@PathVariable Long customerId, @PathVariable String type) {
        Long stationId = AuthContext.requireStationId();
        String bad = ownershipError(customerId, stationId);
        if (bad != null) return Result.error(bad);

        int affected = customerPrivilegeMapper.revoke(customerId, stationId, type);
        if (affected == 0) {
            return Result.error("该客户在本站没有这项特权");
        }
        return Result.success();
    }

    /**
     * 客户必须属于本站。
     *
     * @return 出错时的可读文案；{@code null} = 校验通过
     */
    private String ownershipError(Long customerId, Long stationId) {
        if (customerId == null) {
            return "customerId 不能为空";
        }
        if (customerMapper.getById(customerId) == null) {
            return "客户不存在";
        }
        // 归属判据 = 绑定 或 本站订单（并集）。**不要**换成 getStationCustomer：
        // 那是客户画像口径（要求有订单），会把"还没下过单的新客户"整个挡在门外。
        if (customerMapper.countCustomerOfStation(customerId, stationId) == 0) {
            return "该客户不属于本水站";
        }
        return null;
    }
}
