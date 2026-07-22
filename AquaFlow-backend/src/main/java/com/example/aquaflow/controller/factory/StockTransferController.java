package com.example.aquaflow.controller.factory;

import com.example.aquaflow.common.Result;
import com.example.aquaflow.entity.StockTransfer;
import com.example.aquaflow.service.factory.StockTransferService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/factory-ops/transfers")
public class StockTransferController {

    @Autowired
    private StockTransferService transferService;

    @GetMapping("/available")
    public Result<List<Map<String, Object>>> availableStock() {
        return Result.success(transferService.availableStock());
    }

    @PostMapping
    public Result create(@RequestBody StockTransfer transfer) {
        transferService.create(transfer);
        return Result.success();
    }

    @GetMapping
    public Result<List<StockTransfer>> list(
            @RequestParam(required = false) Integer status) {
        return Result.success(transferService.list(status));
    }

    @PutMapping("/{id}/approve")
    public Result approve(@PathVariable Integer id, @RequestBody Map<String, String> body) {
        transferService.approve(id, body.get("note"));
        return Result.success();
    }

    @PutMapping("/{id}/complete")
    public Result complete(@PathVariable Integer id, @RequestBody Map<String, String> body) {
        transferService.complete(id, body.get("note"));
        return Result.success();
    }

    @PutMapping("/{id}/cancel")
    public Result cancel(@PathVariable Integer id) {
        transferService.cancel(id);
        return Result.success();
    }
}
