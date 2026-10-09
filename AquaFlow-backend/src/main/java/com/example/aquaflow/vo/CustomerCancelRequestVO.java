package com.example.aquaflow.vo;

import com.example.aquaflow.entity.OrderTransfer;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.Map;

/** 客户只读取消申请结论；申请、订单取消和实际退款分别表达。 */
@Data
public class CustomerCancelRequestVO {
    private Long requestId;
    private String status;
    private String statusText;
    private String resultNote;
    private LocalDateTime submittedTime;
    private LocalDateTime handledTime;
    public static CustomerCancelRequestVO of(OrderTransfer request,Map<String,Object> result) {
        CustomerCancelRequestVO view=new CustomerCancelRequestVO();
        view.requestId=request.getId();view.status=request.getStatus();view.submittedTime=request.getCreateTime();
        view.handledTime=OrderTransfer.STATUS_PENDING.equals(view.status)?null:request.getUpdateTime();
        view.statusText=switch(view.status) {
            case OrderTransfer.STATUS_PENDING -> "取消申请待水站处理";
            case OrderTransfer.STATUS_APPROVED -> "取消申请已获同意";
            case OrderTransfer.STATUS_REJECTED -> "取消申请未获同意";
            case OrderTransfer.STATUS_CANCELLED -> "取消申请已撤回";
            default -> "申请结果待核实";
        };
        view.resultNote=result!=null?String.valueOf(result.get("resultNote")):
                OrderTransfer.STATUS_PENDING.equals(view.status)?"提交申请不代表订单已取消；水站处理结果会显示在这里。":
                OrderTransfer.STATUS_APPROVED.equals(view.status)?"申请已获同意；订单及退款进度请以实际办理结果为准。":"如对处理结果有异议，可联系水站核实。";
        return view;
    }
}
