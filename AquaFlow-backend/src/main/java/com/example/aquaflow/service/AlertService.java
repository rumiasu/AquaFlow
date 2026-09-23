package com.example.aquaflow.service;

/**
 * 分级告警：按「谁该处理」投递，见 {@code constant/AlertType.java} 的产品口径。
 *
 * <p>两个方法分别对应两类故障，<b>调用方必须自己选对</b>：</p>
 * <ul>
 *   <li>{@link #systemFault}：系统故障 → 系统管理员（对账不平、补偿失败、未预期 500…）</li>
 *   <li>{@link #stationFault}：运营故障 → 该水站站长（桶异常待处置、补偿已执行…）</li>
 * </ul>
 *
 * <p>投递语义：<b>先落库（{@code alert_log}）再尝试外部渠道</b>。外部渠道（系统告警 webhook /
 * 站长的微信订阅消息）没配就只落库 + 日志，投递状态记 {@code LOGGED}——
 * 绝不允许"渠道没配"变成"这条告警不存在"。</p>
 */
public interface AlertService {

    /**
     * 系统故障告警（收件人：系统管理员）。
     *
     * @param relatedType 关联对象类型，可空
     * @param relatedId   关联对象 id，可空
     */
    void systemFault(String source, String title, String content, String relatedType, Long relatedId);

    /**
     * 运营故障告警（收件人：该水站站长）。
     *
     * @param stationId 必填，收件水站
     * @param level     ERROR / WARN / INFO
     */
    void stationFault(Long stationId, String level, String source, String title, String content,
                      String relatedType, Long relatedId);
}
