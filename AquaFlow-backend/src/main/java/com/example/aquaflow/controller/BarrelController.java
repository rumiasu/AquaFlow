package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.entity.BarrelRecord;
import com.example.aquaflow.service.BarrelService;
import com.example.aquaflow.service.BarrelLedgerService;
import com.example.aquaflow.dto.BarrelRefundDTO;
import com.example.aquaflow.dto.BarrelReturnEmptyDTO;
import com.example.aquaflow.dto.BarrelReturnRequestDTO;
import com.example.aquaflow.util.AuthContext;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 桶资产管理接口。
 *
 * <p><b>分层（2026-09-29 收口）：本类不再注入任何 Mapper</b> —— 读走 {@link BarrelService#listStationRecords}，
 * 写走 {@code requestReturn} / {@code returnEmptyWithRecord}（事务在服务层）。
 * 此前 {@code barrelRecordMapper.insert} 直插流水、{@code @Transactional} 开在 HTTP 层，
 * 与「桶账唯一写入口是 BarrelLedgerService、编排只在 service」的不变量相抵触。
 * {@code LayeringArchitectureTest} 用零容忍断言盯着这条，别加回来。</p>
 */
@RestController
@RequestMapping("/api/barrels")
public class BarrelController {

    @Autowired
    private BarrelService barrelService;

    /**
     * 获取当前登录客户的桶资产摘要（按水类型分组）
     */
    @GetMapping("/summary-by-type")
    public Result<List<Map<String, Object>>> getBarrelSummaryByType(@RequestParam(required = false) Long stationId) {
        Long customerId = AuthContext.requireCustomerId();
        Long effectiveStationId = stationId != null ? stationId : AuthContext.getStationId();
        if (effectiveStationId == null) {
            return Result.success(java.util.Collections.emptyList());
        }
        return Result.success(barrelService.getBarrelSummaryByType(customerId, effectiveStationId));
    }

    /**
     * 获取当前登录客户的桶资产站级汇总（持有/欠桶/配送中/押金余额等）
     */
    @GetMapping("/summary")
    public Result<Map<String, Object>> getBarrelSummary(@RequestParam(required = false) Long stationId) {
        Long customerId = AuthContext.requireCustomerId();
        Long effectiveStationId = stationId != null ? stationId : AuthContext.getStationId();
        if (effectiveStationId == null) {
            return Result.success(java.util.Collections.emptyMap());
        }
        return Result.success(barrelService.getBarrelSummary(customerId, effectiveStationId));
    }

    /**
     * 获取当前登录客户的桶异常记录（可指定站点）
     */
    @GetMapping("/records")
    public Result<List<BarrelRecord>> listRecords(@RequestParam(required = false) Long stationId) {
        Long customerId = AuthContext.requireCustomerId();
        Long effectiveStationId = stationId != null ? stationId : AuthContext.getStationId();
        if (effectiveStationId == null) {
            return Result.success(java.util.Collections.emptyList());
        }
        return Result.success(barrelService.listRecords(customerId, effectiveStationId));
    }

    @RequireRole({"STATION_MANAGER"})
    @GetMapping("/all-records")
    public Result<List<BarrelRecord>> getAllRecords(@RequestParam(required = false) Integer limit) {
        Long stationId = AuthContext.requireStationId();
        // [AQ-046] 分页兜底与 type=2 欠桶补充都在服务端（listStationRecords）
        return Result.success(barrelService.listStationRecords(stationId, limit));
    }

    /**
     * 退桶试算（只读）：退 N 个桶能拿回多少钱，以及依据（哪几张押金条、各自买入单价）。
     *
     * <p>顾客申请前先看到金额，柜台不用吵架。
     * {@code hasMigratedPrice=true} 表示这批单价是<b>历史迁移时推断的</b>、不是真实成交价，前端应提示站长复核。</p>
     */
    @GetMapping("/return/preview")
    public Result<Map<String, Object>> previewReturn(@RequestParam Long productId,
                                                     @RequestParam Integer quantity,
                                                     @RequestParam(required = false) Long stationId) {
        Long customerId = AuthContext.requireCustomerId();
        Long effectiveStationId = stationId != null ? stationId : AuthContext.getStationId();
        if (effectiveStationId == null) {
            return Result.error("请先选择服务水站");
        }
        if (productId == null || quantity == null || quantity <= 0) {
            return Result.error("商品和数量不能为空");
        }
        try {
            return Result.success(barrelService.previewReturn(customerId, effectiveStationId, productId, quantity));
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 客户申请退桶（type=2，创建待审批记录，不立即扣减桶资产）
     *
     * <p>申请单上的 {@code depositRefund} 是<b>按押金条批次试算出来的估值</b>，不是当前商品押金价。
     * 真正退款时（站长退押金）会重新核销一次；若期间批次有变动，以实际核销金额为准。</p>
     */
    @PostMapping("/return")
    public Result<Map<String, Object>> requestReturn(@RequestBody @Valid BarrelReturnRequestDTO dto) {
        Long customerId = AuthContext.requireCustomerId();
        Long stationId = dto.getStationId() != null ? dto.getStationId() : AuthContext.getStationId();
        if (stationId == null) {
            return Result.error("请先选择服务水站");
        }
        Long productId = dto.getProductId();
        Integer quantity = dto.getQuantity();
        String note = dto.getNote() != null ? dto.getNote() : "";
        if (productId == null || quantity == null || quantity <= 0) {
            return Result.error("商品和数量不能为空");
        }
        // 试算 → 拦截 → 落 type=2 申请单，整段在服务端（2026-09-29 下沉）；
        // 被拦时服务层抛 BusinessException，由 GlobalExceptionHandler 转 code=1，文案不变
        return Result.success(barrelService.requestReturn(customerId, stationId, productId, quantity, note));
    }

    /**
     * 站长审批退桶申请 —— 状态机 1 → 2 → 3，<b>不允许跳步</b>（DEF-7）。
     * status: 2=确认收到空桶 3=退押金并当面交付（按押金条批次核销） 4=驳回
     *
     * <p><b>[v66] 第 3 步是「退押金<b>并</b>当面交付」</b>：核销与"钱交到顾客手上"在这一次调用里
     * 一起完成（产品口径"不现场给钱的不要退"，{@code docs/design/35} §7.2）。
     * {@code refund_paid_time} 与 status=3 由后端写进同一条 UPDATE，客户端<b>无法</b>只退钱不交付。</p>
     *
     * <p>入参 {@code refundChannel}：{@code CASH}（现金当面交付）/ {@code ONLINE}（线上原路退回）。
     * {@code ONLINE} 在微信退款通道未接入时<b>明确拒绝</b>并说明原因，不会假装已退（AGENTS §1.1）。
     * <b>不传 = CASH</b>（旧客户端的等价行为；不默认 ONLINE，见 §7.3）。
     * {@code refundPaidBy}：把押金交到顾客手上的人（{@code staff.id}），不传 = 操作人自己 ——
     * 站长核销、配送员下次上门代交时才会传它。</p>
     */
    @RequireRole("STATION_MANAGER")
    @PutMapping("/records/{id}/status")
    public Result<Void> handleReturn(@PathVariable Long id, @RequestBody(required = false) BarrelRefundDTO dto) {
        Long stationId = AuthContext.requireStationId();
        if (dto == null) {
            return Result.error("请求体不能为空");
        }
        Integer status = dto.getStatus();
        String handleNote = dto.getHandleNote() != null ? dto.getHandleNote() : "";
        if (status == null) {
            return Result.error("status 不能为空");
        }

        // 记录存在性与跨站校验（原先在这里用 Mapper 查）已下沉到 handleBarrelReturn：
        // 站别取登录态传入，文案「退桶记录不存在 / 无权处理他站退桶申请」一字未变
        Long operatorId = AuthContext.getUserId();
        try {
            barrelService.handleBarrelReturn(id, stationId, status, handleNote, operatorId,
                    dto.getRefundChannel(), dto.getRefundPaidBy());
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
        return Result.success();
    }

    /**
     * 交付确认（v66）：登记「这笔押金已经交到顾客手上」——只补事实，<b>不动金额、不动状态</b>。
     *
     * <p><b>幂等</b>：已登记过再点只回成功，且<b>不改原交付时间</b>（那是事实，重试不该改写它）。
     * 返回 {@code alreadyPaid=true} 表示本次没有写入。</p>
     *
     * <p>谁需要它：正常流程下第 3 步（{@code /status}）已经写好了交付时间，
     * 无需再点；它服务于<b>升级 v66 之前退过的历史单</b>（那时系统没记过交付）
     * 与站长端「已核销未交付」计数里那些单的逐笔补登记。</p>
     *
     * <p>判权：站长专属；水站按 {@code AuthContext} 取（不信任请求参数），
     * 服务层还会用 CAS 的 {@code station_id} 条件再挡一次跨站。
     * 顾客端不可调。</p>
     */
    @RequireRole("STATION_MANAGER")
    @PutMapping("/records/{id}/refund-paid")
    public Result<Map<String, Object>> confirmRefundPaid(@PathVariable Long id,
                                                         @RequestBody(required = false) BarrelRefundDTO dto) {
        Long stationId = AuthContext.requireStationId();
        Long operatorId = AuthContext.getUserId();
        Long paidBy = dto != null ? dto.getRefundPaidBy() : null;
        boolean firstTime;
        try {
            firstTime = barrelService.confirmRefundPaid(id, stationId, operatorId, paidBy);
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
        Map<String, Object> data = new java.util.HashMap<>();
        // 幂等命中也是成功（前端重复点击/超时重试都该看到"已登记"），但要把这件事说清楚，
        // 免得"什么都没发生"被当成"又记了一次"。
        data.put("alreadyPaid", !firstTime);
        return Result.success(data);
    }

    /**
     * 站长端「已核销未交付」只读计数与明细（v66）。
     *
     * <p>按拍板口径，{@code status=3} 而 {@code refund_paid_time} 为空<b>不允许出现</b> ——
     * 它是"没给钱就先核销"的违规数据（含升级 v66 之前退过的历史单，那是事实、不是错误），
     * 所以这里只读、只呈现，给站长一个自查入口。</p>
     *
     * <p>返回 {@code {count, amount, records, truncated}}；水站按 {@code AuthContext} 取，顾客端不可调。</p>
     */
    @RequireRole("STATION_MANAGER")
    @GetMapping("/refund-undelivered")
    public Result<Map<String, Object>> refundUndelivered(@RequestParam(required = false) Integer limit) {
        Long stationId = AuthContext.requireStationId();
        return Result.success(barrelService.listRefundUndelivered(stationId, limit));
    }

    /**
     * 纯还桶：顾客交回空桶但【不】带走满桶 —— 只冲减欠桶(over)，<b>不扣权益、不退款</b>。
     *
     * <p>为什么需要这个动作：旧模型里消掉欠桶的唯一途径是"下次配送时多还"，
     * 而欠桶 ≥ 阈值又会被拒单，于是顾客一旦欠桶就永久锁死、押金退不出来。
     * 有了这个入口，随时可以主动把桶还回来，over 会被冲减，可以为负（多还桶/水站暂存）。</p>
     *
     * <p>业务边界：over &lt; 0 是合法状态（不是脏数据、不是负债、不是负权益），
     * 所以这里<b>不做任何"结果必须非负"的校验</b>；唯一校验是物理上限「交回数 ≤ 持有数」。
     * 真正想拿回钱要走退桶（/return），那才会按押金条批次核销退款。</p>
     *
     * <p><b>事务在服务层</b>（2026-09-29 下沉）：改 over 和写流水必须是原子的 ——
     * {@code BarrelService#returnEmptyWithRecord} 带 {@code @Transactional} 把两件事捆在一起；
     * 本方法只做 DTO 解析与登录态取值，别把编排或事务搬回这里
     * （完整理由见该方法 javadoc：流水写失败时 over 已落库回滚不了 ⇒ 对账 E5 不平）。</p>
     */
    @RequireRole({"STATION_MANAGER", "DELIVERY"})
    @PostMapping("/return-empty")
    public Result<Map<String, Object>> returnEmpty(@RequestBody @Valid BarrelReturnEmptyDTO dto) {
        Long stationId = AuthContext.requireStationId();
        Long customerId = dto.getCustomerId();
        String clientToken = dto.getClientToken();
        String note = dto.getNote() != null ? dto.getNote() : "";
        if (customerId == null) return Result.error("客户不能为空");
        if (clientToken == null || clientToken.isEmpty()) return Result.error("缺少幂等 token");

        List<BarrelLedgerService.ItemQty> items = new java.util.ArrayList<>();
        for (BarrelReturnEmptyDTO.BarrelReturnEmptyItemDTO it : dto.getItems()) {
            items.add(new BarrelLedgerService.ItemQty(it.getProductId(), it.getQty()));
        }

        // 幂等判定 → 桶账 → type=7 留痕，**事务在服务层**（2026-09-29 下沉）：
        // 原来 @Transactional 挂在本方法上，「桶账唯一写入口」的编排散在 HTTP 层；
        // 事务语义与 DEF-4（不 catch 事务内业务异常）的完整理由都在
        // BarrelService#returnEmptyWithRecord 的 javadoc 里。
        return Result.success(barrelService.returnEmptyWithRecord(
                stationId, customerId, items, clientToken, note, AuthContext.getUserId()));
    }
}
