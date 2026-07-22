package com.example.aquaflow.controller;

import com.example.aquaflow.common.Result;
import com.example.aquaflow.entity.CompanyInfo;
import com.example.aquaflow.service.CompanyInfoService;
import com.example.aquaflow.util.AuthContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/company-info")
@Slf4j
public class CompanyInfoController {

    @Autowired
    private CompanyInfoService companyInfoService;

    @GetMapping
    public Result<CompanyInfo> getByCustomerId() {
        Integer customerId = AuthContext.requireCustomerId();
        return Result.success(companyInfoService.getByCustomerId(customerId));
    }

    /** 员工查询指定客户的企业信息 */
    @GetMapping("/customer/{customerId}")
    public Result<CompanyInfo> getByCustomerIdForStaff(@PathVariable Integer customerId) {
        AuthContext.requireStaffRole();
        return Result.success(companyInfoService.getByCustomerId(customerId));
    }

    @PostMapping
    public Result save(@RequestBody CompanyInfo info) {
        companyInfoService.save(info);
        return Result.success();
    }
}
