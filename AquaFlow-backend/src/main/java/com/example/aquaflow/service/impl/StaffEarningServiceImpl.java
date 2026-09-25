package com.example.aquaflow.service.impl;

import com.example.aquaflow.constant.EarningItemDirection;
import com.example.aquaflow.constant.EarningKind;
import com.example.aquaflow.entity.*;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.*;
import com.example.aquaflow.service.StaffEarningService;
import com.example.aquaflow.util.AuthContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 配送员计件工资实现（v37）。见 {@link StaffEarningService} 与 {@code docs/design/18}。
 */
@Service
@Slf4j
public class StaffEarningServiceImpl implements StaffEarningService {

    @Autowired private OrderMapper orderMapper;
    @Autowired private OrderItemMapper orderItemMapper;
    @Autowired private AddressMapper addressMapper;
    @Autowired private StaffPieceRateMapper staffPieceRateMapper;
    @Autowired private StaffEarningMapper staffEarningMapper;
    @Autowired private StaffPayrollMapper staffPayrollMapper;
    @Autowired private StaffMapper staffMapper;
    @Autowired private StaffEarningItemMapper staffEarningItemMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recordDeliveryEarnings(Long orderId) {
        Orders order = orderMapper.getById(orderId);
        if (order == null) {
            log.warn("[v37] 订单不存在，跳过计件: orderId={}", orderId);
            return;
        }
        Long staffId = order.getDeliveryStaffId();
        if (staffId == null) {
            // 站长自己送、或单子没指派配送员：没有归属人就没有工钱。这不是故障，是常态。
            log.info("[v37] 订单无配送员，跳过计件: orderId={}", orderId);
            return;
        }
        // 归属站 = **履约站**：工钱是履约成本，跟出车的人走（与"钱票记归属站、库存走履约站"一致：
        // 工钱既不是客户的钱，也不是库存）
        Long stationId = order.getDeliveryStationId() != null ? order.getDeliveryStationId() : order.getStationId();
        if (stationId == null) {
            log.error("[v37] 订单既无履约站也无归属站，无法结算工钱: orderId={}", orderId);
            return;
        }

        // ---- 送桶计件：按商品分行（不同品类单价可以不同）----
        List<OrderItem> items = orderItemMapper.listByOrderId(orderId);
        int totalBuckets = 0;
        for (OrderItem item : items) {
            int qty = item.getQuantity() != null ? item.getQuantity() : 0;
            if (qty <= 0) continue;
            totalBuckets += qty;
            StaffPieceRate rate = rateOf(stationId, item.getProductId());
            BigDecimal per = nz(rate.getPerBucketAmount());
            if (per.signum() <= 0) continue;   // 该商品不计件
            insert(order, stationId, staffId, EarningKind.DELIVERY_BUCKET, item.getProductId(), qty, per,
                    "送水计件 " + qty + " 桶");
        }

        // ---- 楼层补贴：v43 起**以配送员上报的楼层为准**，没上报才沿用地址 ----
        // 为什么改成这样：楼层补贴是给配送员的钱，只有他知道自己爬了几层；只认客户在地址里填的值，
        // 等于拿别人的话给自己发工资，虚报也没有痕迹。照片（order_image.type=3）不强制，可作凭证。
        // 两笔钱仍然分开：向客户收的楼层费在下单时按地址快照（orders.floor_fee），本段只算给配送员的补贴。
        StaffPieceRate defaultRate = rateOf(stationId, 0L);
        BigDecimal floorPer = nz(defaultRate.getFloorBonusPerLevel());
        if (floorPer.signum() > 0) {
            Address addr = order.getAddressId() != null ? addressMapper.getById(order.getAddressId()) : null;
            Integer addressFloor = addr != null ? addr.getFloor() : null;
            Integer hasElevator = addr != null ? addr.getHasElevator() : null;
            Integer reported = order.getReportedFloor();
            Integer floor = reported != null ? reported : addressFloor;
            // 地址明确写了"有电梯"就不补（不管有没有上报）；NULL = 客户没确认过 ——
            // 此时若配送员报了几层，就按他报的给（他确实爬了，凭证可核）。
            boolean explicitLift = Integer.valueOf(1).equals(hasElevator);
            if (floor != null && !explicitLift) {
                int freeLevel = defaultRate.getFloorFreeLevel() != null ? defaultRate.getFloorFreeLevel() : 1;
                int levels = floor - freeLevel;
                if (levels > 0) {
                    // ⚠️ 与地址不一致必须**标记出来**：这就是"防虚报"的痕迹，站长在结算单明细里能看到。
                    boolean mismatch = reported != null && addressFloor != null && !addressFloor.equals(reported);
                    String note = (reported != null ? "上报 " + reported + " 层" : "地址 " + floor + " 层")
                            + (mismatch ? "（与地址 " + addressFloor + " 层不一致）" : "")
                            + "，超 " + levels + " 层";
                    insert(order, stationId, staffId, EarningKind.FLOOR_BONUS, 0L, levels, floorPer, note);
                }
            }
        }

        log.info("[v37] 计件收益已产生: orderId={}, staffId={}, stationId={}, 送桶={}", orderId, staffId, stationId,
                totalBuckets);
    }

