package com.example.aquaflow.service.impl;

import com.example.aquaflow.entity.Address;
import com.example.aquaflow.mapper.AddressMapper;
import com.example.aquaflow.service.AddressService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class AddressServiceImpl implements AddressService {

    @Autowired
    private AddressMapper addressMapper;

    @Override
    @Transactional
    public void save(Address address) {
        address.setCreateTime(LocalDateTime.now());
        address.setUpdateTime(LocalDateTime.now());
        if (address.getIsDefault() != null && Integer.valueOf(1).equals(address.getIsDefault())) {
            addressMapper.clearDefault(address.getCustomerId());
        }
        addressMapper.insert(address);
    }

    @Override
    public List<Address> list(Long customerId, String keyword) {
        return addressMapper.list(customerId, keyword);
    }

    @Override
    public List<Address> listByStation(Long stationId, String keyword) {
        return addressMapper.listByStation(stationId, keyword);
    }

    @Override
    public Address getById(Long id) {
        Address address = addressMapper.getById(id);
        if (address == null) {
            throw new com.example.aquaflow.exception.ResourceNotFoundException("地址", id);
        }
        return address;
    }

    @Override
    @Transactional
    public void update(Address address) {
        // [2026-09-17 / v34] 楼层与电梯信息做**保留合并**。
        // 地址更新是整行覆盖（mapper 的 update 把 name/phone/…/floor/has_elevator 全写一遍），
        // 而旧版客户端不传这两个新字段 —— 直接写会把客户已填的楼层抹成 NULL，
        // 楼层费就再也算不出来（本仓「整行覆盖」事故的同一形状，已发生多次）。
        // 语义：**只允许改，不允许用 null 清空**；要清空得显式传 0 之外的约定值（当前不支持清空，
        // 因为 has_elevator 的三态里「未确认」是初始态而非用户会主动选择的状态）。
        Address existing = addressMapper.getById(address.getId());
        if (existing != null) {
            if (address.getFloor() == null) {
                address.setFloor(existing.getFloor());
            }
            if (address.getHasElevator() == null) {
                address.setHasElevator(existing.getHasElevator());
            }
        }

        address.setUpdateTime(LocalDateTime.now());
        if (address.getIsDefault() != null && Integer.valueOf(1).equals(address.getIsDefault())) {
            addressMapper.clearDefault(address.getCustomerId());
            addressMapper.setDefault(address.getId());
            address.setIsDefault(null);
        }
        addressMapper.update(address);
    }

    /**
     * 删除自己的地址。
     *
     * <p>[2026-09-16 修复] 原来调用 {@code addressMapper.delete(addressId, customerId)} 后**不看返回值**，
     * 而控制器又无条件 {@code Result.success()} —— 于是删别人的地址时：SQL 因为带了
     * {@code and customer_id = ?} 一行都没删（没有越权，这点是好的），但客户端收到的是**成功**。
     * 前端据此把该地址从列表里移除，服务端却原样保留：刷新后又冒出来，客服无从判断。
     * 现在 affected==0 直接抛业务异常（→ body code=1），让调用方知道"没删掉"。</p>
     */
    @Override
    public void delete(Long customerId, Long addressId) {
        Address target = addressMapper.getById(addressId);
        if (target == null || target.getCustomerId() == null || !target.getCustomerId().equals(customerId)) {
            throw new com.example.aquaflow.exception.BusinessException("地址不存在或无权删除");
        }
        int affected = addressMapper.delete(addressId, customerId);
        if (affected == 0) {
            throw new com.example.aquaflow.exception.BusinessException("地址删除失败，请刷新后重试");
        }
    }

    /**
     * 设为默认地址。
     *
     * <p>[2026-09-16 修复] 原实现先 {@code clearDefault(customerId)} 再
     * {@code setDefault(addressId)}，而后者<b>不校验归属</b> —— 传别人的地址 id 进来会：
     * ① 清空自己的默认标记；② 把**别人的**那条地址改成默认。既是跨客户的写入，又是"看着成功、
     * 自己却没有任何默认地址"的静默错乱。现在先验归属再动手。</p>
     */
    @Override
    @Transactional
    public void setDefault(Long customerId, Long addressId) {
        Address target = addressMapper.getById(addressId);
        if (target == null || target.getCustomerId() == null || !target.getCustomerId().equals(customerId)) {
            throw new com.example.aquaflow.exception.BusinessException("地址不存在或无权操作");
        }
        addressMapper.clearDefault(customerId);
        addressMapper.setDefault(addressId);
    }

    @Override
    public List<Map<String, Object>> countByTag() {
        return addressMapper.countByTag();
    }

    @Override
    public List<Map<String, Object>> countByTagByStation(Long stationId) {
        return addressMapper.countByTagByStation(stationId);
    }
}
