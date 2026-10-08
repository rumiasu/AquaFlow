package com.example.aquaflow.service;

import com.example.aquaflow.entity.Staff;
import com.example.aquaflow.vo.StaffProfileVO;

import java.util.List;

public interface StaffService {

    List<Staff> listAll();

    Staff getById(Long id);

    /** 员工管理创建入口；使用本站站长登录态校验，不能用于身份注册流程。 */
    void save(Staff staff);

    /** 员工管理白名单更新；仅采纳 id/name/phone/status，归属与角色不能通过此方法改写。 */
    void update(Staff staff);

    /** 员工管理删除入口；保护站长与本人，历史收益及结算单保留。 */
    void delete(Long id);

    List<Staff> listByStationId(Long stationId);

    List<Staff> listByStationIdAndRole(Long stationId, String role);

    /**
     * 员工画像（站长视角）：聚合配送业绩（今日/本月/累计、进行中、完成率）与服务质量。
     * 员工不存在返回 null。
     */
    StaffProfileVO getStaffProfile(Long staffId, Long stationId);
}
