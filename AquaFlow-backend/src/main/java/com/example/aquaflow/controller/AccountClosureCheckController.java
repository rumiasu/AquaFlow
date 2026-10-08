package com.example.aquaflow.controller;

import com.example.aquaflow.common.Result;
import com.example.aquaflow.service.AccountClosureCheckService;
import com.example.aquaflow.util.AuthContext;
import com.example.aquaflow.vo.AccountClosureCheckVO;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** Read-only own-account entry. No user/station ID parameter and no deletion command. */
@RestController
@RequestMapping("/api/customer/account")
public class AccountClosureCheckController {
    private final AccountClosureCheckService service;
    public AccountClosureCheckController(AccountClosureCheckService service){this.service=service;}
    @GetMapping("/closure-check")
    public Result<AccountClosureCheckVO> check(@RequestParam Map<String,String> inputs) {
        AuthContext.requireCustomerId();
        if(!inputs.isEmpty())return Result.error("注销前检查仅查询当前账户，不接受身份或水站参数");
        return Result.success(service.checkMyAccount());
    }
}
