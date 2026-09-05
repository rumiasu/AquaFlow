package com.example.aquaflow.controller;

import com.example.aquaflow.common.Result;
import com.example.aquaflow.entity.CompanyInfo;
import com.example.aquaflow.mapper.CompanyInfoMapper;
import com.example.aquaflow.util.AuthContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/company-info")
public class CompanyInfoController {

    @Autowired
    private CompanyInfoMapper companyInfoMapper;

    @GetMapping
    public Result<CompanyInfo> getCompanyInfo() {
        Long customerId = AuthContext.requireCustomerId();
        return Result.success(companyInfoMapper.getByCustomerId(customerId));
    }

    @PostMapping
    public Result<CompanyInfo> saveCompanyInfo(@RequestBody CompanyInfo companyInfo) {
        Long customerId = AuthContext.requireCustomerId();
        companyInfo.setCustomerId(customerId);
        CompanyInfo existing = companyInfoMapper.getByCustomerId(customerId);
        if (existing == null) {
            companyInfoMapper.insert(companyInfo);
        } else {
            companyInfoMapper.updateByCustomerId(companyInfo);
        }
        return Result.success(companyInfoMapper.getByCustomerId(customerId));
    }

    @PutMapping
    public Result<CompanyInfo> updateCompanyInfo(@RequestBody CompanyInfo companyInfo) {
        Long customerId = AuthContext.requireCustomerId();
        companyInfo.setCustomerId(customerId);
        companyInfoMapper.updateByCustomerId(companyInfo);
        return Result.success(companyInfoMapper.getByCustomerId(customerId));
    }
}
