package com.example.aquaflow.service.impl;

import com.example.aquaflow.entity.Address;
import com.example.aquaflow.mapper.AddressMapper;
import com.example.aquaflow.service.AddressService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;
import com.example.aquaflow.exception.BusinessException;
import java.math.BigDecimal;
import java.sql.SQLException;

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
        lockCustomer(address.getCustomerId());
        validateDefault(address.getIsDefault());
        validateCoordinates(address);
        if (address.getIsDefault() == null) address.setIsDefault(0);
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
        Address target = addressMapper.getById(address.getId());
        if (target == null) throw new BusinessException("地址不存在");
        lockCustomer(target.getCustomerId());
        Address existing = addressMapper.getByIdForUpdate(address.getId());
        if (existing == null) throw new BusinessException("地址不存在");
        // 2026-10-31：默认标记只能改该地址真实所属客户，不能信任编辑请求里的customerId。
        address.setCustomerId(existing.getCustomerId());
        validateDefault(address.getIsDefault());
        validateCoordinates(address);
        if (address.getIsDefault() == null) address.setIsDefault(Integer.valueOf(1).equals(existing.getIsDefault()) ? 1 : 0);
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
        }
        // 2026-10-31：原先设默认后把isDefault=null再整行写回，会丢失默认。现在保留明确标记同次写入。
        if (addressMapper.update(address) != 1) throw new BusinessException("地址保存失败，请刷新后重试");
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
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long customerId, Long addressId) {
        lockCustomer(customerId);
        Address target = addressMapper.getByIdForUpdate(addressId);
        if (target == null || target.getCustomerId() == null || !target.getCustomerId().equals(customerId)) {
            throw new com.example.aquaflow.exception.BusinessException("地址不存在或无权删除");
        }
        if (addressMapper.countOrderReferences(addressId) > 0) throw referencedAddress();
        int affected;
        try { affected = addressMapper.delete(addressId, customerId); }
        catch (DataIntegrityViolationException failure) {
            // 预查之后若有其他写入口新建引用，FK仍是最后防线；只转换真实FK拒删，其他错误原样抛出。
            for (Throwable cause = failure; cause != null; cause = cause.getCause())
                if (cause instanceof SQLException sql && sql.getErrorCode() == 1451) throw referencedAddress();
            throw failure;
        }
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
        lockCustomer(customerId);
        Address target = addressMapper.getByIdForUpdate(addressId);
        if (target == null || target.getCustomerId() == null || !target.getCustomerId().equals(customerId)) {
            throw new com.example.aquaflow.exception.BusinessException("地址不存在或无权操作");
        }
        addressMapper.clearDefault(customerId);
        if (addressMapper.setDefault(addressId, customerId) != 1) throw new BusinessException("默认地址设置失败，请刷新后重试");
    }

    private void lockCustomer(Long customerId) {
        if (customerId == null || addressMapper.lockCustomer(customerId) == null) throw new BusinessException("客户不存在");
    }

    private static void validateDefault(Integer value) {
        if (value != null && value != 0 && value != 1) throw new BusinessException("默认地址标记无效");
    }

    private static void validateCoordinates(Address address) {
        BigDecimal lat = address.getLat(), lng = address.getLng();
        if (lat == null && lng == null) return;
        if (lat == null || lng == null) throw new BusinessException("地址经纬度须同时填写或同时留空");
        if (lat.compareTo(BigDecimal.valueOf(-90)) < 0 || lat.compareTo(BigDecimal.valueOf(90)) > 0
                || lng.compareTo(BigDecimal.valueOf(-180)) < 0 || lng.compareTo(BigDecimal.valueOf(180)) > 0)
            throw new BusinessException("地址坐标超出有效范围");
    }

    private static BusinessException referencedAddress() {
        return new BusinessException("该地址已被订单使用，暂不能删除；可编辑原地址或新增地址");
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
