package com.example.aquaflow.service;

import com.example.aquaflow.entity.Staff;

import java.util.List;

public interface StaffService {

    List<Staff> listAll();

    Staff getById(Integer id);

    void save(Staff staff);

    void update(Staff staff);

    void delete(Integer id);

    List<Staff> listByStationId(Integer stationId);
}
