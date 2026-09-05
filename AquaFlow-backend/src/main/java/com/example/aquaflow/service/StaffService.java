package com.example.aquaflow.service;

import com.example.aquaflow.entity.Staff;

import java.util.List;

public interface StaffService {

    List<Staff> listAll();

    Staff getById(Long id);

    void save(Staff staff);

    void update(Staff staff);

    void delete(Long id);

    List<Staff> listByStationId(Long stationId);

    List<Staff> listByStationIdAndRole(Long stationId, String role);
}
