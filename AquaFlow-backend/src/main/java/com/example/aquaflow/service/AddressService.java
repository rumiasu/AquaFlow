package com.example.aquaflow.service;

import com.example.aquaflow.entity.Address;

import java.util.List;
import java.util.Map;

public interface AddressService {
    void save(Address address);
    List<Address> list(Long customerId, String keyword);
    List<Address> listByStation(Long stationId, String keyword);
    Address getById(Long id);
    void update(Address address);
    void delete(Long customerId, Long addressId);
    void setDefault(Long customerId, Long addressId);
    List<Map<String, Object>> countByTag();
    List<Map<String, Object>> countByTagByStation(Long stationId);
}
