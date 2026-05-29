package com.example.aquaflow.service;

import com.example.aquaflow.entity.Address;

import java.util.List;

public interface AddressService {
    void save(Address address);
    List<Address> list(String tag, String keyword);
    Address getById(Integer id);
    void update(Address address);
}
