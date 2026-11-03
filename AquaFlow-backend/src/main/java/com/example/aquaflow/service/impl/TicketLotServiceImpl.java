package com.example.aquaflow.service.impl;

import com.example.aquaflow.entity.TicketAccount;
import com.example.aquaflow.entity.TicketLot;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.TicketAccountMapper;
import com.example.aquaflow.mapper.TicketLotMapper;
import com.example.aquaflow.service.TicketLotService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * 水票批次账实现（v36）。见 {@link TicketLotService} 与 {@code docs/design/19}。
 *
 * <p>实现细节都是照着桶账（{@code BarrelLedgerService}）做的 —— 那套模型在本仓已经跑过对账、
 * 踩过并发坑，水票没有理由另发明一套。</p>
 */
@Service
@Slf4j
public class TicketLotServiceImpl implements TicketLotService {
    @Autowired private com.example.aquaflow.mapper.TicketHoldMapper ticketHoldMapper;
    @Autowired private com.example.aquaflow.mapper.OrderMapper reservationOrders;
    @Autowired private com.example.aquaflow.mapper.OrderItemMapper reservationItems;
    @Autowired private com.example.aquaflow.mapper.TicketRecordMapper ticketRecordMapper;

    private static long number(java.util.Map<String,Object> row,String key) { return ((Number)row.get(key)).longValue(); }
    private java.util.Map<Long,Integer> heldByLot(Long customer,Long station,Long product) {
        java.util.Map<Long,Integer> result=new java.util.HashMap<>();
        for(var row:ticketHoldMapper.heldForAccount(customer,station,product))
            result.merge(number(row,"lot_id"),(int)number(row,"quantity"),Integer::sum);
        return result;
    }
    @Override @Transactional public java.util.Set<Long> previewSourcePayments(Long customer,Long station,Long product,int quantity) {
        if(quantity<=0 || ticketAccountMapper.lockAccount(customer,station,product)==null)return java.util.Set.of();
        var held=heldByLot(customer,station,product);var sources=new java.util.HashSet<Long>();int need=quantity;
        for(TicketLot lot:ticketLotMapper.listUsableFifo(customer,station,product)) {
            if(need==0)break;int take=Math.min(need,Math.max(0,lot.getRemainQty()-held.getOrDefault(lot.getId(),0)));
            if(take>0 && Integer.valueOf(TicketLot.SourceType.PURCHASE).equals(lot.getSourceType()) && lot.getPaymentRecordId()!=null)sources.add(lot.getPaymentRecordId());
            need-=take;
        }
        return need==0?sources:java.util.Set.of();
    }
    @Override public int availableQuantity(Long customer,Long station,Long product) {
        TicketAccount account=ticketAccountMapper.getByCustomerProductStation(customer,product,station);
        return account==null?0:Math.max(0,account.getRemainQuantity()-ticketHoldMapper.heldQuantity(customer,station,product));
    }
    @Override @Transactional public void assertAvailable(Long customer,Long station,Long product,int quantity) {
        TicketAccount account=ticketAccountMapper.lockAccount(customer,station,product);
        int held=heldByLot(customer,station,product).values().stream().mapToInt(Integer::intValue).sum();
        if(account==null || quantity<=0 || account.getRemainQuantity()-held<quantity)
            throw new BusinessException("可用水票不足，部分水票可能正等待服务费付款；请先核对原订单或补齐水票");
    }
    @Override @Transactional public void reserveForOrder(Long customer,Long station,Long product,Long order,int quantity) {
        var original=order==null?null:reservationOrders.getById(order);
        if(original==null || !customer.equals(original.getCustomerId()) || !station.equals(original.getStationId())
                || !Integer.valueOf(com.example.aquaflow.constant.PayMethod.TICKET).equals(original.getPaymentMethod()) || Integer.valueOf(com.example.aquaflow.constant.OrderStatus.CANCELLED).equals(original.getStatus()))
            throw new BusinessException("只能为本人归属站的有效水票订单预留");
        int expected=reservationItems.listByOrderId(order).stream().filter(line->product.equals(line.getProductId())).mapToInt(line->line.getQuantity()).sum();
        if(expected!=quantity)throw new BusinessException("预留数量与原订单不一致");
        TicketAccount account=ticketAccountMapper.lockAccount(customer,station,product);
        if(account==null || quantity<=0)throw new BusinessException("可用水票不足");
        var previous=ticketHoldMapper.forOrderProduct(order,product);
        if(!previous.isEmpty()) {
            int total=previous.stream().mapToInt(row->(int)number(row,"quantity")).sum();
            if(total!=quantity || previous.stream().anyMatch(row->!java.util.Set.of("HELD","CONSUMED").contains(row.get("state"))))
                throw new BusinessException("原订单的水票预留已变化，请查询原订单");
            return;
        }
        assertAvailable(customer,station,product,quantity);
        var held=heldByLot(customer,station,product);int need=quantity;
        for(TicketLot lot:ticketLotMapper.listUsableFifo(customer,station,product)) {
            if(need==0)break;int take=Math.min(need,Math.max(0,lot.getRemainQty()-held.getOrDefault(lot.getId(),0)));
            if(take>0){if(ticketHoldMapper.insert(order,lot.getId(),product,take,lot.getUnitPrice())!=1)throw new BusinessException("水票预留未保存");need-=take;}
        }
        if(need!=0)throw new BusinessException("水票批次可用量不足，请核对原订单");
    }
    private void reservationRecord(Long customer,Long station,Long product,Long order,int quantity,BigDecimal amount,Long lot,boolean refund) {
        var record=new com.example.aquaflow.entity.TicketRecord();record.setCustomerId(customer);record.setStationId(station);record.setProductId(product);record.setOrderId(order);
        record.setIncreaseQty(refund?quantity:0);record.setDecreaseQty(refund?0:quantity);record.setSource(refund?"退款":com.example.aquaflow.constant.TicketRecordSource.CONSUMPTION);
        record.setTicketSource(1);record.setUnitPrice(amount.divide(BigDecimal.valueOf(quantity),4,RoundingMode.HALF_UP));record.setTicketLotId(lot);record.setCreateTime(java.time.LocalDateTime.now());
        ticketRecordMapper.insert(record);
    }
    @Override @Transactional public void consumeOrderReservation(Long customer,Long station,Long order) {
        for(Long product:ticketHoldMapper.products(order)) {
            TicketAccount account=ticketAccountMapper.lockAccount(customer,station,product);
            if(account==null)throw new BusinessException("水票账户不存在");
            var rows=ticketHoldMapper.forOrderProduct(order,product);int quantity=0;BigDecimal amount=BigDecimal.ZERO;
            if(rows.stream().allMatch(row->"CONSUMED".equals(row.get("state"))))continue;
            if(rows.stream().anyMatch(row->!"HELD".equals(row.get("state"))))throw new BusinessException("水票预留状态已变化，不能确认消费");
            for(var row:rows){Long lotId=number(row,"lot_id");TicketLot lot=ticketLotMapper.getById(lotId);
                if(lot==null || !customer.equals(lot.getCustomerId()) || !station.equals(lot.getStationId()))throw new BusinessException("预留水票不属于本订单客户和水站");
                int q=(int)number(row,"quantity");
                if(ticketHoldMapper.move(number(row,"id"),"HELD","CONSUMED")!=1 || ticketLotMapper.decrementRemain(lotId,q)!=1)throw new BusinessException("水票预留已被处理");
                quantity+=q;amount=amount.add(((BigDecimal)row.get("unit_price")).multiply(BigDecimal.valueOf(q)));
            }
            if(quantity<=0 || ticketAccountMapper.decrementQuantity(account.getId(),quantity)!=1)throw new BusinessException("水票预留与账户不一致");
            reservationRecord(customer,station,product,order,quantity,amount,rows.size()==1?number(rows.get(0),"lot_id"):null,false);
            refreshRightAmount(customer,station,product);
        }
    }
    @Override @Transactional public void releaseOrderReservation(Long customer,Long station,Long order) {
        for(Long product:ticketHoldMapper.products(order)) {
            ticketAccountMapper.lockAccount(customer,station,product);
            for(var row:ticketHoldMapper.forOrderProduct(order,product))if("HELD".equals(row.get("state"))) {
                TicketLot lot=ticketLotMapper.getById(number(row,"lot_id"));
                if(lot==null || !customer.equals(lot.getCustomerId()) || !station.equals(lot.getStationId()))throw new BusinessException("预留水票归属不符");
                if(ticketHoldMapper.move(number(row,"id"),"HELD","RELEASED")!=1)throw new BusinessException("水票预留状态已变化");
            }
        }
    }
    @Override @Transactional public void refundOrderReservation(Long customer,Long station,Long order) {
        for(Long product:ticketHoldMapper.products(order)) {
            TicketAccount account=ticketAccountMapper.lockAccount(customer,station,product);
            if(account==null)throw new BusinessException("水票账户不存在");
            var rows=ticketHoldMapper.forOrderProduct(order,product);int quantity=0;BigDecimal amount=BigDecimal.ZERO;
            if(rows.stream().allMatch(row->"REFUNDED".equals(row.get("state"))))continue;
            if(rows.stream().anyMatch(row->!"CONSUMED".equals(row.get("state"))))throw new BusinessException("只能返还已实际消费的水票");
            for(var row:rows){Long lotId=number(row,"lot_id");TicketLot lot=ticketLotMapper.getById(lotId);
                if(lot==null || !customer.equals(lot.getCustomerId()) || !station.equals(lot.getStationId()))throw new BusinessException("消费水票归属不符");
                int q=(int)number(row,"quantity");
                if(ticketHoldMapper.move(number(row,"id"),"CONSUMED","REFUNDED")!=1 || ticketLotMapper.restoreReserved(lotId,q)!=1)throw new BusinessException("原批次回票失败，不能重复回票");
                quantity+=q;amount=amount.add(((BigDecimal)row.get("unit_price")).multiply(BigDecimal.valueOf(q)));
            }
            ticketAccountMapper.incrementQuantity(account.getId(),quantity);
            reservationRecord(customer,station,product,order,quantity,amount,rows.size()==1?number(rows.get(0),"lot_id"):null,true);
            refreshRightAmount(customer,station,product);
        }
    }

