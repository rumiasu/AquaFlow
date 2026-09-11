package com.example.aquaflow.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 客户过占桶（按 customer × station × product），对应表 customer_barrel_over。
 * 取代原 customer_owed_barrel（站点级、不分商品、且只增不减）。
 *
 * <h3>定义</h3>
 * <pre>over = 实际占用 − 桶权益</pre>
 * 实际占用不落表，由本表与权益共同派生：<pre>占用 = 权益 + over</pre>
 *
 * <h3>三种取值都是合法状态</h3>
 * <ul>
 *   <li><b>&gt; 0</b>　欠桶：占着的桶超出权益（常规）</li>
 *   <li><b>= 0</b>　正常</li>
 *   <li><b>&lt; 0</b>　多还桶 / 水站暂存：顾客把桶还多了，手上桶少于权益，
 *       等价于「水站欠顾客 N 个桶」。<b>这不是脏数据、不是负债、不是负权益。</b></li>
 * </ul>
 *
 * <h3>写代码时四条不可违背的约束</h3>
 * <ol>
 *   <li><b>禁止任何 over &gt;= 0 形式的拦截校验。</b>负值是合法状态。</li>
 *   <li><b>over &lt; 0 不产生任何退款。</b>唯一能退款的是 lot 里剩余的权益。</li>
 *   <li><b>正常还桶绝不扣权益。</b>普通换桶（还一空桶、拿一满桶）权益恒定不变；
 *       纯还桶只动 over。只有「终止权益」的退桶才减权益并退款。</li>
 *   <li><b>严格按产品隔离。</b>A 水的多还桶不能抵 B 水的欠桶。</li>
 * </ol>
 *
 * <p>推论：因为占用 ≥ 0 恒成立，所以 over ≥ −权益 自动成立，
 * 这个界是物理的，<b>不需要任何代码去 clamp</b>。</p>
 */
@Data
public class CustomerBarrelOver {

    private Long id;

    private Long customerId;
    private Long stationId;
    private Long productId;

    /**
     * 过占桶数：占用 − 权益。
     * 正数=欠桶，负数=多还桶（水站暂存）。两者均为合法状态。
     */
    private Integer overQty;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
