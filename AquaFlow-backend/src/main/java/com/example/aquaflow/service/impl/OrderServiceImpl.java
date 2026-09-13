package com.example.aquaflow.service.impl;

import com.example.aquaflow.constant.DepositType;
import com.example.aquaflow.constant.InventoryChangeType;
import com.example.aquaflow.constant.OrderStatus;
import com.example.aquaflow.constant.PaymentStatus;
import com.example.aquaflow.constant.PayMethod;
import com.example.aquaflow.dto.OrderCreateDTO;
import com.example.aquaflow.dto.OrderCreateResult;
import com.example.aquaflow.entity.*;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.*;
import com.example.aquaflow.service.AssetService;
import com.example.aquaflow.service.AuditLogService;
import com.example.aquaflow.service.InventoryService;
import com.example.aquaflow.service.OrderService;
import com.example.aquaflow.service.PaymentService;
import com.example.aquaflow.util.AuthContext;
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
public class OrderServiceImpl implements OrderService {

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
     * [AQ-030] 欠桶风控：下单前检查客户在本站的欠桶数。
     * <p>数据源是 {@code customer_barrel_over}（按商品、可为负），不是已停写的旧表
     * {@code customer_owed_barrel}——下面的 {@code totalOwed} 计算就是按 over 逐商品 max(0,·) 求和。</p>
     */
    @Autowired
    private com.example.aquaflow.mapper.CustomerBarrelOverMapper customerBarrelOverMapper;

    /** [AQ-030] 允许的最大欠桶数，超过则拒绝新单 */
    private static final int MAX_OWED_BUCKETS = 5;


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
        if (dto.getStationId() == null) {
            throw new BusinessException("请先选择服务水站");
        }

        // 幂等性检查：防止重复下单。客户端未传则服务端生成 UUID，确保任何请求都强制幂等，
        // 杜绝因 idempotencyKey 为 null 而跳过查重（AQ-019）
        String idempotencyKey = dto.getIdempotencyKey();
        if (idempotencyKey == null || idempotencyKey.isEmpty()) {
            idempotencyKey = java.util.UUID.randomUUID().toString();
        }
        Orders existing = orderMapper.findByIdempotencyKey(idempotencyKey);
        if (existing != null) {
            java.util.List<String> warnings = new java.util.ArrayList<>();
            return OrderCreateResult.success(existing.getId(), warnings, false);
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
        if ("staff".equals(AuthContext.getUserType())
                && customerStationConfigMapper.getByCustomerAndStation(dto.getCustomerId(), stationId) == null) {
            throw new BusinessException("该客户不属于本水站，无法代客下单");
        }

        Address addr = addressMapper.getById(dto.getAddressId());
        if (addr == null) {
            throw new BusinessException("地址不存在");
        }
        // [AQ-011] 地址必须归属当前下单客户，否则可填他人地址并读出姓名/电话/门牌（隐私泄露）
        if (!dto.getCustomerId().equals(addr.getCustomerId())) {
            throw new BusinessException("该地址不属于当前客户");
        }

        // [AQ-030] 欠桶风控：客户在本站欠桶超阈值时拒绝新单。
        // 原实现下单链路完全不读 owed_qty，唯一拦截点是"退押金"，导致顾客能一直借桶、想结算时被拦，体验割裂。
        // [DEF-3] 欠桶风控改为【按商品】统计（customer_barrel_over），阈值按站内 Σ max(0, over) 计。
        // over 可为负（多还桶 / 水站暂存，合法状态），负值不能拿去抵销其他商品的欠桶
        // ——A 水多还的桶不能抵 B 水的欠桶——所以必须先 max(0, ...) 再求和。
        int totalOwed = 0;
        List<CustomerBarrelOver> overs = customerBarrelOverMapper.listByCustomerAndStation(dto.getCustomerId(), stationId);
        if (overs != null) {
            for (CustomerBarrelOver o : overs) {
                if (o.getOverQty() == null) continue;
                totalOwed += Math.max(0, o.getOverQty());
            }
        }
        if (totalOwed >= MAX_OWED_BUCKETS) {
            throw new BusinessException("您在本水站有 " + totalOwed + " 个欠桶未归还，请先归还后再下单");
        }

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
            // 判断是否涉及站点资产（桶装水类别=1）
            if (product.getCategory() != null && Integer.valueOf(1).equals(product.getCategory())) {
                needStationAsset = true;
            }
        }

