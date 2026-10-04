package com.example.aquaflow.service;

import com.example.aquaflow.constant.*;
import com.example.aquaflow.dto.OrderCreateDTO;
import com.example.aquaflow.entity.*;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.*;
import com.example.aquaflow.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/** 随单押金的原款、逐商品购买及退款编排；桶账只经 BarrelLedgerService。 */
@Service
public class OrderBarrelPurchaseService {
    @Autowired private BarrelBusinessPolicy policy;
    @Autowired private BarrelLedgerService ledger;
    @Autowired private BarrelBusinessMapper rights;
    @Autowired private OrderBarrelPurchaseMapper purchases;
    @Autowired private ProductMapper products;
    @Autowired private InventoryMapper inventory;
    @Autowired private CustomerBarrelLotMapper lots;
    @Autowired private DepositRecordService deposits;
    @Autowired private CustomerStationConfigMapper configs;
    @Autowired private PaymentRecordMapper payments;
    @Autowired private ConsumptionRefundMapper consumption;
    @Autowired private RefundReceiptReader receiptReader;
    @Autowired private OrderMapper orders;
    @Value("${app.payment.mock-wechat-pay:false}") private boolean mockWechatPay;

    /** 报价和建单共享缺口算法；写路径按商品升序锁定并使用当前读。 */
    public record Line(Long productId, String productName, int quantity, BigDecimal unitPrice,
                       BigDecimal amount, int availableRights, int busyRights) {}

    public List<Line> plan(Long customer, Long station, Map<Long,Integer> demand, boolean locked) {
        List<Line> result=new ArrayList<>();
        for (Long product : new TreeSet<>(demand.keySet())) {
            int right=locked?ledger.lockRights(customer,station,product):ledger.rightQty(customer,station,product);
            int busy=locked?rights.activeForUpdate(customer,station,product).stream()
                    .mapToInt(r -> r.getQuantity()-r.getPendingQty()).sum():rights.reserved(customer,station,product);
            busy+=rights.legacyOrderBusy(customer,station,product)+rights.legacyReturnBusy(customer,station,product);
            int available=Math.max(0,right-busy), missing=Math.max(0,demand.get(product)-available);
            Product p=products.getById(product);
            BigDecimal price=PriceUtil.calcDeposit(p,inventory.getByStationAndProduct(station,product));
            if(missing>0 && (price==null || price.signum()<=0))throw new BusinessException("本站尚未设置可办理的桶押金，请联系水站");
            if(missing>0)result.add(new Line(product,p.getName(),missing,price,price.multiply(BigDecimal.valueOf(missing)),available,busy));
        }
        return result;
    }

    /** 未经明确确认或数量/价格变化时拒绝新增押金；不信任客户端金额。 */
    public void validate(List<Line> plan,List<OrderCreateDTO.BarrelPurchaseConfirmation> confirmation,Integer method) {
        if(!plan.isEmpty() && Integer.valueOf(PayMethod.TICKET).equals(method))
            throw new BusinessException("水票不能支付新增桶押金，请改选付款方式或先独立办理押金");
        List<OrderCreateDTO.BarrelPurchaseConfirmation> input=confirmation==null?List.of():confirmation;
        if(input.size()!=plan.size())throw new BusinessException("请重新报价并明确确认本次新增桶押金；已有容量被占用时也可等待释放");
        Set<Long> seen=new HashSet<>();
        for(var c:input) {
            Line line=plan.stream().filter(l -> Objects.equals(l.productId(),c.getProductId())).findFirst().orElse(null);
            if(line==null || !seen.add(c.getProductId()) || c.getQuantity()==null || c.getQuantity()!=line.quantity()
                    || c.getUnitPrice()==null || c.getUnitPrice().compareTo(line.unitPrice())!=0)
                throw new BusinessException("本次新增桶押金数量或价格已变化，请重新报价并确认");
        }
    }

    /** 分配总需求但只占用已有生效容量；未付意图保留至终态识别。 */
    @Transactional
    public void create(Orders order,Map<Long,Integer> demand,List<Line> plan) {
        for(Long product:new TreeSet<>(demand.keySet())) {
            Line line=plan.stream().filter(l -> product.equals(l.productId())).findFirst().orElse(null);
            int pending=line==null?0:line.quantity();
            ledger.reserveOrderPurchase(order.getCustomerId(),order.getStationId(),product,demand.get(product),pending,order.getId());
            if(line==null)continue;
            OrderBarrelPurchase row=new OrderBarrelPurchase();row.setOrderId(order.getId());row.setCustomerId(order.getCustomerId());
            row.setStationId(order.getStationId());row.setProductId(product);row.setQuantity(pending);
            row.setUnitPrice(line.unitPrice());row.setAmount(line.amount());purchases.insert(row);
        }
    }

