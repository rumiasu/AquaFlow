package com.example.aquaflow.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 桶损耗统计（2026-09-18）：只读，按 {@code barrel_record.type} 的 3 丢失 / 4 损坏 汇总。
 *
 * <p><b>为什么要有它</b>：{@code BarrelRecordType} 的 3/4 早已声明、也早已在守恒对账 E5 的
 * UNION 里（记负），但<b>全仓 0 处写入</b> —— 是"语义位已有、缺写入口"的休眠类型。
 * 后果很具体：对账 E5 报不平时，分不清"是账写错了"还是"桶真的坏了、丢了"，
 * 也算不出"今年损耗了几个桶"。</p>
 *
 * <p>⚠️ <b>本类只读、且写入口已决定不做</b>（2026-09-18，见 {@code docs/design/20} §3.5）：
 * 桶是跟水厂换的，破损与丢失由水厂承担，不属于水站的账；客户弄丢的桶记在
 * {@code customer_barrel_over}（欠桶台账）。<b>因此在有人写入 3/4 之前，这里的数字恒为 0</b>
 * —— 这不是缺陷，是本业务模型下的预期。</p>
 *
 * <p>⚠️ 站点一律由调用方从 {@code AuthContext} 传入 —— 损耗是本站的经营数据，
 * 跨站可见等于把库存底细漏给同行。</p>
 */
@Mapper
public interface BarrelLossMapper {

    /**
     * 期间内按商品汇总的损耗数（丢失 / 损坏分开）。
     *
     * <p>⚠️ 时间上界用 {@code < endExclusive}（调用方传「结束日 + 1 天」）——
     * 写成 {@code <= 结束日} 会把当天整整漏掉（AGENTS §8.19）。</p>
     *
     * <p>{@code quantity} 存的是绝对值，方向由 {@code type} 决定
     * （见 {@code BarrelRecordType} 的类注释），所以这里直接求和、不做符号处理。</p>
     */
    @Select("select r.product_id as productId, max(p.name) as productName, "
            + "coalesce(sum(case when r.type = 3 then r.quantity else 0 end), 0) as lostQty, "
            + "coalesce(sum(case when r.type = 4 then r.quantity else 0 end), 0) as damagedQty, "
            + "coalesce(sum(r.quantity), 0) as totalQty "
            + "from barrel_record r left join product p on p.id = r.product_id "
            + "where r.station_id = #{stationId} and r.type in (3, 4) "
            + "  and r.create_time >= #{start} and r.create_time < #{endExclusive} "
            + "group by r.product_id "
            + "order by totalQty desc, r.product_id asc")
    List<Map<String, Object>> lossByProduct(@Param("stationId") Long stationId,
                                            @Param("start") java.time.LocalDateTime start,
                                            @Param("endExclusive") java.time.LocalDateTime endExclusive);
}
