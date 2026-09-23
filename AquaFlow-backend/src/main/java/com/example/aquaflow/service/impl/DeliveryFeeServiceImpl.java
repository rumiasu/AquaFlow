package com.example.aquaflow.service.impl;

import com.example.aquaflow.entity.Address;
import com.example.aquaflow.entity.Station;
import com.example.aquaflow.entity.StationDeliveryConfig;
import com.example.aquaflow.mapper.AddressMapper;
import com.example.aquaflow.mapper.StationDeliveryConfigMapper;
import com.example.aquaflow.mapper.StationMapper;
import com.example.aquaflow.service.DeliveryFeeService;
import com.example.aquaflow.util.DeliveryFeeUtil;
import com.example.aquaflow.util.GeoUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 配送计费服务实现（v35）。见 {@link DeliveryFeeService} 与 {@code docs/design/17}。
 *
 * <p>本类只做"取数 → 调纯函数"，<b>不含任何计费规则</b> —— 规则全在
 * {@link DeliveryFeeUtil}，那样才能用单元测试覆盖、也才不会在调用侧长出第二套口径。</p>
 */
@Service
public class DeliveryFeeServiceImpl implements DeliveryFeeService {

    @Autowired
    private StationDeliveryConfigMapper stationDeliveryConfigMapper;

    @Autowired
    private StationMapper stationMapper;

    @Autowired
    private AddressMapper addressMapper;

    /**
     * 客户特权（v40）：目前只用来读「免起送门槛」。
     *
     * <p>放在这里而不是放进 {@code DeliveryFeeUtil}：那个类是纯规则、不做任何 IO，
     * 特权是数据不是规则（见该文件头注释）。</p>
     */
    @Autowired
    private com.example.aquaflow.mapper.CustomerPrivilegeMapper customerPrivilegeMapper;

    @Override
    public DeliveryFeeUtil.FeeResult calcForOrder(Long customerId, Long stationId, Long addressId,
                                                  int totalBuckets, BigDecimal waterAmount) {
        // 「没配」与「配置全 0」行为完全一致 —— 用 defaults 兜底，业务侧不需要判 null。
        // 本表是后加的，存量水站全都没有配置行，它们必须与升级前一个字不差。
        StationDeliveryConfig config = stationId == null
                ? StationDeliveryConfig.defaults(null)
                : stationDeliveryConfigMapper.getByStationId(stationId);
        if (config == null) {
            config = StationDeliveryConfig.defaults(stationId);
        }

        Double distanceMeters = null;
        Integer floor = null;
        Integer hasElevator = null;

        if (addressId != null) {
            Address addr = addressMapper.getById(addressId);
            if (addr != null) {
                floor = addr.getFloor();
                hasElevator = addr.getHasElevator();
                // 站点坐标可能为 NULL（站长没选点），地址坐标也可能为 NULL（地图解析失败）——
                // GeoUtil 此时返回 null，DeliveryFeeUtil 据此跳过范围校验并放行。
                // 「没有数据」绝不能当成「超出范围」。
                if (stationId != null) {
                    Station station = stationMapper.getById(stationId);
                    if (station != null) {
                        distanceMeters = GeoUtil.distanceMeters(
                                station.getLat(), station.getLng(), addr.getLat(), addr.getLng());
                    }
                }
            }
        }

        return DeliveryFeeUtil.compute(config, totalBuckets, waterAmount, distanceMeters, floor, hasElevator,
                hasNoMinOrderPrivilege(customerId, stationId));
    }

    /**
     * 该客户在本站是否被授予「免起送门槛」（v40）。
     *
     * <p>按 (customer, station) 查 —— 特权与水票/押金/桶账同一隔离维度，
     * A 站给的不在 B 站生效。客户或站点为空时一律按"没有特权"处理（宁严不宽）。</p>
     */
    private boolean hasNoMinOrderPrivilege(Long customerId, Long stationId) {
        if (customerId == null || stationId == null) {
            return false;
        }
        return customerPrivilegeMapper.countByType(customerId, stationId,
                com.example.aquaflow.constant.PrivilegeType.NO_MIN_ORDER) > 0;
    }
}
