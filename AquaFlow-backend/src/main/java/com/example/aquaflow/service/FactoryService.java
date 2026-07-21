package com.example.aquaflow.service;

import com.example.aquaflow.entity.Factory;

import java.util.List;

public interface FactoryService {

    List<Factory> listAll();

    Factory getById(Integer id);

    void save(Factory factory);

    void update(Factory factory);

    void delete(Integer id);
}
