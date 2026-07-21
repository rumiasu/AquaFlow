package com.example.aquaflow.service;

import com.example.aquaflow.entity.Station;

import java.util.List;

public interface StationService {

    List<Station> listAll();

    Station getById(Integer id);

    void save(Station station);

    void update(Station station);

    void delete(Integer id);
}
