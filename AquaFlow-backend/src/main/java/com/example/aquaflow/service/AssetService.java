package com.example.aquaflow.service;

public interface AssetService {

    /**
     * 判断客户在指定水站是否已有资产（水票、桶、押金任一存在即为 true）
     * 用于首次资产业务判断
     */
    boolean hasStationAsset(Long customerId, Long stationId);

    /**
     * 判断客户在水站**是否已经有桶**（桶权益 `customer_barrel_asset.quantity > 0`）。
     *
     * <p>⚠️ 与 {@link #hasStationAsset} 的差别只有**判据范围**，别互相替代：</p>
     * <ul>
     *   <li>{@link #hasStationAsset} 把水票账户与押金账户也算进来 —— 它服务的是"**首次资产业务告知**"
     *       （客户第一次在这儿花钱，报价页要说明押金与归属站），票/押金/桶任一为有就不算新客户；</li>
     *   <li>本方法**只看桶** —— 它服务的是「站点第一笔买桶订单」（{@code orders.first_barrel_order}）：
     *       客户手上到底有没有空桶可还。**先买过水票的客户**用上面那条会判成"老客户"，
     *       于是第一次买桶的订单要核对回桶、回桶数还会被默认填成"送出多少回多少"，
     *       而他手里一个空桶都没有（2026-09-26 产品口径：「第一次送达桶确实不需要回收，
     *       把第一次桶送达时的默认回桶值取消掉」）。</li>
     * </ul>
     */
    boolean hasBarrelAsset(Long customerId, Long stationId);
}