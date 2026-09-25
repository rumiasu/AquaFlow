package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.service.BarrelService;
import com.example.aquaflow.util.AuthContext;
import com.example.aquaflow.vo.OwedBarrelVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 站长端「欠桶台账」：谁欠桶、欠几个、欠了几天、欠的是哪个桶型。
 *
 * <p><b>只预警、不拦截。</b>本接口不做任何风控，且<b>下单链路也已不再有任何欠桶拦截</b>：
 * 原 [AQ-030] 的「欠桶 ≥5 拒绝下单」硬拦已于 2026-09-15 按产品决定移除（见
 * {@code OrderServiceImpl} 文件头注释），欠桶只以 {@code OrderCreateResult.warnings}
 * 的形式在下单响应里提醒客户。分工是：台账负责"让人看见并去回收"，下单提醒负责"让客户自己知道"。</p>
 *
 * <p>明细下钻：某一行"是哪一单欠的、差几个、处理到哪一步"去查现成的异常单
 * （{@code order_barrel_exception} 中 {@code discrepancy > 0} 的记录，站长端异常列表接口）。
 * 台账里的数量是<b>当前净额</b>（已被回收的部分不在了），两者不可相加。</p>
 *
 * <p>权限：类级 {@code @RequireRole("STATION_MANAGER")}；水站取自 {@code AuthContext}，
 * 不信任请求参数（传了 stationId 也没用）。</p>
 */
@RestController
@RequestMapping("/api/manager/owed-barrels")
@RequireRole("STATION_MANAGER")
public class ManagerOwedBarrelController {

    @Autowired
    private BarrelService barrelService;

    /**
     * 本站当前仍欠桶的客户列表（按欠得最久排序）。
     *
     * @param minDays 只返回欠桶天数 ≥ 该值的行；默认 0 = 不过滤（谁欠都列出来）
     */
    @GetMapping
    public Result<List<OwedBarrelVO>> list(@RequestParam(required = false, defaultValue = "0") Integer minDays) {
        return Result.success(barrelService.listOwedCustomers(AuthContext.requireStationId(), minDays));
    }
}