        // 货到付款（现金）权限校验：PayMethod 中 2=现金、3=水票
        // 旧代码用 3 判断"线下支付"，与 PayMethod 定义冲突，导致水票支付被要求走线下授权校验
        if (Integer.valueOf(PayMethod.CASH).equals(dto.getPaymentMethod())) {
            if (!paymentService.canUseOfflinePayment(dto.getCustomerId(), stationId)) {
                throw new BusinessException("当前客户暂不支持货到付款");
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
            if (inv == null) {
                log.warn("[OrderService] Station {} has no inventory for product {} ({}), creating default inventory", stationId, item.getProductId(), product.getName());
                Inventory newInv = new Inventory();
                newInv.setStationId(stationId);
                newInv.setProductId(item.getProductId());
                newInv.setQuantity(0);
                newInv.setEnabled(1);
                newInv.setTicketEnabled(0);
                newInv.setCreateTime(LocalDateTime.now());
                newInv.setUpdateTime(LocalDateTime.now());
                inventoryMapper.insert(newInv);
                inv = newInv;
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

            int stock = inv.getQuantity() != null ? inv.getQuantity() : 0;
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
            if (Integer.valueOf(PayMethod.TICKET).equals(dto.getPaymentMethod())) {
                boolean ticketEnabled = inv.getTicketEnabled() != null && Integer.valueOf(1).equals(inv.getTicketEnabled());
                BigDecimal stTicketPrice = inv.getTicketPrice();
                boolean hasPrice = stTicketPrice != null && stTicketPrice.compareTo(BigDecimal.ZERO) > 0;
                if (!ticketEnabled || !hasPrice) {
                    throw new BusinessException("商品「" + product.getName() + "」未开通水票支付");
                }
            }

            // 与结算页报价共用同一套单价算法（水票支付用站级水票价，其余用零售价）
            BigDecimal unitPrice = PriceUtil.calcUnitPrice(product, inv, dto.getPaymentMethod());
            BigDecimal itemSubtotal = unitPrice.multiply(BigDecimal.valueOf(item.getQuantity()));
            waterAmount = waterAmount.add(itemSubtotal);

            // 桶装水(category=1)不在此处收押金，仅在 extraDeposit 按缺桶数收取
            if (product.getCategory() == null || !Integer.valueOf(1).equals(product.getCategory())) {
                BigDecimal itemDeposit = product.getDeposit() != null ? product.getDeposit() : BigDecimal.ZERO;
                depositAmount = depositAmount.add(itemDeposit.multiply(BigDecimal.valueOf(item.getQuantity())));
            }

            if (product.getCategory() != null && Integer.valueOf(1).equals(product.getCategory())) {
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
            Map<Long, Integer> pendingByProduct = new HashMap<>();
            List<CustomerBarrelInTransit> pendings =
                    customerBarrelInTransitMapper.listByCustomerAndStation(dto.getCustomerId(), stationId);
            if (pendings != null) {
                for (CustomerBarrelInTransit t : pendings) {
                    if (t.getProductId() == null || !"PENDING".equals(t.getStatus())) continue;
                    pendingByProduct.merge(t.getProductId(), t.getQty() == null ? 0 : t.getQty(), Integer::sum);
                }
            }

            BigDecimal requiredExtraDeposit = BigDecimal.ZERO;
            Map<Long, Integer> extraByProduct = new HashMap<>();
            Map<Long, BigDecimal> priceByProduct = new HashMap<>();
            for (Map.Entry<Long, Integer> e : barrelByProduct.entrySet()) {
                Long pid = e.getKey();
                int needed = e.getValue();
                int held = heldByProduct.getOrDefault(pid, 0);
                int pending = pendingByProduct.getOrDefault(pid, 0);
                int shortage = Math.max(0, needed - held - pending);
                if (shortage > 0) {
                    extraByProduct.put(pid, shortage);
                    Product p = productCache.get(pid);
                    BigDecimal dep = (p != null && p.getDeposit() != null) ? p.getDeposit() : BigDecimal.ZERO;
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

        Orders orders = new Orders();
        orders.setCustomerId(dto.getCustomerId());
        orders.setAddressId(dto.getAddressId());
        orders.setStationId(stationId);
        orders.setDeliveryStationId(stationId);
        orders.setSource(dto.getSource() != null ? dto.getSource() : 2);
        orders.setPaymentMethod(dto.getPaymentMethod());
        orders.setPaymentStatus(PaymentStatus.PENDING);
        orders.setWaterAmount(waterAmount);
        orders.setDepositAmount(depositAmount);
        orders.setTotalAmount(waterAmount.add(depositAmount));
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
        orders.setFirstBarrelOrder(firstStationAsset && totalNeededBuckets > 0);
        orders.setIdempotencyKey(idempotencyKey);
        orders.setStatus(1);
        orders.setCreateTime(LocalDateTime.now());
        orders.setUpdateTime(LocalDateTime.now());
        orderMapper.save(orders);

        // 更新配送中桶资产记录的关联订单ID（仅关联本次创建的配送中桶记录）
        if (!createdInTransitIds.isEmpty()) {
            customerBarrelInTransitMapper.linkPendingToOrder(orders.getId(), createdInTransitIds);
        }

        for (OrderCreateDTO.OrderItemDTO item : dto.getItems()) {
            Product product = productCache.get(item.getProductId());
            Inventory inv = invCache.get(item.getProductId());
            BigDecimal unitPrice = PriceUtil.calcUnitPrice(product, inv, dto.getPaymentMethod());
            // 桶装水押金在 extraDeposit 中统一处理，order item 记 0
            BigDecimal itemDeposit = (product.getCategory() != null && Integer.valueOf(1).equals(product.getCategory()))
                    ? BigDecimal.ZERO
                    : (product.getDeposit() != null ? product.getDeposit() : BigDecimal.ZERO);

            // 本次实际能扣减的库存量：库存不足时只能扣到 min(stock, quantity)
            // 落库到 deducted_qty，取消/退款时按此回补，避免"下单10桶库存只有3桶，取消却回补10桶"刷出库存
            int stock = inv != null && inv.getQuantity() != null ? inv.getQuantity() : 0;
            int toDecrease = Math.max(0, Math.min(stock, item.getQuantity()));

            OrderItem oi = new OrderItem();
            oi.setOrderId(orders.getId());
            oi.setProductId(item.getProductId());
            oi.setProductNameSnapshot(product.getName());
            oi.setBrandSnapshot(product.getBrand());
            oi.setSpecSnapshot(product.getSpec());
            oi.setPrice(unitPrice);
            oi.setQuantity(item.getQuantity());
            oi.setDeposit(itemDeposit);
            oi.setDeductedQty(toDecrease);
            oi.setSubtotal(unitPrice.multiply(BigDecimal.valueOf(item.getQuantity())));
            oi.setCreateTime(LocalDateTime.now());
            orderItemMapper.insert(oi);
        }

        // ===== 扣减库存: 库存充足扣全量, 不足扣 min(stock, requested), 为0不扣 =====
        List<String> warnings = new java.util.ArrayList<>();
        for (OrderCreateDTO.OrderItemDTO item : dto.getItems()) {
            Inventory inv = invCache.get(item.getProductId());
            int stock = inv.getQuantity() != null ? inv.getQuantity() : 0;
            int toDecrease = Math.min(stock, item.getQuantity());
            if (toDecrease > 0) {
                int affected = inventoryMapper.decreaseStock(stationId, item.getProductId(), toDecrease);
                if (affected <= 0) {
                    throw new BusinessException("扣减库存失败: " + productCache.get(item.getProductId()).getName());
                }
                // [AQ-029] 扣减库存写流水，与库存变动同事务
                inventoryService.recordChange(stationId, item.getProductId(), -toDecrease,
                        InventoryChangeType.CONSUME, orders.getId(), AuthContext.getUserId(), "下单扣减");
            }
            if (stock < item.getQuantity()) {
                Product p = productCache.get(item.getProductId());
                if (stock == 0) {
                    warnings.add(p.getName() + " 暂时没货，需要等待配送");
                } else {
                    warnings.add(p.getName() + " 库存不足(仅剩" + stock + "桶)，缺" + (item.getQuantity() - stock) + "桶需等待配送");
                }
            }
        }

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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void save(Orders orders) {
        // #9: save接口限制 — 只允许更新已存在的订单，不允许通过此接口创建新订单
        if (orders.getId() == null) {
            throw new BusinessException("不允许通过此接口创建订单，请使用下单接口");
        }
        Orders existing = orderMapper.getById(orders.getId());
        if (existing == null) {
            throw new BusinessException("订单不存在");
        }

        // AQ-005: 跨站改价/搬单防护 — 调用方必须是订单所属水站的员工；且本接口不允许改站。
        Long myStationId = AuthContext.requireStationId();
        Long orderStation = StationUtil.deliveryStation(existing);
        if (myStationId == null || orderStation == null || !myStationId.equals(orderStation)) {
            throw new BusinessException("无权修改他站订单");
        }

        if (orders.getCustomerId() != null) {
            var customer = customerMapper.getById(orders.getCustomerId());
            if (customer == null) {
                throw new BusinessException("客户不存在");
            }
        }

        if (orders.getAddressId() != null) {
            Address addr = addressMapper.getById(orders.getAddressId());
            if (addr == null) {
                throw new BusinessException("地址不存在");
            }
            // AQ-011: 地址归属校验 — 不允许把订单地址改成他人地址（泄露隐私）。
            if (orders.getCustomerId() != null && !orders.getCustomerId().equals(addr.getCustomerId())) {
                throw new BusinessException("地址不属于该客户");
            }
            orders.setReceiverName(addr.getName());
            orders.setReceiverPhone(addr.getPhone());
            orders.setAddressSnapshot(addr.getDetail());
            orders.setAddressSnapshotLat(addr.getLat());
            orders.setAddressSnapshotLng(addr.getLng());
        }

        // AQ-005/012: 归属锁定 — 不允许通过此接口改站、改客户归属；代客改单的客户必须归属本站。
        orders.setStationId(existing.getStationId());
        if (orders.getCustomerId() == null) {
            orders.setCustomerId(existing.getCustomerId());
        } else if (customerStationConfigMapper.getByCustomerAndStation(orders.getCustomerId(), myStationId) == null) {
            throw new BusinessException("该客户不属于当前水站");
        }

        // 保留原有状态和支付状态，不允许通过此接口修改
        orders.setStatus(existing.getStatus());
        orders.setPaymentStatus(existing.getPaymentStatus());
        orders.setCreateTime(existing.getCreateTime());
        orders.setUpdateTime(LocalDateTime.now());

        orderMapper.update(orders);
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
     * 2. 状态校验——客户仅可取消"待配送"(PENDING)，已进入配送环节需联系水站，
     *    避免骑手已出发却被撤单；
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
        if (status != OrderStatus.PENDING) {
            throw new BusinessException("当前订单状态不可取消，如需帮助请联系水站");
        }
        if (!OrderStatus.isValidTransition(status, OrderStatus.CANCELLED)) {
            throw new BusinessException("当前订单状态不可取消");
        }

        paymentService.refundOrder(orderId, "客户取消订单");
        log.info("[OrderService] 客户取消订单成功 orderId={}, customerId={}", orderId, customerId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long id, Integer status) {
        transitionStatus(id, status);
    }

    @Transactional(rollbackFor = Exception.class)
    public void transitionStatus(Long id, Integer targetStatus) {
        Orders order = orderMapper.getById(id);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }
        int currentStatus = order.getStatus() != null ? order.getStatus() : 0;
        // AQ-014: 强制走状态机，杜绝越级跳转（如 PENDING 直接跳 COMPLETED）
        if (!OrderStatus.isValidTransition(currentStatus, targetStatus)) {
            throw new BusinessException("不允许从状态 " + currentStatus + " 转换到 " + targetStatus);
        }
        // AQ-014: CAS 更新，状态已被并发修改则拒绝（affected=0 => 失败）
        int affected = orderMapper.updateStatusIf(id, currentStatus, targetStatus);
        if (affected == 0) {
            throw new BusinessException("订单状态已被并发修改，请刷新后重试");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void transitionPaymentStatus(Long id, Integer expected, Integer target) {
        int affected = orderMapper.updatePaymentStatusIf(id, expected, target);
        if (affected == 0) {
            throw new BusinessException("支付状态已被并发修改，请刷新后重试");
        }
    }
}
