package com.example.aquaflow.service;

import com.example.aquaflow.entity.TicketAccount;

import java.util.List;

public interface TicketAccountService {

    List<TicketAccount> listByCustomerId(Integer customerId);

    void addTicket(Integer customerId, Integer waterTypeId, Integer qty);

    void consumeTicket(Integer customerId, Integer waterTypeId, Integer qty, Integer orderId);
}
