package com.example.aquaflow.service.impl;

import com.example.aquaflow.entity.Staff;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.StaffMapper;
import com.example.aquaflow.mapper.StationMapper;
import com.example.aquaflow.service.StaffService;
import com.example.aquaflow.util.CustomerProfileMask;
import com.example.aquaflow.vo.StaffProfileVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class StaffServiceImpl implements StaffService {

    @Autowired
    private StaffMapper staffMapper;

    @Autowired
    private StationMapper stationMapper;

    @Override
    public List<Staff> listAll() {
        return staffMapper.listAll();
    }

    @Override
    public Staff getById(Long id) {
        Staff staff = staffMapper.getById(id);
        if (staff == null) {
            throw new BusinessException("员工不存在");
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
    public void delete(Long id) {
        staffMapper.delete(id);
    }

    @Override
    public List<Staff> listByStationId(Long stationId) {
        return staffMapper.listByStationId(stationId);
    }

    @Override
    public List<Staff> listByStationIdAndRole(Long stationId, String role) {
        return staffMapper.listByStationIdAndRole(stationId, role);
    }

    @Override
    public StaffProfileVO getStaffProfile(Long staffId, Long stationId) {
        Staff staff = staffMapper.getById(staffId);
        if (staff == null) {
            return null;
        }

        StaffProfileVO vo = new StaffProfileVO();
        // 基础档案
        vo.setId(staff.getId());
        vo.setName(staff.getName());
        vo.setPhone(staff.getPhone());
        vo.setRole(staff.getRole());
        vo.setStationId(staff.getStationId());
        vo.setStatus(staff.getStatus());
        vo.setCreateTime(staff.getCreateTime());
        if (staff.getStationId() != null && stationMapper != null) {
            com.example.aquaflow.entity.Station st = stationMapper.getById(staff.getStationId());
            if (st != null) {
                vo.setStationName(st.getName());
            }
        }

        // 业绩
        vo.setTodayOrders(staffMapper.countTodayCompleted(staffId));
        vo.setMonthOrders(staffMapper.countMonthCompleted(staffId));
        vo.setTotalOrders(staffMapper.countTotalCompleted(staffId));
        vo.setDeliveringOrders(staffMapper.countDelivering(staffId));
        vo.setCancelledOrders(staffMapper.countCancelled(staffId));
        vo.setTotalAmount(staffMapper.sumTotalAmount(staffId));
        vo.setMonthAmount(staffMapper.sumMonthAmount(staffId));

        // 服务质量
        vo.setReturnCount(staffMapper.countReturns(staffId));
        vo.setExceptionCount(staffMapper.countExceptions(staffId));
        vo.setCurrentOrders(decorateOrders(staffMapper.listCurrentOrders(staffId)));
        return vo;
    }

    /** 给进行中订单补后端派生的状态文案 */
    private java.util.List<java.util.Map<String, Object>> decorateOrders(
            java.util.List<java.util.Map<String, Object>> orders) {
        if (orders == null) {
            return new java.util.ArrayList<>();
        }
        for (java.util.Map<String, Object> m : orders) {
            Object st = m.get("status");
            if (st instanceof Number) {
                m.put("statusText", com.example.aquaflow.constant.OrderStatus.textOf(((Number) st).intValue()));
            }
            // 跨站外派给本站配送员的单：客户档案算归属站的，这里只留订单快照
            // （receiverName / 地址 / 金额）。口径见 util/CustomerProfileMask；
            // SQL 必须 as 出 stationId / deliveryStationId 两列，否则这里静默不抹。
            CustomerProfileMask.maskIfCrossStation(m, "customerName");
        }
        return orders;
    }
}
