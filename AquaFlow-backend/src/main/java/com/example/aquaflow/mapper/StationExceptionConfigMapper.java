package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.StationExceptionConfig;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface StationExceptionConfigMapper {

    /** 未配置过的站点返回 {@code null}，由调用方回落到默认配置 */
    @Select("select * from station_exception_config where station_id = #{stationId}")
    StationExceptionConfig getByStationId(@Param("stationId") Long stationId);

    /**
     * 一站一行，插入或覆盖。
     *
     * <p><b>为什么必须是 upsert</b>：不能写成「先 select，没有就 insert，有就 update」——
     * 两个请求同时改同一站配置时，双方都会判定「行不存在」而双双走 insert，第二条撞主键 1062，
     * 站长看到的是"保存失败"而他的修改其实已经生效过一半。
     * {@code on duplicate key update} 把这件事交给数据库的唯一键，天然原子。</p>
     *
     * <p><b>json 列的类型陷阱</b>：参数是普通字符串，MySQL 会隐式转成 JSON。
     * 传进来的字符串不合法（例如手工拼出来的截断 JSON）会直接报
     * {@code 3140 Invalid JSON text}，且报错点在 SQL 层、堆栈里看不出是哪个键——
     * 所以调用方必须先经过 {@code ObjectMapper} 序列化，不要手拼 JSON。</p>
     */
    @Insert("insert into station_exception_config(station_id, compensation_priority, auto_suggest_rules, notify_templates) "
            + "values(#{stationId}, #{compensationPriority}, #{autoSuggestRules}, #{notifyTemplates}) "
            + "on duplicate key update compensation_priority = values(compensation_priority), "
            + "auto_suggest_rules = values(auto_suggest_rules), "
            + "notify_templates = values(notify_templates)")
    int upsert(StationExceptionConfig config);
}
