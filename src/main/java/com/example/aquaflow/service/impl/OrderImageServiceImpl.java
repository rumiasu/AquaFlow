package com.example.aquaflow.service.impl;

import com.example.aquaflow.mapper.OrderImageMapper;
import com.example.aquaflow.service.OrderImageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class OrderImageServiceImpl implements OrderImageService {

    @Autowired
    private OrderImageMapper orderImageMapper;
}
