package com.example.aquaflow.service.impl;

import com.example.aquaflow.constant.DepositType;
import com.example.aquaflow.constant.OrderStatus;
import com.example.aquaflow.constant.PaymentStatus;
import com.example.aquaflow.dto.OrderCreateDTO;
import com.example.aquaflow.dto.OrderCreateResult;
import com.example.aquaflow.entity.*;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.*;
import com.example.aquaflow.service.AssetService;
import com.example.aquaflow.service.AuditLogService;
import com.example.aquaflow.service.OrderService;
import com.example.aquaflow.service.PaymentService;
import com.example.aquaflow.util.AuthContext;
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

@Override
    @Transactional
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

        // 幂等性检查：防止重复下单
        if (dto.getIdempotencyKey() != null && !dto.getIdempotencyKey().isEmpty()) {
            Orders existing = orderMapper.findByIdempotencyKey(dto.getIdempotencyKey());
            if (existing != null) {
                java.util.List<String> warnings = new java.util.ArrayList<>();
                return OrderCreateResult.success(existing.getId(), warnings, false);
            }
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

        Address addr = addressMapper.getById(dto.getAddressId());
        if (addr == null) {
            throw new BusinessException("地址不存在");
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

        // 线下支付权限校验
        if (dto.getPaymentMethod() != null && Integer.valueOf(3).equals(dto.getPaymentMethod())) {
            if (!paymentService.canUseOfflinePayment(dto.getCustomerId(), stationId)) {
                throw new BusinessException("当前客户暂不支持线下支付");
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

            BigDecimal unitPrice = product.getPrice();
            if (unitPrice == null) {
                unitPrice = BigDecimal.ZERO;
            }
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

            BigDecimal requiredExtraDeposit = BigDecimal.ZERO;
            Map<Long, Integer> extraByProduct = new HashMap<>();
            for (Map.Entry<Long, Integer> e : barrelByProduct.entrySet()) {
                Long pid = e.getKey();
                int needed = e.getValue();
                int held = heldByProduct.getOrDefault(pid, 0);
                int shortage = Math.max(0, needed - held);
                if (shortage > 0) {
                    extraByProduct.put(pid, shortage);
                    Product p = productCache.get(pid);
                    BigDecimal dep = (p != null && p.getDeposit() != null) ? p.getDeposit() : BigDecimal.ZERO;
                    requiredExtraDeposit = requiredExtraDeposit.add(dep.multiply(BigDecimal.valueOf(shortage)));
                }
            }

            if (!extraByProduct.isEmpty()) {
                BigDecimal extraDeposit = dto.getExtraDeposit() != null ? dto.getExtraDeposit() : BigDecimal.ZERO;
                if (extraDeposit.compareTo(requiredExtraDeposit) < 0) {
                    throw new BusinessException("桶资产不足，需额外押桶押金 " + requiredExtraDeposit + " 元");
                }

                if (extraDeposit.compareTo(BigDecimal.ZERO) > 0) {
                    customerDepositAccountMapper.increaseBalance(dto.getCustomerId(), stationId, extraDeposit);
                    depositAmount = depositAmount.add(extraDeposit);

                    DepositRecord dr = new DepositRecord();
                    dr.setCustomerId(dto.getCustomerId());
                    dr.setStationId(stationId);
                    dr.setType(5); // PREPAID: 下单预收押金
                    dr.setAmount(extraDeposit);
                    dr.setNote("下单预收桶押金");
                    dr.setOperatorId(AuthContext.getUserId());
                    dr.setCreateTime(LocalDateTime.now());
                    depositRecordMapper.insert(dr);

                    // 创建在途桶资产记录，而非直接增加桶资产
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
                        inTransit.setCreateTime(LocalDateTime.now());
                        inTransit.setUpdateTime(LocalDateTime.now());
                        // 先保存，订单创建后更新 relatedOrderId
                        customerBarrelInTransitMapper.insert(inTransit);
                        createdInTransitIds.add(inTransit.getId());
                    }
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
        orders.setIdempotencyKey(dto.getIdempotencyKey());
        orders.setStatus(1);
        orders.setCreateTime(LocalDateTime.now());
        orders.setUpdateTime(LocalDateTime.now());
        orderMapper.save(orders);

        // 更新在途桶资产记录的关联订单ID（仅关联本次创建的在途桶记录）
        if (!createdInTransitIds.isEmpty()) {
            customerBarrelInTransitMapper.linkPendingToOrder(orders.getId(), createdInTransitIds);
        }

        for (OrderCreateDTO.OrderItemDTO item : dto.getItems()) {
            Product product = productCache.get(item.getProductId());
            Inventory inv = invCache.get(item.getProductId());
            BigDecimal unitPrice = product.getPrice();
            if (unitPrice == null) unitPrice = BigDecimal.ZERO;
            // 桶装水押金在 extraDeposit 中统一处理，order item 记 0
            BigDecimal itemDeposit = (product.getCategory() != null && Integer.valueOf(1).equals(product.getCategory()))
                    ? BigDecimal.ZERO
                    : (product.getDeposit() != null ? product.getDeposit() : BigDecimal.ZERO);

            OrderItem oi = new OrderItem();
            oi.setOrderId(orders.getId());
            oi.setProductId(item.getProductId());
            oi.setProductNameSnapshot(product.getName());
            oi.setBrandSnapshot(product.getBrand());
            oi.setSpecSnapshot(product.getSpec());
            oi.setPrice(unitPrice);
            oi.setQuantity(item.getQuantity());
            oi.setDeposit(itemDeposit);
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
    @Transactional
    public void save(Orders orders) {
        // #9: save接口限制 — 只允许更新已存在的订单，不允许通过此接口创建新订单
        if (orders.getId() == null) {
            throw new BusinessException("不允许通过此接口创建订单，请使用下单接口");
        }
        Orders existing = orderMapper.getById(orders.getId());
        if (existing == null) {
            throw new BusinessException("订单不存在");
        }
        if (orders.getCustomerId() != null) {
            var customer = customerMapper.getById(orders.getCustomerId());
            if (customer == null) {
                throw new RuntimeException("客户不存在");
            }
        }

        if (orders.getAddressId() != null) {
            Address addr = addressMapper.getById(orders.getAddressId());
            if (addr == null) {
                throw new RuntimeException("地址不存在");
            }
            orders.setReceiverName(addr.getName());
            orders.setReceiverPhone(addr.getPhone());
            orders.setAddressSnapshot(addr.getDetail());
            orders.setAddressSnapshotLat(addr.getLat());
            orders.setAddressSnapshotLng(addr.getLng());
        }

        // 保留原有状态和支付状态，不允许通过此接口修改
        orders.setStatus(existing.getStatus());
        orders.setPaymentStatus(existing.getPaymentStatus());
        orders.setCreateTime(existing.getCreateTime());
        orders.setUpdateTime(LocalDateTime.now());

        orderMapper.update(orders);
    }

    @Override
    public List<Orders> list(Long stationId, Long customerId, Integer status, String createTimeStart, String createTimeEnd) {
        return orderMapper.list(stationId, customerId, status, createTimeStart, createTimeEnd);
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
    @Transactional
    /**
     * 客户主动取消订单。
     *
     * 设计要点：
     * 1. 归属校验——只能取消自己的订单；
     * 2. 状态校验——客户仅可取消"待配送"(PENDING)，已进入配送环节需联系水站，
     *    避免骑手已出发却被撤单；
     * 3. 完整回滚下单副作用（下单时扣了库存、收了押桶押金、生成了在途桶记录），
     *    否则会造成库存凭空减少、押金余额虚高、在途桶残留。
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

        Long stationId = order.getStationId();

        // 1) 回补库存：按下单时扣减的商品数量原样加回
        List<OrderItem> items = orderItemMapper.listByOrderId(orderId);
        if (items != null) {
            for (OrderItem oi : items) {
                if (oi.getProductId() != null && oi.getQuantity() != null && oi.getQuantity() > 0) {
                    inventoryMapper.increaseStock(stationId, oi.getProductId(), oi.getQuantity());
                }
            }
        }

        // 2) 退还预收桶押金（写负金额流水，便于对账）
        BigDecimal depositAmount = order.getDepositAmount();
        if (depositAmount != null && depositAmount.compareTo(BigDecimal.ZERO) > 0) {
            int affected = customerDepositAccountMapper.decreaseBalance(customerId, stationId, depositAmount);
            if (affected > 0) {
                DepositRecord dr = new DepositRecord();
                dr.setCustomerId(customerId);
                dr.setStationId(stationId);
                dr.setType(DepositType.CANCEL_PREPAID); // 8 取消订单释放预收押金
                dr.setAmount(depositAmount.negate());
                dr.setNote("客户取消订单释放押金");
                dr.setOperatorId(null);
                dr.setCreateTime(LocalDateTime.now());
                depositRecordMapper.insert(dr);
            }
        }

        // 3) 清理该订单产生的在途桶记录（PENDING 状态）
        List<CustomerBarrelInTransit> inTransitList = customerBarrelInTransitMapper.listPendingByOrderId(orderId);
        if (inTransitList != null && !inTransitList.isEmpty()) {
            customerBarrelInTransitMapper.deleteByOrderId(orderId);
        }

        // 4) 状态置为已取消
        orderMapper.updateStatus(orderId, OrderStatus.CANCELLED);
        log.info("[OrderService] 客户取消订单成功 orderId={}, customerId={}", orderId, customerId);
    }

    @Override
    @Transactional
    public void updateStatus(Long id, Integer status) {
        Orders order = orderMapper.getById(id);
        if (order == null) {
            throw new RuntimeException("订单不存在");
        }
        // #10: 使用isValidTransition校验状态转换合法性
        int currentStatus = order.getStatus() != null ? order.getStatus() : 0;
        if (!OrderStatus.isValidTransition(currentStatus, status)) {
            throw new BusinessException("不允许从状态 " + currentStatus + " 转换到 " + status);
        }
        orderMapper.updateStatus(id, status);
    }
}
