package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.dto.BatchCreateDTO;
import com.example.aquaflow.dto.BatchFinishDTO;
import com.example.aquaflow.entity.Batch;
import com.example.aquaflow.service.BatchService;
import com.example.aquaflow.util.AuthContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;


import java.util.Date;
import java.util.List;


@RestController
@RequestMapping("/api/batches")
public class BatchController {

    @Autowired
    private BatchService batchService;

    @PostMapping
    public Result create(@RequestBody BatchCreateDTO batchCreateDTO){
        Batch batch = batchService.create(batchCreateDTO.getOrderIds());
        return Result.success(batch.getId());
    }

    @GetMapping
    public Result<List<Batch>> list(@RequestParam(required = false) Integer status,
                                    @RequestParam(required = false) String createTimeStart,
                                    @RequestParam(required = false) String createTimeEnd){
        return Result.success(batchService.list(status,createTimeStart,createTimeEnd));
    }

    @GetMapping("/{id}")
    public Result<Batch> getById(@PathVariable Integer id){
        return Result.success(batchService.getById(id));
    }

    @PostMapping("/{id}/start")
    public Result start(@PathVariable Integer id){
        batchService.start(id);
        return Result.success();
    }

    @PostMapping("/{id}/finish")
    public Result finish(@PathVariable Integer id, @RequestBody BatchFinishDTO batchFinishDTO){
        batchService.finish(id,batchFinishDTO.getFinishedOrderIds(),batchFinishDTO.getUnfinishedOrderIds());
        return Result.success();
    }

    @PostMapping("/{id}/finish-all")
    public Result finishAll(@PathVariable Integer id){
        batchService.finishAll(id);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    public Result delete (@PathVariable Integer id){
        batchService.delete(id);
        return Result.success();
    }

    /**
     * 配送员查询自己的批次列表（含订单详情）
     */
    @RequireRole("DELIVERY")
    @GetMapping("/my")
    public Result<List<Batch>> myBatches() {
        Integer staffId = AuthContext.getUserId();
        return Result.success(batchService.listByDeliveryPersonId(staffId));
    }

    /**
     * 站长分配配送员到批次
     */
    @RequireRole({"FACTORY_ADMIN", "STATION_MANAGER"})
    @PostMapping("/{id}/assign")
    public Result assignDeliveryPerson(@PathVariable Integer id, @RequestParam Integer deliveryPersonId) {
        batchService.assignDeliveryPerson(id, deliveryPersonId);
        return Result.success();
    }
}
