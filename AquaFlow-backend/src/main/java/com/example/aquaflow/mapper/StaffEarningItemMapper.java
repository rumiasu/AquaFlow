package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.StaffEarningItem;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 自定义工资条目（v44）。
 *
 * <p>⚠️ <b>所有按 id 的写操作都带 {@code station_id} 条件并返回受影响行数</b>：
 * id 是客户端可编造的，不验归属就会出现"改掉/删掉别站的条目"（AGENTS §8.20 那批事故的同一形状）。
 * 调用方必须检查返回值，0 行就报错，不许无条件返回成功。</p>
 */
@Mapper
public interface StaffEarningItemMapper {

    /** 本站全部条目（含停用；停用的排在后面由前端置灰显示） */
    @Select("select * from staff_earning_item where station_id = #{stationId} order by status desc, sort asc, id asc")
    List<StaffEarningItem> listByStation(@Param("stationId") Long stationId);

    /** 本站**启用**的条目（录一笔时只能选这些） */
    @Select("select * from staff_earning_item where station_id = #{stationId} and status = 1 "
            + "order by sort asc, id asc")
    List<StaffEarningItem> listEnabled(@Param("stationId") Long stationId);

    @Select("select * from staff_earning_item where id = #{id}")
    StaffEarningItem getById(@Param("id") Long id);

    /** 按名字查（重名判定的唯一依据是唯一键，这里只是为了给出可读文案） */
    @Select("select * from staff_earning_item where station_id = #{stationId} and name = #{name}")
    StaffEarningItem getByName(@Param("stationId") Long stationId, @Param("name") String name);

    @Insert("insert into staff_earning_item(station_id, name, direction, status, sort, create_time) "
            + "values(#{stationId}, #{name}, #{direction}, #{status}, #{sort}, NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(StaffEarningItem item);

    /** 改名 / 改方向 / 改顺序（改名不改写历史流水，历史看 item_name 快照） */
    @Update("update staff_earning_item set name = #{name}, direction = #{direction}, sort = #{sort} "
            + "where id = #{id} and station_id = #{stationId}")
    int update(StaffEarningItem item);

    /**
     * 启用 / 停用（1 / 0）—— <b>幂等的绝对置值，不是状态机流转</b>，所以这里没有也不该有
     * {@code status = #{expectedStatus}} 这个条件。
     *
     * <p>⚠️ [F-32] 不加 CAS 是有意的：客户端只提交目标状态（{@code PayrollDTO.EarningItemStatus}），
     * 把"当前值"当期望态写进 SQL 会把「重复点启用 / 重复点停用」这种合法幂等操作判成 0 行错误 ——
     * 那是改业务口径，不是补守卫（该提醒不要顺手把它"CAS 化"）。</p>
     *
     * <p>真正要防的两件事各有一道：<b>归属</b>（{@code station_id} 与 id 写在同一条 SQL 里，
     * id 是客户端可编造的也改不到别站的条目）+ <b>静默成功</b>（调用方
     * {@code StaffEarningItemServiceImpl.setStatus} 检查受影响行数，0 行即抛，AGENTS.md §8.20）。</p>
     */
    @Update("update staff_earning_item set status = #{status} where id = #{id} and station_id = #{stationId}")
    int updateStatus(@Param("id") Long id, @Param("stationId") Long stationId, @Param("status") Integer status);

    @Delete("delete from staff_earning_item where id = #{id} and station_id = #{stationId}")
    int delete(@Param("id") Long id, @Param("stationId") Long stationId);

    /**
     * 该条目被多少条收益流水引用。
     *
     * <p>{@code > 0} 时<b>只允许停用、不允许删</b>：条目是历史工资的标签，
     * 删掉它那些流水在界面上就变成"无条目的人工调整"，站长再也说不清那笔钱是什么。</p>
     */
    @Select("select count(*) from staff_earning where station_id = #{stationId} and item_id = #{itemId}")
    int countUsage(@Param("stationId") Long stationId, @Param("itemId") Long itemId);
}
