package com.example.aquaflow.service.impl;

import com.example.aquaflow.entity.Staff;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.StaffMapper;
import com.example.aquaflow.mapper.StationMapper;
import com.example.aquaflow.service.StaffService;
import com.example.aquaflow.util.AuthContext;
import com.example.aquaflow.util.CustomerProfileMask;
import com.example.aquaflow.vo.StaffProfileVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

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
        Long stationId = requireManagingStation();
        if (staff == null) {
            throw new BusinessException("请提供员工信息");
        }
        // 2026-10-08：原入口只拒绝大写站长名，manager 别名却能通过鉴权；先归一化再白名单校验。
        String role = canonicalRole(staff.getRole());
        if (!"DELIVERY".equals(role)) {
            throw new BusinessException("不可通过此接口创建站长账号");
        }
        Integer status = staff.getStatus() == null ? 1 : staff.getStatus();
        requireValidStatus(status);
        staff.setRole(role);
        staff.setStationId(stationId);
        // 小程序新增员工不传 status；显式补 1，避免 INSERT 写 NULL 后新员工在列表中消失。
        staff.setStatus(status);
        staff.setCreateTime(LocalDateTime.now());
        staff.setUpdateTime(LocalDateTime.now());
        staffMapper.insert(staff);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Staff staff) {
        Long stationId = requireManagingStation();
        if (staff == null) {
            throw new BusinessException("请提供员工信息");
        }
        Staff existing = requireOwnedForUpdate(staff.getId(), stationId);
        Integer status = staff.getStatus() == null ? existing.getStatus() : staff.getStatus();
        requireValidStatus(status);
        if (Integer.valueOf(2).equals(staff.getStatus())) {
            requireRemovableDelivery(existing);
        }
        String name = staff.getName() == null ? existing.getName() : staff.getName();
        String phone = staff.getPhone() == null ? existing.getPhone() : staff.getPhone();
        if (Objects.equals(name, existing.getName()) && Objects.equals(phone, existing.getPhone())
                && Objects.equals(status, existing.getStatus())) {
            return;
        }
        // 2026-10-08：旧服务整行写入会覆盖角色/归属/密码；锁内只改白名单，并检查 CAS 结果。
        if (staffMapper.updateBaseInfoIf(existing.getId(), stationId, existing.getRole(),
                existing.getStatus(), name, phone, status) != 1) {
            throw new BusinessException("员工信息已变化，请刷新重试");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Long stationId = requireManagingStation();
        Staff existing = requireOwnedForUpdate(id, stationId);
        requireRemovableDelivery(existing);
        if (staffMapper.deleteIf(id, stationId, existing.getRole(), existing.getStatus()) != 1) {
            throw new BusinessException("员工信息已变化，请刷新重试");
        }
    }

    /** 服务写入口也验证身份，不能依赖控制器注解拦住其它调用路径。 */
    private Long requireManagingStation() {
        AuthContext.requireStaffRole();
        AuthContext.requireManager();
        if (AuthContext.getUserId() == null) {
            throw new BusinessException("未登录");
        }
        return AuthContext.requireStationId();
    }

    /** 与绑定审批保持员工行优先的锁序；拿锁后用当前归属判权，不能用旧快照。 */
    private Staff requireOwnedForUpdate(Long id, Long stationId) {
        if (id == null) {
            throw new BusinessException("请指定员工");
        }
        Staff existing = staffMapper.getByIdForUpdate(id);
        if (existing == null) {
            throw new BusinessException("员工不存在");
        }
        if (!stationId.equals(existing.getStationId())) {
            throw new BusinessException("无权操作他站员工");
        }
        return existing;
    }

    /** 兼容鉴权支持的两个别名；写入时只落正式角色值，未知角色不得默认当配送员。 */
    private String canonicalRole(String role) {
        if ("DELIVERY".equals(role) || "delivery".equals(role)) {
            return "DELIVERY";
        }
        if ("STATION_MANAGER".equals(role) || "manager".equals(role)) {
            return "STATION_MANAGER";
        }
        throw new BusinessException("员工角色不合法");
    }

    private void requireValidStatus(Integer status) {
        if (status == null || (status != 1 && status != 2)) {
            throw new BusinessException("员工状态不合法，仅支持在职或离职");
        }
    }

    /**
     * 2026-10-08：原通用接口允许站长停用/删除自己，让水站失去管理入口。
     * 与绑定解除一致，只允许移除配送员；别改成先计数站长再写，否则并发互停可留下零站长。
     */
    private void requireRemovableDelivery(Staff staff) {
        if (Objects.equals(staff.getId(), AuthContext.getUserId())) {
            throw new BusinessException("不可停用或删除本人账号");
        }
        if (!"DELIVERY".equals(canonicalRole(staff.getRole()))) {
            throw new BusinessException("不可通过员工管理停用或删除站长账号");
        }
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
