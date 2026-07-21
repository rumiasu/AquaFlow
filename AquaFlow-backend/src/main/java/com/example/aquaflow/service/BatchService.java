package com.example.aquaflow.service;

import com.example.aquaflow.entity.Batch;

import java.util.List;

public interface BatchService {
    Batch create(List<Integer> orderIds);

    List<Batch> list(Integer status, String createTimeStart, String createTimeEnd);

    Batch getById(Integer id);

    void start(Integer id);

    void finish(Integer id, List<Integer> finishedOrderIds, List<Integer> unfinishedOrderIds);

    void finishAll(Integer id);

    void delete(Integer id);
}
