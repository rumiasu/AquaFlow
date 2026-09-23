package com.example.aquaflow.service.impl;

import com.example.aquaflow.entity.OrderImage;
import com.example.aquaflow.mapper.OrderImageMapper;
import com.example.aquaflow.service.OrderImageService;
import com.example.aquaflow.util.CosUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class OrderImageServiceImpl implements OrderImageService {

    @Autowired
    private OrderImageMapper orderImageMapper;

    @Autowired
    private CosUtil cosUtil;

    @Override
    public String uploadImage(Long orderId, Integer type, byte[] bytes, String originalFilename) {
        String extension = originalFilename.substring(originalFilename.lastIndexOf("."));
        String dir = "private/order/" + orderId;
        String objectName = cosUtil.uploadPrivate(bytes, dir, extension);

        OrderImage orderImage = new OrderImage();
        orderImage.setOrderId(orderId.intValue());
        orderImage.setObjectName(objectName);
        orderImage.setType(type);
        orderImage.setCreateTime(LocalDateTime.now());
        orderImageMapper.insert(orderImage);

        return objectName;
    }

    @Override
    public List<OrderImage> getByOrderId(Long orderId) {
        return orderImageMapper.listByOrderId(orderId);
    }
}
