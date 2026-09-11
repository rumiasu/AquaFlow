package com.example.aquaflow.service;

import com.example.aquaflow.entity.Customer;
import com.example.aquaflow.entity.CustomerStationConfig;
import com.example.aquaflow.vo.CustomerProfileVO;
import com.example.aquaflow.vo.CustomerStationAssetVO;
import com.example.aquaflow.vo.CustomerStationVO;

import java.util.List;
import java.util.Map;

public interface CustomerService {
    List<Customer> list(Long stationId);

    void save(Customer customer);

    Customer getById(Long id);

    void update(Customer customer);

    Map<String, Object> getCustomerStats(Long customerId);

    CustomerStationConfig getOfflinePaymentConfig(Long customerId, Long stationId);

    void updateOfflinePaymentConfig(Long customerId, Long stationId, Integer enabled);

    /** 站长视角的客户列表（含本站货到付款权限与统计） */
    List<CustomerStationVO> listStationCustomers(Long stationId);

    /** 站长视角的单个客户详情（含本站货到付款权限）；无权限或无记录返回 null */
    CustomerStationVO getStationCustomerDetail(Long customerId, Long stationId);

    /**
     * 客户画像（站长视角）：聚合该客户在本站的消费、资产、履约与行为数据。
     * 无权限返回 null。
     */
    CustomerProfileVO getCustomerProfile(Long customerId, Long stationId);

    /**
     * 客户在指定水站的资产（站长视角）：水桶 / 水票 / 押金三类。
     *
     * <p>资产按 {@code (customerId, stationId)} 隔离，只会返回该客户<b>在本站</b>的资产；
     * 客户不属于该水站时返回 null，由调用方转为"无权查看"。</p>
     */
    CustomerStationAssetVO getStationAssets(Long customerId, Long stationId);
}
