package com.example.aquaflow.service;

import com.example.aquaflow.entity.Product;

import java.util.List;

public interface ProductService {

    /**
     * 保存商品，验证必填字段，失败抛出 BusinessException
     */
    void save(Product product);

    void update(Product product);

    void deleteById(Long id);

    List<Product> list();

    Product getById(Long id);

    List<Product> listByKeyword(String keyword);

    List<Product> listByCategory(Integer category);

    List<Product> listOnSale();
}