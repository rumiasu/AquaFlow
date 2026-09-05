package com.example.aquaflow.service.impl;

import com.example.aquaflow.entity.Product;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.ProductMapper;
import com.example.aquaflow.service.ProductService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class ProductServiceImpl implements ProductService {

    @Autowired
    private ProductMapper productMapper;

    @Override
    public void save(Product product) {
        validateProduct(product, true);
        product.setCreateTime(LocalDateTime.now());
        product.setUpdateTime(LocalDateTime.now());
        productMapper.insert(product);
    }

    @Override
    public void update(Product product) {
        if (product.getId() == null) {
            throw new BusinessException("商品ID不能为空");
        }
        validateProduct(product, false);
        product.setUpdateTime(LocalDateTime.now());
        productMapper.update(product);
    }

    @Override
    public void deleteById(Long id) {
        productMapper.deleteById(id);
    }

    @Override
    public List<Product> list() {
        return productMapper.list();
    }

    @Override
    public Product getById(Long id) {
        return productMapper.getById(id);
    }

    @Override
    public List<Product> listByKeyword(String keyword) {
        return productMapper.listByKeyword(keyword);
    }

    @Override
    public List<Product> listByCategory(Integer category) {
        return productMapper.listByCategory(category);
    }

    @Override
    public List<Product> listOnSale() {
        return productMapper.listOnSale();
    }

    /**
     * 校验商品必填字段和业务规则
     * @param isCreate 是否为新增操作
     */
    private void validateProduct(Product product, boolean isCreate) {
        if (product == null) {
            throw new BusinessException("商品信息不能为空");
        }
        if (product.getName() == null || product.getName().trim().isEmpty()) {
            throw new BusinessException("商品名称不能为空");
        }
        if (product.getCategory() == null) {
            throw new BusinessException("商品分类不能为空（1=桶装水，2=瓶装水，3=饮水器）");
        }
        if (product.getCategory() < 1 || product.getCategory() > 3) {
            throw new BusinessException("商品分类值无效，必须为 1(桶装水)、2(瓶装水) 或 3(饮水器)");
        }
        if (product.getPrice() == null || product.getPrice().compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException("售价不能为空且不能为负数");
        }
        if (product.getDeposit() == null || product.getDeposit().compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException("押金不能为负数");
        }
        if (product.getStatus() == null) {
            throw new BusinessException("状态不能为空（0=下架，1=正常，2=停售）");
        }
        if (product.getStatus() < 0 || product.getStatus() > 2) {
            throw new BusinessException("状态值无效，必须为 0(下架)、1(正常) 或 2(停售)");
        }
        if (product.getSort() == null) {
            throw new BusinessException("排序不能为空");
        }
        // 桶装水必须有押金
        if (Integer.valueOf(1).equals(product.getCategory()) && (product.getDeposit() == null || product.getDeposit().compareTo(BigDecimal.ZERO) <= 0)) {
            throw new BusinessException("桶装水分类必须设置大于 0 的押金");
        }
    }
}