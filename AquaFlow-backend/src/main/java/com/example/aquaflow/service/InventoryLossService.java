package com.example.aquaflow.service;
import com.example.aquaflow.dto.InventoryLossDTO;
import com.example.aquaflow.entity.Inventory;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.InventoryMapper;
import com.example.aquaflow.util.AuthContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
/** 实盘亏损是实物事实；重新收紧预留，保留原订单需求，不宣称缺货部分已交付。 */
@Service @RequiredArgsConstructor
public class InventoryLossService {
    private final InventoryMapper mapper;
    private final InventoryReservationService reservations;
    private final InventoryService inventory;
    private final AuditLogService audit;
    private final AlertService alerts;
    private final BarrelBusinessPolicy policy;
    @Transactional public Map<String,Object> record(Long product,Long station,InventoryLossDTO dto) {
        if(!policy.isEnabled())throw new BusinessException("尚未开放独立盘亏处理，请联系站长核实原库存流程");
        Inventory current=mapper.getByStationAndProductForUpdate(station,product);
        if(current==null || !Objects.equals(current.getQuantity(),dto.getExpectedQuantity()))throw new BusinessException("库存已变化，请刷新实际库存后重新盘亏");
        if(dto.getTargetQuantity()>=current.getQuantity())throw new BusinessException("实盘亏损的目标数须低于当前库存，增加请走入库或盘点");
        List<Map<String,Object>> affected=reservations.reduceForPhysicalLoss(station,product,dto.getTargetQuantity());
        int delta=inventory.setStock(station,product,dto.getTargetQuantity(),"ADJUST",null,"[实盘亏损] "+dto.getNote());
        audit.log("Inventory","PHYSICAL_LOSS",String.valueOf(product),"水站 "+station+"；实物变动 "+delta+"；受影响订单 "+affected+"；"+dto.getNote(),null);
        if(!affected.isEmpty())alerts.stationFault(station,"WARN","InventoryLoss","实盘亏损导致订单缺货","商品 "+product+" 实物减少 "+(-delta)+"，受影响订单保留待补、延期协商或取消退款。","PRODUCT",product);
        return Map.of("delta",delta,"quantity",dto.getTargetQuantity(),"affectedOrders",affected);
    }
}
