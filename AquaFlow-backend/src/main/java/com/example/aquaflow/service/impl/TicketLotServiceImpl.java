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

    @Autowired
    private TicketLotMapper ticketLotMapper;

    @Autowired
    private TicketAccountMapper ticketAccountMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TicketLot createLot(Long customerId, Long stationId, Long productId, BigDecimal unitPrice, int qty,
                               int sourceType, int priceSource, boolean priceInferred,
                               Long paymentRecordId, String note) {
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
        int need = qty;
        BigDecimal amount = BigDecimal.ZERO;
        Long singleLotId = null;
        int touched = 0;

        for (TicketLot lot : lots) {
            if (need <= 0) break;
            int remain = lot.getRemainQty() != null ? lot.getRemainQty() : 0;
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
        BigDecimal right = ticketLotMapper.sumRightAmount(customerId, stationId, productId);
        ticketAccountMapper.setRightAmount(account.getId(), right != null ? right : BigDecimal.ZERO);
    }
}
