package com.example.aquaflow.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 员工微信绑定码（v69）—— 见 {@code sql/migration_v69_staff_bind_code.sql} 的表注释与
 * {@code docs/design/16} §9.3 的决策记录。
 *
 * <p><b>「有效」的唯一判据是 {@code used_at IS NULL AND expires_at > #{now}}</b>，两处（本类的
 * {@link #findUsableStaffId}、{@link #markUsed}）必须写全这两个条件：
 * 少写 {@code used_at} 就变成"码可以重复用"（一次性就是它存在的全部意义）；
 * 少写 {@code expires_at} 就变成"码永不过期"（6 位数一旦长期有效，猜中只是时间问题）。</p>
 *
 * <p>⚠️ <b>[F-44 2026-09-30] 时效判据的"现在"必须由调用方传入，不许写 SQL 的 {@code NOW()}</b>：
 * 签发侧的 {@code expires_at} 是 <b>Java 时钟</b>算出来的（{@code StaffBindCodeService} 的
 * {@code BusinessTime.now() + 10min}），而校验侧原先读 <b>库的时钟</b> —— 两侧不同源，
 * 冻结 Java 时钟（或用 {@code app.time-zone} 与库时区不一致的部署）时会出现"签发明明还没到期、
 * 消费却说已过期"，反过来（拨快时钟）就是**过期码仍然能用**。现在两侧都取
 * {@code util/BusinessTime}，库只负责存。</p>
 *
 * <p>⚠️ 一员工同时只有一个未用码：{@link #deleteUnusedForStaff} 在生成前清旧的，
 * 所以"最新那个才是有效的"，查询里不需要再按时间排序。</p>
 */
@Mapper
public interface StaffBindCodeMapper {

    /** 签发一个码。 */
    @Insert("INSERT INTO staff_bind_code(staff_id, station_id, code, expires_at, created_by, create_time) " +
            "VALUES(#{staffId}, #{stationId}, #{code}, #{expiresAt}, #{createdBy}, NOW())")
    int insert(@Param("staffId") Long staffId,
               @Param("stationId") Long stationId,
               @Param("code") String code,
               @Param("expiresAt") LocalDateTime expiresAt,
               @Param("createdBy") Long createdBy);

    /**
     * 查这个码指向哪个员工；无效（已用 / 已过期 / 不存在）一律返回 {@code null}。
     *
     * <p>它同时被用作"生成时是否撞码"的探测（见 {@code StaffBindCodeService.generate}）——
     * 撞上已用/过期的码不算冲突，因为唯一键在 {@code code} 上、插入时才会真撞，
     * 所以生成侧还留了重试循环兜底。</p>
     *
     * @param now 业务"此刻"（{@code util/BusinessTime}），见类注释的 F-44 说明
     */
    @Select("SELECT staff_id FROM staff_bind_code " +
            "WHERE code = #{code} AND used_at IS NULL AND expires_at > #{now} " +
            "ORDER BY id DESC LIMIT 1")
    Long findUsableStaffId(@Param("code") String code, @Param("now") LocalDateTime now);

    /**
     * <b>消费</b>：把码标记成已用（CAS，看受影响行数）。
     *
     * <p>为什么必须是 UPDATE 而不是"先查再写"：免认证端点会被并发打 ——
     * 两个请求拿着同一个码同时进来，只有先到的那条 UPDATE 能拿到 1 行，
     * 另一条拿 0 行 ⇒ 只有一个人能绑上。查完再写会有窗口让两个人都通过。</p>
     *
     * @param now 业务"此刻"（{@code util/BusinessTime}）——既用于时效判据，也作为 {@code used_at}
     *            的取值，保证"什么时候用掉的"与"判断它没过期"出自同一个时钟
     */
    @Update("UPDATE staff_bind_code SET used_at = #{now}, used_openid = #{openid} " +
            "WHERE code = #{code} AND used_at IS NULL AND expires_at > #{now}")
    int markUsed(@Param("code") String code, @Param("openid") String openid, @Param("now") LocalDateTime now);

    /** 生成新码前清掉该员工旧的未用码（保证"一员工同时只有一个有效码"）。 */
    @Delete("DELETE FROM staff_bind_code WHERE staff_id = #{staffId} AND used_at IS NULL")
    int deleteUnusedForStaff(@Param("staffId") Long staffId);
}
