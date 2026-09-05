package com.example.aquaflow.service.impl;

import com.example.aquaflow.entity.Address;
import com.example.aquaflow.mapper.AddressMapper;
import com.example.aquaflow.service.AddressService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class AddressServiceImpl implements AddressService {

    @Autowired
    private AddressMapper addressMapper;

    @Override
    @Transactional
    public void save(Address address) {
        address.setCreateTime(LocalDateTime.now());
        address.setUpdateTime(LocalDateTime.now());
        if (address.getIsDefault() != null && Integer.valueOf(1).equals(address.getIsDefault())) {
            addressMapper.clearDefault(address.getCustomerId());
        }
        addressMapper.insert(address);
    }

    @Override
    public List<Address> list(Long customerId, String keyword) {
        return addressMapper.list(customerId, keyword);
    }

    @Override
    public List<Address> listByStation(Long stationId, String keyword) {
        return addressMapper.listByStation(stationId, keyword);
    }

    @Override
    public Address getById(Long id) {
        Address address = addressMapper.getById(id);
        if (address == null) {
            throw new com.example.aquaflow.exception.ResourceNotFoundException("地址", id);
        }
        return address;
    }

    @Override
    @Transactional
    public void update(Address address) {
        address.setUpdateTime(LocalDateTime.now());
        if (address.getIsDefault() != null && Integer.valueOf(1).equals(address.getIsDefault())) {
            addressMapper.clearDefault(address.getCustomerId());
            addressMapper.setDefault(address.getId());
            address.setIsDefault(null);
        }
        addressMapper.update(address);
    }

    @Override
    public void delete(Long customerId, Long addressId) {
        addressMapper.delete(addressId, customerId);
    }

    @Override
    @Transactional
    public void setDefault(Long customerId, Long addressId) {
        addressMapper.clearDefault(customerId);
        addressMapper.setDefault(addressId);
    }

    @Override
    public List<Map<String, Object>> countByTag() {
        return addressMapper.countByTag();
    }

    @Override
    public List<Map<String, Object>> countByTagByStation(Long stationId) {
        return addressMapper.countByTagByStation(stationId);
    }
}
