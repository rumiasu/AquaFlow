package com.example.aquaflow.controller;

import com.example.aquaflow.common.Result;
import com.example.aquaflow.dto.AccountDataRequestDTO;
import com.example.aquaflow.service.AccountDataRequestService;
import com.example.aquaflow.vo.AccountDataRequestVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** Existing authenticated customer/staff identity only. No station/manager handling or deletion authority. */
@RestController
@RequestMapping("/api/account/data-requests")
public class AccountDataRequestController {
    private final AccountDataRequestService service;
    public AccountDataRequestController(AccountDataRequestService service){this.service=service;}
    @GetMapping("/options") public Result<AccountDataRequestService.Options> options(@RequestParam Map<String,String> inputs) {
        if(!inputs.isEmpty())return Result.error("仅查询本人资料请求设置，不接受身份或水站参数");
        return Result.success(service.options());
    }
    @PostMapping public Result<AccountDataRequestVO> submit(@RequestBody @Valid AccountDataRequestDTO dto){return Result.success(service.submit(dto));}
    @GetMapping("/my") public Result<AccountDataRequestService.Page> mine(@RequestParam(required=false) Long beforeId,@RequestParam Map<String,String> inputs) {
        if(inputs.keySet().stream().anyMatch(key->!"beforeId".equals(key)))return Result.error("仅查询本人资料请求，不接受身份或水站参数");
        return Result.success(service.mine(beforeId));
    }
    @GetMapping("/{id}") public Result<AccountDataRequestService.Detail> detail(@PathVariable Long id,@RequestParam Map<String,String> inputs) {
        if(!inputs.isEmpty())return Result.error("仅查询本人资料请求，不接受身份或水站参数");
        return Result.success(service.detail(id));
    }
}
