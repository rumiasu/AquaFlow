package com.example.aquaflow.service.impl;

import com.example.aquaflow.entity.Station;
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
    public Station getById(Integer id) {
        Station station = stationMapper.getById(id);
        if (station == null) {
            throw new RuntimeException("水站不存在");
        }
        return station;
    }

    @Override
    public void save(Station station) {
        station.setCreateTime(LocalDateTime.now());
        station.setUpdateTime(LocalDateTime.now());
        stationMapper.insert(station);
    }

    @Override
    public void update(Station station) {
        station.setUpdateTime(LocalDateTime.now());
        stationMapper.update(station);
    }

    @Override
    public void delete(Integer id) {
        stationMapper.delete(id);
    }
}
