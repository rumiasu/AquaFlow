package com.example.aquaflow.service.impl;

import com.example.aquaflow.entity.Station;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.StationMapper;
import com.example.aquaflow.service.StationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class StationServiceImpl implements StationService {

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
}
