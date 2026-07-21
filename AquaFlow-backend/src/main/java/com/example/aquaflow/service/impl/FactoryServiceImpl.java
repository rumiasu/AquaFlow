package com.example.aquaflow.service.impl;

import com.example.aquaflow.entity.Factory;
import com.example.aquaflow.mapper.FactoryMapper;
import com.example.aquaflow.service.FactoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class FactoryServiceImpl implements FactoryService {

    @Autowired
    private FactoryMapper factoryMapper;

    @Override
    public List<Factory> listAll() {
        return factoryMapper.listAll();
    }

    @Override
    public Factory getById(Integer id) {
        Factory factory = factoryMapper.getById(id);
        if (factory == null) {
            throw new RuntimeException("水厂不存在");
        }
        return factory;
    }

    @Override
    public void save(Factory factory) {
        factory.setCreateTime(LocalDateTime.now());
        factory.setUpdateTime(LocalDateTime.now());
        factoryMapper.insert(factory);
    }

    @Override
    public void update(Factory factory) {
        factory.setUpdateTime(LocalDateTime.now());
        factoryMapper.update(factory);
    }

    @Override
    public void delete(Integer id) {
        factoryMapper.delete(id);
    }
}
