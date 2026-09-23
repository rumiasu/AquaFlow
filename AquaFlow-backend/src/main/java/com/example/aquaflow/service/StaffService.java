package com.example.aquaflow.service;

import com.example.aquaflow.entity.Staff;
import com.example.aquaflow.vo.StaffProfileVO;

import java.util.List;

public interface StaffService {

    List<Staff> listAll();

    Staff getById(Long id);

    void save(Staff staff);

    void update(Staff staff);

    void delete(Long id);

    List<Staff> listByStationId(Long stationId);

    List<Staff> listByStationIdAndRole(Long stationId, String role);

    /**
     * 员工画像（站长视角）：聚合配送业绩（今日/本月/累计、进行中、完成率）与服务质量。
     * 员工不存在返回 null。
     */
    StaffProfileVO getStaffProfile(Long staffId, Long stationId);
}
