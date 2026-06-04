package com.example.aquaflow.controller;

import com.example.aquaflow.common.Result;
import com.example.aquaflow.dto.BatchCreateDTO;
import com.example.aquaflow.dto.BatchFinishDTO;
import com.example.aquaflow.entity.Batch;
import com.example.aquaflow.service.BatchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.xml.crypto.Data;
import java.util.List;
import java.util.Map;

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
                                    @RequestParam(required = false) Data createTimeStart,
                                    @RequestParam(required = false) Data createTimeEnd){
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

    @DeleteMapping("/{id}")
    public Result delete (@PathVariable Integer id){
        batchService.delete(id);
        return Result.success();
    }
}
