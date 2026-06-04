package com.example.aquaflow.controller;

import com.example.aquaflow.common.Result;
import com.example.aquaflow.service.OrderImageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/order-images/")
public class OrderImageController {

    @Autowired
    private OrderImageService orderImageService;

}
