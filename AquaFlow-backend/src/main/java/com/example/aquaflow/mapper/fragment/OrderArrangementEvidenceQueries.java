package com.example.aquaflow.mapper.fragment;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Map;

/** Read-only evidence for order assignment and return decisions. */
public interface OrderArrangementEvidenceQueries {
    /** 服务端操作凭据，只用于指定退回的历史状态核实与只读安排提示。 */
    @Select("select id, action, detail from audit_log where module='ORDER' and target=concat('order:',#{orderId}) "
            + "and action='DIRECTED_RETURN' order by id desc limit 1")
    Map<String, Object> latestDirectedReturnEvent(@Param("orderId") Long orderId);

    /**
     * 站内退回申请的操作凭据。新申请在 detail 里带 sourceStatus/transferId；
     * 旧申请没有这两个字段，只能按申请前的接单/分配审计核实，证据不足不得猜。
     */
    @Select("select id, action, detail, create_time eventTime from audit_log where module='ORDER' and target=concat('order:',#{orderId}) "
            + "and action='RETURN_TO_STATION' order by id desc limit 1")
    Map<String, Object> latestStaffReturnEvent(@Param("orderId") Long orderId);

    /** 申请创建后是否又发生接单/分配/改派等履约变化；有则旧申请不得再审批。 */
    @Select("select id, action, detail, create_time eventTime from audit_log where module='ORDER' and target=concat('order:',#{orderId}) "
            + "and create_time >= #{since} and action in ('ACCEPT','CLAIM_POOL','ASSIGN','DISPATCH','OUTSOURCE_DIRECT',"
            + "'OUTSOURCE','CANCEL_DISPATCH','DIRECTED_RETURN_APPROVE','DIRECTED_RETURN_REJECT','STATION_REJECT') "
            + "order by id desc limit 1")
    Map<String, Object> latestFulfillmentEventSince(@Param("orderId") Long orderId,
                                                    @Param("since") java.time.LocalDateTime since);

    @Select("select id, action, detail from audit_log where module='ORDER' and target=concat('order:',#{orderId}) "
            + "and id < #{eventId} and action in ('ACCEPT','CLAIM_POOL','ASSIGN','DISPATCH','OUTSOURCE_DIRECT',"
            + "'OUTSOURCE','STATION_REJECT','RETURN_TO_STATION','RETURN_APPROVE','RETURN_REJECT','CANCEL_DISPATCH',"
            + "'DIRECTED_RETURN_APPROVE','DIRECTED_RETURN_REJECT','COMPLETE') order by id desc limit 1")
    Map<String, Object> orderStateEventBefore(@Param("orderId") Long orderId, @Param("eventId") Long eventId);

    @Select("select id, action, detail from audit_log where module='ORDER' and target=concat('order:',#{orderId}) "
            + "and action in ('DIRECTED_RETURN','DIRECTED_RETURN_APPROVE','DIRECTED_RETURN_REJECT','CANCEL_DISPATCH',"
            + "'ACCEPT','CLAIM_POOL','ASSIGN','DISPATCH','OUTSOURCE_DIRECT','OUTSOURCE','STATION_REJECT',"
            + "'RETURN_TO_STATION','RETURN_APPROVE','RETURN_REJECT','COMPLETE') order by id desc limit 1")
    Map<String, Object> latestOrderArrangementEvent(@Param("orderId") Long orderId);

}