    @Override
    @Transactional
    public ConsumeResult consumePurchasedBalance(Long customerId,Long stationId,Long productId,Long paymentId) {
        TicketAccount account=ticketAccountMapper.lockAccount(customerId,stationId,productId);
        if (account==null) throw new BusinessException("本批没有可退的水票余额");
        List<TicketLot> lots=ticketLotMapper.purchasedBalanceForUpdate(paymentId);
        var held=heldByLot(customerId,stationId,productId);
        if(lots.stream().anyMatch(lot->held.getOrDefault(lot.getId(),0)>0))throw new BusinessException("本批水票正被订单预留，不能退出");
        int quantity=0; BigDecimal amount=BigDecimal.ZERO;
        for (TicketLot lot:lots) {
            if (!customerId.equals(lot.getCustomerId()) || !stationId.equals(lot.getStationId()) || !productId.equals(lot.getProductId())) throw new BusinessException("水票批次归属不符");
            int q=lot.getRemainQty();
            if (ticketLotMapper.decrementRemain(lot.getId(),q)!=1) throw new BusinessException("水票余额已变动，请刷新后重试");
            quantity+=q; amount=amount.add(lot.getUnitPrice().multiply(BigDecimal.valueOf(q)));
        }
        if (quantity<=0 || ticketAccountMapper.decrementQuantity(account.getId(),quantity)!=1) throw new BusinessException("本批余额不足或已经退完");
        refreshRightAmount(customerId,stationId,productId);
        ConsumeResult result=new ConsumeResult(); result.setTotalAmount(amount); result.setQuantity(quantity);
        result.setWeightedUnitPrice(amount.divide(BigDecimal.valueOf(quantity),4,RoundingMode.HALF_UP));
        result.setSingleLotId(lots.size()==1?lots.get(0).getId():null); return result;
    }

