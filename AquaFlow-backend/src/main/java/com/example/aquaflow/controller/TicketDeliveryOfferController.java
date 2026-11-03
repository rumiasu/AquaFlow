package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.service.TicketDeliveryBenefitService;
import com.example.aquaflow.util.AuthContext;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/manager/ticket-delivery-offers")
@RequireRole({"STATION_MANAGER"})
public class TicketDeliveryOfferController {
    public record OfferInput(String kind,Long tierKey,Long productId,Boolean enabled) {}
    private final TicketDeliveryBenefitService service;
    public TicketDeliveryOfferController(TicketDeliveryBenefitService service){this.service=service;}
    @GetMapping public Result<List<Map<String,Object>>> list(){return Result.success(service.catalog(AuthContext.requireStationId()));}
    @PutMapping public Result<Void> configure(@RequestBody OfferInput body){
        service.configure(AuthContext.requireStationId(),body.kind(),body.tierKey(),body.productId(),body.enabled());return Result.success();
    }
}
