package com.example.aquaflow.controller;

import com.example.aquaflow.common.Result;
import com.example.aquaflow.entity.CompanyInfo;
import com.example.aquaflow.service.CompanyInfoService;
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
    public Result<CompanyInfo> getByCustomerId(@RequestParam Integer customerId) {
        return Result.success(companyInfoService.getByCustomerId(customerId));
    }

    @PostMapping
    public Result save(@RequestBody CompanyInfo info) {
        companyInfoService.save(info);
        return Result.success();
    }
}