    public boolean hasPurchase(Long order) { return policy.hasCombinedSchema() && purchases.hasOrder(order)>0; }
    public OrderBarrelPurchase byLot(Long lot) { return policy.hasCombinedSchema()?purchases.byLot(lot):null; }

    /** 2026-10-03：合并收款不能只增加押金余额；同事务激活批次和分配，避免有钱无容量。 */
    @Transactional
    public void activate(Long orderId) {
        Orders order=consumption.lockOrder(orderId);
        if(order!=null && purchases.lockOrder(orderId).stream().noneMatch(r -> "PENDING".equals(r.getStatus())))return;
        if(order==null || !OrderStatus.isCancellable(order.getStatus()))throw new BusinessException("本单不能再激活新增押金");
        PaymentRecord paid=payments.listByOrderId(orderId).stream().filter(p -> p.getStatus()==PaymentStatus.PAID).findFirst()
                .orElseThrow(() -> new BusinessException("请先实际收到本单水款和押金"));
        if(paid.getAmount().compareTo(order.getTotalAmount())!=0 || paid.getBarrelDeposit()==null
                || paid.getBarrelDeposit().compareTo(order.getDepositAmount())!=0
                || purchases.lockOrder(orderId).stream().map(OrderBarrelPurchase::getAmount)
                    .reduce(BigDecimal.ZERO,BigDecimal::add).compareTo(order.getDepositAmount())!=0)
            throw new BusinessException("本单收款与押金快照不一致");
        configs.ensureExists(order.getCustomerId(),order.getStationId());
        for(OrderBarrelPurchase row:purchases.lockOrder(orderId)) {
            if(!"PENDING".equals(row.getStatus()))continue;
            DepositRecord dr=deposit(row,DepositType.PREPAID,row.getQuantity(),row.getAmount(),"随水单实收押金");
            deposits.add(dr,row.getStationId());
            CustomerBarrelLot lot=ledger.purchaseRight(row.getCustomerId(),row.getStationId(),row.getProductId(),row.getUnitPrice(),row.getQuantity(),AuthContext.getUserId());
            if(lots.linkDepositRecord(lot.getId(),dr.getId())!=1 || purchases.activate(row.getId(),paid.getId(),lot.getId())!=1)
                throw new BusinessException("本单押金凭据已变化，收款已回滚");
            ledger.fundOrderPurchase(row.getCustomerId(),row.getStationId(),row.getProductId(),orderId,row.getQuantity());
        }
    }

    /** 取消只退本单新批次的空闲部分；独立购买和其他正在占用的容量保留。 */
    @Transactional
    public void cancel(Orders order,List<PaymentRecord> paid,String reason,BigDecimal expectedAmount) {
        List<OrderBarrelPurchase> rows=purchases.lockOrder(order.getId());
        purchases.cancelPending(order.getId());
        for(PaymentRecord snapshot:paid) {
            PaymentRecord original=consumption.lockPayment(snapshot.getId());
            BigDecimal deposit=BigDecimal.ZERO;
            Map<Long,Integer> quantities=new LinkedHashMap<>();
            for(OrderBarrelPurchase row:rows) {
                if(!"PAID".equals(row.getStatus()) || !original.getId().equals(row.getPaymentId()))continue;
                int q=ledger.cancelUnusedPurchase(row.getCustomerId(),row.getStationId(),row.getProductId(),row.getLotId());
                if(q==0)continue;
                BigDecimal amount=row.getUnitPrice().multiply(BigDecimal.valueOf(q));
                deposits.add(deposit(row,DepositType.CANCEL_PREPAID,q,amount,"取消水单退本次空闲新增押金"),row.getStationId());
                deposit=deposit.add(amount);quantities.put(row.getId(),q);
            }
            Map<String,Object> prior=currentRefundTotals(original.getId());
            BigDecimal water=nz(original.getWaterAmount()).subtract(decimal(prior.get("waterAmount")));
            BigDecimal delivery=nz(order.getDeliveryFee()).subtract(decimal(prior.get("deliveryFee")));
            BigDecimal floor=nz(order.getFloorFee()).subtract(decimal(prior.get("floorFee")));
            BigDecimal amount=water.add(delivery).add(floor).add(deposit);
            if(expectedAmount!=null && expectedAmount.compareTo(amount)!=0)
                throw new BusinessException("退款金额已变化，请重新预览并确认实际交付金额；本次未登记退款");
            if(amount.signum()<=0)continue;
            PaymentRecord refund=refund(original,amount,water,deposit,delivery,floor,"取消水单："+reason,AuthContext.getUserId());
            if(water.add(delivery).add(floor).signum()>0 && consumption.record(original.getId(),refund.getId(),order.getId(),"ALL_CONSUMPTION",water,delivery,floor,AuthContext.getUserId(),reason)!=1)
                throw new BusinessException("消费退款凭据未保存");
            for(OrderBarrelPurchase row:rows) {
                Integer q=quantities.get(row.getId());if(q==null)continue;
                recordRefund(row,original,refund,q,row.getUnitPrice().multiply(BigDecimal.valueOf(q)),null,"CANCEL");
            }
            finishPayment(original);
        }
    }

