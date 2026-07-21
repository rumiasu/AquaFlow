package com.example.aquaflow.service;

import com.example.aquaflow.entity.Address;

import java.util.List;
import java.util.Map;

public interface AddressService {
    void save(Address address);
    List<Address> list(Integer customerId, String tag, String keyword);
    Address getById(Integer id);
    void update(Address address);
    void delete(Integer customerId, Integer addressId);
    void setDefault(Integer customerId, Integer addressId);
    List<Map<String, Object>> countByTag();
}
