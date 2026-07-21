package com.example.aquaflow.service;

import com.example.aquaflow.entity.WaterType;

import java.util.List;
import java.util.Map;

public interface WaterTypeService {
    void save(WaterType waterType);
    void update(WaterType waterType);
    void delete(Integer id);
    List<WaterType> list();
    List<WaterType> listByKeyword(String keyword);
    WaterType getById(Integer id);
    List<Map<String, Object>> listMyTypes(Integer customerId);
}