    /** 只读预览：假定释放本订单分配后，共享桶账执行时的可退出资格；执行时仍加锁复核。 */
    public Map<String,Object> cancelPreview(Orders order,PaymentRecord original) {
        BigDecimal refundableDeposit=BigDecimal.ZERO;
        for(OrderBarrelPurchase row:purchases.listOrder(order.getId())) {
            if(!"PAID".equals(row.getStatus()) || !original.getId().equals(row.getPaymentId()))continue;
            int q=ledger.previewCancelUnusedPurchase(row.getCustomerId(),row.getStationId(),row.getProductId(),row.getLotId(),order.getId());
            refundableDeposit=refundableDeposit.add(row.getUnitPrice().multiply(BigDecimal.valueOf(q)));
        }
        Map<String,Object> prior=consumption.refunded(original.getId());
        BigDecimal remainingConsumption=nz(original.getWaterAmount()).subtract(decimal(prior.get("waterAmount")))
                .add(nz(order.getDeliveryFee()).subtract(decimal(prior.get("deliveryFee"))))
                .add(nz(order.getFloorFee()).subtract(decimal(prior.get("floorFee"))));
        BigDecimal retained=nz(original.getBarrelDeposit()).subtract(purchases.refunded(original.getId())).subtract(refundableDeposit);
        BigDecimal amount=remainingConsumption.add(refundableDeposit);
        String notice="取消释放本单分配，仅退本单尚未使用且未被其他用途占用的新增押金；原有独立资产保留。实际退款前会重新核实。";
        if(retained.signum()>0)notice+=" 本单新增押金保留 ¥"+retained+"：容量已覆盖实际持桶、被其他用途占用或退款申请锁定，须解除占用或走退桶申请。";
        return Map.of("notice",notice,"refundAmount",amount,"refundableDeposit",refundableDeposit,"retainedDeposit",retained,
                "scopes",List.of(Map.of("scope","ALL_CONSUMPTION","label","取消订单并退款 ¥"+amount,"expectedRefundAmount",amount,"confirmationRequired",true)));
    }

    /** 多批次退还先按稳定顺序锁订单、原款，再取得桶账锁；与消费退款一致。 */
    public void lockReturnSources(List<OrderBarrelPurchase> rows) {
        rows.stream().map(OrderBarrelPurchase::getOrderId).distinct().sorted().forEach(consumption::lockOrder);
        rows.stream().map(OrderBarrelPurchase::getPaymentId).distinct().sorted().forEach(consumption::lockPayment);
    }

    /** 退还固定批次的原款；共享合并支付的退款上限，不把历史来源猜成现金。 */
    @Transactional
    public void refundReturned(OrderBarrelPurchase row,int quantity,BigDecimal amount,Long record,Long payer) {
        consumption.lockOrder(row.getOrderId());
        PaymentRecord original=consumption.lockPayment(row.getPaymentId());
        PaymentRecord refund=refund(original,amount,BigDecimal.ZERO,amount,BigDecimal.ZERO,BigDecimal.ZERO,"随单押金原款退还，申请 "+record,payer);
        recordRefund(row,original,refund,quantity,amount,record,"RETURN");finishPayment(original);
    }

    private void recordRefund(OrderBarrelPurchase row,PaymentRecord original,PaymentRecord refund,int quantity,BigDecimal amount,Long record,String reason) {
        if(purchases.refund(row.getId(),quantity,amount)!=1 || purchases.refundProof(row.getId(),original.getId(),refund.getId(),record,quantity,amount,reason)!=1)
            throw new BusinessException("押金原款已退款或凭据变化，本次退款已回滚");
    }

