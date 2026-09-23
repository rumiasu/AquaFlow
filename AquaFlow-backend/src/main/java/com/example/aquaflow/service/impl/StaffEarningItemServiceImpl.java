package com.example.aquaflow.service.impl;

import com.example.aquaflow.constant.EarningItemDirection;
import com.example.aquaflow.dto.PayrollDTO;
import com.example.aquaflow.entity.StaffEarningItem;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.StaffEarningItemMapper;
import com.example.aquaflow.service.StaffEarningItemService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 自定义工资条目实现（v44）。见 {@link StaffEarningItemService} 与 {@code docs/design/18} §5。
 *
 * <p>⚠️ 所有按 id 的写操作都<b>带 station_id 并检查受影响行数</b>：
 * id 是客户端可编造的，不验归属就会改到/删掉别站的条目（AGENTS §8.20）。
 * 重名判定以唯一键 {@code uk_earning_item_name} 为最终依据，这里先查一次只是为了给出可读文案 ——
 * 先查后插之间存在竞态，所以 insert 还要兜 {@link DuplicateKeyException}。</p>
 */
@Service
@Slf4j
public class StaffEarningItemServiceImpl implements StaffEarningItemService {

    /** 条目名长度上限（与 staff_earning_item.name varchar(20) 对齐） */
    private static final int NAME_MAX = 20;

    @Autowired
    private StaffEarningItemMapper itemMapper;

    @Override
    public List<StaffEarningItem> list(Long stationId, boolean includeDisabled) {
        return includeDisabled ? itemMapper.listByStation(stationId) : itemMapper.listEnabled(stationId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(Long stationId, PayrollDTO.EarningItem body) {
        String name = normalizeName(body);
        Integer direction = requireDirection(body);

        if (itemMapper.getByName(stationId, name) != null) {
            throw new BusinessException("已存在同名条目「" + name + "」");
        }
        StaffEarningItem item = new StaffEarningItem();
        item.setStationId(stationId);           // 站点一律取登录态，不信任请求参数
        item.setName(name);
        item.setDirection(direction);
        item.setStatus(1);
        item.setSort(body.getSort() != null ? body.getSort() : 0);
        try {
            itemMapper.insert(item);
        } catch (DuplicateKeyException e) {
            // 并发下的兜底：唯一键才是最终依据（预检只是为了让正常路径有好文案）
            throw new BusinessException("已存在同名条目「" + name + "」");
        }
        log.info("[v44] 站长新建工资条目: stationId={}, id={}, name={}, direction={}",
                stationId, item.getId(), name, direction);
        return item.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long stationId, Long id, PayrollDTO.EarningItem body) {
        String name = normalizeName(body);
        Integer direction = requireDirection(body);

        StaffEarningItem exist = itemMapper.getByName(stationId, name);
        if (exist != null && !exist.getId().equals(id)) {
            throw new BusinessException("已存在同名条目「" + name + "」");
        }
        StaffEarningItem item = new StaffEarningItem();
        item.setId(id);
        item.setStationId(stationId);
        item.setName(name);
        item.setDirection(direction);
        item.setSort(body.getSort() != null ? body.getSort() : 0);
        try {
            if (itemMapper.update(item) == 0) {
                throw new BusinessException("条目不存在或不属于本站");
            }
        } catch (DuplicateKeyException e) {
            throw new BusinessException("已存在同名条目「" + name + "」");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setStatus(Long stationId, Long id, Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            throw new BusinessException("状态只能是 1（启用）或 0（停用）");
        }
        if (itemMapper.updateStatus(id, stationId, status) == 0) {
            throw new BusinessException("条目不存在或不属于本站");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long stationId, Long id) {
        int used = itemMapper.countUsage(stationId, id);
        if (used > 0) {
            // 条目是历史工资的标签：删掉它，那些流水就变成"无条目的人工调整"，再也说不清是什么钱
            throw new BusinessException("该条目已被 " + used + " 条工资流水使用，只能停用，不能删除");
        }
        if (itemMapper.delete(id, stationId) == 0) {
            throw new BusinessException("条目不存在或不属于本站");
        }
    }

    /** 名称规范化：去首尾空格 + 长度校验（空名字的条目在界面上就是一排空白按钮） */
    private String normalizeName(PayrollDTO.EarningItem body) {
        String name = body.getName() == null ? "" : body.getName().trim();
        if (name.isEmpty()) {
            throw new BusinessException("条目名称不能为空");
        }
        if (name.length() > NAME_MAX) {
            throw new BusinessException("条目名称不能超过 " + NAME_MAX + " 个字");
        }
        return name;
    }

    private Integer requireDirection(PayrollDTO.EarningItem body) {
        if (!EarningItemDirection.isValid(body.getDirection())) {
            throw new BusinessException("方向只能是 1（加项）或 2（扣项）");
        }
        return body.getDirection();
    }
}
