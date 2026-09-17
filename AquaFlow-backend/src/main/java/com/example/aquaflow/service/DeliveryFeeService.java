package com.example.aquaflow.service;

import com.example.aquaflow.util.DeliveryFeeUtil;

/**
 * 配送计费服务：把「查配置 + 取站点/地址坐标 + 算距离」这些 IO 收在一处，
 * 再交给纯函数 {@link DeliveryFeeUtil#compute} 算钱。2026-09-17 新增（v35）。
 *
 * <p><b>为什么要有这一层</b>：规格要求报价与下单"同口径且只实现一次"。
 * 让两个调用方各自去查配置、各自取坐标、各自算距离，就是把口径分叉点搬到了两处 ——
 * 本仓出过"计价双轨"事故（结算页与订单金额不一致引发客诉，见 {@code PriceUtil} 文件头）。
 * 有了本服务，{@code PaymentServiceImpl.quote} 与 {@code OrderServiceImpl.createOrder}
 * 只需各调一行 {@link #calcForOrder}，且传的是同样的入参。</p>
 */
public interface DeliveryFeeService {

    /**
     * 按「客户 + 站 + 收货地址 + 本单桶数 + 水费」算出配送费与楼层费。
     *
     * @param customerId   客户（用于读该客户的特权，如「免起送门槛」v40）。可空 = 无特权
     * @param stationId    服务水站（配置与站点坐标都按它取）
     * @param addressId    收货地址；{@code null} = 未知 → <b>距离按"算不出来"处理</b>（不收远程费、不拦单）
     * @param totalBuckets 本单桶装水桶数
     * @param waterAmount  水费金额（不含押金与运费；起送量与免运费的金额门槛都按这个口径比）
     */
    DeliveryFeeUtil.FeeResult calcForOrder(Long customerId, Long stationId, Long addressId, int totalBuckets,
                                           java.math.BigDecimal waterAmount);
}
