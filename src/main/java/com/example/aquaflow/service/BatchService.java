package com.example.aquaflow.service;

import com.example.aquaflow.entity.Batch;

import javax.xml.crypto.Data;
import java.util.List;

public interface BatchService {
    Batch create(List<Integer> orderIds);

    List<Batch> list(Integer status, Data createTimeStart, Data createTimeEnd);

    Batch getById(Integer id);

    void start(Integer id);

    void finish(Integer id, List<Integer> finishedOrderIds, List<Integer> unfinishedOrderIds);

    void delete(Integer id);
}
