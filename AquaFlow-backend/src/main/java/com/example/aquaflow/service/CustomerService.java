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

    /** 设置客户在本站的货到付款开关（唯一控制点是它；没有站点级总闸）。 */
    void updateOfflinePaymentConfig(Long customerId, Long stationId, Integer enabled);

    /**
     * 站长开通货到付款时弹窗要用的**全部依据**（v48）：当前配置 + 该客户在本站的欠款/逾期 +
     * 历史订单数 + 当前能不能用（不能用时给出原因）。
     *
     * <p>为什么放在服务端一次算：站长要"在设置的那一刻"就看见"这个客户欠着多少、为什么不能用"，
     * 而不是开完通再被下单拒绝。原因文案来自唯一判据（{@code PaymentService.offlinePaymentBlockReason}），
     * 本方法不自己拼一套。</p>
     */
    Map<String, Object> offlinePaymentSummary(Long customerId, Long stationId);

    /**
     * 站长视角的客户列表（含本站货到付款权限与统计）。
     *
     * @param keyword 姓名 / 电话 / <b>地址</b>关键字，支持缩写与中英数字混用
     *                （口径与算法见 {@code util/CustomerSearchMatcher}）；
     *                {@code null}/空 = 不过滤。带关键字时结果按相关性降序、最多 50 条。
     */
    List<CustomerStationVO> listStationCustomers(Long stationId, String keyword);

    /**
     * 站长端客户搜索的<b>统一实现</b>：本站客户（绑定 ∪ 本站订单，含"已建档但没下过单"的新客户）
     * 按姓名 / 电话 / 地址的相关性排序。
     *
     * <p><b>两个入口共用它</b>：{@code GET /api/customers}（客户列表，走上面那个返回 VO 的重载）
     * 与 {@code GET /api/manager/order-assist/customers}（代客下单选择器）。
     * 打分逻辑只允许存在于 {@code util/CustomerSearchMatcher} —— 再写一份就是"计价双轨"的同形事故。</p>
     *
     * @param keyword 关键字；{@code null}/空 = 不筛（返回最近建档的若干条，首屏依赖这个语义）
     * @return 每项含 {@code id/name/phone/customerType/addressText} 五个键
     */
    List<Map<String, Object>> searchStationCustomers(Long stationId, String keyword);

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
