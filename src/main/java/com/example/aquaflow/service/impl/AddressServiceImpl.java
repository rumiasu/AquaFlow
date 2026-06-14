package com.example.aquaflow.service.impl;

import com.example.aquaflow.entity.Address;
import com.example.aquaflow.mapper.AddressMapper;
import com.example.aquaflow.service.AddressService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class AddressServiceImpl implements AddressService {

    @Autowired
    private AddressMapper addressMapper;

    @Override
    public void save(Address address) {
        address.setCreateTime(LocalDateTime.now());
        address.setUpdateTime(LocalDateTime.now());
        addressMapper.insert(address);
    }

    @Override
    public List<Address> list(String tag, String keyword) {
        return addressMapper.list(tag, keyword);
    }

    @Override
    public Address getById(Integer id) {
        Address address = addressMapper.getById(id);
        if(address == null){
            throw new RuntimeException("该地址无记录");
        }
        return address;
    }

    @Override
    public void update(Address address) {
        address.setUpdateTime(LocalDateTime.now());
        addressMapper.update(address);
    }
}
