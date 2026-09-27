package com.example.aquaflow.service.impl;

import com.example.aquaflow.constant.StationOperatingStatus;
import com.example.aquaflow.entity.Station;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.StationMapper;
import com.example.aquaflow.service.StationService;
import com.example.aquaflow.util.GeoUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class StationServiceImpl implements StationService {

    /**
     * 找水站单次返回上限（与改造前的 {@code limit(50)} 一致，别放宽）：
     * 它是"给人挑的候选列表"，不是分页接口。
     */
    private static final int MAX_SEARCH_RESULTS = 50;

    @Autowired
    private StationMapper stationMapper;

    @Override
    public List<Station> listAll() {
        return stationMapper.listAll();
    }

    @Override
    public Station getById(Long id) {
        Station station = stationMapper.getById(id);
        if (station == null) {
            throw new BusinessException("水站不存在");
        }
        return station;
    }

    @Override
    public void save(Station station) {
        requireContactFields(station);
        station.setCreateTime(LocalDateTime.now());
        station.setUpdateTime(LocalDateTime.now());
        stationMapper.insert(station);
    }

    /**
     * 建站必填校验（**唯一实现**）：名称 + 联系电话。
     *
     * <p>[2026-09-19 产品裁定]「电话也加到建立水站时的必填项吧」—— 依据：`station.phone` 可空，
     * 而真实库已有水站的电话是空串，客户在下单页看到的是「水站电话：请咨询客服」，
     * 退桶/催单时找不到人。</p>
     *
     * <p>⚠️ 建站有两条路径（{@code StationController.POST /api/stations} 与
     * {@code LoginController.createStationAndBind} 的首次建站），两条都必须调本方法 ——
     * 只堵一条等于没堵（第二条是站长实际走的那条）。</p>
     *
     * <p>坐标**不在这里强制**：站长可能当场拿不到定位（用户拒授权/室内无信号），
     * 强制会把建站卡死。缺坐标由「信息完善引导」以 P0 提示（见 {@code StationSetupGuideService}）。</p>
     */
    public static void requireContactFields(Station station) {
        if (station == null || station.getName() == null || station.getName().trim().isEmpty()) {
            throw new BusinessException("请填写水站名称");
        }
        if (station.getPhone() == null || station.getPhone().trim().isEmpty()) {
            throw new BusinessException("请填写水站联系电话：客户下单、退桶、催单都靠它联系你");
        }
        station.setName(station.getName().trim());
        station.setPhone(station.getPhone().trim());
    }

    @Override
    public void update(Station station) {
        station.setUpdateTime(LocalDateTime.now());
        stationMapper.update(station);
    }

    @Override
    public void delete(Long id) {
        stationMapper.delete(id);
    }

    /**
     * 找水站：滤掉不该被申请的站 → 关键词筛选 → 有坐标就按距离排序。
     *
     * <p>判据与"为什么这么写"见 {@link StationService#searchForBinding}（接口 javadoc 是正本）。
     * 这里只强调两处实现细节：</p>
     * <ol>
     *   <li>距离用 {@link GeoUtil#distanceMeters} —— 全仓唯一实现，返回 {@code null} 表示
     *       "算不出来"（站点没选点）。排序时 null 一律排最后，**不丢弃**。</li>
     *   <li>先排序再 limit：不这样会出现"最近的站在第 80 条、被截断掉了"。</li>
     * </ol>
     */
    @Override
    public List<Map<String, Object>> searchForBinding(String keyword, BigDecimal lat, BigDecimal lng) {
        List<Station> all = stationMapper.listAll();

        String kw = keyword == null ? "" : keyword.trim();
        List<Station> candidates = all.stream()
                // 待上线（没转正）不该被配送员申请：见 /search 的注释与 StationOperatingStatus
                .filter(s -> !StationOperatingStatus.isPendingLaunch(s.getOperatingStatus()))
                // 已停业（status=2）也不该被申请：顾客端 /public 同样不列它，申请了只会被拒
                .filter(s -> s.getStatus() == null || s.getStatus() != 2)
                .filter(s -> kw.isEmpty()
                        || (s.getName() != null && s.getName().contains(kw))
                        || (s.getAddress() != null && s.getAddress().contains(kw))
                        // ⚠️ 电话匹配是改造前就有的能力，**留着**：它不是信息泄露
                        //（号码是调用方自己输进来的），删掉只会让"拿着站长给的电话找站"失效。
                        || (s.getPhone() != null && s.getPhone().contains(kw)))
                .collect(Collectors.toList());

        boolean hasLocation = lat != null && lng != null;
        // 有坐标时按距离升序（算不出距离的排最后），没坐标时保持库里的顺序 = 老行为
        if (hasLocation) {
            candidates.sort((a, b) -> {
                Double da = GeoUtil.distanceMeters(lat, lng, a.getLat(), a.getLng());
                Double db = GeoUtil.distanceMeters(lat, lng, b.getLat(), b.getLng());
                if (da == null && db == null) return 0;
                if (da == null) return 1;      // 没坐标的排后面，但**照样返回**
                if (db == null) return -1;
                return Double.compare(da, db);
            });
        }

        return candidates.stream()
                .limit(MAX_SEARCH_RESULTS)
                .map(s -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", s.getId());
                    m.put("name", s.getName());
                    m.put("address", s.getAddress());
                    m.put("lat", s.getLat());
                    m.put("lng", s.getLng());
                    Double meters = hasLocation
                            ? GeoUtil.distanceMeters(lat, lng, s.getLat(), s.getLng()) : null;
                    // 保留一位小数：给顾客/配送员看的是"离我多远"，不是米级精度
                    m.put("distanceKm", meters == null ? null
                            : BigDecimal.valueOf(Math.round(meters / 100d) / 10d));
                    return m;
                })
                .collect(Collectors.toList());
    }
}
