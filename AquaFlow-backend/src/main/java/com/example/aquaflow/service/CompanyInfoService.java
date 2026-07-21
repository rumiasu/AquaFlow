package com.example.aquaflow.service;

import com.example.aquaflow.entity.CompanyInfo;

public interface CompanyInfoService {

    CompanyInfo getByCustomerId(Integer customerId);

    void save(CompanyInfo info);
}
