package com.example.aquaflow.service.impl;

import com.example.aquaflow.entity.Customer;
import com.example.aquaflow.entity.CustomerStationConfig;
import com.example.aquaflow.mapper.CustomerMapper;
import com.example.aquaflow.mapper.CustomerStationConfigMapper;
import com.example.aquaflow.service.CustomerService;
import com.example.aquaflow.vo.CustomerProfileVO;
import com.example.aquaflow.vo.CustomerStationVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class CustomerServiceImpl implements CustomerService {

    @Autowired
    private CustomerMapper customerMapper;

    @Autowired
    private CustomerStationConfigMapper customerStationConfigMapper;

    @Override
    public void update(Customer customer) {
        customer.setUpdateTime(LocalDateTime.now());
        customerMapper.update(customer);
    }

    @Override
    public Customer getById(Long id) {
        Customer customer = customerMapper.getById(id);
        if (customer == null) {
            throw new RuntimeException("客户不存在");
        }
        return customer;
    }

    @Override
    public void save(Customer customer) {
        customer.setCreateTime(LocalDateTime.now());
        customer.setUpdateTime(LocalDateTime.now());
        customerMapper.insert(customer);
    }

    @Override
    public List<Customer> list(Long stationId) {
        if (stationId != null) {
            return customerMapper.listByStationId(stationId);
        }
        return customerMapper.list();
    }

    @Override
    public Map<String, Object> getCustomerStats(Long customerId) {
        Customer customer = customerMapper.getById(customerId);
        if (customer == null) {
            throw new RuntimeException("客户不存在");
        }

        Map<String, Object> stats = new HashMap<>();
        stats.put("firstOrderTime", customer.getFirstOrderTime());
        stats.put("lastDeliveryTime", customer.getLastDeliveryTime());
        return stats;
    }

    @Override
    public CustomerStationConfig getOfflinePaymentConfig(Long customerId, Long stationId) {
        CustomerStationConfig config = customerStationConfigMapper.getByCustomerAndStation(customerId, stationId);
        if (config == null) {
            // 确保记录存在，默认关闭
            customerStationConfigMapper.ensureExists(customerId, stationId);
            config = customerStationConfigMapper.getByCustomerAndStation(customerId, stationId);
        }
        return config;
    }

    @Override
    public void updateOfflinePaymentConfig(Long customerId, Long stationId, Integer enabled) {
        customerStationConfigMapper.ensureExists(customerId, stationId);
        customerStationConfigMapper.updateOfflinePaymentEnabled(customerId, stationId, enabled);
    }

    @Override
    public List<CustomerStationVO> listStationCustomers(Long stationId) {
        if (stationId == null) {
            return new java.util.ArrayList<>();
        }
        List<CustomerStationVO> list = customerMapper.listStationCustomers(stationId);
        if (list != null) {
            for (CustomerStationVO vo : list) {
                vo.deriveProfileMeta();
            }
        }
        return list;
    }

    @Override
    public CustomerStationVO getStationCustomerDetail(Long customerId, Long stationId) {
        CustomerStationVO vo = customerMapper.getStationCustomer(customerId, stationId);
        if (vo != null) {
            vo.deriveProfileMeta();
        }
        return vo;
    }

    @Override
    public CustomerProfileVO getCustomerProfile(Long customerId, Long stationId) {
        CustomerStationVO base = customerMapper.getStationCustomer(customerId, stationId);
        if (base == null) {
            return null;
        }
        Customer c = customerMapper.getById(customerId);
        if (c == null) {
            return null;
        }

        CustomerProfileVO vo = new CustomerProfileVO();
        // 基础档案
        vo.setId(base.getId());
        vo.setName(base.getName());
        vo.setPhone(base.getPhone());
        vo.setCustomerType(base.getCustomerType());
        vo.setNote(base.getNote());
        vo.setTags(base.getTags());
        vo.setCreateTime(base.getCreateTime());
        vo.setFirstOrderTime(base.getFirstOrderTime());
        vo.setDefaultAddress(customerMapper.getDefaultAddress(customerId));

        // 消费画像
        vo.setTotalOrders(customerMapper.countCompletedOrders(customerId, stationId));
        vo.setTotalConsumption(customerMapper.sumConsumption(customerId, stationId));
        vo.setMonthOrders(customerMapper.countMonthOrders(customerId, stationId));
        vo.setMonthConsumption(customerMapper.sumMonthConsumption(customerId, stationId));
        vo.setLastOrderTime(customerMapper.getLastOrderTime(customerId, stationId));
        vo.setAvgCycleDays(c.getAvgCycleDays());

        // 资产
        vo.setDepositBalance(base.getDepositBalance());
        vo.setTicketBalance(customerMapper.getTicketBalance(customerId, stationId));
        vo.setOwedBarrels(customerMapper.getOwedBarrels(customerId, stationId));

        // 行为
        vo.setExceptionCount(customerMapper.countExceptions(customerId, stationId));
        vo.setFavoriteProducts(customerMapper.listFavoriteProducts(customerId, stationId));
        vo.setRecentOrders(decorateOrders(customerMapper.listRecentOrders(customerId, stationId)));

        // 权限
        vo.setCodEnabled(base.getCodEnabled());
        vo.setOfflinePaymentEnabled(base.getOfflinePaymentEnabled());
        return vo;
    }

    /** 给订单 map 补上后端派生的状态文案，前端只渲染 */
    private List<Map<String, Object>> decorateOrders(List<Map<String, Object>> orders) {
        if (orders == null) {
            return new java.util.ArrayList<>();
        }
        for (Map<String, Object> m : orders) {
            Object st = m.get("status");
            if (st instanceof Number) {
                m.put("statusText", com.example.aquaflow.constant.OrderStatus.textOf(((Number) st).intValue()));
            }
        }
        return orders;
    }
}
