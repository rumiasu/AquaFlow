package com.example.aquaflow.service.impl;

import com.example.aquaflow.constant.DepositType;
import com.example.aquaflow.constant.InventoryChangeType;
import com.example.aquaflow.constant.OrderStatus;
import com.example.aquaflow.constant.PaymentStatus;
import com.example.aquaflow.constant.StationOperatingStatus;
import com.example.aquaflow.constant.PayMethod;
import com.example.aquaflow.dto.OrderCreateDTO;
import com.example.aquaflow.dto.OrderCreateResult;
import com.example.aquaflow.entity.*;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.*;
import com.example.aquaflow.service.AssetService;
import com.example.aquaflow.service.AuditLogService;
import com.example.aquaflow.service.InventoryService;
import com.example.aquaflow.service.InventoryReservationService;
import com.example.aquaflow.service.OrderService;
import com.example.aquaflow.service.OrderWorkflowService;
import com.example.aquaflow.service.PaymentService;
import com.example.aquaflow.service.TicketAccountService;
import com.example.aquaflow.util.AuthContext;
import com.example.aquaflow.util.BarrelScope;
import com.example.aquaflow.util.PriceUtil;
import com.example.aquaflow.util.StationUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class OrderServiceImpl implements OrderService {

    /**
     * 取消申请的编排入口（已接单订单的取消须站长审批）。
     * <p>{@code @Lazy} 用于打破潜在 Bean 循环：OrderWorkflowService 侧还依赖支付、桶账等一系列服务。</p>
     */
    @Autowired
    @Lazy
    private OrderWorkflowService orderWorkflowService;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderItemMapper orderItemMapper;

    @Autowired
    private AddressMapper addressMapper;

    @Autowired
    private CustomerMapper customerMapper;

    @Autowired
    private InventoryMapper inventoryMapper;

    /**
     * 库存预留凭据（唯一写入口）：下单时"预留"而不是扣实物 —— 见
     * {@code docs/design/28-库存预留与履约凭据.md} 与下方 reserveForItem 调用处的注释。
     */
    @Autowired
    private InventoryReservationService inventoryReservationService;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private StationMapper stationMapper;

    @Autowired
    private CustomerBarrelAssetMapper customerBarrelAssetMapper;

    @Autowired
    private CustomerDepositAccountMapper customerDepositAccountMapper;

    @Autowired
    private TicketAccountMapper ticketAccountMapper;

    /** [v54] 统一水票的"本站是否开通"判据在 TicketAccountService（唯一实现），下单闸门要问它 */
    @Autowired
    private TicketAccountService ticketAccountService;

    @Autowired
    private DepositRecordMapper depositRecordMapper;

    @Autowired
    private CustomerBarrelInTransitMapper customerBarrelInTransitMapper;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private AssetService assetService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private CustomerStationConfigMapper customerStationConfigMapper;

    @Autowired
    private InventoryService inventoryService;

    /**
     * 欠桶数据源（下单时用于**生成提醒**，不再用于拦截）。
     *
     * <p>数据源是 {@code customer_barrel_over}（按 (客户,水站,桶型)、可为负），不是已停写的旧表
     * {@code customer_owed_barrel}。负数 = 客户多还的桶寄存在水站（合法状态），
     * <b>不能拿来抵销其他桶型的欠桶</b>——所以统计一律先 {@code max(0, over)} 再按桶型分开处理。</p>
     */
    @Autowired
    private com.example.aquaflow.mapper.CustomerBarrelOverMapper customerBarrelOverMapper;

    /**
     * 配送计费（起送量 / 配送范围 / 运费 / 楼层费，v35）。
     *
     * <p>⚠️ {@code PaymentServiceImpl.quote} 用的是<b>同一个服务</b>、传同样的入参 ——
     * 报价与下单必须同口径。任何一侧内联算费用都会重新制造"计价双轨"事故
     * （结算页价格与最终订单金额不一致 → 客诉，见 {@code PriceUtil} 文件头）。</p>
     */
    @Autowired
    private com.example.aquaflow.service.DeliveryFeeService deliveryFeeService;

    /**
     * 账期快照（应收账款，2026-09-17）。
     *
     * <p>规则（"哪种单才有应付日期"）只在 {@code ReceivableService.resolveDueDate} 里实现一次 ——
     * 下单时算一次就写死进 {@code orders.due_date}，与地址/金额快照同源的理由：
     * 站长事后改客户账期，不能改到历史单的到期日。</p>
     */
    @Autowired
    private com.example.aquaflow.service.ReceivableService receivableService;

    // [2026-09-15] 原 [AQ-030]/[DEF-3] 的硬拦 `MAX_OWED_BUCKETS = 5`（欠桶 ≥5 拒绝下单）**已按产品决定移除**：
    // 欠桶改为「只警告、不阻断」——下单照常放行，但每次下单都在 warnings 里提醒客户归还空桶
    // （见 buildOwedWarnings），站长端另有「欠桶台账」按天数催收。
    // 移除理由：欠桶是运营追缴事项（配送员下次上门把空桶收回来），不是资金风险；拦单只会把客户推走。
    // 护栏仍在：欠桶的物理上界由桶账恒等式「占用 = 权益 + over ≥ 0」保证（BarrelLedgerService），与下单放行无关。
    // 若将来要恢复硬拦：常数与判断要加回来，并把 BarrelOwedIntegrationTest 里
    // 「欠桶很多仍可下单」那条用例同步反转，否则测试会挡住这次回退。


    /**
     * 生成欠桶提醒（**只提示、不阻断**）。每个欠桶的桶型各一条，文案面向**客户**——
     * 每次下单都要让他看见"记得还桶"。
     *
     * <p>[2026-09-15] 原来的 [AQ-030]/[DEF-3] 是"欠桶 ≥5 拒绝下单"，现按产品决定改为纯提醒：
     * 欠桶是运营追缴事项（配送员下次上门回收），不该把客户挡在下单之外。站长端另有
     * {@code GET /api/manager/owed-barrels} 按欠桶天数催收。</p>
     *
     * <p>口径：只取 {@code over_qty > 0} 的行；{@code over_qty < 0}（水站暂存）不是欠桶。
     * 天数和站长端台账同源（{@link com.example.aquaflow.vo.OwedBarrelVO#owedDays}），避免两处各算一套。</p>
     */
    private List<String> buildOwedWarnings(Long customerId, Long stationId) {
        List<String> out = new java.util.ArrayList<>();
        if (customerId == null || stationId == null) return out;
        List<CustomerBarrelOver> overs = customerBarrelOverMapper.listByCustomerAndStation(customerId, stationId);
        if (overs == null) return out;
        for (CustomerBarrelOver o : overs) {
            int owed = o.getOverQty() == null ? 0 : o.getOverQty();
            if (owed <= 0) continue;
            Product p = o.getProductId() == null ? null : productMapper.getById(o.getProductId());
            String pname = p != null && p.getName() != null ? p.getName() : ("商品" + o.getProductId());
            Long days = com.example.aquaflow.vo.OwedBarrelVO.owedDays(o.getOwedSince());
            out.add("欠桶提醒：您有 " + owed + " 个空桶未归还（" + pname
                    + (days == null ? "" : "，已 " + days + " 天")
                    + "），请记得还桶 —— 配送员上门时把空桶一并交回");
        }
        return out;
    }

@Override
    @Transactional(rollbackFor = Exception.class)
    public OrderCreateResult createOrder(OrderCreateDTO dto) {
        if (dto.getItems() == null || dto.getItems().isEmpty()) {
            throw new BusinessException("订单商品不能为空");
        }
        if (dto.getCustomerId() == null) {
            throw new BusinessException("客户ID不能为空");
        }
        if (dto.getAddressId() == null) {
            throw new BusinessException("地址ID不能为空");
        }
        if (dto.getPaymentMethod() == null) {
            throw new BusinessException("支付方式不能为空");
        }
        // [2026-09-25 架构评审问题 7] 枚举入口白名单（第二道：DTO 上有 @Min/@Max）：
        // 原实现只判非空，传 99 也能建单 —— 详情与后果见 constant/PayMethod.isValid 的注释。
        // 白名单正本在 PayMethod，不要在这里写 1/2/3 字面量。
        if (!PayMethod.isValid(dto.getPaymentMethod())) {
            throw new BusinessException("不支持的支付方式：" + dto.getPaymentMethod());
        }
        if (dto.getSource() != null && (dto.getSource() < 1 || dto.getSource() > 3)) {
            // 来源取值正本：sql/schema.sql 的 orders.source 列注释（1 电话 / 2 微信 / 3 小程序）。
            // 它只是标记、没有业务分支读它，所以这里只拒绝越界值，不新建枚举类。
            throw new BusinessException("订单来源非法：" + dto.getSource());
        }
        if (dto.getStationId() == null) {
            throw new BusinessException("请先选择服务水站");
        }

        // 幂等键：**必传**（评审问题 5）。
        // 旧实现"客户端不传就服务端生成一个 UUID"看起来是"强制幂等"，实际等于**没有幂等**：
        // 同一个请求重试两次会拿到两个不同的键 ⇒ 建两张单（AQ-019 解决了 null 跳过查重，
        // 但没解决"每次都是新键"）。而"断网后重试"正是幂等唯一的用途。
        // 契约：键由客户端生成、**跨重试复用**，长度 ≤ 64（列宽）。
        //   两端小程序都已按这个契约发（miniapp-user/pages/order/create.js 复用 this.data.idempotencyKey，
        //   成功后才换新；员工端 place-order 也带 staff- 前缀的键）。
        // ⚠️ 命中幂等的时机见下面「幂等命中」块 —— **在鉴权之后**（报告 §5.5：先鉴权再命中幂等）。
        String idempotencyKey = dto.getIdempotencyKey();
        if (idempotencyKey == null || idempotencyKey.trim().isEmpty()) {
            throw new BusinessException("缺少幂等键（idempotencyKey），请由客户端生成并在重试时复用");
        }
        idempotencyKey = idempotencyKey.trim();
        if (idempotencyKey.length() > 64) {
            throw new BusinessException("幂等键过长（最多 64 个字符）");
        }

        Customer customer = customerMapper.getById(dto.getCustomerId());
        if (customer == null) {
            throw new BusinessException("客户不存在");
        }
        Long stationId = dto.getStationId();

        // 验证水站存在且营业中
        Station station = stationMapper.getById(stationId);
        if (station == null || !Integer.valueOf(1).equals(station.getStatus())) {
            throw new BusinessException("水站不存在或已停业");
        }

        // [AQ-012] 员工代客下单必须校验该客户确属本水站，禁止替他站客户下单消耗其水票/押金
        // 顾客本人下单走 controller 已强覆盖 customerId，不在此约束。
        // [2026-09-17] 判据从「只看 customer_station_config 绑定行」改成并集
        //（countCustomerOfStation = 绑定 或 本站订单）：老客户完全可能没有绑定行，
        // 而"客户列表里点得到、下单却说他不是本站客户"是最难排查的一类拒绝。
        // 放宽是安全的：只有与本水站已有关系的客户才放行，他站客户两条都不满足。
        //
        // [2026-09-25 架构评审问题 3 · 产品裁定「跨站代客下单不合法」]
        // 上面那条判据只回答"客户与目标站有没有关系"，**不回答"调用者有没有资格替这个站下单"**：
        // dto.stationId 由请求体传入，所以 A 站员工（乃至任何持 UNSELECTED 引导会话的微信用户）
        // 只要挑一个"与该站有关系的客户 + 该客户的地址"，就能在 B 站建单、扣 B 站库存、
        // 动 B 站客户的票与押金。实测（探针用例 2026-09-25）：A 站站长 stationId=B 建单成功。
        //
        // 正确做法：**员工站别一律取登录态**（AuthContext，服务端刷新过），与请求里的目标站
        // 必须相等；跨站业务只走专门的外派/抢单流程（那时货由接单站自己的库存出）。
        // 与 AGENTS §6「跨站校验一律以 AuthContext 为准，不信任请求参数」同一条判据。
        if ("staff".equals(AuthContext.getUserType())) {
            Long myStationId = AuthContext.requireStationId();   // 未绑站（含 UNSELECTED）直接拒
            if (!myStationId.equals(stationId)) {
                throw new BusinessException("只能为本站客户下单：当前账号属于其他水站，"
                        + "跨站业务请走外派流程");
            }
            if (customerMapper.countCustomerOfStation(dto.getCustomerId(), stationId) == 0) {
                throw new BusinessException("该客户不属于本水站，无法代客下单");
            }
        }

        Address addr = addressMapper.getById(dto.getAddressId());
        if (addr == null) {
            throw new BusinessException("地址不存在");
        }
        // [AQ-011] 地址必须归属当前下单客户，否则可填他人地址并读出姓名/电话/门牌（隐私泄露）
        if (!dto.getCustomerId().equals(addr.getCustomerId())) {
            throw new BusinessException("该地址不属于当前客户");
        }

        // ===== 幂等命中（评审问题 5）=====
        // 位置是刻意的：**身份与归属都验完之后**才允许命中幂等（报告 §5.5「先鉴权再命中幂等」），
        // 但**早于**商品/库存/票价/计费那一串校验 —— 那种"重试时商品刚好下架了"的情况
        // 应当仍然拿回原来那单，而不是被一句"商品已下架"挡住（幂等的全部意义就在这里）。
        //
        // 作用域 = (customer_id, idempotency_key)：旧实现只按 key 全局查，
        // 于是"跨客户复用同一个键"会把**别人的订单 id** 返回给调用者（越权信息泄露）。
        // ⚠️ 唯一键也必须跟着带上 customer_id，否则数据库那层仍然把两个客户的同名键当冲突
        //（迁移 v62；只改代码不改键 = 第二个客户永远建不了单）。
        String requestDigest = requestDigest(dto, dto.getCustomerId(), stationId);
        Orders existing = orderMapper.findByCustomerAndIdempotencyKey(dto.getCustomerId(), idempotencyKey);
        if (existing != null) {
            // 同键同内容 → 返回原单（这才是"重试"）；同键**不同内容** → 拒绝，
            // 否则客户端换个金额/商品用同一个键提交，会拿回一张与本次请求无关的旧单，
            // 而它以为自己下单成功了。
            if (existing.getRequestDigest() == null || existing.getRequestDigest().equals(requestDigest)) {
                // 幂等命中同样是"一次下单"，欠桶提醒照发（客户可能只看到这一次响应）
                List<String> warnings = buildOwedWarnings(dto.getCustomerId(), stationId);
                return OrderCreateResult.success(existing.getId(), warnings, false);
            }
            throw new BusinessException("该幂等键已用于另一笔内容不同的下单请求，请重新发起");
        }

        // [2026-09-15] 这里原本是 [AQ-030]/[DEF-3] 的欠桶硬拦（Σ max(0, over) ≥ 5 即拒绝下单）。
        // 现改为**只提醒不阻断**：欠桶明细由 buildOwedWarnings 在下单结果里以 warnings 下发（面向客户），
        // 站长端另有 /api/manager/owed-barrels 台账按天数催收。此处不再做任何拒绝判断。

        // ===== 综合校验链 =====
        // 1. 校验商品属于该水站
        // 2. 校验库存属于该水站
        // 3. 如果使用水票支付，校验该客户在该水站有水票账户
        // 4. 如果涉及桶/押金，校验该客户在该水站有资产记录
        // 5. 首次资产业务检查
        boolean needStationAsset = false;
        for (OrderCreateDTO.OrderItemDTO item : dto.getItems()) {
            if (item.getProductId() == null || item.getQuantity() == null || item.getQuantity() <= 0) {
                throw new BusinessException("商品参数异常");
            }
            Product product = productMapper.getById(item.getProductId());
            if (product == null) {
                throw new BusinessException("商品不存在: " + item.getProductId());
            }
            // 校验商品在该水站有库存记录（即属于该水站可售）
            Inventory inv = inventoryMapper.getByStationAndProduct(stationId, item.getProductId());
            if (inv == null) {
                throw new BusinessException("商品不在该水站销售: " + product.getName());
            }
            if (inv.getEnabled() == null || !Integer.valueOf(1).equals(inv.getEnabled())) {
                throw new BusinessException("商品未上架: " + product.getName());
            }
            // 判断是否涉及站点资产（桶装水类别；判据唯一实现在 util/BarrelScope）
            if (BarrelScope.isBarrel(product)) {
                needStationAsset = true;
            }
        }

        // 货到付款（现金）权限校验：PayMethod 中 2=现金、3=水票
        // 旧代码用 3 判断"线下支付"，与 PayMethod 定义冲突，导致水票支付被要求走线下授权校验
        if (Integer.valueOf(PayMethod.CASH).equals(dto.getPaymentMethod())) {
            // 唯一判据在 PaymentServiceImpl.offlinePaymentBlockReason（开关 + 欠款即停；
        // v48 的"首单/单笔上限"两层已于 v49 按产品裁定撤回）。
        String blockReason = paymentService.offlinePaymentBlockReason(dto.getCustomerId(), stationId);
            if (blockReason != null) {
                throw new BusinessException(blockReason);
            }
        }

        // 首次站点资产业务检查
        boolean firstStationAsset = false;
        if (needStationAsset) {
            firstStationAsset = !assetService.hasStationAsset(dto.getCustomerId(), stationId);
        }

        BigDecimal waterAmount = BigDecimal.ZERO;
        BigDecimal depositAmount = BigDecimal.ZERO;
        int totalNeededBuckets = 0;
        int newDepositBuckets = 0;
        Map<Long, Integer> barrelByProduct = new HashMap<>();

        // 缓存每个商品的 product/inventory 供后续扣库存使用
        Map<Long, Product> productCache = new HashMap<>();
        Map<Long, Inventory> invCache = new HashMap<>();
        List<OrderCreateResult.ShortageItem> shortages = new java.util.ArrayList<>();

        // ===== 第一遍: 校验 + 计算金额 + 收集库存不足 (不扣库存) =====
        for (OrderCreateDTO.OrderItemDTO item : dto.getItems()) {
            // #13: 校验productId去重
            Long pid = item.getProductId();
            if (barrelByProduct.containsKey(pid) || productCache.containsKey(pid)) {
                throw new BusinessException("订单商品不能包含重复商品: " + pid);
            }
            Product product = productMapper.getById(pid);
            Inventory inv = inventoryMapper.getByStationAndProduct(stationId, item.getProductId());
            // [2026-09-16 商品与库存重构] 这里原本有一段"本站没有 inventory 行就自动补建
            // (quantity=0, enabled=1)"的代码，是**不可达的死代码**（上面第一遍校验链已按 inv==null
            // 抛过"商品不在该水站销售"），但它是上了膛的枪：一旦有人摘掉那道校验或合并两个循环，
            // 客户下一单就能把任意商品**自动变成该站已上架商品**并静默建行。
            // 产品口径：只有站长"选用"才能建行（P1 的 /api/manager/catalog/{id}/select），故这里改成抛异常。
            if (inv == null) {
                throw new BusinessException("商品不在该水站销售: " + product.getName());
            }
            if (inv.getEnabled() == null || !Integer.valueOf(1).equals(inv.getEnabled())) {
                throw new BusinessException("商品未上架: " + product.getName());
            }
            // [AQ-030] 商品状态校验：product.status 0下架 / 1在售 / 2停售，仅"在售"可下单。
            // 原实现只看 inventory.enabled，product 停售后仍能继续产生订单。
            if (product.getStatus() != null && !Integer.valueOf(1).equals(product.getStatus())) {
                throw new BusinessException("商品已下架或停售: " + product.getName());
            }

            productCache.put(item.getProductId(), product);
            invCache.put(item.getProductId(), inv);

            // [2026-09-25 库存预留模型] 缺货判断读**可用量**（在库实物 − 本站已预留），不是实物：
            // 下单不再减实物，若不扣掉"已被别的单预留的量"，两个客户会各自把同一批货承诺出去。
            int stock = inventoryReservationService.availableQty(stationId, item.getProductId());
            if (stock < item.getQuantity()) {
                OrderCreateResult.ShortageItem s = new OrderCreateResult.ShortageItem();
                s.setProductId(item.getProductId());
                s.setProductName(product.getName());
                s.setBrand(product.getBrand());
                s.setSpec(product.getSpec());
                s.setRequested(item.getQuantity());
                s.setStock(stock);
                if (stock == 0) {
                    s.setMessage("暂时没货，需要等待配送");
                } else {
                    s.setMessage("库存不足，仅剩 " + stock + " 桶");
                }
                shortages.add(s);
            }

            // [AQ-030] 限购校验：站长按商品配置的单笔上限（product.max_per_order）必须生效
            if (product.getMaxPerOrder() != null && product.getMaxPerOrder() > 0
                    && item.getQuantity() > product.getMaxPerOrder()) {
                throw new BusinessException("商品「" + product.getName() + "」单笔最多购买 "
                        + product.getMaxPerOrder() + " 件");
            }

            // [AQ-030] 水票支付适用性校验：该站必须启用该商品的水票且配置了有效水票价，
            // 否则水票支付无法成立（历史实现完全不校验，可对未开水票的商品下水票单）。
            // [v58] 定制票不可用时，本站的**统一折扣**可以兜底 —— 但只兜桶装水
            // （统一折扣是桶装水的折扣工具：瓶装水/一次性桶/饮水器不占桶、没有"循环"）。
            // 注意「统一折扣」只是**买票时的定价规则**，它不改变"票进哪个账户"：
            // 票永远进这一款水自己的账户（2026-09-20 产品拍板：只抵那款水）。
            if (Integer.valueOf(PayMethod.TICKET).equals(dto.getPaymentMethod())) {
                boolean ticketEnabled = inv.getTicketEnabled() != null && Integer.valueOf(1).equals(inv.getTicketEnabled());
                BigDecimal stTicketPrice = inv.getTicketPrice();
                boolean hasPrice = stTicketPrice != null && stTicketPrice.compareTo(BigDecimal.ZERO) > 0;
                boolean customOk = ticketEnabled && hasPrice;
                boolean unifiedOk = !customOk
                        && BarrelScope.isBarrel(product)
                        && ticketAccountService.unifiedDiscountConfigured(stationId);
                if (!customOk && !unifiedOk) {
                    throw new BusinessException("商品「" + product.getName() + "」未开通水票支付");
                }
            }

            // 与结算页报价共用同一套单价算法（水票支付用站级水票价，其余用零售价）
            BigDecimal unitPrice = PriceUtil.calcUnitPrice(product, inv, dto.getPaymentMethod());
            BigDecimal itemSubtotal = unitPrice.multiply(BigDecimal.valueOf(item.getQuantity()));
            waterAmount = waterAmount.add(itemSubtotal);

            // [2026-09-19] 押金只对桶装水收（判据唯一实现在 util/BarrelScope）：
            // 桶装水不在此处按件收，只在下面按"缺桶数"收 extraDeposit；非桶装（瓶装水/一次性桶/饮水器）
            // **一概不收押金** —— 押金是循环桶的押金（product.deposit 列注释"只有桶装水使用"），
            // 且非桶装没有押金条可核销，收了就没有退还路径。与结算页报价同口径，避免计价双轨。
            if (BarrelScope.isBarrel(product)) {
                totalNeededBuckets += item.getQuantity();
                barrelByProduct.merge(item.getProductId(), item.getQuantity(), Integer::sum);
            }
        }

        // ===== 库存不足且未确认 → 返回 needConfirm, 不创建订单 =====
        if (!shortages.isEmpty() && !Boolean.TRUE.equals(dto.getConfirmShortage())) {
            return OrderCreateResult.needConfirm(shortages);
        }

        List<Long> createdInTransitIds = new java.util.ArrayList<>();
        if (totalNeededBuckets > 0) {
            List<CustomerBarrelAsset> assets = customerBarrelAssetMapper.listByCustomerAndStation(dto.getCustomerId(), stationId);
            Map<Long, Integer> heldByProduct = new HashMap<>();
            if (assets != null) {
                for (CustomerBarrelAsset a : assets) {
                    heldByProduct.merge(a.getProductId(), a.getQuantity() != null ? a.getQuantity() : 0, Integer::sum);
                }
            }

            // [DEF-5] 已下单但尚未送达的「配送中权益」必须一并扣减，否则并发两单会重复收桶款：
            // 两个请求同时读到 held=0，各自算出 shortage=2，顾客付了两份桶款却只买到一份权益。
            // 说明：这里解决的是快速重复下单/连下两单的常规场景；彻底的串行化需要分布式锁，
            //      按"初期量小、跳过高并发"的既定取舍，暂不引入。
            BigDecimal requiredExtraDeposit = BigDecimal.ZERO;
            Map<Long, Integer> extraByProduct = new HashMap<>();
            Map<Long, BigDecimal> priceByProduct = new HashMap<>();
            for (Map.Entry<Long, Integer> e : barrelByProduct.entrySet()) {
                Long pid = e.getKey();
                int needed = e.getValue();
                int held = heldByProduct.getOrDefault(pid, 0);
                // [2026-09-15 口径变更] 下单抵扣**只认已到手的权益**（held），在途（PENDING）不再抵扣：
                // 在途的桶是给上一单的，客户手上并没有可换水的空桶，这一单要按需新买桶权益。
                // 这与支付报价侧（PaymentServiceImpl.quote）和小程序下单页（miniapp-user/pages/order/create.js
                // 用 assetQty 算"已有几个桶"）一致 —— 此前只有本处多减了一个 pending，属三处口径不一致。
                // ⚠️ 取舍：原 [DEF-5] 加 `- pending` 是为了防"连点两次下单各算一次 shortage、重复收桶款"；
                // 现在在途被有意排除，那种情况会各收一份押金（客户多买桶权益）→ **幂等键必须继续生效**，
                // 前端也要防连点。若将来恢复"在途抵扣"，请同时改报价侧与下单页，否则三处又会打架。
                int shortage = Math.max(0, needed - held);
                if (shortage > 0) {
                    extraByProduct.put(pid, shortage);
                    Product p = productCache.get(pid);
                    // [2026-09-16] 缺桶押金必须取**本站押金**：这里算出的值会快照进
                    // customer_barrel_in_transit.unit_price（→ 建 lot 的 unit_price → 决定退桶能退多少钱），
                    // 所以绝不能再直接读全局 product.deposit。
                    BigDecimal dep = PriceUtil.calcDeposit(p, invCache.get(pid));
                    priceByProduct.put(pid, dep);
                    requiredExtraDeposit = requiredExtraDeposit.add(dep.multiply(BigDecimal.valueOf(shortage)));
                }
            }

            if (!extraByProduct.isEmpty()) {
                // [AQ-009] 押金一律按服务端缺桶数计算的应收金额，忽略客户端传入的 extraDeposit。
                // 原实现"只校验不得低于应收、无上界"，客户端可传 99999 直接放大押金余额。
                //
                // 并且不再在下单时入账押金账户——此时顾客一分钱未付（payment_status=PENDING）。
                // 仅把应缴押金记到订单，待支付成功（payment_status -> PAID）时由
                // PaymentService.applyDepositOnPaid(orderId) 入账（幂等：按 related_order_id 去重）。
                depositAmount = depositAmount.add(requiredExtraDeposit);

                // AQ-033: 只要配送中桶缺口存在（extraByProduct 非空），无论是否额外收押金都须建立配送中桶记录，
                // 否则配送完成后桶不进持有资产、凭空消失。物理桶与押金是两件事，不能绑在同一个分支里。
                for (Map.Entry<Long, Integer> e : extraByProduct.entrySet()) {
                    Long pid = e.getKey();
                    int qty = e.getValue();
                    CustomerBarrelInTransit inTransit = new CustomerBarrelInTransit();
                    inTransit.setCustomerId(dto.getCustomerId());
                    inTransit.setStationId(stationId);
                    inTransit.setProductId(pid);
                    inTransit.setQty(qty);
                    inTransit.setRelatedOrderId(null); // 订单创建后再设置
                    inTransit.setStatus("PENDING");
                    // 下单当时的桶权益单价快照：配送完成时用它建押金条(customer_barrel_lot.unit_price)，
                    // 保证将来退款按【买入时】的价格，而不是退款时的当前价。
                    inTransit.setUnitPrice(priceByProduct.get(pid));
                    inTransit.setCreateTime(LocalDateTime.now());
                    inTransit.setUpdateTime(LocalDateTime.now());
                    // 先保存，订单创建后更新 relatedOrderId
                    customerBarrelInTransitMapper.insert(inTransit);
                    createdInTransitIds.add(inTransit.getId());
                }
            }
        }

        // ===== [v35] 配送计费：起送量 / 配送范围 / 运费 / 楼层费 =====
        // 与 PaymentServiceImpl.quote 调**同一个服务**、传同样的入参（水费口径、桶数口径都在这之前已算好），
        // 保证"报价说多少、下单就是多少"。见 docs/design/17。
        com.example.aquaflow.util.DeliveryFeeUtil.FeeResult feeResult =
                deliveryFeeService.calcForOrder(dto.getCustomerId(), stationId, dto.getAddressId(),
                        totalNeededBuckets, waterAmount);
        if (feeResult.isBlocked()) {
            // 硬拦（站长把起送量/配送范围配成 REJECT）。quote 侧会把同一个原因作为 blocked 下发，
            // 所以正常流程里客户不会走到这里 —— 走到这里说明前端没先试算，或配置刚被改。
            throw new BusinessException(feeResult.getBlockReason());
        }

        Orders orders = new Orders();
        orders.setCustomerId(dto.getCustomerId());
        orders.setAddressId(dto.getAddressId());
        orders.setStationId(stationId);
        orders.setDeliveryStationId(stationId);
        // [v47] 结算站（营收归谁）在下单这一刻 = 归属站：客户是在这个站下的单，
        // 抢单/定向外派时才随履约站一起改（改的地方全在 OrderMapper 的 CAS 里，见 v47 迁移文件头）。
        // ⚠️ 漏写这一行**不会报错**：读侧的 coalesce 会回退成旧口径（= coalesce(履约站, 归属站)），
        // 于是"显式列"退化成装饰，下一个改外派流程的人又得回去猜钱归谁。
        orders.setSettleStationId(stationId);
        orders.setSource(dto.getSource() != null ? dto.getSource() : 2);
        orders.setPaymentMethod(dto.getPaymentMethod());
        orders.setPaymentStatus(PaymentStatus.PENDING);
        orders.setWaterAmount(waterAmount);
        orders.setDepositAmount(depositAmount);
        // ⚠️ 费用**单独成列**，绝不并入 water_amount（污染水费口径）或 deposit_amount
        //（那是可退押金，退款按它释放押金余额，混入会导致取消订单多退钱）。
        orders.setDeliveryFee(feeResult.getDeliveryFee());
        orders.setFloorFee(feeResult.getFloorFee());
        orders.setTotalAmount(waterAmount.add(depositAmount).add(feeResult.getFeeTotal()));
        // 计算订单总数量：所有商品数量之和
        if (dto.getItems() != null && !dto.getItems().isEmpty()) {
            int totalQuantity = dto.getItems().stream()
                    .mapToInt(item -> item.getQuantity() != null ? item.getQuantity() : 0)
                    .sum();
            orders.setQuantity(totalQuantity);
        }
        orders.setReceiverName(dto.getReceiverName() != null ? dto.getReceiverName() : addr.getName());
        orders.setReceiverPhone(dto.getReceiverPhone() != null ? dto.getReceiverPhone() : addr.getPhone());
        orders.setAddressSnapshot(addr.getDetail());
        orders.setAddressSnapshotLat(addr.getLat());
        orders.setAddressSnapshotLng(addr.getLng());
        orders.setGuardInfo(dto.getGuardInfo());
        orders.setDeliveryTimeRequest(dto.getDeliveryTimeRequest());
        orders.setSpecialNote(dto.getSpecialNote());
        orders.setReturnBucketQty(dto.getReturnBucketQty());
        orders.setDeliveryBucketQty(totalNeededBuckets > 0 ? totalNeededBuckets : null);
        // ⚠️ [2026-09-26 产品口径] 「本站第一笔**买桶**订单」的判据**只看桶权益**（hasBarrelAsset），
        //    不能复用上面的 firstStationAsset —— 后者把水票账户与押金账户也算作"已有资产"
        //    （那是给客户的"首次资产告知"用的）。用错的后果很具体：**先买过水票的客户**
        //    第一次买桶时被判成非首单 → 完成配送页要求核对回桶、并把回桶数默认成"送出多少回多少"，
        //    可他手里一个空桶都没有（原话：「第一次送达桶确实不需要回收，把第一次桶送达时的
        //    默认回桶值取消掉」）；照默认提交还会凭空给他记上欠桶。
        //    读它的地方：OrderWorkflowServiceImpl.completeDelivery（为真则整段跳过回桶核对）
        //    与 involvesDepositOrBarrelRights 的第 2 项。
        orders.setFirstBarrelOrder(totalNeededBuckets > 0
                && !assetService.hasBarrelAsset(dto.getCustomerId(), stationId));
        orders.setIdempotencyKey(idempotencyKey);
        // 请求摘要：与幂等键一起落库，用来回答"同键的第二次请求内容是否相同"（评审问题 5）。
        // 见 requestDigest 的注释：只取**业务字段**，不取控制字段与任何服务端算出来的金额。
        orders.setRequestDigest(requestDigest);
        // 账期快照：该客户在**本站**设了账期、且本单是现金(货到付款)时才有应付日期，其余为 null（即时结清）。
        // 只有下单这一次会算它，之后 due_date 只读（见 ReceivableService.resolveDueDate）。
        // ⚠️ 必须传 stationId：账期是**站级**的（v60），同一家公司在 A 站月结、在 B 站可能只能现结。
        orders.setDueDate(receivableService.resolveDueDate(dto.getCustomerId(), stationId, dto.getPaymentMethod()));
        orders.setStatus(1);
        orders.setCreateTime(LocalDateTime.now());
        orders.setUpdateTime(LocalDateTime.now());
        try {
            orderMapper.save(orders);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发同键：两个请求同时通过了上面的存在性检查，数据库唯一键
            // uk_orders_idem_customer(customer_id, idempotency_key) 拦住了第二个。
            // 必须抛出、不能吞：走到这里时本事务已经写过配送中桶等行，只有回滚才干净
            //（吞掉后继续提交会因事务被标记 rollback-only 而抛 UnexpectedRollbackException，
            //  把一次正常的并发拒绝伪装成 500 —— PaymentServiceImpl 里有同形的教训）。
            throw new BusinessException("该请求已提交过（同一幂等键），请到订单列表查看结果");
        }

        // 更新配送中桶资产记录的关联订单ID（仅关联本次创建的配送中桶记录）
        if (!createdInTransitIds.isEmpty()) {
            customerBarrelInTransitMapper.linkPendingToOrder(orders.getId(), createdInTransitIds);
        }

        List<OrderItem> createdItems = new java.util.ArrayList<>();
        for (OrderCreateDTO.OrderItemDTO item : dto.getItems()) {
            Product product = productCache.get(item.getProductId());
            Inventory inv = invCache.get(item.getProductId());
            BigDecimal unitPrice = PriceUtil.calcUnitPrice(product, inv, dto.getPaymentMethod());
            // [2026-09-19] 明细押金快照：桶装水在 extraDeposit 里按缺桶数统一收，明细记 0（[DEF-2]：
            // 0 视为"无快照"，退押金时回退到押金条上的买入价）；非桶装本来就不收押金 → 同样记 0。
            // 于是这个字段现在**恒为 0**，留着是因为 BarrelLedgerService.depositByProduct 会读它做兜底。
            BigDecimal itemDeposit = BigDecimal.ZERO;

            OrderItem oi = new OrderItem();
            oi.setOrderId(orders.getId());
            oi.setProductId(item.getProductId());
            oi.setProductNameSnapshot(product.getName());
            oi.setBrandSnapshot(product.getBrand());
            oi.setSpecSnapshot(product.getSpec());
            oi.setPrice(unitPrice);
            oi.setQuantity(item.getQuantity());
            oi.setDeposit(itemDeposit);
            // 先记 0：真正的"下单实际占用库存量"由下面 reserveForItem 写回（它才是唯一知道
            // 锁到多少可用量的人）。旧实现先算 min(stock, qty) 再落库，口径是"已扣减量"，已废。
            oi.setDeductedQty(0);
            oi.setSubtotal(unitPrice.multiply(BigDecimal.valueOf(item.getQuantity())));
            oi.setCreateTime(LocalDateTime.now());
            orderItemMapper.insert(oi);
            createdItems.add(oi);
        }

        // ===== [2026-09-25 库存预留模型] 下单**预留**（占可用量），不再扣减实物 =====
        // 旧实现：`inventoryMapper.decreaseStock` + CONSUME 流水（下单即减实物），取消再 increaseStock 加回去。
        // 它有两个洞（架构评审问题 4）：
        //   ① "扣在哪一站"从未落库 → 跨站外派后取消会把货补到履约站（扣的却是归属站），A 站凭空少、B 站凭空多；
        //   ② 库存不足时只扣 min(stock, qty)，剩下的部分**永远不落账**（补货后完成配送也不再补扣）。
        // 现在：预留 = 一条 inventory_reservation 凭据（记站别与数量），实物在**完成配送**时才出库
        //（InventoryReservationService.shipForOrder，预留不足直接拒绝完成）。
        // 规格见 docs/design/28-库存预留与履约凭据.md。
        List<String> warnings = new java.util.ArrayList<>();
        // ⚠️ **按 productId 升序取锁**（返工 V05）：reserveForItem 会锁住 (站,商品) 的 inventory 行，
        // 若照请求顺序逐条取锁，两张商品顺序相反的单（甲 p1→p2、乙 p2→p1）就会形成固定锁环互相等死锁
        // （MySQL 只会回滚其中一个 → 用户看到系统错误，而不是"库存不足"这类可读拒绝）。
        // 提示文案仍按**请求顺序**下发：用数组暂存，循环结束后再按原序 add（不改变客户端看到的顺序）。
        Integer[] reserveOrder = new Integer[dto.getItems().size()];
        for (int i = 0; i < dto.getItems().size(); i++) {
            reserveOrder[i] = i;
        }
        java.util.Arrays.sort(reserveOrder, java.util.Comparator.comparing(
                i -> dto.getItems().get(i).getProductId(),
                java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())));
        String[] itemWarnings = new String[dto.getItems().size()];
        for (int idx : reserveOrder) {
            OrderCreateDTO.OrderItemDTO item = dto.getItems().get(idx);
            OrderItem oi = createdItems.get(idx);
            int reserved = inventoryReservationService.reserveForItem(
                    orders.getId(), oi.getId(), stationId, item.getProductId(), item.getQuantity());
            if (reserved < item.getQuantity()) {
                Product p = productCache.get(item.getProductId());
                int lack = item.getQuantity() - reserved;
                if (reserved == 0) {
                    itemWarnings[idx] = p.getName() + " 暂时没货，需要等待配送";
                } else {
                    itemWarnings[idx] = p.getName() + " 库存不足(仅剩" + reserved + "桶)，缺" + lack + "桶需等待配送";
                }
            }
        }
        for (String w : itemWarnings) {
            if (w != null) {
                warnings.add(w);
            }
        }

        // [v29] 欠桶提醒（**只标红，不阻断**）：每次下单都提醒客户归还空桶，
        // 同时让配送员/站长知道下次上门要收哪些桶。文案与站长端「欠桶台账」同源（OwedBarrelVO.owedDays），
        // 避免两处各算一套天数。欠桶不再有任何拒绝逻辑（原 [AQ-030] 硬拦已移除，见文件头注释）。
        warnings.addAll(buildOwedWarnings(dto.getCustomerId(), stationId));

        // [2026-09-17] 水站营业状态是**软状态**：不阻断下单，但下单响应必须再提示一次
        //（客户可能没注意到商城/下单页的横幅，而这条提示会跟着"下单成功"直接出现在眼前）。
        String stationStatusHint = StationOperatingStatus.customerHint(
                station.getOperatingStatus(), station.getStatusNote());
        if (stationStatusHint != null) {
            warnings.add(stationStatusHint);
        }

        // [v35] 配送计费提示（起送量未达、超范围、楼层未填、加收的费用…）。
        // 与 quote 侧同源 —— 都由 DeliveryFeeUtil 产出，前端不得自造文案。
        warnings.addAll(feeResult.getWarnings());

        // ⚠️ 水票支付**不在这里扣票**（2026-09-18 试过并撤回）：水票的扣减发生在客户端下单后
        // 那次支付请求里（{@code PaymentServiceImpl.createPayment} 的 TICKET 分支 → {@code deductTickets}）。
        // 把它挪进下单事务会让"客户还没买票/票不够"直接变成**下单失败** —— 而现有流程（客户端先下单、
        // 再扣票；票不足时客户端提示充值）与多张既有用例都按两步走。扣票成功的那一刻
        // {@code payment_status} 会被置 2，订单随即自动进入站长/配送员视野（判据见 OrderMapper 的两张列表），
        // 所以"没扣票的水票单不进站长端"是**由判据本身保证**的，不需要在下单时抢着扣。

        Map<String, Object> detail = new HashMap<>();
        detail.put("orderId", orders.getId());
        detail.put("customerId", dto.getCustomerId());
        detail.put("stationId", stationId);
        detail.put("totalAmount", orders.getTotalAmount());
        detail.put("waterAmount", waterAmount);
        detail.put("depositAmount", depositAmount);
        detail.put("itemCount", dto.getItems().size());
        detail.put("paymentMethod", dto.getPaymentMethod());
        auditLogService.log("ORDER", "CREATE", "order:" + orders.getId(), detail.toString(), null);

        return OrderCreateResult.success(orders.getId(), warnings, firstStationAsset);
    }

    /*
     * 已删除：save(Orders)（2026-09-25，架构评审问题 2 · 端点 POST /api/orders）。
     *
     * 它曾经做过的三道防护（订单必须已存在 / 调用方属该单履约站 / 回填 station_id·status·
     * payment_status·createTime）**不足以**让"客户端直传实体"变安全：delivery_station_id
     * （连带 settle_station_id）、delivery_staff_id、payment_method、四个金额列、
     * first_barrel_order、桶数字段仍然照写，而 where id=#{id} 没有状态 CAS ——
     * 与并发业务动作互相覆盖时会把状态写回旧值（AGENTS §8.18「状态不许倒滚」）。
     * 实测：A 站站长 POST {"id":N,"deliveryStationId":B,"totalAmount":0.01} 三处全部生效。
     *
     * 不修成"再屏蔽几个字段"的原因：这条路的存在本身就是"实体全量写入口"，
     * 而订单的每个可改字段都对应一条具名业务命令（改备注 / 改预约 / 重新报价 / 分配配送员…），
     * 每条都要有自己的状态门槛与副作用。该端点零调用方，故直接删除（登记见删除登记表）。
     */

    /**
     * 幂等请求摘要：把"这一次下单意图"规范化成一个 SHA-256 十六进制串（评审问题 5）。
     *
     * <p>用途只有一个：回答"同一个幂等键的第二次请求，内容是不是同一件事"。
     * 同键同内容 → 返回原单；同键不同内容 → 拒绝（否则客户端改了金额/商品再用同一个键提交，
     * 会拿回一张与本次请求无关的旧单，而它以为自己下单成功了）。</p>
     *
     * <p><b>只取业务字段</b>，且必须满足两条：</p>
     * <ol>
     *   <li><b>不含服务端算出来的金额</b>（水费/押金/配送费/总额）—— 那些由本站配置与库存推导，
     *       改了配置不该让"重试"变成"内容不同"；</li>
     *   <li><b>不含控制字段 {@code confirmShortage}</b> —— 缺货时客户端第一次不带该标记
     *       （后端只回 needConfirm、**不建单**），弹窗确认后用**同一个键**带标记重提。
     *       把它算进摘要，第二次提交就会被判成"内容不同"而永远下不了单。</li>
     * </ol>
     */
    private static String requestDigest(OrderCreateDTO dto, Long customerId, Long stationId) {
        StringBuilder sb = new StringBuilder();
        sb.append(customerId).append('|').append(stationId).append('|')
                .append(dto.getAddressId()).append('|')
                .append(dto.getPaymentMethod()).append('|')
                .append(dto.getSource()).append('|')
                .append(nz(dto.getReceiverName())).append('|')
                .append(nz(dto.getReceiverPhone())).append('|')
                .append(nz(dto.getGuardInfo())).append('|')
                .append(nz(dto.getDeliveryTimeRequest())).append('|')
                .append(nz(dto.getSpecialNote())).append('|')
                .append(dto.getReturnBucketQty()).append('|');
        // 商品按 productId 排序后拼接：同一次下单里商品顺序不同不该算"另一件事"
        dto.getItems().stream()
                .sorted(java.util.Comparator.comparing(OrderCreateDTO.OrderItemDTO::getProductId,
                        java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())))
                .forEach(i -> sb.append(i.getProductId()).append(':').append(i.getQuantity()).append(','));
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，走不到这里；真走到了说明运行环境坏了，
            // 此时**必须失败**而不是退化成"没有摘要"（那会让同键不同内容被静默放过）
            throw new IllegalStateException("无法计算请求摘要", e);
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    @Override
    public List<Orders> list(Long stationId, Long customerId, Integer status, String createTimeStart, String createTimeEnd,
                             Integer limit, Integer offset) {
        return orderMapper.list(stationId, customerId, status, createTimeStart, createTimeEnd, limit, offset);
    }

    @Override
    public Orders getById(Long id) {
        Orders order = orderMapper.getById(id);
        if (order != null) {
            List<OrderItem> items = orderItemMapper.listByOrderId(id);
            order.setItems(items);
        }
        return order;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    /**
     * 客户主动取消订单。
     *
     * 设计要点：
     * 1. 归属校验——只能取消自己的订单；
     * 2. 状态校验——客户仅可取消"待配送"(PENDING)；配送中需由站长审批（本方法只提交申请），
     *    已送达及以上**直接拒**（货已交付，走「配送异常」），避免骑手已出发却被撤单、或已交付的单被抹掉；
     * 3. 完整回滚下单副作用：回补库存、退还押金、清理配送中桶、退还已消耗的水票，
     *    并同步支付状态（未付款→已取消；已付款→已退款）。
     * <p>上面这些回滚动作与站点侧"退款并取消"完全一致，因此统一委托给
     * {@link PaymentService#refundOrder}，不再在本方法内自行复制一份 ——
     * 旧实现正是抄漏了支付状态与水票退还两项：客户取消后订单仍显示"待收款"，
     * 还可能对已取消订单再次发起支付；水票支付的订单取消后票价也没退回。</p>
     */
    public void cancelByCustomer(Long orderId, Long customerId) {
        Orders order = orderMapper.getById(orderId);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }
        if (order.getCustomerId() == null || !order.getCustomerId().equals(customerId)) {
            throw new BusinessException("无权取消他人订单");
        }

        int status = order.getStatus() != null ? order.getStatus() : 0;
        if (!OrderStatus.isCancellable(status)) {
            throw new BusinessException(OrderStatus.notCancellableReason(status));
        }
        // 已接单（配送中）的订单，客户不能自助取消，只能提交取消申请，由站长审批；
        // 同意后才走 refundOrder 完整退款链 —— **配送中的单"取消"这个动作只有配送端能做**。
        // 已送达(3) 及以上走不到这里：上面那道 isCancellable 已经直接拒了（文案指向「配送异常」）。
        if (status != OrderStatus.PENDING) {
            orderWorkflowService.requestCancelByCustomer(orderId, customerId, "客户申请取消");
            log.info("[OrderService] 客户取消申请已提交 orderId={}, customerId={}", orderId, customerId);
            return;
        }
        if (!OrderStatus.isValidTransition(status, OrderStatus.CANCELLED)) {
            throw new BusinessException("当前订单状态不可取消");
        }

        paymentService.refundOrder(orderId, "客户取消订单");
        log.info("[OrderService] 客户取消订单成功 orderId={}, customerId={}", orderId, customerId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void transitionPaymentStatus(Long id, Integer expected, Integer target) {
        int affected = orderMapper.updatePaymentStatusIf(id, expected, target);
        if (affected == 0) {
            throw new BusinessException("支付状态已被并发修改，请刷新后重试");
        }
    }
}