    @Autowired
    private TicketLotMapper ticketLotMapper;

    @Autowired
    private TicketAccountMapper ticketAccountMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TicketLot createLot(Long customerId, Long stationId, Long productId, BigDecimal unitPrice, int qty,
                               int sourceType, int priceSource, boolean priceInferred,
                               Long paymentRecordId, String note) {
        ticketAccountMapper.lockAccount(customerId,stationId,productId);
        if (qty <= 0) {
            throw new BusinessException("批次张数必须大于 0");
        }
        TicketLot lot = new TicketLot();
        // 占位号：唯一且 ≤32 字符（lot_no 是 varchar(32)）。正式号里带自增 id，所以只能先插后改。
        // 用 UUID 而不是时间戳：同一毫秒内建两批（一次下单买两种水票）时时间戳会撞唯一键。
        lot.setLotNo("TMP-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        lot.setCustomerId(customerId);
        lot.setStationId(stationId);
        lot.setProductId(productId);
        // 单价为空按 0 处理而不是抛错：历史迁移时可能连参考价都查不到，
        // 那种批次的正确处置是"标记为推断值、退票需二次确认"，而不是让整个迁移失败。
        lot.setUnitPrice(unitPrice != null ? unitPrice : BigDecimal.ZERO);
        lot.setQty(qty);
        lot.setRemainQty(qty);
        lot.setSourceType(sourceType);
        lot.setPriceSource(priceSource);
        lot.setIsMigrated(priceInferred ? 1 : 0);
        lot.setPaymentRecordId(paymentRecordId);
        lot.setStatus(1);
        lot.setOperatorId(com.example.aquaflow.util.AuthContext.getUserId());
        lot.setNote(note);
        ticketLotMapper.insert(lot);

        String lotNo = String.format("TM%s-%06d",
                LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE), lot.getId());
        ticketLotMapper.setLotNo(lot.getId(), lotNo);
        lot.setLotNo(lotNo);

        refreshRightAmount(customerId, stationId, productId);
        log.info("[v36] 水票批次入账: lotNo={}, customerId={}, productId={}, stationId={}, qty={}, unitPrice={}, sourceType={}, 单价为推断={}",
                lotNo, customerId, productId, stationId, qty, lot.getUnitPrice(), sourceType, priceInferred);
        return lot;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ConsumeResult consumeFifo(Long customerId, Long stationId, Long productId, int qty) {
        if (qty <= 0) {
            throw new BusinessException("消耗张数必须大于 0");
        }
        List<TicketLot> lots = ticketLotMapper.listUsableFifo(customerId, stationId, productId);
        var held=heldByLot(customerId,stationId,productId);
        int need = qty;
        BigDecimal amount = BigDecimal.ZERO;
        Long singleLotId = null;
        int touched = 0;

        for (TicketLot lot : lots) {
            if (need <= 0) break;
            int remain = (lot.getRemainQty() != null ? lot.getRemainQty() : 0)-held.getOrDefault(lot.getId(),0);
            if (remain <= 0) continue;
            int take = Math.min(need, remain);

            // CAS 扣减：判断与扣减在同一条 SQL 里，避免"读到旧快照双双通过"（AGENTS §8.2）。
            // 返回 0 表示被并发改过，跳过该批次继续往后找，而不是当成失败。
            int affected = ticketLotMapper.decrementRemain(lot.getId(), take);
            if (affected == 0) {
                log.warn("[v36] 批次并发冲突，跳过: lotId={}, 想扣={}", lot.getId(), take);
                continue;
            }
            BigDecimal price = lot.getUnitPrice() != null ? lot.getUnitPrice() : BigDecimal.ZERO;
            amount = amount.add(price.multiply(BigDecimal.valueOf(take)));
            need -= take;
            touched++;
            singleLotId = lot.getId();
        }

        if (need > 0) {
            // 正常流程不该走到这里：调用方应先用 ticket_account.remain_quantity 校验并扣减。
            // 走到这里说明批次与账户余额已经不一致（那正是 E8 要抓的），宁可失败出声也不要静默少扣。
            throw new BusinessException("水票批次余额不足，账目可能不一致，请联系管理员");
        }

        refreshRightAmount(customerId, stationId, productId);

        ConsumeResult r = new ConsumeResult();
        r.setTotalAmount(amount);
        // 加权均价带 4 位小数：跨批次消耗时要用它写流水与退款回补，
        // 只留 2 位会在"部分退款"时累积舍入误差，最后对不上账。
        r.setWeightedUnitPrice(amount.divide(BigDecimal.valueOf(qty), 4, RoundingMode.HALF_UP));
        r.setSingleLotId(touched == 1 ? singleLotId : null);
        return r;
    }

    @Override
    public void refreshRightAmount(Long customerId, Long stationId, Long productId) {
        TicketAccount account = ticketAccountMapper.getByCustomerProductStation(customerId, productId, stationId);
        if (account == null) {
            // 没有账户就没有可同步的汇总行。不抛错：调用方可能正在建账户（先建 lot 后建 account 的顺序），
            // 账户建好后会再调一次。
            return;
        }
        BigDecimal right = ticketLotMapper.currentRightAmount(customerId, stationId, productId);
        ticketAccountMapper.setRightAmount(account.getId(), right != null ? right : BigDecimal.ZERO);
    }
}
