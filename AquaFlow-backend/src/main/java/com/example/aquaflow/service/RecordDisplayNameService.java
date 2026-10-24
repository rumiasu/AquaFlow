package com.example.aquaflow.service;

import com.example.aquaflow.dto.RecordDisplayNameRow;
import com.example.aquaflow.entity.BarrelRecord;
import com.example.aquaflow.mapper.InterStationSettlementMapper;
import com.example.aquaflow.mapper.RecordDisplayNameMapper;
import com.example.aquaflow.service.OrderBarrelExceptionService.OrderBarrelExceptionDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 仅补已经过业务准入的记录名称；查询有站别和本批ID双重限制，不枚举客户或逐条查名。 */
@Service
@RequiredArgsConstructor
public class RecordDisplayNameService {
    private final RecordDisplayNameMapper mapper;
    private final InterStationSettlementMapper stationMapper;

    public void exceptions(Long station, List<OrderBarrelExceptionDTO> records) {
        if (!positive(station)) return;
        List<Long> ids = records.stream().filter(r -> Objects.equals(station, r.getStationId()))
                .map(OrderBarrelExceptionDTO::getId).filter(RecordDisplayNameService::positive).distinct().toList();
        if (ids.isEmpty()) return;
        Map<Long, RecordDisplayNameRow> names = index(mapper.exceptionNames(station, ids));
        for (var row : records) {
            var name = Objects.equals(station, row.getStationId()) ? names.get(row.getId()) : null;
            if (name != null) {
                row.setCustomerName(name.getCustomerName());
                row.setReceiverName(name.getReceiverName());
            }
        }
    }

    public void barrels(Long station, List<BarrelRecord> records) {
        if (!positive(station)) return;
        List<Long> ids = records.stream().filter(r -> Objects.equals(station, r.getStationId()) && Integer.valueOf(2).equals(r.getType()))
                .map(BarrelRecord::getId).filter(RecordDisplayNameService::positive).distinct().toList();
        if (ids.isEmpty()) return;
        Map<Long, RecordDisplayNameRow> names = index(mapper.barrelNames(station, ids));
        for (var row : records) {
            var name = Objects.equals(station, row.getStationId()) && Integer.valueOf(2).equals(row.getType()) ? names.get(row.getId()) : null;
            if (name != null) {
                row.setCustomerName(name.getCustomerName());
                row.setProductName(name.getProductName());
                row.setProductSpec(name.getProductSpec());
                row.setStationName(name.getStationName());
            }
        }
    }

    /** 调用方只能传入本次已授权响应里的站ID；复用既有的按ID批量站名查询。 */
    public void stations(List<Map<String, Object>> records, String... roles) {
        Set<Long> ids = new LinkedHashSet<>();
        for (var row : records) for (String role : roles) {
            Long id = id(row.get(role + "Id"));
            if (positive(id)) ids.add(id);
        }
        if (ids.isEmpty()) return;
        Map<Long, String> names = new HashMap<>();
        for (var row : stationMapper.stationNames(new ArrayList<>(ids))) {
            Long id = id(row.get("id"));
            if (ids.contains(id) && row.get("name") instanceof String name && !name.isBlank()) names.put(id, name);
        }
        for (var row : records) for (String role : roles) row.put(role + "Name", names.get(id(row.get(role + "Id"))));
    }

    private static Map<Long, RecordDisplayNameRow> index(List<RecordDisplayNameRow> rows) {
        return rows.stream().collect(Collectors.toMap(RecordDisplayNameRow::getId, Function.identity(), (a, b) -> a));
    }

    private static boolean positive(Long id) { return id != null && id > 0; }
    private static Long id(Object value) {
        if (value instanceof Number n) {
            try { return new java.math.BigDecimal(n.toString()).longValueExact(); }
            catch (ArithmeticException | NumberFormatException ignored) { return null; }
        }
        if (value instanceof String s && s.matches("[1-9][0-9]*")) {
            try { return Long.valueOf(s); } catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }
}
