package com.example.aquaflow.dto;

import lombok.Data;

/**
 * 员工信息更新白名单 DTO。
 * <p>
 * 背景：此前 {@code PUT /api/staff/{id}} 直接接收一个完整的 Staff 实体并全量 update，
 * Mapper 的 SQL 会一并写入 role / station_id / password_hash。
 * 这意味着站长可以：把自己改成任意角色、把任意员工拉进自己水站、改写他人密码 —— 典型垂直提权。
 * </p>
 * 现在只允许改姓名、手机号、在职状态这三项；role / station_id / password_hash 一律不接受客户端传入。
 */
@Data
public class StaffUpdateDTO {

    /** 姓名 */
    private String name;

    /** 手机号 */
    private String phone;

    /** 在职状态：1 在职 0 离职 */
    private Integer status;
}
