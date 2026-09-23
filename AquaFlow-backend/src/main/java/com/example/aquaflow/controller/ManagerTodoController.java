package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.entity.StaffPayroll;
import com.example.aquaflow.mapper.GrossProfitMapper;
import com.example.aquaflow.mapper.StaffPayrollMapper;
import com.example.aquaflow.service.OrderBarrelExceptionService;
import com.example.aquaflow.service.ReceivableService;
import com.example.aquaflow.util.AuthContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 站长首页「待办聚合」：把"今天该处理什么"合成一次请求。2026-09-18 新增（规格见 {@code docs/design/20} §7）。
 *
 * <p>只读、不写任何业务表，也<b>不新造任何金额口径</b> —— 四件事全部复用各自模块已有的读数入口：
 * 逾期应收走 {@code ReceivableService.overview}（它本身也是"明细累加，不另写聚合 SQL"）、
 * 未填成本走 {@code GrossProfitMapper.listMissingCost}、待确认结算单走结算单的状态计数、
 * 待处理桶异常走 {@code OrderBarrelExceptionService.listExceptions} 的同一个状态过滤。
 * 这里若自己再写一遍 SQL，就会出现"首页说有 3 笔逾期、点进去有 5 笔"这种最难解释的不一致
 * （本站已经在计价上踩过同形的"双轨"事故，见 {@code util/PriceUtil} 文件头）。</p>
 *
 * <p>⚠️ 站点一律取自 {@code AuthContext.requireStationId()}，不接受请求参数里的 siteId ——
 * 待办里含本站的欠款与人事数据，跨站可见等于把经营底细漏给同行。</p>
 *
 * <p>⚠️ <b>不给"待办总数"这一个数字</b>：四件事的单位不同（客户 / 商品 / 张 / 单），
 * 相加得到的数没有任何业务含义，只会让站长以为"有 10 件事"。给 {@code hasAny} 供角标用就够了。</p>
 */
@RestController
@RequestMapping("/api/manager/todo-summary")
@RequireRole({"STATION_MANAGER"})
public class ManagerTodoController {

    /**
     * 桶异常里「配送员已录入、等站长处置」的状态。
     *
     * <p>字面量来自 {@code entity/OrderBarrelException} 的状态约定（{@code STAFF_RECORDED} = 待处理），
     * 那里没有常量类，所以这里用字符串并指向它 —— 改状态名时要一起改。</p>
     */
    private static final String EXCEPTION_PENDING = "STAFF_RECORDED";

    private static final String SCOPE_NOTE =
            "逾期应收 = 已过应付日期且仍未收款的挂账（点进去是应收账款台账）；"
                    + "未填成本 = 上架商品里还没填进货成本的数量（不填就算不出毛利）；"
                    + "待确认结算单 = 已生成但还没确认的配送员工资单；"
                    + "待处理桶异常 = 配送员已录入、等站长处置的回桶差异。";

    @Autowired
    private ReceivableService receivableService;

    @Autowired
    private GrossProfitMapper grossProfitMapper;

    @Autowired
    private StaffPayrollMapper staffPayrollMapper;

    @Autowired
    private OrderBarrelExceptionService orderBarrelExceptionService;

    /** 四类待办的当前数量（每项带 key / label / count，逾期那一项另带金额）。 */
    @GetMapping
    public Result<Map<String, Object>> summary() {
        Long stationId = AuthContext.requireStationId();

        Map<String, Object> overdueOverview = receivableService.overview(stationId);
        int overdueCustomers = intOf(overdueOverview.get("overdueCustomerCount"));
        BigDecimal overdueAmount = decOf(overdueOverview.get("overdueAmount"));

        int missingCost = grossProfitMapper.listMissingCost(stationId).size();
        int draftPayrolls = staffPayrollMapper.countByStatus(stationId, StaffPayroll.Status.DRAFT);

        OrderBarrelExceptionService.ExceptionQuery q = new OrderBarrelExceptionService.ExceptionQuery();
        q.setStatus(EXCEPTION_PENDING);
        q.setPage(1);
        q.setSize(1);   // 只要 total，不要明细
        int pendingExceptions = (int) orderBarrelExceptionService.listExceptions(stationId, q).getTotal();

        List<Map<String, Object>> items = new ArrayList<>();
        // key 是稳定标识，前端据此决定点进去是哪个页面（路由是前端的事，后端不认识小程序路径）；
        // label 由后端下发，前端不再自带一份中文表。
        items.add(item("overdueReceivable", "逾期应收", overdueCustomers, overdueAmount));
        items.add(item("costNotFilled", "未填成本", missingCost, null));
        items.add(item("draftPayroll", "待确认结算单", draftPayrolls, null));
        items.add(item("pendingBarrelException", "待处理桶异常", pendingExceptions, null));

        boolean hasAny = items.stream().anyMatch(i -> intOf(i.get("count")) > 0);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items);
        data.put("hasAny", hasAny);
        data.put("scopeNote", SCOPE_NOTE);
        return Result.success(data);
    }

    private static Map<String, Object> item(String key, String label, int count, BigDecimal amount) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("key", key);
        row.put("label", label);
        row.put("count", count);
        // 金额只给"有金额含义"的那一项，其余为 null —— 前端不必猜哪一项该显示钱
        row.put("amount", amount);
        return row;
    }

    private static int intOf(Object v) {
        if (v == null) {
            return 0;
        }
        if (v instanceof BigDecimal bd) {
            return bd.intValue();
        }
        return ((Number) v).intValue();
    }

    private static BigDecimal decOf(Object v) {
        if (v == null) {
            return BigDecimal.ZERO;
        }
        if (v instanceof BigDecimal bd) {
            return bd;
        }
        return new BigDecimal(v.toString());
    }
}