    /**
     * 取计价单价：**商品专属价优先，否则回落到该站默认价（product_id=0）**。
     *
     * <p>与 {@code PriceUtil} 的"站级覆盖 → 通用库参考值"是同一套阶梯：
     * 有专门配过就按专门的，没配就用默认。两处都不存在时返回全 0 的默认配置（= 不计件）。</p>
     */
    private StaffPieceRate rateOf(Long stationId, Long productId) {
        if (productId != null && productId > 0) {
            StaffPieceRate byProduct = staffPieceRateMapper.getByStationAndProduct(stationId, productId);
            if (byProduct != null) return byProduct;
        }
        StaffPieceRate byStation = staffPieceRateMapper.getByStationAndProduct(stationId, 0L);
        return byStation != null ? byStation : StaffPieceRate.defaults(stationId, 0L);
    }

    /**
     * 写一条收益明细。
     *
     * <p>符号在这里统一转：{@link EarningKind#isDecrease} 为真的类型落库取负。</p>
     *
     * <p>⚠️ 插入冲突（{@code uk_earning_auto}）时<b>必须抛出</b>、不能吞掉继续：
     * 本方法在事务里，捕获后继续提交会让 Spring 抛 {@code UnexpectedRollbackException}，
     * 把一次正常的并发拒绝伪装成 500。重复完成配送的第二次调用会被唯一键拦住，
     * 这对调用方是"拒绝"，不是"成功"。同款处理见 {@code PaymentServiceImpl.createPayment}。</p>
     */
    private void insert(Orders order, Long stationId, Long staffId, String kind, Long productId,
                        Integer qty, BigDecimal unitAmount, String note) {
        BigDecimal raw = unitAmount.multiply(BigDecimal.valueOf(qty != null ? qty : 1))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal amount = EarningKind.isDecrease(kind) ? raw.negate() : raw;
        if (amount.signum() == 0) return;   // 0 元收益没有记录价值，也不该占唯一键

        StaffEarning e = new StaffEarning();
        e.setStationId(stationId);
        e.setStaffId(staffId);
        e.setOrderId(order.getId());
        e.setKind(kind);
        e.setProductId(productId != null ? productId : 0L);
        e.setQty(qty);
        e.setUnitAmount(unitAmount);
        e.setAmount(amount);
        e.setNote(note);
        try {
            staffEarningMapper.insert(e);
        } catch (org.springframework.dao.DuplicateKeyException dup) {
            log.warn("[v37] 该单该类型收益已存在（重复完成配送？），跳过: orderId={}, staffId={}, kind={}, productId={}",
                    order.getId(), staffId, kind, productId);
            throw new BusinessException("该订单的计件收益已产生，请勿重复提交");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long generatePayroll(Long stationId, Long staffId, LocalDate periodStart, LocalDate periodEnd,
                                String note) {
        if (staffId == null || periodStart == null || periodEnd == null) {
            throw new BusinessException("配送员与结算期间不能为空");
        }
        if (periodEnd.isBefore(periodStart)) {
            throw new BusinessException("结算期间止不能早于起");
        }
        StaffPayroll existing = staffPayrollMapper.getByPeriod(stationId, staffId, periodStart, periodEnd);
        if (existing != null) {
            throw new BusinessException("该配送员在本期间已有结算单（" + existing.getPayrollNo() + "），请勿重复生成");
        }

        StaffPayroll p = new StaffPayroll();
        p.setPayrollNo("PR-PENDING-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        p.setStationId(stationId);
        p.setStaffId(staffId);
        p.setPeriodStart(periodStart);
        p.setPeriodEnd(periodEnd);
        p.setTotalAmount(BigDecimal.ZERO);
        p.setStatus(StaffPayroll.Status.DRAFT);
        p.setOperatorId(AuthContext.getUserId());
        p.setNote(note);
        staffPayrollMapper.insert(p);

        String no = String.format("PR%s-%06d", LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE), p.getId());
        staffPayrollMapper.setPayrollNo(p.getId(), no);

        // ⚠️ 时间上界用「结束日 +1 天」而不是 <= 结束日：endDate 为当天时 <= 当天 在 SQL 里
        // 等价于 <= 当天 00:00:00，当天的收益一条都结算不到（AGENTS §8.19）。
        LocalDateTime start = periodStart.atStartOfDay();
        LocalDateTime endExclusive = periodEnd.plusDays(1).atStartOfDay();
        int attached = staffEarningMapper.attachToPayroll(p.getId(), stationId, staffId, start, endExclusive);
        BigDecimal total = staffEarningMapper.sumByPayroll(p.getId());
        staffPayrollMapper.setTotalAmount(p.getId(), total != null ? total : BigDecimal.ZERO);

        log.info("[v37] 生成结算单: no={}, stationId={}, staffId={}, 期间={}~{}, 明细={} 条, 合计={}",
                no, stationId, staffId, periodStart, periodEnd, attached, total);
        return p.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirmPayroll(Long stationId, Long payrollId) {
        StaffPayroll p = requireOwned(stationId, payrollId);
        if (p.isLocked()) {
            throw new BusinessException("该结算单已确认，请勿重复操作");
        }
        // 确认前再核一次总额：状态只前进，确认之后明细不允许再改，
        // 所以此刻的合计必须等于明细之和（对账 E-PAY 的前提）
        BigDecimal total = staffEarningMapper.sumByPayroll(payrollId);
        staffPayrollMapper.setTotalAmount(payrollId, total != null ? total : BigDecimal.ZERO);
        int affected = staffPayrollMapper.updateStatusTo(payrollId, StaffPayroll.Status.CONFIRMED,
                StaffPayroll.Status.DRAFT);
        if (affected == 0) {
            throw new BusinessException("结算单状态已变更，请刷新后重试");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markPayrollPaid(Long stationId, Long payrollId) {
        StaffPayroll p = requireOwned(stationId, payrollId);
        if (p.getStatus() != null && p.getStatus() == StaffPayroll.Status.PAID) {
            throw new BusinessException("该结算单已标记发放，请勿重复操作");
        }
        // markPaid 的 SQL 自带 `and status = 2` 门槛：只有已确认的才能发放。
        // 顺带落 paid_time + operator_id —— 发钱是线下动作，系统只能留痕。
        int affected = staffPayrollMapper.markPaid(payrollId, AuthContext.getUserId());
        if (affected == 0) {
            throw new BusinessException("结算单必须先确认才能标记发放");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void adjustEarning(Long stationId, Long staffId, Long itemId, BigDecimal amount, String note) {
        if (staffId == null || amount == null || amount.signum() == 0) {
            throw new BusinessException("配送员与调整金额不能为空，且金额不能为 0");
        }
        requireAdjustableStaff(stationId, staffId);

        StaffEarning e = new StaffEarning();
        e.setStationId(stationId);
        e.setStaffId(staffId);
        e.setOrderId(null);            // 人工调整：auto_uk 为 NULL，允许无限多条（显式设计）
        e.setKind(EarningKind.ADJUST);
        e.setProductId(0L);
        e.setQty(null);
        e.setUnitAmount(null);

        if (itemId != null) {
            // 按条目录入（v44）：方向由条目决定，因此这里**只接受正数** ——
            // 让录的人自己判"这次是补还是扣"，等于每次都要重新赌一次手感
            StaffEarningItem item = staffEarningItemMapper.getById(itemId);
            if (item == null || !item.getStationId().equals(stationId)) {
                throw new BusinessException("条目不存在或不属于本站");
            }
            if (!item.isEnabled()) {
                throw new BusinessException("条目「" + item.getName() + "」已停用，请先启用它或换一个条目");
            }
            if (amount.signum() < 0) {
                throw new BusinessException("按条目录入时金额一律传正数，加项/扣项由条目决定");
            }
            e.setItemId(item.getId());
            e.setItemName(item.getName());     // 名称快照：条目日后改名不改写这笔历史
            e.setAmount(amount.abs()
                    .multiply(BigDecimal.valueOf(EarningItemDirection.signOf(item.getDirection())))
                    .setScale(2, RoundingMode.HALF_UP));
            e.setNote(note == null || note.trim().isEmpty() ? item.getName() : note);
        } else {
            // 自由文本调整：唯一允许调用方给符号的 kind
            e.setAmount(amount.setScale(2, RoundingMode.HALF_UP));
            e.setNote(note);
        }
        staffEarningMapper.insert(e);
    }

    /**
     * 人工调整的归属校验：只能给「本站员工」或「在本站有过履约的人」记工钱。
     *
     * <p>⚠️ 修的是一个真实越权形状（2026-09-18 随 v44 一起补）：{@code POST /payroll/adjust}
     * 原本只收 {@code staffId}，而"我的工资"自助查询**只按 staff_id 过滤**（刻意不带站点，
     * 否则跨站外派挣的那部分会消失）—— 于是任何站长传一个别站配送员的 id 就能往那个人的
     * 「未结工资」里塞钱或扣钱。判据取并集而不是只看 {@code staff.station_id}：
     * 跨站外派时人属于 B 站、给 A 站跑腿，A 站给他记一笔完全正常。</p>
     */
    private void requireAdjustableStaff(Long stationId, Long staffId) {
        Staff staff = staffMapper.getById(staffId);
        if (staff == null
                || (!"DELIVERY".equals(staff.getRole()) && !"STATION_MANAGER".equals(staff.getRole()))) {
            throw new BusinessException("配送员不存在");
        }
        if (stationId.equals(staff.getStationId())) {
            return;   // 本站员工
        }
        if (staffEarningMapper.countByStationAndStaff(stationId, staffId) > 0) {
            return;   // 跨站外派：在本站留下过收益痕迹
        }
        throw new BusinessException("该配送员与本站没有履约关系，不能给他记工资");
    }

    private StaffPayroll requireOwned(Long stationId, Long payrollId) {
        StaffPayroll p = staffPayrollMapper.getById(payrollId);
        if (p == null) {
            throw new BusinessException("结算单不存在");
        }
        // 跨站校验一律以服务端刷新的 stationId 为准，不信任请求参数
        if (!p.getStationId().equals(stationId)) {
            throw new BusinessException("无权操作他站的结算单");
        }
        return p;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
