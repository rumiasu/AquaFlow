package com.example.aquaflow.service.impl;

import com.example.aquaflow.entity.Address;
import com.example.aquaflow.mapper.AddressMapper;
import com.example.aquaflow.service.AddressService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class AddressServiceImpl implements AddressService {

    @Autowired
    private AddressMapper addressMapper;

    @Override
    public void save(Address address) {
        address.setCreateTime(LocalDateTime.now());
        address.setUpdateTime(LocalDateTime.now());
        if (address.getIsDefault() != null && address.getIsDefault() == 1) {
            addressMapper.clearDefault(address.getCustomerId());
        }
        addressMapper.insert(address);
    }

    @Override
    public List<Address> list(Integer customerId, String tag, String keyword) {
        return addressMapper.list(customerId, tag, keyword);
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
        if (address.getIsDefault() != null && address.getIsDefault() == 1) {
            addressMapper.clearDefault(address.getCustomerId());
            addressMapper.setDefault(address.getId());
            address.setIsDefault(null);
        }
        addressMapper.update(address);
    }

    @Override
    public void delete(Integer customerId, Integer addressId) {
        addressMapper.delete(addressId, customerId);
    }

    @Override
    public void setDefault(Integer customerId, Integer addressId) {
        addressMapper.clearDefault(customerId);
        addressMapper.setDefault(addressId);
    }

    @Override
    public List<Map<String, Object>> countByTag() {
        return addressMapper.countByTag();
    }
}
