package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.annotation.RequireStation;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.constant.StationOperatingStatus;
import com.example.aquaflow.entity.Station;
import com.example.aquaflow.mapper.StationMapper;
import com.example.aquaflow.service.impl.StationSetupGuideService;
import com.example.aquaflow.util.AuthContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 水站<b>营业状态</b>（软状态）+ 站长留言（<b>站长专属</b>）。
 *
 * <p>产品口径（2026-09-17）：「正常运营、休息…」，<b>不阻断下单</b> ——
 * 顾客照常下单，只是会在商城/下单页看到横幅、下单响应里带 {@code warnings} 提示。
 * 需要"真的不接单"时用的是另一套东西：{@code station.status = 2 停业}（硬开关，下单直接被拒）。</p>
 *
 * <p>顾客侧读同一份数据走 <b>公开</b>端点 {@code GET /api/stations/&#123;id&#125;/status}；
 * 站点列表 {@code /api/stations/public} 也已带上这三个字段（实体自动带出）。</p>
 *
 * <p>水站一律由后端按登录态判定（{@link AuthContext#requireStationId()}），不信任请求参数；
 * 更新走 {@code updateOperatingStatus} 并校验受影响行数（整行覆盖的坑见 StationMapper 注释）。</p>
 */
@RestController
@RequestMapping("/api/manager/station-status")
@RequireRole("STATION_MANAGER")
@RequireStation
public class ManagerStationStatusController {

    /** 留言长度上限（与 station.status_note varchar(100) 对齐） */
    private static final int NOTE_MAX = 100;

    @Autowired
    private StationMapper stationMapper;

    /**
     * 「转正」门槛的唯一判据来源（2026-09-24）。
     *
     * <p>⚠️ 转正要求"全部填完"，判据必须取 {@link StationSetupGuideService#guide} 的
     * {@code pendingCount} —— **不要**在这里另写一套"差哪几项"的 SQL：本仓已因为
     * 两处口径分叉踩过多次（见 AGENTS §1.1「计价双轨」）。</p>
     */
    @Autowired
    private StationSetupGuideService stationSetupGuideService;

    /** 读本站营业状态（站长端设置页用）。 */
    @GetMapping
    public Result<Map<String, Object>> get() {
        Station station = stationMapper.getById(AuthContext.requireStationId());
        if (station == null) {
            return Result.error("水站不存在");
        }
        return Result.success(toView(station));
    }

    /**
     * 设置营业状态与留言。
     *
     * @param body {@code {"operatingStatus":2,"note":"今天休息，明早正常送"}}
     */
    @PutMapping
    public Result<Map<String, Object>> update(@RequestBody Map<String, Object> body) {
        Integer status = parseStatus(body.get("operatingStatus"));
        if (!StationOperatingStatus.isSelectable(status)) {
            // ⚠️ 取值清单从枚举取，别在这里手写一份 —— 改枚举（如 2026-09-23 把 3 从「配送延迟」
            //    改成「待上线」）时必然会漏改，而错误提示恰恰是站长唯一能看到的"合法取值说明书"。
            //    ⚠️ 用 isSelectable 而不是 isValid：3「待上线」是**系统状态**，不是可选项
            //    （2026-09-24 产品裁定：只有刚注册的站才可能是它，站长不能自己设）。
            return Result.error("营业状态取值非法（" + StationOperatingStatus.selectableText() + "）");
        }
        String note = body.get("note") == null ? null : String.valueOf(body.get("note")).trim();
        if (note != null && note.length() > NOTE_MAX) {
            return Result.error("留言不能超过 " + NOTE_MAX + " 字");
        }
        if (note != null && note.isEmpty()) {
            note = null;
        }
        Long stationId = AuthContext.requireStationId();
        Station current = stationMapper.getById(stationId);
        if (current == null) {
            return Result.error("保存失败：水站不存在");
        }

        // ---- 单向门（2026-09-24 产品裁定，两条判据缺一不可）----
        // ① **回不去**：待上线只可能由"注册"产生，站长不能把已在营业的站改回待上线
        //    （否则等同于用状态开关把自家站从客户视野里摘掉 / 再摘回来）。
        if (StationOperatingStatus.isPendingLaunch(status)) {
            return Result.error("「待上线」是系统状态，不能手动设置");
        }
        // ② **转正要配齐**：还在待上线时，唯一的去处是「正常运营」，且必须等必填项全配好。
        if (StationOperatingStatus.isPendingLaunch(current.getOperatingStatus())) {
            // ⚠️ NORMAL 是 int 基本类型，写 `NORMAL.equals(status)` 编译不过；直接比数值
            if (status == null || status.intValue() != StationOperatingStatus.NORMAL) {
                return Result.error("水站还在待上线，配齐资料后才能开始营业");
            }
            Map<String, Object> guide = stationSetupGuideService.guide(stationId);
            // ⚠️ **只卡必填（P0）**，不卡建议/可选（2026-09-24 产品裁定："水票不该强制"）。
            //    用 p0PendingCount 而不是 pendingCount：后者含 P1/P2（水票档位 / 工资结构 / 营业公告），
            //    那些"配了更好、不配也能开张"，拿它们挡上线等于把建议项变成硬门槛。
            int pending = guide.get("p0PendingCount") instanceof Number
                    ? ((Number) guide.get("p0PendingCount")).intValue() : 0;
            if (pending > 0) {
                // 说清"还差几项"而不是一句"不能设置" —— 站长据此才知道去哪儿补
                return Result.error("还有 " + pending + " 项必填资料没配好，配齐后才能开始营业（营业状态小框里能看到是哪几项）");
            }
        }

        int affected = stationMapper.updateOperatingStatus(stationId, status, note);
        if (affected == 0) {
            return Result.error("保存失败：水站不存在");
        }
        return Result.success(toView(stationMapper.getById(stationId)));
    }

    private static Integer parseStatus(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Number) return ((Number) raw).intValue();
        try {
            return Integer.parseInt(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Map<String, Object> toView(Station station) {
        Map<String, Object> data = new HashMap<>();
        data.put("stationId", station.getId());
        data.put("stationName", station.getName());
        data.put("operatingStatus", station.getOperatingStatus() == null
                ? StationOperatingStatus.NORMAL : station.getOperatingStatus());
        data.put("statusText", station.getOperatingStatusText());
        data.put("note", station.getStatusNote());
        data.put("statusUpdateTime", station.getStatusUpdateTime());
        // 给前端判断"要不要展示横幅"：正常运营时 customerHint 为 null
        data.put("customerHint", StationOperatingStatus.customerHint(
                station.getOperatingStatus(), station.getStatusNote()));
        // 选择器选项**由后端下发**（value / text / desc 三者同源于枚举）——
        // 前端原先在 pages/station-mgmt/station-status/index.js 里自己写了一份
        // `{value:3, name:'配送延迟', ...}`，枚举一改名它就静默对不上（本仓明令禁止自带映射表）。
        data.put("options", options());
        return data;
    }

    /**
     * 选择器选项。
     *
     * <p>⚠️ 用 {@link StationOperatingStatus#SELECTABLE} 而**不是** {@code ALL}（2026-09-24）：
     * 待上线是系统状态，不该出现在站长的选项里 —— 出现了就等于告诉他"你可以设"，
     * 而后端会拒；本仓最忌这种"界面允许、后端拒绝"的错配。</p>
     */
    private List<Map<String, Object>> options() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (int v : StationOperatingStatus.SELECTABLE) {
            Map<String, Object> o = new HashMap<>();
            o.put("value", v);
            o.put("text", StationOperatingStatus.textOf(v));
            o.put("desc", StationOperatingStatus.descOf(v));
            list.add(o);
        }
        return list;
    }
}
