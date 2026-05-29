package com.example.aquaflow.service;

import com.example.aquaflow.entity.WaterType;

import java.util.List;

public interface WaterTypeService {
    void save(WaterType waterType);
    List<WaterType> list();
    WaterType getById(Integer id);
}
