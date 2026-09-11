package com.example.aquaflow.service;

import com.example.aquaflow.entity.TicketAccount;

import java.util.List;

public interface TicketAccountService {

    List<TicketAccount> listByCustomerAndStation(Long customerId, Long stationId);

    void addTicket(Long customerId, Long productId, Integer qty, Long stationId);

    void consumeTicket(Long customerId, Long productId, Integer qty, Long orderId, Long stationId);

    /** 退款归还水票：回补客户水票账户余额并记一条"退款"流水（AQ-008） */
    void refundTicket(Long customerId, Long productId, Integer qty, Long orderId, Long stationId);

    /** 客户线上购买水票：入账水票 + 生成支付记录（无订单） */
    com.example.aquaflow.entity.PaymentRecord purchaseTicket(Long customerId, Long productId, Integer qty, Integer paymentMethod, Long stationId);
}
