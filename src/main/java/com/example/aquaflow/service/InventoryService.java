package com.example.aquaflow.service;

import com.example.aquaflow.entity.Inventory;

import java.util.List;
import java.util.Map;

public interface InventoryService {
    List<Inventory> list();

    void inbound(List<Map<String, Integer>> items);
}
