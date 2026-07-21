package com.example.aquaflow.service.impl;

import com.example.aquaflow.entity.CompanyInfo;
import com.example.aquaflow.mapper.CompanyInfoMapper;
import com.example.aquaflow.service.CompanyInfoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class CompanyInfoServiceImpl implements CompanyInfoService {

    @Autowired
    private CompanyInfoMapper companyInfoMapper;

    @Override
    public CompanyInfo getByCustomerId(Integer customerId) {
        return companyInfoMapper.getByCustomerId(customerId);
    }

    @Override
    public void save(CompanyInfo info) {
        CompanyInfo existing = companyInfoMapper.getByCustomerId(info.getCustomerId());
        if (existing == null) {
            info.setCreateTime(LocalDateTime.now());
            info.setUpdateTime(LocalDateTime.now());
            companyInfoMapper.insert(info);
        } else {
            companyInfoMapper.update(info);
        }
    }
}
