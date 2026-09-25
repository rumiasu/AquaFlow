package com.example.aquaflow.controller;

import com.example.aquaflow.common.Result;
import com.example.aquaflow.entity.CompanyInfo;
import com.example.aquaflow.mapper.CompanyInfoMapper;
import com.example.aquaflow.util.AuthContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 客户企业资料（开票信息等），<b>顾客自助</b>。
 *
 * <p>身份由 {@code AuthContext.requireCustomerId()} 强制取得并回写进实体，
 * 因此不存在"改到别人资料"的可能 —— 这也是本类无需 {@code @RequireRole} 的原因。
 * 若将来新增"站长代某客户填企业信息"这类端点，<b>必须</b>改标注解并显式校验站别归属。</p>
 */
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
