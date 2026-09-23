package com.example.aquaflow.service;

import com.example.aquaflow.entity.OrderImage;

import java.util.List;

public interface OrderImageService {
    String uploadImage(Long orderId, Integer type, byte[] bytes, String originalFilename);
    List<OrderImage> getByOrderId(Long orderId);
}
