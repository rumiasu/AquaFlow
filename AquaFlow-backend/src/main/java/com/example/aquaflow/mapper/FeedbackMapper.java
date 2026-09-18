package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.Feedback;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface FeedbackMapper {

    @Insert("insert into feedback(staff_id, customer_id, category, content, contact, anonymous, create_time) " +
            "values(#{staffId}, #{customerId}, #{category}, #{content}, #{contact}, #{anonymous}, #{createTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(Feedback feedback);

    @Select("select * from feedback where id = #{id}")
    Feedback getById(@Param("id") Long id);

    @Select("select * from feedback where customer_id = #{customerId} order by create_time desc")
    List<Feedback> listByCustomerId(@Param("customerId") Long customerId);

    @Select("select * from feedback where staff_id = #{staffId} order by create_time desc")
    List<Feedback> listByStaffId(@Param("staffId") Long staffId);

    /**
     * ⚠️ <b>当前零调用点，且它是一个「全平台 + 实名」的读</b>（{@code select *}，无站过滤、
     * 不做匿名脱敏）。留着就是给下一个接线的人准备的坑：谁把它接进任何一个站长可调的端点，
     * 立刻同时得到<b>跨站可见</b>与<b>匿名被穿透</b>两个缺陷，而且不报错、界面看起来正常。
     *
     * <p>要接线，先做两件事：① 加站过滤（口径同 {@link #listCustomerFeedbackByStation} 的并集）；
     * ② 复用同一套 {@code CASE ... THEN NULL} 脱敏，不要另写一份 SQL。</p>
     */
    @Select("select * from feedback order by create_time desc")
    List<Feedback> listAll();

    /**
     * 站长端「客户反馈」列表：落到本站的顾客反馈（按站过滤，杜绝跨站可见）。
     *
     * <p>[2026-09-18 修复] 两处都是接了页面才暴露出来的：</p>
     * <ol>
     *   <li><b>归属口径</b>：原 SQL 用 {@code inner join customer_station_config}（只看「绑定行」），
     *       而绑定行只在站长把客户纳入管辖时才产生 —— <b>只在小程序下过单、没有绑定行的老顾客，
     *       报错后提交的反馈不进站长的列表</b>：界面显示"暂无客户反馈"，库里那条记录却真实存在，
     *       两者无法区分（AGENTS §8.22 的形态）。归属口径必须与
     *       {@code CustomerMapper.countCustomerOfStation} 一致：绑定行 <b>或</b> 本站订单，取并集。</li>
     *   <li><b>客户是谁</b>：原 SQL 是 {@code select f.*}，从不 JOIN {@code customer}，
     *       于是 {@code Feedback.customerName} 恒为 null —— 站长看到的是"客户 #42"，
     *       认不出人，这条反馈等于没法处理。故补 {@code left join customer} 带出姓名。</li>
     * </ol>
     *
     * <p>[2026-09-18 匿名提交] <b>脱敏就在这里，不在 Java 里</b> —— 判据是
     * 「<b>站长不知道是谁</b>」，不是「前端不显示」。{@code anonymous = 1} 时
     * {@code customer_id} 与 JOIN 出来的姓名<b>都由 SQL 置为 NULL</b>。</p>
     *
     * <p><b>为什么钉在 SQL 层而不是在 Java 里把返回值 setCustomerId(null)</b>：</p>
     * <ul>
     *   <li>Java 里置空只保护**这一个调用点当前这一版代码**。下一个人要给列表加个字段、
     *       顺手把 {@code select f.*} 或 {@code c.name} 加回 select，脱敏立刻失效，
     *       <b>编译期不报错、运行期不报错、界面看起来一切正常</b>（本仓 §8.15/§8.17 同一类
     *       "静默成功"陷阱）。写在 SQL 里，泄露姓名就必须**主动删掉一个 CASE**，
     *       而这是一个显眼的、需要解释的动作。</li>
     *   <li>脱敏与"数据出库"绑在同一句 SQL 上，任何拿到这个 ResultSet 的人（将来新增的
     *       导出、统计、二次封装的 service）都不可能绕过 —— 它压根没读到那个字段。</li>
     *   <li>{@code Feedback} 是共享实体：Java 置空只能保护返回给站长的那个 list，
     *       实体一旦被别处复用（缓存、DTO 转换、日志），真名又会冒出来。</li>
     * </ul>
     *
     * <p>⚠️ <b>改本查询时唯一不能做的事：把 {@code f.customer_id} 或 {@code c.name}
     * 从 CASE 里挪出来</b>（包括"先 select 出来、在别处再判匿名"这种写法）。
     * 另外 {@code feedback.anonymous} <b>刻意不在 select 列表里</b>：站长端只需按
     * 「有没有姓名 / 客户号」判断，下发标志位只会诱使前端自造身份文案（本仓禁止前端自带
     * 文案映射）。因此响应里 {@code anonymous} 恒为 null 是<b>有意行为，不是漏了字段</b>。</p>
     *
     * <p>顾客自己的 {@code GET /api/feedback/my}（{@link #listByCustomerId}）<b>不受影响</b>：
     * 那是他自己的记录，{@code select *} 照常带出 customerId 与 anonymous。</p>
     */
    @Select("select f.id, f.staff_id, f.category, f.content, f.contact, f.create_time, "
            + "case when f.anonymous = 1 then null else f.customer_id end as customer_id, "
            + "case when f.anonymous = 1 then null else c.name end as customer_name "
            + "from feedback f "
            + "left join customer c on c.id = f.customer_id "
            + "where f.customer_id is not null and ("
            + "exists (select 1 from customer_station_config csc where csc.customer_id = f.customer_id and csc.station_id = #{stationId}) "
            + "or exists (select 1 from orders o where o.customer_id = f.customer_id and o.station_id = #{stationId})) "
            + "order by f.create_time desc")
    List<Feedback> listCustomerFeedbackByStation(@Param("stationId") Long stationId);

    // [2026-09-18 删除] listCustomerFeedback()：全平台顾客反馈（无站过滤），
    // 唯一调用点是 FeedbackController 里一个不可达的兜底分支（@RequireRole STATION_MANAGER 下
    // isManager() 恒真）。留着等于留一个"跨站读"模板，已随该分支一并删除。
}
