package com.example.aquaflow.service.impl;

import com.example.aquaflow.entity.Staff;
import com.example.aquaflow.mapper.StaffMapper;
import com.example.aquaflow.service.StaffService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class StaffServiceImpl implements StaffService {

    @Autowired
    private StaffMapper staffMapper;

    @Override
    public List<Staff> listAll() {
        return staffMapper.listAll();
    }

    @Override
    public Staff getById(Integer id) {
        Staff staff = staffMapper.getById(id);
        if (staff == null) {
            throw new RuntimeException("员工不存在");
        }
        return staff;
    }

    @Override
    public void save(Staff staff) {
        staff.setCreateTime(LocalDateTime.now());
        staff.setUpdateTime(LocalDateTime.now());
        staffMapper.insert(staff);
    }

    @Override
    public void update(Staff staff) {
        staff.setUpdateTime(LocalDateTime.now());
        staffMapper.update(staff);
    }

    @Override
    public void delete(Integer id) {
        staffMapper.delete(id);
    }

    @Override
    public List<Staff> listByStationId(Integer stationId) {
        return staffMapper.listByStationId(stationId);
    }
}