    /**
     * 调用前必须持有原付款行锁，锁在独立只读事务期间也不会释放。
     * 原款行锁使同一笔写入串行；凭据不可变且只追加，按主键去重合并最新提交与本事务新增。
     * 不能对非唯一退款索引做范围 FOR UPDATE：不同原款的空区间锁会互相阻止插入。
     */
    public Map<String,Object> currentRefundTotals(Long original) {
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("退款上限核验必须在持有原款锁的事务中执行");
        boolean combined=policy.hasCombinedSchema();
        RefundReceiptReader.Rows latest=receiptReader.latestCommitted(original,combined);
        List<Map<String,Object>> water=mergeReceiptRows(latest.consumption(),consumption.refundRows(original));
        List<Map<String,Object>> deposit=combined?mergeReceiptRows(latest.deposits(),purchases.refundRows(original)):List.of();
        return Map.of("waterAmount",sumReceiptColumn(water,"waterAmount"),"deliveryFee",sumReceiptColumn(water,"deliveryFee"),
                "floorFee",sumReceiptColumn(water,"floorFee"),"depositAmount",sumReceiptColumn(deposit,"amount"));
    }

    private static List<Map<String,Object>> mergeReceiptRows(List<Map<String,Object>> committed,List<Map<String,Object>> ownView) {
        Map<Long,Map<String,Object>> rows=new LinkedHashMap<>();
        for(var row:committed)rows.put(((Number)row.get("receiptId")).longValue(),row);
        for(var row:ownView)rows.put(((Number)row.get("receiptId")).longValue(),row);
        return new ArrayList<>(rows.values());
    }
    private static BigDecimal sumReceiptColumn(List<Map<String,Object>> rows,String column) {
        return rows.stream().map(row -> decimal(row.get(column))).reduce(BigDecimal.ZERO,BigDecimal::add);
    }

    /** 消费和押金共享同一原款上限；原流水仅在全部组成实际退完时终结。 */
    public void assertRefundRoom(PaymentRecord original,BigDecimal amount) {
        Map<String,Object> prior=currentRefundTotals(original.getId());
        BigDecimal total=decimal(prior.get("waterAmount")).add(decimal(prior.get("deliveryFee"))).add(decimal(prior.get("floorFee"))).add(decimal(prior.get("depositAmount")));
        if(amount.signum()<=0 || total.add(amount).compareTo(original.getAmount())>0)
            throw new BusinessException("累计退款超过原收款金额，请核实原款");
    }

    public void finishPayment(PaymentRecord original) {
        Map<String,Object> prior=currentRefundTotals(original.getId());
        BigDecimal total=decimal(prior.get("waterAmount")).add(decimal(prior.get("deliveryFee"))).add(decimal(prior.get("floorFee"))).add(decimal(prior.get("depositAmount")));
        if(total.compareTo(original.getAmount())==0 && original.getStatus()==PaymentStatus.PAID) {
            if(payments.updateStatusTo(original.getId(),PaymentStatus.REFUNDED,PaymentStatus.PAID)!=1)
                throw new BusinessException("原收款状态已变化，退款已回滚");
            orders.updatePaymentStatusIf(original.getOrderId(),PaymentStatus.PAID,PaymentStatus.REFUNDED);
        }
    }

    private PaymentRecord refund(PaymentRecord original,BigDecimal amount,BigDecimal water,BigDecimal deposit,BigDecimal delivery,BigDecimal floor,String note,Long payer) {
        if (Integer.valueOf(PayMethod.WECHAT).equals(original.getPaymentMethod()) && !mockWechatPay)
            throw new BusinessException("原微信退款渠道尚不可用，不能登记为实际退款，请联系水站核实原款");
        assertRefundRoom(original,amount);
        PaymentRecord r=new PaymentRecord();r.setOrderId(original.getOrderId());r.setCustomerId(original.getCustomerId());r.setStationId(original.getStationId());
        r.setPaymentMethod(original.getPaymentMethod());r.setStatus(PaymentStatus.REFUNDED);r.setAmount(amount.negate());
        r.setWaterAmount(water.negate());r.setBarrelDeposit(deposit.negate());r.setDeliveryFee(delivery.negate());r.setFloorFee(floor.negate());
        r.setOperatorId(payer);r.setNote(note.substring(0,Math.min(200,note.length())));r.setCreateTime(LocalDateTime.now());r.setUpdateTime(LocalDateTime.now());payments.insert(r);return r;
    }

    private DepositRecord deposit(OrderBarrelPurchase row,int type,int quantity,BigDecimal amount,String note) {
        DepositRecord r=new DepositRecord();r.setCustomerId(row.getCustomerId());r.setProductId(row.getProductId());r.setRelatedOrderId(row.getOrderId());
        r.setType(type);r.setQuantity(quantity);r.setUnitPrice(row.getUnitPrice());r.setAmount(amount);r.setOperatorId(AuthContext.getUserId());r.setNote(note);return r;
    }
    private static BigDecimal nz(BigDecimal value) { return value==null?BigDecimal.ZERO:value; }
    private static BigDecimal decimal(Object value) { return value==null?BigDecimal.ZERO:new BigDecimal(value.toString()); }
}
