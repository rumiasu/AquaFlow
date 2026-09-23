package com.example.aquaflow.service.impl;

import com.example.aquaflow.constant.PaymentStatus;
import com.example.aquaflow.constant.OrderStatus;
import com.example.aquaflow.constant.DepositType;
import com.example.aquaflow.constant.InventoryChangeType;
import com.example.aquaflow.constant.PayMethod;
import com.example.aquaflow.entity.Orders;
import com.example.aquaflow.entity.PaymentRecord;
import com.example.aquaflow.entity.CustomerBarrelInTransit;
import com.example.aquaflow.entity.DepositRecord;
import com.example.aquaflow.entity.TicketAccount;
import com.example.aquaflow.entity.Product;
import com.example.aquaflow.entity.Inventory;
import com.example.aquaflow.entity.CustomerBarrelAsset;
import com.example.aquaflow.entity.OrderItem;
import com.example.aquaflow.entity.CustomerStationConfig;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.*;
import com.example.aquaflow.service.PaymentService;
import com.example.aquaflow.service.EnterpriseIdentityService;
import com.example.aquaflow.service.InventoryService;
import com.example.aquaflow.service.TicketAccountService;
import com.example.aquaflow.util.AuthContext;
import com.example.aquaflow.util.BarrelScope;
import com.example.aquaflow.util.PriceUtil;
import com.example.aquaflow.util.StationUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class PaymentServiceImpl implements PaymentService {

    /**
     * 微信支付**模拟渠道**开关（{@code app.payment.mock-wechat-pay}，**默认 false**）。
     *
     * <p>[2026-09-20 产品裁定] 真实微信支付尚未接入，但联调需要一个能走通的微信单。
     * 口径是「<b>点击即成功，只取代真实支付这一下，别的一律按真实标准</b>」—— 因此本开关
     * <b>只改一件事</b>：{@code createPayment} 里 method=1 的流水由 PENDING 改为 PAID。
     * 其余全部**原样保留**：金额服务端重算、活跃流水唯一键
     * （{@code uk_payment_active_order}）、水票扣减、押金入账（{@code applyDepositOnPaid}）、
     * 订单付款状态只前进（{@code markPaidIfCollectable}）、对账等式。</p>
     *
     * <p>⚠️ <b>不能只写 {@code status = PAID} 就完事</b>：下面那两个 if 分支是按
     * "status 已是 PAID"触发的，改这一处就自动带上了押金入账与订单置已付 ——
     * 若另起一段单独写，就会出现"流水说付了、押金没进账"（AGENTS §8.4 的老坑）。</p>
     *
     * <p>⚠️ 生产**必须保持 false**：开启等于任何人选微信支付都能零元购。
     * 真实渠道接入时应删掉本开关与 {@link #isMockWechatPay}，而不是把它留成"永真"。</p>
     */
    @org.springframework.beans.factory.annotation.Value("${app.payment.mock-wechat-pay:false}")
    private boolean mockWechatPay;

    /**
     * 本次请求是否走微信**模拟**渠道。
     *
     * <p>集中一处判断，避免"报价说微信可用、下单又不认"这类口径分叉
     * （同一个坑本仓已踩过：报价与下单各写一套判据）。</p>
     */
    private boolean isMockWechatPay(Integer paymentMethod) {
        return mockWechatPay && Integer.valueOf(PayMethod.WECHAT).equals(paymentMethod);
    }

    @Autowired
    private PaymentRecordMapper paymentRecordMapper;

    @Autowired
    private TicketAccountMapper ticketAccountMapper;

    @Autowired
    private TicketRecordMapper ticketRecordMapper;

    /** 档位判据（定制 or 统一折扣）的唯一实现 —— 结算页预览要与下单闸门、扣票路径同一口径 */
    @Autowired
    private TicketTierService ticketTierService;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private DepositRecordMapper depositRecordMapper;

    @Autowired
    private CustomerDepositAccountMapper customerDepositAccountMapper;

    @Autowired
    private CustomerBarrelAssetMapper customerBarrelAssetMapper;

    @Autowired
    private CustomerBarrelInTransitMapper customerBarrelInTransitMapper;

    @Autowired
    private InventoryMapper inventoryMapper;

    @Autowired
    private OrderItemMapper orderItemMapper;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private CustomerStationConfigMapper customerStationConfigMapper;

    /** 企业身份（v50）：只用来取"大额可申请"的提示文案；开关关着时它恒返回 null。 */
    @Autowired
    private EnterpriseIdentityService enterpriseIdentityService;

    /** 水票账户：用于水票支付的原子扣减与在线购票入账 */
    @Autowired
    private TicketAccountService ticketAccountService;

    /** [AQ-029] 库存流水：库存回补时写流水 */
    @Autowired
    private InventoryService inventoryService;

    /**
     * 配送计费（起送量 / 配送范围 / 运费 / 楼层费，v35）。
     *
     * <p>⚠️ {@code OrderServiceImpl.createOrder} 用的是<b>同一个服务</b>、传同样的入参 ——
     * 报价与下单必须同口径。见 {@code docs/design/17} 与 {@code PriceUtil} 文件头记的计价双轨事故。</p>
     */
    @Autowired
    private com.example.aquaflow.service.DeliveryFeeService deliveryFeeService;

    /** 履约站口径取 {@link StationUtil#deliveryStation}（唯一实现，本类不自留副本）。 */
    private static Long stationOf(Orders o) {
        if (o == null) return null;
        return o.getDeliveryStationId() != null ? o.getDeliveryStationId() : o.getStationId();
    }

    /**
     * [AQ-043] 订单「归属站」= orders.station_id。
     *
     * <p><b>认归属站的是"资产与退款"</b>：预收押金（{@code customer_deposit_account} /
     * {@code deposit_record}）、水票扣减与回补、退款——那是"客户买在哪个站的资产"，
     * 与欠桶 {@code adjustOwed} 的口径一致。跨站外派时若用履约站会与实物账错位
     * （押金记履约站、欠桶记归属站）。</p>
     *
     * <p>⚠️ <b>收款流水不归本方法管</b>（v47，2026-09-18）：{@code payment_record.station_id}
     * 按<b>结算站</b>写（水费 + 配送费 + 楼层费归实际配送站），取 {@link StationUtil#settleStation}
     * —— 口径只有那一份，本类不再自留副本（两份实现迟早算出两个站，正是本仓"计价双轨"事故的同形风险）。
     * 两者刻意不同，别"统一"掉 —— 产品裁定就是"钱（营收）跟着送货的站走，
     * 押金/票/桶这种客户资产留在归属站"。</p>
     */
    private static Long ownerStation(Orders o) {
        if (o == null) return null;
        return o.getStationId() != null ? o.getStationId() : stationOf(o);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PaymentRecord createPayment(Long orderId, Long customerId, BigDecimal amount, BigDecimal waterAmount,
                                        BigDecimal barrelDeposit, Integer excessBarrels, Integer paymentMethod,
                                        Long ticketWaterTypeId, Integer ticketQty, String note) {
        // 防重复支付：该订单已有「已支付」或「待收款」记录时直接返回，不再新建。
        // [DEF-3] 必须连同 PENDING 一起拦：payment_record 原来靠
        // uk_payment_order_status(order_id, status) 唯一键兜底防重，但该唯一键与
        // 「退款另立负金额流水」的设计根本冲突（退款会把原记录置 REFUNDED 再插一条 REFUNDED，
        // 撞唯一键 → 水票已付订单永远取消不了），已改为普通索引。
        // 去掉数据库兜底后，防重的责任回到应用层：同一订单不允许出现第二条待收款流水。
        if (orderId != null) {
            PaymentRecord existing = paymentRecordMapper.getByOrderId(orderId);
            if (existing != null && existing.getStatus() != null
                    && (existing.getStatus() == PaymentStatus.PAID
                            || existing.getStatus() == PaymentStatus.PENDING)) {
                return existing;
            }
        }

        // 获取订单信息
        com.example.aquaflow.entity.Orders order = null;
        Long orderStationId = null;
        if (orderId != null) {
            order = orderMapper.getById(orderId);
            if (order != null) {
                // [v47 复核] 这个表达式的值 = 结算站（本列写入 payment_record.station_id）：
                // 正常路径下 settle_station_id 与 coalesce(delivery_station_id, station_id) 恒等
                // （下单两列同值、抢单/外派两列一起改、退回池/召回一起回归属站），故此处**无需**改用
                // StationUtil.settleStation(order)。留着 orderStationId 是因为它下面还要判「货到付款权限」
                // （customer_station_config 是 (客户 × 站) 的绑定关系，判据不是营收归属，别混用）。
                orderStationId = order.getDeliveryStationId() != null ? order.getDeliveryStationId() : order.getStationId();
            }
        }

        // 货到付款（现金）权限二次校验：需要水站开启且客户已授权
        // 注意：旧代码这里判断的是 3（老语义"线下支付"），与 PayMethod 的 2=现金冲突，已按 PayMethod 统一
        if (Integer.valueOf(PayMethod.CASH).equals(paymentMethod) && orderStationId != null) {
            if (!canUseOfflinePayment(customerId, orderStationId)) {
                throw new BusinessException("当前客户暂不支持货到付款");
            }
        }

        // ===== 金额一律以服务端重算为准，客户端传入值全部丢弃 =====
        // 否则任何人都可以 POST {orderId, paymentMethod, amount:0.01} 让自己的订单变成已支付
        if (order != null) {
            amount = order.getTotalAmount() != null ? order.getTotalAmount() : BigDecimal.ZERO;
            waterAmount = order.getWaterAmount() != null ? order.getWaterAmount() : BigDecimal.ZERO;
            barrelDeposit = order.getDepositAmount() != null ? order.getDepositAmount() : BigDecimal.ZERO;
        }

        PaymentRecord record = new PaymentRecord();
        record.setOrderId(orderId);
        record.setCustomerId(customerId);
        record.setStationId(orderStationId);
        record.setAmount(amount);
        record.setWaterAmount(waterAmount);
        record.setBarrelDeposit(barrelDeposit);
        record.setExcessBarrels(excessBarrels);
        record.setTicketWaterTypeId(ticketWaterTypeId);
        record.setTicketQty(ticketQty);
        record.setPaymentMethod(paymentMethod);
        record.setNote(note);
        record.setCreateTime(LocalDateTime.now());
        record.setUpdateTime(LocalDateTime.now());

        // ===== 支付状态判定：绝不能由客户端参数直接决定"已支付" =====
        // 水票(3)：先按订单项原子扣减水票，扣减成功才算已支付
        // 微信(1)：真实渠道下是 PENDING，PAID 只能由微信支付异步回调写入（TODO: 接入统一下单 + 回调验签）；
        //         **模拟渠道**下当场置 PAID（见字段 mockWechatPay 的说明）
        // 现金(2)：PENDING，货到付款，由站长确认收款后写入
        // 历史实现：paymentMethod==1 或 2 直接置 PAID（"测试阶段"注释），等于任何人都可零元购 ——
        // 注意区别：当年的错在于**无条件**置 PAID；现在是**开关控制**且默认关闭，判据没有放宽。
        Integer status;
        if (Integer.valueOf(PayMethod.TICKET).equals(paymentMethod)) {
            deductTickets(orderId);
            status = PaymentStatus.PAID;
        } else if (isMockWechatPay(paymentMethod)) {
            // 只跳过"真实付款"这一下；走到这里时金额重算、活跃流水唯一键、幂等检查都已跑完。
            log.warn("[模拟微信支付] 开关 app.payment.mock-wechat-pay=true，跳过真实渠道直接置为已付款: orderId={}", orderId);
            status = PaymentStatus.PAID;
        } else {
            status = PaymentStatus.PENDING;
        }
        record.setStatus(status);

        try {
            paymentRecordMapper.insert(record);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // [AQ-053] 数据库级防重兜底：并发的两个请求都会通过上面的存在性检查（check-then-act），
            // 此时由 uk_payment_active_order（active_order_id 上的唯一键）拦住第二条活跃流水。
            //
            // 关键：**必须抛出**，不能吞掉后返回原记录。本方法在插入之前可能已经扣过水票
            // （见上面的 deductTickets），只有回滚才能保证「票不被扣两次」「押金不被入账两次」；
            // 若在此处 catch 后继续提交，Spring 会因事务被标记 rollback-only 而抛
            // UnexpectedRollbackException，把一次正常的并发拒绝伪装成 500。
            throw new BusinessException("该订单已有待收款或已支付流水，请勿重复提交");
        }

        // 同步订单付款状态：[2026-09-16 修正] 只有「水票」在这一步才是真付款（下单即视同已付）。
        // 现金/微信只是**发起收款**（payment_record 落一条待收款流水），订单必须留在 待收款(1)：
        // 原先无条件把它写成 UNPAID(0)，而 DashboardMapper 的待收款金额口径是
        // `payment_status = 1 且未取消` —— 一改这笔钱就从站长「待收款」合计里凭空消失。
        // 状态只前进：0/1（钱没到手）→ 2（已付款），见 OrderMapper.markPaidIfCollectable。
        if (orderId != null && Integer.valueOf(PaymentStatus.PAID).equals(status)) {
            orderMapper.markPaidIfCollectable(orderId);
        }

        // [AQ-009] 水票是「下单即视同已付」的唯一支付方式：订单在这一步就已经是 PAID，
        // 但它绕过了 confirmPayment（现金/线上走那条路径时才入账押金），因此必须在这里补入账，
        // 否则水票支付的订单预收押金永远不进押金账户 —— 客户退了桶却退不出钱，押金余额显示为 0。
        // applyDepositOnPaid 以 (related_order_id, PREPAID) 去重，重复调用安全。
        if (orderId != null && Integer.valueOf(PaymentStatus.PAID).equals(status)) {
            applyDepositOnPaid(orderId);
        }

        return record;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirmPayment(Long paymentId) {
        PaymentRecord record = paymentRecordMapper.getById(paymentId);
        // 以下均为"业务前置条件不满足"，属于可预期的用户侧错误，用 BusinessException（code=1）
        // 而不是 RuntimeException —— 后者会被兜底处理器转成 code=500「系统错误」，
        // 让"重复点击确认"这类正常场景看起来像后端崩了。
        if (record == null) throw new BusinessException("支付记录不存在");
        if (record.getStatus() != PaymentStatus.PENDING) {
            throw new BusinessException(record.getStatus() == PaymentStatus.PAID
                    ? "该笔支付已确认，请勿重复操作"
                    : "该笔支付当前状态不可确认");
        }
        // #29: 乐观锁 — 用原子更新确保只有PENDING状态才能改为PAID
        // [AQ-003] 期望状态必须显式传 PaymentStatus.PENDING(=1)；
        // 旧实现在 SQL 里硬编码 status=0，与 PENDING 取值不符，导致此处恒为 0 行、支付永远确认失败。
        int affected = paymentRecordMapper.updateStatusTo(paymentId, PaymentStatus.PAID, PaymentStatus.PENDING);
        if (affected == 0) {
            throw new BusinessException("支付确认失败，状态已变更，请刷新后重试");
        }

        // 水票支付：确认收款时补齐扣减（deductTickets 内部幂等，已扣过会跳过）
        if (Integer.valueOf(PayMethod.TICKET).equals(record.getPaymentMethod()) && record.getOrderId() != null) {
            deductTickets(record.getOrderId());
        }

        // 在线购买水票：支付确认后入账。
        // 此前这里是一个 TODO —— 客户在线买水票付了钱，水票却永远不到账。
        //
        // ⚠️ [2026-09-17 修正] 原文写的是「上面的乐观锁保证同一笔支付只会确认成功一次，
        // 因此入账天然幂等」—— 这句话是**错的**，而且危险：它会诱导下一个人以为「重复流水无害」。
        // 上面的 CAS 只保证**单条 payment_record** 只会被确认成功一次，它管不住
        // 「同一个购买意图存在两条流水」这种情况 —— 两条流水各自被确认一次，水票就入账两次。
        // 所以防重必须发生在**落流水**那一步：见 TicketAccountServiceImpl.purchaseTicket 的
        // idempotencyKey 与 uk_payment_idempotency（v33 / migration_v33_payment_idempotency.sql）。
        if (record.getTicketWaterTypeId() != null && record.getTicketQty() != null && record.getTicketQty() > 0) {
            // [v36] 走 creditPurchasedTickets 而不是 addTicket：前者把**实付均价**快照进水票批次，
            // 后者用的是站级水票价。档位套餐下两者不同 —— 客户按档位价付了 800 元买 100 张，
            // 批次单价必须是 8.00；用站级单张价记，退票时就会多退给客户钱。
            ticketAccountService.creditPurchasedTickets(record.getCustomerId(), record.getTicketWaterTypeId(),
                    record.getTicketQty(), record.getStationId(), record.getId(), record.getAmount());
        }

        if (record.getOrderId() != null) {
            // 确认收款 = 收钱 → 0/1 任一状态都前进到 已付款(2)；水票创建时已是 2，此处 affected=0 属幂等
            orderMapper.markPaidIfCollectable(record.getOrderId());
            // [AQ-009] 支付成功时才入账预收桶押金（此前在下单时即入账，那时客户一分未付）
            applyDepositOnPaid(record.getOrderId());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirmOrderCollection(Long orderId) {
        com.example.aquaflow.entity.Orders order = orderMapper.getById(orderId);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }
        if (order.getStatus() != OrderStatus.DELIVERED) {
            throw new BusinessException("仅已配送待付款的订单可确认收款");
        }
        // 货到付款（现金）：现场收款后把待支付流水置为已支付
        // 旧代码用 3 判断"线下支付"，与 PayMethod（3=水票）语义冲突，已统一为 CASH=2
        boolean isCashOnDelivery = Integer.valueOf(PayMethod.CASH).equals(order.getPaymentMethod());
        if (isCashOnDelivery) {
            List<PaymentRecord> records = paymentRecordMapper.listByOrderId(orderId);
            for (PaymentRecord r : records) {
                if (r.getStatus() != null && r.getStatus() == PaymentStatus.PENDING) {
                    // ⚠️ 参数顺序是 (id, 目标状态, 期望状态) —— PaymentRecordMapper.updateStatusIf 的
                    // SQL 是 `set status = #{status} ... and status = #{expectStatus}`。
                    // [2026-09-16 修复] 这里原写作 (PENDING, PAID)，即"把 status 改成 PENDING、要求它现在是 PAID"，
                    // 与上面那行 `r.getStatus() == PENDING` 的判据自相矛盾，SQL 恒命中 0 行：
                    // 现金单确认收款后订单变已付款，但支付流水永远停在「待收款」。
                    paymentRecordMapper.updateStatusTo(r.getId(), PaymentStatus.PAID, PaymentStatus.PENDING);
                }
            }
            orderMapper.markPaidIfCollectable(orderId);
            // [AQ-009] 收款成功时入账预收桶押金
            applyDepositOnPaid(orderId);
        }
        orderMapper.updateStatusIf(orderId, OrderStatus.DELIVERED, OrderStatus.COMPLETED);
    }

    // [2026-09-16 按产品决定删除] 原 `unconfirmOrderCollection`：把 已完成(4) 倒回 已送达(3)。
    //   删除理由（与上一条领域不变式一致）：订单状态只前进；而且它**只改订单状态、不改
    //   payment_status**，回滚后订单会停在「已送达 + 已付款(2)」这种自相矛盾的组合上。
    //   删除时全仓零调用点（接口声明 + 本实现 + refundOrder 里的一句注释）。
    //   已完成的收款不许撤销；要退钱请走 refundOrder（其 isCancellable 门槛本就排除 已完成/已取消）。

    @Override
    public boolean hasPaidRecord(Long orderId) {
        if (orderId == null) {
            return false;
        }
        // [AQ-002][AQ-007] 订单能否视为「已付款」，唯一凭据是存在 PAID 流水。
        return paymentRecordMapper.countByOrderIdAndStatus(orderId, PaymentStatus.PAID) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void relocatePendingCollection(Long orderId, Long settleStationId) {
        if (orderId == null || settleStationId == null) {
            return;
        }
        // [v47 2026-09-18] 订单换站（抢单/定向外派/退回池/召回/指定退回-同意）后，待收款流水必须跟着走：
        // 否则「履约站收了钱、凭据却挂在归属站」，并且两个站的「待确认收款」列表各错一边
        // （归属站列着它永远收不到的钱、履约站看不到自己该催的单）。
        // 只搬 PENDING 行 —— 已收/已退的历史凭据是已经发生过的钱，改站等于伪造账。
        // 调用方（OrderWorkflowServiceImpl）与订单站别的 CAS 在同一个事务里，CAS 没成功就不会走到这里。
        int moved = paymentRecordMapper.movePendingToStation(orderId, settleStationId);
        if (moved > 0) {
            log.info("[结算站] 订单 {} 的 {} 条待收款流水改挂水站 {}", orderId, moved, settleStationId);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recordCashCollection(Long orderId) {
        // 默认事由即"配送员现场收款"——本方法最初的唯一调用方是 completeDelivery。
        recordCashCollection(orderId, "配送员现场收款（货到付款）");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recordCashCollection(Long orderId, String note) {
        if (orderId == null) {
            return;
        }
        com.example.aquaflow.entity.Orders order = orderMapper.getById(orderId);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }
        // 幂等：已有 PAID 流水则跳过，不重复记账（#27）
        if (hasPaidRecord(orderId)) {
            return;
        }
        String finalNote = note != null && !note.isEmpty() ? note : "配送员现场收款（货到付款）";
        Long settleStationId = StationUtil.settleStation(order);
        // [2026-09-18] 已有一条**待收款**流水时，就地确认它，**不要再插一条 PAID**：
        // 一单只能有一条活跃流水（生成列 active_order_id 把 status ∈ (1,2) 都算活跃 +
        // uk_payment_active_order），插第二条必然 DuplicateKeyException → 整个送达事务回滚，
        // 配送员点「已收款」只拿到 code=1「数据已存在，请勿重复提交」，订单永远停在配送中。
        // 这条路径真实可达：现金单在客户点过「去支付」之后就会先落一条 PENDING
        // （PaymentServiceImpl.createPayment 的现金分支），而收款是另一条写路径。
        // 顺带把站别改成结算站 —— 确认下来的这条凭据就是"谁结算谁收钱"的凭证。
        if (paymentRecordMapper.confirmPendingToPaid(orderId, settleStationId, finalNote,
                AuthContext.getUserId()) > 0) {
            return;
        }
        // [AQ-002] 现金（货到付款）由配送员现场收款，是合法收款动作；
        // 但必须补写一条 PAID 支付流水，否则「订单已付款」与支付流水对不上，日结无凭证。
        //
        // [v47 2026-09-18] 流水的站别从 ownerStation（归属站）改为**结算站**：
        // 产品裁定「水费 + 配送费 + 楼层费归实际配送站」，钱在谁手里收的就记谁的营收。
        // 两条链路的调用方都在这条规则里：① 站长核销应收账款（ReceivableService.settle
        // → 本方法）—— 核销的站别校验也已改成结算站，收款流水必须跟着同一个站，
        // 否则"在 B 站核销掉、钱却记在 A 站"，对账口径再次分叉；
        // ② 配送员现场收款（completeDelivery / confirmOfflinePay）—— 现金是履约站收的。
        // ⚠️ 押金/水票**不跟着走**：applyDepositOnPaid / refundOrder 仍用 ownerStation（归属站），
        // 那是客户买在哪个站的资产（见 ownerStation 的 javadoc）。
        // 结算站口径取 StationUtil.settleStation（唯一实现），不在本类另写一份。
        PaymentRecord record = new PaymentRecord();
        record.setOrderId(orderId);
        record.setCustomerId(order.getCustomerId());
        record.setStationId(settleStationId);
        record.setAmount(order.getTotalAmount() != null ? order.getTotalAmount() : BigDecimal.ZERO);
        record.setWaterAmount(order.getWaterAmount());
        record.setBarrelDeposit(order.getDepositAmount());
        record.setPaymentMethod(PayMethod.CASH);
        record.setStatus(PaymentStatus.PAID);
        record.setNote(finalNote);
        record.setOperatorId(AuthContext.getUserId());
        record.setCreateTime(LocalDateTime.now());
        record.setUpdateTime(LocalDateTime.now());
        paymentRecordMapper.insert(record);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void applyDepositOnPaid(Long orderId) {
        if (orderId == null) {
            return;
        }
        com.example.aquaflow.entity.Orders order = orderMapper.getById(orderId);
        if (order == null) {
            return;
        }
        BigDecimal dep = order.getDepositAmount();
        if (dep == null || dep.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        Long stationId = ownerStation(order);
        // [AQ-009] 幂等：同一订单仅入账一次（按 related_order_id + PREPAID 去重），
        // 可被线上确认 / 现金收款 / 水票扣减等多处"置已付款"入口安全重复调用。
        if (depositRecordMapper.countByOrderAndType(orderId, DepositType.PREPAID) > 0) {
            return;
        }
        customerDepositAccountMapper.increaseBalance(order.getCustomerId(), stationId, dep);

        DepositRecord dr = new DepositRecord();
        dr.setCustomerId(order.getCustomerId());
        dr.setStationId(stationId);
        dr.setType(DepositType.PREPAID);
        dr.setAmount(dep);
        dr.setRelatedOrderId(orderId);
        dr.setNote("订单支付成功预收桶押金");
        dr.setOperatorId(AuthContext.getUserId());
        dr.setCreateTime(LocalDateTime.now());
        depositRecordMapper.insert(dr);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirmCashPayment(Long orderId, Long customerId, BigDecimal amount) {
        // #27: 幂等性检查 — 如果该订单已有确认的支付记录，直接返回
        if (orderId != null) {
            List<PaymentRecord> existing = paymentRecordMapper.listByOrderId(orderId);
            for (PaymentRecord r : existing) {
                if (r.getStatus() == PaymentStatus.PAID && Integer.valueOf(PayMethod.CASH).equals(r.getPaymentMethod())) {
                    return; // 已确认过，幂等返回
                }
            }
        }

        com.example.aquaflow.entity.Orders o = orderMapper.getById(orderId);
        // 收款金额以订单为准，忽略客户端传入值（否则可伪造实收金额）
        if (o != null && o.getTotalAmount() != null) {
            amount = o.getTotalAmount();
        }

        PaymentRecord record = new PaymentRecord();
        record.setOrderId(orderId);
        record.setCustomerId(customerId);
        record.setAmount(amount);
        record.setPaymentMethod(PayMethod.CASH); // 现金（货到付款）
        record.setStatus(PaymentStatus.PAID);
        record.setNote("货到付款确认");
        // 审计留痕：记录「谁、在哪个水站」收的款（历史 payment_record.operator_id 全为空，无法追溯）
        record.setOperatorId(AuthContext.getUserId());
        // [2026-09-18 订正] 站别改为**结算站**（v47）：跨站外派单（归属 A / 履约 B）的营收归履约站，
        // 所以这笔现金流水也必须记 B，与 recordCashCollection 同口径。
        // ⚠️ 本方法**全仓零调用**（2026-09-18 grep 复核：只有接口声明与本实现）—— 保留但不得据它判权；
        // 曾经的注释写着「刻意不动、等 [AQ-043] 裁定」，而那条裁定已经下来（确认收款认结算站）。
        Long st = StationUtil.settleStation(o);
        if (st == null) st = AuthContext.getStationId();
        record.setStationId(st);
        record.setCreateTime(LocalDateTime.now());
        record.setUpdateTime(LocalDateTime.now());
        paymentRecordMapper.insert(record);

        orderMapper.markPaidIfCollectable(orderId);
        // [AQ-009] 现场收款（货到付款）成功时入账预收桶押金
        applyDepositOnPaid(orderId);
    }

    @Override
    public void lockTicketPayment(Long orderId, Long customerId, Long productId, int qty, Integer orderStationId) {
        // #26: 直接调用原子扣减，检查返回值判断是否成功
        TicketAccount account = ticketAccountMapper.getByCustomerProductStation(customerId, productId, orderStationId != null ? Long.valueOf(orderStationId) : null);
        if (account == null) {
            throw new BusinessException("该客户还没有此商品的水票账户，无法扣减");
        }
        int affected = ticketAccountMapper.decrementQuantity(account.getId(), qty);
        if (affected == 0) {
            throw new BusinessException("水票余额不足，请先购买水票后再试");
        }
    }

    /**
     * 按订单项扣减水票（水票支付的核心链路）。
     * <p>
     * 此前这是一个空方法，且全项目 0 处调用 —— 客户用"水票支付"下单，水票余额一分不减，可以无限白嫖。
     * 现在在「支付确认成功」与「水票支付创建时」两个入口调用，内部按订单项逐个原子扣减。
     * </p>
     * 扣减幂等：以 ticket_record 中该订单已有的消费流水为依据，已扣过的商品跳过。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deductTickets(Long orderId) {
        if (orderId == null) {
            return;
        }
        Orders order = orderMapper.getById(orderId);
        if (order == null) {
            throw new BusinessException("订单不存在，无法扣减水票");
        }
        Long stationId = ownerStation(order);
        List<OrderItem> items = orderItemMapper.listByOrderId(orderId);
        if (items == null || items.isEmpty()) {
            return;
        }
        for (OrderItem item : items) {
            if (item.getProductId() == null || item.getQuantity() == null || item.getQuantity() <= 0) {
                continue;
            }
            if (ticketRecordMapper.countConsumeByOrderAndProduct(orderId, item.getProductId()) > 0) {
                continue; // 已扣过，幂等跳过
            }
            // consumeTicket 内部使用 remain_quantity >= qty 的原子 SQL，扣减失败（余额不足）直接抛异常
            ticketAccountService.consumeTicket(order.getCustomerId(), item.getProductId(),
                    item.getQuantity(), orderId, stationId);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void refundOrder(Long orderId, String reason) {
        com.example.aquaflow.entity.Orders order = orderMapper.getById(orderId);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }

        // [2026-09-13 修复] 本方法是**唯一**的取消退款入口，但此前完全没有状态门槛：
        // 任何调用方（配送员拒单 / 站长解决 / 站长取消）只要订单存在，就能对
        // **已完成(4)** 甚至 **已取消(5)** 的订单再跑一遍退款。
        // 实测路径：`POST /api/delivery/orders/reject/{id}` 对一张已完成订单调用 →
        // 退水票 + 退支付流水（押金/库存因 orderStatus >= DELIVERED 被跳过）→ 末尾 CAS 4→5 成功，
        // 结果「货已送达、桶在客户手上、钱退回去了、订单变成已取消」。
        // 这里用 isCancellable 明确限定为 待配送/配送中；**已送达(3)/已完成(4)/已取消(5) 一律不可取消**。
        // [2026-09-16] 原先这句后面还写着"已完成须先经 unconfirmOrderCollection 退回"——那个方法已按产品
        // 决定删除（状态不许倒滚）。所以已完成订单出现问题时，出路是**退款流程**或人工调整单，
        // 不是把订单状态改回去。
        // [2026-09-21 产品裁定] **已送达(3) 也被排除**：见 OrderStatus.isCancellable 的注释
        // （货已交付、权益已建而取消链够不着它）。客户拒付改走「配送异常」流程。
        int currentStatus = order.getStatus() != null ? order.getStatus() : 0;
        if (!OrderStatus.isCancellable(currentStatus)) {
            throw new BusinessException(OrderStatus.notCancellableReason(currentStatus));
        }

        Long customerId = order.getCustomerId();
        // ===== 站别口径（跨站外派单必须分清，混用会真丢钱）=====
        // 这里退的是**客户的资产**：预收押金、水票。它们在收款时一律记【归属站】(ownerStation)，
        // 所以取消时也必须退回同一个账户 —— 退到履约站等于把钱记进没收到过钱的站。
        // （收款流水 payment_record.station_id 是另一回事：v47 起按【结算站】写，
        //   但退款流水是"沿用原流水自己的站"（insertRefundRecord），原路返回，与这里无关。）
        // 库存则相反：下单扣的是【履约站】的库存（OrderServiceImpl 用 dto.stationId，抢单池接单=接单站），
        // 因此回补必须回到履约站，否则履约站库存凭空少、归属站凭空多。
        Long ownerStation = ownerStation(order);
        Long fulfillStation = StationUtil.deliveryStation(order);

        // AQ-008: 退还订单已消费的水票（按实际扣减记录回补，避免重复还/漏还）
        // [2026-09-18] 抽成 restoreTicketsForOrder：站长手工退款（refundPayment）要对水票支付的
        // 订单做**同一件事**，两处各写一份必然走样（本仓已有"计价双轨"的前车之鉴）。
        // 站别仍取【归属站】、判据仍是"该单该商品确曾消耗过票"，与抽取前一致；
        // 唯一新增的是"已回补过就跳过"的幂等闸门（两条退款路径可能先后碰到同一张单）。
        restoreTicketsForOrder(order, orderId, reason);

        List<PaymentRecord> paidRecords = paymentRecordMapper.listByOrderId(orderId).stream()
                .filter(r -> r.getStatus() == PaymentStatus.PAID)
                .toList();

        if (paidRecords.isEmpty()) {
            // 没有任何已支付记录 = 这笔订单客户根本没付过钱。
            // 旧实现一律写 REFUNDED，于是从未付款的订单取消后显示"已退款"，
            // 与实际资金流水对不上。正确语义是「已取消」。
            // 未付款订单的付款状态可能是 UNPAID(0，建了现金支付但未确认) 或 PENDING(1，库默认待付款)，
            // 以读取到的当前值作 expected（同一事务内，CAS 仍防并发重复取消）。
            final int prePs = order.getPaymentStatus() != null ? order.getPaymentStatus() : PaymentStatus.PENDING;
            // [2026-09-18] 但「已退款(3)」不许被覆盖成「已取消(4)」：站长手工退款（refundPayment）
            // 先把流水转 REFUNDED，此后订单再被取消就会走到这一支（此时该单已无 PAID 流水），
            // 于是钱退了、支付状态却显示"从未付款"。与 [AQ-022] 同一类错误：
            // 3/4 都是终态、只前进不倒滚（AGENTS.md §1.1）。
            // 这个分支原本只有 0/1 会进来，是 2026-09-18 给 refundPayment 补票据后才出现的组合。
            if (prePs != PaymentStatus.REFUNDED && prePs != PaymentStatus.CANCELLED) {
                orderMapper.updatePaymentStatusIf(orderId, prePs, PaymentStatus.CANCELLED);
            }
        } else {
            for (PaymentRecord r : paidRecords) {
                // 标记原支付记录为已退款。
                // [2026-09-16 修复] 参数顺序原写作 (PAID, REFUNDED) = "把 status 改成 PAID、要求它现在是 REFUNDED",
                // SQL 恒命中 0 行 → 原流水永远停在「已付款」。后果有两层：
                //   ① 站长在支付流水里看到一笔"已付款"，而钱其实已退回客户；
                //   ② 对账等式2 的 p2b 项（订单非已付款却存在 status=2 的流水）会持续报差异。
                // 正确顺序是 (id, 目标状态, 期望状态)。
                paymentRecordMapper.updateStatusTo(r.getId(), PaymentStatus.REFUNDED, PaymentStatus.PAID);

                // 生成退款记录（形状与站长手工退款共用同一个私有方法，见 insertRefundRecord）
                insertRefundRecord(r, refundNoteForOrder(r.getPaymentMethod(), reason));
                // 微信渠道未接入（PayMethod.availableMethods() 里该项恒 disabled），这里**没有**任何
                // API 调用会发生。注释从原文的 `// TODO: 微信支付退款 - 若 paymentMethod=1，调用微信退款 API`
                // 改写成结论：TODO 会被下一次读到它的人当成"已经处理过"，而实际口径是**永不自动退**。
            }

            // AQ-022: 退款后支付状态应为 REFUNDED 而非 UNPAID，反映"已退款"而非"从未付款"
            orderMapper.updatePaymentStatusIf(orderId, PaymentStatus.PAID, PaymentStatus.REFUNDED);
        }

        // ===== 退桶押金 + 清理配送中桶 =====
        // 仅当订单尚未配送完成(status < DELIVERED=4)时，押金桶还在配送中状态，需退还押金并清理配送中记录
        //
        // ⚠️【护栏】本块**不撤销桶权益**，这是对的，但改动前必须读懂这条边界（2026-09-21 裁定）：
        //   桶权益批次是在**送达那一刻**由 `BarrelLedgerService.applyDelivery` 建的，而本块只在
        //   `status < 已送达(3)` 时执行 —— 两者时点不同，本块天然够不着已送达单的权益。
        //   曾担心「取消一张已送达的单会留下没被撤销的权益（客户留着可退押金、我们从未收到押金）」，
        //   **该场景现已不可达**：`isCancellable` 已把 已送达(3) 排除，本方法开头的门槛会直接拒。
        //
        //   **若将来有人放松取消门槛（让已送达也能取消），必须同时补上：**
        //     ① 用 `BarrelLedgerService.consumeLots` 撤销送达时建立的权益；
        //     ② `customer_barrel_over` 加上 delivered 数量（记成客户欠桶）——
        //        否则占用 = 权益 + over 会对不上，物理桶数在账上凭空消失。
        //   判据：`CashOrderLifecycleScenarioTest` 与 `OrderCancelRollbackIntegrationTest`
        //   各有一条用例断言「已送达单不可取消」，放松门槛会让它们变红。
        int orderStatus = order.getStatus() != null ? order.getStatus() : 0;
        if (orderStatus < OrderStatus.DELIVERED) {
            // 1. 释放客户在该站预收的押金
            //    钱当初入在【归属站】(applyDepositOnPaid -> ownerStation)，所以只能从归属站释放。
            //
            //    是否需要释放，只看【有没有入账凭据】(deposit_record 里该单的 PREPAID 流水)，
            //    不能只看 orders.deposit_amount：那个字段是"应收押金"，下单时就写好了，
            //    而钱要等支付成功才入账。未付款订单（含从未付款的现金单、水票余额不足被拒的单）
            //    deposit_amount 照样 > 0，此时账户里一分钱都没有，去"释放"就是无中生有。
            //
            //    真入过账才可能余额不足（账被人为改动 / 押金曾被手工退过）。
            //    旧实现对 affected=0 静默跳过，于是「订单已取消、押金却留在账上」，既无流水也无提示。
            //    这里显式拒绝：宁可让取消失败并被人发现，也不要留下查不出来的敞口。
            BigDecimal depositAmount = order.getDepositAmount();
            boolean depositCredited = depositRecordMapper.countByOrderAndType(orderId, DepositType.PREPAID) > 0;
            if (depositCredited && depositAmount != null && depositAmount.compareTo(BigDecimal.ZERO) > 0) {
                int affected = customerDepositAccountMapper.decreaseBalance(customerId, ownerStation, depositAmount);
                if (affected == 0) {
                    throw new BusinessException("取消失败：该客户在本水站的押金余额不足 ¥" + depositAmount
                            + "，无法释放本单预收押金，请人工核对押金流水后重试");
                }
                DepositRecord dr = new DepositRecord();
                dr.setCustomerId(customerId);
                dr.setStationId(ownerStation);
                dr.setType(DepositType.CANCEL_PREPAID); // 8 取消订单释放预收押金
                dr.setAmount(depositAmount.negate()); // 负金额表示退出
                dr.setNote("订单取消释放押金: " + (reason != null ? reason : ""));
                // related_order_id 必须落库：它不只是"备注"，还是"这笔释放属于哪张订单"的唯一凭据。
                // 旧实现漏了这一行，于是（a）按订单反查押金流水查不到任何释放记录，
                // （b）入账侧靠 countByOrderAndType(orderId, PREPAID) 做幂等、释放侧却无从判断是否已释放。
                dr.setRelatedOrderId(orderId);
                dr.setOperatorId(null);
                dr.setCreateTime(LocalDateTime.now());
                depositRecordMapper.insert(dr);
            }

            // 2. 清理该订单的配送中桶记录（PENDING 状态）
            List<CustomerBarrelInTransit> inTransitList = customerBarrelInTransitMapper.listPendingByOrderId(orderId);
            if (inTransitList != null && !inTransitList.isEmpty()) {
                customerBarrelInTransitMapper.deleteByOrderId(orderId);
            }

            // 3. 回补库存：按下单时"实际扣减量"回补，而不是订单数量。
            //    下单时库存不足只扣了现有库存（deducted_qty < quantity），若按 quantity 回补会凭空多出库存，
            //    反复"下单-取消"即可刷出无限库存。
            //    站别：回到【履约站】—— 下单扣的就是履约站的库存。
            List<OrderItem> items = orderItemMapper.listByOrderId(orderId);
            if (items != null) {
                for (OrderItem item : items) {
                    if (item.getProductId() == null || item.getQuantity() == null || item.getQuantity() <= 0) {
                        continue;
                    }
                    int restoreQty = item.getDeductedQty() != null
                            ? Math.min(item.getDeductedQty(), item.getQuantity())
                            : item.getQuantity();
                    if (restoreQty > 0) {
                        inventoryMapper.increaseStock(fulfillStation, item.getProductId(), restoreQty);
                        // [AQ-029] 退款回补库存写流水
                        inventoryService.recordChange(fulfillStation, item.getProductId(), restoreQty,
                                InventoryChangeType.REFUND_RESTORE, orderId, AuthContext.getUserId(), "退款回补");
                    }
                }
            }
        }

        // [Phase C] CAS：以读取到的当前状态为 expected，防止取消期间订单状态被并发改动。
        // affected 必须检查：上面已按订单释放过押金 / 退过水票 / 回过库存，这是本方法唯一一道
        // "同一订单只允许完成一次取消"的闸门。旧实现不检查返回值，重复或并发取消时会再走一遍
        // 释放段（押金被重复退、库存被重复回补）。
        int cancelled = orderMapper.updateStatusIf(orderId, order.getStatus(), OrderStatus.CANCELLED);
        if (cancelled == 0) {
            throw new BusinessException("订单状态已变更（可能已被取消），请刷新后重试");
        }
    }

    /**
     * 站长手工退款（单笔支付流水）。
     *
     * <p>覆盖范围 = <b>一笔流水</b>，不是一张订单：它<b>不</b>取消订单、<b>不</b>退押金、
     * <b>不</b>清配送中桶、<b>不</b>回补库存 —— 那些属于订单取消链（{@link #refundOrder}）。
     * 用它的场景是"客户投诉多收/重复付款"，订单本身还要继续履约。</p>
     *
     * <h3>[2026-09-18] 修掉的三处真实缺口</h3>
     * <p>原文自陈「只做两件事：原流水 CAS 成 REFUNDED、订单 payment_status 从 PAID 改成 REFUNDED」。
     * 三处后果都在钱上：</p>
     * <ol>
     *   <li><b>没有"钱流出去了"的凭据</b>：原流水被改成"已退款"，却<b>不</b>新增负金额冲正流水
     *       （{@link #refundOrder} 一直有）。于是资金流水里既没有这笔支出，
     *       对账等式2 也无从复核"退了多少、按什么方式退的"。</li>
     *   <li><b>水票一点没回来</b>：水票支付的钱就是票。原实现对 method=3 只改流水状态，
     *       客户账上少掉的票一张都不补 —— 界面上显示"已退款"，票却永远没了（AGENTS.md §8.15）。</li>
     *   <li><b>微信假装退成功</b>：method=1 时同样只改状态，而微信渠道根本没接入
     *       （{@code PayMethod.availableMethods()} 里该项恒 disabled），钱一分没退。</li>
     * </ol>
     *
     * <h3>刻意与 {@link #refundOrder} 不一致的一处（不是疏漏）</h3>
     * <p>微信（method=1）在本方法<b>直接拒绝</b>，在 {@code refundOrder} 里<b>只记流水 + 写清"需线下退款"</b>。
     * 理由：本方法的唯一产出就是"钱"，钱退不出去时它没有任何有意义的下半场，装作成功等于制造
     * "界面说退了、账上没退"；而订单取消链必须跑完（还要连退押金 / 回补库存 / 清配送中桶），
     * 为一笔退不出去的钱把整条取消链卡死，会让历史微信单永远取消不掉。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void refundPayment(Long paymentId, String note) {
        PaymentRecord record = paymentRecordMapper.getById(paymentId);
        if (record == null) throw new BusinessException("支付记录不存在");
        // #32: 校验当前状态，只有PAID才能退款
        if (record.getStatus() != PaymentStatus.PAID) {
            throw new BusinessException("仅已支付记录可退款，当前状态: " + record.getStatus());
        }

        // ===== 场景① 无订单的线上购票流水：本批不实现，但必须拒绝得明明白白 =====
        // 这笔钱买到的是水票，而水票在 confirmPayment 时就已经入账（creditPurchasedTickets）。
        // 「退款」= 把已入账的票扣回来，难度与风险都不在同一个量级：
        //   · 票可能已经被用掉了 —— 扣回必须**先校验余额是否够**，不够就得拒绝（不能透支）；
        //   · 扣回同样要过批次账（TicketLotService.consumeFifo + 反写 ticket_record），
        //     否则对账 E8 立刻报不平；
        //   · 已经发出去的票是否允许回收，本身还需要产品口径（现金退款？还是站长资产调整单的
        //     人工扣减 + 置流水 REFUNDED？）。
        // 所以这里宁可拒绝，也**绝不**静默只改流水状态：那样界面显示"已退款"，客户票却还在账上，
        // 等于白送 —— 与 [AQ-017]「要求了补偿却没执行也必须失败」同一条判据。
        if (record.getOrderId() == null && record.getTicketQty() != null && record.getTicketQty() > 0) {
            throw new BusinessException("该笔为无订单的线上购票支付，退款需先扣回已入账的水票"
                    + "（票可能已被使用，余额不足时不能透支），本批未开放此路径；"
                    + "请走站长资产调整单处理，或联系运维按流水手工核销");
        }

        // ===== 场景② 微信（1）：真实渠道未接入，不能假装退成功 =====
        // PayMethod.availableMethods() 在没有模拟渠道时把微信置为 disabled，全系统没有一处
        // 微信退款 API 调用。若照旧把流水标成已退款，界面会显示"已退款"，而钱仍在客户账上没动。
        // [2026-09-20] 模拟渠道开启时放行：那笔钱本来就是模拟进来的，"原路退回"同样是模拟，
        // 两边口径一致；否则会出现"模拟能付、但一取消订单就退不掉"的怪状态，联调走不完。
        if (Integer.valueOf(PayMethod.WECHAT).equals(record.getPaymentMethod()) && !mockWechatPay) {
            throw new BusinessException("微信支付渠道未接入，无法自动原路退回，请线下退款并登记");
        }

        // ===== 水票（3）：钱就是票，必须原路回补水票 =====
        // ⚠️ 必须放在 CAS 改状态**之前**：回补失败（余额/批次账对不上）时整事务回滚，
        // 流水状态也要跟着回到「已付款」，不能留下"流水说退了、票没补"的中间态。
        // 站别用**归属站**（钱当初收在归属站），与 refundOrder 同源，不回补 = 客户票凭空少。
        Orders ticketOrder = record.getOrderId() != null ? orderMapper.getById(record.getOrderId()) : null;
        if (Integer.valueOf(PayMethod.TICKET).equals(record.getPaymentMethod())
                && record.getOrderId() != null && ticketOrder != null) {
            restoreTicketsForOrder(ticketOrder, record.getOrderId(), note);
        }

        // [2026-09-16 修复] 参数顺序：(id, 目标状态, 期望状态)。原写作 (PAID, REFUNDED) 会让这条 CAS
        // 恒命中 0 行 —— 退款成功了，但支付流水仍显示「已付款」（本类 refundOrder 里同一处也已修）。
        // [2026-09-18] 补上 0 行检查：上面的开销（回补水票 + 建批次）是不能重复吃的副作用，
        // 而这一句正是"同一笔流水只允许退一次"的唯一闸门 —— 不检查就等于把闸门焊死在开位。
        int refunded = paymentRecordMapper.updateStatusTo(paymentId, PaymentStatus.REFUNDED, PaymentStatus.PAID);
        if (refunded == 0) {
            throw new BusinessException("退款失败，该笔支付状态已变更，请刷新后重试");
        }

        // ===== 负金额冲正流水：与 refundOrder 共用同一个私有方法，杜绝两套口径 =====
        // 原文把"退款"实现成"把原流水改成已退款"，于是资金流水里看不到这笔支出。
        insertRefundRecord(record, manualRefundNote(record, note));

        if (record.getOrderId() != null) {
            // [2026-09-16] 原为 updatePaymentStatusIf(orderId, PAID, UNPAID)：把订单支付状态从
            // 已付款(2) 倒滚回 未支付(0)。后果与 [AQ-022]（refundOrder 那条）完全一样 ——
            // 钱退给客户了，订单却显示"从未付款"，对账与客服排查都会读到假的资金状态；
            // 也违反 AGENTS.md §1「支付状态只前进、不倒滚，3/4 是终态」。
            // 正确值是与退款流水一致的 已退款(3)：markPaidIfCollectable 从 0/1 迁入，绝不复活 3/4，
            // 因此退款后的订单不会被重新收一遍钱。
            orderMapper.updatePaymentStatusIf(record.getOrderId(), PaymentStatus.PAID, PaymentStatus.REFUNDED);
        }
    }

    /**
     * 按订单项把该订单消耗掉的水票原路回补（<b>唯一的</b>水票回补实现，供两条退款路径共用）。
     *
     * <p>[2026-09-18] 从 {@code refundOrder} 抽出来，与站长手工退款（{@code refundPayment}）共用。
     * 抽出来的理由是"同一件事只能有一份口径"：水票回补要同时满足四条约束，
     * 任何一条在复制粘贴时漏掉，对账 E8 就会报不平或让客户凭空多票 ——</p>
     * <ol>
     *   <li>只回补**确曾消耗过**的商品（{@code countConsumeByOrderAndProduct > 0}），否则会给没扣过票的单凭空发票；</li>
     *   <li>已经回补过就跳过（{@code countRefundByOrderAndProduct > 0}）：两条退款路径可能先后碰到同一张单，
     *       第二次回补会撞 {@code uk_ticket_consume}，报出来却是 500「系统错误」；</li>
     *   <li>回补必须走 {@code ticketAccountService.refundTicket}（<b>不要</b>直接改 ticket_account /
     *       ticket_lot）：余额的真相源是 {@code ticket_lot}，批次是唯一写入口，
     *       单价按流水里**当时消耗的批次单价**还原（见 docs/design/19）；</li>
     *   <li>站别一律【归属站】（{@code ownerStation}）—— 水票当初就扣在归属站（{@code deductTickets}
     *       用 ownerStation，是"客户买在哪个站的资产"），退回履约站等于把票记进没扣过票的站
     *       （跨站外派单 [AQ-043]）。⚠️ 这里说的只是**票**：收款流水 v47 起按结算站写，
     *       两者刻意不同，别顺手"统一"。</li>
     * </ol>
     *
     * @param order       订单（用于取归属站与客户）
     * @param orderId     订单ID
     * @param ticketReason 退款事由，仅用于日志
     */
    private void restoreTicketsForOrder(Orders order, Long orderId, String ticketReason) {
        Long customerId = order.getCustomerId();
        Long ownerStation = ownerStation(order);
        List<OrderItem> refundItems = orderItemMapper.listByOrderId(orderId);
        if (refundItems == null) {
            return;
        }
        int restored = 0;
        for (OrderItem item : refundItems) {
            if (item.getProductId() == null || item.getQuantity() == null || item.getQuantity() <= 0) {
                continue;
            }
            // 仅当该订单该商品确曾消耗水票时才归还
            if (ticketRecordMapper.countConsumeByOrderAndProduct(orderId, item.getProductId()) <= 0) {
                continue;
            }
            // [2026-09-18] 幂等闸门前移：这张单的该商品若已经回补过（两条退款路径可能先后碰到
            // 同一张单：站长先在订单详情点「退款」，之后订单又被取消/拒单），再来一次会撞
            // uk_ticket_consume(order_id, product_id, source='退款')。撞键虽然也能保证票不多退
            // （整个事务回滚），但报出来的是 code=500「系统错误」，
            // 把"这张单已经退过了"伪装成后端故障。这里提前判掉，错误留在原路径，见 §8.17。
            if (ticketRecordMapper.countRefundByOrderAndProduct(orderId, item.getProductId()) > 0) {
                continue;
            }
            ticketAccountService.refundTicket(customerId, item.getProductId(), item.getQuantity(), orderId, ownerStation);
            restored++;
        }
        if (restored > 0) {
            log.info("[水票回补] orderId={}, customerId={}, stationId={}, 商品数={}, 事由={}",
                    orderId, customerId, ownerStation, restored, ticketReason);
        }
    }

    /**
     * 插入"负金额退款冲正流水" —— <b>退款凭据的唯一写法</b>，两条退款路径共用。
     *
     * <p>[2026-09-18] 从 {@code refundOrder} 里原样抽出，行为逐字未变：金额与水费/押金/超出桶数
     * 一律取负、支付方式/客户/水站沿用原流水、状态 REFUNDED。抽出来的直接原因：
     * {@code refundPayment} 原先<b>没有</b>这条凭据（只把原流水改成已退款），
     * 补的时候若复制粘贴，就又多出一套口径 —— 日后改金额方向必然只改一处。
     * 本仓对"同一件事两份实现"的代价有明确记录（计价双轨、前端各自维护 1/2/3 映射表）。</p>
     *
     * <p><b>不要把 {@code deliveryFee} / {@code floorFee} 改成取负</b>：原实现就不设这两个字段，
     * 抽出来时保持逐字不变；mapper 的 {@code IFNULL(#{deliveryFee}, 0.00)} 会把 null 兜成 0，
     * 改方向等于凭空改动资金流水（见 {@code PaymentRecordMapper.insert} 的注释）。</p>
     *
     * <p>{@code status} 保持 {@code REFUNDED}（而不是 CANCELLED）：对账等式2 的 p2d 项要求
     * 「订单 payment_status=3 时必须存在 status=3 的流水」。{@code operatorId} 也保持不设 ——
     * 与原实现一致；要追溯"谁点的退款"，看原流水所在订单的操作日志与 note。</p>
     *
     * @param original   被退的原支付流水（必须是 PAID，调用方已校验）
     * @param refundNote 备注（本方法负责按 {@code payment_record.note} 的 varchar(200) 截断）
     */
    private void insertRefundRecord(PaymentRecord original, String refundNote) {
        PaymentRecord refundRecord = new PaymentRecord();
        // order_id 直接沿 original 取（两条路径传进来的 original 就是被退的那条流水）。
        refundRecord.setOrderId(original.getOrderId());
        refundRecord.setCustomerId(original.getCustomerId());
        refundRecord.setStationId(original.getStationId());
        refundRecord.setAmount(negate(original.getAmount())); // 负金额表示退款
        refundRecord.setWaterAmount(negate(original.getWaterAmount()));
        refundRecord.setBarrelDeposit(negate(original.getBarrelDeposit()));
        refundRecord.setExcessBarrels(original.getExcessBarrels() != null ? -original.getExcessBarrels() : 0);
        refundRecord.setPaymentMethod(original.getPaymentMethod());
        refundRecord.setStatus(PaymentStatus.REFUNDED);
        refundRecord.setNote(truncateNote(refundNote));
        refundRecord.setCreateTime(LocalDateTime.now());
        refundRecord.setUpdateTime(LocalDateTime.now());
        paymentRecordMapper.insert(refundRecord);
    }

    /** 金额取负，{@code null} 一律按 0 处理（原 refundOrder 内联写法即 {@code != null ? negate : ZERO}）。 */
    private static BigDecimal negate(BigDecimal v) {
        return v != null ? v.negate() : BigDecimal.ZERO;
    }

    /**
     * {@code payment_record.note} 是 {@code varchar(200)}，而 {@code PUT /api/payments/{id}/refund}
     * 的 DTO 允许 500 字。不截断的话长备注会在 INSERT 阶段报「Data too long for column 'note'」，
     * 把一次正常退款变成 500 系统异常，且站长完全看不懂（AGENTS.md §8.21 判据）。
     */
    private static String truncateNote(String note) {
        if (note == null) {
            return null;
        }
        return note.length() <= 200 ? note : note.substring(0, 200);
    }

    /**
     * 订单取消链的退款备注。
     *
     * <p>[2026-09-18] 基数文案与抽取前完全一致（{@code "退款：" + reason}），只是按支付方式追加了
     * 一句渠道说明：微信追加「未接入，需线下退款并登记」，现金追加「钱由站长当面退还」。
     * 这两句是给人看的凭据说明 —— 站长在支付流水里必须能一眼看出"这笔钱到底有没有真的退出去"。</p>
     *
     * <p>[2026-09-20] 因此改成**实例方法**：微信那支现在要看 {@code mockWechatPay}
     * （模拟渠道开启时文案改为「原路退回亦为模拟」）。这是注释契约的一部分 ——
     * 把测试期的模拟资金记成真实退款凭据，比缺一句文案危险得多。</p>
     */
    private String refundNoteForOrder(Integer paymentMethod, String reason) {
        String base = "退款：" + (reason != null ? reason : "订单取消");
        if (Integer.valueOf(PayMethod.WECHAT).equals(paymentMethod)) {
            if (mockWechatPay) {
                // [2026-09-20] 模拟渠道：这笔钱本来就是模拟收到的，"原路退回"同样是模拟，
                // 备注必须写明是模拟，否则日后翻流水会把测试数据当成真实资金记录。
                base = base + "（模拟微信渠道，原路退回亦为模拟）";
            } else {
                // [2026-09-18] 微信渠道未接入，本链**不会**自动原路退回（渠道未接入，
                // PayMethod.availableMethods() 里该项恒 disabled）。但不阻断取消：
                // 取消还要连锁退押金 / 回补库存 / 清配送中桶，为一笔退不出去的钱卡死整条链，
                // 会让历史微信单永远取消不掉。所以这里只记流水 + 写清"需线下退款"，让站长看得见。
                // 对比：站长手工退款（refundPayment）对 method=1 直接拒绝 —— 那个方法的唯一产出就是"钱"，
                // 退不出去时没有有意义的下半场，不能假装成功。
                log.warn("[退款] 微信渠道未接入，本条退款流水仅为凭据，需线下退款: orderId 见流水, paymentMethod={}, reason={}",
                        paymentMethod, reason);
                base = base + "（微信渠道未接入，需线下退款并登记）";
            }
        } else if (Integer.valueOf(PayMethod.CASH).equals(paymentMethod)) {
            // 现金退款没有线上渠道可言，钱由站长当面退还；写进备注，对账/客服才有依据。
            base = base + "（现金，钱由站长当面退还）";
        }
        return base;
    }

    /**
     * 站长手工退款的备注（含支付方式与原因，便于日后从流水反查"这笔钱怎么出去的"）。
     * <p>金额一律取数据库里那一笔的实际值，不重新计算 —— 备注只是文案，不是账。</p>
     */
    private static String manualRefundNote(PaymentRecord record, String note) {
        String reason = (note != null && !note.trim().isEmpty()) ? note.trim() : "站长手工退款";
        StringBuilder sb = new StringBuilder("手工退款（")
                .append(PayMethod.textOf(record.getPaymentMethod()))
                .append("）：")
                .append(reason);
        if (Integer.valueOf(PayMethod.TICKET).equals(record.getPaymentMethod())) {
            sb.append("；已按原路径回补水票");
        } else if (Integer.valueOf(PayMethod.CASH).equals(record.getPaymentMethod())) {
            sb.append("；钱由站长当面退还");
        }
        return sb.toString();
    }

    @Override
    public List<PaymentRecord> listByOrderId(Long orderId) {
        return paymentRecordMapper.listByOrderId(orderId);
    }

    @Override
    public List<PaymentRecord> listByCustomerId(Long customerId) {
        return paymentRecordMapper.listByCustomerId(customerId);
    }

    @Override
    public List<PaymentRecord> listAll(Long stationId, int limit) {
        // [AQ-052] 旧实现丢弃入参、恒调 listByStationId(null)，一旦 SQL 被"顺手优化"成动态条件
        // 就会变成全平台流水泄露。改为必须按站查询。
        return paymentRecordMapper.listAllByStation(stationId, limit);
    }

    @Override
    public List<PaymentRecord> listWithFilter(Long stationId, Integer status, Integer paymentMethod, int limit) {
        return paymentRecordMapper.listByStation(stationId, status, paymentMethod, limit);
    }

    @Override
    public Map<String, Object> quote(Long customerId, Long stationId, Integer paymentMethod,
                                     List<Map<String, Object>> items, Long addressId) {
        Map<String, Object> result = new HashMap<>();

        // 货到付款能不能选：走唯一判据（开关 + 欠款即停），并把原因一并下发 ——
        // 前端只知道 false 是不知道为什么的。
        String offlineBlockReason = offlinePaymentBlockReason(customerId, stationId);
        boolean allowOffline = offlineBlockReason == null;
        result.put("allowOfflinePayment", allowOffline);
        result.put("offlinePaymentBlockReason", offlineBlockReason);
        // 可用支付方式由后端下发（含文案与默认选中项），前端禁止自带 1/2/3 映射表，
        // 否则再次出现"前端 2=水票、后端 2=现金"这类错位。
        result.put("methods", PayMethod.availableMethods(allowOffline, mockWechatPay));
        result.put("defaultMethod", PayMethod.defaultMethod(allowOffline, mockWechatPay));

        if (items == null || items.isEmpty() || stationId == null) {
            result.put("waterAmount", BigDecimal.ZERO);
            result.put("barrelDeposit", BigDecimal.ZERO);
            result.put("extraDeposit", BigDecimal.ZERO);
            result.put("extraDepositBuckets", 0);
            // 费用字段即使为空单也要下发，否则前端得判 undefined —— 判 undefined 就会长出第二套默认值
            result.put("deliveryFee", BigDecimal.ZERO);
            result.put("floorFee", BigDecimal.ZERO);
            result.put("warnings", java.util.Collections.emptyList());
            // 键名与下方同形：前端读 d.ticketPay 时不必判 undefined（本仓对"判 undefined 就会长出第二套默认值"有记录）
            result.put("ticketPay", null);
            result.put("blocked", false);
            result.put("blockReason", null);
            result.put("totalAmount", BigDecimal.ZERO);
            return result;
        }

        BigDecimal totalWaterAmount = BigDecimal.ZERO;
        int totalExtraBuckets = 0;
        BigDecimal totalExtraDeposit = BigDecimal.ZERO;

        Map<Long, Integer> barrelByProduct = new java.util.HashMap<>();

        // [v54 统一水票] 水票支付的可用性：客户在结算页点了「水票」就得知道
        // "这单能不能用票 / 用的是定制票还是本站统一票 / 票够不够"，
        // 而不是等提交订单后才由 consumeTicket 抛错 —— 那时地址、时段都白填了一遍。
        //
        // [2026-09-19 第十批] 从"只给一句提示"升级为**结构化的抵扣预览**：
        // 结算页要按产品口径「水票支付时只计费除去水票的部分」把计费区改掉，
        // 所以金额也得由后端算好下发（前端不得自算金额，这是本仓的硬规则）。
        List<String> ticketWarnings = new java.util.ArrayList<>();
        List<TicketLine> ticketLines = new java.util.ArrayList<>();
        BigDecimal ticketCoveredAmount = BigDecimal.ZERO;
        int ticketTotalQty = 0;
        boolean payByTicket = Integer.valueOf(PayMethod.TICKET).equals(paymentMethod);

        for (Map<String, Object> item : items) {
            Long productId = item.get("productId") != null ? Long.valueOf(item.get("productId").toString()) : null;
            Integer quantity = item.get("quantity") != null ? Integer.valueOf(item.get("quantity").toString()) : 1;
            if (productId == null || quantity <= 0) continue;

            Product product = productMapper.getById(productId);
            if (product == null) continue;

            Inventory inv = inventoryMapper.getByStationAndProduct(stationId, productId);
            if (inv == null || inv.getEnabled() == null || !Integer.valueOf(1).equals(inv.getEnabled())) continue;

            // 单价统一走 PriceUtil：报价与下单共用同一算法
            // 旧实现用 paymentMethod==2 判断水票价，而 PayMethod 定义 2=现金、3=水票，是错位的
            // [AQ-031] 传入站级库存，水票价以 inventory.ticket_price 为准，与下单侧保持一致
            BigDecimal unitPrice = PriceUtil.calcUnitPrice(product, inv, paymentMethod);

            totalWaterAmount = totalWaterAmount.add(unitPrice.multiply(BigDecimal.valueOf(quantity)));

            // [2026-09-19] 只有桶装水进桶/押金体系（判据唯一实现在 util/BarrelScope）：
            // 非桶装商品**不收押金**（押金是循环桶的押金，两处列注释都写着"仅桶装水使用"），
            // 也不进桶账（见 BarrelLedgerService.applyDelivery 的白名单过滤）。
            // 历史实现在这里按件收非桶装押金，结果钱进了押金账户却没有退还路径（无押金条可核销）。
            if (BarrelScope.isBarrel(product)) {
                barrelByProduct.merge(productId, quantity, Integer::sum);
            }

            if (payByTicket) {
                TicketLine line = ticketLineOf(customerId, stationId, product, quantity);
                ticketLines.add(line);
                ticketTotalQty += quantity;
                if (line.covered()) {
                    // 覆盖部分的水费 = 该行水票价 × 行数量（与 deductTickets 扣的是同一批桶）
                    ticketCoveredAmount = ticketCoveredAmount
                            .add(unitPrice.multiply(BigDecimal.valueOf(quantity)));
                }
            }
        }
        // 逐行文案（定制/统一/不能用票）在循环外统一生成：与结构化数据同源，不会两处对不上。
        // 定制票够的行返回 null（不必打扰客户），这里必须判掉 —— 否则 null 会进 JSON 数组。
        for (TicketLine line : ticketLines) {
            String text = ticketLineText(line);
            if (text != null) {
                ticketWarnings.add(text);
            }
        }

        // 桶装水押金：按商品独立计算，缺多少桶交多少押金
        if (!barrelByProduct.isEmpty()) {
            List<CustomerBarrelAsset> assets = customerBarrelAssetMapper.listByCustomerAndStation(customerId, stationId);
            Map<Long, Integer> heldByProduct = new java.util.HashMap<>();
            if (assets != null) {
                for (CustomerBarrelAsset a : assets) {
                    heldByProduct.merge(a.getProductId(), a.getQuantity() != null ? a.getQuantity() : 0, Integer::sum);
                }
            }

            for (Map.Entry<Long, Integer> e : barrelByProduct.entrySet()) {
                Long pid = e.getKey();
                int needed = e.getValue();
                int held = heldByProduct.getOrDefault(pid, 0);
                int shortage = Math.max(0, needed - held);
                if (shortage > 0) {
                    totalExtraBuckets += shortage;
                    Product p = productMapper.getById(pid);
                    // 缺桶押金取**本站押金**（inventory.deposit_price 优先），与下单侧同口径
                    Inventory pInv = inventoryMapper.getByStationAndProduct(stationId, pid);
                    BigDecimal deposit = PriceUtil.calcDeposit(p, pInv);
                    totalExtraDeposit = totalExtraDeposit.add(deposit.multiply(BigDecimal.valueOf(shortage)));
                }
            }
        }

        // ===== [v35] 配送计费：与下单侧调**同一个服务**、传同样的入参 =====
        // 水费口径 = totalWaterAmount（不含押金与运费）；桶数口径 = 桶装水商品的数量之和，
        // 与 OrderServiceImpl 的 totalNeededBuckets 同源（都只数 category=1）。
        // 任何一侧内联算费用都会重新制造"计价双轨"事故（结算页一个价、下单另一个价）。
        int totalBuckets = 0;
        for (Integer q : barrelByProduct.values()) {
            totalBuckets += q == null ? 0 : q;
        }
        com.example.aquaflow.util.DeliveryFeeUtil.FeeResult fee =
                deliveryFeeService.calcForOrder(customerId, stationId, addressId, totalBuckets, totalWaterAmount);

        // 押金合计 = 缺桶押金(totalExtraDeposit)；非桶装不再有押金（2026-09-19），故这里没有第二项
        BigDecimal totalAmount = totalWaterAmount.add(totalExtraDeposit).add(fee.getFeeTotal());

        result.put("waterAmount", totalWaterAmount);
        // ⚠️ 键名 barrelDeposit 是历史遗留的**误称**：它装的曾是"非桶装商品的押金"（`barrelDeposit` 与
        // `extraDeposit` 语义正好相反），顾客端 create.js 读它当"押金合计"展示。2026-09-19 起非桶装不收押金
        // → **恒为 0**。保留键名是为了不动两端契约；真正的"桶押金"看 extraDeposit（缺桶押金）。
        // 别看到 0 就以为是 bug，也**别把非桶装押金加回来**（那会让钱进押金账户却无退还路径）。
        result.put("barrelDeposit", BigDecimal.ZERO);
        result.put("extraDeposit", totalExtraDeposit);
        result.put("extraDepositBuckets", totalExtraBuckets);
        // 费用单独下发，前端各自展示；totalAmount 是含费用的合计
        result.put("deliveryFee", fee.getDeliveryFee());
        result.put("floorFee", fee.getFloorFee());
        // 水票可用性提示与配送费提示**合并**下发（前端只认一个 warnings 数组，新增一个键等于前端不显示）
        List<String> allWarnings = new java.util.ArrayList<>();
        if (fee.getWarnings() != null) {
            allWarnings.addAll(fee.getWarnings());
        }
        allWarnings.addAll(ticketWarnings);
        result.put("warnings", allWarnings);
        // blocked/blockReason 让前端在提交前就能拦住并显示原因，与下单侧的拒绝判据同源
        result.put("blocked", fee.isBlocked());
        result.put("blockReason", fee.getBlockReason());
        result.put("totalAmount", totalAmount);

        result.put("ticketPay", payByTicket
                ? ticketPayPreview(ticketLines, ticketTotalQty, ticketCoveredAmount, totalAmount)
                : null);

        // 企业身份提示（v51）：**只算水** —— 桶装水数量与"水费"（不含押金/配送费/楼层费）两条口径，
        // 命中任一即提示；阈值按站配、没配过用平台默认（30 桶）。开关关着时连字段都不下发（前端也就没有入口）。
        // ⚠️ 传的是 totalWaterAmount（水费），不是 totalAmount（含押金与费用的合计）——
        // 押金不算进企业身份的判定（产品 2026-09-19：「企业的只看水，押金不算」）。
        result.put("enterpriseHint",
                enterpriseIdentityService.largeOrderHint(customerId, stationId, totalBuckets, totalWaterAmount));


        return result;
    }

    /** 结算页水票抵扣预览的**单行**事实（每行一个订单项）。 */
    private record TicketLine(String name, int qty, boolean usable, int balance, boolean covered) {}

    /**
     * 算一行的水票抵扣事实。**判据与真实扣票逐条对齐**（这是本方法唯一的价值所在）：
     *
     * <ol>
     *   <li>账户恒为**该商品**（2026-09-20 产品拍板：按统一折扣买的票只能抵那款水）；</li>
     *   <li>该商品得**能用票**（定制票已开，或本站配了统一折扣且它是桶装水）——
     *       判据与下单闸门、购票路径同源，见 {@code TicketTierService}；</li>
     *   <li>该账户余额 <b>≥ 本行数量</b> 才算这一行能抵 ——
     *       {@code consumeTicket} 用的是 {@code remain_quantity >= qty} 的原子 SQL，
     *       <b>不够就整行失败</b>，不会"先抵一部分"。所以这里也必须是全有或全无，
     *       否则结算页会说"还差 1 桶"而实际提交时整行被拒。</li>
     * </ol>
     */
    private TicketLine ticketLineOf(Long customerId, Long stationId, Product product, int qty) {
        String name = product.getName() != null ? product.getName() : ("商品" + product.getId());
        Inventory inv = inventoryMapper.getByStationAndProduct(stationId, product.getId());
        // 与下单闸门同一判据：定制票开了 → 可用；否则本站有统一折扣且是桶装水 → 可用
        boolean usable = ticketTierService.usesCustomTicket(product, inv)
                || (BarrelScope.isBarrel(product)
                    && ticketAccountService.unifiedDiscountConfigured(stationId));
        int balance = usable ? ticketAccountService.balanceOf(customerId, product.getId(), stationId) : 0;
        boolean covered = usable && balance >= qty;
        return new TicketLine(name, qty, usable, balance, covered);
    }

    /** 单行的可读文案（不能用票 / 余额不足两种）。文案一律后端下发，前端不自造。 */
    private String ticketLineText(TicketLine line) {
        if (!line.usable()) {
            return "「" + line.name() + "」不能用票支付：本站没有为它开通水票，也没有配置可用的统一折扣";
        }
        if (line.balance() <= 0) {
            return "「" + line.name() + "」的水票余额为 0，请先购买水票";
        }
        if (!line.covered()) {
            // 不存在"先抵一部分"：consumeTicket 要求余额 ≥ 整行数量，所以必须说清"整单会失败"
            return "「" + line.name() + "」的水票余额 " + line.balance() + " 张、本单需要 " + line.qty() + " 张";
        }
        return null;   // 票够：不必打扰客户
    }

    /**
     * 水票抵扣预览（结算页据此把计费区改成"只计费除去水票的部分"）。
     *
     * <p><b>⚠️ 先把"票到底结清了什么"说清楚</b>（这一条决定了字段口径，别想当然）：
     * 水票支付是把**整单**置为已付 —— {@code createPayment} 在 {@code paymentMethod=3} 时
     * 扣票成功后直接 {@code status=PAID} + {@code markPaidIfCollectable} + {@code applyDepositOnPaid}，
     * 也就是<b>水费、押金、配送费、楼层费全部随票一并结清</b>，客户在门口不再掏钱。
     * 所以"票够"时客户这次要付的是 <b>0</b>，而不是 {@code 合计 − 水费}。</p>
     *
     * <p>字段（前端不得自造同义字段）：</p>
     * <ul>
     *   <li>{@code needQty} —— 本单需要几张票（= 需要走票的行数量合计）；</li>
     *   <li>{@code coverQty} —— 按唯一判据选出的账户实际能覆盖几件。**不够就是整单用不了票**
     *       （{@code consumeTicket} 要求账户余额 ≥ 本行数量，不存在"先抵一部分"）；</li>
     *   <li>{@code fullyCovered} —— 整单能否全部用票结清；</li>
     *   <li>{@code coverAmount} —— 被票覆盖那部分对应的**水费**（账目口径的展示值）；</li>
     *   <li>{@code payableAmount} —— <b>客户这次实际要付的钱</b>：能全抵 → {@code 0}；
     *       抵不掉 → 订单全额（因为本单根本用不了票，得改选别的支付方式全额付）；</li>
     *   <li>{@code title} / {@code hint} —— 弹窗标题与一句可读结论（都由后端下发）。
     *       抵不掉时**必须区分两种情况**：某商品<b>根本不能用票</b>（补票也没用，只能改选）
     *       与<b>票不够</b>（补票或者改选）。两者混成一句话会把客户引到错的动作上。
     *       另外必须说清"**当前不支持票 + 现金/微信混合支付**，票不会被扣、留在账户里下次用" ——
     *       否则客户会以为可以补差价，或者以为票已经被扣掉了。</li>
     * </ul>
     */
    private Map<String, Object> ticketPayPreview(List<TicketLine> lines,
                                                 int totalQty, BigDecimal coveredAmount, BigDecimal totalAmount) {
        int coveredQty = 0;
        String unavailableName = null;    // 第一项"根本不能用票"的商品（如未开水票的瓶装水）
        for (TicketLine l : lines) {
            if (l.covered()) {
                coveredQty += l.qty();
            } else if (!l.usable() && unavailableName == null) {
                unavailableName = l.name();
            }
        }
        int shortfallQty = totalQty - coveredQty;
        boolean fullyCovered = shortfallQty == 0 && totalQty > 0;
        // 票够 → 整单随票结清，这次不用再付；票不够 → 本单用不了票，要付就是全额
        BigDecimal payable = fullyCovered ? BigDecimal.ZERO : totalAmount;

        // 三种情况必须分开说，否则客户会被引到错的动作上：
        //   ① 全抵 → 不用付钱；
        //   ② 有商品**根本不能用票** → 补票也没用，只能改选支付方式（优先级最高，
        //      因为"再买几张票"解决不了它）；
        //   ③ 票能抵这项商品、只是余额不够 → 补票或者改选。
        String title;
        String hint;
        if (fullyCovered) {
            title = "水票支付";
            hint = "本单全部由水票结清（含水费、押金与配送费），无需另行付款";
        } else if (unavailableName != null) {
            title = "暂不能用票支付";
            hint = "「" + unavailableName + "」不能用票支付（本站没有为它开通水票，也没有可用的统一折扣），"
                    + "补票也解决不了 —— 请改选支付方式后再下单";
        } else {
            title = "水票不足";
            hint = "水票不足以覆盖本单：需要 " + totalQty + " 张、手上只够抵 " + coveredQty
                    + " 件，还差 " + shortfallQty + " 张。当前不支持「水票 + 现金/微信」混合支付，"
                    + "请改选支付方式，或先补齐水票再下单（票不会被扣，仍留在你的账户里）";
        }

        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("needQty", totalQty);
        data.put("coverQty", coveredQty);
        data.put("shortfallQty", shortfallQty);
        data.put("coverAmount", coveredAmount);
        data.put("payableAmount", payable);
        data.put("fullyCovered", fullyCovered);
        // [2026-09-20] 结构化原因，供前端决定"票不足"弹窗该给哪个动作：
        //   COVERED      = 票够，整单结清；
        //   INSUFFICIENT = 票能抵这项商品、只是余额不够 → 可以引导去补票（跳购票页）；
        //   UNUSABLE     = 这项商品根本不能用票 → 补票也没用，只能改选支付方式。
        // 前端**不得**靠比对 title 文案来区分这两种情况（文案是给人看的，不是判据）。
        data.put("reason", fullyCovered ? "COVERED" : (unavailableName != null ? "UNUSABLE" : "INSUFFICIENT"));
        data.put("title", title);
        data.put("hint", hint);
        return data;
    }

    /**
     * 该客户在该水站能否使用货到付款（现金）。
     *
     * <p><b>唯一控制点 = 客户级授权</b>（{@code customer_station_config.offline_payment_enabled}），
     * 由站长在「用户画像 → 权限设置 → 货到付款」逐个客户开通。默认 0 → 顾客端不展示货到付款。
     *
     * <p><b>[2026-09-12 移除站点总闸 station.offline_payment_enabled]</b>
     * 旧实现要求「站点总闸 + 客户授权」双开才算放行，但总闸在三个小程序里<b>没有任何入口能打开</b>
     * （建站默认 0），结果是站长明明给用户开了开关、顾客端照样不显示货到付款。
     * 两道闸的控制权实质重合在站长一人身上，多出来的一道只会制造「我明明开了啊」的困惑，
     * 因此按「站长说了算」的原则移除：站长在用户画像里的开关就是最终决定。
     * 站点列已停止读写，DROP 脚本见 {@code sql/migration_v22_drop_station_offline_payment.sql}。
     */
    @Override
    public boolean canUseOfflinePayment(Long customerId, Long stationId) {
        // 只判"开关层"（老调用方：试算/报价里决定要不要把"货到付款"这个选项放出来）。
        // 欠款那一层见 offlinePaymentBlockReason —— 那里是**唯一判据**。
        return offlinePaymentBlockReason(customerId, stationId) == null;
    }

    /**
     * 货到付款**能不能用**，不能用时给出原因 —— 全仓唯一判据（v48 起；两项配置已于 v49 撤回）。
     *
     * <p>两层，顺序即优先级：</p>
     * <ol>
     *   <li><b>开关</b>：该客户在该站是否被站长开通（customer_station_config.offline_payment_enabled）。
     *       货到付款**没有站点级总闸**，只能由站长逐个客户开通 —— 这本身就是第一道审核；</li>
     *   <li><b>欠款即停</b>：该客户在本站有逾期未结的现金单就不给新的赊账单 —— 判据只用现有列现算
     *       （payment_status = 1 且未取消且 due_date 已过），不发明新规则。它挡的是
     *       "已经欠着钱还想再赊"，与站长审核不重复。</li>
     * </ol>
     *
     * <p>⚠️ 下单（OrderServiceImpl.createOrder）与报价（quote）都必须调本方法，不要各写一套：
     * 两处判据一旦分叉，就会出现"报价页能选货到付款、提交却被拒"。</p>
     *
     * <p>⚠️ v48 曾在此加过"首单是否放行 + 单笔上限"两层，v49 按产品裁定撤回
     * （"既然目前还由站长审核，这两个先不做了"）；要加回来先读 migration_v49_drop_cod_limits.sql 的文件头。</p>
     *
     * @return null = 可用；否则是给用户看的原因（前端直接展示，不要自编同义文案）
     */
    public String offlinePaymentBlockReason(Long customerId, Long stationId) {
        if (customerId == null || stationId == null) {
            return "无法识别客户或水站";
        }
        CustomerStationConfig config = customerStationConfigMapper.getByCustomerAndStation(customerId, stationId);
        if (config == null || !Integer.valueOf(1).equals(config.getOfflinePaymentEnabled())) {
            return "当前客户暂不支持货到付款";
        }
        int overdue = orderMapper.countOverdueCashOrders(customerId, stationId);
        if (overdue > 0) {
            BigDecimal owed = orderMapper.sumOverdueCashAmount(customerId, stationId);
            return "该客户有 " + overdue + " 笔逾期未结货款（合计 ¥" + owed + "），请先结清再使用货到付款";
        }
        return null;
    }
}
