package com.example.aquaflow.service.impl;

import com.example.aquaflow.entity.WaterType;
import com.example.aquaflow.mapper.WaterTypeMapper;
import com.example.aquaflow.service.WaterTypeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class WaterTypeServiceImpl implements WaterTypeService {

    @Autowired
    private WaterTypeMapper waterTypeMapper;

    @Override
    public void save(WaterType waterType) {
        waterType.setCreateTime(LocalDateTime.now());
        waterType.setUpdateTime(LocalDateTime.now());
        waterTypeMapper.insert(waterType);
    }

    @Override
    public void update(WaterType waterType) {
        waterType.setUpdateTime(LocalDateTime.now());
        waterTypeMapper.update(waterType);
    }

    @Override
    public void delete(Integer id) {
        waterTypeMapper.deleteById(id);
    }

    @Override
    public List<WaterType> list() {
        return waterTypeMapper.list();
    }

    @Override
    public List<WaterType> listByKeyword(String keyword) {
        return waterTypeMapper.listByKeyword(keyword);
    }

    @Override
    public WaterType getById(Integer id) {
        WaterType waterType = waterTypeMapper.getById(id);
        if (waterType == null){
            throw new RuntimeException("该水类型无记录");
        }
        return waterType;
    }

    @Override
    public List<Map<String, Object>> listMyTypes(Integer customerId) {
        return waterTypeMapper.listMyTypes(customerId);
    }
}
