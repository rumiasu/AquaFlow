package com.example.aquaflow.service;

import com.example.aquaflow.entity.Station;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public interface StationService {

    List<Station> listAll();

    Station getById(Long id);

    void save(Station station);

    void update(Station station);

    void delete(Long id);

    /**
     * 找水站（配送员申请绑定前用，也可给任何"想找一家水站"的入口用）。
     *
     * <p><b>行为</b>：先滤掉"待上线"（{@code operating_status=3}）与"已停业"（{@code status=2}）——
     * 前者没转正、后者下单会被拒，让配送员申请它们只会白跑一趟审批；再按 {@code keyword}
     * 匹配名称/地址；最后若有坐标，按<b>直线距离</b>升序并给出 {@code distanceKm}。</p>
     *
     * <p><b>⚠️ 两条不许改的判据</b>：</p>
     * <ol>
     *   <li><b>没坐标的站不丢弃</b>：{@code station.lat/lng} 可为 NULL，此时距离算不出来
     *       （{@link com.example.aquaflow.util.GeoUtil#distanceMeters} 返回 null）。
     *       这类站排在带距离的站之后照常返回，{@code distanceKm=null} ——
     *       把"没有数据"当成"超出范围"会让一家真实存在的站凭空消失。</li>
     *   <li><b>不传坐标 = 老行为</b>：定位被拒/未授权/老客户端仍要能用，
     *       位置只影响<b>排序</b>，从不做"按半径裁掉"的硬过滤。</li>
     * </ol>
     *
     * <p>返回 Map 而不是实体：实体带 {@code phone} 等字段，而调用方（申请绑定页）
     * 在绑定前并不需要站长电话；顺带把 {@code distanceKm} 放进来。</p>
     *
     * @param keyword 名称/地址关键词，空则不筛
     * @param lat     手机定位纬度，可空
     * @param lng     手机定位经度，可空
     */
    List<Map<String, Object>> searchForBinding(String keyword, BigDecimal lat, BigDecimal lng);
}
